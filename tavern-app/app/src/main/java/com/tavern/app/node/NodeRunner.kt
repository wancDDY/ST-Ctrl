package com.tavern.app.node

import android.content.Context
import android.util.Log
import com.tavern.app.ApplicationState
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

class NodeRunner(private val context: Context) {

    data class StartParams(
        val coreDir: File,
        val port: Int = 8000,
        val niceValue: Int = 0,
        val uvPoolSize: Int = 4,
        val maxOldSpaceMb: Int = 256,
        val memoryEmbeddingEnabled: Boolean = false,
        val memoryEmbeddingEndpoint: String = "",
        val memoryEmbeddingModel: String = "",
        val memoryEmbeddingApiKey: String = "",
        val memoryEmbeddingAutoIndex: Boolean = true,
        val onProgress: suspend (Float, String) -> Unit = { _, _ -> }
    )

    companion object {
        private const val TAG = "NodeRunner"
        const val STARTUP_TIMEOUT_MS = 120_000L
        private const val PORT_CHECK_INTERVAL_MS = 500L

        init {
            System.loadLibrary("node-bridge")
        }

        /**
         * Is anything listening on the port?
         *
         * The connect carries its own timeout: a socket opened while the core is still
         * starting can sit in the accept queue and never answer, and a probe without a
         * deadline left the startup wait parked on that call forever — no progress, no
         * timeout, and an entry screen that stopped reacting to input.
         */
        fun isPortOpen(port: Int): Boolean {
            val socket = java.net.Socket()
            return try {
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 400)
                true
            } catch (_: Exception) {
                false
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }

        /**
         * Health check: the port answers and it is SillyTavern answering.
         *
         * The POST needs no CSRF token because a rejection proves the point just as well:
         * protection is on (the runtime turns it back on), so an unauthenticated POST to
         * /api/ping returns 403 with SillyTavern's own "Invalid CSRF token" page. Treating
         * only 2xx/3xx as healthy made this check permanently false, which cost the boot
         * its direct detection and left a live service looking dead until the timeout.
         */
        fun isNodeHealthy(port: Int): Boolean {
            val status: Int
            val body: String
            try {
                val conn = java.net.URL("http://127.0.0.1:$port/api/ping").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 1_500
                conn.readTimeout = 1_500
                status = conn.responseCode
                body = try {
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    stream?.bufferedReader()?.use { NodeHealth.readHead(it) } ?: ""
                } catch (_: Exception) {
                    ""
                }
                try { conn.disconnect() } catch (_: Exception) {}
            } catch (_: Exception) {
                return false
            }
            return NodeHealth.isHealthyResponse(status, body)
        }

        // IPC: Node always runs in the :node process. The main process never
        // calls JNI directly — it asks TavernForegroundService to start/stop.
        fun requestStart(ctx: android.content.Context) {
            // action==null → TavernForegroundService starts Node in :node process.
            val intent = android.content.Intent(ctx, com.tavern.app.service.TavernForegroundService::class.java)
            ctx.startForegroundService(intent)
        }

        fun requestStop(ctx: android.content.Context) {
            try {
                val intent = android.content.Intent(ctx, com.tavern.app.service.TavernForegroundService::class.java).apply {
                    action = com.tavern.app.service.TavernForegroundService.ACTION_STOP_NODE
                }
                ctx.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e("NodeRunner", "requestStop failed (retry on next action): ${e.message}")
            }
        }
    }

    /** Convenience overload using [StartParams] data class. */
    suspend fun start(params: StartParams): Result<Int> = start(
        coreDir = params.coreDir, port = params.port,
        niceValue = params.niceValue, uvPoolSize = params.uvPoolSize,
        maxOldSpaceMb = params.maxOldSpaceMb,
        memoryEmbeddingEnabled = params.memoryEmbeddingEnabled,
        memoryEmbeddingEndpoint = params.memoryEmbeddingEndpoint,
        memoryEmbeddingModel = params.memoryEmbeddingModel,
        memoryEmbeddingApiKey = params.memoryEmbeddingApiKey,
        memoryEmbeddingAutoIndex = params.memoryEmbeddingAutoIndex,
        onProgress = params.onProgress
    )

    suspend fun start(
        coreDir: File,
        port: Int = 8000,
        niceValue: Int = 0,
        uvPoolSize: Int = 4,
        maxOldSpaceMb: Int = 256,
        memoryEmbeddingEnabled: Boolean = false,
        memoryEmbeddingEndpoint: String = "",
        memoryEmbeddingModel: String = "",
        memoryEmbeddingApiKey: String = "",
        memoryEmbeddingAutoIndex: Boolean = true,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> }
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                if (nativeIsRunning() || isPortOpen(port)) {
                    Log.w(TAG, "Node already running, skipping start")
                    com.tavern.app.log.TavernLog.node("Node 已在运行，跳过启动")
                    NodeState.setRunning(port)
                    return@withContext Result.success(port)
                }
                val startTime = System.currentTimeMillis()
                Log.i(TAG, "Starting Node.js: dir=${coreDir.absolutePath}, port=$port")
                com.tavern.app.log.TavernLog.node("启动 Node.js port=$port")

