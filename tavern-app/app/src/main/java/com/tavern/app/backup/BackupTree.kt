package com.tavern.app.backup

import java.io.File

/**
 * Collects what a backup archive has to carry for one source tree.
 *
 * Two kinds of content are lost by a plain "files only" walk, and both exist in real
 * extension installs: a directory that holds no file at all (a zip of files cannot
 * recreate it) and a symbolic link to a file (`node_modules/.bin` is made of them,
 * and skipping them dropped the command entry points). Links are stored as their
 * target's content: a link that resolved on the source device can dangle on the
 * target one, and the content is what the extension needs.
 *
 * Directory links are followed, so a cycle is possible; every directory is therefore
 * visited once by canonical path, which also keeps two links to the same directory
 * from duplicating its whole subtree.
 */
internal object BackupTree {

    data class Item(
        val file: File,
        val entryName: String,
        /** Directory entries carry a trailing slash and no content. */
        val directory: Boolean,
    )

    /**
     * @param skip decides per child, before descending, so the file-level filters
     *   (runtime caches, secrets) drop a directory and everything under it in one
     *   step.
     */
    fun collect(root: File, prefix: String, skip: (File) -> Boolean = { false }): List<Item> {
        val items = mutableListOf<Item>()
        val visited = mutableSetOf<String>()

        // Zip entry names always use forward slashes, whatever the host uses.
        fun entryName(file: File): String {
            val relative = file.absolutePath.removePrefix(root.absolutePath)
            return prefix + "/" + relative.replace('\\', '/').trimStart('/')
        }

        /** Returns true when at least one entry was collected below [directory]. */
        fun walk(directory: File): Boolean {
            val key = try {
                directory.canonicalPath
            } catch (_: Exception) {
                directory.absolutePath
            }
            if (!visited.add(key)) return false

            val children = directory.listFiles() ?: return false
            var added = false
            for (child in children) {
                if (skip(child)) continue
                if (child.isDirectory) {
                    if (walk(child)) added = true
                } else if (child.exists()) {
                    // exists() also rejects a symlink whose target is gone.
                    items += Item(child, entryName(child), directory = false)
                    added = true
                }
            }
            // A directory that contributed nothing needs its own entry, otherwise the
            // archive holds no record that it existed. Parents are recreated by the
            // path of such a leaf.
            if (!added) {
                items += Item(directory, entryName(directory) + "/", directory = true)
                added = true
            }
            return added
        }

        walk(root)
        return items
    }
}
