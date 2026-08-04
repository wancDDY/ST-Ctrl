package com.tavern.app.util

import android.content.Context
import android.util.Log
import com.tavern.app.util.FileUtils
import java.io.File
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
        val dataBackup = File(context.filesDir, "data-extract-bak")
        try { dataBackup.deleteRecursively() } catch (_: Exception) {}
        if (dataDir.exists()) {
            Log.i(TAG, "Backing up user data before extraction...")
            FileUtils.moveDirSafely(dataDir, dataBackup)
        }

        // Preserve user-installed extensions
        val extDir = File(coreDir, "public/scripts/extensions/third-party")
        val extBackup = File(context.filesDir, "ext-backup")
        try { extBackup.deleteRecursively() } catch (_: Exception) {}
        if (extDir.exists()) {
            FileUtils.moveDirSafely(extDir, extBackup)
        }

        // Preserve user's config.yaml (whitelist, listen settings, etc.)
        val configFile = File(coreDir, "config.yaml")
        val configBackup = File(context.filesDir, "config-extract-bak")
        try { configBackup.delete() } catch (_: Exception) {}
        if (configFile.exists()) {
            Log.i(TAG, "Backing up config.yaml before extraction...")
            configFile.renameTo(configBackup)
        }

        // ── Wipe and extract ──
        if (coreDir.exists()) {
            coreDir.deleteRecursively()
        }
        coreDir.mkdirs()

        Log.i(TAG, "Extracting core assets (streaming) to ${coreDir.absolutePath}")

        // Extract directly from assets — no temp file needed, saves 144 MB disk space
        var count = 0
        context.assets.open(CORE_ZIP).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
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
            }
        }
        Log.i(TAG, "Extraction complete: $count entries")

        // ── Restore user data ──
        if (dataBackup.exists()) {
            val newDataDir = File(coreDir, "data")
            // Remove the empty data/ from the extracted bundle
            try { newDataDir.deleteRecursively() } catch (_: Exception) {}
            FileUtils.moveDirSafely(dataBackup, newDataDir)
            Log.i(TAG, "Restored user data (chats, characters, settings, etc.)")
        }

        // Restore user-installed extensions
        val newExtDir = File(coreDir, "public/scripts/extensions/third-party")
        if (extBackup.exists()) {
            try { newExtDir.deleteRecursively() } catch (_: Exception) {}
            newExtDir.parentFile?.mkdirs()
            FileUtils.moveDirSafely(extBackup, newExtDir)
            Log.i(TAG, "Restored user extensions")
        }

        // Restore user's config.yaml
        if (configBackup.exists()) {
            val newConfig = File(coreDir, "config.yaml")
            try { newConfig.delete() } catch (_: Exception) {}
            configBackup.renameTo(newConfig)
            Log.i(TAG, "Restored user config.yaml")
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
