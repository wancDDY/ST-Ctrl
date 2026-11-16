package com.tavern.app.console

import com.tavern.app.console.pages.coreCardName
import com.tavern.app.console.pages.isCoreReadableCard
import com.tavern.app.console.pages.repairCardForCore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * The console lists cards by reading the directory, the tavern lists them by asking the core.
 * The two disagree on two things - a card in a subdirectory, and a card whose extension is not
 * the exact lowercase suffix - and that difference is what shows a card in the console that the
 * tavern will never open.
 */
class CoreCardVisibilityTest {
    private fun tempDirectory(): File =
        File.createTempFile("cards", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    // The defect only exists on a case-sensitive filesystem, which Android is and the JVM the
    // tests run on (Windows) is not: there `a.PNG` and `a.png` are one file, so there is no
    // second name to repair.
    private fun onCaseSensitiveFileSystem(dir: File): Boolean {
        File(dir, "probe.png").writeText("x")
        return !File(dir, "probe.PNG").exists()
    }

    @Test
    fun `a card at the top level with the lowercase suffix is listed by the core`() {
        val dir = tempDirectory()
        assertTrue(isCoreReadableCard(File(dir, "阿戊.png"), dir))
    }

    @Test
    fun `an uppercase suffix or another image format is invisible to the core`() {
        val dir = tempDirectory()
        assertFalse(isCoreReadableCard(File(dir, "阿戊.PNG"), dir))
        assertFalse(isCoreReadableCard(File(dir, "阿戊.Png"), dir))
        assertFalse(isCoreReadableCard(File(dir, "阿戊.jpg"), dir))
    }

    @Test
    fun `a card in a subdirectory is invisible to the core`() {
        val dir = tempDirectory()
        val nested = File(dir, "阿戊")
        nested.mkdirs()
        assertFalse(isCoreReadableCard(File(nested, "阿戊.png"), dir))
    }

    @Test
    fun `the repaired name keeps the stem and lowers only the suffix`() {
        assertEquals("阿戊.png", coreCardName("阿戊.PNG"))
        assertEquals("阿戊.png", coreCardName("阿戊.Png"))
        assertEquals("阿戊.png", coreCardName("阿戊.png"))
        assertEquals("阿戊.txt", coreCardName("阿戊.txt"))
    }

    @Test
    fun `repairing renames an uppercase card in place`() {
        val dir = tempDirectory()
        assumeFalse("case-insensitive filesystem", !onCaseSensitiveFileSystem(dir))
        val card = File(dir, "阿戊.PNG")
        card.writeText("x")

        val repaired = repairCardForCore(card, dir)

        assertEquals(File(dir, "阿戊.png").absolutePath, repaired?.absolutePath)
        assertTrue(File(dir, "阿戊.png").exists())
        assertFalse(card.exists())
    }

    @Test
    fun `repairing moves a nested card into the characters root`() {
        val dir = tempDirectory()
        val nested = File(dir, "阿戊").apply { mkdirs() }
        val card = File(nested, "阿戊.png")
        card.writeText("x")

        val repaired = repairCardForCore(card, dir)

        assertEquals(File(dir, "阿戊.png").absolutePath, repaired?.absolutePath)
        assertTrue(File(dir, "阿戊.png").exists())
        assertFalse(card.exists())
    }

    @Test
    fun `repairing refuses to overwrite a card that is already there`() {
        val dir = tempDirectory()
        val existing = File(dir, "阿戊.png")
        existing.writeText("real")
        val nested = File(dir, "阿戊").apply { mkdirs() }
        val card = File(nested, "阿戊.png")
        card.writeText("shadow")

        assertNull(repairCardForCore(card, dir))
        assertTrue(card.exists())
        assertEquals("real", existing.readText())
    }
}
