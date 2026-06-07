#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/resource.h>
#include <android/log.h>

#define LOG_TAG "TavernNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::thread g_nodeThread;
static std::atomic<bool> g_nodeRunning(false);
static std::atomic<bool> g_pipeClosed(false);  // true if Node stdout pipe closed unexpectedly

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

    env->ReleaseStringUTFChars(jDataDir, dataDirRaw);
    env->ReleaseStringUTFChars(jEntryPoint, entryRaw);

    const char* libDirRaw = env->GetStringUTFChars(jLibDir, nullptr);
    std::string libDir(libDirRaw);
    env->ReleaseStringUTFChars(jLibDir, libDirRaw);

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
    if (g_nodeThread.joinable()) {
        g_nodeThread.join();
    }

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

        // Redirect stdout/stderr to a pipe so we can log Node.js output
        int pipefd[2];
        if (pipe(pipefd) == 0) {
            dup2(pipefd[1], STDOUT_FILENO);
            dup2(pipefd[1], STDERR_FILENO);
            close(pipefd[1]);

            // Reader thread: forward pipe output to logcat
            std::thread reader([pipefd_read = pipefd[0]]() {
                char buf[1024];
                ssize_t n;
                while ((n = read(pipefd_read, buf, sizeof(buf) - 1)) > 0) {
                    buf[n] = '\0';
                    char* end = buf + n - 1;
                    while (end >= buf && (*end == '\n' || *end == '\r')) *(end--) = '\0';
                    if (end >= buf) LOGI("[node] %s", buf);
                }
                // Pipe closed: if Node hasn't exited normally, it crashed
                if (g_nodeRunning.load()) {
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
        char* argEntry = strdup(entryPoint.c_str());
        char* argPort = strdup(portArg.c_str());
        char* argv[] = { argNode, argHarmony, argEntry, argPort, nullptr };
        int argc = 4;
        int ret = nodeStart(argc, argv);
        free(argNode); free(argHarmony); free(argEntry); free(argPort);
        LOGI("Node exited: %d", ret);

        dlclose(handle);
        g_nodeRunning.store(false);
    });

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeStopNode(JNIEnv *env, jobject thiz) {
    LOGI("Stopping node");
    g_nodeRunning.store(false);
    // Detach is necessary: joining a running Node thread would block for minutes.
    // The atomic flag in nativeStartNode prevents double-start.
    if (g_nodeThread.joinable()) {
        g_nodeThread.detach();
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeIsRunning(JNIEnv *env, jobject thiz) {
    return (g_nodeRunning.load() && !g_pipeClosed.load()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
