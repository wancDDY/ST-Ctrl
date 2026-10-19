package com.tavern.app.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetExtractorTest {

    @Test
    fun `missing extraction state requires extraction`() {
        assertTrue(AssetExtractor.shouldExtractCore(false, false, "1.19.0", "", null, 73, 0))
    }

    @Test
    fun `missing core entry points require extraction`() {
        assertTrue(AssetExtractor.shouldExtractCore(true, false, "1.19.0", "1.19.0", null, 73, 73))
    }

    @Test
    fun `bundled version upgrade requires extraction`() {
        assertTrue(AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.18.0", null, 73, 73))
    }

    @Test
    fun `extracted newer version is authoritative`() {
        assertFalse(AssetExtractor.shouldExtractCore(true, true, "1.18.0", "1.19.0", null, 73, 73))
    }

    @Test
    fun `manual update for current app version skips bundled extraction`() {
        assertFalse(AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.19.0", "2.0.0", 74, 73))
    }

    @Test
    fun `asset revision upgrade requires extraction`() {
        assertTrue(AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.19.0", null, 74, 73))
    }

    @Test
    fun `unchanged asset revision does not require extraction`() {
        assertFalse(AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.19.0", null, 73, 73))
    }

    @Test
    fun `a complete core without a revision record is trusted instead of unpacked again`() {
        // The record is written only after unpacking, so a missing one used to mean every
        // start repeated the whole unpack; the core on disk is what the reader is using.
        assertFalse(
            AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.19.0", null, 74, 0, false),
        )
    }

    @Test
    fun `a recorded revision still drives the next update`() {
        assertTrue(
            AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.19.0", null, 74, 73, true),
        )
        assertFalse(
            AssetExtractor.shouldExtractCore(true, true, "1.19.0", "1.19.0", null, 73, 73, true),
        )
    }

    @Test
    fun `data left behind by an interrupted extraction is put back`() {
        val root = Files.createTempDirectory("stctrl-extract").toFile()
        try {
            // What a failed run leaves: the reader's chats in the backup, and the data/ the
            // archive itself carries sitting in their place. That copy is not empty, which is
            // exactly what made the older emptiness test answer "nothing to recover".
            val dataDir = File(root, "core/data")
            val backup = File(root, "data-extract-bak")
            File(dataDir, "chats").mkdirs()
            File(dataDir, "settings.json").writeText("{}")
            File(backup, "chats").mkdirs()
            File(backup, "chats/北门.jsonl").writeText("[]")

            assertTrue(AssetExtractor.restoreLeftoverData(dataDir, backup))
            assertTrue(File(dataDir, "chats/北门.jsonl").exists())
            assertFalse(
                "the extracted defaults must not survive next to the recovered data",
                File(dataDir, "settings.json").exists(),
            )
            assertFalse("the backup is gone once it has been put back", backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a start without a leftover backup leaves the data in place alone`() {
        val root = Files.createTempDirectory("stctrl-extract").toFile()
        try {
            val dataDir = File(root, "core/data")
            val backup = File(root, "data-extract-bak")
            dataDir.mkdirs()
            File(dataDir, "settings.json").writeText("{}")

            assertFalse(AssetExtractor.restoreLeftoverData(dataDir, backup))
            assertTrue(File(dataDir, "settings.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `paths the reader owns are left alone once they exist`() {
        val root = Files.createTempDirectory("stctrl-extract").toFile()
        try {
            val coreDir = File(root, "core")
            coreDir.mkdirs()

            // Nothing is there yet, so a first install writes the archive's own copies.
            assertFalse(AssetExtractor.keepsExisting(coreDir, "data/default-user/settings.json"))
            assertFalse(AssetExtractor.keepsExisting(coreDir, "config.yaml"))
            assertFalse(
                AssetExtractor.keepsExisting(
                    coreDir,
                    "public/scripts/extensions/third-party/foo/index.js",
                ),
            )

            File(coreDir, "data").mkdirs()
            File(coreDir, "config.yaml").writeText("listen: true")
            File(coreDir, "public/scripts/extensions/third-party").mkdirs()

            assertTrue(AssetExtractor.keepsExisting(coreDir, "data/default-user/chats/beimen.jsonl"))
            assertTrue(AssetExtractor.keepsExisting(coreDir, "config.yaml"))
            assertTrue(
                AssetExtractor.keepsExisting(
                    coreDir,
                    "public/scripts/extensions/third-party/foo/index.js",
                ),
            )
            assertFalse(
                "core program files are always overwritten",
                AssetExtractor.keepsExisting(coreDir, "server.js"),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