                val entryPoint = "server-wrapper.cjs"
                val entryFile = File(coreDir, entryPoint)
                if (!entryFile.exists()) {
                    val msg = "Entry not found: ${entryFile.absolutePath}"
                    Log.e(TAG, msg)
                    NodeState.setError(msg)
                    return@withContext Result.failure(Exception(msg))
                }

                onProgress(0.1f, "启动 Node.js…")
                val libDir = context.applicationInfo.nativeLibraryDir
                val success = nativeStartNode(
                    coreDir.absolutePath,
                    entryPoint,
                    port,
                    libDir,
                    "",
                    niceValue,
                    uvPoolSize,
                    maxOldSpaceMb,
                    memoryEmbeddingEnabled,
                    memoryEmbeddingEndpoint,
                    memoryEmbeddingModel,
                    memoryEmbeddingApiKey,
                    memoryEmbeddingAutoIndex,
                )
                if (!success) {
                    val msg = "Node.js start returned false"
                    Log.e(TAG, msg)
                    NodeState.setError(msg)
                    return@withContext Result.failure(Exception(msg))
                }

                onProgress(0.5f, "等待服务就绪…")
                val deadline = startTime + STARTUP_TIMEOUT_MS
                var portOpen = false
                var lastPingAttempt = 0L

                while (System.currentTimeMillis() < deadline) {
                    if (isPortOpen(port)) {
                        // Wait until /api/ping responds (server really ready, not just port open)
                        val pingOk = try {
                            val url = java.net.URL("http://127.0.0.1:$port/api/ping")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 1000; conn.readTimeout = 1000
                            conn.requestMethod = "GET"
                            conn.responseCode in 200..499
                        } catch (_: Exception) { false }

                        if (pingOk) { portOpen = true; break }
                        lastPingAttempt = System.currentTimeMillis()
                    }

                    // After 60s with no progress, log diagnostics
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 60_000 && elapsed - (lastPingAttempt.coerceAtLeast(startTime + 60_000)) > 15_000) {
                        Log.w(TAG, "Boot slow: ${elapsed/1000}s elapsed, port=$port open=${isPortOpen(port)} native=${nativeIsRunning()}")
                        com.tavern.app.log.TavernLog.boot('w', "启动偏慢 ${elapsed/1000}s, 端口开放=${isPortOpen(port)}")
                        lastPingAttempt = System.currentTimeMillis()
                    }

                    val waitProgress = 0.7f + (elapsed.toFloat() / STARTUP_TIMEOUT_MS) * 0.25f
                    onProgress(waitProgress.coerceIn(0.7f, 0.95f), "等待服务就绪…")
                    delay(PORT_CHECK_INTERVAL_MS)
                }
                if (portOpen) {
                    Log.i(TAG, "Node.js ready on port $port (${(System.currentTimeMillis()-startTime)/1000}s)")
                    com.tavern.app.log.TavernLog.node("端口 $port 就绪 ✓")
                    NodeState.setRunning(port)
                    Result.success(port)
                } else {
                    val msg = "Port $port not ready after ${STARTUP_TIMEOUT_MS}ms"
                    Log.e(TAG, msg)
                    try { nativeStopNode() } catch (_: Exception) {}
                    NodeState.setError(msg)
                    Result.failure(Exception(msg).also {
                        ApplicationState.ctx?.let { ctx -> com.tavern.app.console.SettingsState.recordNodeCrash(ctx) }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Node start exception: ${e.message}", e)
                try { nativeStopNode() } catch (_: Exception) {}
                NodeState.setError(e.message ?: "Unknown error")
                ApplicationState.ctx?.let { ctx -> com.tavern.app.console.SettingsState.recordNodeCrash(ctx) }
                Result.failure(e)
            }
        }

    suspend fun stop() = withContext(Dispatchers.IO) {
        NodeState.setStopping()
        Log.i(TAG, "Stopping Node.js")
        try {
            nativeStopNode()
        } finally {
            NodeState.setIdle()
        }
    }

    val isRunning: Boolean get() = nativeIsRunning()

    private fun isPortOpen(port: Int): Boolean {
        val sock = Socket()
        return try {
            sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private external fun nativeStartNode(
        dataDir: String,
        entryPoint: String,
        port: Int,
        libDir: String,
        nodeBinDir: String,
        niceValue: Int,
        uvPoolSize: Int,
        maxOldSpaceMb: Int,
        memoryEmbeddingEnabled: Boolean,
        memoryEmbeddingEndpoint: String,
        memoryEmbeddingModel: String,
        memoryEmbeddingApiKey: String,
        memoryEmbeddingAutoIndex: Boolean,
    ): Boolean
    private external fun nativeStopNode(): Boolean
    private external fun nativeIsRunning(): Boolean
}
