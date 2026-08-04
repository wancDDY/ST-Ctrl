package com.tavern.app.backup

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_DIR_NAME = "TavernBackups"
        // Callback to stop/restart Node during restore — set by MainActivity
        var onBeforeRestore: (suspend () -> Unit)? = null
        var onAfterRestore: (suspend () -> Unit)? = null
    }

    val backupDir: File by lazy {
        val publicDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        val dir = File(publicDir, BACKUP_DIR_NAME)
        if ((dir.exists() || (publicDir.exists() && dir.mkdirs()) || (publicDir.mkdirs() && dir.mkdirs())) && dir.canWrite()) {
            dir
        } else {
            val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_DIR_NAME)
            if (!fallback.exists()) fallback.mkdirs()
            fallback
        }
    }

    suspend fun listBackups(): List<Pair<File, BackupMetadata>> =
        withContext(Dispatchers.IO) {
            val zipFiles = if (android.os.Build.VERSION.SDK_INT >= 29) {
                listBackupsViaMediaStore()
            } else {
                backupDir.listFiles { f -> f.name.endsWith(".zip") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
            }
            zipFiles.map { file ->
                // Quick stats — don't open the zip for basic listing
                file to BackupMetadata(
                    timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(file.lastModified())),
                    appVersion = "", coreVersion = "",
                    fileCount = 0, totalSizeBytes = file.length()
                )
            }
        }

    /** Single-pass metadata read that also collects fallback data (avoids double-scan). */
    private fun readMetadataWithFallback(file: File): BackupMetadata {
        var metadata: BackupMetadata? = null
        var hasData = false
        var fileCount = 0
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    fileCount++
                    if (entry.name.startsWith("data/")) hasData = true
                    if (entry.name == "backup.json" && metadata == null) {
                        try {
                            metadata = BackupMetadata.fromJson(zis.bufferedReader().readText())
                        } catch (_: Exception) {}
                    }
                }
                entry = zis.nextEntry
            }
        }
        return metadata ?: if (hasData) BackupMetadata(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            appVersion = "imported", coreVersion = "unknown",
            fileCount = fileCount, totalSizeBytes = file.length()
        ) else BackupMetadata(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(file.lastModified())),
            appVersion = "unknown", coreVersion = "unknown",
            fileCount = 0, totalSizeBytes = file.length()
        )
    }

    private fun listBackupsViaMediaStore(): List<File> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<File>()

        // 1. Direct filesystem files (our own backups — always visible)
        backupDir.listFiles { f -> f.name.endsWith(".zip") }?.forEach {
            if (seen.add(it.absolutePath)) result.add(it)
        }

        // 2. MediaStore files (Termux / other apps — need ContentResolver on API 29+)
        // NOTE: MediaStore.Files.FileColumns.DATA is deprecated on API 29+ and returns null.
        // On Android 10+ devices, Termux-created backups outside our own directory
        // will not be discoverable until we adopt SAF/DocumentsContract.
        // The direct filesystem listing (#1 above) still works for our own backups.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            val uri = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(android.provider.MediaStore.Files.FileColumns.DATA)
            val selection = "${android.provider.MediaStore.Files.FileColumns.DATA} like ?"
            val selectionArgs = arrayOf("%TavernBackups%")
            try {
                context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val dataIdx = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DATA)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataIdx)
                        if (path != null && path.endsWith(".zip") && seen.add(path)) {
                            val file = File(path)
                            if (file.exists()) result.add(file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore query failed: ${e.message}")
            }
        }

        return result.sortedByDescending { it.lastModified() }
    }

    fun readMetadata(zipFile: File): BackupMetadata? {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") {
                    val json = zis.bufferedReader().readText()
                    return BackupMetadata.fromJson(json)
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Validate a ZIP that may not have backup.json.
     * Returns synthetic metadata if the ZIP contains recognizable data/ entries.
     */
    fun validateBackupZip(zipFile: File): BackupMetadata? {
        var hasData = false
        var fileCount = 0
        var source = ""
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    fileCount++
                    if (entry.name.startsWith("data/")) hasData = true
                    if (entry.name == "backup.json") {
                        try {
                            val json = zis.bufferedReader().readText()
                            source = org.json.JSONObject(json).optString("source", "")
                        } catch (_: Exception) {}
                    }
                }
                entry = zis.nextEntry
            }
        }
        if (!hasData) return null
        return BackupMetadata(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            appVersion = "imported",
            coreVersion = "unknown",
            fileCount = fileCount,
            totalSizeBytes = zipFile.length(),
            source = source
        )
    }

    suspend fun createBackup(
        coreDir: File,
        coreVersion: String,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val files = mutableListOf<Pair<File, String>>()
            val dataDir = File(coreDir, "data")
            val extDir = File(coreDir, "public/scripts/extensions/third-party")
            val themesDir = File(coreDir, "public/themes")
            val avatarsDir = File(coreDir, "public/User Avatars")
            val configFile = File(coreDir, "config.yaml")

            // skip ST runtime dirs and content.log
            val skipNames = setOf("_cache", "_errors", "_storage", "_webpack")
            val skipFiles = setOf("content.log")

            fun shouldSkip(absolutePath: String): Boolean {
                val parts = absolutePath.split("/")
                return parts.any { it in skipNames } || parts.last() in skipFiles
            }

            onProgress(0, 0, "扫描用户数据…")
            if (dataDir.exists()) {
                dataDir.walkTopDown().filter { f ->
                    f.isFile && !Files.isSymbolicLink(f.toPath()) && !shouldSkip(f.absolutePath)
                }.forEach {
                    val rel = it.absolutePath.removePrefix(dataDir.absolutePath).trimStart('/')
                    files.add(it to "data/$rel")
                    if (files.size % 50 == 0) onProgress(0, files.size, "已扫描 ${files.size} 个文件…")
                }
            }

            if (extDir.exists()) {
                onProgress(0, files.size, "扫描扩展程序…")
                extDir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach {
                    val rel = it.absolutePath.removePrefix(extDir.absolutePath).trimStart('/')
                    files.add(it to "extensions/$rel")
                }
            }

            if (themesDir.exists()) {
                onProgress(0, files.size, "扫描 UI 主题…")
                themesDir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach {
                    val rel = it.absolutePath.removePrefix(themesDir.absolutePath).trimStart('/')
                    files.add(it to "root/public/themes/$rel")
                }
            }

            if (avatarsDir.exists()) {
                onProgress(0, files.size, "扫描用户头像…")
                avatarsDir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach {
                    val rel = it.absolutePath.removePrefix(avatarsDir.absolutePath).trimStart('/')
                    files.add(it to "root/public/User Avatars/$rel")
                }
            }

            if (configFile.exists()) {
                files.add(configFile to "root/config.yaml")
            }

            val total = files.size
            onProgress(0, total, "扫描完成，共 $total 个文件，开始打包…")

            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val prefix = if (coreVersion == "auto") "AutoBackup_" else "TavernBackup_"
            val backupFile = File(backupDir, "${prefix}${ts}.zip")
            val totalSize = files.sumOf { it.first.length() }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile))).use { zos ->
                val meta = BackupMetadata(
                    timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
                    appVersion = try {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get app version: ${e.message}")
                        "unknown"
                    },
                    coreVersion = coreVersion,
                    fileCount = total,
                    totalSizeBytes = totalSize
                )
                zos.putNextEntry(ZipEntry("backup.json"))
                zos.write(meta.toJson().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                files.forEachIndexed { index, (file, relativePath) ->
                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    onProgress(index + 1, total, file.name)
                }
            }

            Log.i(TAG, "Backup created: ${backupFile.name} ($total files, $totalSize bytes)")
            Result.success(backupFile)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun restoreBackup(
        backupFile: File,
        coreDir: File,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Stop Node before replacing files to avoid file lock corruption
        try { onBeforeRestore?.invoke() } catch (_: Exception) {}
        val dataDir = File(coreDir, "data")
        val extDir = File(coreDir, "public/scripts/extensions/third-party")
        var dataBak: File? = null
        var extBak: File? = null
        try {
            val skipNames = setOf("_cache", "_errors", "_storage", "_webpack")

            // Save webpack cache so it survives restore (avoids slow recompile)
            val webpackCache = File(dataDir, "_webpack")
            val webpackBak = File(coreDir.parentFile, "webpack-restore-bak")
            if (webpackCache.exists()) {
                try { webpackBak.deleteRecursively() } catch (_: Exception) {}
                webpackCache.renameTo(webpackBak)
            }

            // Backup existing data before wiping (safety net for failed restore)
            dataBak = File(coreDir.parentFile, "data-restore-bak")
            extBak = File(coreDir.parentFile, "ext-restore-bak")
            try { dataBak!!.deleteRecursively() } catch (_: Exception) {}
            try { extBak!!.deleteRecursively() } catch (_: Exception) {}
            fun safeMoveToBackup(src: File, dst: File) {
                // Try fast atomic rename first
                if (src.renameTo(dst)) return
                // Cross-filesystem fallback: copy first, then delete source
                try {
                    dst.deleteRecursively()
                    src.copyRecursively(dst, true)
                    src.deleteRecursively()
                } catch (e: Exception) {
                    Log.w(TAG, "safeMoveToBackup fallback for ${src.name}: ${e.message}")
                }
            }
            if (dataDir.exists()) safeMoveToBackup(dataDir, dataBak)
            if (extDir.exists()) { extBak!!.parentFile?.mkdirs(); safeMoveToBackup(extDir, extBak!!) }
            dataDir.mkdirs()
            extDir.parentFile?.mkdirs()
            extDir.mkdirs()

            var restoredCount = 0
            // Pre-scan to count total entries for progress
            var totalEntries = 0
            ZipInputStream(BufferedInputStream(FileInputStream(backupFile))).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    val name = ze.name.replace('\\', '/')
                    if (!ze.isDirectory && name != "backup.json" &&
                        (name.startsWith("data/") || name.startsWith("extensions/") || name.startsWith("root/"))) {
                        val parts = name.split("/")
                        if (parts.none { it in skipNames } && parts.last() != "content.log") totalEntries++
                    }
                    ze = zis.nextEntry
                }
            }
            onProgress(0, totalEntries, "共 $totalEntries 项，开始还原…")
            ZipInputStream(BufferedInputStream(FileInputStream(backupFile))).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    // Normalize Windows-style backslash paths
                    val name = ze.name.replace('\\', '/')
                    // Inline filtering — single pass, no pre-scan
                    val isValid = !ze.isDirectory && name != "backup.json" &&
                        (name.startsWith("data/") || name.startsWith("extensions/") || name.startsWith("root/"))
                    val parts = name.split("/")
                    val isSkipped = parts.any { it in skipNames } || parts.last() == "content.log"
                    if (isValid && !isSkipped) {
                        val out: File = when {
                            name.startsWith("data/") -> {
                                val rel = name.removePrefix("data/")
                                File(dataDir, rel)
                            }
                            name.startsWith("extensions/") -> {
                                val rel = name.removePrefix("extensions/")
                                File(extDir, rel)
                            }
                            name.startsWith("root/") -> {
                                val rel = name.removePrefix("root/")
                                File(coreDir, rel)
                            }
                            else -> { ze = zis.nextEntry; continue }
                        }
                        // zip-slip path traversal defence
                        val safeBase = when {
                            name.startsWith("data/") -> dataDir.canonicalPath
                            name.startsWith("extensions/") -> extDir.canonicalPath
                            else -> coreDir.canonicalPath
                        }
                        if (!out.canonicalPath.startsWith(safeBase + File.separator) &&
                            out.canonicalPath != safeBase) {
                            throw SecurityException("检测到路径穿越攻击: $name")
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        restoredCount++
                        onProgress(restoredCount, totalEntries, ze.name)
                    }
                    ze = zis.nextEntry
                }
            }

            // Cleanup temp backup on success
            try { dataBak?.deleteRecursively() } catch (_: Exception) {}
            try { extBak?.deleteRecursively() } catch (_: Exception) {}

            val settingsFile = File(dataDir, "default-user/settings.json")
            if (!settingsFile.exists()) {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText("""{"firstRun":false}""")
            }

            // Restore webpack cache so next start doesn't recompile
            if (webpackBak.exists()) {
                try { webpackCache.deleteRecursively() } catch (_: Exception) {}
                webpackBak.renameTo(webpackCache)
            }

            Log.i(TAG, "Restore complete: ${backupFile.name} ($restoredCount files)")
            onAfterRestore?.invoke()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            // Recover original data on failure
            try {
                if (dataBak != null && dataBak!!.exists()) {
                    if (dataDir.exists()) dataDir.deleteRecursively()
                    if (!dataBak!!.renameTo(dataDir)) {
                        dataBak!!.copyRecursively(dataDir, true)
                        dataBak!!.deleteRecursively()
                    }
                }
                if (extBak != null && extBak!!.exists()) {
                    if (extDir.exists()) extDir.deleteRecursively()
                    if (!extBak!!.renameTo(extDir)) {
                        extBak!!.copyRecursively(extDir, true)
                        extBak!!.deleteRecursively()
                    }
                }
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    fun deleteBackup(file: File): Boolean = file.delete()

    fun getBackupsSize(): Long =
        backupDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }
    }

    suspend fun cleanupOldAutoBackups(maxKeep: Int) = withContext(Dispatchers.IO) {
        val all = listBackups()
        // Auto backups are named "AutoBackup_*" — identify by filename, not metadata
        val autoBackups = all.filter { it.first.name.startsWith("AutoBackup_") }
        val effectiveMaxKeep = maxKeep.coerceAtLeast(1)
        if (autoBackups.size > effectiveMaxKeep) {
            autoBackups.drop(effectiveMaxKeep).forEach { (file, _) ->
                if (!file.delete() && file.exists()) {
                    Log.w(TAG, "Failed to delete old auto-backup: ${file.name} (may be locked)")
                } else {
                    Log.i(TAG, "Deleted old auto-backup: ${file.name}")
                }
            }
        }
    }
}
