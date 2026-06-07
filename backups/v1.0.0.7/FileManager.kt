package com.tavern.app.console

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val extension: String
)

class FileManager(context: Context) {
    val baseDir: File = AssetExtractor.getCoreDir(context)

    suspend fun listDirectory(dir: File): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                (dir.listFiles() ?: emptyArray())
                    .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                    .map {
                        FileItem(it, it.name, it.isDirectory,
                            if (it.isFile) it.length() else 0L,
                            it.lastModified(),
                            if (it.isFile) it.extension.lowercase() else "")
                    }
            }
        }

    suspend fun deleteItem(item: FileItem): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ok = if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
                if (!ok) throw IllegalStateException("删除失败")
            }
        }

    suspend fun renameItem(item: FileItem, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(item.file.parentFile, newName)
                if (target.exists()) throw IllegalStateException("目标已存在")
                if (!item.file.renameTo(target)) throw IllegalStateException("重命名失败")
                FileItem(target, target.name, target.isDirectory,
                    if (target.isFile) target.length() else 0L,
                    target.lastModified(),
                    if (target.isFile) target.extension.lowercase() else "")
            }
        }

    suspend fun createDirectory(parent: File, name: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching { File(parent, name).also { if (!it.mkdirs()) throw IllegalStateException("创建失败") } }
        }

    suspend fun readText(file: File): Result<String> =
        withContext(Dispatchers.IO) { runCatching { file.readText() } }

    suspend fun writeText(file: File, content: String): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching { file.writeText(content) } }

    suspend fun compressItems(items: List<FileItem>, outputFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                    items.forEach { item -> addToZip(zos, item.file, item.name, "") }
                }
            }
        }

    private fun addToZip(zos: ZipOutputStream, file: File, baseName: String, parentPath: String) {
        val entryPath = if (parentPath.isEmpty()) baseName else "$parentPath/$baseName"
        if (file.isDirectory) {
            zos.putNextEntry(ZipEntry(entryPath + "/"))
            zos.closeEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addToZip(zos, child, child.name, entryPath)
            }
        } else {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    suspend fun decompressItem(item: FileItem, outputDir: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ZipInputStream(item.file.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(outputDir, entry.name)
                        // Prevent zip-slip path traversal
                        if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath + File.separator) &&
                            outFile.canonicalPath != outputDir.canonicalPath) {
                            throw SecurityException("检测到路径穿越攻击: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }

    companion object {

        /**
         * 从 content URI 解析真实文件名。
         * 优先查 [OpenableColumns.DISPLAY_NAME]，失败则从 MIME 类型推断扩展名。
         */
        fun resolveFilename(ctx: Context, uri: Uri): String {
            // 1) Query DISPLAY_NAME
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2) Try lastPathSegment if it looks like a filename
            val lps = uri.lastPathSegment
            if (!lps.isNullOrBlank() && lps.contains('.') && lps.length <= 120) return lps

            // 3) Infer from MIME type
            val mime = ctx.contentResolver.getType(uri)
            if (mime != null) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (ext != null) return "imported_${System.currentTimeMillis()}.$ext"
            }

            return "imported_${System.currentTimeMillis()}"
        }
        fun isImage(ext: String) = ext in setOf("png","jpg","jpeg","gif","webp","bmp","svg","ico")
        fun isText(ext: String) = ext in setOf("txt","json","yaml","yml","js","css","html","htm","xml","md","csv","log","sh","py","cfg","conf","toml","ini","properties","gradle","kt","java","c","cpp","h","ts","jsx","tsx","scss","less")

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "—"
            val u = arrayOf("B","KB","MB","GB")
            var v = bytes.toDouble(); var i = 0
            while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
            return "%.1f %s".format(v, u[i])
        }

        fun formatDate(millis: Long) =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
