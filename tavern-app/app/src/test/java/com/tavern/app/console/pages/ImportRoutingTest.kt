package com.tavern.app.console.pages

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import routing on plain files: whether a card counts as the same one, and where a
 * second copy of it lands. Both decide whether a batch of sixty cards leaves a shelf
 * of folders or a shelf of twins.
 */
class ImportRoutingTest {
    private fun tempDir(): File =
        File.createTempFile("routing", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    private fun cardFile(
        dir: File,
        fileName: String,
        name: String,
        version: String = "",
        description: String = "旧描述",
        avatar: String = "",
    ): File {
        val data = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("personality", "")
            .put("first_mes", "开场。")
            .put("character_version", version)
        val card = JSONObject().put("spec", "chara_card_v2").put("data", data)
        if (avatar.isNotBlank()) card.put("avatar", avatar)
        return File(dir, fileName).apply { writeText(card.toString(2)) }
    }

    private fun folders(cardsDir: File): List<File> =
        cardsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

    private fun versions(cardsDir: File): List<File> =
        folders(cardsDir).flatMap { dir ->
            File(dir, "versions").listFiles()?.filter { it.isDirectory } ?: emptyList()
        }

    @Test
    fun `the same card with a different picture is not a second version`() {
        val root = tempDir()
        val cardsDir = File(root, "cards").apply { mkdirs() }
        try {
            val first = cardFile(root, "a.png.json", "阿甲", "2.0")
            val copy = cardFile(root, "b.png.json", "阿甲", "2.0", avatar = "data:image/png;base64,AAAA")
            assertTrue(importCardIntoLibraryAt(cardsDir, first) is ImportOutcome.Ok)
            // Same payload, different file bytes: one card, exported twice with two covers.
            assertTrue(importCardIntoLibraryAt(cardsDir, copy) is ImportOutcome.Duplicate)
            assertEquals(1, folders(cardsDir).size)
            assertEquals(1, versions(cardsDir).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a real second build of the same version is kept beside the first`() {
        val root = tempDir()
        val cardsDir = File(root, "cards").apply { mkdirs() }
        try {
            val plain = cardFile(root, "a.png.json", "阿甲", "2.0", description = "纯文字版")
            val scripted = cardFile(root, "b.png.json", "阿甲", "2.0", description = "带前端的版本")
            assertTrue(importCardIntoLibraryAt(cardsDir, plain) is ImportOutcome.Ok)
            assertTrue(importCardIntoLibraryAt(cardsDir, scripted) is ImportOutcome.Ok)
            assertEquals(1, folders(cardsDir).size)
            assertEquals(2, versions(cardsDir).size)
            val labels = versions(cardsDir)
                .mapNotNull { JSONObject(File(it, "meta.json").readText()).optString("version") }
                .sorted()
            assertEquals(listOf("2.0", "2.0 (2)"), labels)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a cleanup lists the repeat rows and spares the deployed one`() {
        val root = tempDir()
        val cardsDir = File(root, "cards").apply { mkdirs() }
        try {
            // Built the way an older build could: same card, three rows, one deployed.
            val a = cardFile(root, "a.json", "阿丙", "1.0")
            assertTrue(importCardIntoLibraryAt(cardsDir, a) is ImportOutcome.Ok)
            val entry = folders(cardsDir).single()
            val copied = File(entry, "versions/copy").apply { mkdirs() }
            File(copied, "card.json").writeBytes(a.readBytes())
            File(copied, "meta.json").writeText(
                JSONObject().put("id", "copy").put("name", "阿丙").put("format", "json")
                    .put("coreStatus", "uninstalled").toString(2)
            )
            val deployed = File(entry, "versions/live").apply { mkdirs() }
            File(deployed, "card.json").writeBytes(a.readBytes())
            File(deployed, "meta.json").writeText(
                JSONObject().put("id", "live").put("name", "阿丙").put("format", "json")
                    .put("coreStatus", "installed").toString(2)
            )

            // Three rows of one card, one of them deployed: the cleanup keeps that one
            // and lists the other two, whatever order the directories sort in.
            val doomed = duplicateVersionIdsAt(cardsDir, entry.name)
            assertEquals(2, doomed.size)
            assertTrue(doomed.none { it == "live" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cards sharing a name share a folder`() {
        val root = tempDir()
        val cardsDir = File(root, "cards").apply { mkdirs() }
        try {
            val one = cardFile(root, "one.json", "阿乙", "1.0", description = "第一版")
            val two = cardFile(root, "two.json", "阿乙", "1.1", description = "第二版")
            assertTrue(importCardIntoLibraryAt(cardsDir, one) is ImportOutcome.Ok)
            assertTrue(importCardIntoLibraryAt(cardsDir, two) is ImportOutcome.Ok)
            assertEquals(1, folders(cardsDir).size)
            assertEquals(2, versions(cardsDir).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an archive keeps the file names it carries`() {
        val root = tempDir()
        val zip = File(root, "pack.zip")
        val first = cardFile(root, "阿甲.json", "阿甲")
        val second = cardFile(root, "阿乙.json", "阿乙")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((entryName, file) in listOf("第一版/阿甲.json" to first, "深层/阿乙.json" to second)) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(file.readBytes())
                out.closeEntry()
            }
        }
        val dest = File(root, "unpacked").apply { mkdirs() }
        try {
            val names = unpackCardArchive(zip, dest).map { it.name }.sorted()
            assertEquals(listOf("阿乙.json", "阿甲.json"), names)
        } finally {
            root.deleteRecursively()
        }
    }
}
