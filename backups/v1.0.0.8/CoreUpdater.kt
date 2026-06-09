package com.tavern.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class UpdateCancelledException : Exception("已取消")

object CoreUpdater {

    private const val TAG = "CoreUpdater"

    suspend fun applyUpdate(
        context: Context,
        downloadUrl: String,
        version: String,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val coreDir = File(context.filesDir, "core")
            val tmpZip = File(context.cacheDir, "tavern-update-$version.zip")

            onProgress(0.05f, "正在下载 ST $version …")
            for (retry in 1..3) {
                try {
                    val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "TavernApp")
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 300_000
                    conn.instanceFollowRedirects = true

                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        conn.disconnect()
                        if (retry < 3) continue
                        throw Exception("下载失败: HTTP $code")
                    }

                    conn.inputStream.use { input ->
                        FileOutputStream(tmpZip).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var totalRead = 0L
                            var bytesRead: Int
                            val contentLength = conn.contentLengthLong
                            while (input.read(buf).also { bytesRead = it } != -1) {
                                if (isCancelled()) throw UpdateCancelledException()
                                output.write(buf, 0, bytesRead)
                                totalRead += bytesRead
                                val pct = if (contentLength > 0) 0.05f + (totalRead.toFloat() / contentLength) * 0.25f else 0.05f
                                onProgress(pct.coerceIn(0f, 1f), "下载中… ${totalRead / 1024 / 1024}MB")
                            }
                        }
                    }
                    conn.disconnect()
                    break
                } catch (e: UpdateCancelledException) { throw e
                } catch (e: Exception) {
                    val msg = e.message?.take(60) ?: "unknown"
                    if (retry == 3) throw Exception("下载失败: $msg")
                    Thread.sleep(1000)
                }
            }

            // ── 2. Backup user data & extensions ──
            onProgress(0.3f, "备份用户数据…")
            val dataDir = File(coreDir, "data")
            val extDir = File(coreDir, "public/scripts/extensions/third-party")
            val dataBak = File(context.cacheDir, "data-update-bak")
            val extBak = File(context.cacheDir, "ext-update-bak")

            try { dataBak.deleteRecursively() } catch (_: Exception) {}
            try { extBak.deleteRecursively() } catch (_: Exception) {}

            if (dataDir.exists()) {
                val ok = dataDir.renameTo(dataBak)
                if (!ok) {
                    Log.w(TAG, "renameTo failed for data, copying…")
                    try {
                        dataBak.mkdirs()
                        dataDir.copyRecursively(dataBak, overwrite = true)
                        // Only delete original after copy succeeds
                        dataDir.deleteRecursively()
                    } catch (copyErr: Exception) {
                        Log.e(TAG, "Failed to backup data: ${copyErr.message}")
                        // Restore partial backup to avoid data loss
                        try { dataBak.deleteRecursively() } catch (_: Exception) {}
                        throw Exception("备份用户数据失败: ${copyErr.message}")
                    }
                }
            }
            if (extDir.exists()) {
                extBak.parentFile?.mkdirs()
                val ok = extDir.renameTo(extBak)
                if (!ok) {
                    Log.w(TAG, "renameTo failed for extensions, copying…")
                    try {
                        extBak.mkdirs()
                        extDir.copyRecursively(extBak, overwrite = true)
                        extDir.deleteRecursively()
                    } catch (copyErr: Exception) {
                        Log.e(TAG, "Failed to backup extensions: ${copyErr.message}")
                        // Restore partial backup to avoid data loss
                        try { extBak.deleteRecursively() } catch (_: Exception) {}
                        throw Exception("备份扩展数据失败: ${copyErr.message}")
                    }
                }
            }

            // ── 3. Extract new core to temp dir (validate before swapping) ──
            onProgress(0.4f, "安装新版本…")
            val tmpCore = File(context.cacheDir, "core-update-tmp")
            try { tmpCore.deleteRecursively() } catch (_: Exception) {}
            tmpCore.mkdirs()

            ZipInputStream(tmpZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var prefix = ""
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (prefix.isEmpty() && name.contains("/")) {
                        prefix = name.substringBefore("/") + "/"
                    }
                    val relativeName = name.removePrefix(prefix)
                    if (relativeName.isEmpty()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (!entry.isDirectory) {
                        val target = File(tmpCore, relativeName)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zis.copyTo(it, 65536) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Validate: must contain server.js
            if (!File(tmpCore, "server.js").exists()) {
                try { tmpCore.deleteRecursively() } catch (_: Exception) {}
                // Restore user data that was moved to backup earlier
                if (dataBak.exists()) {
                    coreDir.mkdirs()
                    val restoredData = File(coreDir, "data")
                    if (!dataBak.renameTo(restoredData)) {
                        dataBak.copyRecursively(restoredData, true)
                        dataBak.deleteRecursively()
                    }
                }
                if (extBak.exists()) {
                    val restoredExt = File(coreDir, "public/scripts/extensions/third-party")
                    restoredExt.parentFile?.mkdirs()
                    if (!extBak.renameTo(restoredExt)) {
                        extBak.copyRecursively(restoredExt, true)
                        extBak.deleteRecursively()
                    }
                }
                throw Exception("更新包无效：缺少 server.js")
            }

            // Swap: rename old core to backup, move new core into place
            val coreBak = File(context.cacheDir, "core-update-bak")
            try { coreBak.deleteRecursively() } catch (_: Exception) {}
            // Move old core aside instead of deleting it — safe rollback point
            val oldCoreMoved = if (coreDir.exists()) {
                coreDir.renameTo(coreBak)
            } else {
                true // no old core to move
            }
            try {
                if (!tmpCore.renameTo(coreDir)) {
                    // renameTo may fail across filesystems; fall back to copy
                    tmpCore.copyRecursively(coreDir, overwrite = true)
                    tmpCore.deleteRecursively()
                }
            } catch (swapErr: Exception) {
                // Restore old core on failure
                Log.e(TAG, "Swap failed, restoring old core: ${swapErr.message}")
                try { coreDir.deleteRecursively() } catch (_: Exception) {}
                if (oldCoreMoved && coreBak.exists()) {
                    if (!coreBak.renameTo(coreDir)) {
                        coreBak.copyRecursively(coreDir, overwrite = true)
                        coreBak.deleteRecursively()
                    }
                }
                throw swapErr
            }
            // Clean up old core backup on success
            try { coreBak.deleteRecursively() } catch (_: Exception) {}

            // ── 4. Restore user data & extensions ──
            onProgress(0.85f, "恢复用户数据…")
            if (dataBak.exists()) {
                val newDataDir = File(coreDir, "data")
                if (newDataDir.exists()) newDataDir.deleteRecursively()
                dataBak.renameTo(newDataDir)
                if (dataBak.exists()) {
                    dataBak.copyRecursively(newDataDir, overwrite = true)
                    dataBak.deleteRecursively()
                }
            }
            if (extBak.exists()) {
                val newExtDir = File(coreDir, "public/scripts/extensions/third-party")
                newExtDir.parentFile?.mkdirs()
                if (newExtDir.exists()) newExtDir.deleteRecursively()
                extBak.renameTo(newExtDir)
                if (extBak.exists()) {
                    newExtDir.mkdirs()
                    extBak.copyRecursively(newExtDir, overwrite = true)
                    extBak.deleteRecursively()
                }
            }

            // ── 5. Apply Android patches ──
            onProgress(0.95f, "应用兼容补丁…")
            try {
                context.assets.open("core/plugin-adapter.js").use { input ->
                    FileOutputStream(File(coreDir, "plugin-adapter.js")).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy plugin-adapter.js: ${e.message}")
            }

            // ── 6. Write version ──
            File(context.filesDir, "core_version.txt").writeText(version)

            // ── 7. Cleanup ──
            tmpZip.delete()
            try { dataBak.deleteRecursively() } catch (_: Exception) {}
            try { extBak.deleteRecursively() } catch (_: Exception) {}

            onProgress(1f, "更新完成")
            Log.i(TAG, "Core updated to $version")
            coreDir
        }
    }
}
