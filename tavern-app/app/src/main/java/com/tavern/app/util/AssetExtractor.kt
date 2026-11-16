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
    private const val EXTRACTING_FILE = "core_extracting.txt"
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
        val extractingFile = File(context.filesDir, EXTRACTING_FILE)
        val versionFileExists = versionFile.exists()
        val coreEntryPointExists = coreIsComplete(coreDir)
        val interrupted = extractingFile.exists()
        val shouldExtract = interrupted || shouldExtractCore(
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
                + "interrupted=$interrupted "
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

    internal fun coreIsComplete(coreDir: File): Boolean =
        File(coreDir, "server.js").exists() &&
            File(coreDir, "server-wrapper.cjs").exists() &&
            // The server imports webpack from src/middleware/webpack-serve.js while starting. An
            // extraction cut short after the entry files landed left a core that looked usable
            // and then died with ERR_MODULE_NOT_FOUND before opening the port, so a package this
            // deep is the sentinel for a complete unpack.
            File(coreDir, "node_modules/webpack/package.json").exists()

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
        val extractingFile = File(context.filesDir, EXTRACTING_FILE)
        // The revision records are written only after the loop finishes, so a run killed midway
        // otherwise leaves a core that the next start may trust: its entry files and version are
        // on disk, while packages further down are not. This marker outlives any such kill and
        // forces the unpack instead.
        extractingFile.writeText("1")

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
        context.assets.open(CORE_ZIP).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/')
                    val targetFile = File(coreDir, normalizedName)
                    when {
                        keepsExisting(coreDir, normalizedName) -> kept++
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
        // Every entry is written, including the ones whose size already matched: a file of the
        // same length can still hold different bytes (the patch swaps strings of equal length),
        // and skipping it left that content frozen at whatever the device first unpacked. The cost
        // is a full unpack per core revision, which is a rare event, not a per-launch one.
        Log.i(TAG, "Extraction complete: $count entries ($kept kept)")
        BootTrace.log(context, "unpacked $count entries")

        // A zip that lost a runtime package still unpacks without an error; the failure only
        // shows up when the server starts. Checking here turns it into an extraction error the
        // console can report, and the marker is left in place so the next start tries again.
        check(coreIsComplete(coreDir)) {
            "packaged core is incomplete after unpacking $count entries"
        }

        // Record what was unpacked last, so an interrupted run is repeated on the next start
        // instead of looking like a finished upgrade.
        val bundledVersion = readBundledVersion(context)
        File(context.filesDir, VERSION_FILE).writeText(bundledVersion)
        File(context.filesDir, REVISION_FILE).writeText(
            readBundledRevision(context).toString(),
        )
        extractingFile.delete()

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
     * A check that also requires the live data directory to be empty never holds: the archive
     * ships its own `data/` directory, so a failed run leaves those defaults in place, the answer
     * is always "nothing to recover", and the reader's only copy stays in the backup.
     * [FileUtils.moveDirSafely] clears the destination itself, so the extracted defaults are
     * replaced rather than merged with. Returns whether the leftover is no longer where it was.
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
