package com.tavern.app.console.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ListGroup
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.PortEditDialog
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.node.NodeRunner
import com.tavern.app.node.NodeState
import androidx.compose.material.icons.outlined.Lock
import com.tavern.app.console.SettingsState
import com.tavern.app.ApplicationState

// The page opens with the state summary and the start/stop control, because
// that is the only action a user comes here to perform; details follow below.

@Composable
fun ServerStatusScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit,
    onStartNode: () -> Unit = {},
    onStopNode: () -> Unit = {},
    onRestartNode: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPortDialog by remember { mutableStateOf(false) }
    val state by viewModel.nodeState.collectAsState()
    val port by viewModel.nodePort.collectAsState()
    val startInFlight by viewModel.startInFlight.collectAsState()
    val isRunning = state == NodeState.State.RUNNING
    // A start attempt of its own is a fact the page can show even when the state
    // flow was overwritten by a stop that finished after the attempt began.
    val isStarting = state == NodeState.State.STARTING || startInFlight
    val isStopping = state == NodeState.State.STOPPING && !startInFlight
    val appColors = LocalAppColors.current

    PageScaffold(title = "服务器状态", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.xxl)
        ) {
            // The summary keeps its surface through a group of one, now that rows
            // carry no container of their own.
            ListGroup {
                ListRow(
                    icon = if (isRunning) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                    title = when {
                        isRunning -> "运行中"
                        isStarting -> "启动中"
                        isStopping -> "停止中"
                        else -> "已停止"
                    },
                    onClick = {},
                    trailing = {
                        Text(
                            text = if (isRunning) "端口 $port" else "—",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // Actions are not rows, so they carry the gutter the scroll container
            // no longer applies.
            val actionModifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg)
            when {
                isRunning -> SoftActionButton(
                    text = "停止服务",
                    onClick = onStopNode,
                    modifier = actionModifier,
                    color = appColors.error,
                    icon = Icons.Outlined.StopCircle
                )
                isStarting -> SoftActionButton(
                    text = "启动中",
                    onClick = {},
                    modifier = actionModifier,
                    enabled = false,
                    icon = Icons.Outlined.HourglassTop
                )
                isStopping -> SoftActionButton(
                    text = "停止中",
                    onClick = {},
                    modifier = actionModifier,
                    enabled = false,
                    color = appColors.error,
                    icon = Icons.Outlined.HourglassBottom
                )
                else -> SoftActionButton(
                    text = "启动服务",
                    onClick = onStartNode,
                    modifier = actionModifier,
                    color = appColors.success,
                    icon = Icons.Outlined.PlayArrow
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

            SectionLabel("运行信息")
            ListGroup {
                InfoRow(
                    icon = Icons.Outlined.Lan,
                    label = "端口",
                    value = port.toString(),
                    onClick = { showPortDialog = true },
                    actionIcon = Icons.Outlined.Edit
                )
                InfoRow(
                    icon = Icons.Outlined.Language,
                    label = "监听地址",
                    value = "127.0.0.1:$port",
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("", "http://127.0.0.1:$port")
                        )
                        com.tavern.app.util.AppToast.show(context, "地址已复制", android.widget.Toast.LENGTH_SHORT)
                    },
                    actionIcon = Icons.Outlined.ContentCopy
                )
                InfoRow(icon = Icons.Outlined.Terminal, label = "Node.js", value = "v24.20.0")
                InfoRow(
                    icon = Icons.Outlined.Tag,
                    label = "核心",
                    value = "1.19.0",
                    onClick = { onNavigate("update") },
                    navigates = true
                )
                InfoRow(
                    icon = Icons.Outlined.Info,
                    label = "启动超时",
                    value = "${NodeRunner.STARTUP_TIMEOUT_MS / 1000} 秒"
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

            // When LAN access is on the tavern is reachable at a different
            // address than the loopback one above, so the state belongs here
            // rather than only in settings.
            val lanOn by SettingsState.lanAccess.collectAsState()
            val lanIp = remember(lanOn) { ApplicationState.lanIp }
            val lanUrl = if (lanIp.isBlank()) "" else "http://$lanIp:7999"

            SectionLabel("局域网访问")
            ListGroup {
                if (lanOn) {
                    InfoRow(
                        icon = Icons.Outlined.Language,
                        label = "访问地址",
                        value = lanUrl.ifBlank { "未检测到局域网 IP" },
                        onClick = if (lanUrl.isNotBlank()) {
                            {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", lanUrl))
                                com.tavern.app.util.AppToast.show(context, "地址已复制", android.widget.Toast.LENGTH_SHORT)
                            }
                        } else null,
                        actionIcon = if (lanUrl.isNotBlank()) Icons.Outlined.ContentCopy else null
                    )
                    InfoRow(
                        icon = Icons.Outlined.Lock,
                        label = "访问控制",
                        value = if (SettingsState.lanCustomToken().isNotBlank()) "自定义 Token" else "随机 Token"
                    )
                } else {
                    InfoRow(
                        icon = Icons.Outlined.Language,
                        label = "局域网访问",
                        value = "未开启",
                        // The switch that turns this on lives on the home tab's
                        // access entry, so the row goes there rather than to the
                        // settings tab it used to point at.
                        onClick = { onNavigate("access") },
                        navigates = true
                    )
                }
            }

            if (showPortDialog) {
                PortEditDialog(
                    currentPort = port,
                    onDismiss = { showPortDialog = false },
                    onSaved = { onRestartNode() }
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    actionIcon: ImageVector? = null,
    navigates: Boolean = false
) {
    ListRow(icon = icon, title = label, onClick = onClick, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionIcon != null) {
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppDimens.iconSm)
                )
            }
            // Rows that open a page mark themselves with the shared chevron;
            // actionIcon is left for taps that act in place, such as editing the
            // port or copying the address.
            if (navigates) {
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                TrailingChevron()
            }
        }
    })
}
