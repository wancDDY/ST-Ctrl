#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <future>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/resource.h>
#include <sys/file.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "TavernNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_threadMutex;
static std::thread g_nodeThread;
static std::atomic<bool> g_nodeRunning(false);
static std::atomic<bool> g_pipeClosed(false);  // true if Node stdout pipe closed unexpectedly
static JavaVM* g_jvm = nullptr;               // cached JVM reference for log forwarding
static int g_lockFd = -1;                       // cross-process file lock to prevent dual-start
static std::string g_logPath;                   // filesDir/tavern-server.log for cross-process log viewer

// Function pointer type for node::Start(int argc, char** argv)
typedef int (*NodeStartFunc)(int, char**);

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeStartNode(
    JNIEnv *env,
    jobject thiz,
    jstring jDataDir,
    jstring jEntryPoint,
    jint port,
    jstring jLibDir,
    jstring jNodeBinDir,
    jint niceValue,
    jint uvPoolSize,
    jint maxOldSpaceMb) {

    const char* dataDirRaw = env->GetStringUTFChars(jDataDir, nullptr);
    const char* entryRaw = env->GetStringUTFChars(jEntryPoint, nullptr);

    std::string dataDir(dataDirRaw);
    std::string entryPoint(entryRaw);

    // dataDir = .../files/core → log file lives one level up in filesDir,
    // shared by main + :node processes so the in-app log viewer can read it.
    g_logPath = dataDir + "/../tavern-server.log";

    env->ReleaseStringUTFChars(jDataDir, dataDirRaw);
    env->ReleaseStringUTFChars(jEntryPoint, entryRaw);

    const char* libDirRaw = env->GetStringUTFChars(jLibDir, nullptr);
    std::string libDir(libDirRaw);
    env->ReleaseStringUTFChars(jLibDir, libDirRaw);

    // Cache JVM for log forwarding in reader thread
    env->GetJavaVM(&g_jvm);

    // ── Cross-process file lock ──
    // Only one process may run Node.js at a time.
    // If :node process already holds the lock, main process is rejected,
    // and vice versa. This prevents two libnode.so instances from
    // competing for the same port.
    std::string lockPath = dataDir + "/node.lock";
    g_lockFd = open(lockPath.c_str(), O_CREAT | O_RDWR, 0600);
    if (g_lockFd >= 0) {
        if (flock(g_lockFd, LOCK_EX | LOCK_NB) != 0) {
            LOGW("Node already running in another process (lock held: %s)", strerror(errno));
            close(g_lockFd);
            g_lockFd = -1;
            return JNI_TRUE;  // another instance is running — treat as success
        }
    } else {
        LOGW("Cannot create lock file %s: %s (continuing without lock)", lockPath.c_str(), strerror(errno));
    }

    LOGI("Starting node (embedded): dir=%s entry=%s port=%d lib=%s",
         dataDir.c_str(), entryPoint.c_str(), port, libDir.c_str());

    // Atomic check-and-set: prevent double start
    bool expected = false;
    if (!g_nodeRunning.compare_exchange_strong(expected, true)) {
        LOGE("Node is already running");
        return JNI_FALSE;
    }
    g_pipeClosed.store(false);  // reset crash flag for new run

    // Join previous thread if present (safety net)
    {
        std::lock_guard<std::mutex> lock(g_threadMutex);
        if (g_nodeThread.joinable()) {
            g_nodeThread.join();
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_threadMutex);
        g_nodeThread = std::thread([dataDir, entryPoint, port, libDir, niceValue, uvPoolSize, maxOldSpaceMb]() {
        // Set only this thread's priority (gettid() targets the calling thread)
        if (niceValue > 0) {
            if (setpriority(PRIO_PROCESS, gettid(), niceValue) != 0) {
                LOGI("setpriority failed for nice=%d (non-critical)", niceValue);
            } else {
                LOGI("Node thread priority lowered: nice=%d", niceValue);
            }
        }

        // Change to the server directory
        if (chdir(dataDir.c_str()) != 0) {
            LOGE("chdir failed: %s (errno=%d)", dataDir.c_str(), errno);
            g_nodeRunning.store(false);
            return;
        }

        // Set PORT env for the server
        setenv("PORT", std::to_string(port).c_str(), 1);

        // Load libnode.so from the native library directory
        std::string libnodePath = libDir + "/libnode.so";
        LOGI("Loading libnode.so from: %s", libnodePath.c_str());

        void* handle = dlopen(libnodePath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            LOGE("dlopen failed: %s", dlerror());
            g_nodeRunning.store(false);
            return;
        }

        // Find node::Start(int, char**)
        NodeStartFunc nodeStart = (NodeStartFunc)dlsym(handle, "_ZN4node5StartEiPPc");
        if (!nodeStart) {
            nodeStart = (NodeStartFunc)dlsym(handle, "_ZN4node5StartEiPKc");
        }
        if (!nodeStart) {
            LOGE("dlsym failed: %s", dlerror());
            dlclose(handle);
            g_nodeRunning.store(false);
            return;
        }

        // Redirect stdout/stderr to a pipe so we can log Node.js output.
        // Save original fds so we can restore them after node::Start returns.
        int savedStdout = dup(STDOUT_FILENO);
        int savedStderr = dup(STDERR_FILENO);
        int pipefd[2];
        if (pipe(pipefd) == 0) {
            dup2(pipefd[1], STDOUT_FILENO);
            dup2(pipefd[1], STDERR_FILENO);
            close(pipefd[1]);

            // Reader thread: forward pipe output to logcat + tavern-server.log.
            // The log file lives in filesDir (shared with main process) so the
            // in-app log viewer can read Node output across processes — a direct
            // JNI call to TavernLog would only mutate the :node process's memory.
            std::thread reader([pipefd_read = pipefd[0]]() {
                int logFd = -1;
                if (!g_logPath.empty()) {
                    logFd = open(g_logPath.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0640);
                }
                char buf[1024];
                ssize_t n;
                while ((n = read(pipefd_read, buf, sizeof(buf) - 1)) > 0) {
                    buf[n] = '\0';
                    char* end = buf + n - 1;
                    while (end >= buf && (*end == '\n' || *end == '\r')) *(end--) = '\0';
                    if (end >= buf) {
                        LOGI("[node] %s", buf);
                        if (logFd >= 0) {
                            std::string line(buf);
                            line += "\n";
                            if (write(logFd, line.c_str(), line.size()) < 0) {
                                // ignore transient write errors
                            }
                        }
                    }
                }
                if (logFd >= 0) close(logFd);
                // Pipe closed: if Node hasn't exited normally, it crashed.
                // Use acquire semantics for the reader to see the release-store from main thread.
                if (g_nodeRunning.load(std::memory_order_acquire)) {
                    LOGE("Node stdout pipe closed unexpectedly — process may have crashed");
                    g_pipeClosed.store(true);
                }
                close(pipefd_read);
            });
            reader.detach();
        }

        // Limit libuv thread pool size for non-FULL modes
        if (uvPoolSize < 4) {
            setenv("UV_THREADPOOL_SIZE", std::to_string(uvPoolSize).c_str(), 1);
            LOGI("UV_THREADPOOL_SIZE=%d", uvPoolSize);
        }

        // Limit V8 heap via env var (safer than argv — doesn't change argc)
        setenv("NODE_OPTIONS", ("--max-old-space-size=" + std::to_string(maxOldSpaceMb)).c_str(), 1);
        LOGI("Calling node::Start nice=%d pool=%d heap=%dMB entry=%s",
             niceValue, uvPoolSize, maxOldSpaceMb, entryPoint.c_str());

        // Build arguments for node::Start — use strdup for mutable copies (Node may write argv)
        std::string portArg = "--port=" + std::to_string(port);
        char* argNode = strdup("node");
        char* argHarmony = strdup("--harmony");
        char* argReport = strdup("--report-on-fatalerror");
        char* argEntry = strdup(entryPoint.c_str());
        char* argPort = strdup(portArg.c_str());
        char* argv[] = { argNode, argHarmony, argReport, argEntry, argPort, nullptr };
        int argc = 5;
        int ret = nodeStart(argc, argv);
        free(argNode); free(argHarmony); free(argReport); free(argEntry); free(argPort);
        LOGI("Node exited: %d", ret);

        dlclose(handle);

        // Signal normal exit with release semantics so the reader thread's
        // acquire-load sees this update before the pipe is closed.
        g_nodeRunning.store(false, std::memory_order_release);

        // Close the redirect fds so the reader thread receives EOF.
        // Restore original stdout/stderr that was saved before the redirect.
        if (savedStdout >= 0) {
            dup2(savedStdout, STDOUT_FILENO);
            close(savedStdout);
        }
        if (savedStderr >= 0) {
            dup2(savedStderr, STDERR_FILENO);
            close(savedStderr);
        }
    });
    } // lock released

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeStopNode(JNIEnv *env, jobject thiz) {
    LOGI("Stopping node (flag only — Node thread cannot be joined safely)");
    g_nodeRunning.store(false);
    g_pipeClosed.store(true);

    // Detach the old thread so a subsequent start won't block trying to join it.
    {
        std::lock_guard<std::mutex> lock(g_threadMutex);
        if (g_nodeThread.joinable()) {
            g_nodeThread.detach();
        }
    }

    // Release cross-process lock so another process can start Node
    if (g_lockFd >= 0) {
        flock(g_lockFd, LOCK_UN);
        close(g_lockFd);
        g_lockFd = -1;
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeIsRunning(JNIEnv *env, jobject thiz) {
    return (g_nodeRunning.load() && !g_pipeClosed.load()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
