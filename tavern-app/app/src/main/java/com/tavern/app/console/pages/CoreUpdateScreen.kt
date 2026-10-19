package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import androidx.compose.material3.IconButton
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ActionButton
import com.tavern.app.console.components.SoftActionButton
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.update.AppVersionSummary
import com.tavern.app.update.AppUpdateChecker
import com.tavern.app.update.PeriodicUpdateChecker
import com.tavern.app.update.TavernCoreVersionSummary
import com.tavern.app.update.UpdateChecker
import com.tavern.app.update.VersionComparator
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.tavern.app.console.components.PageTopBar
import com.tavern.app.console.theme.AppRadius

@Composable
fun CoreUpdateScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit,
    initialVersionSummaryExpanded: Boolean = false,
    /** Set when the console island announced an update and sent the reader here. */
    openPendingLogOnStart: Boolean = false,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = LocalAppColors.current.accent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onBackground
    val motion = LocalFluentMotion.current

    // ST core state
    var coreVersion by remember { mutableStateOf("加载中…") }
    var isCheckingCore by remember { mutableStateOf(false) }
    var coreUpdateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var coreCheckError by remember { mutableStateOf<String?>(null) }
    val coreUpdateState by viewModel.coreUpdateState.collectAsState()
    val isUpdatingCore = coreUpdateState is ConsoleViewModel.CoreUpdateState.Running
    val updateProgress = (coreUpdateState as? ConsoleViewModel.CoreUpdateState.Running)?.progress ?: 0f
    val updatePhase = (coreUpdateState as? ConsoleViewModel.CoreUpdateState.Running)?.phase ?: ""
    val updateCancellable =
        (coreUpdateState as? ConsoleViewModel.CoreUpdateState.Running)?.cancellable ?: false
    val updateCancelling =
        (coreUpdateState as? ConsoleViewModel.CoreUpdateState.Running)?.cancelling ?: false

    // Version list dropdown state
    var allVersions by remember { mutableStateOf<List<UpdateChecker.ReleaseInfo>?>(null) }
    var showVersionList by remember { mutableStateOf(false) }
    var selectedCoreLogVersion by remember { mutableStateOf<String?>(null) }
    var isLoadingVersions by remember { mutableStateOf(false) }
    var versionLoadError by remember { mutableStateOf<String?>(null) }

    // App update state
    var appVersion by remember {
        mutableStateOf(
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            },
        )
    }
    var isCheckingApp by remember { mutableStateOf(false) }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateChecker.AppRelease?>(null) }
    var appCheckError by remember { mutableStateOf<String?>(null) }
    var allAppVersions by remember { mutableStateOf<List<AppUpdateChecker.AppRelease>?>(null) }
    var showAppVersionList by remember { mutableStateOf(false) }
    var selectedAppLogVersion by remember { mutableStateOf<String?>(null) }
    var isLoadingAppVersions by remember { mutableStateOf(false) }
    var appVersionLoadError by remember { mutableStateOf<String?>(null) }
    var coreVersionSummaryExpanded by remember { mutableStateOf(false) }
    var versionSummaryExpanded by remember { mutableStateOf(initialVersionSummaryExpanded) }
    // What the island announced. Held here as well as in the update state so the log
    // dialogs can show the notes of a version whose release list was never fetched.
    var pendingAppChangelog by remember { mutableStateOf<String?>(null) }
    var pendingCoreChangelog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialVersionSummaryExpanded) {
        if (initialVersionSummaryExpanded) versionSummaryExpanded = true
    }

    // In-app download state for the ST Ctrl APK
    var apkDownloadTask by remember { mutableStateOf<DownloadTask?>(null) }
    var apkDownloadProgress by remember { mutableFloatStateOf(0f) }
    var apkDownloadPhase by remember { mutableStateOf("") }
    var apkDownloadJob by remember { mutableStateOf<Job?>(null) }
    var showCancelDownload by remember { mutableStateOf(false) }

    fun loadAppVersions(forceRefresh: Boolean = false) {
        isLoadingAppVersions = true
        appVersionLoadError = null
        scope.launch {
            val result = if (forceRefresh) AppUpdateChecker.refreshReleases() else AppUpdateChecker.listAllVersions()
            result.fold(
                onSuccess = { allAppVersions = it },
                onFailure = { appVersionLoadError = it.message }
            )
            isLoadingAppVersions = false
        }
    }

    fun appReleaseFor(version: String): AppUpdateChecker.AppRelease? {
        return allAppVersions?.firstOrNull { it.version == version }
            ?: appUpdateInfo?.takeIf { it.version == version }
    }

    fun loadVersions(forceRefresh: Boolean = false) {
        isLoadingVersions = true
        versionLoadError = null
        scope.launch {
            val result = if (forceRefresh) UpdateChecker.refreshVersions() else UpdateChecker.listAllVersions()
            result.fold(
                onSuccess = { allVersions = it },
                onFailure = { versionLoadError = it.message }
            )
            isLoadingVersions = false
        }
    }

    LaunchedEffect(Unit) {
        // Load cached versions silently on first open
        val cached = UpdateChecker.getCachedVersions()
        if (cached != null) allVersions = cached else loadVersions()
        val cachedApp = AppUpdateChecker.getCachedReleases()
        if (cachedApp != null) allAppVersions = cachedApp
        coreVersion = withContext(Dispatchers.IO) {
            val ver = com.tavern.app.util.JsonUtil.readVersion(
                File(AssetExtractor.getCoreDir(ctx), "package.json"), "")
            if (ver.isNotBlank()) ver else {
                val verFile = File(ctx.filesDir, "core_version.txt")
                if (verFile.exists()) verFile.readText().trim() else "未知"
            }
        }
        try {
            appVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {}
    }

    fun isNewer(available: String, current: String): Boolean {
        return VersionComparator.isNewer(available, current)
    }

    fun checkCoreUpdate() {
        isCheckingCore = true
        coreCheckError = null
        scope.launch {
            UpdateChecker.checkLatest().fold(
                onSuccess = { info ->
                    // The listing can lag behind an announcement (assets still uploading,
                    // cached feed). A stale answer must not hide the version the island
                    // already named, notes included.
                    val announced = coreUpdateInfo
                    coreUpdateInfo = if (announced != null && isNewer(announced.version, info.version)) {
                        announced
                    } else {
                        info
                    }
                    coreCheckError = if (isNewer(coreUpdateInfo!!.version, coreVersion)) null
                        else "已是最新版本"
                },
                onFailure = { coreCheckError = it.message ?: "检查失败" }
            )
            isCheckingCore = false
        }
    }

    fun applyCoreUpdate(info: UpdateChecker.ReleaseInfo) {
        coreCheckError = null
        viewModel.startCoreUpdate(info)
    }

    LaunchedEffect(coreUpdateState) {
        val state = coreUpdateState as? ConsoleViewModel.CoreUpdateState.Done ?: return@LaunchedEffect
        // A cancelled run never installed anything, so the version on screen has to come from the
        // core directory rather than from the release the reader walked away from.
        if (state.success && !state.cancelled) {
            coreVersion = state.version
            coreUpdateInfo = null
        } else {
            coreVersion = withContext(Dispatchers.IO) {
                val ver = com.tavern.app.util.JsonUtil.readVersion(
                    File(AssetExtractor.getCoreDir(ctx), "package.json"), "")
                if (ver.isNotBlank()) ver else "未知"
            }
        }
        coreCheckError = state.message
    }

    fun downloadApk(directUrl: String, version: String) {
        val downloadDir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(downloadDir, "update-v$safeVersion.apk")
        if (!directUrl.substringBefore('?').endsWith(".apk", ignoreCase = true)) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))
            return
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            AppToast.show(ctx, "请允许 ST Ctrl 安装应用后重试", Toast.LENGTH_LONG)
            try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            } catch (_: Exception) {
                try {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                } catch (_: Exception) {}
            }
            return
        }
        try { outFile.delete() } catch (_: Exception) {}
        val task = DownloadTask(
            url = directUrl,
            destFile = outFile,
            onProgress = { pct, _, _, phase ->
                apkDownloadProgress = pct
                apkDownloadPhase = phase
            }
        )
        apkDownloadTask = task
        apkDownloadProgress = 0f
        apkDownloadPhase = "准备下载…"
        apkDownloadJob = scope.launch {
            try {
                task.start()
                apkDownloadPhase = "下载完成"
                apkDownloadTask = null
                try {
                    val archive = ctx.packageManager.getPackageArchiveInfo(outFile.absolutePath, 0)
                        ?: throw Exception("无法读取安装包")
                    if (archive.packageName != ctx.packageName) {
                        throw Exception("安装包包名不匹配")
                    }
                    val currentVersion = try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
                    } catch (_: Exception) {
                        "0"
                    }
                    if (!VersionComparator.isNewer(archive.versionName ?: "0", currentVersion)) {
                        throw Exception("下载包版本不高于当前版本")
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", outFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    AppToast.show(ctx, "无法打开安装程序：${e.message}", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                apkDownloadPhase = if (e is kotlinx.coroutines.CancellationException) "已取消"
                    else "下载失败：${e.message?.take(40)}"
                apkDownloadTask = null
            }
        }
    }

    fun checkAppUpdate() {
        isCheckingApp = true
        appCheckError = null
        scope.launch {
            AppUpdateChecker.check().fold(
                onSuccess = { info ->
                    // Same reason as the core card: an answer older than the announced
                    // version must not take its place.
                    val announced = appUpdateInfo
                    appUpdateInfo = if (announced != null && isNewer(announced.version, info.version)) {
                        announced
                    } else {
                        info
                    }
                    appCheckError = if (isNewer(appUpdateInfo!!.version, appVersion)) null
                        else "已是最新版本"
                },
                onFailure = { appCheckError = it.message ?: "检查失败" }
            )
            isCheckingApp = false
        }
    }

    // The island announces what a check found while the app was closed. Seeding the
    // cards from it is what makes the reader land on the release notes instead of on a
    // page that looks up to date until it checks again; the checks below then run in the
    // background so a seeded card gains its download link and any newer release.
    LaunchedEffect(Unit) {
        val pending = PeriodicUpdateChecker.pending(ctx)
        pendingAppChangelog = pending.appChangelog
        pendingCoreChangelog = pending.coreChangelog
        pending.appVersion?.let { version ->
            appUpdateInfo = AppUpdateChecker.AppRelease(
                version = version,
                downloadUrl = "",
                directDownloadUrl = "",
                changelog = pending.appChangelog.orEmpty(),
            )
        }
        pending.coreVersion?.let { version ->
            coreUpdateInfo = UpdateChecker.ReleaseInfo(
                version = version,
                downloadUrl = "",
                changelog = pending.coreChangelog.orEmpty(),
            )
        }
        // An announced version has notes worth reading, so its card opens expanded; the
        // reader collapses it again with the same tap that opened it.
        if (pending.appVersion != null) versionSummaryExpanded = true
        if (pending.coreVersion != null) coreVersionSummaryExpanded = true
        // The app's notes are complete on the card, so only the core keeps a dialog: its
        // notes come from upstream and are long enough to want their own screen.
        if (openPendingLogOnStart && pending.coreVersion != null) {
            selectedCoreLogVersion = pending.coreVersion
        }
        if (pending.appVersion != null) checkAppUpdate()
        if (pending.coreVersion != null) checkCoreUpdate()
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
        PageTopBar(title = "版本与更新", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.page)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.lg))

            Text("内核更新", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = AppAlpha.onSurfaceSecondary), letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "当前应用内置核心 v${TavernCoreVersionSummary.version}。列表来自 " +
                    "wancDDY/ST-Ctrl 的 GitHub Releases，可选择已经发布过的版本重新安装或回退。" +
                    "点按版本右侧的“日志”可查看该核心版本的官方更新说明。",
                style = MaterialTheme.typography.labelSmall, color = muted, lineHeight = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))

            // Only emphasised shared cards are outlined, so a neutral hairline
            // here would set this card apart from the rest of the console.
            Surface(
                shape = RoundedCornerShape(AppRadius.md),
                color = surface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.md)),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.md))
                            .clickable { coreVersionSummaryExpanded = !coreVersionSummaryExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前版本", style = MaterialTheme.typography.bodySmall, color = muted)
                            Text("SillyTavern $coreVersion", style = MaterialTheme.typography.titleLarge, color = onBg)
                            // Names the relationship rather than implying this is the
                            // stock upstream build: the shipped core carries this
                            // project's mobile compatibility changes.
                            Text(
                                "官方内核，本项目在其上做了移动端兼容改动。",
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                        Icon(
                            if (coreVersionSummaryExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (coreVersionSummaryExpanded) "收起" else "展开",
                            tint = muted,
                            modifier = Modifier.size(AppDimens.iconMd),
                        )
                    }

                    AnimatedVisibility(
                        visible = coreVersionSummaryExpanded,
                        enter = motion.enterExpand(),
                        exit = motion.exitShrink(),
                    ) {
                        Column(modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.lg)) {
                            HorizontalDivider(color = onBg.copy(alpha = AppAlpha.hairline))
                            Spacer(modifier = Modifier.height(12.dp))
                            CoreVersionSummaryContent(
                                coreVersion = coreVersion,
                                accent = accent,
                                onBg = onBg,
                                muted = muted,
                            )
                        }
                    }

                    if (coreUpdateInfo != null && isNewer(coreUpdateInfo!!.version, coreVersion)) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = onBg.copy(alpha = AppAlpha.hairline))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("新版本 ${coreUpdateInfo!!.version}", style = MaterialTheme.typography.titleSmall, color = accent)
                            Spacer(modifier = Modifier.height(6.dp))
                            // The core's notes live upstream. This project links to them
                            // instead of copying them into its releases or its UI.
                            OfficialCoreLogLink(LocalContext.current, accent)
                            Spacer(modifier = Modifier.height(12.dp))
                            ActionButton(
                                text = if (isUpdatingCore) "更新中…" else "下载并安装",
                                onClick = { applyCoreUpdate(coreUpdateInfo!!) },
                                enabled = !isUpdatingCore,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (isUpdatingCore) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                            Spacer(modifier = Modifier.height(12.dp))
                            com.tavern.app.console.components.RoundedProgressBar(
                                progress = updateProgress,
                                modifier = Modifier.fillMaxWidth(),
                                color = accent,
                                trackColor = onBg.copy(alpha = AppAlpha.iconBadge)
                            )
                            if (updatePhase.isNotBlank()) {
                                Text(updatePhase, style = MaterialTheme.typography.labelSmall, color = muted,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Offered only while it is honoured. Past the download the core
                            // directory is being replaced, and a cancel button there was one the
                            // reader could press with nothing happening.
                            when {
                                // The phase line already says a cancel is under way; adding
                                // "cannot be cancelled" underneath contradicted it on screen.
                                updateCancelling -> Unit
                                // Neutral ink, like every other action on this page: stopping a
                                // download destroys nothing, and a semantic colour here read as a
                                // warning about the update rather than about the button.
                                updateCancellable -> SoftActionButton(
                                    text = "取消更新",
                                    onClick = { viewModel.cancelCoreUpdate() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                else -> Text(
                                    "正在替换核心，请保持应用在前台，完成后会自动重启服务",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = muted,
                                )
                            }
                        }
                    }
                    if (coreCheckError != null) {
                        // A call-off is neither good news nor bad: the reader asked for it. Red
                        // there read as a failure they had to do something about.
                        val statusSuccess = coreCheckError == "已是最新版本"
                            || coreCheckError?.startsWith("核心已更新") == true
                            || coreCheckError?.startsWith("已取消更新") == true
                        // Laid out like the app card's status line: both cards must
                        // read the same when the reader expands one after the other.
                        Text(coreCheckError!!, style = MaterialTheme.typography.bodySmall,
                            color = if (statusSuccess) muted else LocalAppColors.current.error,
                            modifier = Modifier.padding(
                                start = AppSpacing.lg,
                                end = AppSpacing.lg,
                                top = 10.dp,
                                bottom = 14.dp,
                            ))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SoftActionButton(
                text = if (isCheckingCore) "检查中…" else "检查 ST 更新",
                onClick = { checkCoreUpdate() },
                enabled = !isCheckingCore && !isUpdatingCore,
                modifier = Modifier.fillMaxWidth()
            )

            // Version list dropdown
            if (!isUpdatingCore) {
                Spacer(modifier = Modifier.height(10.dp))
                SoftActionButton(
                    text = if (showVersionList) "收起版本列表" else "选择安装版本",
                    onClick = {
                        showVersionList = !showVersionList
                        if (showVersionList && allVersions == null && !isLoadingVersions) loadVersions()
                    },
                    enabled = !isUpdatingCore,
                    modifier = Modifier.fillMaxWidth()
                )

                AnimatedVisibility(
                    visible = showVersionList,
                    enter = motion.enterExpand(),
                    exit = motion.exitShrink(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(AppRadius.md),
                        color = surface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("可用版本", style = MaterialTheme.typography.bodySmall, color = muted,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { loadVersions(true) },
                                    enabled = !isLoadingVersions) {
                                    if (isLoadingVersions) {
                                        CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                    } else {
                                        Icon(Icons.Outlined.Refresh, "刷新", tint = accent, modifier = Modifier.size(AppDimens.iconSm))
                                    }
                                }
                            }

                            if (versionLoadError != null) {
                                Text(versionLoadError!!, style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.error)
                            }

                            val versions = allVersions
                            if (versions == null && isLoadingVersions) {
                                CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(top = 8.dp))
                            } else if (versions.isNullOrEmpty()) {
                                Text("暂无可用版本", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 8.dp))
                            } else {
                                versions.forEach { info ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        shape = RoundedCornerShape(AppRadius.sm),
                                        color = surface
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("v${info.version}", style = MaterialTheme.typography.labelLarge, color = onBg)
                                                    if (info.version == coreVersion || !isNewer(info.version, coreVersion) && info.version != coreVersion) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(if (info.version == coreVersion) "当前" else "旧版", style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                                if (info.changelog.isNotBlank()) {
                                                    Text(info.changelog.take(80), style = MaterialTheme.typography.labelSmall,
                                                        color = muted, maxLines = 1)
                                                }
                                            }
                                            TextButton(onClick = {
                                                selectedCoreLogVersion = info.version
                                            }) {
                                                Text("日志", style = MaterialTheme.typography.bodySmall, color = muted)
                                            }
                                            TextButton(
                                                onClick = { applyCoreUpdate(info) },
                                                enabled = !isUpdatingCore && info.version != coreVersion
                                            ) {
                                                Text(
                                                    if (info.version == coreVersion) "已安装" else "安装",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (info.version == coreVersion) muted else LocalAppColors.current.action
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // App update
            Text("应用更新", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = AppAlpha.onSurfaceSecondary), letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(10.dp))

            val visibleAppUpdate = appUpdateInfo?.takeIf { isNewer(it.version, appVersion) }
            val hasAppError = appCheckError != null
            val hasAppCardDetails = versionSummaryExpanded || visibleAppUpdate != null || hasAppError

            Surface(
                shape = RoundedCornerShape(AppRadius.md),
                color = surface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.md)),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.md))
                            .clickable { versionSummaryExpanded = !versionSummaryExpanded }
                            .padding(
                                start = AppSpacing.lg,
                                end = AppSpacing.lg,
                                top = AppSpacing.lg,
                                bottom = if (hasAppCardDetails) AppSpacing.md else AppSpacing.lg,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前版本", style = MaterialTheme.typography.bodySmall, color = muted)
                            Text("ST Ctrl v$appVersion", style = MaterialTheme.typography.titleLarge, color = onBg)
                            Text(
                                "当前发布版本，主要改动见下方日志。",
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                        Icon(
                            if (versionSummaryExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (versionSummaryExpanded) "收起" else "展开",
                            tint = muted,
                            modifier = Modifier.size(AppDimens.iconMd),
                        )
                    }

                    AnimatedVisibility(
                        visible = versionSummaryExpanded,
                        enter = motion.enterExpand(),
                        exit = motion.exitShrink(),
                    ) {
                        Column(modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.lg)) {
                            HorizontalDivider(color = onBg.copy(alpha = AppAlpha.hairline))
                            Spacer(modifier = Modifier.height(12.dp))
                            // The notes published with the announced version, in full:
                            // this project writes them, so the card carries them whole
                            // instead of sending the reader to a second dialog.
                            visibleAppUpdate?.takeIf { it.changelog.isNotBlank() }?.let { update ->
                                Text(
                                    "新版本 ${update.version} 更新日志",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = accent,
                                )
                                Text(
                                    update.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = muted,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            VersionSummaryContent(
                                appVersion = appVersion,
                                remoteChangelog = appReleaseFor(appVersion)?.changelog,
                                accent = accent,
                                onBg = onBg,
                                muted = muted,
                            )
                        }
                    }

                    if (visibleAppUpdate != null) {
                        Column(modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = 14.dp)) {
                            HorizontalDivider(color = onBg.copy(alpha = AppAlpha.hairline))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("新版本 ${visibleAppUpdate.version}", style = MaterialTheme.typography.titleSmall, color = accent)
                            // The notes are the point of this card; without a link yet
                            // they still stand, and an instruction would only be noise.
                            if (visibleAppUpdate.directDownloadUrl.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                ActionButton(
                                    text = "下载 APK",
                                    onClick = {
                                        downloadApk(visibleAppUpdate.directDownloadUrl, visibleAppUpdate.version)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    if (appCheckError != null) {
                        Text(appCheckError!!, style = MaterialTheme.typography.bodySmall,
                            color = if (appCheckError == "已是最新版本") muted else LocalAppColors.current.error,
                            modifier = Modifier.padding(
                                start = AppSpacing.lg,
                                end = AppSpacing.lg,
                                top = 10.dp,
                                bottom = 14.dp,
                            ))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SoftActionButton(
                text = if (isCheckingApp) "检查中…" else "检查 App 更新",
                onClick = { checkAppUpdate() },
                enabled = !isCheckingApp,
                modifier = Modifier.fillMaxWidth()
            )

            // App version dropdown
            SoftActionButton(
                text = if (showAppVersionList) "收起版本列表" else "选择安装版本",
                onClick = {
                    showAppVersionList = !showAppVersionList
                    if (showAppVersionList && allAppVersions == null && !isLoadingAppVersions) loadAppVersions()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            AnimatedVisibility(
                visible = showAppVersionList,
                enter = motion.enterExpand(),
                exit = motion.exitShrink(),
            ) {
                Surface(
                    shape = RoundedCornerShape(AppRadius.md),
                    color = surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("可用版本", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                            IconButton(onClick = { loadAppVersions(true) }, enabled = !isLoadingAppVersions) {
                                if (isLoadingAppVersions) {
                                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                } else {
                                    Icon(Icons.Outlined.Refresh, "刷新", tint = accent, modifier = Modifier.size(AppDimens.iconSm))
                                }
                            }
                        }
                        if (appVersionLoadError != null) {
                            Text(appVersionLoadError!!, style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.error)
                        }
                        val appVersions = allAppVersions
                        if (appVersions == null && isLoadingAppVersions) {
                            CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(top = 8.dp))
                        } else if (appVersions.isNullOrEmpty()) {
                            Text("暂无可用版本", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 8.dp))
                        } else {
                            appVersions.forEach { info ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    shape = RoundedCornerShape(AppRadius.sm),
                                    color = surface
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("v${info.version}", style = MaterialTheme.typography.labelLarge, color = onBg)
                                                if (info.version == appVersion || !isNewer(info.version, appVersion) && info.version != appVersion) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(if (info.version == appVersion) "当前" else "旧版", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            if (info.changelog.isNotBlank()) {
                                                Text(info.changelog.take(80), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                                            }
                                        }
                                        TextButton(onClick = {
                                            selectedAppLogVersion = info.version
                                        }) {
                                            Text("日志", style = MaterialTheme.typography.bodySmall, color = muted)
                                        }
                                        if (info.version != appVersion) {
                                            TextButton(onClick = {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                                            }) {
                                                Text("下载", style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.action)
                                            }
                                        } else {
                                            Text("已安装", style = MaterialTheme.typography.bodySmall, color = muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
        }
    }

    selectedCoreLogVersion?.let { version ->
        val summary = TavernCoreVersionSummary.summaryFor(version)
        // The release body this project wrote for that core version. It is what the
        // reader opens the dialog for when the built-in translation does not cover the
        // version, and it is kept in full rather than previewed. The notes carried by
        // the update notice cover the case where the release list was never fetched.
        val announced = if (coreUpdateInfo?.version == version) pendingCoreChangelog else null
        val remoteChangelog = allVersions?.firstOrNull { it.version == version }?.changelog
            ?: announced?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { selectedCoreLogVersion = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("SillyTavern $version 更新日志") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (summary == null) {
                        if (remoteChangelog.isNullOrBlank()) {
                            Text(
                                "当前版本暂无内置官方更新日志。",
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        } else {
                            Text(
                                "来自 GitHub Releases",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                remoteChangelog,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 18.sp,
                                color = muted,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OfficialCoreLogLink(LocalContext.current, accent)
                    } else {
                        CoreVersionSummaryContent(
                            coreVersion = version,
                            accent = accent,
                            onBg = onBg,
                            muted = muted,
                        )
                    }
                }
            },
            confirmButton = {
                DialogDismissButton(
                    text = "关闭",
                    onClick = { selectedCoreLogVersion = null },
                )
            },
        )
    }

    selectedAppLogVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { selectedAppLogVersion = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("ST Ctrl v$version 更新日志") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    VersionSummaryContent(
                        appVersion = version,
                        // The release notes published with that version; the ones the
                        // update notice carried stand in when the release list is empty.
                        remoteChangelog = appReleaseFor(version)?.changelog
                            ?: (if (appUpdateInfo?.version == version) pendingAppChangelog else null)
                                ?.takeIf { it.isNotBlank() },
                        accent = accent,
                        onBg = onBg,
                        muted = muted,
                    )
                }
            },
            confirmButton = {
                DialogDismissButton(
                    text = "关闭",
                    onClick = { selectedAppLogVersion = null },
                )
            },
        )
    }

    if (showCancelDownload) {
        AlertDialog(
            onDismissRequest = { showCancelDownload = false },
            title = { Text("取消下载") },
            text = { Text("确定取消下载？已下载的部分将被删除。") },
            confirmButton = {
                DialogConfirmButton(text = "确定取消", color = LocalAppColors.current.error, onClick = {
                    showCancelDownload = false
                    apkDownloadTask?.cancel()
                    apkDownloadJob?.cancel()
                    apkDownloadTask = null
                    apkDownloadPhase = ""
                    apkDownloadProgress = 0f
                })
            },
            dismissButton = {
                DialogDismissButton(text = "继续下载", onClick = { showCancelDownload = false })
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

}

@Composable
private fun CoreVersionSummaryContent(
    coreVersion: String,
    accent: Color,
    onBg: Color,
    muted: Color,
) {
    val ctx = LocalContext.current
    val summary = TavernCoreVersionSummary.summaryFor(coreVersion)
    if (summary == null) {
        Text(
            "当前核心版本暂无内置官方更新日志。",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OfficialCoreLogLink(ctx, accent)
        return
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.NewReleases,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SillyTavern 官方更新日志",
                    style = MaterialTheme.typography.titleMedium,
                    color = onBg,
                )
                Text(
                    "v$coreVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            summary.first,
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 17.sp,
            color = muted,
        )

        summary.second.forEach { section ->
            VersionSectionBlock(
                section = section,
                accent = accent,
                onBg = onBg,
                muted = muted,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        OfficialCoreLogLink(ctx, accent)
    }
}

// The log shown here is a translation of the parts that changed what the app user
// sees; the upstream notes carry the rest. The link is the way to the full text
// without shipping every release note inside the APK.
private const val UPSTREAM_RELEASES_URL =
    "https://github.com/SillyTavern/SillyTavern/releases"

@Composable
private fun OfficialCoreLogLink(ctx: android.content.Context, accent: Color) {
    Text(
        text = "查看官方完整更新日志（GitHub Releases）",
        style = MaterialTheme.typography.bodySmall,
        color = accent,
        modifier = Modifier.clickable {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM_RELEASES_URL))
            )
        },
    )
}

@Composable
private fun VersionSummaryContent(
    appVersion: String,
    remoteChangelog: String? = null,
    accent: Color,
    onBg: Color,
    muted: Color,
) {
    val summary = AppVersionSummary.summaryFor(appVersion)
    if (summary == null) {
        if (remoteChangelog.isNullOrBlank()) {
            Text(
                "当前版本暂无应用更新日志。",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        } else {
            Text(
                "来自 GitHub Releases",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                remoteChangelog,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                color = muted,
            )
        }
        return
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.NewReleases,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "更新日志",
                    style = MaterialTheme.typography.titleMedium,
                    color = onBg,
                )
                Text(
                    // The upgrade range comes from the summary itself, so adding a
                    // release does not need a version check here.
                    summary.fromVersion
                        ?.let { "v$it → v$appVersion" }
                        ?: "v$appVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (summary.migrationSteps.isNotEmpty()) {
            VersionStepSection(
                title = summary.migrationTitle ?: "升级前请先备份",
                steps = summary.migrationSteps,
                accent = accent,
                onBg = onBg,
                muted = muted,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            summary.intro,
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 17.sp,
            color = muted,
        )

        summary.sections.forEach { section ->
            VersionSectionBlock(
                section = section,
                accent = accent,
                onBg = onBg,
                muted = muted,
            )
        }
    }
}

@Composable
private fun VersionSectionBlock(
    section: com.tavern.app.update.VersionSection,
    accent: Color,
    onBg: Color,
    muted: Color,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = onBg.copy(alpha = AppAlpha.hairline))
    Spacer(modifier = Modifier.height(13.dp))
    Text(
        section.title,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = accent,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        section.summary,
        style = MaterialTheme.typography.labelSmall,
        lineHeight = 17.sp,
        color = onBg.copy(alpha = AppAlpha.onSurfaceSecondary),
    )
    Spacer(modifier = Modifier.height(7.dp))
    section.items.forEach { item ->
        Row(
            modifier = Modifier.padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .size(5.dp)
                    .background(accent, RoundedCornerShape(AppRadius.pill)),
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                item,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 17.sp,
                color = muted,
            )
        }
    }
}

@Composable
private fun VersionStepSection(
    title: String,
    steps: List<String>,
    accent: Color,
    onBg: Color,
    muted: Color,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = onBg.copy(alpha = AppAlpha.hairline))
    Spacer(modifier = Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = onBg)
    Spacer(modifier = Modifier.height(8.dp))
    steps.forEachIndexed { index, step ->
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(accent.copy(alpha = AppAlpha.subtle), RoundedCornerShape(AppRadius.pill)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (index + 1).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                step,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 17.sp,
                color = muted,
            )
        }
    }
}
