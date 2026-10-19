package com.tavern.app

import com.tavern.app.util.AppToast
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.tavern.app.console.ConsoleNavHost
import com.tavern.app.console.ThemeState
import com.tavern.app.console.theme.SystemBarsController
import com.tavern.app.node.NodeRunner
import com.tavern.app.node.NodeState
import com.tavern.app.service.KeepAliveMonitor
import com.tavern.app.service.TavernForegroundService
import com.tavern.app.util.AssetExtractor
import com.tavern.app.webview.MemoryDrainBridge
import com.tavern.app.webview.MemoryDrainState
import com.tavern.app.webview.TavernWebView
import com.tavern.app.webview.WebViewBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private lateinit var keepAliveMonitor: KeepAliveMonitor
    private var webView: TavernWebView? = null
    private var consoleShown = false
    // A tavern entry tapped before the service was ready; honoured on RUNNING.
    private var pendingEnterTavern = false

    /**
     * True when this instance came back from saved state rather than a cold launch.
     * A recreation is the reader returning to a session, so a service that is not up
     * yet is reported on the console, which has the boot progress; the splash belongs
     * to a launch, where there is no session to return to.
     */
    private var recreatedUnderUser = false
    private var lastLoadedPort = 0
    private var loadedCompatMode: Boolean? = null
    private var loadedPerfFactor: Double? = null
    private var loadedTavernReloadHash: String? = null
    private var tavernFullscreen = false
    private var nodeStoppedByUser = false
    private val consoleStartRoute = mutableStateOf("home")
    // Mirrors whether showConsole parked a tavern page the user had opened in the
    // background instead of destroying it. A live WebView is not enough: a background
    // reload creates the page without anyone entering the tavern, and the home entry
    // would then offer to resume a page never shown. Kept apart from tavernWasShown,
    // which stays true for the session even after the page is destroyed.
    private val tavernInBackground = mutableStateOf(false)
    private var tavernWasShown = false
    // Set when the page needs reloading but the tavern is on screen; applied on entry.
    private var pendingTavernReload = false

    // Engine version read once at launch: the tavern entry asks for it before any page
    // exists, and the read can block, so it must not happen on the entry itself.
    private val webViewMajor = mutableStateOf<Int?>(null)
    private val compatGateMajor = mutableStateOf<Int?>(null)

    /** Rate limit for reloading a page that failed on a port the service no longer uses. */
    private var lastLoadRecoveryAt = 0L
    private var loadRecoveryCount = 0
    private val resumeProbeRunning = AtomicBoolean(false)
    private val autoBackupScheduled = AtomicBoolean(false)
    private var lastBackTime = 0L
    private var activityBackCallback: OnBackPressedCallback? = null

    companion object {
        // survive Activity recreation during startup (config change)
        private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var lanProxy: com.tavern.app.util.LanProxy? = null
        @Volatile private var activeInstance: MainActivity? = null

        @JvmStatic
        fun refreshActiveWebViewPerf() {
            val activity = activeInstance ?: return
            activity.runOnUiThread { activity.webView?.applyPerformanceProfile() }
        }

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
            val letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz"
            val random = java.security.SecureRandom()
            val letter = letters[random.nextInt(letters.length)]
            val digits = buildString(5) {
                repeat(5) {
                    append(random.nextInt(10))
                }
            }
            return "$letter$digits"
        }
    }

    private val composeScreen = mutableStateOf("startup")
    private var contentViewIsCompose = false // true when setContent is active, false after setContentView
    private var nodeStateReceiver: android.content.BroadcastReceiver? = null

    // Only the running-service notification uses this permission now; the update
    // announcement moved into the console's own top bar.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
        AppToast.show(this, "无法打开文件选择器", Toast.LENGTH_SHORT)
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
    }

    /** Returns true if the intent can be resolved and launched via createChooser. */
    private fun tryResolveAndLaunch(intent: Intent): Boolean {
        try {
            fileChooserLauncher.launch(Intent.createChooser(intent, "选择文件"))
            return true
        } catch (e: Exception) {
            Log.w("MainActivity", "createChooser threw: ${e.message}")
            return false
        }
    }

    // SAF file save for blob exports; payload is decoded while writing.
    private var pendingSaveDataUrl: String? = null
    private var pendingSaveChunkFile: java.io.File? = null
    private var pendingSaveMimeType: String? = null
    private var pendingSaveFileName: String? = null
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(pendingSaveMimeType ?: "*/*")
    ) { uri ->
        val dataUrl = pendingSaveDataUrl
        val chunkFile = pendingSaveChunkFile
        val mime = pendingSaveMimeType
        val name = pendingSaveFileName
        pendingSaveDataUrl = null
        pendingSaveChunkFile = null
        pendingSaveMimeType = null
        pendingSaveFileName = null
        if (uri == null) {
            chunkFile?.delete()
            return@registerForActivityResult
        }
        try {
            val out = contentResolver.openOutputStream(uri)
            if (out == null) {
                AppToast.show(this, "保存失败：无法打开目标文件", Toast.LENGTH_SHORT)
                return@registerForActivityResult
            }
            var saved = false
            out.use { output ->
                if (chunkFile != null) {
                    chunkFile.inputStream().use { it.copyTo(output, 64 * 1024) }
                    saved = true
                } else if (dataUrl != null) {
                    val comma = dataUrl.indexOf(',')
                    if (comma < 0) {
                        AppToast.show(this, "保存失败：数据为空", Toast.LENGTH_SHORT)
                    } else {
                        val header = dataUrl.substring(0, comma)
                        val encoded = dataUrl.substring(comma + 1)
                        val input = if (header.contains(";base64", ignoreCase = true)) {
                            java.util.Base64.getMimeDecoder().wrap(
                                java.io.ByteArrayInputStream(encoded.toByteArray(Charsets.US_ASCII))
                            )
                        } else {
                            java.io.ByteArrayInputStream(
                                java.net.URLDecoder.decode(encoded, "UTF-8").toByteArray(Charsets.UTF_8)
                            )
                        }
                        input.use { it.copyTo(output, 64 * 1024) }
                        saved = true
                    }
                } else {
                    AppToast.show(this, "保存失败：数据为空", Toast.LENGTH_SHORT)
                }
            }
            if (saved) {
                AppToast.show(this, "已保存：${name ?: "文件"}", Toast.LENGTH_SHORT)
            }
        } catch (e: Exception) {
            AppToast.show(this, "保存失败：${e.message}", Toast.LENGTH_SHORT)
        } finally {
            chunkFile?.delete()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        if (action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            if (composeScreen.value == "console") {
                recoverNodeFromConsole()
            }
            return
        }
        if (action == "com.stctrl.mobile.ENTER_TAVERN") {
            requestEnterTavern()
        } else if (action == com.tavern.app.service.TavernForegroundService.ACTION_ENTER_CONSOLE) {
            val port = NodeState.port.value
            showConsole(port)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.tavern.app.ApplicationState.ctx = this
        com.tavern.app.update.CoreUpdater.clearStaleUpdateLock(applicationContext)
        keepAliveMonitor = KeepAliveMonitor(applicationContext)
        // Wire blob export → SAF save
        WebViewBridge.onSaveRequested = { dataUrl, mime, name ->
            pendingSaveDataUrl = dataUrl
            pendingSaveMimeType = mime
            pendingSaveFileName = name
            saveFileLauncher.launch(name)
        }
        WebViewBridge.onChunkComplete = { file, mime, name ->
            pendingSaveChunkFile = file
            pendingSaveMimeType = mime
            pendingSaveFileName = name
            saveFileLauncher.launch(name)
        }
        WebViewBridge.onFullscreenChanged = { enabled ->
            runOnUiThread {
                tavernFullscreen = enabled
                applyCurrentSystemBarMode()
            }
        }
        // Generate LAN access token on each cold start
        com.tavern.app.ApplicationState.lanToken = genLanTokenStatic()

        // IME inset handling — prevent keyboard from obscuring the input area
        installImeInsetsHandling()
        // An Activity recreated under the user (power-save uiMode change, rotation)
        // returns to the surface it was showing. A fresh launch opens the console: the
        // tavern page is the user's decision, taken by tapping the entry.
        if (savedInstanceState?.getString("screen") == "webview") {
            composeScreen.value = "webview"
            // The page is re-attached by the boot observer when the service answers.
            // Restoring the screen but not the request left nothing to attach it, and
            // an unconditional "resume" below threw the restored screen away — either
            // way the reader came back from another app to the splash.
            pendingEnterTavern = true
            recreatedUnderUser = true
            if (NodeState.state.value != NodeState.State.RUNNING) startTavern()
        } else if (savedInstanceState != null) {
            // Recreated under the user with the console in front. The console is where a
            // boot reports itself, so landing there shows the session coming back rather
            // than a splash that says nothing and covers the state the reader had.
            recreatedUnderUser = true
            composeScreen.value = "resume"
        } else {
            composeScreen.value = "resume"
        }
        ThemeState.init(this)
        com.tavern.app.console.SettingsState.init(this)
        SystemBarsController.apply(
            this,
            dark = ThemeState.isDarkMode.value,
            hideStatusBar = false,
        )
        // Register restore callbacks — stop Node before overwriting data to avoid file locks
        com.tavern.app.backup.BackupManager.onBeforeRestore = {
            com.tavern.app.node.NodeRunner.requestStop(this)
        }
        com.tavern.app.backup.BackupManager.onAfterRestore = {
            restartAfterRestore()
        }

        // Sync NodeState from :node process via broadcasts
        nodeStateReceiver = NodeState.initAsSecondary(this)

        if (com.tavern.app.update.PeriodicUpdateChecker.shouldRequestNotificationPermission(this)) {
            com.tavern.app.update.PeriodicUpdateChecker.markNotificationPermissionPrompted(this)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }


        // Read the engine version while nothing depends on it, so the tavern entry can
        // decide synchronously whether to offer compat rendering before loading a page.
        lifecycleScope.launch {
            webViewMajor.value = withContext(Dispatchers.IO) {
                com.tavern.app.util.WebViewVersion.majorVersion(this@MainActivity)
            }
        }

        // Operations in the console change data the running page already read; reload it
        // in the background so the user does not have to refresh the tavern by hand.
        // Without a live WebView nothing is stale — the next load reads the new state.
        lifecycleScope.launch {
            var handled = com.tavern.app.webview.TavernRefresh.requests.value
            com.tavern.app.webview.TavernRefresh.requests.collect { value ->
                if (value == handled) return@collect
                handled = value
                // One scan can materialise several world books in a row; a reload covers
                // them all, and the delay also absorbs a burst of requests.
                kotlinx.coroutines.delay(800)
                if (handled != com.tavern.app.webview.TavernRefresh.requests.value) return@collect
                if (webView != null && NodeState.state.value == NodeState.State.RUNNING) {
                    requestTavernReload("data changed in the console")
                } else {
                    android.util.Log.i("STCtrlRefresh", "request ignored; webView=${webView != null} node=${NodeState.state.value}")
                }
            }
        }

        // A restart leaves the retained page showing the old session: reload it once the
        // service is up, which also covers restoring a backup and installing a core.
        lifecycleScope.launch {
            var wasRunning = NodeState.state.value == NodeState.State.RUNNING
            NodeState.state.collect { state ->
                val running = state == NodeState.State.RUNNING
                if (running && !wasRunning && webView != null) {
                    requestTavernReload("service restarted")
                }
                if (running && !wasRunning) {
                    // A service that just came up read the options written before it started.
                    com.tavern.app.console.SettingsState.markTavernServerOptsRead(this@MainActivity)
                }
                wasRunning = running
            }
        }

        contentViewIsCompose = true
        setContent {
            TavernTheme {
                Box(modifier = Modifier.fillMaxSize().imePadding()) {
                    AnimatedContent(
                        targetState = composeScreen.value,
                        transitionSpec = {
                            // Fade only — scaling would reveal the black backdrop behind it.
                            (fadeIn(tween(300, easing = com.tavern.app.console.theme.FluentEasing)))
                                .togetherWith(fadeOut(tween(250, easing = com.tavern.app.console.theme.FluentEasingOut)))
                        },
                        label = "screenTransition"
                    ) { screen ->
                        when (screen) {
                            "resume" -> {
                                LaunchedEffect(Unit) { resolveInitialScreen() }
                                ResumeScreen()
                            }
                            "webview" -> {
                                // Activity recreated while WebView was active
                                // (e.g. power-save uiMode config change).
                                // Compose needs a placeholder while showWebView
                                // swaps in the native WebView via setContentView.
                                LaunchedEffect(Unit) { showWebView(NodeState.port.value) }
                                Box(Modifier.fillMaxSize()) // empty placeholder
                            }
                            "console" -> ConsoleNavHost(
                                onBack = { handleConsoleExit() },
                                startRoute = consoleStartRoute.value,
                                onStartRouteApplied = { consoleStartRoute.value = "home" },
                                tavernInBackground = tavernInBackground.value,
                                onEnterTavern = { requestEnterTavern() },
                                onRestartNode = { restartNode() },
                                onRefreshTavern = { refreshTavern(NodeState.port.value) },
                                onStopNode = { stopNodeWithFeedback() },
                                onStartNode = { lifecycleScope.launch { doStartTavern() } },
                                onCancelEnterTavern = { cancelEnterTavern() }
                            )
                            else -> StartupScreen(
                                onStart = { startTavern() },
                                onEnterEarly = { enterEarly() },
                                onViewLog = { showConsole(NodeState.port.value, "logs") }
                            )
                        }
                    }
                    CompatTavernGate()
                }
            }
        }
        observeNodeStateForScreen()
        resolveInitialScreen()
        watchForStalledBoot()

        var pendingBackCheck = false
        val backCallback = object : OnBackPressedCallback(composeScreen.value == "webview") {
            override fun handleOnBackPressed() {
                val wv = webView
                if (composeScreen.value != "webview" || wv == null) return

                // Browser-level history back takes priority — except into a page left on a
                // port the service no longer answers on, where walking back only lands on a
                // dead address. Reloading here instead is worse: back then becomes a page
                // load, competes with the page's own navigation and traps the reader in the
                // tavern. The page in front is left alone; the recovery path owns reloads.
                val live = NodeState.port.value
                val current = wv.url
                val onLivePort = current != null && current.startsWith("http://127.0.0.1:$live")
                if (onLivePort && wv.canGoBack()) {
                    wv.goBack()
                    lastBackTime = 0L
                    return
                }

                // Ask the tavern frontend to close open panels before leaving.
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
                            val now = System.currentTimeMillis()
                            if (now - lastBackTime < 2000) {
                                val keepAlive = com.tavern.app.console.SettingsState.keepTavernAlive()
                                if (keepAlive) AppToast.show(this@MainActivity, "核心在后台继续运行", Toast.LENGTH_SHORT)
                                showConsole(NodeState.port.value)
                                lastBackTime = 0L
                            } else {
                                lastBackTime = now
                                AppToast.show(this@MainActivity, "再按一次返回控制台", Toast.LENGTH_SHORT)
                            }
                        }
                    }
                }
            }
        }
        activityBackCallback = backCallback
        onBackPressedDispatcher.addCallback(this, backCallback)

    }

    private fun observeNodeStateForScreen() {
        lifecycleScope.launch {
            NodeState.state.collect { state ->
                if (state != NodeState.State.RUNNING) return@collect
                // Providers the engine holds in memory die with the process. Sending them on
                // every start is what keeps a configured vector, rewrite or rerank service in
                // effect after a backup or a core update restarted the core.
                launch(Dispatchers.IO) {
                    com.tavern.app.console.pages.pushMemoryProviderSettings(
                        applicationContext,
                        NodeState.port.value,
                    )
                }
                if (pendingEnterTavern) {
                    pendingEnterTavern = false
                    // A request parked while the service was starting is still an entry, so
                    // it answers the same question instead of opening the page behind it.
                    if (!offerCompatBeforeTavern()) showWebView(NodeState.port.value)
                } else if (composeScreen.value == "startup" || composeScreen.value == "resume") {
                    resumeToLastScreen(NodeState.port.value)
                }
            }
        }
    }

    private fun restartNode() {
        lifecycleScope.launch {
            com.tavern.app.node.NodeRunner.requestStop(this@MainActivity)
            kotlinx.coroutines.delay(600)
            startTavern()
        }
    }

    private fun restartAfterRestore() {
        Log.i("MainActivity", "Restarting Node after restore")
        lifecycleScope.launch {
            while (NodeState.state.value == NodeState.State.STOPPING) {
                kotlinx.coroutines.delay(100)
            }
            startTavern()
        }
    }

    /** Stop Node via IPC, wait for the :node process to actually release the
     *  port, then update UI state + toast — avoids "Stopped" showing while the
     *  port is still open (state race). */
    private fun stopNodeWithFeedback() {
        nodeStoppedByUser = true
        lifecycleScope.launch(Dispatchers.IO) {
            com.tavern.app.node.NodeRunner.requestStop(this@MainActivity)
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline &&
                com.tavern.app.node.NodeRunner.isPortOpen(com.tavern.app.console.SettingsState.nodePort())) {
                kotlinx.coroutines.delay(150)
            }
            withContext(Dispatchers.Main) {
                NodeState.setIdle()
                keepAliveMonitor.cancel()
                stopNodeHeartbeat()
                // Stop the LAN proxy too — it would otherwise keep listening
                // on 7999 and forward to a dead backend.
                stopLanProxyStatic()
                AppToast.show(this@MainActivity, "核心服务已停止", Toast.LENGTH_SHORT)
            }
        }
    }

    private fun startTavern() {
        doStartTavern()
    }

    private fun setComposeScreen(screen: String) {
        composeScreen.value = screen
        if (screen == "startup" || screen == "resume") {
            consoleShown = false
        }
        activityBackCallback?.isEnabled = screen == "webview"
    }

    private fun resumeToLastScreen(port: Int) {
        // The tavern opens when the user asks for it, by tapping the entry. A launch
        // restores the console instead, which carries the state of the running session —
        // entering the page on its own would take that decision away.
        // This runs from a probe that started before the page did, so it must never
        // pull the console over a page the user has since opened.
        if (composeScreen.value != "webview") showConsole(port)
    }

    private fun resolveInitialScreen() {
        if (NodeState.state.value == NodeState.State.RUNNING) {
            if (composeScreen.value != "webview") resumeToLastScreen(NodeState.port.value)
            return
        }
        if (!resumeProbeRunning.compareAndSet(false, true)) return

        lifecycleScope.launch {
            val port = com.tavern.app.console.SettingsState.nodePort()
            val healthy = try {
                withContext(Dispatchers.IO) {
                    com.tavern.app.node.NodeRunner.isPortOpen(port) &&
                        com.tavern.app.node.NodeRunner.isNodeHealthy(port)
                }
            } finally {
                resumeProbeRunning.set(false)
            }

            if (healthy) {
                NodeState.setRunning(port)
                if (composeScreen.value != "webview") resumeToLastScreen(port)
                return@launch
            }

            when (NodeState.state.value) {
                NodeState.State.RUNNING -> if (composeScreen.value != "webview") {
                    resumeToLastScreen(NodeState.port.value)
                }
                NodeState.State.STARTING -> {
                    val startedAt = NodeState.bootStartedAt.value
                    val stale = startedAt > 0L &&
                        System.currentTimeMillis() - startedAt >
                        com.tavern.app.node.NodeRunner.STARTUP_TIMEOUT_MS + 15_000L
                    // No screen to switch to either way: the console home carries the boot
                    // state and its progress, so a page that only repeats them is in the way.
                    if (stale) {
                        Log.w("MainActivity", "Stale STARTING state detected; restarting boot")
                        com.tavern.app.log.TavernLog.boot('w', "检测到过期启动状态，重新启动服务")
                        NodeState.setIdle()
                        landOnBootSurface(port)
                        startTavern()
                    } else {
                        landOnBootSurface(port)
                    }
                }
                NodeState.State.STOPPING -> landOnBootSurface(port)
                else -> {
                    landOnBootSurface(port)
                    startTavern()
                }
            }
        }
    }

    /**
     * Where a service that is not up yet is reported. A cold launch shows the splash; a
     * recreation shows the console, which carries the boot state and its progress, so a
     * reader returning to the app watches the session come back where they left it.
     */
    private fun landOnBootSurface(port: Int) {
        if (recreatedUnderUser && composeScreen.value != "webview") {
            showConsole(port)
        } else {
            setComposeScreen("startup")
        }
    }

    /**
     * The tavern entry, whenever it is tapped. A tap while the service is still coming up
     * is a promise to open the tavern, not a request for a progress dialog: the console
     * home already shows the boot, so the tap is remembered and honoured on RUNNING.
     * Tapping while nothing runs starts the service for the same reason the tap was made.
     */
    private fun requestEnterTavern() {
        if (offerCompatBeforeTavern()) return
        enterTavernNow()
    }

    private fun enterTavernNow() {
        if (NodeState.state.value == NodeState.State.RUNNING) {
            showWebView(NodeState.port.value)
            return
        }
        // The console only offers this while a boot is running, and the dialog it opens
        // is what asks for the page: the page opens when the boot it reports finishes,
        // and closing the dialog drops the request.
        pendingEnterTavern = true
    }

    /**
     * Old engines render the core's stylesheets wrong, and that first page is exactly what
     * the entry is about to build. The reader chooses between compat rendering and a
     * browser before that, not after the page looks broken. Made once per engine version;
     * a version that cannot be read is not guessed at.
     */
    private fun offerCompatBeforeTavern(): Boolean {
        // The offer is a dialog, so it needs the console tree on screen; an entry from the
        // notification while the native page is up has nothing to draw it on.
        if (!contentViewIsCompose) return false
        // The console re-asks for the entry once a boot it was waiting on finishes, so a
        // second request arrives while the question is still on screen. It is the same
        // question: answering it by opening the page behind the dialog is what put the
        // reader in the tavern before they had chosen.
        if (compatGateMajor.value != null) return true
        // A service that is still coming up already has the console's own dialog on screen.
        // The question belongs where the page is about to load, which is also where a
        // request parked during the boot lands.
        if (NodeState.state.value != NodeState.State.RUNNING) return false
        if (com.tavern.app.console.SettingsState.compatModeEnabled()) return false
        // The probe runs at launch; an entry that beats it reads the version here rather
        // than letting the first tap behave differently from every later one.
        val major = webViewMajor.value
            ?: com.tavern.app.util.WebViewVersion.majorVersion(this).also { webViewMajor.value = it }
            ?: return false
        if (major >= com.tavern.app.util.WebViewVersion.MIN_SUPPORTED_MAJOR) return false
        if (com.tavern.app.console.SettingsState.compatOfferVersion(this) == major) return false
        com.tavern.app.console.SettingsState.markCompatOffered(this, major)
        compatGateMajor.value = major
        return true
    }

    /** The other way into the same server, one the phone's own engine is not part of. */
    private fun openTavernInBrowser() {
        if (NodeState.state.value != NodeState.State.RUNNING) {
            AppToast.show(this, "核心服务尚未运行，启动后再用浏览器打开", Toast.LENGTH_SHORT)
            return
        }
        val port = NodeState.port.value.takeIf { it > 0 } ?: com.tavern.app.console.SettingsState.nodePort()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, "选择浏览器"))
        } catch (_: Exception) {
            AppToast.show(this, "未找到浏览器应用", Toast.LENGTH_SHORT)
        }
    }

    private fun cancelEnterTavern() {
        pendingEnterTavern = false
    }

    /** Jumped from the boot page while the service is still coming up. */
    private fun enterEarly() {
        if (NodeState.state.value == NodeState.State.STARTING) {
            showConsole(NodeState.port.value)
        }
    }

    private fun doStartTavern() {
        val currentState = NodeState.state.value
        Log.i("MainActivity", "Starting Node from state=$currentState")
        if (currentState == NodeState.State.RUNNING) {
            if (composeScreen.value != "webview") showConsole(NodeState.port.value)
            return
        }
        if (currentState == NodeState.State.STARTING) return
        if (currentState == NodeState.State.STOPPING) {
            lifecycleScope.launch {
                while (NodeState.state.value == NodeState.State.STOPPING) {
                    kotlinx.coroutines.delay(100)
                }
                doStartTavern()
            }
            return
        }
        nodeStoppedByUser = false
        // Atomic lock prevents concurrent startup.
        if (!starting.compareAndSet(false, true)) return
        NodeState.beginStart()

        lifecycleScope.launch {
            try {
                NodeState.setStarting()
                NodeState.setProgress(0.01f, "检查核心代码…")

                // Reset any stale native state from previous failed attempt — no-op
                // in the main process: Node always lives in :node process.

                // Extract core, map progress 0→30%
                val needsExtract = AssetExtractor.needsExtraction(this@MainActivity)

                val coreDir = if (needsExtract) {
                    NodeState.setProgress(0.05f, "正在解压核心代码…")
                    com.tavern.app.log.TavernLog.boot('i', "开始解压核心代码…")
                    val extracted = withContext(Dispatchers.IO) {
                        AssetExtractor.extractCore(
                            this@MainActivity,
                            onEntry = { entries ->
                                // The archive has no readable total, so the caption
                                // carries the work done while the bar stays honest
                                // about not knowing the end.
                                NodeState.setUnmeasuredProgress(0.05f, "正在解压核心代码… $entries 项")
                            },
                            onPhase = { phase ->
                                // Restoring the reader's data has no entry count of its own;
                                // saying nothing there is what made a slow restore look like
                                // a stalled extraction.
                                NodeState.setUnmeasuredProgress(0.25f, phase)
                            },
                        )
                    }
                    val dir = extracted.getOrElse {
                        com.tavern.app.log.TavernLog.boot('e', "核心解压失败：${it.message}")
                        NodeState.setError("核心代码解压失败：${it.message}")
                        return@launch
                    }
                    com.tavern.app.log.TavernLog.boot('i', "核心解压完成")
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
                com.tavern.app.console.SettingsState.writeTavernServerOpts(this@MainActivity, coreDir)
                withContext(Dispatchers.IO) {
                    com.tavern.app.update.CoreUpdater.ensureRuntimePatches(
                        this@MainActivity,
                        coreDir
                    )
                }

                // Node always runs in the :node process — ask TavernForegroundService
                // to start it there. Progress/state comes back via NodeState broadcasts.
                NodeState.setProgress(0.3f, "等待服务就绪…")
                com.tavern.app.node.NodeRunner.requestStart(this@MainActivity)

                // No WebView warm-up in this flow. Creating it loads the whole tavern page — a
                // 700 KB document plus its scripts — on the main thread, and doing that during
                // startup starved the wait loop of its suspensions: the state machine stayed at
                // STARTING and the entry screen stopped reacting to input, while the core itself
                // was already answering. The WebView is created on demand when a page is opened.

                // Wait for RUNNING (or ERROR) broadcast, or detect health directly —
                // belt-and-braces in case :node is already up and never re-broadcasts.
                //
                // The port comes from settings, not from NodeState: the service reads
                // it from settings when it starts, so after a port change that state
                // still holds the previous run's port and this loop would poll the old
                // one until the boot timed out — which left the entry dialog sitting at
                // the end of its progress bar with the service actually up.
                val port = com.tavern.app.console.SettingsState.nodePort()
                NodeState.setPort(port)
                // A plain background thread keeps the answer fresh. The wait loop below only
                // reads it and sleeps, so nothing it does can be starved out of a dispatcher
                // or parked inside a probe.
                val coreAnswering = java.util.concurrent.atomic.AtomicBoolean(false)
                val portProbe = Thread {
                    var ticks = 0
                    while (!Thread.currentThread().isInterrupted) {
                        val open = try {
                            com.tavern.app.node.NodeRunner.isPortOpen(port)
                        } catch (_: Exception) {
                            false
                        }
                        coreAnswering.set(open)
                        ticks += 1
                        if (ticks % 6 == 0) {
                            com.tavern.app.log.BootTrace.log(
                                this@MainActivity,
                                "probe open=$open state=${NodeState.state.value.name}",
                            )
                        }
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }
                portProbe.isDaemon = true
                portProbe.start()
                var announcedListening = false
                var lastWaitLine = ""
                var waitIterations = 0
                val finalState = withTimeoutOrNull(com.tavern.app.node.NodeRunner.STARTUP_TIMEOUT_MS) {
                    while (true) {
                        val s = NodeState.state.value
                        if (s == NodeState.State.RUNNING || s == NodeState.State.ERROR) break
                        // Read-only: the probe thread owns the network call.
                        val listening = coreAnswering.get()
                        // Answering on its own port is the fact that decides the boot: the health
                        // probe adds a second deadline, and asking the ActivityManager for the node
                        // process could park this loop on its own.
                        if (listening) {
                            Log.i("MainActivity", "Adopting the running service on port $port")
                            NodeState.setRunning(port)
                            break
                        }
                        waitIterations += 1
                        val waitLine = "wait state=${s.name} listening=$listening port=$port"
                        // A heartbeat every few seconds is what tells a stalled loop from a loop
                        // that simply has nothing new to report.
                        if (waitLine != lastWaitLine || waitIterations % 10 == 0) {
                            lastWaitLine = waitLine
                            com.tavern.app.log.BootTrace.log(
                                this@MainActivity,
                                "$waitLine iter=$waitIterations",
                            )
                        }
                        // Without this suspension the loop spins as fast as the probes return:
                        // the main thread never gets a chance to draw or handle input, and the
                        // startup timeout never fires because nothing ever suspends.
                        kotlinx.coroutines.delay(500)
                    }
                    NodeState.state.value
                }
                portProbe.interrupt()

                NodeState.setProgress(0.97f, "加载核心界面…")

                when (finalState) {
                    NodeState.State.RUNNING -> {
                        // A fresh session: the next timeout may use its own retry.
                        lateNodeRetryUsed = false
                        keepAliveMonitor.schedule()
                        startNodeHeartbeat()
                        // Start LAN proxy if user has it enabled in settings
                        startLanProxyIfNeededStatic(port)
                        com.tavern.app.log.TavernLog.boot('i', "核心服务就绪 (port=$port)")
                        // The console comes up after the first boot only while it is the
                        // surface that should be in front: a page opened from the entry
                        // dialog clears consoleShown on its way in, and this branch used
                        // to take that as "show the console" and cover the page it had
                        // just opened.
                        if (!consoleShown && composeScreen.value != "webview") {
                            // Trigger the switch while the brand zoom-fade is still
                            // running (~280ms into the 420ms animation) so the
                            // console fades in over it — no empty gap.
                            kotlinx.coroutines.delay(280)
                            showConsole(port)
                        }
                    }
                    NodeState.State.ERROR -> {
                        com.tavern.app.log.TavernLog.boot('e', "启动失败：${NodeState.lastError.value ?: "未知"}")
                        NodeState.setError(NodeState.lastError.value ?: "核心服务启动失败")
                    }
                    else -> {
                        com.tavern.app.log.TavernLog.boot('e', "启动超时（STARTUP_TIMEOUT_MS=${com.tavern.app.node.NodeRunner.STARTUP_TIMEOUT_MS}）")
                        NodeState.setError("启动超时，请检查日志")
                        watchForLateNodeStart(port)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                starting.set(false)
                NodeState.endStart()
                throw e  // don't swallow cancellation
            } catch (e: Exception) {
                NodeState.setError(e.message ?: "未知异常")
                starting.set(false)
                NodeState.endStart()
            } finally {
                starting.set(false)  // safety net for Error subtypes (OOM etc.)
                NodeState.endStart()
                // A tap on the tavern made while this boot ran is honoured on RUNNING;
                // nothing else moves on its own now that the console stays put.
            }
        }
    }

    /**
     * The boot that timed out may still come up: a device under memory pressure can
     * take longer than the wait. One boot is retried before the state is reported as
     * failed — a page left on its loading caption with no error is the one outcome the
     * reader cannot act on.
     */
    private var lateNodeRetryUsed = false

    /**
     * A boot that stops making progress never leaves the entry screen on its own.
     *
     * The stale-state check in [resolveInitialScreen] runs when the activity is created, which is
     * the one moment a stall cannot have happened yet; a boot that died after the core was
     * unpacked — an interrupted coroutine, a process that came back while the previous boot was
     * still marked as running — left the screen spinning with no error and no retry until the
     * app was closed and reopened. This watches while the app is up, so that state recovers
     * itself.
     */
    private var stalledBootRetries = 0

    private fun watchForStalledBoot() {
        lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (NodeState.state.value != NodeState.State.STARTING) continue
                val startedAt = NodeState.bootStartedAt.value
                if (startedAt <= 0L) continue
                val stalledFor = System.currentTimeMillis() - startedAt
                // A boot that is still following its own timeline ends in RUNNING or in ERROR by
                // this point: the wait loop gives up after STARTUP_TIMEOUT_MS. Still STARTING
                // past that means nothing is driving it.
                if (stalledFor <= com.tavern.app.node.NodeRunner.STARTUP_TIMEOUT_MS + 15_000L) {
                    continue
                }
                if (stalledBootRetries >= 2) {
                    Log.e("MainActivity", "Boot stalled twice; leaving it to the reader")
                    com.tavern.app.log.TavernLog.boot('e', "启动停滞，已停止自动重试")
                    NodeState.setError("启动停滞，请关闭应用后重新打开")
                    // Nothing else can move it, so the loop stops instead of repeating the same
                    // message every ten seconds.
                    return@launch
                }
                stalledBootRetries += 1
                Log.w("MainActivity", "Boot stalled for ${stalledFor}ms; restarting it")
                com.tavern.app.log.TavernLog.boot('w', "启动停滞 ${stalledFor / 1000}s，自动重试")
                // The guard is cleared as well: a boot that ended without reaching its own
                // cleanup leaves it set, and the retry would be dropped as a duplicate.
                starting.set(false)
                NodeState.setIdle()
                landOnBootSurface(NodeState.port.value)
                startTavern()
            }
        }
    }

    private fun watchForLateNodeStart(port: Int) {
        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + 60_000L
            while (isActive && System.currentTimeMillis() < deadline) {
                if (NodeState.state.value == NodeState.State.RUNNING) return@launch
                if (com.tavern.app.node.NodeRunner.isPortOpen(port) &&
                    com.tavern.app.node.NodeRunner.isNodeHealthy(port)) {
                    NodeState.setRunning(port)
                    com.tavern.app.log.TavernLog.boot('i', "服务在等待超时后已就绪，状态已恢复")
                    keepAliveMonitor.schedule()
                    startNodeHeartbeat()
                    startLanProxyIfNeededStatic(port)
                    return@launch
                }
                kotlinx.coroutines.delay(1000L)
            }
            if (!isActive) return@launch
            if (NodeState.state.value == NodeState.State.RUNNING) return@launch
            if (!lateNodeRetryUsed) {
                lateNodeRetryUsed = true
                com.tavern.app.log.TavernLog.boot('w', "启动超时，重新启动一次服务")
                NodeState.setIdle()
                startTavern()
                watchForLateNodeStart(port)
            } else {
                // Reported rather than left silent: the console home shows the error
                // and the start button, which is what the reader can act on.
                com.tavern.app.log.TavernLog.boot('e', "启动超时且重试失败，已停止等待")
                NodeState.setError("启动超时，请点按重试或查看运行日志")
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, TavernForegroundService::class.java)
        startForegroundService(intent)
    }

    // Heartbeat the :node process every 45s while the main process is alive,
    // restarting it immediately on death. Reaching the keep-alive alarm tick
    // above takes too long, so this is what keeps chat generation alive in the
    // background.
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun startNodeHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(45_000)
                withContext(Dispatchers.IO) {
                    try {
                        if (
                            !com.tavern.app.update.CoreUpdater
                                .isUpdateInProgress(applicationContext)
                            && !com.tavern.app.node.NodeRunner.isPortOpen(NodeState.port.value)
                        ) {
                            Log.w("Heartbeat", "检测到 :node 已停止，重新拉起…")
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

    private fun handleConsoleExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            finish()
        } else {
            lastBackTime = now
            AppToast.show(this, "再按一次返回键退出", Toast.LENGTH_SHORT)
        }
    }

    private fun showConsole(port: Int, startRoute: String = "home") {
        com.tavern.app.ApplicationState.tavernPageOpen = false
        lastBackTime = 0L
        consoleShown = true
        consoleStartRoute.value = startRoute
        setComposeScreen("console")
        tavernFullscreen = false
        WebViewBridge.setFullscreenState(false)
        SystemBarsController.apply(
            this,
            dark = ThemeState.isDarkMode.value,
            hideStatusBar = false,
        )
        scheduleAutoBackupWhenIdle()
        val keepAlive = com.tavern.app.console.SettingsState.keepTavernAlive()
        if (keepAlive) {
            webView?.pauseRendering()
        } else {
            MemoryDrainBridge.runner = null
            webView?.destroy()
            webView = null
            lastLoadedPort = 0
        }
        // A page the user never opened is not "in the background", whatever the
        // WebView's state: only an entry puts the tavern behind the console.
        tavernInBackground.value = webView != null && lastLoadedPort != 0 && tavernWasShown
        if (!contentViewIsCompose) {
        setContent {
            TavernTheme {
                Box(modifier = Modifier.fillMaxSize().imePadding()) {
                    AnimatedContent(
                        targetState = "console",
                        transitionSpec = {
                            (fadeIn(tween(300)) + scaleIn(initialScale = 0.98f, animationSpec = tween(300)))
                                .togetherWith(fadeOut(tween(200)))
                        },
                        label = "backToConsole"
                    ) { target ->
                        if (target == "console") {
                            ConsoleNavHost(
                                onBack = { handleConsoleExit() },
                                startRoute = startRoute,
                                onStartRouteApplied = { consoleStartRoute.value = "home" },
                                tavernInBackground = tavernInBackground.value,
                                // Read the live port when the tap happens. This Compose
                                // tree is installed once, so a captured port is the port
                                // the console was first shown with — after a port change
                                // the entry still opened the address the service had left.
                                onEnterTavern = { requestEnterTavern() },
                                onRestartNode = { restartNode() },
                                onRefreshTavern = { refreshTavern(NodeState.port.value) },
                                onStopNode = { stopNodeWithFeedback() },
                                onStartNode = { lifecycleScope.launch { doStartTavern() } },
                                onCancelEnterTavern = { cancelEnterTavern() }
                            )
                        }
                    }
                    CompatTavernGate()
                }
            }
        }
        contentViewIsCompose = true
        }
    }

    /**
     * The compat-rendering offer, drawn over whichever console tree is installed. Both
     * trees render it, so the entry behaves the same whether the console was opened from
     * the app's own surface or re-installed after the native page had it.
     */
    @Composable
    private fun CompatTavernGate() {
        val major = compatGateMajor.value ?: return
        var compatOn by remember(major) {
            mutableStateOf(com.tavern.app.console.SettingsState.compatModeEnabled())
        }
        com.tavern.app.console.components.CompatRenderDialog(
            webViewMajor = major,
            compatEnabled = compatOn,
            onCompatChange = { on ->
                compatOn = on
                com.tavern.app.console.SettingsState.setCompatMode(this, on)
                // A page parked behind the console re-reads its styles on the next load,
                // so an open one needs the reload signal for the switch to mean anything.
                com.tavern.app.webview.TavernRefresh.request()
                AppToast.show(this, if (on) "兼容渲染已开启" else "兼容渲染已关闭", Toast.LENGTH_SHORT)
            },
            notes = listOf(
                "在手机浏览器中打开可避开该问题，推荐 Edge 或 Chrome。",
                "稍后也可以在「设置 → 核心 → 核心优化 → 兼容渲染修复」中开启。",
            ),
            browserText = "用浏览器打开",
            onBrowser = {
                openTavernInBrowser()
                compatGateMajor.value = null
            },
            // Turning the switch on answers the question this dialog asks, so the way on
            // stops reading as a refusal and says what it now is.
            enterText = if (compatOn) "进入核心" else "仍然进入核心",
            enterIsPrimary = compatOn,
            onEnter = {
                compatGateMajor.value = null
                enterTavernNow()
            },
            // Closing without an answer leaves the reader in the console: the entry is the
            // only thing that opens the page, and the dialog comes back on the next try.
            onDismissRequest = { compatGateMajor.value = null },
        )
    }

    private fun scheduleAutoBackupWhenIdle() {
        if (!autoBackupScheduled.compareAndSet(false, true)) return
        lifecycleScope.launch {
            kotlinx.coroutines.delay(30_000L)
            while (isActive) {
                if (
                    composeScreen.value == "console"
                    && NodeState.state.value == NodeState.State.RUNNING
                ) {
                    com.tavern.app.update.PeriodicUpdateChecker
                        .checkWhenIdle(applicationContext)
                    com.tavern.app.backup.AutoBackupWorker
                        .checkAndBackupIfNeeded(applicationContext)
                    return@launch
                }
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }

    private fun recoverNodeFromConsole() {
        if (nodeStoppedByUser) return
        when (NodeState.state.value) {
            NodeState.State.IDLE,
            NodeState.State.ERROR -> {
                setComposeScreen("startup")
                startTavern()
            }
            NodeState.State.STARTING,
            NodeState.State.STOPPING -> setComposeScreen("startup")
            NodeState.State.RUNNING -> Unit
        }
    }

    /**
     * Reload the tavern UI from the node server without switching away from the
     * console (the refresh-page action): rebuild the WebView and load the current
     * URL in the background, so the next time the user opens the tavern it shows
     * fresh content instead of a stale snapshot.
     */
    /**
     * Reloads the tavern page, or defers it while that page is on screen. Reloading under
     * the user's finger reads as the app randomly refreshing, and the new data is only
     * needed the next time the page is opened anyway.
     */
    /**
     * A page that failed to load while the service is up on a different port is a page
     * pointed at the wrong port, not a broken service. The address that failed decides
     * this — the page can navigate itself back to a port it remembers, so bookkeeping
     * about what was last loaded says nothing about the attempt that just failed. A
     * cooldown and a small cap keep a real outage from turning into a reload loop.
     */
    private fun handleTavernLoadError(message: String) {
        AppToast.show(this@MainActivity, "加载失败：$message", Toast.LENGTH_LONG)
        if (NodeState.state.value != NodeState.State.RUNNING) return
        val failedPort = webView?.lastFailedUrl
            ?.let { Regex("127\\.0\\.0\\.1:(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val now = System.currentTimeMillis()
        if (now - lastLoadRecoveryAt < 4_000L || loadRecoveryCount >= 3) return
        lastLoadRecoveryAt = now
        loadRecoveryCount++
        lifecycleScope.launch {
            // The state's port is what the service reported, and it can lag a restart; the
            // port that answers is the only one worth loading, and the configured port is
            // the service's own source of truth for the next start.
            val candidates = listOf(NodeState.port.value, com.tavern.app.console.SettingsState.nodePort())
                .distinct()
                .filter { it != failedPort }
            val live = withContext(Dispatchers.IO) {
                candidates.firstOrNull { com.tavern.app.node.NodeRunner.isPortOpen(it) }
            } ?: return@launch
            if (NodeState.state.value != NodeState.State.RUNNING) return@launch
            android.util.Log.i("STCtrlRefresh", "retrying the page on port $live after a failed load")
            refreshTavern(live)
        }
    }

    private fun requestTavernReload(reason: String) {
        if (composeScreen.value == "webview") {
            pendingTavernReload = true
            android.util.Log.i("STCtrlRefresh", "reload deferred until next entry: $reason")
            return
        }
        android.util.Log.i("STCtrlRefresh", "reloading tavern page: $reason")
        refreshTavern(NodeState.port.value)
    }

    // The page-side extraction hook is the only caller of generateRaw, so the console reaches the
    // main API through here. The runner is rebound with the view rather than left pointing at a
    // destroyed WebView, which would answer every later request with an exception.
    private fun bindWebView(wv: TavernWebView) {
        webView = wv
        MemoryDrainBridge.runner = { maxBatches, onProgress ->
            drainMemoryThroughPage(maxBatches, onProgress)
        }
    }

    private suspend fun drainMemoryThroughPage(
        maxBatches: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): MemoryDrainState {
        val wv = webView ?: return MemoryDrainState(false, false, 0, 0, "no-page")

        // evaluateJavascript only reports a synchronous result and the drain loop is async, so the
        // page publishes progress on a global and this polls it. A destroyed view can swallow the
        // callback entirely, hence the per-call bound instead of waiting forever.
        suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        wv.evaluateJavascript(script) { raw ->
                            if (continuation.isActive) continuation.resume(raw.orEmpty())
                        }
                    } catch (exception: Exception) {
                        if (continuation.isActive) continuation.resume("\"\"")
                    }
                }
            }.orEmpty()
        }

        suspend fun readState(): JSONObject? {
            val raw = evaluate("JSON.stringify(globalThis.__stctrlDrainState || null)")
            val decoded = runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? String
            return decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

        if (!evaluate("typeof globalThis.stctrlDrainExtraction").contains("function")) {
            return MemoryDrainState(false, false, 0, 0, "core-too-old")
        }

        evaluate("(globalThis.stctrlDrainExtraction($maxBatches), 'ok')")

        // A long chat is dozens of batches at tens of seconds each. The cursor keeps whatever was
        // already stored, so a bounded wait that the reader can repeat beats an unbounded one.
        val deadline = System.currentTimeMillis() + 30 * 60 * 1000L
        var sawStarted = false
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            val state = readState() ?: continue
            if (state.optBoolean("started")) {
                sawStarted = true
                onProgress(state.optInt("batches"), state.optInt("processed"))
                if (!state.optBoolean("running")) {
                    return MemoryDrainState(
                        ok = state.optString("reason").isEmpty(),
                        done = state.optBoolean("done"),
                        batches = state.optInt("batches"),
                        processed = state.optInt("processed"),
                        reason = state.optString("reason"),
                    )
                }
            } else if (sawStarted) {
                return MemoryDrainState(false, false, 0, 0, "page-reloaded")
            }
        }
        return MemoryDrainState(false, false, 0, 0, "timeout")
    }

    private fun refreshTavern(port: Int) {
        val wv = webView?.also { it.resumeRendering() } ?: TavernWebView(this).apply {
            setOnPageLoaded { loadRecoveryCount = 0 }
            setOnError { msg -> handleTavernLoadError(msg) }
        }
        wv.applyPerformanceProfile()
        bindWebView(wv)
        wv.loadTavern(port)
        lastLoadedPort = port
        loadedCompatMode = com.tavern.app.console.SettingsState.compatModeEnabled()
        loadedPerfFactor = com.tavern.app.console.SettingsState.currentFactor()
        loadedTavernReloadHash = com.tavern.app.console.SettingsState.tavernReloadHash()
    }

    /** Switch to the tavern WebView, reusing the existing instance. */
    private fun showWebView(port: Int) {
        com.tavern.app.ApplicationState.tavernPageOpen = true
        tavernWasShown = true
        // Check WebView availability
        try { android.webkit.WebView(this) } catch (e: Exception) {
            AppToast.show(this, "未检测到 Android System WebView，无法加载核心。请从应用商店安装或启用 WebView。", Toast.LENGTH_LONG)
            return
        }
        val currentState = NodeState.state.value
        if (currentState != NodeState.State.RUNNING) {
            if (currentState == NodeState.State.ERROR || currentState == NodeState.State.IDLE) {
                AppToast.show(this, "核心服务未运行", Toast.LENGTH_SHORT)
                showConsole(port)
                return
            }
            // STARTING/STOPPING — service is coming up in the background
            // (fast-start path). Wait until it is actually healthy, then load.
            AppToast.show(this, "正在启动核心服务，请稍候…", Toast.LENGTH_SHORT)
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
                    AppToast.show(this@MainActivity, "核心服务启动超时", Toast.LENGTH_LONG)
                    showConsole(port)
                }
            }
            return
        }
        loadWebViewNow(port)
    }

    private fun loadWebViewNow(port: Int) {
        lastBackTime = 0L
        consoleShown = false
        WebViewBridge.setFullscreenState(tavernFullscreen)
        setComposeScreen("webview")
        SystemBarsController.applyTavern(
            this,
            tavernFullscreen,
        )
        startForegroundService()
        val wv = webView ?: TavernWebView(this).apply {
            setOnPageLoaded { loadRecoveryCount = 0 }
            setOnError { msg -> handleTavernLoadError(msg) }
            onFileChooserRequested = { callback, intent ->
                Log.w("MainActivity", "fileChooser launching, intent=$intent")
                pendingFileCallback?.onReceiveValue(null)  // cancel stale callback from prev Activity
                pendingFileCallback = callback
                launchFileChooser(intent)
            }
        }
        // Re-apply opt mode every time
        wv.applyPerformanceProfile()
        // Rebind on each reuse — the callback is lost when the WebView is recreated.
        wv.onFileChooserRequested = { callback, intent ->
            Log.w("MainActivity", "fileChooser launching")
            pendingFileCallback = callback
            launchFileChooser(intent)
        }
        wv.setOnPageLoaded { loadRecoveryCount = 0 }
        wv.setOnError { msg -> handleTavernLoadError(msg) }
        bindWebView(wv)
        // Reload when the port changed, on first load, or after a WebView
        // rendering setting changed while the existing instance stayed alive.
        val compatChanged = webView != null && loadedCompatMode != null &&
            loadedCompatMode != com.tavern.app.console.SettingsState.compatModeEnabled()
        val currentFactor = com.tavern.app.console.SettingsState.currentFactor()
        val lastPerf = loadedPerfFactor
        val perfChanged = lastPerf != null &&
            kotlin.math.abs(lastPerf - currentFactor) > 0.005
        val reloadHash = com.tavern.app.console.SettingsState.tavernReloadHash()
        val chatDomChanged = loadedTavernReloadHash != null && loadedTavernReloadHash != reloadHash
        if (lastLoadedPort != port || compatChanged || perfChanged || chatDomChanged || pendingTavernReload) {
            pendingTavernReload = false
            wv.loadTavern(port)
            lastLoadedPort = port
            loadedCompatMode = com.tavern.app.console.SettingsState.compatModeEnabled()
            loadedPerfFactor = currentFactor
            loadedTavernReloadHash = reloadHash
        }
        wv.resumeRendering()
        contentViewIsCompose = false
        setContentView(wv)
        SystemBarsController.applyTavern(this, tavernFullscreen)
        wv.requestApplyInsets()
    }

    private fun applyCurrentSystemBarMode() {
        if (composeScreen.value == "webview") {
            SystemBarsController.applyTavern(
                this,
                tavernFullscreen,
            )
        } else {
            SystemBarsController.apply(
                this,
                dark = ThemeState.isDarkMode.value,
                hideStatusBar = false,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        com.tavern.app.ApplicationState.appInForeground = true
        applyCurrentSystemBarMode()
        webView?.applyPerformanceProfile()
        if (composeScreen.value == "startup" &&
            NodeState.state.value != NodeState.State.STARTING &&
            NodeState.state.value != NodeState.State.STOPPING
        ) {
            resolveInitialScreen()
        } else if (composeScreen.value == "console") {
            recoverNodeFromConsole()
        }
    }

    override fun onPause() {
        if (activeInstance === this) activeInstance = null
        com.tavern.app.ApplicationState.appInForeground = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyCurrentSystemBarMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", composeScreen.value)
    }

    override fun onDestroy() {
        nodeStateReceiver?.let { unregisterReceiver(it) }
        stopNodeHeartbeat()
        stopLanProxyStatic()
        pendingSaveChunkFile?.delete()
        pendingSaveChunkFile = null
        WebViewBridge.cancelChunkExport()
        WebViewBridge.onSaveRequested = null
        WebViewBridge.onChunkComplete = null
        WebViewBridge.onFullscreenChanged = null
        WebViewBridge.setFullscreenState(false)
        if (activeInstance === this) activeInstance = null
        MemoryDrainBridge.runner = null
        webView?.destroy()
        webView = null
        keepAliveMonitor.cancel()
        super.onDestroy()
    }

    // IME insets: rely on system adjustResize. Like upstream, adjustResize
    // shrinks the WebView above the keyboard. No extra native-inset hacks here —
    // they fought adjustResize and caused keyboard/open-black-gap issues. The
    // page's --doc-height follow (tavern-mobile-inject.js) is enough.
    private fun installImeInsetsHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            // Let the system adjustResize do the work; consume nothing.
            insets
        }
    }
}
