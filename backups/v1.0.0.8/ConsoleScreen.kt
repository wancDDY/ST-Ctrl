package com.tavern.app.console

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.R
import com.tavern.app.console.components.ConsoleCard
import com.tavern.app.console.components.ConsoleTopBar
import com.tavern.app.console.components.RoundedProgressBar
import com.tavern.app.node.NodeState
import kotlin.math.abs

/** iOS-style rubber-band stretch at scroll bounds — no gray Material glow. */
private class StretchOverscrollConnection(
    private val scrollState: ScrollState,
    private val getY: () -> Float,
    private val setY: (Float) -> Unit
) : NestedScrollConnection {

    companion object {
        private const val MAX_PX = 250f
        private const val DAMPING = 0.35f
    }

    override fun onPostScroll(
        consumed: Offset, available: Offset, source: NestedScrollSource
    ): Offset {
        // Content shorter than viewport — nothing to scroll, nothing to stretch
        if (scrollState.maxValue == 0) return Offset.Zero
        // Only stretch on user drag, not programmatic scrolls
        if (source != NestedScrollSource.Drag) return Offset.Zero
        val atTop = !scrollState.canScrollBackward
        val atBottom = !scrollState.canScrollForward
        if ((atTop && available.y > 0f) || (atBottom && available.y < 0f)) {
            val cur = getY()
            val delta = available.y * DAMPING
            setY((cur + delta).coerceIn(-MAX_PX, MAX_PX))
            return available
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (abs(getY()) > 1f) setY(0f) // let animateFloatAsState spring back
        return Velocity.Zero
    }
}

@Composable
fun ConsoleScreen(onEnterTavern: () -> Unit, onNavigate: (String) -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val nodeState by NodeState.state.collectAsState()
    val nodeRunning = nodeState == NodeState.State.RUNNING
    val progress by NodeState.progress.collectAsState()
    val phaseText by NodeState.phaseText.collectAsState()
    var showNotReady by remember { mutableStateOf(false) }
    var showStarting by remember { mutableStateOf(false) }

    // Auto-enter tavern when node becomes ready while user is waiting
    LaunchedEffect(nodeState, showStarting) {
        if (showStarting && nodeState == NodeState.State.RUNNING) {
            showStarting = false
            onEnterTavern()
        }
    }

    // ── Stretch overscroll ──
    val scrollState = rememberScrollState()
    var stretchTarget by remember { mutableFloatStateOf(0f) }
    val isDragging = scrollState.isScrollInProgress

    val displayStretch by animateFloatAsState(
        targetValue = if (isDragging) stretchTarget else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val stretchConnection = remember(scrollState) {
        StretchOverscrollConnection(
            scrollState = scrollState,
            getY = { stretchTarget },
            setY = { stretchTarget = it }
        )
    }

    // Dialog: node is idle or stopped
    if (showNotReady) {
        AlertDialog(
            onDismissRequest = { showNotReady = false },
            title = { Text("服务未启动", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("酒馆需要 Node.js 服务才能运行。请前往「服务器状态」页面启动服务。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotReady = false
                    onNavigate("status")
                }) { Text("前往服务器状态", color = Color(0xFFD4A853)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotReady = false }) { Text("知道了") }
            },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Dialog: node is starting — show live progress, auto-dismiss when ready
    if (showStarting) {
        AlertDialog(
            onDismissRequest = { showStarting = false },
            title = { Text("正在启动酒馆服务", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    Text(
                        phaseText.ifEmpty { "准备中…" },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    RoundedProgressBar(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFD4A853)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStarting = false }) {
                    Text("后台等待", color = Color(0xFFD4A853))
                }
            },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
        // Scrollable content fills entire space — scrolls behind the top bar
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = displayStretch }
                .nestedScroll(stretchConnection)
                .verticalScroll(scrollState)
        ) {
            // Clear the overlaid top bar so content starts below it
            Spacer(modifier = Modifier.height(52.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = {
                    when (nodeState) {
                        NodeState.State.RUNNING -> onEnterTavern()
                        NodeState.State.STARTING -> showStarting = true
                        else -> showNotReady = true
                    }
                }),
                    shape = RoundedCornerShape(16.dp), color = surface,
                    border = BorderStroke(1.dp, Color(0xFFD4A853).copy(alpha = 0.25f))) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Home, null, tint = Color(0xFFD4A853), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("进入酒馆", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("· SillyTavern", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                        Box(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                            Image(painter = painterResource(id = R.drawable.sillytavern_logo),
                                contentDescription = "SillyTavern", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(horizontal = 8.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("备份", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConsoleCard(icon = Icons.Outlined.Archive, title = "创建备份", subtitle = "打包所有用户数据",
                        modifier = Modifier.weight(1f), surfaceColor = surface, onClick = { onNavigate("backup") })
                    ConsoleCard(icon = Icons.Outlined.Unarchive, title = "还原备份", subtitle = "从备份文件恢复",
                        modifier = Modifier.weight(1f), surfaceColor = surface, onClick = { onNavigate("restore") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ConsoleCard(icon = Icons.Outlined.Schedule, title = "自动备份", subtitle = "定时备份 · 保留多份",
                        modifier = Modifier.weight(1f), surfaceColor = surface, onClick = { onNavigate("auto_backup") })
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("管理", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleCard(icon = Icons.Outlined.Dns, title = "服务器状态", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("status") })
                    ConsoleCard(icon = Icons.Outlined.SdCard, title = "存储概览", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("storage") })
                    ConsoleCard(icon = Icons.Outlined.SystemUpdateAlt, title = "更新", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("update") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleCard(icon = Icons.Outlined.Extension, title = "扩展管理", subtitle = "扩展程序 · 角色卡",
                        modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("extensions") })
                    ConsoleCard(icon = Icons.Outlined.CleaningServices, title = "清除缓存", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("cache") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleCard(icon = Icons.Outlined.FolderOpen, title = "文件管理", subtitle = "浏览酒馆数据目录",
                        modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("files") })
                }
                Spacer(modifier = Modifier.height(80.dp))  // clearance for bottom FAB
            }
        }

        // Top bar — overlaid on top of scrollable content with solid background
        ConsoleTopBar()

        // Settings gear — bottom-right corner
        FloatingActionButton(
            onClick = { onNavigate("settings") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Color(0xFFD4A853),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
        ) {
            Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(22.dp))
        }
    }
}
