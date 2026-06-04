package com.tavern.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object CoreUpdater {

    suspend fun applyUpdate(
        context: Context,
        downloadUrl: String,
        version: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val coreDir = File(context.filesDir, "core")
            val tempZip = File(context.cacheDir, "tavern-update-$version.zip")

            // 1. Download
            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("User-Agent", "TavernApp/1.0")
            conn.inputStream.use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            }

            // 2. Backup old version
            val backupDir = File(context.filesDir, "core_backup")
            if (backupDir.exists()) backupDir.deleteRecursively()
            if (coreDir.exists()) {
                coreDir.copyRecursively(backupDir, overwrite = true)
            }

            // 3. Clear and extract
            coreDir.deleteRecursively()
            coreDir.mkdirs()
            ZipInputStream(tempZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var prefix = ""
                while (entry != null) {
                    val name = entry.name
                    if (prefix.isEmpty() && name.contains("/")) {
                        prefix = name.substringBefore("/") + "/"
                    }
                    val relativeName = name.removePrefix(prefix)
                    if (relativeName.isEmpty()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val targetFile = File(coreDir, relativeName)
                    if (!entry.isDirectory) {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 4. Write version
            File(context.filesDir, "core_version.txt").writeText(version)

            // 5. Cleanup — keep backup for manual rollback, delete temp zip only
            tempZip.delete()
            // backupDir intentionally kept: users can manually restore if update breaks

            coreDir
        }
    }
}
