package com.tavern.app.console

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tavern.app.console.pages.*
import com.tavern.app.console.components.pendingNoticeText
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.update.AppVersionSummary
import com.tavern.app.update.PeriodicUpdateChecker
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors

private data class ConsoleTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

// Four top-level destinations. Sub-pages are pushed on top and hide the bar so
// they keep the full height for lists and editors.
private val ConsoleTabs = listOf(
    ConsoleTab("home", "首页", Icons.Outlined.Home),
    ConsoleTab("data", "数据", Icons.Outlined.Archive),
    ConsoleTab("extensions", "扩展", Icons.Outlined.Extension),
    ConsoleTab("settings", "设置", Icons.Outlined.Settings),
)

// Which top-level tab a sub-page belongs to, keyed by the route with its query
// and path arguments stripped. Used to highlight the owning tab and to route
// cross-tab jumps through that tab instead of stacking pages across tabs.
private val SubPageParent = mapOf(
    "backup_run" to "data",
    "restore" to "data",
    "auto_backup" to "data",
    "storage" to "data",
    "files" to "data",
    "status" to "home",
    "logs" to "home",
    // The clear-cache page is reached from the data tab's storage block, so it
    // belongs to that tab: pointing it at settings made Back land on a tab the
    // reader had never opened.
    "cache" to "data",
    "update" to "settings",
    "access" to "home",
    "settings_optimize" to "settings",
    "settings_perf" to "settings",
    "settings_about" to "settings",
)

// "update?summary=false" -> "update", "files/a%2Fb" -> "files".
private fun baseRoute(route: String?): String? =
    route?.substringBefore('?')?.substringBefore('/')

private fun selectedTabOf(route: String?): String? {
    val base = baseRoute(route) ?: return null
    return SubPageParent[base] ?: base
}

private fun coreUpdateTaskText(state: ConsoleViewModel.CoreUpdateState.Running): String {
    // Once a cancel was accepted the task is no longer an update: naming the version being
    // installed while the app is undoing that install read as two different operations.
    if (state.cancelling) return state.phase
    val phase = when {
        state.phase.contains("停止") -> "停止服务"
        state.phase.contains("下载") -> "下载核心"
        state.phase.contains("安装")
            || state.phase.contains("校验")
            || state.phase.contains("恢复") -> "安装核心"
        state.phase.contains("重启") -> "重启服务"
        else -> "更新中"
    }
    return "正在更新核心 v${state.version}，$phase"
}

