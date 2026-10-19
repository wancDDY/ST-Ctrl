package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.TaskTerminal
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import com.tavern.app.console.components.PageTopBar
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius


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

private fun detectExtensionSource(dir: File): String {
    // 1. Try package.json repository field
    val pkgFile = File(dir, "package.json")
    if (pkgFile.exists()) {
        try {
            val pkg = JSONObject(pkgFile.readText())
            val repo = pkg.optJSONObject("repository")?.optString("url", "") ?: ""
            if (repo.isNotBlank()) return repo
            val homepage = pkg.optString("homepage", "")
            if (homepage.isNotBlank()) return homepage
        } catch (_: Exception) {}
    }
    // 2. Try .git/config for remote origin
    val gitConfig = File(dir, ".git/config")
    if (gitConfig.exists()) {
        try {
            val lines = gitConfig.readLines()
            var inRemote = false
            for (line in lines) {
                if (line.trim() == "[remote \"origin\"]") { inRemote = true; continue }
                if (inRemote && line.trim().startsWith("url = ")) {
                    val url = line.substringAfter("url = ").trim()
                    if (url.isNotBlank()) return url
                }
                if (inRemote && line.trim().startsWith("[")) break
            }
        } catch (_: Exception) {}
    }
    // 3. Try README for GitHub URL
    val readme = dir.listFiles()?.find { it.name.equals("README.md", ignoreCase = true) }
    if (readme != null) {
        try {
            val text = readme.readText()
            val match = Regex("github\\.com/[\\w.-]+/[\\w.-]+").find(text)
            if (match != null) return "https://${match.value}"
        } catch (_: Exception) {}
    }
    return ""
}

private fun parseExtension(dir: File): ExtensionInfo? {
    val manifestFile = File(dir, "manifest.json")
    if (!manifestFile.isFile) return null
    return try {
        val json = JSONObject(manifestFile.readText())
        var url = json.optString("github_url",
            json.optString("repository", json.optString("homePage", "")))
        if (url.isBlank()) url = detectExtensionSource(dir)
        ExtensionInfo(
            dirName = dir.name,
            displayName = json.optString("display_name", dir.name),
            description = json.optString("description", ""),
            version = json.optString("version", "0.0.0"),
            githubUrl = url
        )
    } catch (_: Exception) {
        null
    }
}

