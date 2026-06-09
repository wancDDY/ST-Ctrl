package com.tavern.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object AssetExtractor {

    private const val CORE_ZIP = "core/tavern-core.zip"
    private const val VERSION_FILE = "core_version.txt"
    private const val TAG = "AssetExtractor"

    fun needsExtraction(context: Context): Boolean {
        val versionFile = File(context.filesDir, VERSION_FILE)
        // First install — core directory doesn't exist yet
        if (!versionFile.exists()) return true

        val bundledVersion = readBundledVersion(context)
        val extractedVersion = versionFile.readText().trim()
        // Only extract if the bundled version is STRICTLY NEWER.
        // This prevents wiping user data when they've updated ST core
        // to a version newer than what's bundled in the APK.
        return isNewer(bundledVersion, extractedVersion)
    }

    /** Returns true if v1 > v2, comparing numeric segments (e.g. "1.12.5" > "1.0.0"). */
    private fun isNewer(v1: String, v2: String): Boolean {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false // equal versions — no extraction needed
    }

    fun extractCore(context: Context): Result<File> = runCatching {
        val coreDir = File(context.filesDir, "core")

        // ── Backup user data BEFORE wiping core ──
        val dataDir = File(coreDir, "data")
        val dataBackup = File(context.cacheDir, "data-extract-bak")
        try { dataBackup.deleteRecursively() } catch (_: Exception) {}
        if (dataDir.exists()) {
            Log.i(TAG, "Backing up user data before extraction...")
            backupDir(dataDir, dataBackup)
        }

        // Preserve user-installed extensions
        val extDir = File(coreDir, "public/scripts/extensions/third-party")
        val extBackup = File(context.cacheDir, "ext-backup")
        try { extBackup.deleteRecursively() } catch (_: Exception) {}
        if (extDir.exists()) {
            backupDir(extDir, extBackup)
        }

        // ── Wipe and extract ──
        if (coreDir.exists()) {
            coreDir.deleteRecursively()
        }
        coreDir.mkdirs()

        Log.i(TAG, "Extracting core assets to ${coreDir.absolutePath}")

        // Step 1: Copy ZIP from assets to a temp file (much faster than streaming from assets)
        val tmpZip = File(context.cacheDir, "tavern-core-tmp.zip")
        Log.i(TAG, "Copying ZIP from assets (144 MB)...")
        context.assets.open(CORE_ZIP).use { input ->
            FileOutputStream(tmpZip).use { output ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                var totalCopied = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalCopied += bytesRead
                    if (totalCopied % (10 * 1024 * 1024) < 65536) {
                        Log.i(TAG, "Copied ${totalCopied / (1024 * 1024)} MB...")
                    }
                }
                Log.i(TAG, "ZIP copy complete: ${totalCopied / (1024 * 1024)} MB")
            }
        }

        // Step 2: Extract using ZipInputStream from temp file (fast sequential reads)
        Log.i(TAG, "Extracting files...")
        FileInputStream(tmpZip).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                var count = 0
                while (entry != null) {
                    // Normalize Windows backslash paths to forward slashes for Android/Linux
                    val normalizedName = entry.name.replace('\\', '/')
                    val targetFile = File(coreDir, normalizedName)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos, 65536)
                        }
                    }
                    zis.closeEntry()
                    count++
                    if (count % 1000 == 0) {
                        Log.i(TAG, "Extracted $count entries...")
                    }
                    entry = zis.nextEntry
                }
                Log.i(TAG, "Extraction complete: $count entries")
            }
        }

        // Clean up temp ZIP
        tmpZip.delete()

        // ── Restore user data ──
        if (dataBackup.exists()) {
            val newDataDir = File(coreDir, "data")
            // Remove the empty data/ from the extracted bundle
            try { newDataDir.deleteRecursively() } catch (_: Exception) {}
            restoreDir(dataBackup, newDataDir)
            Log.i(TAG, "Restored user data (chats, characters, settings, etc.)")
        }

        // Restore user-installed extensions
        val newExtDir = File(coreDir, "public/scripts/extensions/third-party")
        if (extBackup.exists()) {
            try { newExtDir.deleteRecursively() } catch (_: Exception) {}
            newExtDir.parentFile?.mkdirs()
            restoreDir(extBackup, newExtDir)
            Log.i(TAG, "Restored user extensions")
        }

        val bundledVersion = readBundledVersion(context)
        File(context.filesDir, VERSION_FILE).writeText(bundledVersion)

        coreDir
    }

    /** Safely backup a directory to a temp location. */
    private fun backupDir(src: File, dst: File) {
        try {
            val renamed = src.renameTo(dst)
            if (renamed) return
            // renameTo failed (e.g. cross-filesystem) — fallback to copy
            Log.w(TAG, "renameTo failed for ${src.name}, using copyRecursively fallback")
            dst.mkdirs()
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup ${src.name}: ${e.message}", e)
        }
    }

    /** Restore a directory from backup, with renameThenCopy fallback. */
    private fun restoreDir(src: File, dst: File) {
        try {
            dst.parentFile?.mkdirs()
            val moved = src.renameTo(dst)
            if (moved) return
            Log.w(TAG, "renameTo failed for restore, using copyRecursively fallback")
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore ${src.name}: ${e.message}", e)
        }
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
}
