package com.tavern.app.console

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One removed extension directory. [storedName] is the folder inside the trash (a suffix is added
 * when the same extension had a second copy), [originalPath] is where it came from.
 */
data class TrashEntry(
    val name: String,
    val storedName: String,
    val originalPath: String,
    val trashedAt: Long,
    val sizeBytes: Long,
)

/**
 * Removing an extension moves every copy into a trash folder under the user data rather than
 * deleting it: a reader who removed the wrong one would otherwise have nothing to go back to,
 * and the copy in the other extension root would be left behind. The console can list the trash
 * and restore from it.
 */
object ExtensionTrash {
    private const val TRASH_DIR = "extensions-trash"
    private const val INDEX = "trash.json"

    fun moveToTrash(
        coreDir: File,
        name: String,
        userHandle: String = "default-user",
        now: Long = System.currentTimeMillis(),
    ): List<TrashEntry> {
        if (name.isBlank()) return emptyList()
        val stampDir = File(File(coreDir, "data/$userHandle"), "$TRASH_DIR/$now")
        val recorded = readIndex(stampDir).toMutableList()
        val moved = mutableListOf<TrashEntry>()

        for (source in candidates(coreDir, userHandle, name)) {
            if (!source.isDirectory) continue
            if (!stampDir.mkdirs() && !stampDir.isDirectory) continue
            val stored = freeName(stampDir, source.name)
            val destination = File(stampDir, stored)
            if (!source.renameTo(destination)) continue
            val entry = TrashEntry(
                name = name,
                storedName = stored,
                originalPath = source.absolutePath,
                trashedAt = now,
                sizeBytes = sizeOf(destination),
            )
            moved += entry
            recorded += entry
        }

        if (moved.isNotEmpty()) {
            writeIndex(stampDir, recorded)
            clearDisabledName(coreDir, name, userHandle)
        }
        return moved
    }

    fun list(coreDir: File, userHandle: String = "default-user"): List<TrashEntry> {
        val root = File(File(coreDir, "data/$userHandle"), TRASH_DIR)
        val stamps = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return stamps
            .flatMap { readIndex(it) }
            .sortedByDescending { it.trashedAt }
    }

    /** Puts a trashed extension back where it came from. Never overwrites. */
    fun restore(
        coreDir: File,
        entry: TrashEntry,
        userHandle: String = "default-user",
    ): Result<Unit> {
        val stampDir = File(File(coreDir, "data/$userHandle"), "$TRASH_DIR/${entry.trashedAt}")
        val source = File(stampDir, entry.storedName)
        if (!source.isDirectory) {
            return Result.failure(IllegalStateException("回收站里找不到这个扩展"))
        }

        val destination = File(entry.originalPath)
        if (destination.exists()) {
            return Result.failure(IllegalStateException("原位置已有同名目录，未覆盖"))
        }
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            return Result.failure(IllegalStateException("无法移回原位置"))
        }

        writeIndex(stampDir, readIndex(stampDir).filterNot { it.storedName == entry.storedName })
        prune(stampDir)
        clearDisabledName(coreDir, entry.name, userHandle)
        return Result.success(Unit)
    }

    /** Empties the trash for good: the reader decided the space back is worth the directory. */
    fun clear(coreDir: File, userHandle: String = "default-user"): Int {
        val root = File(File(coreDir, "data/$userHandle"), TRASH_DIR)
        val stamps = root.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        for (stamp in stamps) {
            removed += readIndex(stamp).size
            runCatching { stamp.deleteRecursively() }
        }
        if (root.listFiles().isNullOrEmpty()) root.delete()
        return removed
    }

    /**
     * The extension's own name may remain in the tavern's disabled list after the directory is
     * gone; leaving it there is what made a removed extension look like it was still installed.
     */
    fun clearDisabledName(coreDir: File, name: String, userHandle: String = "default-user") {
        val file = File(coreDir, "data/$userHandle/settings.json")
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            val settings = json.optJSONObject("extension_settings") ?: return@runCatching
            val disabled = settings.optJSONArray("disabledExtensions") ?: return@runCatching
            val kept = (0 until disabled.length())
                .map { disabled.optString(it) }
                .filter { it.isNotBlank() && it != name }
            if (kept.size == disabled.length()) return@runCatching
            settings.put("disabledExtensions", JSONArray(kept))
            file.writeText(json.toString())
        }
    }

    /**
     * Both roots, and the subdirectory the console parks disabled copies in: a plugin the reader
     * turned off is still a plugin they can remove, and it is the one case removal misses when
     * the disabled root is left out.
     */
    private fun candidates(coreDir: File, userHandle: String, name: String): List<File> =
        listOf(
            "data/$userHandle/extensions",
            "public/scripts/extensions/third-party",
        ).flatMap { root ->
            listOf(File(coreDir, "$root/$name"), File(coreDir, "$root/_disabled/$name"))
        }

    private fun freeName(stampDir: File, base: String): String {
        if (!File(stampDir, base).exists()) return base
        var index = 2
        while (File(stampDir, "$base-$index").exists() && index < 100) index += 1
        return "$base-$index"
    }

    private fun readIndex(stampDir: File): List<TrashEntry> {
        val file = File(stampDir, INDEX)
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                TrashEntry(
                    name = item.optString("name"),
                    storedName = item.optString("storedName"),
                    originalPath = item.optString("originalPath"),
                    trashedAt = item.optLong("trashedAt"),
                    sizeBytes = item.optLong("sizeBytes"),
                )
            }.filter { it.storedName.isNotBlank() && it.originalPath.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(stampDir: File, entries: List<TrashEntry>) {
        val file = File(stampDir, INDEX)
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().apply {
                put("name", entry.name)
                put("storedName", entry.storedName)
                put("originalPath", entry.originalPath)
                put("trashedAt", entry.trashedAt)
                put("sizeBytes", entry.sizeBytes)
            })
        }
        runCatching { file.writeText(array.toString()) }
    }

    private fun sizeOf(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** A stamp directory left holding nothing but its own index is litter that would list forever. */
    private fun prune(stampDir: File) {
        val remaining = stampDir.listFiles()?.filterNot { it.name == INDEX } ?: return
        if (remaining.isNotEmpty()) return
        runCatching { stampDir.deleteRecursively() }
        val root = stampDir.parentFile
        if (root != null && root.listFiles().isNullOrEmpty()) root.delete()
    }
}
