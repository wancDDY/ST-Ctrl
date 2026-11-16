package com.tavern.app.console.pages

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch import runs on files and plain counts, so both halves are tested here:
 * what an archive yields, and what the summary says about it.
 */
class CardBatchImportTest {
    private fun tempDir(): File =
        File.createTempFile("batch", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    private fun zipOf(entries: Map<String, ByteArray>): File {
        val zip = File.createTempFile("cards", ".zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `only card entries come out of an archive`() {
        val zip = zipOf(
            mapOf(
                "阿甲.png" to "png-a".toByteArray(),
                "nested/阿乙.json" to "{}".toByteArray(),
                "readme.txt" to "读我".toByteArray(),
                "__MACOSX/._阿甲.png" to "fork".toByteArray(),
                "._阿丙.png" to "fork".toByteArray(),
            )
        )
        val dest = tempDir()
        try {
            val cards = unpackCardArchive(zip, dest)
            assertEquals(2, cards.size)
            assertEquals(setOf("png", "json"), cards.map { it.extension }.toSet())
            assertTrue(cards.all { it.isFile && it.length() > 0 })
        } finally {
            zip.delete()
            dest.deleteRecursively()
        }
    }

    @Test
    fun `cards with the same file name do not overwrite each other`() {
        val zip = zipOf(
            mapOf(
                "第一版/阿甲.png" to "one".toByteArray(),
                "第二版/阿甲.png" to "two".toByteArray(),
            )
        )
        val dest = tempDir()
        try {
            val cards = unpackCardArchive(zip, dest)
            assertEquals(2, cards.size)
            assertEquals(setOf("one", "two"), cards.map { it.readText() }.toSet())
        } finally {
            zip.delete()
            dest.deleteRecursively()
        }
    }

    @Test
    fun `a file that is not an archive yields nothing instead of throwing`() {
        val notAZip = File.createTempFile("broken", ".zip").apply { writeText("not a zip") }
        val dest = tempDir()
        try {
            assertTrue(unpackCardArchive(notAZip, dest).isEmpty())
        } finally {
            notAZip.delete()
            dest.deleteRecursively()
        }
    }

    @Test
    fun `sizes are counted from the files and said in plain units`() {
        val dir = tempDir()
        File(dir, "a.png").writeBytes(ByteArray(2048))
        File(dir, "meta.json").writeText("{}")
        val nested = File(dir, "sub").apply { mkdirs() }
        File(nested, "b.png").writeBytes(ByteArray(1024))
        try {
            assertEquals(2048L + 2L + 1024L, directorySize(dir))
            assertEquals("512 B", formatCardSize(512))
            assertEquals("2 KB", formatCardSize(1536))
            assertEquals("1.5 MB", formatCardSize((1.5 * 1024 * 1024).toLong()))
            assertEquals("0 KB", formatCardSize(0))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a folder exports into an archive the importer can read back`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val dir = File(cardsDir, "a").apply { mkdirs() }
        File(dir, "meta.json").writeText("{}")
        val versions = listOf("v1" to "png", "v2" to "json").map { (id, format) ->
            val vDir = File(File(dir, "versions"), id).apply { mkdirs() }
            val body = File(vDir, if (format == "png") "card.png" else "card.json")
            body.writeText("body-$id")
            LibraryVersion(
                id = id,
                name = "阿甲-$id",
                format = format,
                file = body,
                meta = org.json.JSONObject(),
                imported = false,
                coreFileName = null,
            )
        }
        val card = LibraryCard("a", "阿甲", org.json.JSONObject(), versions)
        val out = File(root, "阿甲.zip")
        val dest = tempDir()
        try {
            assertEquals(2, exportCardsToZip(card, out))
            // Round trip: what the export writes is what the batch import accepts.
            assertEquals(2, unpackCardArchive(out, dest).size)
        } finally {
            root.deleteRecursively()
            dest.deleteRecursively()
        }
    }

    @Test
    fun `the summary names what landed and what did not`() {
        fun line(kind: ImportLineKind, name: String) = ImportLine(name, name, null, kind)
        val report = BatchImportReport(
            listOf(
                line(ImportLineKind.NewFolder, "阿甲"),
                line(ImportLineKind.NewFolder, "阿乙"),
                line(ImportLineKind.AddedVersion, "阿甲"),
                line(ImportLineKind.AddedVersion, "阿甲"),
                line(ImportLineKind.AddedVersion, "阿丙"),
                line(ImportLineKind.Duplicate, "旧卡"),
            )
        )
        assertEquals("共 6 张：新建 2 个文件夹，加入 3 个版本，跳过 1 张重复", batchImportMessage(report))
        assertEquals("没有选中任何文件", batchImportMessage(BatchImportReport(emptyList())))
    }

    @Test
    fun `two bodies of one card at one version get separate labels`() {
        // Same card, same version, different file: one with the scripted front end,
        // one plain. Both belong in the folder, and the rows must not read alike.
        assertEquals("2.0", uniqueVersionLabel("2.0", emptyList()))
        assertEquals("2.0", uniqueVersionLabel("2.0", listOf("1.0")))
        assertEquals("2.0 (2)", uniqueVersionLabel("2.0", listOf("2.0")))
        assertEquals("2.0 (3)", uniqueVersionLabel("2.0", listOf("2.0", "2.0 (2)")))
    }
}
