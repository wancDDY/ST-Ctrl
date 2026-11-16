package com.tavern.app.console

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removal has to keep a way back, and it has to remove both copies an extension can have: a
 * reader who deleted the effective one left the legacy copy behind and saw the extension return.
 */
class ExtensionTrashTest {
    private fun core(): File =
        File.createTempFile("trash", "").let { file ->
            file.delete()
            file.mkdirs()
            File(file, "data/default-user/extensions").mkdirs()
            File(file, "public/scripts/extensions/third-party").mkdirs()
            file
        }

    private fun extension(parent: File, name: String): File =
        File(parent, name).apply {
            mkdirs()
            File(this, "index.js").writeText("// ${name}")
        }

    @Test
    fun `both copies move into the trash and are listed`() {
        val core = core()
        extension(File(core, "data/default-user/extensions"), "alpha")
        extension(File(core, "public/scripts/extensions/third-party"), "alpha")

        val moved = ExtensionTrash.moveToTrash(core, "alpha", now = 1000L)

        assertEquals(2, moved.size)
        assertFalse(File(core, "data/default-user/extensions/alpha").exists())
        assertFalse(File(core, "public/scripts/extensions/third-party/alpha").exists())
        assertEquals(2, ExtensionTrash.list(core).size)
    }

    @Test
    fun `restore puts the copy back where it came from`() {
        val core = core()
        val user = extension(File(core, "data/default-user/extensions"), "alpha")
        ExtensionTrash.moveToTrash(core, "alpha", now = 1000L)

        val entry = ExtensionTrash.list(core).single()
        assertTrue(ExtensionTrash.restore(core, entry).isSuccess)

        assertTrue(user.isDirectory)
        assertTrue(ExtensionTrash.list(core).isEmpty())
    }

    @Test
    fun `restore refuses to overwrite a directory that came back`() {
        val core = core()
        val parent = File(core, "data/default-user/extensions")
        extension(parent, "alpha")
        ExtensionTrash.moveToTrash(core, "alpha", now = 1000L)
        extension(parent, "alpha")

        val result = ExtensionTrash.restore(core, ExtensionTrash.list(core).single())

        assertTrue(result.isFailure)
        assertTrue(File(parent, "alpha").isDirectory)
        assertTrue(ExtensionTrash.list(core).isNotEmpty())
    }

    @Test
    fun `removal clears the name from the tavern disabled list`() {
        val core = core()
        extension(File(core, "data/default-user/extensions"), "alpha")
        val settings = File(core, "data/default-user/settings.json")
        settings.writeText(
            """{"extension_settings":{"disabledExtensions":["alpha","beta"]}}""",
        )

        ExtensionTrash.moveToTrash(core, "alpha", now = 1000L)

        val text = settings.readText()
        assertFalse(text.contains("\"alpha\""))
        assertTrue(text.contains("\"beta\""))
    }

    @Test
    fun `emptying the trash leaves no directories behind`() {
        val core = core()
        extension(File(core, "data/default-user/extensions"), "alpha")
        ExtensionTrash.moveToTrash(core, "alpha", now = 1000L)

        assertEquals(1, ExtensionTrash.clear(core))

        assertTrue(ExtensionTrash.list(core).isEmpty())
        assertFalse(File(core, "data/default-user/extensions-trash").exists())
    }

    @Test
    fun `a plugin the reader turned off can still be removed`() {
        val core = core()
        val disabled = File(core, "data/default-user/extensions/_disabled/alpha")
        disabled.mkdirs()
        File(disabled, "index.js").writeText("// alpha")

        val moved = ExtensionTrash.moveToTrash(core, "alpha", now = 1000L)

        assertEquals(1, moved.size)
        assertFalse(disabled.exists())
        assertTrue(ExtensionTrash.restore(core, moved.single()).isSuccess)
        assertTrue(disabled.isDirectory)
    }
}
