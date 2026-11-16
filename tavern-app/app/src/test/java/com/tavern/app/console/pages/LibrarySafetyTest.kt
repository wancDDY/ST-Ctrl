package com.tavern.app.console.pages

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two places where a wrong step loses something the reader cannot get back: the purge
 * that runs on every load, and the deploy that overwrites the copy the chats read.
 */
class LibrarySafetyTest {

    private fun tempDir(): File = File.createTempFile("safety", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    private fun folder(root: File, id: String, name: String) =
        File(File(root, "cards"), id).apply { mkdirs() }

    private fun version(
        cardDir: File,
        id: String,
        source: String,
        coreFileName: String? = null,
        body: String = "body",
    ) {
        val dir = File(File(cardDir, "versions"), id).apply { mkdirs() }
        File(dir, "card.png").writeText(body)
        File(dir, "meta.json").writeText(
            JSONObject().put("id", id).put("name", id).put("format", "png")
                .put("source", source)
                .apply { if (coreFileName != null) put("coreFileName", coreFileName) }
                .toString(2)
        )
    }

    @Test
    fun `a folder holding the reader's own import is never purged`() {
        val root = tempDir()
        val charsDir = tempDir()
        File(File(charsDir, "Seraphina"), "anger.png").apply {
            parentFile.mkdirs()
            writeText("a")
        }
        // Same name as a default card, but one version is the reader's import.
        val mixed = folder(root, "m", "anger")
        File(mixed, "meta.json").writeText(JSONObject().put("id", "m").put("name", "anger").toString(2))
        version(mixed, "v1", "核心迁移", "anger.png")
        version(mixed, "v2", "导入")

        assertEquals(0, purgeStDefaultCardsAt(root, charsDir))
        assertTrue(File(root, "cards/m").isDirectory)
    }

    @Test
    fun `a purged folder is moved aside rather than destroyed`() {
        val root = tempDir()
        val charsDir = tempDir()
        File(File(charsDir, "Seraphina"), "anger.png").apply {
            parentFile.mkdirs()
            writeText("a")
        }
        val pure = folder(root, "p", "anger")
        File(pure, "meta.json").writeText(JSONObject().put("id", "p").put("name", "anger").toString(2))
        version(pure, "v1", "核心迁移", "anger.png")

        assertEquals(1, purgeStDefaultCardsAt(root, charsDir))
        assertFalse(File(root, "cards/p").exists())
        val trash = File(root, ".trash").listFiles() ?: emptyArray()
        assertEquals(1, trash.size)
        assertTrue(File(trash[0], "versions/v1/card.png").isFile)
    }

    @Test
    fun `deploy leaves no temporary file behind`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entry = File(cardsDir, "a").apply { mkdirs() }
        File(entry, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        version(entry, "v1", "导入")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters").apply { mkdirs() }
        val card = LibraryCard(
            id = "a",
            name = "阿甲",
            meta = JSONObject(),
            versions = listOf(
                LibraryVersion(
                    id = "v1", name = "v1", format = "png",
                    file = File(entry, "versions/v1/card.png"),
                    meta = JSONObject().put("id", "v1").put("format", "png"),
                    imported = false, coreFileName = null, sizeBytes = 8,
                ),
            ),
            coreFileName = null,
            coverVersionId = null,
            sizeBytes = 8,
        )

        assertTrue(deployVersionAt(cardsDir, card, card.versions.single(), coreDir))
        assertEquals(1, charsDir.listFiles()?.size)
        assertTrue(charsDir.listFiles()!!.none { it.name.endsWith(".stctrl-tmp") })
    }

    @Test
    fun `a failed deploy keeps the copy the chats are reading`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entry = File(cardsDir, "a").apply { mkdirs() }
        File(entry, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        version(entry, "v1", "核心迁移", "阿甲.png", body = "old-body")
        version(entry, "v2", "导入", body = "new-body")
        // The new version's body is gone: the copy step cannot succeed.
        File(entry, "versions/v2/card.png").delete()

        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters").apply { mkdirs() }
        File(charsDir, "阿甲.png").writeText("old-body")

        val card = LibraryCard(
            id = "a",
            name = "阿甲",
            meta = JSONObject(),
            versions = listOf(
                LibraryVersion(
                    id = "v1", name = "v1", format = "png",
                    file = File(entry, "versions/v1/card.png"),
                    meta = JSONObject().put("id", "v1").put("format", "png")
                        .put("coreFileName", "阿甲.png").put("coreStatus", "installed"),
                    imported = true, coreFileName = "阿甲.png", sizeBytes = 8,
                ),
                LibraryVersion(
                    id = "v2", name = "v2", format = "png",
                    file = File(entry, "versions/v2/card.png"),
                    meta = JSONObject().put("id", "v2").put("format", "png"),
                    imported = false, coreFileName = null, sizeBytes = 8,
                ),
            ),
            coreFileName = null,
            coverVersionId = null,
            sizeBytes = 16,
        )

        assertFalse(deployVersionAt(cardsDir, card, card.versions[1], coreDir))
        assertEquals("old-body", File(charsDir, "阿甲.png").readText())
    }

    @Test
    fun aDeployedImportIsNotBroughtBackAsASecondFolder() {
        val root = tempDir()
        val cards = File(root, "cards").apply { mkdirs() }
        val chars = File(root, "characters").apply { mkdirs() }
        try {
            // Imported first, deployed afterwards: the folder names the core file it owns,
            // but the row did not come from a migration.
            val entry = File(cards, "a").apply { mkdirs() }
            File(entry, "meta.json").writeText(
                JSONObject().put("id", "a").put("name", "某张卡").toString(2)
            )
            version(entry, "v2", "导入", coreFileName = "某张卡.png")
            File(entry, "versions/v2/meta.json").writeText(
                JSONObject().put("id", "v2").put("name", "v2").put("format", "png")
                    .put("source", "导入").put("coreFileName", "某张卡.png")
                    .put("coreStatus", "installed").toString(2)
            )
            File(chars, "某张卡.png").writeBytes(byteArrayOf(1, 2, 3))

            assertEquals(0, migrateCoreCardsAt(cards, chars))
            assertEquals(1, loadLibraryAt(cards, chars).size)

            // A character that really only lives in the core is still picked up.
            File(chars, "另一张.png").writeBytes(byteArrayOf(4, 5, 6))
            assertEquals(1, migrateCoreCardsAt(cards, chars))
            val after = loadLibraryAt(cards, chars)
            assertEquals(2, after.size)
            assertTrue(after.any { card -> card.versions.any { it.coreFileName == "另一张.png" } })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deployingClearsTheStoredFlagEvenWhenTheOldCopyIsGone() {
        val root = tempDir()
        val cardsDir = File(root, "cards").apply { mkdirs() }
        val coreDir = File(root, "core").apply { mkdirs() }
        val chars = File(coreDir, "data/default-user/characters").apply { mkdirs() }
        try {
            val entry = File(cardsDir, "a").apply { mkdirs() }
            File(entry, "meta.json").writeText(
                JSONObject().put("id", "a").put("name", "某张卡").put("coreFile", "某张卡.png").toString(2)
            )
            // The old version still claims the core, but its copy was removed by hand.
            version(entry, "v2", "导入", coreFileName = "某张卡.png")
            File(entry, "versions/v2/meta.json").writeText(
                JSONObject().put("id", "v2").put("name", "v2").put("format", "png")
                    .put("coreFileName", "某张卡.png").put("coreStatus", "installed").toString(2)
            )
            version(entry, "v1", "导入")
            File(entry, "versions/v1/meta.json").writeText(
                JSONObject().put("id", "v1").put("name", "v1").put("format", "png")
                    .put("coreStatus", "uninstalled").toString(2)
            )

            val card = loadLibraryAt(cardsDir, chars).single()
            val target = card.versions.first { it.id == "v1" }
            assertTrue(deployVersionAt(cardsDir, card, target, coreDir))

            val after = loadLibraryAt(cardsDir, chars).single()
            assertEquals(
                listOf("v1"),
                after.versions.filter { it.meta.optString("coreStatus") == "installed" }.map { it.id },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
