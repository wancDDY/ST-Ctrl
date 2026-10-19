package com.tavern.app.console

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.tavern.app.console.components.CompletionNotice
import com.tavern.app.console.components.ConsoleTopBar
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListGroup
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.PortEditDialog
import com.tavern.app.console.components.RoundedProgressBar
import com.tavern.app.console.components.StatTile
import com.tavern.app.console.components.TaskPanel
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.components.appOutlinedButtonBorder
import com.tavern.app.console.components.appOutlinedButtonColors
import com.tavern.app.console.components.appSwitchColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.node.NodeRuntimeStats
import com.tavern.app.node.NodeState
import com.tavern.app.util.AppToast
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.JsonUtil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Home tab. The first block is live service data, so the screen opens with
// information rather than a list of entry points; navigation to the other
// areas lives in the bottom bar.

@Composable
fun ConsoleHomeTab(
    onEnterTavern: () -> Unit,
    tavernInBackground: Boolean = false,
    onRefreshTavern: () -> Unit,
    onNavigate: (String) -> Unit,
    onRestartNode: () -> Unit = {},
    tasks: List<ConsoleTask> = emptyList(),
    onGotoTask: (String) -> Unit = {},
    doneNotice: CompletionNotice? = null,
    versionNoticeText: String? = null,
    updateNoticeText: String? = null,
    onVersionNoticeClick: () -> Unit = {},
    onUpdateNoticeClick: () -> Unit = {},
    onCancelEnterTavern: () -> Unit = {},
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val state by NodeState.state.collectAsState()
    val port by NodeState.port.collectAsState()
    val runningSince by NodeState.runningSince.collectAsState()
    val progress by NodeState.progress.collectAsState()
    val progressKnown by NodeState.progressKnown.collectAsState()
    val phaseText by NodeState.phaseText.collectAsState()
    val lastError by NodeState.lastError.collectAsState()
    val startInFlight by NodeState.startInFlight.collectAsState()

    val isRunning = state == NodeState.State.RUNNING
    val isStarting = state == NodeState.State.STARTING || startInFlight
    var memoryMb by remember { mutableStateOf<Long?>(null) }
    var coreVersion by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showStarting by remember { mutableStateOf(false) }
    var showNotReady by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var portInput by remember { mutableStateOf("") }
    var keepAlive by remember { mutableStateOf(SettingsState.keepTavernAlive()) }
    var taskPanelOpen by remember { mutableStateOf(false) }
    // The list is the only thing that can close the panel from the inside: the tasks it lists can
    // finish while it is open, and an empty panel would stay on screen over the page.
    LaunchedEffect(tasks.isEmpty()) {
        if (tasks.isEmpty()) taskPanelOpen = false
    }
    // Back closes the panel before it leaves the console: an overlay that a back press ignored
    // would read as the console having no way out of it.
    BackHandler(enabled = taskPanelOpen) { taskPanelOpen = false }

    LaunchedEffect(Unit) {
        coreVersion = withContext(Dispatchers.IO) {
            val fromPackage = JsonUtil.readVersion(
                File(AssetExtractor.getCoreDir(context), "package.json"),
                "",
            )
            if (fromPackage.isNotBlank()) fromPackage else "1.19.0"
        }
    }

    // Poll RSS while the service runs; /proc scanning is cheap but not free,
    // and the value only matters while the process exists.
    LaunchedEffect(isRunning) {
        while (isRunning) {
            memoryMb = withContext(Dispatchers.IO) {
                NodeRuntimeStats.readNodeMemoryKb(context)?.div(1024)
            }
            delay(5_000)
        }
        if (!isRunning) memoryMb = null
    }

    // Uptime ticks once a second so the readout stays honest without needing
    // a recomposition per frame.
    LaunchedEffect(isRunning) {
        while (isRunning) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // The dialog reports a boot that is still running. Once the boot stops being in
    // flight the report is over — running means the tavern is opening, failed means
    // the console home carries the error — so the dialog closes itself instead of
    // parking at the end of its bar until the reader dismisses it by hand.
    LaunchedEffect(isStarting, showStarting) {
        if (showStarting && !isStarting) showStarting = false
    }

    if (showStarting) {
        AlertDialog(
            onDismissRequest = {
                showStarting = false
                onCancelEnterTavern()
            },
            title = { Text("正在启动服务", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = phaseText.ifEmpty { "准备中" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    RoundedProgressBar(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = LocalAppColors.current.accent
                    )
                }
            },
            confirmButton = {
                DialogConfirmButton(text = "后台等待", onClick = {
                    showStarting = false
                    onCancelEnterTavern()
                })
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg)
        )
    }

    if (showNotReady) {
        AlertDialog(
            onDismissRequest = { showNotReady = false },
            title = { Text("服务未启动", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "核心需要本地 Node.js 服务才能运行，请先在「管理 → 服务器状态」中启动服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                DialogConfirmButton(text = "前往服务器状态", onClick = {
                    showNotReady = false
                    onNavigate("status")
                })
            },
            dismissButton = {
                DialogDismissButton(text = "知道了", onClick = { showNotReady = false })
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg)
        )
    }

    if (showPortDialog) {
        PortEditDialog(
            currentPort = SettingsState.nodePort(),
            onDismiss = { showPortDialog = false },
            // The running service keeps answering on the old port until it is
            // restarted, so the change is only real once that restart runs; the
            // console cannot show the new number before then either.
            onSaved = { onRestartNode() }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConsoleTopBar(
                tasks = tasks,
                // One task is not a list: tapping goes straight to it, which is what the island
                // is for. Only a count has to be opened to be useful.
                onInflightClick = {
                    if (tasks.size > 1) {
                        taskPanelOpen = !taskPanelOpen
                    } else {
                        tasks.firstOrNull()?.let { onGotoTask(it.kind) }
                    }
                },
                doneNotice = doneNotice,
                versionNoticeText = versionNoticeText,
                updateNoticeText = updateNoticeText,
                onVersionNoticeClick = onVersionNoticeClick,
                onUpdateNoticeClick = onUpdateNoticeClick
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = AppSpacing.xxl)
            ) {
                // Data tiles are cards, not full-bleed rows, so they carry their own gutter.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg)
                ) {
                    StatTile(
                        label = "端口",
                        value = if (isRunning) port.toString() else "—",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            portInput = SettingsState.nodePort().toString()
                            showPortDialog = true
                        }
                    )
                    StatTile(
                        label = "运行时长",
                        value = if (isRunning && runningSince > 0) formatUptime(now - runningSince) else "—",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("status") }
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg)
                ) {
                    StatTile(
                        label = "内存",
                        value = memoryMb?.let { "$it MB" } ?: "—",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("status") }
                    )
                    StatTile(
                        label = "核心",
                        value = coreVersion.ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("update") }
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.lg))

                // Where the tavern was last opened from outside the app. The runtime
                // records entries whose user agent is not this app's WebView, which is
                // how a browser on the LAN shows up here.
                val externalEntry by produceState<String?>(initialValue = null, tavernInBackground) {
                    value = withContext(Dispatchers.IO) { readExternalEntry(context) }
                }

                PrimaryEnterAction(
                    isRunning = isRunning,
                    isStarting = isStarting,
                    status = entryStatus(
                        state = state,
                        phaseText = phaseText,
                        progress = progress,
                        progressKnown = progressKnown,
                        lastError = lastError,
                        tavernInBackground = tavernInBackground,
                        externalEntry = externalEntry,
                        startInFlight = startInFlight
                    ),
                    modifier = Modifier.padding(horizontal = AppSpacing.lg),
                    onClick = {
                        when {
                            isRunning -> onEnterTavern()
                            isStarting -> {
                                // Waiting in this dialog is a request to open the tavern;
                                // closing it withdraws the request.
                                showStarting = true
                                onEnterTavern()
                            }
                            else -> showNotReady = true
                        }
                    },
                    onOpenLogs = { onNavigate("logs") },
                    onRefreshPage = {
                        onRefreshTavern()
                        AppToast.show(context, "页面已刷新", android.widget.Toast.LENGTH_SHORT)
                    }
                )

                Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

                // No heading of its own: a section label over the three rows the
                // group holds would be the skeleton of a larger block. The
                // background switch sits with the service rows it belongs to rather
                // than after the entry points, which is where it ended up when the
                // settings page still owned it.
                ListGroup {
                    ListRow(
                        icon = Icons.Outlined.Dns,
                        title = "服务器状态",
                        subtitle = when {
                            isRunning -> "运行中，端口 $port"
                            isStarting -> "启动中"
                            else -> "已停止"
                        },
                        onClick = { onNavigate("status") }
                    )
                    // The row and its switch do the same thing, so the setting can
                    // be reached without aiming at the switch itself.
                    ListRow(
                        icon = Icons.Outlined.Autorenew,
                        title = "后台运行",
                        onClick = {
                            keepAlive = !keepAlive
                            SettingsState.setKeepTavernAlive(context, keepAlive)
                        },
                        trailing = {
                            Switch(
                                checked = keepAlive,
                                onCheckedChange = {
                                    keepAlive = it
                                    SettingsState.setKeepTavernAlive(context, it)
                                },
                                colors = appSwitchColors()
                            )
                        }
                    )
                    ListRow(
                        icon = Icons.Outlined.Language,
                        title = "浏览器与局域网",
                        onClick = { onNavigate("access") }
                    )
                }
            }
        }

        if (taskPanelOpen && tasks.isNotEmpty()) {
            // The scrim starts below the bar, so the island stays tappable and the same gesture
            // closes the panel it opened.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = AppDimens.topBarHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { taskPanelOpen = false }
            )
            TaskPanel(
                tasks = tasks,
                onPick = { kind ->
                    taskPanelOpen = false
                    onGotoTask(kind)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = AppDimens.topBarHeight + AppSpacing.xs, end = AppSpacing.lg)
            )
        }
    }
}

