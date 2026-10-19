package com.tavern.app.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The walk decides what a backup archive can rebuild. Empty directories and linked
 * files are the two cases a files-only walk silently drops, so both are pinned here.
 */
class BackupTreeTest {

    private fun tempRoot(): File = Files.createTempDirectory("stctrl-backup-tree").toFile()

    @Test
    fun carriesEmptyDirectories() {
        val root = tempRoot()
        File(root, "keep").mkdirs()
        File(root, "keep/file.txt").writeText("x")
        File(root, "empty").mkdirs()
        File(root, "nested/leaf").mkdirs()

        val names = BackupTree.collect(root, "extensions").map { it.entryName }.sorted()

        assertEquals(
            listOf(
                "extensions/empty/",
                "extensions/keep/file.txt",
                "extensions/nested/leaf/",
            ),
            names,
        )
    }

    @Test
    fun keepsDirectoryEntryShape() {
        val root = tempRoot()
        File(root, "empty").mkdirs()

        val item = BackupTree.collect(root, "data").single()

        assertTrue(item.directory)
        assertEquals("data/empty/", item.entryName)
    }

    @Test
    fun carriesLinkedFiles() {
        val root = tempRoot()
        val target = File(root, "real.txt").apply { writeText("payload") }
        val link = File(root, "linked.txt")
        val created = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            // Creating links needs a privilege on Windows hosts; the device run
            // covers this case there.
            false
        }
        assumeTrue("symbolic links are not permitted on this host", created)

        val items = BackupTree.collect(root, "extensions")

        val linked = items.single { it.entryName == "extensions/linked.txt" }
        assertTrue(!linked.directory)
        assertEquals("payload", linked.file.readText())
    }

    @Test
    fun skipDropsTheWholeSubtree() {
        val root = tempRoot()
        File(root, "keep.txt").writeText("k")
        File(root, "gone").mkdirs()
        File(root, "gone/deep.txt").writeText("g")

        val names = BackupTree.collect(root, "extensions") { it.name == "gone" }
            .map { it.entryName }

        assertEquals(listOf("extensions/keep.txt"), names)
    }
}
