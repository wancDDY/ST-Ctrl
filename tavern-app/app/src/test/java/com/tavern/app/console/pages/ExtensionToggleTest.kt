package com.tavern.app.console.pages

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same plugin can sit in both extension roots, and the core loads whichever copy is still in
 * place. Turning one copy off has to take the other copy down too; leaving it loaded is the
 * "disabled but still running" report that started the plugin work.
 */
class ExtensionToggleTest {
    private fun core(): File =
        File.createTempFile("toggle", "").let { file ->
            file.delete()
            file.mkdirs()
            File(file, "data/default-user/extensions").mkdirs()
            File(file, "public/scripts/extensions/third-party").mkdirs()
            file
        }

    private fun extension(parent: File, name: String): File =
        File(parent, name).apply {
            mkdirs()
            File(this, "manifest.json").writeText("""{"display_name":"$name"}""")
        }

    @Test
    fun `turning a plugin off moves every copy out of the roots`() {
        val core = core()
        extension(File(core, "data/default-user/extensions"), "dup")
        extension(File(core, "public/scripts/extensions/third-party"), "dup")

        assertTrue(toggleExtension(core, "dup", enable = false))

        assertFalse(File(core, "data/default-user/extensions/dup").exists())
        assertFalse(File(core, "public/scripts/extensions/third-party/dup").exists())
        assertTrue(File(core, "data/default-user/extensions/_disabled/dup").isDirectory)
        assertTrue(
            File(core, "public/scripts/extensions/third-party/_disabled/dup").isDirectory,
        )
    }

    @Test
    fun `turning it back on returns every copy`() {
        val core = core()
        extension(File(core, "data/default-user/extensions"), "dup")
        extension(File(core, "public/scripts/extensions/third-party"), "dup")
        toggleExtension(core, "dup", enable = false)

        assertTrue(toggleExtension(core, "dup", enable = true))

        assertTrue(File(core, "data/default-user/extensions/dup").isDirectory)
        assertTrue(File(core, "public/scripts/extensions/third-party/dup").isDirectory)
    }

    @Test
    fun `a plugin that is not there is not reported as moved`() {
        val core = core()

        assertFalse(toggleExtension(core, "missing", enable = false))
    }
}
