package com.tavern.app.util

import android.content.Context
import android.util.Log
import com.tavern.app.log.BootTrace
import com.tavern.app.update.VersionComparator
import com.tavern.app.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object AssetExtractor {

    private const val CORE_ZIP = "core/tavern-core.zip"
    private const val VERSION_FILE = "core_version.txt"
    private const val REVISION_FILE = "core_asset_revision.txt"
    private const val MANUAL_UPDATE_FILE = "core_manual_update.txt"
    private const val TAG = "AssetExtractor"

    fun needsExtraction(context: Context): Boolean {
        val versionFile = File(context.filesDir, VERSION_FILE)
        val coreDir = File(context.filesDir, "core")
        val bundledVersion = readBundledVersion(context)
        val manualUpdate = File(context.filesDir, MANUAL_UPDATE_FILE)
        val appVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                ?: ""
        } catch (_: Exception) {
            ""
        }
        val manualVersion = manualUpdate.takeIf { it.exists() }
            ?.readText()
            ?.trim()
            .orEmpty()
        if (manualVersion.isNotEmpty() && manualVersion != appVersion) {
            manualUpdate.delete()
        }
        val revisionFile = File(context.filesDir, REVISION_FILE)
        val versionFileExists = versionFile.exists()
        val coreEntryPointExists = File(coreDir, "server.js").exists()
            && File(coreDir, "server-wrapper.cjs").exists()
        val shouldExtract = shouldExtractCore(
            versionFileExists = versionFileExists,
            coreEntryPointExists = coreEntryPointExists,
            bundledVersion = bundledVersion,
            extractedVersion = if (versionFile.exists()) versionFile.readText().trim() else "",
            manualUpdateVersion = manualVersion.takeIf { it == appVersion },
            bundledRevision = readBundledRevision(context),
            extractedRevision = readRevisionFile(revisionFile),
            revisionFileExists = revisionFile.exists(),
        )
        BootTrace.log(
            context,
            "needsExtraction=$shouldExtract versionFile=$versionFileExists entryPoints=$coreEntryPointExists "
                + "bundled=$bundledVersion extracted=${if (versionFile.exists()) versionFile.readText().trim() else "-"} "
                + "manual=${manualVersion.takeIf { it == appVersion } ?: "-"} "
                + "bundledRev=${readBundledRevision(context)} extractedRev=${readRevisionFile(revisionFile)} "
                + "revisionFile=${revisionFile.exists()}",
        )
        if (!shouldExtract) {
            // A complete core with no revision record is not a reason to unpack 22000 entries
            // again: the record is written after extraction, so losing it (or never having it)
            // used to mean every single start repeated the whole unpack. Repair the record
            // instead, and keep the revision mechanism for the next real update.
            ensureRevisionRecord(context, revisionFile)
        }
        return shouldExtract
    }

    internal fun shouldExtractCore(
        versionFileExists: Boolean,
        coreEntryPointExists: Boolean,
        bundledVersion: String,
        extractedVersion: String,
        manualUpdateVersion: String?,
        bundledRevision: Int,
        extractedRevision: Int,
        revisionFileExists: Boolean = true,
    ): Boolean {
        if (!versionFileExists || !coreEntryPointExists) return true
        if (VersionComparator.isNewer(bundledVersion, extractedVersion)) return true
        if (manualUpdateVersion != null) return false
        if (VersionComparator.isNewer(extractedVersion, bundledVersion)) return false
        // Same core version, entry points in place, no record of what was unpacked: the
        // unpacked core is what the reader is using right now, so trust it.
        if (!revisionFileExists) return false
        return bundledRevision > extractedRevision
    }

    private fun ensureRevisionRecord(context: Context, revisionFile: File) {
        if (revisionFile.exists()) return
        try {
            revisionFile.writeText(readBundledRevision(context).toString())
            Log.i(TAG, "Repaired the missing core asset revision record")
        } catch (_: Exception) {
            // A record that cannot be written only costs another check next start.
        }
    }

    /**
     * Extracts the bundled core into filesDir.
     *
     * [onEntry] receives the running entry count. The archive is re-deflated
     * inside the APK, so its expanded length cannot be read up front; the count
     * is the only measure of progress that is true rather than estimated.
     */
    fun extractCore(
        context: Context,
        onEntry: ((Int) -> Unit)? = null,
        onPhase: ((String) -> Unit)? = null,
    ): Result<File> = runCatching {
        BootTrace.log(context, "extract started")
        val coreDir = File(context.filesDir, "core")

        // A leftover from a build that moved the reader's data aside instead of leaving it in
        // place. Nothing writes one any more, but an install that interrupted on that build still
        // has the chats sitting there.
        val dataDir = File(coreDir, "data")
        val dataBackup = File(context.filesDir, "data-extract-bak")
        if (restoreLeftoverData(dataDir, dataBackup)) {
            Log.w(TAG, "Recovered user data left behind by an interrupted extraction")
        } else if (dataBackup.exists()) {
            throw IllegalStateException(
                "leftover user data at ${dataBackup.absolutePath} could not be restored",
            )
        }
        try { dataBackup.deleteRecursively() } catch (_: Exception) {}

        // The core directory is not wiped. The paths the reader owns are skipped below and the
        // rest is overwritten in place, so an upgrade never has to carry their chats, settings
        // and extensions out of the way and back again — which is the step that could lose them.
        coreDir.mkdirs()
        onPhase?.invoke("正在更新核心代码…")

        Log.i(TAG, "Extracting core assets (streaming) to ${coreDir.absolutePath}")

        // Extract directly from assets — no temp file needed, saves 144 MB disk space
        var count = 0
        var kept = 0
        var reused = 0
        context.assets.open(CORE_ZIP).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/')
                    val targetFile = File(coreDir, normalizedName)
                    when {
                        keepsExisting(coreDir, normalizedName) -> kept++
                        // Matching length means the file already holds the archive's bytes.
                        // Comparing it is what stops a re-unpack from rewriting the ~22k files
                        // that a version bump never touches.
                        !entry.isDirectory && entry.size >= 0L &&
                            targetFile.length() == entry.size -> reused++
                        entry.isDirectory -> targetFile.mkdirs()
                        else -> {
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fos ->
                                zis.copyTo(fos, 65536)
                            }
                        }
                    }
                    zis.closeEntry()
                    count++
                    if (count % 1000 == 0) {
                        Log.i(TAG, "Extracted $count entries...")
                        // Report every 1000 entries, matching the log cadence. A finer
                        // interval pushes updates faster than the eye can read them.
                        onEntry?.invoke(count)
                    }
                    entry = zis.nextEntry
                }
            }
        }
        Log.i(TAG, "Extraction complete: $count entries ($kept kept, $reused unchanged)")
        BootTrace.log(context, "unpacked $count entries")

        // Record what was unpacked last, so an interrupted run is repeated on the next start
        // instead of looking like a finished upgrade.
        val bundledVersion = readBundledVersion(context)
        File(context.filesDir, VERSION_FILE).writeText(bundledVersion)
        File(context.filesDir, REVISION_FILE).writeText(
            readBundledRevision(context).toString(),
        )

        BootTrace.log(context, "extract finished")
        coreDir
    }.onFailure { error ->
        BootTrace.log(
            context,
            "extract failed: ${error::class.java.simpleName}: ${error.message}",
        )
    }



    /**
     * Paths that belong to the reader rather than to the core program. Once one exists it is left
     * alone for the rest of the install's life: the archive's copy is the factory default and is
     * only wanted on a first install.
     */
    private val READER_OWNED_PATHS = listOf(
        "data",
        "config.yaml",
        "public/scripts/extensions/third-party",
    )

    internal fun keepsExisting(coreDir: File, entryName: String): Boolean {
        val owned = READER_OWNED_PATHS.firstOrNull { path ->
            entryName == path || entryName.startsWith("$path/")
        } ?: return false
        return File(coreDir, owned).exists()
    }

    /**
     * An extraction that fails after taking the data out leaves it in [backupDir]. This runs
     * before the backup for the current run is taken, so a backup found here can only be a
     * leftover, and it can only hold the reader's real data.
     *
     * The check used to be "and the live data directory is empty", which never held: the archive
     * ships its own `data/` directory, so a failed run leaves those defaults in place and the
     * answer was always "nothing to recover". The unconditional cleanup that followed then
     * deleted the reader's only copy. [FileUtils.moveDirSafely] clears the destination itself,
     * so the extracted defaults are replaced rather than merged with. Returns whether the
     * leftover is no longer where it was.
     */
    internal fun restoreLeftoverData(dataDir: File, backupDir: File): Boolean {
        if (!backupDir.exists()) {
            return false
        }
        FileUtils.moveDirSafely(backupDir, dataDir)
        return !backupDir.exists()
    }

    fun getCoreDir(context: Context): File = File(context.filesDir, "core")
    private fun readBundledVersion(context: Context): String {
        return try {
            context.assets.open("core/version.txt")
                .bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    internal fun readBundledRevision(context: Context): Int {
        return try {
            context.assets.open("core/asset_revision.txt")
                .bufferedReader().use { it.readText().trim().toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            0
        }
    }

    private fun readRevisionFile(file: File): Int {
        return try {
            if (file.exists()) file.readText().trim().toIntOrNull() ?: 0 else 0
        } catch (e: Exception) {
            0
        }
    }
}
