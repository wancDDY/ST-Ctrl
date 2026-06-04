package com.tavern.app

import android.Manifest
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Design tokens
private val DeepVoid       = Color(0xFF08080E)
private val VoidSurface    = Color(0xFF0E0E16)
private val AmberGlow      = Color(0xFFD4A853)
private val SoftGold       = Color(0xFFF0C969)
private val FadedAmber     = Color(0xFF8B6914)
private val EtherealPurple = Color(0xFF6B5B9E)
private val WarmWhite      = Color(0xFFF0EDE0)
private val MistGray       = Color(0xFF8A8A80)
private val ErrorRed       = Color(0xFFCC4455)
private val SuccessGreen   = Color(0xFF5AA87A)

class MainActivity : ComponentActivity() {

    private lateinit var nodeRunner: NodeRunner
    private val keepAliveMonitor by lazy { KeepAliveMonitor(this) }
    private var webView: TavernWebView? = null

    private var composeScreen by mutableStateOf("startup")

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
        val uris = when {
            result.resultCode == RESULT_OK && result.data?.clipData != null -> {
                val c = result.data!!.clipData!!
                Array(c.itemCount) { c.getItemAt(it).uri }
            }
            result.resultCode == RESULT_OK && result.data?.data != null -> {
                arrayOf(result.data!!.data!!)
            }
            else -> null
        }
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
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
        nodeRunner = NodeRunner(this)
        ThemeState.init(this)
        com.tavern.app.console.SettingsState.init(this)