@Composable
fun ConsoleNavHost(
    onBack: () -> Unit,
    startRoute: String = "home",
    onStartRouteApplied: () -> Unit = {},
    tavernInBackground: Boolean = false,
    onEnterTavern: () -> Unit,
    onRefreshTavern: () -> Unit = {},
    onRestartNode: () -> Unit = {},
    onStopNode: () -> Unit = {},
    onStartNode: () -> Unit = {},
    onCancelEnterTavern: () -> Unit = {}
) {
    val navController: NavHostController = rememberNavController()
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    if (activity == null) {
        Log.e("ConsoleNavHost", "LocalContext is not a ComponentActivity, cannot create ViewModel")
        return
    }
    val viewModel: ConsoleViewModel = viewModel(viewModelStoreOwner = activity)
    val motion = LocalFluentMotion.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val initialRoute = if (startRoute == "update") "update?summary=false" else startRoute
    LaunchedEffect(initialRoute) {
        if (initialRoute != "home" && navController.currentDestination?.route == "home") {
            navController.navigate(initialRoute) { launchSingleTop = true }
        }
        // Report the route as consumed. Leaving it set makes every later
        // recreation of this composable replay the jump, so leaving the tavern
        // throws the user back onto the update page instead of the home tab.
        if (initialRoute != "home") onStartRouteApplied()
    }

    // Collect long-running task state declared on the shared ViewModel so the
    // home top bar keeps showing progress after the user navigates away.
    val backupState by viewModel.backupState.collectAsState()
    val restoreOpState by viewModel.restoreState.collectAsState()
    val coreUpdateState by viewModel.coreUpdateState.collectAsState()
    val memoryTaskRunning by MemoryTaskState.running.collectAsState()
    val memoryScanRunning by MemoryScanState.running.collectAsState()
    val extensionInstall by com.tavern.app.console.pages.ExtensionInstall.progress.collectAsState()
    // Everything that outlives the page that started it. The island names the single entry when
    // there is one and counts them when there are more, so the order here is priority: the first
    // entry is the one a single-task tap follows. Quick, reader-blocking operations come first;
    // a memory pass runs for minutes behind them.
    val activeTasks: List<ConsoleTask> = buildList {
        (backupState as? ConsoleViewModel.OpState.Running)?.let { state ->
            add(
                ConsoleTask(
                    ConsoleTask.BACKUP,
                    if (state.current > 0) "正在备份 ${state.current}/${state.total}" else "正在备份"
                )
            )
        }
        (restoreOpState as? ConsoleViewModel.OpState.Running)?.let { state ->
            add(
                ConsoleTask(
                    ConsoleTask.RESTORE,
                    if (state.current > 0) "正在还原 ${state.current}/${state.total}" else "正在还原"
                )
            )
        }
        (coreUpdateState as? ConsoleViewModel.CoreUpdateState.Running)?.let { state ->
            add(ConsoleTask(ConsoleTask.CORE_UPDATE, coreUpdateTaskText(state)))
        }
        extensionInstall?.let { install ->
            add(
                ConsoleTask(
                    ConsoleTask.EXTENSION_INSTALL,
                    if (install.updateFrom != null) {
                        "正在更新 ${install.name}"
                    } else {
                        "正在安装 ${install.name}"
                    },
                    install.phase
                )
            )
        }
        memoryScanRunning?.let { scan ->
            add(
                ConsoleTask(
                    ConsoleTask.MEMORY_SCAN,
                    "收录旧聊天",
                    // The progress line carries a chat name and two counters on separate lines;
                    // the panel shows one line per task, so the break becomes a separator.
                    scan.text.replace('\n', ' ')
                )
            )
        }
        memoryTaskRunning?.let { task ->
            add(
                ConsoleTask(
                    // The chat is what this label has to carry. The island exists so the reader
                    // can get back to that pass, and only the chat says which one it is; the
                    // counts are shown by the page the tap opens.
                    ConsoleTask.MEMORY,
                    "整理记忆 ${task.chatTitle}",
                    if (task.batches == 0) "正在整理第 1 批…" else "${task.batches} 批，${task.processed} 条"                )
            )
        }
    }

    // One-shot completion notice. The raw state stays at Done, so track the
    // operation that actually entered Running instead of inferring from Done.
    var trackedTask by remember { mutableStateOf<String?>(null) }
    var doneNotice by remember { mutableStateOf<com.tavern.app.console.components.CompletionNotice?>(null) }
    var versionNoticeText by remember { mutableStateOf<String?>(null) }
    var updateNoticeText by remember { mutableStateOf<String?>(null) }
    val runningTask = activeTasks.firstOrNull()?.kind
    LaunchedEffect(backupState, restoreOpState, coreUpdateState) {
        if (runningTask != null) {
            trackedTask = runningTask
            if (doneNotice != null) {
                doneNotice = null
            }
        } else if (trackedTask != null) {
            val notice = when (trackedTask) {
                "backup" -> (backupState as? ConsoleViewModel.OpState.Done)?.let {
                    com.tavern.app.console.components.CompletionNotice(
                        success = it.success,
                        text = if (it.success) "备份完成" else "备份失败"
                    )
                }
                "restore" -> (restoreOpState as? ConsoleViewModel.OpState.Done)?.let {
                    com.tavern.app.console.components.CompletionNotice(
                        success = it.success,
                        text = if (it.success) "还原完成" else "还原失败"
                    )
                }
                "core_update" ->
                    (coreUpdateState as? ConsoleViewModel.CoreUpdateState.Done)?.let {
                        com.tavern.app.console.components.CompletionNotice(
                            // A cancelled update is not a failure: the message says what happened,
                            // and the glyph stays neutral rather than reporting an error.
                            success = it.success || it.cancelled,
                            text = if (it.cancelled) {
                                it.message
                            } else if (it.success) {
                                "核心更新完成"
                            } else {
                                "核心更新失败"
                            }
                        )
                    }
                else -> null
            }
            if (notice != null) {
                doneNotice = notice
                trackedTask = null
            }
        }
    }

    LaunchedEffect(doneNotice) {
        if (doneNotice != null) {
            kotlinx.coroutines.delay(2600)
            doneNotice = null
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val selectedTabRoute = selectedTabOf(currentRoute)

    // A switch that only the next service start can honour leaves this true until the reader
    // takes the restart. Shown only while the service is up: a stopped one reads the options
    // when it starts, so there is nothing to restart.
    val coreRestartPending by SettingsState.coreRestartPending.collectAsState()
    val nodeState by com.tavern.app.node.NodeState.state.collectAsState()

    // A check runs while the console may be closed, so the announcement waits
    // here for the next time the home tab is in front. Keyed on the notice text
    // so a version found later in the same session still gets announced once.
    val pendingUpdates by PeriodicUpdateChecker.pendingUpdates.collectAsState()
    // The announcement is a reminder: it holds the slot for five seconds and leaves.
    // Acting on it is optional, so it does not sit in the console bar afterwards.
    LaunchedEffect(currentRoute, pendingUpdates, doneNotice, versionNoticeText) {
        if (currentRoute != "home") return@LaunchedEffect
        if (doneNotice != null || versionNoticeText != null) return@LaunchedEffect
        // A first launch on a new build owes the reader that build's own summary
        // first; this effect runs again once the reminder releases the slot.
        if (SettingsState.shouldShowVersionSummary(context)) return@LaunchedEffect
        val notice = pendingNoticeText(pendingUpdates) ?: return@LaunchedEffect
        updateNoticeText = notice
        try {
            kotlinx.coroutines.delay(5000)
        } finally {
            updateNoticeText = null
        }
    }

    // The one-shot version reminder takes the slot for its five seconds; the update
    // announcement waits for it instead of the other way round, because that
    // announcement no longer expires and would otherwise hold the slot forever.
    LaunchedEffect(currentRoute) {
        if (currentRoute != "home") {
            return@LaunchedEffect
        }
        if (!SettingsState.shouldShowVersionSummary(context)) {
            return@LaunchedEffect
        }
        val versionName = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                ?: "当前版本"
        } catch (_: Exception) {
            "当前版本"
        }
        versionNoticeText = AppVersionSummary.noticeText(versionName)
        SettingsState.markVersionSummarySeen(context)
        try {
            kotlinx.coroutines.delay(5000)
        } finally {
            versionNoticeText = null
        }
    }

    // Back on a sub-page pops it; back on a top-level tab leaves the console.
    BackHandler {
        if (!navController.popBackStack()) onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .clipToBounds()
            // A tap on empty space means the reader is done typing, and a caret left blinking in a
            // field somewhere behind the page they moved on to reads as the app ignoring them.
            // The gesture only fires for taps no child consumed, which is what keeps buttons,
            // fields and list rows working: each of those takes its own tap first.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        val tabOrder = ConsoleTabs.map { it.route }
        Box(modifier = Modifier.weight(1f).imePadding()) {
            NavHost(
                navController = navController,
                startDestination = "home",
                // Edge to edge turns off the platform's own resize: with
                // setDecorFitsSystemWindows(false) the window keeps its full height when the keyboard
                // comes up, so every field under it stays under it. Applying the inset on the
                // container above shrinks the whole content area once, for every page, and Compose's
                // bring-into-view then scrolls the focused field above the keyboard. Insets are
                // consumed as they are applied, so a surface that pads for the keyboard itself (the
                // file editor) sees nothing left to add and is not padded twice.
                modifier = Modifier.fillMaxSize(),
                // Moving between sibling tabs slides in from the direction of travel;
                // pushing or popping a sub-page keeps the plain fade so the two
                // motions stay readable as different actions.
                enterTransition = {
                    val from = tabOrder.indexOf(initialState.destination.route)
                    val to = tabOrder.indexOf(targetState.destination.route)
                    if (from >= 0 && to >= 0 && from != to) {
                        val direction = if (to > from) 1 else -1
                        slideInHorizontally(animationSpec = tween(motion.normalMs)) { width ->
                            direction * width / 8
                        } + fadeIn(animationSpec = tween(motion.normalMs))
                    } else {
                        fadeIn(animationSpec = tween(motion.quickMs))
                    }
                },
                exitTransition = { fadeOut(animationSpec = tween(motion.quickMs / 2)) },
                popEnterTransition = { fadeIn(animationSpec = tween(motion.quickMs)) },
                popExitTransition = { fadeOut(animationSpec = tween(motion.quickMs / 2)) }
            ) {
                composable("home") {
                    ConsoleHomeTab(
                        onEnterTavern = onEnterTavern,
                        onCancelEnterTavern = onCancelEnterTavern,
                        tavernInBackground = tavernInBackground,
                        onRefreshTavern = onRefreshTavern,
                        onRestartNode = onRestartNode,
                        onNavigate = { route -> navController.navigateRoute(route) },
                        tasks = activeTasks,
                        doneNotice = doneNotice,
                        versionNoticeText = versionNoticeText,
                        updateNoticeText = updateNoticeText,
                        onVersionNoticeClick = {
                            versionNoticeText = null
                            navController.navigate("update?summary=true") { launchSingleTop = true }
                        },
                        onUpdateNoticeClick = {
                            updateNoticeText = null
                            // The notice announces a version, so the reader arrives wanting
                            // to know what changed: the page opens the release notes for it.
                            navController.navigate("update?summary=false&log=true") { launchSingleTop = true }
                        },
                        onGotoTask = { kind ->
                            // Each entry names the screen that owns the work. The two that live under
                            // the extensions hub also have to name where inside it the work is: that
                            // screen keeps its own remembered sub-tab, so arriving is not enough.
                            val target = when (kind) {
                                ConsoleTask.BACKUP -> "backup_run"
                                ConsoleTask.RESTORE -> "restore"
                                ConsoleTask.CORE_UPDATE -> "update?summary=false"
                                ConsoleTask.EXTENSION_INSTALL -> {
                                    ExtensionsTabRequest.request(0)
                                    "extensions"
                                }
                                ConsoleTask.MEMORY_SCAN -> {
                                    // Indexing is a property of the library rather than of one chat,
                                    // so the list is where its progress and result are shown.
                                    ExtensionsTabRequest.request(ExtensionsTabRequest.MEMORY_TAB)
                                    "extensions"
                                }
                                ConsoleTask.MEMORY -> {
                                    // The island names a chat, so the reader has to arrive at that
                                    // chat's memory rather than at the list of every chat with memory.
                                    val scopeKey = MemoryTaskState.running.value?.scopeKey
                                    if (scopeKey != null) {
                                        ExtensionsTabRequest.requestMemoryScope(scopeKey)
                                    } else {
                                        ExtensionsTabRequest.request(ExtensionsTabRequest.MEMORY_TAB)
                                    }
                                    "extensions"
                                }
                                else -> "home"
                            }
                            navController.navigate(target) { launchSingleTop = true }
                        }
                    )
                }
                composable("data") {
                    ConsoleDataTab(
                        viewModel = viewModel,
                        onNavigate = { route -> navController.navigateRoute(route) }
                    )
                }
                composable("extensions") {
                    ExtensionsHubScreen(
                        onBack = { },
                        onRefreshTavern = onRefreshTavern,
                        onNavigateToFiles = { path ->
                            val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                            navController.navigate("files/$encoded") { launchSingleTop = true }
                        },
                        asTab = true
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onNavigate = { route -> navController.navigateRoute(route) }
                    )
                }
                composable("access") {
                    SettingsAccessScreen(onBack = { navController.popBackStack() })
                }
                composable("settings_optimize") {
                    SettingsOptimizeScreen(onBack = { navController.popBackStack() })
                }
                composable("settings_perf") {
                    SettingsPerfScreen(onBack = { navController.popBackStack() })
                }
                composable("settings_about") {
                    SettingsAboutScreen(onBack = { navController.popBackStack() })
                }

                composable("backup_run") {
                    BackupScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable("restore") {
                    RestoreScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onRestoreComplete = { navController.popBackStack("home", false) },
                        onOpenInFileManager = { path ->
                            navController.navigate("files/${java.net.URLEncoder.encode(path, "UTF-8")}")
                        }
                    )
                }
                composable("auto_backup") {
                    AutoBackupScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable("status") {
                    ServerStatusScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onStartNode = onStartNode,
                        onStopNode = onStopNode,
                        onRestartNode = onRestartNode,
                        onNavigate = { route -> navController.navigateRoute(route) }
                    )
                }
                composable("logs") {
                    LogScreen(onBack = { navController.popBackStack() })
                }
                composable("storage") {
                    StorageScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable(
                    route = "update?summary={summary}&log={log}",
                    arguments = listOf(
                        androidx.navigation.navArgument("summary") {
                            type = androidx.navigation.NavType.BoolType
                            defaultValue = false
                        },
                        androidx.navigation.navArgument("log") {
                            type = androidx.navigation.NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) { backStackEntry ->
                    CoreUpdateScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        initialVersionSummaryExpanded = backStackEntry.arguments?.getBoolean("summary") == true,
                        openPendingLogOnStart = backStackEntry.arguments?.getBoolean("log") == true,
                    )
                }
                composable("cache") {
                    ClearCacheScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable("files") {
                    FileManagerScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = "files/{path}",
                    arguments = listOf(androidx.navigation.navArgument("path") { type = androidx.navigation.NavType.StringType })
                ) { backStackEntry ->
                    val path = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", "UTF-8")
                    FileManagerScreen(onBack = { navController.popBackStack() }, initialPath = path)
                }
            }

            // Sits over the page rather than in it: switching a startup-only option is a decision
            // to restart, not a reason to leave the page the switch is on. The two pages that put
            // their own action in this corner keep it: ours stacks above theirs instead of on top
            // of it. Material's FAB is 56dp and those pages pad it by 16dp, so clearing them costs
            // 72dp on top of the same margin.
            val cornerTaken = baseRoute(currentRoute) in setOf("extensions", "files")
            if (coreRestartPending &&
                nodeState == com.tavern.app.node.NodeState.State.RUNNING
            ) {
                val accent = LocalAppColors.current.accent
                ExtendedFloatingActionButton(
                    onClick = { onRestartNode() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = AppSpacing.lg,
                            bottom = AppSpacing.lg + (if (cornerTaken) 72.dp else 0.dp),
                        ),
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.surface,
                    icon = {
                        Icon(
                            Icons.Outlined.Autorenew,
                            contentDescription = null,
                            modifier = Modifier.size(AppDimens.iconMd),
                        )
                    },
                    text = { Text("重启核心服务") },
                )
            }
        }

        run {
            // Under adjustResize the window shrinks for the keyboard, so the tab bar
            // ends up sitting on top of the input field and reads as if it followed it.
            // Collapsed while the keyboard is up, the way chat apps behave.
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            // The bar draws edge to edge, so its own height has to leave the
            // system's bottom inset on top of the space the tabs need. Capping the
            // container at the tab height alone let NavigationBar spend part of that
            // height on its inset padding, which pushed the labels under the phone's
            // navigation bar on devices that still have one.
            val navigationBottom = WindowInsets.navigationBars
                .asPaddingValues()
                .calculateBottomPadding()
            val barHeight by animateDpAsState(
                targetValue = if (imeVisible) 0.dp else AppDimens.navBarHeight + navigationBottom,
                label = "navBarHeight",
            )
            // Container is capped so the bar stops eating vertical space; the
            // system inset is handled by the bar itself (no extra padding).
            NavigationBar(
                modifier = Modifier.height(barHeight),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                ConsoleTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTabRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    // Land on the tab's own root every time.
                                    // Saving and restoring tab state here also
                                    // replays the sub-page the user last had
                                    // open, so tapping a tab looks like it did
                                    // nothing. popUpTo home is enough to flatten
                                    // the stack back to a single tab.
                                    popUpTo(navController.graph.startDestinationId) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(AppDimens.iconMd),
                            )
                        },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

private fun NavHostController.navigateRoute(route: String) {
    val destination = if (route == "update") "update?summary=false" else route
    val owner = SubPageParent[baseRoute(destination)]
    // Sub-pages belong to a tab. Jumping into one from another tab switches to
    // that tab first, otherwise the settings tab ends up stacked above a home
    // page and Back disagrees with the highlighted tab.
    if (owner != null && owner != selectedTabOf(currentDestination?.route)) {
        navigate(owner) {
            popUpTo(graph.startDestinationId) { inclusive = false }
            launchSingleTop = true
        }
    }
    navigate(destination) { launchSingleTop = true }
}
