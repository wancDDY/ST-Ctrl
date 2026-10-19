package com.tavern.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tavern.app.MainActivity
import com.tavern.app.R
import com.tavern.app.TavernApplication
import com.tavern.app.console.MemoryEmbeddingPreferences
import com.tavern.app.node.NodeRunner
import com.tavern.app.node.NodeState
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.*

class TavernForegroundService : Service() {

    companion object {
        const val ACTION_HIDE = "com.stctrl.mobile.HIDE_SERVICE"
        const val ACTION_OPEN = "com.stctrl.mobile.OPEN_APP"
        const val ACTION_ENTER_CONSOLE = "com.stctrl.mobile.ENTER_CONSOLE"
        const val ACTION_STOP_NODE = "com.stctrl.mobile.STOP_NODE"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        NodeState.initAsPrimary(this)
        // Lives with the service rather than a screen: the reports arrive while the reader is
        // chatting, with no log page open.
        com.tavern.app.log.CacheTraceTailer.start(scope, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> stopForeground(STOP_FOREGROUND_REMOVE)
            ACTION_OPEN -> {
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    action = "com.stctrl.mobile.ENTER_TAVERN"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(openIntent)
            }
            ACTION_STOP_NODE -> scope.launch { stopNode() }
            else -> {
                startForegroundCompat(buildNotification())
                // START_STICKY: system re-created the service after the process
                // was killed — pull Node back up automatically.
                // action==null means "start Node" (KeepAlive / NodeRunner).
                if (intent?.action == null) scope.launch { startLowPower() }
            }
        }
        return START_STICKY
    }

    private val startLock = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var startGeneration = 0

    private suspend fun startLowPower() {
        if (!startLock.compareAndSet(false, true)) return
        startGeneration++  // mark a new start request — cancels pending killProcess
        try {
            if (com.tavern.app.update.CoreUpdater.isUpdateInProgress(this)) {
                Log.i("ForegroundService", "Node start deferred while core update is active")
                return
            }
            val state = NodeState.state.value
            // Only RUNNING says this process has nothing to do. A STARTING state can
            // be the broadcast the main process sends before it asks for this start,
            // so treating it as "already booting" dropped the first request and left
            // Node to come up on a later retry — seconds of an apparently dead button.
            // startLock is what says a boot is already running in this process.
            if (state == NodeState.State.RUNNING) {
                // Nothing to start, but the caller may be a main process that was
                // recreated and does not know this service still serves. Answering with
                // silence left it waiting out its startup timeout on a live service.
                //
                // A port change arrives as this same request, and a service still on the
                // old port is not "already running" for it: re-broadcasting the old port
                // made the main process load an address nothing listens on while the new
                // Node was binding elsewhere. Different port means stop first — the old
                // listener has to go before the new one can bind — then start below.
                if (com.tavern.app.console.SettingsState.nodePort() == NodeState.port.value) {
                    NodeState.rebroadcast()
                    return
                }
                Log.i("ForegroundService", "Port changed to ${com.tavern.app.console.SettingsState.nodePort()}; restarting Node")
                stopNode()
            }
            NodeState.setStarting()
            // setStarting clears phaseText — set a meaningful caption right away
            // so the loading page never shows a blank/"Loading" during Node boot.
            NodeState.setProgress(0.3f, "等待服务就绪…")
            val coreDir = AssetExtractor.getCoreDir(this)
            if (!java.io.File(coreDir, "server.js").exists()) {
                NodeState.setError("核心入口缺失：server.js")
                return
            }
            com.tavern.app.update.CoreUpdater.ensureRuntimePatches(this, coreDir)
            // Perf params are computed by the MAIN process and shipped via
            // SharedPreferences (preparePerfParams before requestStart). Do
            // not recompute here — this process's SettingsState is separate.
            val perf = com.tavern.app.console.SettingsState.readPerfParams(this)
            val embedding = MemoryEmbeddingPreferences.load(this)
            NodeRunner(this).start(
                coreDir = coreDir,
                port = com.tavern.app.console.SettingsState.nodePort(),
                niceValue = perf.niceValue,
                uvPoolSize = perf.uvPoolSize,
                maxOldSpaceMb = perf.maxOldSpaceMb,
                // Only a complete address is handed to the core as a provider: the vector switch
                // on its own means the local vectors, and the engine reads that switch itself.
                memoryEmbeddingEnabled = embedding.providerEnabled,
                memoryEmbeddingEndpoint = embedding.endpoint,
                memoryEmbeddingModel = embedding.model,
                memoryEmbeddingApiKey = embedding.apiKey,
                memoryEmbeddingAutoIndex = embedding.autoIndex,
            ).fold(
                onSuccess = { NodeState.setRunning(it) },
                onFailure = { NodeState.setError(it.message ?: "") }
            )
        } catch (e: Exception) {
            android.util.Log.e("ForegroundService", "startLowPower failed", e)
            NodeState.setError(e.message ?: "服务启动失败")
        } finally {
            startLock.set(false)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Recents swipe → tear Node down with the task. stopWithTask also stops
        // this service here, but stop explicitly so Node dies promptly instead
        // of lingering while the :node process unwinds on its own.
        com.tavern.app.node.NodeState.setStopping()
        try { scope.launch { com.tavern.app.node.NodeRunner(this@TavernForegroundService).stop() } } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scope.cancel()
        // Safety net: stop native Node even if killed outside stopNode()
        try {
            kotlinx.coroutines.runBlocking { NodeRunner(this@TavernForegroundService).stop() }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Stop Node inside this (:node) process, then end the process so the
     *  detached native thread dies too. This is the ONLY place nativeStopNode
     *  is effective (Node always runs in :node process). */
    private suspend fun stopNode() {
        // If a start is currently in-flight, defer to it — killing now would
        // murder the Node that's about to come up.
        if (startLock.get()) return
        val genAtStop = startGeneration
        try {
            NodeState.setStopping()
            try { NodeRunner(this).stop() } catch (_: Exception) {}
            NodeState.setIdle()
        } finally {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            // Give the IDLE broadcast a moment to reach the main process,
            // then kill :node process so detached Node thread cannot linger.
            // If a new start arrived after the stop generation began
            // (quick stop→start toggle), skip the kill to preserve the
            // fresh Node process.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (startGeneration == genAtStop) {
                    try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Exception) {}
                }
            }, 250)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, TavernApplication.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notification_running))
        .setContentText("127.0.0.1:${NodeState.port.value}")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setShowWhen(false)
        .addAction(0, "隐藏", servicePending(ACTION_HIDE, 0))
        .addAction(0, "打开", servicePending(ACTION_OPEN, 1))
        .addAction(0, "控制台", activityPending(ACTION_ENTER_CONSOLE, 2))
        .build()

    private fun servicePending(action: String, code: Int): PendingIntent {
        val intent = Intent(this, TavernForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun activityPending(action: String, code: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TavernApplication.NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TavernApplication.NOTIFICATION_ID, n)
        }
    }
}
