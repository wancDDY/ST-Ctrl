package com.tavern.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * An update moves the reader's data aside, swaps the core, and puts the data back. A kill between
 * those steps leaves the app looking wiped — no core, or a fresh core beside an empty data
 * directory — while the chats sit in a backup folder nothing points at. These pin what the next
 * start has to do about each of those landing spots.
 */
class CoreUpdateRecoveryTest {

    private fun root(): File = createTempDirectory("update-recovery-").toFile()

    private fun paths(dir: File) = UpdatePaths(
        coreDir = File(dir, "core"),
        coreBak = File(dir, "core-update-bak"),
        dataBak = File(dir, "data-update-bak"),
        extBak = File(dir, "ext-update-bak"),
        configBak = File(dir, "config-update-bak"),
        optsBak = File(dir, "tavern-opts-update-bak"),
    )

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun aKillBeforeTheSwapPutsTheOldCoreBack() {
        val dir = root()
        val p = paths(dir)
        // The core was moved away and the process died before the new one landed.
        write(File(p.coreBak, "server.js"), "old")
        write(File(p.dataBak, "default-user/chats/c.jsonl"), "聊天")

        val actions = recoverUpdate(p)

        assertTrue("restore-core" in actions)
        assertEquals("old", File(p.coreDir, "server.js").readText())
        assertEquals("聊天", File(p.coreDir, "data/default-user/chats/c.jsonl").readText())
        assertFalse(p.coreBak.exists())
        assertFalse(p.dataBak.exists())
    }

    @Test
    fun aKillAfterTheSwapKeepsTheNewCoreAndStillPutsTheDataBack() {
        val dir = root()
        val p = paths(dir)
        // The new core is in place; the data never made it back.
        write(File(p.coreDir, "server.js"), "new")
        write(File(p.coreDir, "data/placeholder"), "")
        write(File(p.coreBak, "server.js"), "old")
        write(File(p.dataBak, "default-user/chats/c.jsonl"), "聊天")

        val actions = recoverUpdate(p)

        assertEquals(listOf("drop-core-backup", "restore-data"), actions)
        assertEquals("the update is not undone", "new", File(p.coreDir, "server.js").readText())
        assertFalse(p.coreBak.exists())
        assertEquals("聊天", File(p.coreDir, "data/default-user/chats/c.jsonl").readText())
        assertFalse(
            "the empty data directory the new core shipped with is replaced, not merged into",
            File(p.coreDir, "data/placeholder").exists(),
        )
    }

    @Test
    fun anInterruptedRestoreIsFinishedNotRepeated() {
        val dir = root()
        val p = paths(dir)
        write(File(p.coreDir, "server.js"), "new")
        write(File(p.coreDir, "data/default-user/chats/c.jsonl"), "聊天")
        write(File(p.extBak, "one/index.js"), "ext")

        val actions = recoverUpdate(p)

        assertEquals(listOf("restore-extensions"), actions)
        assertEquals("ext", File(p.coreDir, "public/scripts/extensions/third-party/one/index.js").readText())
    }

    @Test
    fun aFinishedUpdateLeavesNothingToDo() {
        val dir = root()
        val p = paths(dir)
        write(File(p.coreDir, "server.js"), "new")
        assertTrue(recoverUpdate(p).isEmpty())
        assertEquals("new", File(p.coreDir, "server.js").readText())
    }
}
