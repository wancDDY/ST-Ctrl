package com.tavern.app.console.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream


data class ExtensionInfo(
    val dirName: String,
    val displayName: String,
    val description: String,
    val version: String,
    val githubUrl: String = ""
)


private fun parseRepoName(url: String): String? {
    val regex = Regex("github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/|\$)")
    val match = regex.find(url)
    return match?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
}

private fun compareVersion(v1: String, v2: String): Int {
    val clean1 = v1.trimStart('v', 'V').split("-").first()
    val clean2 = v2.trimStart('v', 'V').split("-").first()
    val parts1 = clean1.split(".").map { it.toIntOrNull() ?: 0 }
    val parts2 = clean2.split(".").map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 < p2) return -1
        if (p1 > p2) return 1
    }
    return 0
}

private fun parseExtension(dir: File): ExtensionInfo {
    val manifestFile = File(dir, "manifest.json")
    if (!manifestFile.exists()) {
        return ExtensionInfo(dirName = dir.name, displayName = dir.name, description = "", version = "0.0.0")
    }
    return try {
        val json = JSONObject(manifestFile.readText())
        ExtensionInfo(
            dirName = dir.name,
            displayName = json.optString("display_name", dir.name),
            description = json.optString("description", ""),
            version = json.optString("version", "0.0.0"),
            githubUrl = json.optString(
                "github_url",
                json.optString("repository", json.optString("homePage", ""))
            )
        )
    } catch (_: Exception) {
        ExtensionInfo(dirName = dir.name, displayName = dir.name, description = "", version = "0.0.0")
    }
}

private fun loadExtensions(coreDir: File): List<ExtensionInfo> {
    val extDir = File(coreDir, "public/scripts/extensions/third-party")
    if (!extDir.exists() || !extDir.isDirectory) return emptyList()
    return extDir.listFiles()
        ?.filter { it.isDirectory }
        ?.map { parseExtension(it) }
        ?.sortedBy { it.displayName.lowercase() }
        ?: emptyList()
}

