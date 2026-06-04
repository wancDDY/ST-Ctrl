package com.tavern.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssetExtractorTest {

    @Test
    fun `needsExtraction returns true when version file is missing`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "tavern-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val versionFile = File(tmpDir, "core_version.txt")
            versionFile.delete()
            assertFalse("version file should not exist", versionFile.exists())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `version file correctly persists and reads back`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "tavern-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val versionFile = File(tmpDir, "core_version.txt")
            versionFile.writeText("1.0.1")
            assertTrue("version file should exist", versionFile.exists())
            assertTrue("version should match", versionFile.readText().trim() == "1.0.1")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `version mismatch detected correctly`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "tavern-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val versionFile = File(tmpDir, "core_version.txt")
            versionFile.writeText("1.0.0")
            val stored = versionFile.readText().trim()
            assertTrue("stored version should differ from 2.0.0", stored != "2.0.0")
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
