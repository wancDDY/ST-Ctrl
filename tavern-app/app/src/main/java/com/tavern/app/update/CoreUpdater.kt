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

object CoreUpdater {

    private const val TAG = "CoreUpdater"

    suspend fun applyUpdate(
        context: Context,
        downloadUrl: String,
        version: String,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> }
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
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    conn.disconnect()
                    break
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
                    dataBak.mkdirs()
                    dataDir.copyRecursively(dataBak, overwrite = true)
                    dataDir.deleteRecursively()
                }
            }
            if (extDir.exists()) {
                extBak.parentFile?.mkdirs()
                val ok = extDir.renameTo(extBak)
                if (!ok) {
                    Log.w(TAG, "renameTo failed for extensions, copying…")
                    extBak.mkdirs()
                    extDir.copyRecursively(extBak, overwrite = true)
                    extDir.deleteRecursively()
                }
            }

            // ── 3. Extract new core ──
            onProgress(0.4f, "安装新版本…")
            coreDir.deleteRecursively()
            coreDir.mkdirs()

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
                        val target = File(coreDir, relativeName)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zis.copyTo(it, 65536) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // ── 4. Restore user data & extensions ──
            onProgress(0.85f, "恢复用户数据…")
            if (dataBak.exists()) {
                if (dataDir.exists()) dataDir.deleteRecursively()
                dataBak.renameTo(dataDir)
                if (dataBak.exists()) {
                    dataBak.copyRecursively(dataDir, overwrite = true)
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
