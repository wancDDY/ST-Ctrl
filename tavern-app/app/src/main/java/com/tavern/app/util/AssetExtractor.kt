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
        if (!versionFile.exists()) return true

        val bundledVersion = readBundledVersion(context)
        val extractedVersion = versionFile.readText().trim()
        return bundledVersion != extractedVersion
    }

    fun extractCore(context: Context): Result<File> = runCatching {
        val coreDir = File(context.filesDir, "core")
        // Preserve user-installed extensions before wiping the core directory
        val extDir = File(coreDir, "public/scripts/extensions/third-party")
        val extBackup = File(context.cacheDir, "ext-backup")
        try { extBackup.deleteRecursively() } catch (_: Exception) {}
        if (extDir.exists()) {
            val renamed = extDir.renameTo(extBackup)
            if (!renamed) {
                Log.w(TAG, "renameTo failed for extensions, using copyRecursively fallback")
                try {
                    extBackup.mkdirs()
                    extDir.copyRecursively(extBackup, overwrite = true)
                    extDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to backup extensions: ${e.message}", e)
                }
            }
        }
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

        // Restore user-installed extensions
        val newExtDir = File(coreDir, "public/scripts/extensions/third-party")
        if (extBackup.exists()) {
            try { newExtDir.deleteRecursively() } catch (_: Exception) {}
            newExtDir.parentFile?.mkdirs()
            val restored = extBackup.renameTo(newExtDir)
            if (!restored) {
                Log.w(TAG, "renameTo failed for restore, using copyRecursively fallback")
                try {
                    extBackup.copyRecursively(newExtDir, overwrite = true)
                    extBackup.deleteRecursively()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore extensions: ${e.message}", e)
                }
            }
            Log.i(TAG, "Restored user extensions")
        }

        val bundledVersion = readBundledVersion(context)
        File(context.filesDir, VERSION_FILE).writeText(bundledVersion)

        coreDir
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
