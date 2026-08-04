package com.tavern.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.DesignTokens.AmberGlow
import com.tavern.app.console.DesignTokens.DeepVoid
import com.tavern.app.console.DesignTokens.ErrorRed
import com.tavern.app.console.DesignTokens.EtherealPurple
import com.tavern.app.console.DesignTokens.MistGray
import com.tavern.app.console.DesignTokens.VoidSurface
import com.tavern.app.console.DesignTokens.WarmWhite
import com.tavern.app.console.ThemeState
import com.tavern.app.node.NodeState
import kotlin.math.sin
import kotlin.random.Random

// ─── Theme ───────────────────────────────────────────────────────

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

// ─── Ambient Background ──────────────────────────────────────────

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
                bubbleY < 0.05f -> b.opacity * (bubbleY / 0.05f)
                bubbleY > 0.85f -> b.opacity * ((1.1f - bubbleY) / 0.25f)
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

data class Bubble(
    val x: Float,
    val baseY: Float,
    val radius: Float,
    val speed: Float,
    val opacity: Float
)

// ─── Pulse Ring ──────────────────────────────────────────────────

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

// ─── Shimmer Text ────────────────────────────────────────────────

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

// ─── Startup Screen ──────────────────────────────────────────────

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
                        // Node ready — transition frame: the breathing pulse
                        // rings zoom outward and fade slowly. The main process
                        // switches to the console ~280ms in; the console fades
                        // in over ~350ms while the rings are still fading (they
                        // linger to ~550ms) so the background never flashes
                        // empty between the two.
                        var zoom by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { zoom = true }
                        val ringZoom by animateFloatAsState(
                            targetValue = if (zoom) 1.8f else 1f,
                            animationSpec = tween(450, easing = FastOutSlowInEasing),
                            label = "ringZoom"
                        )
                        val ringFade by animateFloatAsState(
                            targetValue = if (zoom) 0f else 1f,
                            animationSpec = tween(550, easing = LinearEasing),
                            label = "ringFade"
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.scale(ringZoom).alpha(ringFade)
                        ) {
                            PulseRing()
                            Text("🍺", fontSize = 36.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Loading Content ─────────────────────────────────────────────

@Composable
fun LoadingContent() {
    val phaseText by NodeState.phaseText.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "breathe"
    )

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
        Box(contentAlignment = Alignment.Center) {
            PulseRing()
            Text("🍺", fontSize = 36.sp, modifier = Modifier.alpha(breatheAlpha))
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "ST Ctrl",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = WarmWhite.copy(alpha = breatheAlpha * 0.6f + 0.4f),
            letterSpacing = 3.sp
        )

        Spacer(modifier = Modifier.height(56.dp))

        // Sequential phase captions (检查核心代码… → 启动酒馆服务… → …).
        AnimatedContent(
            targetState = phaseText,
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(initialScale = 0.98f, animationSpec = tween(400)))
                    .togetherWith(fadeOut(tween(250)))
            },
            label = "phaseText"
        ) { text ->
            val dots = ".".repeat(dotCount.toInt())
            val display = text.ifEmpty { "加载中" }
            Text(
                text = "$display$dots",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WarmWhite.copy(alpha = 0.85f)
            )
        }
    }
}

// ─── Error Content ───────────────────────────────────────────────

@Composable
fun ErrorContent(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 40.dp)
    ) {
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

// ─── Storage Permission Dialog ───────────────────────────────────

@Composable
fun StoragePermDialog(show: MutableState<Boolean>, pkg: String) {
    if (!show.value) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = { show.value = false },
        title = { Text("需要存储权限", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "ST-Ctrl 需要「所有文件访问」权限才能正常使用以下功能：\n\n" +
                "· 酒馆内导入角色卡、主题、扩展等文件\n" +
                "· Termux 数据迁移后读取备份\n" +
                "· 还原备份时浏览 ZIP 文件\n\n" +
                "仅用于上述场景，不会访问其他文件。",
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(onClick = {
                show.value = false
                try {
                    ctx.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$pkg")
                    ))
                } catch (_: Exception) {}
            }) { Text("去开启", color = AmberGlow) }
        },
        dismissButton = {
            TextButton(onClick = { show.value = false }) {
                Text("稍后", color = MistGray)
            }
        },
        containerColor = VoidSurface,
        titleContentColor = WarmWhite,
        textContentColor = WarmWhite.copy(alpha = 0.8f)
    )
}

// ─── Stopping Content ────────────────────────────────────────────

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