// Shared by the row that lays the controls out: an outlined border is the same on
// both, so the controls are told apart by their labels alone.
private val PlateActionPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
private val PlateActionGap = AppSpacing.md

// The controls draw 40dp and rely on their minimum interactive size to reach the
// 48dp touch target.
private val PlateActionShape = RoundedCornerShape(AppRadius.md)

private const val PLATE_TITLE = "SillyTavern"
private const val ACTION_LOGS = "运行日志"
private const val ACTION_REFRESH = "刷新页面"

@Composable
private fun PrimaryEnterAction(
    isRunning: Boolean,
    isStarting: Boolean,
    status: EntryStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpenLogs: () -> Unit,
    onRefreshPage: () -> Unit
) {
    val progressTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.subtle)

    Column(modifier = modifier.fillMaxWidth()) {
        // The plate holds only what has to be read before the tap: the name and
        // the progress of what that name is starting. The status line and the two
        // controls moved to the row underneath, so no height here is reserved for
        // elements the plate no longer draws. Its height follows its content at any
        // font scale, with no minimum of its own.
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.md)),

            shape = RoundedCornerShape(AppRadius.md),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.lg)
            ) {
                TitleAndPercent(status = status)
                if (status.progress != null || status.indeterminate) {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    RoundedProgressBar(
                        progress = status.progress.takeIf { !status.indeterminate },
                        color = LocalAppColors.current.accent,
                        trackColor = progressTrack
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        // Alongside the plate, not inside it: the row is not clickable, so a tap
        // meant for a control cannot also open the tavern. Only the two controls
        // are left: the sentence that used to open this row described the WebView's
        // state rather than what the user did, and read wrong often enough that it
        // was removed.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryPlateActions(
                onOpenLogs = onOpenLogs,
                onRefreshPage = onRefreshPage
            )
        }
    }
}

