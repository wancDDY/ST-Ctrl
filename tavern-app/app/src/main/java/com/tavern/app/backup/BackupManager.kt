package com.tavern.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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

    /** App-private backup dir (external app-private or internal filesDir). */
    private fun privateBackupDir(): File {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_DIR_NAME)
        if ((base.exists() || base.mkdirs()) && base.canWrite()) return base
        val fb = File(context.filesDir, BACKUP_DIR_NAME)
        if (!fb.exists()) fb.mkdirs()
        return fb
    }

    fun publicTreeUri(): Uri? =
        com.tavern.app.console.SettingsState.backupTreeUri(context)
            .takeIf { it.isNotBlank() }
            ?.let(Uri::parse)

    fun publicTree(): DocumentFile? {
        return try {
            val uri = publicTreeUri() ?: return null
            val tree = DocumentFile.fromTreeUri(context, uri)
            tree?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
        } catch (_: Exception) {
            null
        }
    }

    fun hasPublicTreePermission(): Boolean = publicTree() != null

    fun persistTreePermission(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        com.tavern.app.console.SettingsState.setBackupTreeUri(context, uri.toString())
    }

    suspend fun listBackups(): List<Pair<BackupEntry, BackupMetadata>> =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<Pair<BackupEntry, BackupMetadata>>()
            collectPrivateBackupFiles().forEach { file ->
                entries += BackupEntry(
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    file = file,
                    privateStorage = true
                ) to quickMetadata(file.length(), file.lastModified(), "private")
            }

            publicTree()?.listFiles()
                ?.filter { it.isFile && it.name?.endsWith(".zip", ignoreCase = true) == true }
                ?.forEach { doc ->
                    val name = doc.name ?: return@forEach
                    entries += BackupEntry(
                        name = name,
                        sizeBytes = doc.length(),
                        lastModified = doc.lastModified(),
                        uri = doc.uri,
                        folderUri = doc.parentFile?.uri,
                        privateStorage = false
                    ) to quickMetadata(doc.length(), doc.lastModified(), "public")
                }

            entries.distinctBy { it.first.key }
                .sortedByDescending { it.first.lastModified }
        }

    private fun quickMetadata(size: Long, lastModified: Long, source: String) =
        BackupMetadata(
            timestamp = SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssZ", Locale.US
            ).format(Date(lastModified)),
            appVersion = "",
            coreVersion = "",
            fileCount = 0,
            totalSizeBytes = size,
            source = source
        )

    private fun collectPrivateBackupFiles(): List<File> {
        val dir = privateBackupDir()
        return dir.listFiles { f -> f.name.endsWith(".zip", ignoreCase = true) }
            ?.toList()
            .orEmpty()
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

    fun openEntryInput(entry: BackupEntry): InputStream {
        entry.file?.let { return BufferedInputStream(FileInputStream(it)) }
        val uri = entry.uri ?: throw IOException("备份文件位置无效")
        return context.contentResolver.openInputStream(uri)?.let(::BufferedInputStream)
            ?: throw IOException("无法读取备份文件")
    }

    suspend fun getBackupContents(entry: BackupEntry): List<String> =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<String>()
            try {
                ZipInputStream(openEntryInput(entry)).use { zis ->
                    var item = zis.nextEntry
                    while (item != null) {
                        if (!item.isDirectory) {
                            val size = if (item.size > 0) " (${item.size / 1024} KB)" else ""
                            entries += item.name + size
                        }
                        item = zis.nextEntry
                    }
                }
            } catch (_: Exception) {}
            entries
        }

    fun writeTermuxScript(): Boolean {
        val tree = publicTree() ?: return false
        val existing = tree.findFile("st-migrate.sh")
        val target = existing ?: tree.createFile("text/x-shellscript", "st-migrate.sh")
        target ?: return false
        val raw = context.assets.open("st-migrate.sh").bufferedReader().readText()
        val cleaned = raw.replace("\r", "").replace("\uFEFF", "")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            output.write(cleaned.toByteArray(Charsets.UTF_8))
        } ?: return false
        return true
    }

    fun termuxScriptCommand(): String? {
        val uri = publicTreeUri() ?: return null
        return try {
            if (uri.authority != "com.android.externalstorage.documents") return null
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val parts = documentId.split(':', limit = 2)
            if (parts.size != 2) return null
            val volume = parts[0]
            val volumeRoot = when {
                volume == "primary" || volume == "home" -> "/storage/emulated/0"
                Regex("^[A-Fa-f0-9-]{4,}$").matches(volume) -> "/storage/$volume"
                else -> return null
            }
            val relative = parts[1].trim('/')
            val folder = if (relative.isEmpty()) volumeRoot else "$volumeRoot/$relative"
            "bash \"$folder/st-migrate.sh\""
        } catch (_: Exception) {
            null
        }
    }

    fun readMetadata(entry: BackupEntry): BackupMetadata? {
        ZipInputStream(openEntryInput(entry)).use { zis ->
            var item = zis.nextEntry
            while (item != null) {
                if (item.name == "backup.json") {
                    return BackupMetadata.fromJson(zis.bufferedReader().readText())
                }
                item = zis.nextEntry
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
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> },
        includeApiKeys: Boolean = true
    ): Result<BackupEntry> = withContext(Dispatchers.IO) {
        val restartNodeAfter = stopNodeForSafeBackup()
        var tempFile: File? = null
        try {
            val dataDir = File(coreDir, "data")
            val extDir = File(coreDir, "public/scripts/extensions/third-party")
            val configFile = File(coreDir, "config.yaml")

            // skip ST runtime dirs and content.log.
            // _uploads = temporary upload dir (no user data worth backing up).
            // Themes and avatars live under data/default-user/,
            // so they are already covered by the data/ walk below — no separate
            // public/ paths needed.
            val skipNames = setOf("_cache", "_errors", "_storage", "_webpack", "_uploads")
            val skipFiles = setOf("content.log")

            fun shouldSkip(absolutePath: String): Boolean {
                val parts = absolutePath.replace('\\', '/').split("/")
                return parts.any { it in skipNames } || parts.last() in skipFiles
            }

            onProgress(0, 0, "扫描用户数据…")
            val items = mutableListOf<BackupTree.Item>()
            if (dataDir.exists()) {
                items += BackupTree.collect(dataDir, "data") { child ->
                    shouldSkip(child.absolutePath) ||
                        (!includeApiKeys && isSensitiveBackupFile(child.absolutePath, dataDir))
                }
            }

            if (extDir.exists()) {
                onProgress(0, items.size, "扫描扩展程序…")
                // No runtime-dir filter here: inside an extension every name is the
                // extension's own, and dropping directories such as _cache would take
                // data the extension expects to find after a restore.
                items += BackupTree.collect(extDir, "extensions")
            }

            if (configFile.exists()) {
                items += BackupTree.Item(configFile, "root/config.yaml", directory = false)
            }

            // The app's own settings belong to "my setup" too: theme, port, switches,
            // auto-backup schedule. Secrets inside them follow the same opt-in as the
            // tavern's secrets.json.
            val appSettings = com.tavern.app.console.SettingsState
                .exportBackupSettings(context, includeApiKeys)
                .toString(2)
                .toByteArray(Charsets.UTF_8)

            val total = items.size
            onProgress(0, total, "扫描完成，共 $total 项，开始打包…")

            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val prefix = if (coreVersion == "auto") "AutoBackup_" else "TavernBackup_"
            val finalName = "${prefix}${ts}.zip"
            tempFile = File(context.cacheDir, "backup-$ts.zip.tmp")
            val totalSize = items.sumOf { if (it.directory) 0L else it.file.length() }
            val requiredBytes = totalSize + 16L * 1024 * 1024
            if (tempFile.parentFile?.usableSpace?.let { it < requiredBytes } == true) {
                throw IOException("存储空间不足：至少需要 ${requiredBytes / 1024 / 1024} MB")
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zos ->
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
                    fileCount = items.count { !it.directory },
                    totalSizeBytes = totalSize,
                    source = if (coreVersion == "auto") "auto" else "",
                    includeApiKeys = includeApiKeys,
                    containsAppSettings = true
                )
                zos.putNextEntry(ZipEntry("backup.json"))
                zos.write(meta.toJson().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("app/settings.json").apply { time = System.currentTimeMillis() })
                zos.write(appSettings)
                zos.closeEntry()

                items.forEachIndexed { index, item ->
                    val entry = ZipEntry(item.entryName)
                    // Carry the original modification time: a restore should not rewrite
                    // every date in the user's data with the restore time.
                    val modified = item.file.lastModified()
                    if (!item.directory && modified > 0L) entry.time = modified
                    zos.putNextEntry(entry)
                    if (!item.directory) {
                        item.file.inputStream().use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                    onProgress(index + 1, items.size, item.file.name)
                }
            }

            val entry = publishBackup(tempFile, finalName)
            Log.i(TAG, "Backup created: ${entry.name} ($total files, $totalSize bytes)")
            Result.success(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.failure(e)
        } finally {
            try { tempFile?.takeIf { it.exists() }?.delete() } catch (_: Exception) {}
            if (restartNodeAfter) {
                try { com.tavern.app.node.NodeRunner.requestStart(context) } catch (_: Exception) {}
            }
        }
    }

    private fun publishBackup(tempFile: File, finalName: String): BackupEntry {
        if (com.tavern.app.console.SettingsState.backupLocation() == "private") {
            val target = File(privateBackupDir(), finalName)
            if (!tempFile.renameTo(target)) {
                tempFile.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
            return BackupEntry(
                name = target.name,
                sizeBytes = target.length(),
                lastModified = target.lastModified(),
                file = target,
                privateStorage = true
            )
        }

        val tree = publicTree()
            ?: throw IOException("公共目录未授权，请重新选择备份文件夹")
        val target = tree.createFile("application/zip", finalName)
            ?: throw IOException("无法创建备份文件：$finalName")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            tempFile.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        } ?: throw IOException("无法写入公共目录备份")
        return BackupEntry(
            name = target.name ?: finalName,
            sizeBytes = target.length(),
            lastModified = target.lastModified(),
            uri = target.uri,
            folderUri = tree.uri,
            privateStorage = false
        )
    }

    fun validateBackupZip(entry: BackupEntry): BackupMetadata? {
        var hasData = false
        var fileCount = 0
        var source = ""
        ZipInputStream(openEntryInput(entry)).use { zis ->
            var item = zis.nextEntry
            while (item != null) {
                if (!item.isDirectory) {
                    fileCount++
                    if (item.name.startsWith("data/")) hasData = true
                    if (item.name == "backup.json") {
                        try {
                            source = org.json.JSONObject(
                                zis.bufferedReader().readText()
                            ).optString("source", "")
                        } catch (_: Exception) {}
                    }
                }
                item = zis.nextEntry
            }
        }
        if (!hasData) return null
        return BackupMetadata(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            appVersion = "imported",
            coreVersion = "unknown",
            fileCount = fileCount,
            totalSizeBytes = entry.sizeBytes,
            source = source
        )
    }

    private fun isSensitiveBackupFile(absolutePath: String, dataDir: File): Boolean {
        val rel = absolutePath.replace('\\', '/')
            .removePrefix(dataDir.absolutePath.replace('\\', '/'))
            .trimStart('/')
        val parts = rel.split("/")
        val name = parts.last().lowercase(java.util.Locale.US)
        if (name == "secrets.json") return true
        if (name == "memory-enhancement.json") return true
        if (name.startsWith("secrets_migration_") && parts.getOrNull(parts.size - 2) == "backups") return true
        return false
    }

    /** Stop Node before backup so an in-memory write cannot mutate the tree. */
    private suspend fun stopNodeForSafeBackup(): Boolean {
        return try {
            val port = com.tavern.app.node.NodeState.port.value
            val running = com.tavern.app.node.NodeRunner.isPortOpen(port)
            if (!running) return false
            com.tavern.app.node.NodeRunner.requestStop(context)
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline && com.tavern.app.node.NodeRunner.isPortOpen(port)) {
                kotlinx.coroutines.delay(150)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun restoreBackup(
        backup: BackupEntry,
        coreDir: File,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Stop Node before replacing files to avoid file lock corruption
        try { onBeforeRestore?.invoke() } catch (_: Exception) {}
        // requestStop is async IPC — wait until the :node process actually
        // releases the port before touching files, otherwise Node may still be
        // writing data/ (chat history etc.) during the overwrite.
        val nodePort = com.tavern.app.node.NodeState.port.value
        val stopDeadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < stopDeadline &&
            com.tavern.app.node.NodeRunner.isPortOpen(nodePort)) {
            kotlinx.coroutines.delay(150)
        }
        val dataDir = File(coreDir, "data")
        val extDir = File(coreDir, "public/scripts/extensions/third-party")
        val includeApiKeys = try { readMetadata(backup)?.includeApiKeys ?: true } catch (_: Exception) { true }
        // Declared outside the try so the catch block can roll them back too.
        val webpackCache = File(dataDir, "_webpack")
        val webpackBak = File(coreDir.parentFile, "webpack-restore-bak")
        val configFile = File(coreDir, "config.yaml")
        val configBak = File(coreDir.parentFile, "config-restore-bak")
        var dataBak: File? = null
        var extBak: File? = null
        try {
            val skipNames = setOf("_cache", "_errors", "_storage", "_webpack", "_uploads")

            // Save webpack cache so it survives restore (avoids slow recompile)
            if (webpackCache.exists()) {
                try { webpackBak.deleteRecursively() } catch (_: Exception) {}
                webpackCache.renameTo(webpackBak)
            }

            // Save the user's config.yaml so a failed restore can roll it back
            try { configBak.delete() } catch (_: Exception) {}
            if (configFile.exists()) configFile.renameTo(configBak)

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
            var appSettings: org.json.JSONObject? = null
            // Runtime-directory and log filters apply to the tavern data only. Inside
            // an extension every name belongs to the extension, so dropping a folder
            // such as _cache would delete content the archive had carried.
            fun shouldSkipEntry(name: String): Boolean {
                if (!name.startsWith("data/")) return false
                val parts = name.split("/")
                return parts.any { it in skipNames } || parts.last() == "content.log"
            }

            fun isKnownEntry(name: String): Boolean =
                name != "backup.json" &&
                    (name.startsWith("data/") || name.startsWith("extensions/") || name.startsWith("root/"))

            // Pre-scan to count total entries for progress
            var totalEntries = 0
            ZipInputStream(openEntryInput(backup)).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    val name = ze.name.replace('\\', '/')
                    if (isKnownEntry(name) && !shouldSkipEntry(name)) totalEntries++
                    ze = zis.nextEntry
                }
            }
            onProgress(0, totalEntries, "共 $totalEntries 项，开始还原…")
            ZipInputStream(openEntryInput(backup)).use { zis ->
                var ze = zis.nextEntry
                while (ze != null) {
                    // Normalize Windows-style backslash paths
                    val rawName = ze.name.replace('\\', '/')
                    if (rawName == "app/settings.json") {
                        appSettings = try {
                            org.json.JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        } catch (e: Exception) {
                            Log.w(TAG, "app/settings.json unreadable: ${e.message}")
                            null
                        }
                        ze = zis.nextEntry
                        continue
                    }
                    val isDirectoryEntry = ze.isDirectory || rawName.endsWith("/")
                    val name = if (isDirectoryEntry) rawName.trimEnd('/') else rawName
                    val isValid = isKnownEntry(rawName) || (isDirectoryEntry && isKnownEntry(name))
                    if (isValid && !shouldSkipEntry(name)) {
                        // Reject any ".." path segment up front: a crafted entry like
                        // root/public/x/../../server.js normalizes into coreDir/server.js
                        // and would bypass both the root/ whitelist and the canonicalPath
                        // check below (which only verifies "inside coreDir").
                        val parts = name.split("/")
                        if (parts.any { it == ".." }) {
                            throw SecurityException("压缩包内条目路径非法：$name")
                        }
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
                                // Whitelist: only config.yaml and public/ subpaths are
                                // legitimate under root/. Anything else (e.g.
                                // root/data/...) could bypass the zip-slip check below
                                // and overwrite real user data.
                                if (rel != "config.yaml" && !rel.startsWith("public/")) {
                                    throw SecurityException("备份包含不允许的 root/ 路径：$name")
                                }
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
                            throw SecurityException("压缩包内条目路径非法：$name")
                        }
                        if (isDirectoryEntry) {
                            // An empty directory only survives a restore if the archive
                            // carries its own entry.
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                            // Keep the modification time the archive recorded instead
                            // of stamping every restored file with "now".
                            if (ze.time > 0L) {
                                try { out.setLastModified(ze.time) } catch (_: Exception) {}
                            }
                        }
                        restoredCount++
                        onProgress(restoredCount, totalEntries, name)
                    }
                    ze = zis.nextEntry
                }
            }

            // A key-less backup must not wipe secrets that already exist on the
            // device. Copy them back from the pre-restore snapshot.
            if (!includeApiKeys && dataBak?.exists() == true) {
                dataBak!!.walkTopDown().filter { f ->
                    f.isFile && isSensitiveBackupFile(f.absolutePath, dataBak!!)
                }.forEach { source ->
                    val rel = source.absolutePath.removePrefix(dataBak!!.absolutePath).trimStart('/')
                    val target = File(dataDir, rel)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }

            // Cleanup temp backup on success
            try { dataBak?.deleteRecursively() } catch (_: Exception) {}
            try { extBak?.deleteRecursively() } catch (_: Exception) {}
            try { configBak.delete() } catch (_: Exception) {}

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

            // The app's own settings travel inside the archive; applying them is what
            // makes a restore put the console back the way it was (theme, port, the
            // tavern switches, auto-backup schedule). Archives written before this
            // existed simply have no such file.
            appSettings?.let { settings ->
                try {
                    com.tavern.app.console.SettingsState.applyBackupSettings(context, settings)
                } catch (e: Exception) {
                    // The data itself is restored; a settings failure must not turn a
                    // usable restore into a failed one.
                    Log.w(TAG, "app settings restore failed: ${e.message}")
                }
            }

            Log.i(TAG, "Restore complete: ${backup.name} ($restoredCount files)")
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
                // Restore config.yaml that was moved aside before restore
                if (configBak.exists()) {
                    try { File(coreDir, "config.yaml").delete() } catch (_: Exception) {}
                    configBak.renameTo(File(coreDir, "config.yaml"))
                }
                // Restore webpack cache so next start doesn't recompile
                if (webpackBak.exists()) {
                    try { webpackCache.deleteRecursively() } catch (_: Exception) {}
                    webpackBak.renameTo(webpackCache)
                }
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * The address a provider accepts for document operations on a file inside the
     * public backup folder. A document created through the tree comes back
     * addressed as a plain document, and providers reject that form for
     * rename/delete — the external storage provider answers with
     * UnsupportedOperationException, which is what used to crash the screen — so
     * the tree address is rebuilt from the document id.
     */
    private fun treeDocumentUri(uri: Uri): Uri {
        val tree = publicTreeUri() ?: return uri
        if (!DocumentsContract.isTreeUri(tree)) return uri
        return try {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(uri))
        } catch (_: Exception) {
            uri
        }
    }

    fun deleteBackup(entry: BackupEntry): Boolean {
        entry.file?.let { return it.delete() }
        val uri = entry.uri ?: return false
        // A document provider refuses an unsupported operation by throwing rather
        // than by answering false, and that throw used to travel out of the button
        // handler and kill the app. Every provider answer is folded into a boolean.
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, treeDocumentUri(uri))
        } catch (_: Exception) {
            false
        }
    }

    fun renameBackup(entry: BackupEntry, newName: String): Boolean {
        val normalized = if (newName.endsWith(".zip", ignoreCase = true)) newName else "$newName.zip"
        entry.file?.let { file ->
            val target = File(file.parentFile, normalized)
            return file.renameTo(target)
        }
        val uri = entry.uri ?: return false
        if (normalized == entry.name) return true
        val renamed = try {
            DocumentsContract.renameDocument(context.contentResolver, treeDocumentUri(uri), normalized) != null
        } catch (_: Exception) {
            false
        }
        // Providers are inconsistent about the answer: the external storage provider
        // performs the rename and still returns null, while providers without
        // renameDocument throw. The folder is therefore asked what it holds now —
        // the new name present and the old one gone is the rename.
        if (renamed || (publicBackupExists(normalized) && !publicBackupExists(entry.name))) {
            return true
        }
        // Neither form is implemented here (Downloads, MediaStore), so the bytes are
        // written into a new document and the original dropped afterwards. The
        // original is only deleted once the new document is complete, so a failure
        // leaves exactly one backup behind.
        return copyDocumentToName(uri, normalized)
    }

    private fun publicBackupExists(name: String): Boolean = try {
        publicTree()?.listFiles()?.any { it.name == name } == true
    } catch (_: Exception) {
        false
    }

    private fun copyDocumentToName(source: Uri, newName: String): Boolean {
        val tree = publicTree() ?: return false
        var targetUri: Uri? = null
        return try {
            targetUri = DocumentsContract.createDocument(
                context.contentResolver,
                tree.uri,
                "application/zip",
                newName,
            ) ?: return false
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                    input.copyTo(output)
                    true
                } ?: false
            } ?: false
            if (!copied) {
                DocumentsContract.deleteDocument(context.contentResolver, treeDocumentUri(targetUri))
                return false
            }
            if (!DocumentsContract.deleteDocument(context.contentResolver, treeDocumentUri(source))) {
                // The new document is complete, so one of the two has to go: keeping
                // both would leave the folder with two backups of the same data.
                DocumentsContract.deleteDocument(context.contentResolver, treeDocumentUri(targetUri))
                return false
            }
            true
        } catch (_: Exception) {
            try {
                targetUri?.let {
                    DocumentsContract.deleteDocument(context.contentResolver, treeDocumentUri(it))
                }
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * True if the backup file lives in an app-private directory (internal
     * filesDir or external app-private Android/data/...). Private backups
     * cannot be opened by the system file manager without root, so the UI
     * should route "open location" to the in-app file manager instead.
     */
    fun isPrivateBackup(entry: BackupEntry): Boolean = entry.privateStorage

    fun availableSpaceBytes(): Long = context.filesDir.usableSpace

    fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }
    }

    suspend fun cleanupOldAutoBackups(maxKeep: Int) = withContext(Dispatchers.IO) {
        val all = listBackups()
        // Only prune files named "AutoBackup_*" that are ALSO flagged in their
        // backup.json (source=auto). A manually renamed TavernBackup_* must not
        // be deleted just because it happens to match the prefix.
        val autoBackups = all.filter { (entry, _) ->
            entry.name.startsWith("AutoBackup_") && isAutoBackup(entry)
        }
        val effectiveMaxKeep = maxKeep.coerceAtLeast(1)
        if (autoBackups.size > effectiveMaxKeep) {
            autoBackups.drop(effectiveMaxKeep).forEach { (entry, _) ->
                if (!deleteBackup(entry)) {
                    Log.w(TAG, "Failed to delete old auto-backup: ${entry.name}")
                } else {
                    Log.i(TAG, "Deleted old auto-backup: ${entry.name}")
                }
            }
        }
    }

    /** True if the zip's backup.json marks it as an automatic backup (source=auto). */
    private fun isAutoBackup(entry: BackupEntry): Boolean {
        return try {
            ZipInputStream(openEntryInput(entry)).use { zis ->
                var e = zis.nextEntry
                var isAuto = false
                while (e != null) {
                    if (e.name == "backup.json") {
                        // Cap the read at 64 KB — a crafted zip could declare a huge
                        // backup.json (zip bomb) and OOM the worker otherwise.
                        val buf = ByteArray(64 * 1024)
                        val n = zis.read(buf)
                        val json = String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
                        isAuto = org.json.JSONObject(json).optString("source", "") == "auto"
                        break
                    }
                    e = zis.nextEntry
                }
                isAuto
            }
        } catch (_: Exception) {
            false
        }
    }
}