        // Request all permissions on first launch
        val allPerms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                allPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                allPerms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT <= 32) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                allPerms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (allPerms.isNotEmpty()) {
            storagePermissionLauncher.launch(allPerms.toTypedArray())
        }

        setContent {
            TavernTheme {
                AnimatedContent(
                    targetState = composeScreen,
                    transitionSpec = {
                        (fadeIn(tween(350)) + scaleIn(initialScale = 0.97f, animationSpec = tween(350)))
                            .togetherWith(fadeOut(tween(250)) + scaleOut(targetScale = 1.03f, animationSpec = tween(250)))
                    },
                    label = "screenTransition"
                ) { screen ->
                    when (screen) {
                        "console" -> ConsoleNavHost(
                            onBack = { },
                            startRoute = "home",
                            onEnterTavern = { showWebView(NodeState.port.value) }
                        )
                        else -> StartupScreen(onStart = { startTavern() })
                    }
                }
            }
        }

        var lastBackTime = 0L
        var handlingBack = false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null) {
                    if (wv.canGoBack()) {
                        wv.goBack()
                        return
                    }
                    // no history left, check ST overlay via JS before going back to console
                    if (handlingBack) return
                    handlingBack = true
                    wv.evaluateJavascript(
                        "(function(){if(window.__tavernIsOverlayOpen&&window.__tavernIsOverlayOpen()){window.__tavernCloseOverlay();return'1';}return'0';})()"
                    ) { result ->
                        handlingBack = false
                        if (isDestroyed || isFinishing) return@evaluateJavascript
                        if (result != "\"1\"" && result != "1") {
                            showConsole(NodeState.port.value)
                        }
                    }
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackTime < 2000) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    } else {
                        lastBackTime = now
                        Toast.makeText(this@MainActivity, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        startTavern()
    }

    private fun startTavern() {
        val currentState = NodeState.state.value
        if (currentState == NodeState.State.STARTING || currentState == NodeState.State.RUNNING) {
            return
        }

        lifecycleScope.launch {
            try {
                NodeState.setStarting()

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
                NodeState.setProgress(0.3f, "启动酒馆服务…")

                val result = nodeRunner.start(
                    coreDir = coreDir,
                    port = TavernApplication.DEFAULT_PORT,
                    onProgress = { progress, phase ->
                        // remap 0..1 → 0.3..0.95
                        val mapped = 0.3f + progress * 0.65f
                        NodeState.setProgress(mapped, phase)
                    }
                )

                NodeState.setProgress(0.97f, "加载酒馆界面…")

                result.fold(
                    onSuccess = { port ->
                        startForegroundService()
                        keepAliveMonitor.schedule()
                        runOnUiThread {
                            showConsole(port)
                        }
                    },
                    onFailure = { error ->
                        NodeState.setError(error.message ?: "未知错误")
                    }
                )
            } catch (e: Exception) {
                NodeState.setError(e.message ?: "未知异常")
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
        webView = null
        // Re-attach ComposeView when coming back from WebView (setContentView replaced it)
        setContent {
            TavernTheme {
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
                        onEnterTavern = { showWebView(port) }
                    )
                }
            }
        }
    }

    /** 切换到 WebView 加载酒馆 */
    private fun showWebView(port: Int) {
        if (NodeState.state.value != NodeState.State.RUNNING) {
            NodeState.setRunning(port)
        }
        startForegroundService()
        val perfMode = com.tavern.app.console.SettingsState.perfMode.value
        val wv = webView ?: TavernWebView(this).apply {
            setOnPageLoaded { }
            setOnError { msg ->
                Toast.makeText(this@MainActivity, "加载失败: $msg", Toast.LENGTH_LONG).show()
            }
            onFileChooserRequested = { callback, intent ->
                pendingFileCallback = callback
                fileChooserLauncher.launch(Intent.createChooser(intent, "选择文件"))
            }
        }
        // Re-apply perf mode every time (WebView may be reused across mode changes)
        wv.applyPerfMode(perfMode)
        webView = wv
        wv.loadTavern(port)
        setContentView(wv)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun TavernTheme(content: @Composable () -> Unit) {
    val isDark by ThemeState.isDarkMode.collectAsState()
    val scheme = if (isDark) {
        darkColorScheme(background = DeepVoid, surface = VoidSurface, primary = AmberGlow, onBackground = WarmWhite, onSurface = WarmWhite)
    } else {
        lightColorScheme(background = Color(0xFFF5F3EE), surface = Color(0xFFFFFFFF), primary = Color(0xFFB8921A), onBackground = Color(0xFF1A1A1A), onSurface = Color(0xFF1A1A1A))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "glow"
    )

    // reverse mode avoids the Restart jump at loop end
    val bubblePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "bubbles"
    )

    val bubbles = remember {
        val rng = Random(42)
        List(60) {
            Bubble(
                x = rng.nextFloat(),
                baseY = rng.nextFloat(),
                radius = rng.nextFloat() * 12f + 3f,
                speed = rng.nextFloat() * 0.3f + 0.1f,
                opacity = rng.nextFloat() * 0.4f + 0.15f
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val cx = w * 0.5f
        val cy = h * 0.4f
        val glowRadius = size.minDimension * 0.55f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    AmberGlow.copy(alpha = glowAlpha * 0.4f),
                    AmberGlow.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = glowRadius
            ),
            radius = glowRadius,
            center = Offset(cx, cy)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    EtherealPurple.copy(alpha = glowAlpha * 0.2f),
                    Color.Transparent
                ),
                center = Offset(cx * 1.5f, cy * 1.7f),
                radius = glowRadius * 0.6f
            ),
            radius = glowRadius * 0.6f,
            center = Offset(cx * 1.5f, cy * 1.7f)
        )

        bubbles.forEach { b ->
            val bubbleY = ((b.baseY - bubblePhase * b.speed) % 1.2f + 1.2f) % 1.2f - 0.1f
            val bubbleX = b.x + 0.03f * sin(bubblePhase * 8f + b.baseY * 6f)
            val bubbleAlpha = when {
                bubbleY < 0.05f -> b.opacity * (bubbleY / 0.05f)       // fade in from bottom
                bubbleY > 0.85f -> b.opacity * ((1.1f - bubbleY) / 0.25f) // fade out at top
                else -> b.opacity
            }.coerceIn(0f, 1f)

            drawCircle(
                color = AmberGlow.copy(alpha = bubbleAlpha),
                radius = b.radius,
                center = Offset(bubbleX * w, bubbleY * h)
            )
        }
    }
}