private fun loadExtensions(coreDir: File): List<ExtensionInfo> {
    val extDir = File(coreDir, "public/scripts/extensions/third-party")
    if (!extDir.exists() || !extDir.isDirectory) return emptyList()
    return extDir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { parseExtension(it) }
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
                return@withContext tag to "发现新版本 $tag（当前：${ext.version}）"
            }
            return@withContext null to "已是最新版本（${ext.version}）"
        } catch (e: Exception) {
            return@withContext null to "检查失败：${e.message?.take(60) ?: "未知错误"}"
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
/**
 * The extension root inside an extracted archive: the shallowest directory holding a
 * manifest.json, which is what the tavern itself looks for. Archives differ in how they
 * nest — a GitHub zipball has one root folder, a direct zip may have none — so the search
 * walks a few levels instead of assuming a layout.
 */
private fun findExtensionManifest(root: File, maxDepth: Int = 3): File? {
    val queue = ArrayDeque<Pair<File, Int>>()
    queue.add(root to 0)
    while (queue.isNotEmpty()) {
        val (dir, depth) = queue.removeFirst()
        val manifest = File(dir, "manifest.json")
        if (manifest.isFile) return manifest
        if (depth >= maxDepth) continue
        dir.listFiles()?.forEach { child ->
            // node_modules is not the extension root and can be large.
            if (child.isDirectory && child.name != "node_modules") queue.add(child to depth + 1)
        }
    }
    return null
}

internal suspend fun installExtension(
    ctx: Context,
    url: String,
    coreDir: File,
    onProgress: suspend (ExtensionInstall.Step) -> Unit = { }
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
            return@withContext Result.failure(Exception("仅支持 .zip 格式，当前文件：$fileName"))
        }
        repoShortName = fileName.removeSuffix(".zip").removeSuffix(".ZIP").take(50)
        zipUrl = url
        onProgress(ExtensionInstall.Step("正在准备…", 0f, log = "读取本地文件 $fileName"))
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
        onProgress(ExtensionInstall.Step("正在准备下载…", 0f, log = "解析 $repoFullName → $ref"))
    } else {
        // Direct zip URL — extract name from URL
        repoShortName = url.substringAfterLast("/").removeSuffix(".zip").take(50)
        zipUrl = url
        onProgress(
            ExtensionInstall.Step(
                "正在准备下载…",
                0f,
                log = "直链 ${url.substringAfter("://").take(72)}"
            )
        )
    }

    val targetDir = File(coreDir, "public/scripts/extensions/third-party/$repoShortName")
    var tmpZip: File? = null
    var tmpExtract: File? = null
    // Whether this run has already removed what was installed there. The directory existing is
    // normal on an update, so only a run that got as far as removing it may delete it again.
    var targetReplaced = false

    try {
        // Download or copy zip
        tmpZip = File(ctx.cacheDir, "ext-${System.currentTimeMillis()}.zip")
        onProgress(
            ExtensionInstall.Step(
                "正在准备下载…",
                0f,
                log = "下载 ${zipUrl.substringAfter("://").take(72)}"
            )
        )
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
            suspend fun tryDl(url: String): Boolean {
                for (retry in 1..2) {
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "TavernApp")
                        conn.setRequestProperty("Accept", "application/octet-stream")
                        conn.connectTimeout = 15_000
                        // A read that stalls for a minute is a dead transfer, not a
                        // slow one: the previous two-minute budget meant a stuck
                        // download held the dialog for six minutes before it failed.
                        conn.readTimeout = 30_000
                        conn.instanceFollowRedirects = true
                        val code = conn.responseCode
                        if (code != 200) { lastErr = "HTTP $code"; conn.disconnect(); continue }
                        val total = conn.contentLengthLong.coerceAtLeast(0L)
                        conn.inputStream.use { input ->
                            FileOutputStream(tmpZip).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                var lastBytes = 0L
                                var lastAt = 0L
                                while (true) {
                                    // Stopping has to stop the transfer, not just its report. This
                                    // loop is the only long wait in the install, and a stop that
                                    // leaves the download running finishes by replacing the very
                                    // extension the reader cancelled, with no report left to say so.
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    copied += read
                                    // GitHub builds the source archive while sending it and uses
                                    // chunked encoding, so there is no length to divide by and a
                                    // percentage cannot exist. The byte count always exists, so
                                    // that is what gets reported — a bar with nothing behind it
                                    // was indistinguishable from a dead download.
                                    // At most one report per 300 ms: a write per 64 KB chunk
                                    // recomposes the dialog hundreds of times a second.
                                    val now = System.currentTimeMillis()
                                    val advanced =
                                        total <= 0L || copied - lastBytes >= (total / 100).coerceAtLeast(1L)
                                    if (advanced && now - lastAt >= 300L) {
                                        lastAt = now
                                        lastBytes = copied
                                        val measured = total > 0L
                                        onProgress(
                                            ExtensionInstall.Step(
                                                phase = if (measured) {
                                                    "正在下载 ${copied * 100 / total}%"
                                                } else {
                                                    "正在下载 ${FormatUtils.fileSize(copied)}"
                                                },
                                                fraction = if (measured) {
                                                    (copied.toFloat() / total).coerceIn(0f, 1f)
                                                } else {
                                                    null
                                                },
                                                downloaded = copied,
                                                total = total
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        conn.disconnect()
                        onProgress(
                            ExtensionInstall.Step(
                                "下载完成，正在解压…",
                                downloaded = tmpZip.length(),
                                total = total,
                                log = "已下载 ${FormatUtils.fileSize(tmpZip.length())}"
                            )
                        )
                        return true
                    } catch (e: Exception) {
                        lastErr = e.message?.take(60) ?: "network error"
                        if (retry == 2) return false
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
                        "\n\n网络连接失败，请检查网络或尝试在核心内安装"
                    lastErr.contains("429") ->
                        "\n\nGitHub 请求过于频繁，请稍后重试"
                    else -> ""
                }
                return@withContext Result.failure(Exception("下载失败：$lastErr$hint"))
            }
        }

        // 3. Extract zip to temp directory
        tmpExtract = File(ctx.cacheDir, "ext-tmp-${System.currentTimeMillis()}")
        tmpExtract.mkdirs()
        onProgress(ExtensionInstall.Step("正在解压…"))

        var unpackedCount = 0
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
                    unpackedCount++
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        onProgress(ExtensionInstall.Step("正在解压…", log = "解压 $unpackedCount 个条目"))

        // 4. Decide whether this is an extension at all, by the tavern's own rule: the
        // folder that holds manifest.json is the extension, and a folder without one is
        // never loaded. The previous version wrote a stub manifest when none was found,
        // which registered whatever the archive happened to be as an installed extension
        // (and made a genuinely manifest-less download look installed but dead).
        onProgress(ExtensionInstall.Step("正在校验…"))
        val manifestFile = findExtensionManifest(tmpExtract)
            ?: return@withContext Result.failure(
                Exception(
                    "这不是核心插件：压缩包里没有 manifest.json。\n\n" +
                        "核心按 manifest.json 识别插件（它声明要加载的 js/css）。" +
                        "请确认下载的是插件仓库，而不是普通项目或压缩包。"
                )
            )
        val manifest = try {
            JSONObject(manifestFile.readText())
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("这不是核心插件：manifest.json 不是有效的 JSON（${e.message}）"))
        }
        val displayName = manifest.optString("display_name").takeIf { it.isNotBlank() } ?: repoShortName
        val version = manifest.optString("version").takeIf { it.isNotBlank() }
        val entryCount = listOf("js", "css").count { manifest.optString(it).isNotBlank() }
        if (entryCount == 0 && manifest.optString("display_name").isBlank()) {
            // A manifest with neither an entry point nor a name declares nothing to load.
            return@withContext Result.failure(
                Exception("这不是核心插件：manifest.json 里没有 js/css 或 display_name")
            )
        }

        onProgress(
            ExtensionInstall.Step(
                "正在校验…",
                log = "校验 manifest.json → $displayName" + (version?.let { " v$it" } ?: "")
            )
        )

        val sourceDir = manifestFile.parentFile ?: tmpExtract
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetReplaced = true
        targetDir.parentFile?.mkdirs()
        sourceDir.copyRecursively(targetDir, overwrite = true)
        onProgress(ExtensionInstall.Step("正在写入…", log = "写入 third-party/$repoShortName"))

        // 5. Keep the download address, next to the manifest the archive brought.
        File(targetDir, "manifest.json").let { file ->
            try {
                val written = JSONObject(file.readText())
                written.put("github_url", url)
                file.writeText(written.toString(2))
            } catch (_: Exception) {
            }
        }

        onProgress(ExtensionInstall.Step("安装完成", 1f))
        Result.success(if (version != null) "$displayName v$version" else displayName)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // A cancelled run is not a failed one: it must not be reported as a failure, and it must
        // not run the cleanup below, which would take the installed extension down with it.
        throw e
    } catch (e: Exception) {
        // Clean up a partial install, but only one this run created: a download that fails while
        // updating used to delete the extension the reader was updating.
        if (targetReplaced && targetDir.exists()) {
            try { targetDir.deleteRecursively() } catch (_: Exception) {}
        }
        Result.failure(Exception("安装失败：${e.message}"))
    } finally {
        // Always clean up temp files
        tmpZip?.let { try { it.delete() } catch (_: Exception) {} }
        tmpExtract?.let { try { it.deleteRecursively() } catch (_: Exception) {} }
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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

    var pendingOverwriteUrl by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteName by remember { mutableStateOf<String?>(null) }

    var deleteTarget by remember { mutableStateOf<ExtensionInfo?>(null) }

    var checkingUpdateDir by remember { mutableStateOf<String?>(null) }
    var updateTarget by remember { mutableStateOf<ExtensionInfo?>(null) }
    var updateVersion by remember { mutableStateOf<String?>(null) }

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

    // The install itself lives in ExtensionInstall so it survives leaving this page; the
    // screen only reports it. Nothing here may own the job: a composition scope dies with
    // the page, which silently cancelled a download the dialog had just called background.
    val installProgress by ExtensionInstall.progress.collectAsState()
    val installLog by ExtensionInstall.log.collectAsState()
    val installResult by ExtensionInstall.result.collectAsState()
    val isInstalling = installProgress != null
    // Re-entry guard: prevents double-tapping Update from running two
    // installs of the same extension concurrently. It reads the process-level job rather than a
    // page flag, because an update started here keeps running after the page is gone and the page
    // is then in no position to remember it.
    val isUpdating = ExtensionInstall.isRunning
    // An update reports the versions it moved between; a fresh install has nothing to compare.
    val updateOutcome = (installResult as? ExtensionInstall.Result.Done)
        ?.takeIf { it.updateFrom != null }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ExtensionInstall.start(ctx, uri.toString(), coreDir, "本地 zip")
    }

    fun dismissInstallDialog() {
        showInstallDialog = false
        installUrl = ""
        installError = null
    }

    fun startInstall(url: String) {
        installError = null
        ExtensionInstall.start(ctx, url, coreDir, parseRepoName(url)?.substringAfter("/") ?: url)
    }

    // The list and the tavern page both need to know the moment an install lands, which can
    // happen long after the user left this page.
    LaunchedEffect(installResult) {
        when (val outcome = installResult) {
            is ExtensionInstall.Result.Done -> {
                refreshExtensions()
                AppToast.show(
                    ctx,
                    if (outcome.updateFrom != null) {
                        "已更新 ${outcome.name}，核心页面已刷新"
                    } else {
                        "已安装 ${outcome.name}，核心页面已刷新"
                    },
                    Toast.LENGTH_SHORT
                )
            }
            is ExtensionInstall.Result.Failed ->
                AppToast.show(ctx, "安装失败：${outcome.message}", Toast.LENGTH_LONG)
            else -> Unit
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

    val accentColor = LocalAppColors.current.accent
    val errorColor = LocalAppColors.current.error
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showHeader) {
                PageTopBar(title = "插件", onBack = onBack)
            }

            if (showHeader) {
                Text("管理已安装的插件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceSecondary))
                Text("安装和检测依赖 GitHub，国内网络可能受限", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary))
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (installResult != null && installResult !is ExtensionInstall.Result.Cancelled &&
                updateOutcome == null
            ) {
                val outcome = installResult!!
                val failed = outcome is ExtensionInstall.Result.Failed
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(AppRadius.md)
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (outcome) {
                                is ExtensionInstall.Result.Done -> "已安装 ${outcome.name}"
                                is ExtensionInstall.Result.Failed -> "安装失败：${outcome.message}"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) errorColor else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { ExtensionInstall.dismissResult() }) {
                            Text("知道了", color = accentColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
            }

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
                            "未检测到已安装的插件",
                            color = mutedColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = AppSpacing.xs),
                    ) {
                        items(extensions, key = { it.dirName }) { ext ->
                            ExtensionEntry(
                                ext = ext,
                                isExpanded = ext.dirName in expandedDirs,
                                modifier = Modifier.animateItem(),
                                isChecking = checkingUpdateDir == ext.dirName,
                                accentColor = accentColor,
                                errorColor = errorColor,
                                onToggleExpand = {
                                    expandedDirs = if (ext.dirName in expandedDirs) {
                                        expandedDirs - ext.dirName
                                    } else {
                                        expandedDirs + ext.dirName
                                    }
                                },
                                onDelete = { deleteTarget = ext },
                                onCheckUpdate = {
                                    if (isUpdating) {
                                        AppToast.show(ctx, "有插件正在更新中，请稍候…", Toast.LENGTH_SHORT)
                                    } else {
                                        checkingUpdateDir = ext.dirName
                                        scope.launch {
                                            val (latestVer, msg) = checkExtensionUpdate(ext)
                                            checkingUpdateDir = null
                                            if (latestVer != null) {
                                                updateTarget = ext
                                                updateVersion = latestVer
                                            } else {
                                                AppToast.show(ctx, msg, Toast.LENGTH_SHORT)
                                            }
                                        }
                                    }
                                },
                                onCopyUrl = {
                                    val clipboard =
                                        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("url", ext.githubUrl)
                                    clipboard.setPrimaryClip(clip)
                                    AppToast.show(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT)
                                }
                            )
                        }
                    }
                }
            }

            // Pinned to the bottom of the page rather than sitting in the list's flow, and
            // outside the dialog: the reader who sent the install to the background, or came
            // back to this page later, still needs to see what it is doing, how far along it is,
            // and that it can still be called off.
            installProgress?.let { running ->
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                TaskTerminal(
                    command = running.command,
                    lines = installLog,
                    status = running.phase,
                    startedAt = running.startedAt,
                    running = true,
                    fraction = running.fraction,
                    downloaded = running.downloaded,
                    total = running.total,
                    cancelLabel = if (running.updateFrom != null) "取消更新" else "取消安装",
                    onCancel = { ExtensionInstall.cancel() },
                    hint = "可以离开本页：安装会在后台继续。"
                )
            }
        }

        // FAB — hidden while an install is running or a dialog already owns the screen
        if (!isInstalling && !showInstallDialog && pendingOverwriteUrl == null) {
            FloatingActionButton(
                onClick = {
                    installUrl = ""
                    installError = null
                    showInstallDialog = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(AppSpacing.xl),
                containerColor = accentColor,
                contentColor = MaterialTheme.colorScheme.surface,
                shape = CircleShape
            ) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(AppDimens.iconLg)) }
        }
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { if (!isInstalling) dismissInstallDialog() },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
            shape = RoundedCornerShape(16.dp),
            title = {
                Column {
                    Text(
                        "安装插件",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "已安装过的插件重新安装会覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary)
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
                            label = { Text("插件下载地址") },
                            placeholder = { Text("GitHub / GitLab / zip 直链") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            enabled = !isInstalling,
                            colors = appTextFieldColors(),
                        )
                        if (!isInstalling && installUrl.isNotEmpty()) {
                            TextButton(
                                onClick = { installUrl = ""; installError = null },
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            ) {
                                Text("清空", color = mutedColor, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (installProgress != null) {
                        val running = installProgress!!
                        Spacer(modifier = Modifier.height(12.dp))
                        // No action of its own here: this dialog's own button is the cancel, and
                        // two controls doing the same thing in one sheet read as two different
                        // outcomes.
                        TaskTerminal(
                            command = running.command,
                            lines = installLog,
                            status = running.phase,
                            startedAt = running.startedAt,
                            running = true,
                            fraction = running.fraction,
                            downloaded = running.downloaded,
                            total = running.total,
                            hint = "可以关闭本窗口，安装会在后台继续；取消安装的操作在页面底部。",
                            maxLines = 5
                        )
                    }

                    // The outcome belongs in the dialog too: the reader just pressed the
                    // confirm button and is looking here, not at the banner behind it.
                    val inlineOutcome = installResult?.takeIf { it !is ExtensionInstall.Result.Cancelled }
                    if (installError != null || inlineOutcome != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            installError ?: when (inlineOutcome) {
                                is ExtensionInstall.Result.Done -> "安装完成：${inlineOutcome.name}"
                                is ExtensionInstall.Result.Failed -> "安装失败：${inlineOutcome.message}"
                                else -> ""
                            },
                            color = if (inlineOutcome is ExtensionInstall.Result.Failed) errorColor else accentColor,
                            style = MaterialTheme.typography.bodySmall
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
                        Text("导入本地 zip（仅支持 .zip 格式）", color = accentColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = mutedColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "请仅安装来自可信来源的插件。第三方插件可能含有恶意代码，App 不对插件内容负责。",
                            color = mutedColor,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                // One button, two jobs in sequence: it starts the install, and while that install
                // runs it is the way out of it. The label says which one is in front of the
                // reader — the sheet has no room to explain a control that means "stop" but does
                // not pause.
                DialogConfirmButton(
                    text = if (isInstalling) "取消安装" else "安装",
                    onClick = { if (isInstalling) ExtensionInstall.cancel() else requestInstall(installUrl) },
                    enabled = if (isInstalling) true else installUrl.isNotBlank(),
                    color = accentColor
                )
            },
            dismissButton = {
                // Closing the dialog while a download runs only hides the report: the
                // install keeps going and reports through a toast. Blocking the whole
                // sheet left the cancel button as the only reachable control for the
                // minutes a slow download takes.
                DialogDismissButton(
                    text = if (isInstalling) "后台安装" else "关闭",
                    onClick = { if (isInstalling) showInstallDialog = false else dismissInstallDialog() },
                    enabled = true
                )
            }
        )
    }

    if (pendingOverwriteUrl != null) {
        ConfirmDialog(
            title = "覆盖安装",
            message = "插件 \"$pendingOverwriteName\" 已经存在，是否覆盖安装？",
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
            title = "删除插件",
            message = "确定删除\"${deleteTarget!!.displayName}\"吗？此操作不可撤销。",
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
                    AppToast.show(ctx, "已删除\"${target.displayName}\"", Toast.LENGTH_SHORT)
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    if (updateTarget != null && updateVersion != null) {
        ConfirmDialog(
            title = "检查更新",
            message = "发现新版本 v$updateVersion（当前：v${updateTarget!!.version}），是否更新？",
            confirmText = "更新",
            dismissText = "取消",
            onConfirm = {
                if (isUpdating) {
                    AppToast.show(ctx, "该插件正在更新中，请稍候…", Toast.LENGTH_SHORT)
                    return@ConfirmDialog
                }
                val target = updateTarget!!
                val oldVer = target.version
                val newVer = updateVersion!!
                updateTarget = null
                updateVersion = null
                AppToast.show(ctx, "正在更新 ${target.displayName}…", Toast.LENGTH_SHORT)
                // Through ExtensionInstall rather than the page's own scope: it is the same
                // download and the same unpack as an install, and running it here meant leaving
                // the page cancelled an update without a word.
                ExtensionInstall.start(
                    ctx,
                    target.githubUrl,
                    coreDir,
                    target.displayName,
                    updateFrom = oldVer,
                    updateTo = newVer,
                )
            },
            onDismiss = {
                updateTarget = null
                updateVersion = null
            }
        )
    }

    if (updateOutcome != null) {
        AlertDialog(
            onDismissRequest = { ExtensionInstall.dismissResult() },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("更新完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("旧版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("v${updateOutcome.updateFrom}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("新版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("v${updateOutcome.updateTo}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                DialogConfirmButton(text = "确定", onClick = { ExtensionInstall.dismissResult() })
            }
        )
    }
}


@Composable
private fun ExtensionEntry(
    ext: ExtensionInfo,
    isExpanded: Boolean,
    isChecking: Boolean,
    accentColor: Color,
    errorColor: Color,
    modifier: Modifier = Modifier,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
    onCheckUpdate: () -> Unit,
    onCopyUrl: () -> Unit
) {
    val motion = LocalFluentMotion.current
    // No card: the rows carry their own padding and separate themselves by the gap
    // between them, so a filled block behind each entry reads as a box drawn around
    // every plugin.
    Column(modifier = modifier.fillMaxWidth()) {
        ListRow(
            icon = Icons.Outlined.Extension,
            title = ext.displayName,
            subtitle = "${ext.dirName}，v${ext.version}",
            onClick = onToggleExpand,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.disabled),
                        modifier = Modifier.size(AppDimens.iconMd)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = errorColor,
                            modifier = Modifier.size(AppDimens.iconMd)
                        )
                    }
                }
            }
        )

        // Expandable detail section
        AnimatedVisibility(
            visible = isExpanded,
            enter = motion.enterExpand(),
            exit = motion.exitShrink()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        // Lines up under the row's title: leading inset + glyph + gap.
                        start = AppSpacing.lg + AppDimens.iconMd + AppSpacing.md,
                        end = AppSpacing.page,
                        bottom = AppSpacing.md
                    )
            ) {
                // Description
                if (ext.description.isNotBlank()) {
                    Text(
                        ext.description,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceSecondary),
                        style = MaterialTheme.typography.bodySmall,
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
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
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
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceSecondary),
            style = MaterialTheme.typography.bodySmall
        )
        if (isBusy && busyIndicator != null) {
            busyIndicator()
        } else {
            TextButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.height(AppDimens.controlHeightCompact),
                contentPadding = PaddingValues(horizontal = AppSpacing.md),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = buttonText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(buttonText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
