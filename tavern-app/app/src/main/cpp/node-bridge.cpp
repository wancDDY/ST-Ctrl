#include <jni.h>
#include <string>
#include <thread>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "TavernNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::thread g_nodeThread;
static bool g_nodeRunning = false;

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
    jstring jNodeBinDir) {

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

    if (g_nodeRunning) {
        LOGE("Node is already running");
        return JNI_FALSE;
    }

    g_nodeRunning = true;

    g_nodeThread = std::thread([dataDir, entryPoint, port, libDir]() {
        // Change to the server directory
        if (chdir(dataDir.c_str()) != 0) {
            LOGE("chdir failed: %s (errno=%d)", dataDir.c_str(), errno);
            g_nodeRunning = false;
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
            g_nodeRunning = false;
            return;
        }

        // Find node::Start(int, char**)
        // Mangled name for node::Start(int, char**)
        NodeStartFunc nodeStart = (NodeStartFunc)dlsym(handle, "_ZN4node5StartEiPPc");
        if (!nodeStart) {
            // Try alternate mangling
            nodeStart = (NodeStartFunc)dlsym(handle, "_ZN4node5StartEiPKc");
        }
        if (!nodeStart) {
            LOGE("dlsym failed: %s", dlerror());
            dlclose(handle);
            g_nodeRunning = false;
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
                    // Trim trailing newlines for cleaner log
                    char* end = buf + n - 1;
                    while (end >= buf && (*end == '\n' || *end == '\r')) *(end--) = '\0';
                    if (end >= buf) LOGI("[node] %s", buf);
                }
                close(pipefd_read);
            });
            reader.detach();
        }

        LOGI("Calling node::Start with entry: %s", entryPoint.c_str());

        // Build arguments for node::Start
        std::string portArg = "--port=" + std::to_string(port);
        char* argv[] = {
            const_cast<char*>("node"),
            const_cast<char*>("--harmony"),
            const_cast<char*>(entryPoint.c_str()),
            const_cast<char*>(portArg.c_str()),
            nullptr
        };
        int argc = 4;

        int ret = nodeStart(argc, argv);
        LOGI("Node exited: %d", ret);

        dlclose(handle);
        g_nodeRunning = false;
    });

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeStopNode(JNIEnv *env, jobject thiz) {
    LOGI("Stopping node");
    g_nodeRunning = false;

    if (g_nodeThread.joinable()) {
        g_nodeThread.detach();
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tavern_app_node_NodeRunner_nativeIsRunning(JNIEnv *env, jobject thiz) {
    return g_nodeRunning ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
