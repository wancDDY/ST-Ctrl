package com.tavern.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tavern.app.util.CrashGuard

class TavernApplication : Application() {

    companion object {
        const val CHANNEL_ID = "tavern_service_channel"
        const val NOTIFICATION_ID = 1001
        }

    override fun onCreate() {
        super.onCreate()
        ApplicationState.ctx = this
        CrashGuard.install(filesDir)
        // Both processes write their own lines; only the main one also reads the
        // file back and shows them.
        if (isNodeProcess()) com.tavern.app.log.TavernLog.attachWriter(this)
        else com.tavern.app.log.TavernLog.attach(this)
        // Init settings in EVERY process (main + :node). The :node process needs
        // maxOldSpaceMb/uvPoolSize/niceValue for Node startup; without this it
        // would fall back to defaults for boot-start/keep-alive scenarios.
        com.tavern.app.console.SettingsState.init(this)
        // The memory service refuses requests without its token; the client needs the path in
        // both processes because the console reads state from whichever one is showing it.
        com.tavern.app.console.AgentClient.init(this)
        // WebView must be initialized in ONE process only — Android forbids
        // two processes sharing the same WebView data directory at once
        // (crbug.com/558377). The :node process never renders WebView, so it
        // must skip the warm-up entirely, otherwise a concurrent start (e.g.
        // right after a restore backup → relaunch) throws RuntimeException and can
        // break the main process's WebView too.
        if (!isNodeProcess()) warmUpWebView()
        createNotificationChannel()
        if (!isNodeProcess()) {
            // The memory engine runs in the :node process and has no ONNX runtime; this listener
            // is the only way it can ask for a sentence vector. Loopback only, started here so a
            // request that arrives before the console is drawn still gets an answer.
            com.tavern.app.console.memory.EmbedServer.start(this)
            // Loading the ONNX session costs a second or two. Doing it here, off the main thread
            // and before anything asks, is what keeps the first generation from paying it: the
            // engine has a few hundred milliseconds to spare and not a second and a half. Skipped
            // when the reader turned vectors off, so the memory is not spent on an unused model.
            if (com.tavern.app.console.MemoryEmbeddingPreferences.load(this).retrieval) {
                Thread({
                    com.tavern.app.console.memory.MemoryEmbedding.prepare(this)
                }, "memory-embed-warm").apply { isDaemon = true }.start()
            }
            com.tavern.app.update.PeriodicUpdateChecker.initialize(this)
        }
        initHeuristics()
    }

    private fun isNodeProcess(): Boolean {
        val name = currentProcessName()
        return name?.endsWith(":node") == true
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return try {
            java.io.File("/proc/self/cmdline")
                .readText()
                .trim('\u0000', '\n', '\r', ' ')
                .substringBefore('\u0000')
                .ifBlank { null }
        } catch (_: Exception) {
            try {
                val am = getSystemService(android.app.ActivityManager::class.java)
                am.runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }
                    ?.processName
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Pre-load the Chromium engine so the tavern WebView is ready faster. */
    private fun warmUpWebView() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) return
        try {
            WebViewCompat.startSafeBrowsing(this) { /* result ignored */ }
            Log.i("TavernApplication", "WebView warmup started")
        } catch (t: Throwable) {
            Log.w("TavernApplication", "WebView warmup failed", t)
        }
    }

    private fun initHeuristics() {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java) ?: return
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            com.tavern.app.console.SettingsState.heuristicTotalRamMb = memInfo.totalMem / (1024 * 1024)
            com.tavern.app.console.SettingsState.heuristicIsEmulator =
                com.tavern.app.util.DeviceDetector.isEmulator()
            // Thermal polling only feeds the main-process perf-mode UI; the :node
            // process never reads thermalLevel, so skip the extra thread there.
            if (!isNodeProcess()) {
                Thread({
                    while (true) {
                        try {
                            val level = com.tavern.app.util.BatteryHelper.thermalLevel(this)
                            if (level != com.tavern.app.console.SettingsState.thermalLevel) {
                                com.tavern.app.console.SettingsState.thermalLevel = level
                                com.tavern.app.console.SettingsState.writePerfParams(this)
                                MainActivity.refreshActiveWebViewPerf()
                            }
                        } catch (_: Exception) {}
                        Thread.sleep(30_000)
                    }
                }, "thermal-poll").apply { isDaemon = true; start() }
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
