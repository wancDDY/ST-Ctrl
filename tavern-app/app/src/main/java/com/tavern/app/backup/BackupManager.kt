package com.tavern.app.backup

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_DIR_NAME = "TavernBackups"
    }

    val backupDir: File
        get() {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ), BACKUP_DIR_NAME
            )
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun listBackups(): List<Pair<File, BackupMetadata>> =
        withContext(Dispatchers.IO) {
            val files = backupDir.listFiles { f -> f.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            files.mapNotNull { file ->
                try {
                    val meta = readMetadata(file)
                    if (meta != null) file to meta else null
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read backup: ${file.name}", e)
                    null
                }
            }
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
                val parts = absolutePath.split("/", "\\")
                return parts.any { it in skipNames } || parts.last() in skipFiles
            }

            onProgress(0, 0, "扫描用户数据…")
            if (dataDir.exists()) {
                dataDir.walkTopDown().filter { f ->
                    f.isFile && !shouldSkip(f.absolutePath)
                }.forEach {
                    val rel = it.absolutePath.removePrefix(dataDir.absolutePath).trimStart('/')
                    files.add(it to "data/$rel")
                    if (files.size % 50 == 0) onProgress(0, files.size, "已扫描 ${files.size} 个文件…")
                }
            }

            if (extDir.exists()) {
                onProgress(0, files.size, "扫描扩展程序…")
                extDir.walkTopDown().filter { it.isFile }.forEach {
                    val rel = it.absolutePath.removePrefix(extDir.absolutePath).trimStart('/')
                    files.add(it to "extensions/$rel")
                }
            }

            if (themesDir.exists()) {
                onProgress(0, files.size, "扫描 UI 主题…")
                themesDir.walkTopDown().filter { it.isFile }.forEach {
                    val rel = it.absolutePath.removePrefix(themesDir.absolutePath).trimStart('/')
                    files.add(it to "root/public/themes/$rel")
                }
            }

            if (avatarsDir.exists()) {
                onProgress(0, files.size, "扫描用户头像…")
                avatarsDir.walkTopDown().filter { it.isFile }.forEach {
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
            val backupFile = File(backupDir, "TavernBackup_$ts.zip")
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
        try {
            val dataDir = File(coreDir, "data")
            val extDir = File(coreDir, "public/scripts/extensions/third-party")

            val skipNames = setOf("_cache", "_errors", "_storage", "_webpack")

            data class Entry(val name: String, val isDir: Boolean)

            val entries = mutableListOf<Entry>()
            ZipInputStream(BufferedInputStream(FileInputStream(backupFile))).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    if (ze.name == "backup.json") { ze = zis.nextEntry; continue }
                    val parts = ze.name.split("/")
                    val isSkipped = parts.any { it in skipNames } || parts.last() == "content.log"
                    if (!isSkipped && (ze.name.startsWith("data/") ||
                            ze.name.startsWith("extensions/") ||
                            ze.name.startsWith("root/"))) {
                        entries.add(Entry(ze.name, ze.isDirectory))
                    }
                    ze = zis.nextEntry
                }
            }
            val total = entries.count { !it.isDir }

            if (dataDir.exists()) dataDir.deleteRecursively()
            if (extDir.exists()) extDir.deleteRecursively()
            dataDir.mkdirs()
            extDir.parentFile?.mkdirs()
            extDir.mkdirs()

            ZipInputStream(BufferedInputStream(FileInputStream(backupFile))).use { zis ->
                var ze = zis.nextEntry
                var count = 0
                val targetNames = entries.map { it.name }.toSet()
                while (ze != null) {
                    if (ze.name in targetNames && !ze.isDirectory) {
                        val out: File = when {
                            ze.name.startsWith("data/") -> {
                                val rel = ze.name.removePrefix("data/")
                                File(dataDir, rel)
                            }
                            ze.name.startsWith("extensions/") -> {
                                val rel = ze.name.removePrefix("extensions/")
                                File(extDir, rel)
                            }
                            ze.name.startsWith("root/") -> {
                                val rel = ze.name.removePrefix("root/")
                                File(coreDir, rel)
                            }
                            else -> { ze = zis.nextEntry; continue }
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        count++
                        onProgress(count, total, ze.name)
                    }
                    ze = zis.nextEntry
                }
            }

            val settingsFile = File(dataDir, "default-user/settings.json")
            if (!settingsFile.exists()) {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText("""{"firstRun":false}""")
            }

            Log.i(TAG, "Restore complete: ${backupFile.name} ($total files)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            Result.failure(e)
        }
    }

    fun deleteBackup(file: File): Boolean = file.delete()

    fun getBackupsSize(): Long =
        backupDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun cleanupOldAutoBackups(maxKeep: Int) = withContext(Dispatchers.IO) {
        val all = listBackups()
        val autoBackups = all.filter { it.first.name.startsWith("TavernBackup_") }
        if (autoBackups.size > maxKeep) {
            autoBackups.drop(maxKeep).forEach { (file, _) ->
                file.delete()
                Log.i(TAG, "Deleted old backup: ${file.name}")
            }
        }
    }
}