private data class Bubble(
    val x: Float,
    val baseY: Float,
    val radius: Float,
    val speed: Float,
    val opacity: Float
)

@Composable
fun PulseRing(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "ring"
    )

    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "ringAlpha"
    )

    Canvas(modifier = modifier.size(80.dp)) {
        drawCircle(
            color = AmberGlow.copy(alpha = ringAlpha),
            radius = size.minDimension / 2,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = AmberGlow.copy(alpha = ringAlpha * 1.5f),
            radius = (size.minDimension / 2) * ringScale,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")

    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "shimmer"
    )

    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        color = WarmWhite.copy(alpha = shimmerAlpha),
        modifier = modifier
    )
}

@Composable
fun StartupScreen(onStart: () -> Unit) {
    val state by NodeState.state.collectAsState()
    val error by NodeState.lastError.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(DeepVoid)) {
        AmbientBackground()

        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(animationSpec = tween(400)) +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(400)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(300)) +
                            scaleOut(targetScale = 1.04f, animationSpec = tween(300))
                    )
            },
            label = "stateTransition",
            modifier = Modifier.fillMaxSize()
        ) { currentState ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (currentState) {
                    NodeState.State.IDLE,
                    NodeState.State.STARTING -> LoadingContent()

                    NodeState.State.STOPPING -> StoppingContent()

                    NodeState.State.ERROR -> ErrorContent(
                        error = error ?: "未知错误",
                        onRetry = onStart
                    )

                    NodeState.State.RUNNING -> {
                        Text(
                            "✦",
                            fontSize = 24.sp,
                            color = AmberGlow.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingContent() {
    val phaseText by NodeState.phaseText.collectAsState()
    val rawProgress by NodeState.progress.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "breathe"
    )

    // Dot animation
    val dotCount by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "dots"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with breathing glow
        Box(contentAlignment = Alignment.Center) {
            PulseRing()
            Text("🍺", fontSize = 36.sp, modifier = Modifier.alpha(breatheAlpha))
        }

        Spacer(modifier = Modifier.height(32.dp))

        // App title — subtle breathing
        Text(
            "ST Ctrl",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = WarmWhite.copy(alpha = breatheAlpha * 0.6f + 0.4f),
            letterSpacing = 3.sp
        )

        Spacer(modifier = Modifier.height(56.dp))

        // Phase text with animated dots
        AnimatedContent(
            targetState = phaseText,
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(initialScale = 0.98f, animationSpec = tween(400)))
                    .togetherWith(fadeOut(tween(250)))
            },
            label = "phaseText"
        ) { text ->
            val display = text.ifEmpty { "准备中" }
            val dots = ".".repeat(dotCount.toInt())
            Text(
                text = "$display$dots",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WarmWhite.copy(alpha = 0.85f)
            )
        }

        // Hint for first launch
        if (rawProgress < 0.3f) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "首次启动需要解压资源，请耐心等待",
                fontSize = 12.sp,
                color = MistGray.copy(alpha = 0.4f),
                fontWeight = FontWeight.Light
            )
        }
    }
}

// ─── Error Sub-screen ───────────────────────────────────────────
@Composable
fun ErrorContent(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 40.dp)
    ) {
        // Error icon with subtle glow
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(64.dp)) {
                drawCircle(
                    color = ErrorRed.copy(alpha = 0.1f),
                    radius = size.minDimension / 2
                )
            }
            Text("!", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "启动失败",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = WarmWhite,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            error,
            fontSize = 14.sp,
            color = MistGray,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.alpha(0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Retry button — amber glow outline style
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberGlow.copy(alpha = 0.15f),
                contentColor = AmberGlow
            ),
            border = ButtonDefaults.outlinedButtonBorder,
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 14.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text("重试", fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
        }
    }
}

// ─── Stopping Sub-screen ────────────────────────────────────────
@Composable
fun StoppingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PulseRing()
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "正在停止…",
            fontSize = 16.sp,
            color = MistGray,
            fontWeight = FontWeight.Light
        )
    }
}
