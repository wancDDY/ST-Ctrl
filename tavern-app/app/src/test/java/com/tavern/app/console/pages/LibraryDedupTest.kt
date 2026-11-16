package com.tavern.app.console.pages

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library is keyed by display name: a same-named import joins the existing
 * folder as a new version, so no second folder is ever created under that name.
 * The dedup check reads the meta files directly, so it can be exercised on the
 * JVM without an Android context.
 */
class LibraryDedupTest {
    private fun tempLibrary(): File =
        File.createTempFile("library", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    private fun addCard(dir: File, id: String, name: String) {
        val cardDir = File(dir, id)
        cardDir.mkdirs()
        File(cardDir, "meta.json").writeText(
            JSONObject().put("name", name).put("format", "png").toString(2)
        )
    }

    @Test
    fun `a same-named card is a duplicate`() {
        val lib = tempLibrary()
        addCard(lib, "a", "阿甲")
        assertTrue(libraryHasName(lib, "阿甲"))
    }

    @Test
    fun `the duplicate check ignores case`() {
        val lib = tempLibrary()
        addCard(lib, "a", "Seraphina")
        assertTrue(libraryHasName(lib, "seraphina"))
        assertTrue(libraryHasName(lib, "SERAPHINA"))
    }

    @Test
    fun `an empty library has no duplicates`() {
        val lib = tempLibrary()
        assertFalse(libraryHasName(lib, "阿甲"))
    }

    @Test
    fun `a differently-named card is not a duplicate`() {
        val lib = tempLibrary()
        addCard(lib, "a", "阿甲")
        assertFalse(libraryHasName(lib, "阿乙"))
    }
}