@Composable
private fun TitleAndPercent(status: EntryStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The only accent on the plate: it names the thing that is running.
        Text(
            text = PLATE_TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = LocalAppColors.current.accent
        )
        // The disclosure marker goes to the trailing edge, where every other row
        // that leads somewhere keeps it; against the title it read as part of the
        // name and left the rest of the plate looking empty.
        Spacer(modifier = Modifier.weight(1f))
        if (status.progress != null) {
            Text(
                text = "${(status.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
        }
        TrailingChevron()
    }
}

/**
 * The row's two controls, left to right: the log viewer, then the refresh.
 *
 * Both are outlined and share one geometry, one label size and one label colour.
 * Which action is the disruptive one is carried by the label, not by giving one
 * control a border and leaving the other bare: two controls sitting side by side
 * and styled differently read as two different kinds of thing, and the pair stops
 * looking like the pair of peer actions it is.
 */
@Composable
private fun EntryPlateActions(
    onOpenLogs: () -> Unit,
    onRefreshPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PlateActionGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onOpenLogs,
            modifier = Modifier.heightIn(min = AppDimens.controlHeightDense),
            shape = PlateActionShape,
            colors = appOutlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = appOutlinedButtonBorder(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PlateActionPadding
        ) {
            Text(text = ACTION_LOGS, style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
            onClick = onRefreshPage,
            modifier = Modifier.heightIn(min = AppDimens.controlHeightDense),
            shape = PlateActionShape,
            colors = appOutlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = appOutlinedButtonBorder(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PlateActionPadding
        ) {
            Text(text = ACTION_REFRESH, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * What the entry plate says under its name. Every branch is a state the user can
 * actually be in, so the line never falls back to a generic "starting" while
 * something more specific is known.
 */
private data class EntryStatus(
    val line: String,
    val progress: Float? = null,
    val indeterminate: Boolean = false,
    val error: Boolean = false
)

private fun entryStatus(
    state: NodeState.State,
    phaseText: String,
    progress: Float,
    progressKnown: Boolean,
    lastError: String?,
    tavernInBackground: Boolean,
    externalEntry: String?,
    startInFlight: Boolean = false
): EntryStatus = when {
    // A stop that finished after the boot began overwrites STARTING with IDLE;
    // the boot is still real, and a home screen claiming "service not started"
    // sends the user to a start button that is already running.
    startInFlight && state != NodeState.State.RUNNING && state != NodeState.State.ERROR ->
        EntryStatus(
            line = phaseText.ifEmpty { "正在启动服务…" },
            progress = progress,
            indeterminate = !progressKnown
        )
    else -> when (state) {
        NodeState.State.STARTING -> EntryStatus(
            line = phaseText.ifEmpty { "正在启动服务…" },
            progress = progress,
            indeterminate = !progressKnown
        )
        NodeState.State.STOPPING -> EntryStatus("正在停止服务…", indeterminate = true)
        NodeState.State.ERROR -> EntryStatus(
            line = lastError?.takeIf { it.isNotBlank() }?.let { "启动失败：$it" } ?: "启动失败",
            error = true
        )
        NodeState.State.RUNNING -> EntryStatus(
            when {
                tavernInBackground -> "核心在后台运行，点按返回"
                externalEntry != null -> "最近从其他方式进入：$externalEntry"
                else -> "尚未打开核心页面"
            }
        )
        NodeState.State.IDLE -> EntryStatus("服务未启动")
    }
}

/**
 * "10-05 08:12, 192.0.2.7" (an address from the range reserved for documentation)
 * for the last tavern entry that came from outside the app, or null when only this app
 * has opened it. The runtime writes the file; a missing file simply means no browser
 * has opened the tavern yet.
 */
private fun readExternalEntry(context: android.content.Context): String? {
    return try {
        val file = File(AssetExtractor.getCoreDir(context), "data/stctrl-access.json")
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val at = json.optLong("at", 0L)
        if (at <= 0L) return null
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
        val ip = json.optString("ip").removePrefix("/")
        if (ip.isBlank()) time else "$time，$ip"
    } catch (_: Exception) {
        null
    }
}

private fun formatUptime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
