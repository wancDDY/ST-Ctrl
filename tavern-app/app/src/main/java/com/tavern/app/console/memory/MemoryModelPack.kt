package com.tavern.app.console.memory

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The embedding model, installable from an archive as well as from the package.
 *
 * The bundled copy is unpacked on first use. This side exists for the two cases that need a file
 * the package cannot carry: a device where the assets are missing, and a model that has to be
 * replaced without shipping a new build.
 *
 * Installation goes through a staging directory and a rename. A download that dies half way — or
 * an archive that turns out to be an error page saved under a .zip name — leaves the previous
 * model in place, rather than a directory that looks installed and fails at inference.
 */
object MemoryModelPack {
    private const val TAG = "MemoryEmbed"
    private const val FOLDER = "memory-model"
    private const val MODEL = "model.onnx"
    private const val TOKENIZER = "tokenizer.json"
    private const val CONFIG = "config.json"

    /**
     * The archive is expected to hold the exported file under its export name; the loader has
     * always looked for model.onnx, so the rename happens here and in one place only.
     */
    private val ARCHIVE_NAMES = mapOf("model_quantized.onnx" to MODEL, "model.onnx" to MODEL)

    /** Below this the file is a truncated download or an error page, not a model. */
    private const val MIN_MODEL_BYTES = 8L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, FOLDER)

    /**
     * Whether the model is in the installation package.
     *
     * A bundled build unpacks it into [dir] on the first launch, which takes a moment; until that
     * finishes the files are not on disk yet. Asking this as well keeps the page from telling a
     * reader that they have to download something they already have, in the one window where the
     * answer would be technically true and practically wrong.
     */
    fun bundled(context: Context): Boolean =
        runCatching { context.assets.list(FOLDER)?.isNotEmpty() == true }.getOrDefault(false)

    /** Where a download is staged; the archive itself is not worth keeping after unpacking. */
    fun archiveFile(context: Context): File = File(context.cacheDir, "memory-model.zip")

    fun installed(context: Context): Boolean = isInstalled(dir(context)) || bundled(context)

    fun isInstalled(dir: File, minBytes: Long = MIN_MODEL_BYTES): Boolean {
        val model = File(dir, MODEL)
        if (!model.isFile || model.length() < minBytes) return false
        return File(dir, TOKENIZER).let { it.isFile && it.length() > 0 }
    }

    /** Bytes the unpacked model occupies, for the line that reports what it costs to keep. */
    fun sizeOnDisk(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Unpacks [archive] into [target]. Returns null on success, or a sentence to show the reader.
     *
     * Both required files are checked inside the staging directory before the live one is
     * touched, so an archive of the wrong shape cannot leave the app with no working model.
     */
    fun installInto(archive: File, target: File, minBytes: Long = MIN_MODEL_BYTES): String? {
        if (!archive.isFile) return "下载的文件不在了"
        val staging = File(target.parentFile, target.name + ".incoming")
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) return "写不进模型目录"
        try {
            var modelBytes = 0L
            var hasTokenizer = false
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        val mapped = ARCHIVE_NAMES[name] ?: name
                        if (mapped == MODEL || mapped == TOKENIZER || mapped == CONFIG) {
                            val out = File(staging, mapped)
                            out.outputStream().use { sink -> zip.copyTo(sink, 64 * 1024) }
                            if (mapped == MODEL) modelBytes = out.length()
                            if (mapped == TOKENIZER) hasTokenizer = out.length() > 0
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (modelBytes < minBytes || !hasTokenizer) {
                staging.deleteRecursively()
                return "压缩包里没有完整的模型文件"
            }
            // The old model is moved aside rather than deleted first: if the rename fails there is
            // still something to put back.
            val backup = File(target.parentFile, target.name + ".old")
            backup.deleteRecursively()
            if (target.exists() && !target.renameTo(backup)) {
                staging.deleteRecursively()
                return "旧的模型文件正在使用中"
            }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                staging.deleteRecursively()
                return "模型文件写入失败"
            }
            backup.deleteRecursively()
            Log.i(TAG, "model installed (${modelBytes / 1024} KB)")
            return null
        } catch (error: Throwable) {
            staging.deleteRecursively()
            Log.w(TAG, "install failed", error)
            return error.message ?: "解压失败"
        }
    }

    /** Removes leftovers from an interrupted attempt; called when a download starts. */
    fun clearStaging(context: Context) {
        val target = dir(context)
        File(target.parentFile, target.name + ".incoming").deleteRecursively()
        File(target.parentFile, target.name + ".old").deleteRecursively()
        archiveFile(context).delete()
    }
}
