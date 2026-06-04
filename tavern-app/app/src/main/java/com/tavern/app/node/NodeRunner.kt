package com.tavern.app.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket

class NodeRunner(private val context: Context) {

    companion object {
        private const val TAG = "NodeRunner"
        private const val STARTUP_TIMEOUT_MS = 120_000L
        private const val PORT_CHECK_INTERVAL_MS = 500L

        init {
            System.loadLibrary("node-bridge")
        }
    }

    suspend fun start(
        coreDir: File,
        port: Int = 8000,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> }
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting Node.js: dir=${coreDir.absolutePath}, port=$port")

                val entryPoint = "server.js"
                val entryFile = File(coreDir, entryPoint)

                if (!entryFile.exists()) {
                    val msg = "server.js 不存在: ${entryFile.absolutePath}"
                    Log.e(TAG, msg)
                    NodeState.setError(msg)
                    return@withContext Result.failure(Exception(msg))
                }

                onProgress(0.1f, "启动 Node.js 服务…")

                val libDir = context.applicationInfo.nativeLibraryDir

                val success = nativeStartNode(
                    coreDir.absolutePath,
                    entryPoint,
                    port,
                    libDir,
                    ""  // nodeBinDir — unused with dlopen approach
                )

                if (!success) {
                    val msg = "Node.js 启动返回 false"
                    Log.e(TAG, msg)
                    NodeState.setError(msg)
                    return@withContext Result.failure(Exception(msg))
                }

                onProgress(0.5f, "等待服务就绪…")

                // Poll port with progress updates
                val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
                var portReady = false
                while (System.currentTimeMillis() < deadline) {
                    if (isPortOpen(port)) {
                        portReady = true
                        break
                    }
                    // Advance progress from 0.7 → 0.95 as we wait
                    val elapsed = STARTUP_TIMEOUT_MS - (deadline - System.currentTimeMillis())
                    val waitProgress = 0.7f + (elapsed.toFloat() / STARTUP_TIMEOUT_MS) * 0.25f
                    onProgress(waitProgress.coerceIn(0.7f, 0.95f), "等待服务就绪…")
                    delay(PORT_CHECK_INTERVAL_MS)
                }

                if (portReady) {
                    Log.i(TAG, "Node.js 端口 $port 就绪")
                    NodeState.setRunning(port)
                    Result.success(port)
                } else {
                    val msg = "端口 $port 在 ${STARTUP_TIMEOUT_MS}ms 内未就绪"
                    Log.e(TAG, msg)
                    NodeState.setError(msg)
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Node.js 启动异常: ${e.message}", e)
                NodeState.setError(e.message ?: "未知错误")
                Result.failure(e)
            }
        }

    fun stop() {
        NodeState.setStopping()
        Log.i(TAG, "Stopping Node.js")
        nativeStopNode()
        NodeState.setIdle()
    }

    val isRunning: Boolean get() = nativeIsRunning()

    private fun isPortOpen(port: Int): Boolean {
        val sock = Socket()
        return try {
            sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 2000)
            sock.close()
            true
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Copy the architecture-appropriate node binary from jniLibs to coreDir/node.
     * The node binary allows starting Node.js via popen() instead of the C++ embedding API.
     *
     * NOTE: Currently unused — the dlopen-based nativeStartNode() embeds Node.js directly.
     * Keep this method as a fallback in case the dlopen approach needs to be replaced.
     */
    @Suppress("unused")
    private fun prepareNodeBinary(coreDir: File): Boolean {
        return try {
            // Place node binary in code cache dir (allows execution, unlike files/ dir on some devices)
            val execDir = File(context.codeCacheDir, "tavern-node")
            if (!execDir.exists()) execDir.mkdirs()
            
            val nodeFile = File(execDir, "node")
            val libNodeSo = File(execDir, "libnode.so")
            val libCppSo = File(execDir, "libc++_shared.so")

            // If all files exist and are valid, skip
            if (nodeFile.exists() && nodeFile.canExecute() && nodeFile.length() > 1000 &&
                libNodeSo.exists() && libCppSo.exists()) {
                Log.d(TAG, "node binary + libs already prepared in ${execDir.absolutePath}")
                return true
            }

            // Delete any stale/incomplete files from a previous failed attempt
            nodeFile.delete()
            libNodeSo.delete()
            libCppSo.delete()

            // Determine which node binary to use based on ABI
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            Log.i(TAG, "Device ABI: $abi")
            val assetName = when {
                abi.startsWith("arm64") -> "node/node-arm64"
                abi.startsWith("armeabi") -> "node/node-arm"
                abi.startsWith("x86_64") -> "node/node-x64"
                abi.startsWith("x86") -> "node/node-x64"
                else -> {
                    Log.e(TAG, "Unsupported ABI: $abi")
                    return false
                }
            }

            // Copy node binary from assets (retry up to 3 times for robustness)
            var copySuccess = false
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    context.assets.open(assetName).use { input ->
                        nodeFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    copySuccess = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Attempt $attempt to copy node binary failed: ${e.message}")
                    if (attempt < 3) Thread.sleep(200)
                }
            }
            if (!copySuccess) {
                Log.e(TAG, "Failed to copy node binary after 3 attempts", lastError)
                return false
            }

            nodeFile.setExecutable(true)
            Log.i(TAG, "Node binary prepared: ${nodeFile.absolutePath} (${nodeFile.length()} bytes)")

            // Copy required .so files to exec dir so LD_LIBRARY_PATH can find them
            val appLibDir = File(context.applicationInfo.nativeLibraryDir)
            File(appLibDir, "libnode.so").copyTo(libNodeSo, overwrite = true)
            File(appLibDir, "libc++_shared.so").copyTo(libCppSo, overwrite = true)

            Log.i(TAG, "Node binary + libs prepared in ${execDir.absolutePath} ($abi)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare node binary: ${e.message}", e)
            false
        }
    }

    private external fun nativeStartNode(dataDir: String, entryPoint: String, port: Int, libDir: String, nodeBinDir: String): Boolean
    private external fun nativeStopNode(): Boolean
    private external fun nativeIsRunning(): Boolean
}
