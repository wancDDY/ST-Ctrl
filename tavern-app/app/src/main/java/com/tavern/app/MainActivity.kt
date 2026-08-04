package com.tavern.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.tavern.app.console.ConsoleNavHost
import com.tavern.app.console.ConsoleScreen
import com.tavern.app.console.ThemeState
import com.tavern.app.node.NodeRunner
import com.tavern.app.node.NodeState
import com.tavern.app.service.KeepAliveMonitor
import com.tavern.app.service.TavernForegroundService
import com.tavern.app.util.AssetExtractor
import com.tavern.app.webview.TavernWebView
import com.tavern.app.webview.WebViewBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private lateinit var keepAliveMonitor: KeepAliveMonitor
    private var webView: TavernWebView? = null
    private var consoleShown = false
    private var lastLoadedPort = 0

    companion object {
        // survive Activity recreation during startup (config change)
        private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var lanProxy: com.tavern.app.util.LanProxy? = null

        fun startLanProxyIfNeededStatic(port: Int) {
            if (!com.tavern.app.console.SettingsState.lanAccessEnabled()) {
                lanProxy?.stop()
                return
            }
            // Always create a fresh proxy — scope is cancelled after stop()
            lanProxy?.stop()
            lanProxy = com.tavern.app.util.LanProxy(
                listenPort = 7999,
                targetPort = port
            ) {
                // User-defined fixed token takes priority; otherwise the
                // random per-boot token.
                com.tavern.app.console.SettingsState.lanCustomToken()
                    .ifBlank { com.tavern.app.ApplicationState.lanToken }
            }
            lanProxy?.start()
        }

        fun stopLanProxyStatic() {
            lanProxy?.stop()
            com.tavern.app.ApplicationState.lanSessionActive = false
        }

        fun refreshLanTokenStatic(): String {
            com.tavern.app.ApplicationState.lanToken = genLanTokenStatic()
            lanProxy?.stop()
            startLanProxyIfNeededStatic(com.tavern.app.node.NodeState.port.value)
            return com.tavern.app.ApplicationState.lanToken
        }

        private fun genLanTokenStatic(): String {
            // 6-char token: 1 random letter (upper/lower) + 5 digits
            val letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz"
            val letter = letters[kotlin.random.Random.nextInt(letters.length)]
            val digits = (1..5).map { kotlin.random.Random.nextInt(10) }.joinToString("")
            return letter + digits
        }
    }

    private val showStoragePermDialog = mutableStateOf(false)
    private val composeScreen = mutableStateOf("startup")
    private var contentViewIsCompose = false // true when setContent is active, false after setContentView
    private var nodeStateReceiver: android.content.BroadcastReceiver? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* continue */ }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* all granted or denied, continue anyway */ }

    // file chooser: single callback to avoid stale refs
    private var pendingFileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == RESULT_OK) {
            val parsed = android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if (!parsed.isNullOrEmpty()) {
                parsed
            } else {
                // Fallback for providers that don't support parseResult
                val data = result.data
                val list = mutableListOf<android.net.Uri>()
                data?.data?.let { list.add(it) }
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { list.add(it) }
                    }
                }
                list.toTypedArray()
            }
        } else null

        // Take persistable URI permission so chromium can
        // query file metadata (Content-Length) and stream the upload
        // instead of buffering the entire file in main process memory.
        if (uris != null && result.data != null) {
            val flags = result.data?.flags ?: 0
            if ((flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 &&
                (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                for (uri in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { /* not persistable */ }
                }
            }
        }

        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    /** Launch file chooser safely. Multi-level fallback for emulators / custom ROMs:
     *  1. ACTION_GET_CONTENT + given MIME type
     *  2. ACTION_GET_CONTENT + any type (broad MIME fallback)
     *  3. ACTION_OPEN_DOCUMENT + any type (emulator fallback)
     *  4. Direct launch without createChooser (last resort) */
    private fun launchFileChooser(intent: Intent) {
        if (tryResolveAndLaunch(intent)) return

        // Specific MIME type may have no handler → broaden to */*
        if (intent.type != null && intent.type != "*/*") {
            Log.w("MainActivity", "Type ${intent.type} unresolvable, trying */*")
            val broad = Intent(intent).apply { type = "*/*" }
            if (tryResolveAndLaunch(broad)) return
        }

        // ACTION_GET_CONTENT not supported → fall back to ACTION_OPEN_DOCUMENT
        Log.w("MainActivity", "ACTION_GET_CONTENT unresolvable, falling back to ACTION_OPEN_DOCUMENT")
        val fallback = Intent(intent).apply {
            action = Intent.ACTION_OPEN_DOCUMENT
            type = "*/*"
        }
        if (tryResolveAndLaunch(fallback)) return

        // Nothing resolvable via createChooser → try direct launch
        Log.w("MainActivity", "All chooser attempts failed, trying direct launch")
        try {
            fileChooserLauncher.launch(intent)
            return
        } catch (_: Exception) {}

        Log.e("MainActivity", "No file chooser available on this device")
        Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
    }

    /** Returns true if the intent can be resolved and launched via createChooser. */
    private fun tryResolveAndLaunch(intent: Intent): Boolean {
        if (intent.resolveActivity(packageManager) == null) return false
        try {
            fileChooserLauncher.launch(Intent.createChooser(intent, "选择文件"))
            return true
        } catch (e: Exception) {
            Log.w("MainActivity", "createChooser threw: ${e.message}")
            return false
        }
    }

    // SAF file save — for blob exports
    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveMimeType: String? = null
    private var pendingSaveFileName: String? = null
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(pendingSaveMimeType ?: "*/*")
    ) { uri ->
        val bytes = pendingSaveBytes
        val mime = pendingSaveMimeType
        val name = pendingSaveFileName
        pendingSaveBytes = null
        pendingSaveMimeType = null
        pendingSaveFileName = null
        if (uri == null || bytes == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "已保存: ${name ?: "文件"}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "com.tavern.app.ENTER_TAVERN") {
            val port = NodeState.port.value
            showWebView(port)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.tavern.app.ApplicationState.ctx = this
        keepAliveMonitor = KeepAliveMonitor(applicationContext)
        // Wire blob export → SAF save
        WebViewBridge.onSaveRequested = { bytes, mime, name ->
            pendingSaveBytes = bytes
            pendingSaveMimeType = mime
            pendingSaveFileName = name
            saveFileLauncher.launch(name)
        }
        // Generate LAN access token on each cold start
        com.tavern.app.ApplicationState.lanToken = genLanTokenStatic()

        // IME inset handling — prevent keyboard from obscuring the input area
        installImeInsetsHandling()
        composeScreen.value = savedInstanceState?.getString("screen") ?: "startup"
        ThemeState.init(this)
        com.tavern.app.console.SettingsState.init(this)
        // Re-schedule auto backup on launch, but at most once per hour to avoid
        // resetting WorkManager timer on every ordinary app reopen.
        val prefs = this.getSharedPreferences("tavern_console_prefs", android.content.Context.MODE_PRIVATE)
        val lastSched = prefs.getLong("last_schedule_ms", 0L)
        // Reset backup hour/minute to defaults (clear stale time-picker values)
        val bp = this.getSharedPreferences("tavern_auto_backup_prefs", android.content.Context.MODE_PRIVATE)
        bp.edit().putInt("auto_backup_hour", 3).putInt("auto_backup_minute", 0).apply()
        // Check if auto-backup is due (based on configured interval since last backup)
        com.tavern.app.backup.AutoBackupWorker.checkAndBackupIfNeeded(this)
        // Register restore callbacks — stop Node before overwriting data to avoid file locks
        com.tavern.app.backup.BackupManager.onBeforeRestore = {
            com.tavern.app.node.NodeRunner.requestStop(this)
        }
        com.tavern.app.backup.BackupManager.onAfterRestore = {
            startTavern()
        }

        // Sync NodeState from :node process via broadcasts
        nodeStateReceiver = NodeState.initAsSecondary(this)

        // MANAGE_EXTERNAL_STORAGE: show Compose dialog on first launch (API 30+)
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            showStoragePermDialog.value = true
        }

        // Android 9/10: no MANAGE_EXTERNAL_STORAGE — writing public storage
        // (Termux migration script) needs the WRITE_EXTERNAL_STORAGE runtime
        // permission instead.
        if (Build.VERSION.SDK_INT <= 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }


        contentViewIsCompose = true
        setContent {
            TavernTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = composeScreen.value,
                        transitionSpec = {
                            (fadeIn(tween(350)) + scaleIn(initialScale = 0.97f, animationSpec = tween(350)))
                                .togetherWith(fadeOut(tween(250)) + scaleOut(targetScale = 1.03f, animationSpec = tween(250)))
                        },
                        label = "screenTransition"
                    ) { screen ->
                        when (screen) {
                            "webview" -> {
                                // Activity recreated while WebView was active
                                // (e.g. power-save uiMode config change).
                                // Compose needs a placeholder while showWebView
                                // swaps in the native WebView via setContentView.
                                LaunchedEffect(Unit) { showWebView(NodeState.port.value) }
                                Box(Modifier.fillMaxSize()) // empty placeholder
                            }
                            "console" -> ConsoleNavHost(
                                onBack = { },
                                startRoute = "home",
                                onEnterTavern = { showWebView(NodeState.port.value) },
                                onRestartNode = { restartNode() },
                                onRefreshTavern = { webView?.reload() },
                                onStopNode = { stopNodeWithFeedback() },
                                onStartNode = { lifecycleScope.launch { doStartTavern() } }
                            )
                            else -> StartupScreen(onStart = { startTavern() })
                        }
                    }

                    StoragePermDialog(showStoragePermDialog, packageName)
                }
            }
        }

        var lastBackTime = 0L
        var pendingBackCheck = false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null) {
                    // 1) Browser-level history back
                    if (wv.canGoBack()) { wv.goBack(); lastBackTime = 0L; return }

                    // 2) Ask the tavern frontend to close open panels
                    if (!pendingBackCheck) {
                        pendingBackCheck = true
                        wv.evaluateJavascript(
                            "(function(){try{var f=window.__ctrlHandleBack;return(typeof f==='function')?String(f()):'noop';}catch(e){return'noop';}})();"
                        ) { raw ->
                            runOnUiThread {
                                pendingBackCheck = false
                                if ((raw ?: "").trim('"', ' ', '\n').lowercase() == "consumed") {
                                    lastBackTime = 0L
                                    return@runOnUiThread
                                }
                                // JS didn't consume — native exit flow
                                val now = System.currentTimeMillis()
                                if (now - lastBackTime < 2000) {
                                    val keepAlive = com.tavern.app.console.SettingsState.keepTavernAlive()
                                    if (keepAlive) Toast.makeText(this@MainActivity, "酒馆在后台继续运行", Toast.LENGTH_SHORT).show()
                                    showConsole(NodeState.port.value)
                                    lastBackTime = 0L
                                } else {
                                    lastBackTime = now
                                    Toast.makeText(this@MainActivity, "再按一次返回控制台", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else {
                    // Console: double-tap to exit
                    val now = System.currentTimeMillis()
                    if (now - lastBackTime < 2000) {
                        isEnabled = false
                        try { onBackPressedDispatcher.onBackPressed() } finally { isEnabled = true }
                    } else {
                        lastBackTime = now
                        Toast.makeText(this@MainActivity, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        startTavern()
    }

    private fun restartNode() {
        lifecycleScope.launch {
            com.tavern.app.node.NodeRunner.requestStop(this@MainActivity)
            kotlinx.coroutines.delay(600)
            startTavern()
        }
    }

    /** Stop Node via IPC, wait for the :node process to actually release the
     *  port, then update UI state + toast — avoids "已停止" showing while the
     *  port is still open (state race). */
    private fun stopNodeWithFeedback() {
        lifecycleScope.launch(Dispatchers.IO) {
            com.tavern.app.node.NodeRunner.requestStop(this@MainActivity)
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline &&
                com.tavern.app.node.NodeRunner.isPortOpen(TavernApplication.DEFAULT_PORT)) {
                kotlinx.coroutines.delay(150)
            }
            withContext(Dispatchers.Main) {
                NodeState.setIdle()
                keepAliveMonitor.cancel()
                stopNodeHeartbeat()
                // Stop the LAN proxy too — it would otherwise keep listening
                // on 7999 and forward to a dead backend.
                stopLanProxyStatic()
                Toast.makeText(this@MainActivity, "酒馆服务已停止", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startTavern() {
        doStartTavern()
    }

    private fun doStartTavern() {
        val currentState = NodeState.state.value
        if (currentState == NodeState.State.RUNNING) {
            showConsole(NodeState.port.value)
            return
        }
        if (currentState == NodeState.State.STARTING ||
            currentState == NodeState.State.STOPPING) {
            return
        }
        // 原子锁防并发启动
        if (!starting.compareAndSet(false, true)) return

        lifecycleScope.launch {
            try {
                NodeState.setStarting()

                // Reset any stale native state from previous failed attempt — no-op
                // in the main process: Node always lives in :node process.

                // Pre-warm WebView engine in parallel with Node startup
                withContext(Dispatchers.Main) {
                    if (webView == null) webView = TavernWebView(this@MainActivity)
                }

                // Extract core, map progress 0→30%
                NodeState.setProgress(0f, "检查核心代码…")
                val needsExtract = AssetExtractor.needsExtraction(this@MainActivity)

                val coreDir = if (needsExtract) {
                    NodeState.setProgress(0.05f, "正在解压核心代码…")
                    val extracted = withContext(Dispatchers.IO) {
                        AssetExtractor.extractCore(this@MainActivity)
                    }
                    val dir = extracted.getOrElse {
                        NodeState.setError("核心代码解压失败: ${it.message}")
                        return@launch
                    }
                    NodeState.setProgress(0.25f, "解压完成")
                    dir
                } else {
                    NodeState.setProgress(0.25f, "核心代码已就绪")
                    AssetExtractor.getCoreDir(this@MainActivity)
                }

                // Start Node, map progress 30→95%
                // Populate engine heuristics
                val settingsFile = java.io.File(coreDir, "data/default-user/settings.json")
                com.tavern.app.console.SettingsState.heuristicBackupKb =
                    if (settingsFile.exists()) settingsFile.length() / 1024 else 0
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                com.tavern.app.console.SettingsState.heuristicTotalRamMb = memInfo.totalMem / (1024 * 1024)
                com.tavern.app.console.SettingsState.heuristicIsEmulator =
                    com.tavern.app.util.DeviceDetector.isEmulator()

                val lanIp = try {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { ifc ->
                        ifc.inetAddresses?.toList() ?: emptyList()
                    }?.firstOrNull { addr ->
                        !addr.isLoopbackAddress && addr is java.net.Inet4Address && addr.isSiteLocalAddress
                    }?.hostAddress ?: ""
                } catch (_: Exception) { "" }
                com.tavern.app.ApplicationState.lanIp = lanIp

                // Compute perf params in the MAIN process and ship them to
                // :node via SharedPreferences — UI and Node stay consistent.
                com.tavern.app.console.SettingsState.preparePerfParams(this@MainActivity)

                // Node always runs in the :node process — ask TavernForegroundService
                // to start it there. Progress/state comes back via NodeState broadcasts.
                NodeState.setProgress(0.3f, "等待服务就绪…")
                com.tavern.app.node.NodeRunner.requestStart(this@MainActivity)

                // Wait for RUNNING (or ERROR) broadcast, or detect health directly —
                // belt-and-braces in case :node is already up and never re-broadcasts.
                val port = NodeState.port.value
                val finalState = withTimeoutOrNull(com.tavern.app.node.NodeRunner.STARTUP_TIMEOUT_MS) {
                    while (true) {
                        val s = NodeState.state.value
                        if (s == NodeState.State.RUNNING || s == NodeState.State.ERROR) break
                        if (com.tavern.app.node.NodeRunner.isPortOpen(port) &&
                            com.tavern.app.node.NodeRunner.isNodeHealthy(port)) {
                            NodeState.setRunning(port)
                            break
                        }
                        kotlinx.coroutines.delay(500)
                    }
                    NodeState.state.value
                }

                NodeState.setProgress(0.97f, "加载酒馆界面…")

                when (finalState) {
                    NodeState.State.RUNNING -> {
                        keepAliveMonitor.schedule()
                        startNodeHeartbeat()
                        // Start LAN proxy if user has it enabled in settings
                        startLanProxyIfNeededStatic(port)
                        if (!consoleShown) {
                            // Trigger the switch while the brand zoom-fade is still
                            // running (~280ms into the 420ms animation) so the
                            // console fades in over it — no empty gap.
                            kotlinx.coroutines.delay(280)
                            showConsole(port)
                        }
                    }
                    NodeState.State.ERROR -> {
                        NodeState.setError(NodeState.lastError.value ?: "酒馆服务启动失败")
                    }
                    else -> {
                        NodeState.setError("启动超时，请检查日志")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                starting.set(false)
                throw e  // don't swallow cancellation
            } catch (e: Exception) {
                NodeState.setError(e.message ?: "未知异常")
                starting.set(false)
            } finally {
                starting.set(false)  // safety net for Error subtypes (OOM etc.)
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, TavernForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ── Node heartbeat: while the main process is alive, probe the :node
    // process every 45s and heal it immediately if it died — no need to wait
    // for the keep-alive alarm tick (which matters when the main process is
    // dead too). This keeps chat generation going in the background.
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun startNodeHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(45_000)
                withContext(Dispatchers.IO) {
                    try {
                        if (!com.tavern.app.node.NodeRunner.isPortOpen(NodeState.port.value)) {
                            Log.w("Heartbeat", "检测到 :node 已停止，立即拉起…")
                            com.tavern.app.service.KeepAliveMonitor.checkAndHeal(applicationContext)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopNodeHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun requestStoragePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+: use granular media permissions
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT <= 32) {
            // Android 12 and below: READ_EXTERNAL_STORAGE
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /** 显示控制台主页 */
    private fun showConsole(port: Int) {
        consoleShown = true
        composeScreen.value = "console"
        val keepAlive = com.tavern.app.console.SettingsState.keepTavernAlive()
        if (keepAlive) {
            webView?.pauseRendering()
        } else {
            webView?.destroy()
            webView = null
            lastLoadedPort = 0
        }
        if (!contentViewIsCompose) {
        setContent {
            TavernTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = "console",
                        transitionSpec = {
                            (fadeIn(tween(300)) + scaleIn(initialScale = 0.98f, animationSpec = tween(300)))
                                .togetherWith(fadeOut(tween(200)))
                        },
                        label = "backToConsole"
                    ) {
                        ConsoleNavHost(
                            onBack = { },
                            startRoute = "home",
                            onEnterTavern = { showWebView(port) },
                            onRestartNode = { restartNode() },
                            onRefreshTavern = { webView?.reload() },
                            onStopNode = { stopNodeWithFeedback() },
                            onStartNode = { lifecycleScope.launch { doStartTavern() } }
                        )
                    }

                    StoragePermDialog(showStoragePermDialog, packageName)
                }
            }
        }
        contentViewIsCompose = true
        }
    }

    /** 切换到 WebView 加载酒馆 — 复用已有 WebView，避免重建 */
    private fun showWebView(port: Int) {
        // Check WebView availability
        try { android.webkit.WebView(this) } catch (e: Exception) {
            Toast.makeText(this, "未检测到 Android System WebView，无法加载酒馆。请从应用商店安装或启用 WebView。", Toast.LENGTH_LONG).show()
            return
        }
        val currentState = NodeState.state.value
        if (currentState != NodeState.State.RUNNING) {
            if (currentState == NodeState.State.ERROR || currentState == NodeState.State.IDLE) {
                Toast.makeText(this, "酒馆服务未运行", Toast.LENGTH_SHORT).show()
                showConsole(port)
                return
            }
            // STARTING/STOPPING — service is coming up in the background
            // (fast-start path). Wait until it is actually healthy, then load.
            Toast.makeText(this, "正在启动酒馆服务，请稍候…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + com.tavern.app.node.NodeRunner.STARTUP_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (com.tavern.app.node.NodeRunner.isPortOpen(port) &&
                        com.tavern.app.node.NodeRunner.isNodeHealthy(port)
                    ) {
                        NodeState.setRunning(port)
                        withContext(Dispatchers.Main) { loadWebViewNow(port) }
                        return@launch
                    }
                    kotlinx.coroutines.delay(500)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "酒馆服务启动超时", Toast.LENGTH_LONG).show()
                    showConsole(port)
                }
            }
            return
        }
        loadWebViewNow(port)
    }

    private fun loadWebViewNow(port: Int) {
        consoleShown = false
        composeScreen.value = "webview"
        startForegroundService()
        val wv = webView ?: TavernWebView(this).apply {
            setOnPageLoaded { }
            setOnError { msg ->
                Toast.makeText(this@MainActivity, "加载失败: $msg", Toast.LENGTH_LONG).show()
            }
            onFileChooserRequested = { callback, intent ->
                Log.w("MainActivity", "fileChooser launching, intent=$intent")
                pendingFileCallback?.onReceiveValue(null)  // cancel stale callback from prev Activity
                pendingFileCallback = callback
                launchFileChooser(intent)
            }
        }
        // Re-apply opt mode every time
        wv.applyTimerThrottle(com.tavern.app.console.SettingsState.timerThrottleEnabled())
        // 回调在 WebView 复用时可能丢失，每次重新绑定
        wv.onFileChooserRequested = { callback, intent ->
            Log.w("MainActivity", "fileChooser launching")
            pendingFileCallback = callback
            launchFileChooser(intent)
        }
        wv.setOnPageLoaded { }
        wv.setOnError { msg ->
            Toast.makeText(this@MainActivity, "加载失败: $msg", Toast.LENGTH_LONG).show()
        }
        webView = wv
        // 仅在端口变化或首次加载时才重新 loadUrl，复用时不刷新
        if (lastLoadedPort != port) {
            wv.loadTavern(port)
            lastLoadedPort = port
        }
        wv.resumeRendering()
        contentViewIsCompose = false
        setContentView(wv)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
            showStoragePermDialog.value = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", composeScreen.value)
    }

    override fun onDestroy() {
        nodeStateReceiver?.let { unregisterReceiver(it) }
        stopNodeHeartbeat()
        stopLanProxyStatic()
        webView?.destroy()
        webView = null
        keepAliveMonitor.cancel()
        super.onDestroy()
    }

    // ── IME insets: rely on system adjustResize ──
    // adjustResize already shrinks the WebView layout above the keyboard.
    // Adding manual bottom padding on top of that double-shrinks the view
    // and makes the input area jump/flicker. So: no manual padding.
    private fun installImeInsetsHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            // Let the system adjustResize do the work; consume nothing.
            insets
        }
    }
}
