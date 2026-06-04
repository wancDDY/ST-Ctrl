package com.tavern.app.console.pages

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import kotlinx.coroutines.Dispatchers
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

    // Rollback state
    var hasBackup by remember { mutableStateOf(false) }
    var isRollingBack by remember { mutableStateOf(false) }

    // App update state
    var appVersion = remember { "1.0.0" }
    var isCheckingApp by remember { mutableStateOf(false) }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateChecker.AppRelease?>(null) }
    var appCheckError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        hasBackup = File(ctx.filesDir, "core_backup").exists()
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

    fun checkCoreUpdate() {
        isCheckingCore = true
        coreCheckError = null
        scope.launch {
            UpdateChecker.checkLatest().fold(
                onSuccess = { info ->
                    coreUpdateInfo = info
                    if (info.version == coreVersion) coreCheckError = "已是最新版本"
                },
                onFailure = { coreCheckError = it.message ?: "检查失败" }
            )
            isCheckingCore = false
        }
    }

    var updateProgress by remember { mutableStateOf(0f) }
    var updatePhase by remember { mutableStateOf("") }

    fun applyCoreUpdate(info: UpdateChecker.ReleaseInfo) {
        isUpdatingCore = true
        updateProgress = 0f
        updatePhase = "正在停止服务…"
        scope.launch {
            // Stop Node.js first — can't delete files while they're in use
            withContext(Dispatchers.IO) { NodeRunner(ctx).stop() }
            CoreUpdater.applyUpdate(ctx, info.downloadUrl, info.version) { prog, phase ->
                updateProgress = prog
                updatePhase = phase
            }.fold(
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

    fun checkAppUpdate() {
        isCheckingApp = true
        appCheckError = null
        scope.launch {
            AppUpdateChecker.check().fold(
                onSuccess = { info ->
                    appUpdateInfo = info
                    if (info.version == appVersion) appCheckError = "已是最新版本"
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
            Text("更新源：wancDDY/ST-Ctrl Releases。ST 核心为预先打好 Android 兼容补丁的版本，非官方原版。发布时 tag 格式如 st-1.12.0。",
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

                    if (coreUpdateInfo != null && coreUpdateInfo!!.version != coreVersion) {
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
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = accent,
                            trackColor = onBg.copy(alpha = 0.1f),
                        )
                        if (updatePhase.isNotBlank()) {
                            Text(updatePhase, fontSize = 11.sp, color = muted,
                                modifier = Modifier.padding(top = 4.dp))
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

            if (hasBackup && !isUpdatingCore) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        isRollingBack = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val coreDir = File(AssetExtractor.getCoreDir(ctx).absolutePath)
                                val backupDir = File(ctx.filesDir, "core_backup")
                                val dataDir = File(coreDir, "data")
                                val dataBak = File(ctx.cacheDir, "data-rb-tmp")
                                val extDir = File(coreDir, "public/scripts/extensions/third-party")
                                val extBak = File(ctx.cacheDir, "ext-rb-tmp")

                                try { dataBak.deleteRecursively() } catch (_: Exception) {}
                                try { extBak.deleteRecursively() } catch (_: Exception) {}
                                if (dataDir.exists()) { dataDir.renameTo(dataBak) }
                                if (extDir.exists()) { extBak.mkdirs(); extDir.copyRecursively(extBak, true); extDir.deleteRecursively() }

                                coreDir.deleteRecursively()
                                backupDir.copyRecursively(coreDir, overwrite = true)

                                val newDataDir = File(coreDir, "data")
                                if (dataBak.exists()) { dataBak.renameTo(newDataDir) }
                                val newExtDir = File(coreDir, "public/scripts/extensions/third-party")
                                newExtDir.parentFile?.mkdirs()
                                if (extBak.exists()) { extBak.renameTo(newExtDir) }

                                try { dataBak.deleteRecursively() } catch (_: Exception) {}
                                try { extBak.deleteRecursively() } catch (_: Exception) {}
                                backupDir.deleteRecursively()
                            }
                            isRollingBack = false
                            hasBackup = false
                            coreVersion = withContext(Dispatchers.IO) {
                                val pkgJson = File(AssetExtractor.getCoreDir(ctx), "package.json")
                                if (pkgJson.exists()) try { org.json.JSONObject(pkgJson.readText()).optString("version", "已回退") } catch (_: Exception) { "已回退" } else "已回退"
                            }
                            Toast.makeText(ctx, "已回退到上一个版本，请重启应用", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isRollingBack,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE05555)),
                    border = BorderStroke(1.dp, Color(0xFFE05555).copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isRollingBack) {
                        CircularProgressIndicator(color = Color(0xFFE05555), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("回退到上一个版本", fontSize = 14.sp)
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

                    if (appUpdateInfo != null && appUpdateInfo!!.version != appVersion) {
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
                                val url = appUpdateInfo!!.downloadUrl.ifEmpty {
                                    "https://github.com/wancDDY/ST-Ctrl/releases"
                                }
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
