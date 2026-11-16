package com.tavern.app.console.memory

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unpacking the model archive a reader downloaded.
 *
 * The cases that matter are the failures: a half-written zip, an archive for something else, an
 * HTML error page saved under a .zip name. All of them look like a successful download until the
 * loader tries to open the file, and by then the only symptom is "vectors stopped working" with
 * nothing in the log to say why.
 */
class MemoryModelPackTest {

    private fun tempDir(): File = File.createTempFile("model-pack", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    /** A zip with the given entries, sized so the model check has something to measure. */
    private fun zipOf(dir: File, entries: Map<String, Int>): File {
        val archive = File(dir, "model.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for ((name, size) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(size) { 7 })
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `the export name becomes the name the loader looks for`() {
        val root = tempDir()
        try {
            val archive = zipOf(root, mapOf(
                "memory-model/model_quantized.onnx" to 4096,
                "memory-model/tokenizer.json" to 128,
                "memory-model/config.json" to 32,
            ))
            val target = File(root, "installed")

            assertNull(MemoryModelPack.installInto(archive, target, minBytes = 1024))

            assertEquals(4096L, File(target, "model.onnx").length())
            assertEquals(128L, File(target, "tokenizer.json").length())
            assertEquals(32L, File(target, "config.json").length())
            assertTrue(MemoryModelPack.isInstalled(target, minBytes = 1024))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an archive without a model installs nothing`() {
        val root = tempDir()
        try {
            val archive = zipOf(root, mapOf("tokenizer.json" to 128))
            val target = File(root, "installed")

            val failure = MemoryModelPack.installInto(archive, target, minBytes = 1024)

            assertNotNull("a package missing the model must be refused", failure)
            assertFalse("nothing is left behind", target.exists())
            assertFalse(MemoryModelPack.isInstalled(target, minBytes = 1024))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a truncated model is refused`() {
        val root = tempDir()
        try {
            // The size check is what catches a download that ended early: the zip itself is
            // valid and contains both names, just not enough of the second one.
            val archive = zipOf(root, mapOf(
                "model_quantized.onnx" to 100,
                "tokenizer.json" to 128,
            ))
            val target = File(root, "installed")

            assertNotNull(MemoryModelPack.installInto(archive, target, minBytes = 1024))
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a bad download does not remove the model already installed`() {
        val root = tempDir()
        try {
            val target = File(root, "installed")
            target.mkdirs()
            File(target, "model.onnx").writeBytes(ByteArray(4096) { 1 })
            File(target, "tokenizer.json").writeText("{}")

            val archive = zipOf(root, mapOf("tokenizer.json" to 128))
            assertNotNull(MemoryModelPack.installInto(archive, target, minBytes = 1024))

            assertEquals("the working model is untouched", 4096L, File(target, "model.onnx").length())
            assertTrue(MemoryModelPack.isInstalled(target, minBytes = 1024))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a second download replaces the first`() {
        val root = tempDir()
        try {
            val target = File(root, "installed")
            target.mkdirs()
            File(target, "model.onnx").writeBytes(ByteArray(4096) { 1 })
            File(target, "tokenizer.json").writeText("{}")

            val archive = zipOf(root, mapOf(
                "model_quantized.onnx" to 8192,
                "tokenizer.json" to 64,
            ))
            assertNull(MemoryModelPack.installInto(archive, target, minBytes = 1024))

            assertEquals(8192L, File(target, "model.onnx").length())
            // The staging and backup directories must not survive: leaving them would double the
            // space the model takes and confuse a later size report.
            assertEquals(
                "only the model files remain",
                setOf("model.onnx", "tokenizer.json"),
                target.listFiles()!!.map { it.name }.toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an empty directory does not count as an installation`() {
        val root = tempDir()
        try {
            assertFalse(MemoryModelPack.isInstalled(root, minBytes = 1024))
        } finally {
            root.deleteRecursively()
        }
    }
}
