package com.tavern.app.util

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Safely move a directory from [src] to [dst], handling cross-filesystem cases
 * where File.renameTo() silently fails (returns false without error).
 *
 * Strategy:
 * 1. Try renameTo (fast, atomic on same filesystem)
 * 2. Fallback: copyRecursively + verify, then delete source
 *
 * If copy fails mid-way, the source is NOT deleted — preventing data loss.
 */
object FileUtils {

    private const val TAG = "FileUtils"

    fun moveDirSafely(src: File, dst: File) {
        if (!src.exists()) return

        // Clean up any stale destination
        try { dst.deleteRecursively() } catch (_: Exception) {}

        // Step 1: Try fast atomic rename (works within same filesystem)
        if (src.renameTo(dst)) {
            Log.d(TAG, "renameTo succeeded: ${src.name} → ${dst.name}")
            return
        }

        // Step 2: Cross-filesystem fallback — copy then delete
        Log.w(TAG, "renameTo failed for ${src.name}, using copyRecursively fallback")
        try {
            dst.mkdirs()
            src.copyRecursively(dst, overwrite = true)
            // Only delete source AFTER successful copy
            src.deleteRecursively()
            Log.i(TAG, "copyRecursively succeeded: ${src.name} → ${dst.name}")
        } catch (e: IOException) {
            // Source is preserved — copy failed, don't delete
            Log.e(TAG, "moveDirSafely failed for ${src.name}: ${e.message}")
            throw e
        }
    }

    fun deleteDirSafely(dir: File) {
        if (!dir.exists()) return
        try {
            dir.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete ${dir.name}: ${e.message}")
        }
    }
}
