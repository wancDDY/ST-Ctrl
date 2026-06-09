package com.tavern.app.console.pages

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.node.NodeRunner
import com.tavern.app.update.AppUpdateChecker
import com.tavern.app.update.CoreUpdater
import com.tavern.app.update.UpdateChecker
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CoreUpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = Color(0xFFD4A853)
    val muted = Color(0xFF8A8A80)
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onBackground

    // ST core state
    var coreVersion by remember { mutableStateOf("加载中…") }
    var isCheckingCore by remember { mutableStateOf(false) }
    var isUpdatingCore by remember { mutableStateOf(false) }
    var coreUpdateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var coreCheckError by remember { mutableStateOf<String?>(null) }

    // Version list dropdown state
    var allVersions by remember { mutableStateOf<List<UpdateChecker.ReleaseInfo>?>(null) }
    var showVersionList by remember { mutableStateOf(false) }
    var isLoadingVersions by remember { mutableStateOf(false) }
    var versionLoadError by remember { mutableStateOf<String?>(null) }

    // App update state
    var appVersion by remember { mutableStateOf("1.0.0") }
    var isCheckingApp by remember { mutableStateOf(false) }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateChecker.AppRelease?>(null) }
    var appCheckError by remember { mutableStateOf<String?>(null) }
    var allAppVersions by remember { mutableStateOf<List<AppUpdateChecker.AppRelease>?>(null) }
    var showAppVersionList by remember { mutableStateOf(false) }
    var isLoadingAppVersions by remember { mutableStateOf(false) }
    var appVersionLoadError by remember { mutableStateOf<String?>(null) }

    // In-app download state for ST-Ctrl APK
    var apkDownloadTask by remember { mutableStateOf<DownloadTask?>(null) }
    var apkDownloadProgress by remember { mutableStateOf(0f) }
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
            val pkgJson = File(AssetExtractor.getCoreDir(ctx), "package.json")
            if (pkgJson.exists()) {
                try { org.json.JSONObject(pkgJson.readText()).optString("version", "未知") }
                catch (_: Exception) { "未知" }
            } else {
                val verFile = File(ctx.filesDir, "core_version.txt")
                if (verFile.exists()) verFile.readText().trim() else "未知"
            }
        }
        try {
            appVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {}
    }

    fun isNewer(available: String, current: String): Boolean {
        val a = available.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, c.size)) {
            val av = a.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (av > cv) return true
            if (av < cv) return false
        }
        return false
    }

    fun checkCoreUpdate() {
        isCheckingCore = true
        coreCheckError = null
        scope.launch {
            UpdateChecker.checkLatest().fold(
                onSuccess = { info ->
                    coreUpdateInfo = info
                    coreCheckError = if (isNewer(info.version, coreVersion)) null
                        else "已是最新版本"
                },
                onFailure = { coreCheckError = it.message ?: "检查失败" }
            )
            isCheckingCore = false
        }
    }

    var updateProgress by remember { mutableStateOf(0f) }
    var updatePhase by remember { mutableStateOf("") }

    var cancelCore by remember { mutableStateOf(false) }

    fun applyCoreUpdate(info: UpdateChecker.ReleaseInfo) {
        isUpdatingCore = true
        cancelCore = false
        updateProgress = 0f
        updatePhase = "正在停止服务…"
        scope.launch {
            // Stop Node.js first — can't delete files while they're in use
            withContext(Dispatchers.IO) { NodeRunner(ctx).stop() }
            CoreUpdater.applyUpdate(ctx, info.downloadUrl, info.version,
                onProgress = { prog, phase ->
                    updateProgress = prog
                    updatePhase = phase
                },
                isCancelled = { cancelCore }
            ).fold(
                onSuccess = {
                    isUpdatingCore = false
                    coreVersion = info.version
                    coreUpdateInfo = null
                    coreCheckError = null
                    updatePhase = ""
                    Toast.makeText(ctx, "更新完成，请重启应用", Toast.LENGTH_LONG).show()
                },
                onFailure = {
                    isUpdatingCore = false
                    updatePhase = ""
                    coreCheckError = "更新失败: ${it.message}"
                }
            )
        }
    }

    fun downloadApk(directUrl: String, version: String) {
        val outFile = File(ctx.getExternalFilesDir(null), "update-v$version.apk")
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
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", outFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "无法打开安装程序: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                apkDownloadPhase = if (e is kotlinx.coroutines.CancellationException) "已取消"
                    else "下载失败: ${e.message?.take(40)}"
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
                    appUpdateInfo = info
                    appCheckError = if (isNewer(info.version, appVersion)) null
                        else "已是最新版本"
                },
                onFailure = { appCheckError = it.message ?: "检查失败" }
            )
            isCheckingApp = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = accent, fontSize = 15.sp) }
            Spacer(modifier = Modifier.height(24.dp))
            Text("更新", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onBg)
            Text("ST 核心 · ST-Ctrl", fontSize = 13.sp, color = muted)
            Spacer(modifier = Modifier.height(20.dp))

            Text("ST 核心", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("更新源：wancDDY/ST-Ctrl Releases（GitHub）。ST 核心为预先打好 Android 兼容补丁的版本，非官方原版。tag 格式如 st-1.12.0。需稳定网络环境。",
                fontSize = 11.sp, color = muted, lineHeight = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(16.dp), color = surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前版本", fontSize = 12.sp, color = muted)
                            Text("SillyTavern $coreVersion", fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold, color = onBg)
                        }
                    }

                    if (coreUpdateInfo != null && isNewer(coreUpdateInfo!!.version, coreVersion)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = onBg.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("新版本 ${coreUpdateInfo!!.version}", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = accent)
                        if (coreUpdateInfo!!.changelog.isNotBlank()) {
                            Text(coreUpdateInfo!!.changelog.take(300), fontSize = 12.sp,
                                color = muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { applyCoreUpdate(coreUpdateInfo!!) },
                            enabled = !isUpdatingCore,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isUpdatingCore) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isUpdatingCore) "更新中…" else "下载并安装",
                                color = Color(0xFF08080E), fontWeight = FontWeight.Medium)
                        }
                    }

                    if (isUpdatingCore) {
                        Spacer(modifier = Modifier.height(12.dp))
                        com.tavern.app.console.components.RoundedProgressBar(
                            progress = updateProgress,
                            modifier = Modifier.fillMaxWidth(),
                            color = accent,
                            trackColor = onBg.copy(alpha = 0.1f)
                        )
                        if (updatePhase.isNotBlank()) {
                            Text(updatePhase, fontSize = 11.sp, color = muted,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { cancelCore = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFE05555).copy(alpha = 0.4f))
                        ) {
                            Text("取消更新", fontSize = 13.sp, color = Color(0xFFE05555))
                        }
                    }
                    if (coreCheckError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(coreCheckError!!, fontSize = 13.sp,
                            color = if (coreCheckError == "已是最新版本") Color(0xFF5AA87A) else Color(0xFFE05555))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { checkCoreUpdate() },
                enabled = !isCheckingCore && !isUpdatingCore,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isCheckingCore) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("检查 ST 更新", color = accent, fontSize = 14.sp)
            }

            // Version list dropdown
            if (!isUpdatingCore) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        showVersionList = !showVersionList
                        if (showVersionList && allVersions == null && !isLoadingVersions) loadVersions()
                    },
                    enabled = !isUpdatingCore,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (showVersionList) "收起版本列表" else "选择安装版本",
                        fontSize = 14.sp
                    )
                }

                AnimatedVisibility(visible = showVersionList) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = surface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("可用版本", fontSize = 13.sp, color = muted,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { loadVersions(true) },
                                    enabled = !isLoadingVersions,
                                    modifier = Modifier.size(24.dp)) {
                                    if (isLoadingVersions) {
                                        CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                    } else {
                                        Icon(Icons.Outlined.Refresh, "刷新", tint = accent, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (versionLoadError != null) {
                                Text(versionLoadError!!, fontSize = 12.sp, color = Color(0xFFE05555))
                            }

                            val versions = allVersions
                            if (versions == null && isLoadingVersions) {
                                CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(top = 8.dp))
                            } else if (versions.isNullOrEmpty()) {
                                Text("暂无可用版本", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 8.dp))
                            } else {
                                versions.forEach { info ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (info.version == coreVersion) Color(0xFF5AA87A).copy(alpha = 0.15f) else surface
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("v${info.version}", fontSize = 14.sp,
                                                        fontWeight = FontWeight.Medium, color = onBg)
                                                    if (info.version == coreVersion || !isNewer(info.version, coreVersion) && info.version != coreVersion) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(if (info.version == coreVersion) "当前" else "旧版", fontSize = 10.sp,
                                                            color = if (info.version == coreVersion) Color(0xFF5AA87A) else Color(0xFF8A8A80))
                                                    }
                                                }
                                                if (info.changelog.isNotBlank()) {
                                                    Text(info.changelog.take(80), fontSize = 11.sp,
                                                        color = muted, maxLines = 1)
                                                }
                                            }
                                            TextButton(
                                                onClick = { applyCoreUpdate(info) },
                                                enabled = !isUpdatingCore && info.version != coreVersion
                                            ) {
                                                Text(
                                                    if (info.version == coreVersion) "已安装" else "安装",
                                                    fontSize = 13.sp,
                                                    color = if (info.version == coreVersion) Color(0xFF5AA87A) else accent
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

            // ═══ App 更新 ═══
            Text("ST-Ctrl", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(16.dp), color = surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前版本", fontSize = 12.sp, color = muted)
                            Text("ST Ctrl v$appVersion", fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold, color = onBg)
                        }
                    }

                    if (appUpdateInfo != null && isNewer(appUpdateInfo!!.version, appVersion)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = onBg.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("新版本 ${appUpdateInfo!!.version}", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = accent)
                        if (appUpdateInfo!!.changelog.isNotBlank()) {
                            Text(appUpdateInfo!!.changelog.take(300), fontSize = 12.sp,
                                color = muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val info = appUpdateInfo!!
                                downloadApk(info.directDownloadUrl, info.version)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("下载 APK", color = Color(0xFF08080E), fontWeight = FontWeight.Medium)
                        }
                    }

                    if (appCheckError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(appCheckError!!, fontSize = 13.sp,
                            color = if (appCheckError == "已是最新版本") Color(0xFF5AA87A) else Color(0xFFE05555))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { checkAppUpdate() },
                enabled = !isCheckingApp,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isCheckingApp) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("检查 App 更新", color = accent, fontSize = 14.sp)
            }

            // App version dropdown
            OutlinedButton(
                onClick = {
                    showAppVersionList = !showAppVersionList
                    if (showAppVersionList && allAppVersions == null && !isLoadingAppVersions) loadAppVersions()
                },
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (showAppVersionList) "收起版本列表" else "选择安装版本", fontSize = 14.sp)
            }

            AnimatedVisibility(visible = showAppVersionList) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("可用版本", fontSize = 13.sp, color = muted, modifier = Modifier.weight(1f))
                            IconButton(onClick = { loadAppVersions(true) }, enabled = !isLoadingAppVersions,
                                modifier = Modifier.size(24.dp)) {
                                if (isLoadingAppVersions) {
                                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                } else {
                                    Icon(Icons.Outlined.Refresh, "刷新", tint = accent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        if (appVersionLoadError != null) {
                            Text(appVersionLoadError!!, fontSize = 12.sp, color = Color(0xFFE05555))
                        }
                        val appVersions = allAppVersions
                        if (appVersions == null && isLoadingAppVersions) {
                            CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(top = 8.dp))
                        } else if (appVersions.isNullOrEmpty()) {
                            Text("暂无可用版本", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 8.dp))
                        } else {
                            appVersions.forEach { info ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (info.version == appVersion) Color(0xFF5AA87A).copy(alpha = 0.15f) else surface
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("v${info.version}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onBg)
                                                if (info.version == appVersion || !isNewer(info.version, appVersion) && info.version != appVersion) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(if (info.version == appVersion) "当前" else "旧版", fontSize = 10.sp, color = if (info.version == appVersion) Color(0xFF5AA87A) else Color(0xFF8A8A80))
                                                }
                                            }
                                            if (info.changelog.isNotBlank()) {
                                                Text(info.changelog.take(80), fontSize = 11.sp, color = muted, maxLines = 1)
                                            }
                                        }
                                        if (info.version != appVersion) {
                                            TextButton(onClick = {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                                            }) {
                                                Text("下载", fontSize = 13.sp, color = accent)
                                            }
                                        } else {
                                            Text("已安装", fontSize = 13.sp, color = Color(0xFF5AA87A))
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

    if (showCancelDownload) {
        AlertDialog(
            onDismissRequest = { showCancelDownload = false },
            title = { Text("取消下载") },
            text = { Text("确定取消下载？已下载的部分将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDownload = false
                    apkDownloadTask?.cancel()
                    apkDownloadJob?.cancel()
                    apkDownloadTask = null
                    apkDownloadPhase = ""
                    apkDownloadProgress = 0f
                }) { Text("确定取消", color = Color(0xFFE05555)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDownload = false }) { Text("继续下载") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

}
