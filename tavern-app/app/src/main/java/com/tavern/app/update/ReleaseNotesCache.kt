package com.tavern.app.update

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Keeps the last fetched release body of each version so the notes survive a restart
 * and stay readable offline.
 *
 * A body is only stored when it has text: a release that is still being edited, or an
 * API answer that arrives empty, must not wipe notes the reader already saw.
 */
object ReleaseNotesCache {
    private const val FILE_NAME = "release-notes.json"
    private const val KEY_TEXT = "text"
    private const val KEY_FETCHED_AT = "fetchedAt"
    private const val MAX_ENTRIES = 30

    @Synchronized
    fun get(context: Context, version: String): String? {
        val entry = read(context).optJSONObject(version) ?: return null
        return entry.optString(KEY_TEXT).takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun fetchedAt(context: Context, version: String): Long? {
        val entry = read(context).optJSONObject(version) ?: return null
        return entry.optLong(KEY_FETCHED_AT).takeIf { it > 0L }
    }

    @Synchronized
    fun remember(context: Context, releases: List<AppUpdateChecker.AppRelease>) {
        val withText = releases.filter { it.changelog.isNotBlank() }
        if (withText.isEmpty()) return
        val now = System.currentTimeMillis()
        val root = read(context)
        for (release in withText) {
            val entry = root.optJSONObject(release.version) ?: JSONObject()
            entry.put(KEY_TEXT, release.changelog)
            entry.put(KEY_FETCHED_AT, now)
            root.put(release.version, entry)
        }
        prune(root)
        write(context, root)
    }

    private fun prune(root: JSONObject) {
        if (root.length() <= MAX_ENTRIES) return
        val keys = root.keys().asSequence().toList()
        val drop = keys.size - MAX_ENTRIES
        keys.take(drop).forEach { root.remove(it) }
    }

    private fun read(context: Context): JSONObject {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun write(context: Context, root: JSONObject) {
        val target = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }
}