private suspend fun checkExtensionUpdate(ext: ExtensionInfo): Pair<String?, String> =
    withContext(Dispatchers.IO) {
        if (ext.githubUrl.isBlank()) return@withContext null to "未记录安装地址，无法检查更新"
        val repoName = parseRepoName(ext.githubUrl)
            ?: return@withContext null to "无法解析仓库地址"
        try {
            // Use GitHub Atom feed: returns XML with <entry><title>v4.8.10</title></entry>
            val atomUrl = "https://github.com/$repoName/releases.atom"
            val conn = URL(atomUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "TavernApp")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            // Parse first <title> inside <entry> (skip feed title)
            val entryTitle = Regex("""<entry>.*?<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.trim() ?: return@withContext null to "未找到发布版本"

            val tag = entryTitle.trimStart('v', 'V', ' ')
            if (tag.isEmpty() || !tag.any { it.isDigit() })
                return@withContext null to "无法解析版本号"

            if (compareVersion(tag, ext.version) > 0) {
                return@withContext tag to "发现新版本 $tag（当前: ${ext.version}）"
            }
            return@withContext null to "已是最新版本（${ext.version}）"
        } catch (e: Exception) {
            return@withContext null to "检查失败: ${e.message?.take(60) ?: "未知错误"}"
        }
    }

/**
 * Installs a third-party extension from a GitHub repository URL.
 *
 * Steps:
 * 1. Parse GitHub repo name from URL
 * 2. Fetch latest release info via GitHub API to get zipball_url
 * 3. Download the zip archive
 * 4. Extract zip to a temp directory
 * 5. Move contents to the third-party extensions directory
 * 6. Write/update manifest.json with github_url
 *
 * Cleanup of temp files is handled in finally blocks.
 */
private suspend fun installExtension(
    ctx: Context,
    url: String,
    coreDir: File
): Result<String> = withContext(Dispatchers.IO) {
    val isGitHub = parseRepoName(url) != null
    val isLocalFile = url.startsWith("content://")
    val repoShortName: String
    val zipUrl: String

    if (isLocalFile) {
        // Local file import: validate extension
        var fileName = ""
        try {
            val uri = android.net.Uri.parse(url)
            val cursor = ctx.contentResolver.query(uri, null, null, null, null)
            if (cursor != null) {
                cursor.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = it.getString(idx) ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        if (fileName.isBlank()) fileName = url.substringAfterLast("/")
        if (!fileName.endsWith(".zip", ignoreCase = true)) {
            return@withContext Result.failure(Exception("仅支持 .zip 格式，当前文件: $fileName"))
        }
        repoShortName = fileName.removeSuffix(".zip").removeSuffix(".ZIP").take(50)
        zipUrl = url
    } else if (isGitHub) {
        val repoFullName = parseRepoName(url)!!
        repoShortName = repoFullName.substringAfter("/")
        // Use Atom feed to find latest release tag
        var rawTag = ""
        try {
            val atomUrl = "https://github.com/$repoFullName/releases.atom"
            val atomConn = URL(atomUrl).openConnection() as HttpURLConnection
            atomConn.setRequestProperty("User-Agent", "TavernApp")
            atomConn.connectTimeout = 10_000; atomConn.readTimeout = 10_000
            val body = atomConn.inputStream.bufferedReader().use { it.readText() }
            atomConn.disconnect()
            rawTag = Regex("""<entry>.*?<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.trim() ?: ""
        } catch (_: Exception) {}

        // Use codeload.github.com — case-sensitive, matches SillyTavern's git clone behavior
        val ref = if (rawTag.isNotEmpty()) "refs/tags/$rawTag" else "refs/heads/main"
        zipUrl = "https://codeload.github.com/$repoFullName/zip/$ref"
    } else {
        // Direct zip URL — extract name from URL
        repoShortName = url.substringAfterLast("/").removeSuffix(".zip").take(50)
        zipUrl = url
    }

    val targetDir = File(coreDir, "public/scripts/extensions/third-party/$repoShortName")
    var tmpZip: File? = null
    var tmpExtract: File? = null

    try {
        // Download or copy zip
        tmpZip = File(ctx.cacheDir, "ext-${System.currentTimeMillis()}.zip")
        if (isLocalFile) {
            // Copy from ContentResolver
            val uri = android.net.Uri.parse(zipUrl)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpZip).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return@withContext Result.failure(Exception("无法读取文件"))
        } else {
            var lastErr = ""
            fun tryDl(url: String): Boolean {
                for (retry in 1..3) {
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "TavernApp")
                        conn.setRequestProperty("Accept", "application/octet-stream")
                        conn.connectTimeout = 15_000; conn.readTimeout = 120_000
                        conn.instanceFollowRedirects = true
                        val code = conn.responseCode
                        if (code != 200) { lastErr = "HTTP $code"; conn.disconnect(); continue }
                        conn.inputStream.use { input ->
                            FileOutputStream(tmpZip).use { output -> input.copyTo(output, 64 * 1024) }
                        }
                        conn.disconnect()
                        return true
                    } catch (e: Exception) {
                        lastErr = e.message?.take(60) ?: "network error"
                        if (retry == 3) return false
                        Thread.sleep(1000)
                    }
                }
                return false
            }
            var ok = tryDl(zipUrl)
            if (!ok && isGitHub) {
                val repoFullName = parseRepoName(url)!!
                // Fallback: try the other common branch (master ↔ main)
                val fallbackRef = if (zipUrl.contains("refs/heads/main")) "refs/heads/master" else "refs/heads/main"
                ok = tryDl("https://codeload.github.com/$repoFullName/zip/$fallbackRef")
            }
            if (!ok) {
                val hint = when {
                    lastErr.contains("404") && isGitHub ->
                        "\n\n请检查：\n• 仓库地址是否正确（大小写敏感）\n• 仓库是否有 Release 或 main/master 分支"
                    lastErr.contains("403") && isGitHub ->
                        "\n\nGitHub 访问受限，可稍后重试或检查网络"
                    lastErr.contains("network") || lastErr.contains("timeout") || lastErr.contains("UnknownHost") ->
                        "\n\n网络连接失败，请检查网络或尝试在酒馆内安装"
                    lastErr.contains("429") ->
                        "\n\nGitHub 请求过于频繁，请稍后重试"
                    else -> ""
                }
                return@withContext Result.failure(Exception("下载失败: $lastErr$hint"))
            }
        }

        // 3. Extract zip to temp directory
        tmpExtract = File(ctx.cacheDir, "ext-tmp-${System.currentTimeMillis()}")
        tmpExtract.mkdirs()

        java.io.FileInputStream(tmpZip).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/')
                    val entryFile = File(tmpExtract, normalizedName)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { fos ->
                            zis.copyTo(fos, bufferSize = 64 * 1024)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        // 4. Move contents — GitHub zipballs have a single root directory
        val innerDirs = tmpExtract.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val sourceDir = if (innerDirs.size == 1) innerDirs.first() else tmpExtract

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.parentFile?.mkdirs()
        sourceDir.copyRecursively(targetDir, overwrite = true)

        // 5. Write/update manifest.json with github_url
        val manifestFile = File(targetDir, "manifest.json")
        val manifest = if (manifestFile.exists()) {
            try {
                JSONObject(manifestFile.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        manifest.put("github_url", url)
        manifestFile.writeText(manifest.toString(2))

        Result.success(repoShortName)
    } catch (e: Exception) {
        // Clean up target dir on failure so a partial install doesn't linger
        if (targetDir.exists()) {
            try { targetDir.deleteRecursively() } catch (_: Exception) {}
        }
        Result.failure(Exception("安装失败: ${e.message}"))
    } finally {
        // Always clean up temp files
        tmpZip?.let { try { it.delete() } catch (_: Exception) {} }
        tmpExtract?.let { try { it.deleteRecursively() } catch (_: Exception) {} }
    }
}


@Composable
fun ExtensionsScreen(onBack: () -> Unit, showHeader: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val coreDir = remember { AssetExtractor.getCoreDir(ctx) }

    var extensions by remember { mutableStateOf<List<ExtensionInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedDirs by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showInstallDialog by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }
    var installError by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    var installJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var pendingOverwriteUrl by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteName by remember { mutableStateOf<String?>(null) }

    var deleteTarget by remember { mutableStateOf<ExtensionInfo?>(null) }

    var checkingUpdateDir by remember { mutableStateOf<String?>(null) }
    var updateTarget by remember { mutableStateOf<ExtensionInfo?>(null) }
    var updateVersion by remember { mutableStateOf<String?>(null) }

    var updateResultOld by remember { mutableStateOf("") }
    var updateResultNew by remember { mutableStateOf("") }
    var showUpdateResult by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        extensions = withContext(Dispatchers.IO) { loadExtensions(coreDir) }
        isLoading = false
    }

    fun refreshExtensions() {
        scope.launch {
            extensions = withContext(Dispatchers.IO) { loadExtensions(coreDir) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isInstalling = true
        installJob = scope.launch {
            val result = installExtension(ctx, uri.toString(), coreDir)
            isInstalling = false
            result.fold(
                onSuccess = { refreshExtensions(); Toast.makeText(ctx, "导入完成", Toast.LENGTH_SHORT).show() },
                onFailure = { e -> installError = e.message ?: "导入失败" }
            )
        }
    }

    fun dismissInstallDialog() {
        showInstallDialog = false
        installUrl = ""
        installError = null
    }

    fun startInstall(url: String) {
        installError = null
        isInstalling = true
        installJob = scope.launch {
            val result = installExtension(ctx, url, coreDir)
            isInstalling = false
            result.fold(
                onSuccess = {
                    dismissInstallDialog()
                    refreshExtensions()
                    Toast.makeText(ctx, "安装完成", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    installError = e.message ?: "安装失败"
                }
            )
        }
    }

    fun requestInstall(url: String) {
        // For GitHub URLs, check for existing extension first
        val repoName = parseRepoName(url)
        if (repoName != null) {
            val repoShortName = repoName.substringAfter("/")
            val targetDir = File(coreDir, "public/scripts/extensions/third-party/$repoShortName")
            if (targetDir.exists()) {
                pendingOverwriteUrl = url
                pendingOverwriteName = repoShortName
                return
            }
        }
        // GitHub or direct zip URL — both supported
        startInstall(url)
    }

    val accentColor = Color(0xFFD4A853)
    val errorColor = Color(0xFFE05555)
    val mutedColor = Color(0xFF8A8A80)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            if (showHeader) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = accentColor, fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Install row — always visible (also shown when embedded in hub)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showHeader) {
                    Text("扩展", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
                FilledTonalButton(
                    onClick = {
                        installUrl = ""
                        installError = null
                        showInstallDialog = true
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = accentColor.copy(alpha = 0.15f),
                        contentColor = accentColor
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "安装", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("安装", fontSize = 13.sp)
                }
            }

            if (showHeader) {
                Text("管理已安装的扩展程序", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Text("安装和检测依赖 GitHub，国内网络可能受限", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Content area
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = accentColor
                        )
                    }
                }

                extensions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "未检测到已安装的扩展",
                            color = mutedColor,
                            fontSize = 14.sp
                        )
                    }
                }

                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(extensions, key = { it.dirName }) { ext ->
                            ExtensionCard(
                                ext = ext,
                                isExpanded = ext.dirName in expandedDirs,
                                isChecking = checkingUpdateDir == ext.dirName,
                                accentColor = accentColor,
                                errorColor = errorColor,
                                mutedColor = mutedColor,
                                onToggleExpand = {
                                    expandedDirs = if (ext.dirName in expandedDirs) {
                                        expandedDirs - ext.dirName
                                    } else {
                                        expandedDirs + ext.dirName
                                    }
                                },
                                onDelete = { deleteTarget = ext },
                                onCheckUpdate = {
                                    checkingUpdateDir = ext.dirName
                                    scope.launch {
                                        val (latestVer, msg) = checkExtensionUpdate(ext)
                                        checkingUpdateDir = null
                                        if (latestVer != null) {
                                            updateTarget = ext
                                            updateVersion = latestVer
                                        } else {
                                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onCopyUrl = {
                                    val clipboard =
                                        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("url", ext.githubUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { if (!isInstalling) dismissInstallDialog() },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp),
            title = {
                Column {
                    Text(
                        "安装扩展",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "已安装过的扩展重新安装会覆盖",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = installUrl,
                            onValueChange = {
                                installUrl = it
                                installError = null
                            },
                            label = { Text("扩展下载地址") },
                            placeholder = { Text("GitHub / GitLab / zip 直链") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            enabled = !isInstalling,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                cursorColor = accentColor,
                                focusedLabelColor = accentColor,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        )
                        if (!isInstalling && installUrl.isNotEmpty()) {
                            TextButton(
                                onClick = { installUrl = ""; installError = null },
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            ) {
                                Text("清空", color = mutedColor, fontSize = 12.sp)
                            }
                        }
                    }

                    if (isInstalling) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = accentColor,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                installJob?.cancel()
                                isInstalling = false
                                installError = "已取消"
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("取消安装", color = errorColor, fontSize = 13.sp)
                        }
                    }

                    if (installError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            installError!!,
                            color = errorColor,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // Import local zip
                    TextButton(
                        onClick = {
                            if (!isInstalling) {
                                dismissInstallDialog()
                                importLauncher.launch(arrayOf("application/zip", "*/*"))
                            }
                        },
                        enabled = !isInstalling
                    ) {
                        Icon(Icons.Outlined.Upload, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导入本地 zip（仅支持 .zip 格式）", color = accentColor, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "⚠️ 请仅安装来自可信来源的扩展。第三方扩展可能含有恶意代码，酒馆不对扩展内容负责。",
                        color = mutedColor,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { requestInstall(installUrl) },
                    enabled = installUrl.isNotBlank() && !isInstalling
                ) {
                    Text(
                        "安装",
                        color = if (installUrl.isNotBlank() && !isInstalling) accentColor
                        else mutedColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isInstalling) dismissInstallDialog() },
                    enabled = !isInstalling
                ) {
                    Text(
                        "关闭",
                        color = if (!isInstalling) mutedColor else mutedColor.copy(alpha = 0.5f)
                    )
                }
            }
        )
    }

    if (pendingOverwriteUrl != null) {
        ConfirmDialog(
            title = "覆盖安装",
            message = "扩展 \"$pendingOverwriteName\" 已经存在，是否覆盖安装？",
            confirmText = "覆盖",
            dismissText = "取消",
            onConfirm = {
                val url = pendingOverwriteUrl!!
                pendingOverwriteUrl = null
                pendingOverwriteName = null
                startInstall(url)
            },
            onDismiss = {
                pendingOverwriteUrl = null
                pendingOverwriteName = null
            }
        )
    }

    if (deleteTarget != null) {
        ConfirmDialog(
            title = "删除扩展",
            message = "确定要删除\"${deleteTarget!!.displayName}\"吗？此操作不可撤销。",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                val target = deleteTarget!!
                deleteTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val dir = File(
                            coreDir,
                            "public/scripts/extensions/third-party/${target.dirName}"
                        )
                        if (dir.exists()) dir.deleteRecursively()
                    }
                    refreshExtensions()
                    Toast.makeText(ctx, "已删除\"${target.displayName}\"", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    if (updateTarget != null && updateVersion != null) {
        ConfirmDialog(
            title = "检查更新",
            message = "发现新版本 v$updateVersion（当前: v${updateTarget!!.version}），是否更新？",
            confirmText = "更新",
            dismissText = "取消",
            onConfirm = {
                val target = updateTarget!!
                val oldVer = target.version
                val newVer = updateVersion!!
                updateTarget = null
                updateVersion = null
                scope.launch {
                    val result = installExtension(ctx, target.githubUrl, coreDir)
                    result.fold(
                        onSuccess = {
                            refreshExtensions()
                            updateResultOld = oldVer
                            updateResultNew = newVer
                            showUpdateResult = true
                        },
                        onFailure = { e ->
                            Toast.makeText(ctx, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            },
            onDismiss = {
                updateTarget = null
                updateVersion = null
            }
        )
    }

    if (showUpdateResult) {
        AlertDialog(
            onDismissRequest = { showUpdateResult = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("更新完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0A10),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("旧版本", fontSize = 13.sp, color = Color(0xFF8A8A80))
                                Text("v$updateResultOld", fontSize = 13.sp, color = Color(0xFF8A8A80))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("新版本", fontSize = 13.sp, color = Color(0xFF8A8A80))
                                Text("v$updateResultNew", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUpdateResult = false }) {
                    Text("确定", color = accentColor, fontWeight = FontWeight.Medium)
                }
            }
        )
    }
}


@Composable
private fun ExtensionCard(
    ext: ExtensionInfo,
    isExpanded: Boolean,
    isChecking: Boolean,
    accentColor: Color,
    errorColor: Color,
    mutedColor: Color,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
    onCheckUpdate: () -> Unit,
    onCopyUrl: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            // Main row: clickable to expand/collapse, with delete button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ext.displayName,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${ext.dirName}  v${ext.version}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Expand/collapse indicator
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除",
                        tint = errorColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expandable detail section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Description
                    if (ext.description.isNotBlank()) {
                        Text(
                            ext.description,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Update check row
                    ExtensionActionRow(
                        label = "检查更新",
                        isBusy = isChecking,
                        busyIndicator = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = accentColor
                            )
                        },
                        icon = Icons.Outlined.Refresh,
                        buttonText = "检测",
                        onClick = onCheckUpdate
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Install URL row
                    ExtensionActionRow(
                        label = "安装地址",
                        icon = Icons.Outlined.ContentCopy,
                        buttonText = "复制",
                        enabled = ext.githubUrl.isNotBlank(),
                        onClick = onCopyUrl
                    )

                    if (ext.githubUrl.isNotBlank()) {
                        Text(
                            ext.githubUrl,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// Reusable action row for expanded extension detail

@Composable
private fun ExtensionActionRow(
    label: String,
    icon: ImageVector,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isBusy: Boolean = false,
    busyIndicator: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 13.sp
        )
        if (isBusy && busyIndicator != null) {
            busyIndicator()
        } else {
            TextButton(
                onClick = onClick,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = buttonText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(buttonText, fontSize = 13.sp)
            }
        }
    }
}
