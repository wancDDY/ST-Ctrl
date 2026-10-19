package com.tavern.app.update

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class UpdateCancelledException : Exception("已取消")

object CoreUpdater {

    private const val TAG = "CoreUpdater"
    private const val UPDATE_LOCK_FILE = "core-update.lock"
    private const val UPDATE_LOCK_MAX_AGE_MS = 30 * 60 * 1000L
    private const val RUNTIME_STAMP_FILE = "core-runtime-stamp"

    private val updateOwnedInProcess = AtomicBoolean(false)

    // The connection and body stream in flight, so a cancel can drop them. A read() on a stalled
    // socket does not return until the read timeout, so on its own the caller's cancel flag can
    // sit unseen for that long and the button reads as broken.
    @Volatile
    private var activeDownload: HttpURLConnection? = null

    @Volatile
    private var activeStream: java.io.InputStream? = null

    /**
     * Drops the download in flight: the body stream first, then the connection.
     *
     * Closing the stream is what actually unblocks a reader that is already parked in read(); a
     * close from another thread does not always reach it on every platform, which is why the read
     * timeout is also kept short enough to notice a cancel by itself.
     */
    fun cancelActiveDownload() {
        try {
            activeStream?.close()
        } catch (_: Exception) {
        }
        try {
            activeDownload?.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * The lock bridges the main process and :node so automatic health recovery
     * does not restart Node while the core directory is being replaced.
     */
    fun beginUpdate(context: Context) {
        updateOwnedInProcess.set(true)
        try {
            File(context.filesDir, UPDATE_LOCK_FILE)
                .writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {}
    }

    fun endUpdate(context: Context) {
        updateOwnedInProcess.set(false)
        try {
            File(context.filesDir, UPDATE_LOCK_FILE).delete()
        } catch (_: Exception) {}
    }

    fun isUpdateInProgress(context: Context): Boolean {
        if (updateOwnedInProcess.get()) return true
        val lock = File(context.filesDir, UPDATE_LOCK_FILE)
        if (!lock.exists()) return false
        val startedAt = lock.readText().trim().toLongOrNull()
        if (
            startedAt == null
            || System.currentTimeMillis() - startedAt > UPDATE_LOCK_MAX_AGE_MS
        ) {
            try { lock.delete() } catch (_: Exception) {}
            return false
        }
        return true
    }

    /** Called after an app process restart; an in-process update cannot survive it. */
    fun clearStaleUpdateLock(context: Context) {
        if (updateOwnedInProcess.get()) return
        try {
            File(context.filesDir, UPDATE_LOCK_FILE).delete()
        } catch (_: Exception) {}
    }

    /**
     * Merges the runtime and patches the app ships with into the core directory.
     *
     * Reading the archive means inflating all of it — 120 MB across some 22k entries — and doing
     * that on every launch dominated startup. The merge only changes when this app or the bundled
     * core changes, so it is stamped and skipped otherwise. An in-app core update clears the stamp
     * because that path installs a core the stamp knows nothing about.
     */
    fun ensureRuntimePatches(context: Context, targetRoot: File) {
        if (!targetRoot.exists()) return
        val stampFile = File(context.filesDir, RUNTIME_STAMP_FILE)
        val stamp = "${com.tavern.app.BuildConfig.VERSION_CODE}:${AssetExtractor.readBundledRevision(context)}"
        if (stampFile.exists() && stampFile.readText().trim() == stamp) return
        restoreBundledRuntime(context, targetRoot)
        applyBundledPatchManifest(context, targetRoot)
        disableExtensionStartupUpdate(targetRoot)
        try { stampFile.writeText(stamp) } catch (_: Exception) {}
    }

    fun invalidateRuntimeStamp(context: Context) {
        try { File(context.filesDir, RUNTIME_STAMP_FILE).delete() } catch (_: Exception) {}
    }

    private fun restoreBundledRuntime(
        context: Context,
        targetRoot: File,
    ) {
        val targetBase = targetRoot.canonicalPath
        context.assets.open("core/tavern-core.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    val keep = name == "server-wrapper.cjs"
                        || name == "stctrl-runtime.cjs"
                        || name.startsWith("memory-engine/")
                    if (keep && !entry.isDirectory) {
                        val target = File(targetRoot, name)
                        if (!target.canonicalPath.startsWith(targetBase + File.separator)) {
                            throw SecurityException("内置运行时路径异常：$name")
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zis.copyTo(it, 65536) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun disableExtensionStartupUpdate(targetRoot: File) {
        val file = File(targetRoot, "public/scripts/extensions.js")
        if (!file.exists()) return
        val original = file.readText()
        val oldLf = "    if (versionChanged && enableAutoUpdate) {\n" +
            "        await autoUpdateExtensions(false);\n" +
            "    }\n\n" +
            "    await activateExtensions();"
        val newLf = "    // Extension updates are manual on Android so startup never waits on network I/O.\n" +
            "    await activateExtensions();"
        val oldCrlf = oldLf.replace("\n", "\r\n")
        val newCrlf = newLf.replace("\n", "\r\n")
        val updated = when {
            original.contains(oldLf) -> original.replace(oldLf, newLf, ignoreCase = false)
            original.contains(oldCrlf) -> original.replace(oldCrlf, newCrlf, ignoreCase = false)
            else -> original
        }
        if (updated != original) {
            file.writeText(updated)
            Log.i(TAG, "Disabled extension startup auto-update in downloaded core")
        }
    }

    private fun applyBundledPatchManifest(
        context: Context,
        targetRoot: File,
    ) {
        val manifest = context.assets.open("core/core-patches.json")
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val patches = manifest.optJSONArray("patches") ?: return
        for (index in 0 until patches.length()) {
            val patch = patches.optJSONObject(index) ?: continue
            val path = patch.optString("path")
            val old = patch.optString("old")
            val new = patch.optString("new")
            if (path.isBlank() || old.isBlank() || new.isBlank()) continue
            val file = File(targetRoot, path)
            if (!file.exists()) {
                throw Exception("运行时补丁目标不存在：$path")
            }
            val original = file.readText()
            if (
                original.contains(new)
                || original.contains(new.replace("\n", "\r\n"))
            ) {
                continue
            }
            val oldCandidates = listOf(old, old.replace("\n", "\r\n"))
            val newCandidates = listOf(new, new.replace("\n", "\r\n"))
            var matchedIndex = -1
            for (candidateIndex in oldCandidates.indices) {
                if (original.contains(oldCandidates[candidateIndex])) {
                    matchedIndex = candidateIndex
                    break
                }
            }
            if (matchedIndex < 0) {
                throw Exception("运行时补丁锚点不匹配：$path")
            }
            file.writeText(
                original.replaceFirst(
                    oldCandidates[matchedIndex],
                    newCandidates[matchedIndex]
                )
            )
        }
        Log.i(TAG, "Applied ${patches.length()} runtime core patches")
    }

    suspend fun applyUpdate(
        context: Context,
        downloadUrl: String,
        version: String,
        onProgress: suspend (Float, String, Boolean) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val coreDir = File(context.filesDir, "core")
            val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tmpZip = File(context.cacheDir, "tavern-update-$safeVersion.zip")

            onProgress(0.05f, "正在下载 ST $version …", true)
            for (retry in 1..3) {
                // Checked before connecting: a cancel that arrives while the previous attempt was
                // backing off has to end the update, not start another download.
                if (isCancelled()) throw UpdateCancelledException()
                try {
                    val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "TavernApp")
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    conn.connectTimeout = 15_000
                    // Short on purpose. This timeout is also the worst case for noticing a
                    // cancel, because the flag is only read between reads of the body: at five
                    // minutes a cancel during a stall looked like a button that did nothing.
                    // A gap this long mid-download is rare, and the retry loop above still covers
                    // the case where the network really did drop.
                    conn.readTimeout = 15_000
                    conn.instanceFollowRedirects = true
                    activeDownload = conn

                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        conn.disconnect()
                        if (retry < 3) continue
                        throw Exception("下载失败：HTTP $code")
                    }

                    conn.inputStream.use { input ->
                        activeStream = input
                        FileOutputStream(tmpZip).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var totalRead = 0L
                            var bytesRead: Int
                            val contentLength = conn.contentLengthLong
                            val startedAt = SystemClock.elapsedRealtime()
                            var lastProgressAt = 0L
                            while (input.read(buf).also { bytesRead = it } != -1) {
                                if (isCancelled()) throw UpdateCancelledException()
                                output.write(buf, 0, bytesRead)
                                totalRead += bytesRead
                                val pct = if (contentLength > 0) 0.05f + (totalRead.toFloat() / contentLength) * 0.25f else 0.05f
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastProgressAt >= 250L || totalRead == contentLength) {
                                    lastProgressAt = now
                                    val elapsedSeconds = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                                    val speedMb = (totalRead / 1024.0 / 1024.0) / elapsedSeconds
                                    val downloadedMb = totalRead / 1024.0 / 1024.0
                                    val totalMb = if (contentLength > 0) {
                                        contentLength / 1024.0 / 1024.0
                                    } else {
                                        null
                                    }
                                    val sizeText = if (totalMb != null) {
                                        String.format(
                                            Locale.US,
                                            "%.1f / %.1f MB",
                                            downloadedMb,
                                            totalMb
                                        )
                                    } else {
                                        String.format(Locale.US, "%.1f MB / 未知", downloadedMb)
                                    }
                                    onProgress(
                                        pct.coerceIn(0f, 1f),
                                        "下载 ST $version，$sizeText，" +
                                            String.format(Locale.US, "%.2f MB/s", speedMb),
                                        true
                                    )
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    break
                } catch (e: UpdateCancelledException) { throw e
                } catch (e: Exception) {
                    // A dropped connection or stream is how a cancel surfaces here; retrying it
                    // would undo the cancel and start the download over.
                    if (isCancelled()) throw UpdateCancelledException()
                    val msg = e.message?.take(60) ?: "unknown"
                    if (retry == 3) throw Exception("下载失败：$msg")
                    Thread.sleep(1000)
                } finally {
                    // Nothing is in flight between attempts, so a cancel arriving during the
                    // backoff is handled by the flag check at the top of the next one.
                    activeStream = null
                    activeDownload = null
                }
            }

            // Validate the downloaded zip is non-empty before extracting
            if (tmpZip.length() == 0L) {
                tmpZip.delete()
                throw Exception("下载失败：文件为空")
            }

            // Last chance to honour a cancel: the download can finish in the moment between the
            // reader's tap and the loop's next check, and up to here nothing has been touched, so
            // a cancel that arrives in that window can still be honoured rather than reported as
            // impossible a second later.
            if (isCancelled()) throw UpdateCancelledException()

            // 2. Backup user data & extensions
            // Cancelling stops being available here: from this point the core directory is being
            // replaced, and a half-replaced core is worse than an update the reader has to wait
            // out. The screen hides the button on this flag rather than offering a dead one.
            onProgress(0.3f, "校验 ST $version 并备份用户数据…", false)
            val dataDir = File(coreDir, "data")
            val extDir = File(coreDir, "public/scripts/extensions/third-party")
            val dataBak = File(context.filesDir, "data-update-bak")
            val extBak = File(context.cacheDir, "ext-update-bak")

            try { dataBak.deleteRecursively() } catch (_: Exception) {}
            try { extBak.deleteRecursively() } catch (_: Exception) {}

            if (dataDir.exists()) FileUtils.moveDirSafely(dataDir, dataBak)
            if (extDir.exists()) {
                extBak.parentFile?.mkdirs()
                FileUtils.moveDirSafely(extDir, extBak)
            }
            // Preserve user's config.yaml (whitelist, listen, custom settings) —
            // same behavior as AssetExtractor.extractCore so an update never
            // silently resets the user's own configuration.
            val configFile = File(coreDir, "config.yaml")
            val configBak = File(context.filesDir, "config-update-bak")
            try { configBak.delete() } catch (_: Exception) {}
            if (configFile.exists()) {
                configFile.renameTo(configBak)
                Log.i(TAG, "config.yaml backed up")
            }
            val optsFile = File(coreDir, "tavern-opts.json")
            val optsBak = File(context.filesDir, "tavern-opts-update-bak")
            try { optsBak.delete() } catch (_: Exception) {}
            if (optsFile.exists()) {
                optsFile.renameTo(optsBak)
                Log.i(TAG, "tavern-opts.json backed up")
            }

            // 3. Extract new core to temp dir (validate before swapping)
            onProgress(0.4f, "安装 ST $version…", false)
            val tmpCore = File(context.cacheDir, "core-update-tmp")
            try { tmpCore.deleteRecursively() } catch (_: Exception) {}
            tmpCore.mkdirs()

            // Detect the ST root inside the zip (the dir that contains server.js)
            // This allows robust removal of a variable-depth top-level folder
            // instead of relying on the first entry's top-level segment.
            var stPrefix = ""
            ZipInputStream(tmpZip.inputStream()).use { scan ->
                var e = scan.nextEntry
                while (e != null) {
                    val n = e.name.replace('\\', '/')
                    if (n.substringAfterLast('/') == "server.js") {
                        stPrefix = n.removeSuffix("server.js")
                        break
                    }
                    e = scan.nextEntry
                }
            }

            val tmpBase = tmpCore.canonicalPath
            ZipInputStream(tmpZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    // Skip entries outside the detected ST root (e.g. stray top-level files)
                    if (stPrefix.isNotEmpty() && !name.startsWith(stPrefix)) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val relativeName = if (stPrefix.isEmpty()) name else name.removePrefix(stPrefix)
                    if (relativeName.isEmpty()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (!entry.isDirectory) {
                        val target = File(tmpCore, relativeName)
                        // zip-slip path traversal guard
                        if (!target.canonicalPath.startsWith(tmpBase + File.separator) &&
                            target.canonicalPath != tmpBase) {
                            throw SecurityException("更新包内条目路径非法：$name")
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zis.copyTo(it, 65536) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // GitHub core packages contain the upstream server only. Merge the
            // Android runtime layer from this APK before validating/swapping.
            restoreBundledRuntime(context, tmpCore)
            applyBundledPatchManifest(context, tmpCore)
            disableExtensionStartupUpdate(tmpCore)

            // Validate: must contain server.js
            if (
                !File(tmpCore, "server.js").exists()
                || !File(tmpCore, "server-wrapper.cjs").exists()
                || !File(tmpCore, "memory-engine/router.cjs").exists()
            ) {
                try { tmpCore.deleteRecursively() } catch (_: Exception) {}
                // Restore user data that was moved to backup earlier
                if (dataBak.exists()) {
                    coreDir.mkdirs()
                    FileUtils.moveDirSafely(dataBak, File(coreDir, "data"))
                }
                if (extBak.exists()) {
                    val restoredExt = File(coreDir, "public/scripts/extensions/third-party")
                    restoredExt.parentFile?.mkdirs()
                    FileUtils.moveDirSafely(extBak, restoredExt)
                }
                if (configBak.exists()) {
                    configBak.renameTo(File(coreDir, "config.yaml"))
                }
                if (optsBak.exists()) {
                    optsBak.renameTo(File(coreDir, "tavern-opts.json"))
                }
                throw Exception("更新包无效：缺少 Android 运行时文件")
            }

            // Swap: rename old core to backup, move new core into place
            val coreBak = File(context.filesDir, "core-update-bak")
            try { coreBak.deleteRecursively() } catch (_: Exception) {}
            var oldCoreMoved = false
            try {
                if (coreDir.exists()) {
                    FileUtils.moveDirSafely(coreDir, coreBak)
                    oldCoreMoved = true
                }
                FileUtils.moveDirSafely(tmpCore, coreDir)
            } catch (swapErr: Exception) {
                Log.e(TAG, "Swap failed, restoring old core: ${swapErr.message}")
                if (oldCoreMoved) {
                    try { coreDir.deleteRecursively() } catch (_: Exception) {}
                    if (coreBak.exists()) {
                        try { FileUtils.moveDirSafely(coreBak, coreDir) } catch (_: Exception) {}
                    }
                }
                throw swapErr
            }
            // Clean up old core backup on success
            try { coreBak.deleteRecursively() } catch (_: Exception) {}

            // 4. Restore user data & extensions
            onProgress(0.85f, "恢复用户数据并完成 ST $version…", false)
            if (dataBak.exists()) {
                val newDataDir = File(coreDir, "data")
                if (newDataDir.exists()) newDataDir.deleteRecursively()
                FileUtils.moveDirSafely(dataBak, newDataDir)
            }
            if (extBak.exists()) {
                val newExtDir = File(coreDir, "public/scripts/extensions/third-party")
                newExtDir.parentFile?.mkdirs()
                if (newExtDir.exists()) newExtDir.deleteRecursively()
                FileUtils.moveDirSafely(extBak, newExtDir)
            }
            // Restore user's config.yaml over the fresh core's default
            if (configBak.exists()) {
                val newConfig = File(coreDir, "config.yaml")
                try { newConfig.delete() } catch (_: Exception) {}
                configBak.renameTo(newConfig)
                Log.i(TAG, "config.yaml restored")
            }
            if (optsBak.exists()) {
                val newOpts = File(coreDir, "tavern-opts.json")
                try { newOpts.delete() } catch (_: Exception) {}
                optsBak.renameTo(newOpts)
                Log.i(TAG, "tavern-opts.json restored")
            }

            // 5. Write version
            File(context.filesDir, "core_version.txt").writeText(version)
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
            File(context.filesDir, "core_manual_update.txt").writeText(appVersion)
            PeriodicUpdateChecker.clearPendingCore(context)

            // 6. Cleanup
            tmpZip.delete()
            try { dataBak.deleteRecursively() } catch (_: Exception) {}
            try { extBak.deleteRecursively() } catch (_: Exception) {}
            try { configBak.delete() } catch (_: Exception) {}
            try { optsBak.delete() } catch (_: Exception) {}

            onProgress(0.95f, "ST $version 安装完成", false)
            Log.i(TAG, "Core updated to $version")
            // This core came from the network, not from the archive the stamp describes, so the
            // next start has to merge the bundled runtime into it rather than trust the stamp.
            invalidateRuntimeStamp(context)
            coreDir
        }
    }
}
