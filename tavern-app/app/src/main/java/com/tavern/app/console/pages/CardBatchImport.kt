package com.tavern.app.console.pages

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cards unpacked from an archive, written into [dest] as one file each.
 *
 * Only png and json entries are taken: an archive shared between users routinely
 * carries previews, readme files and macOS resource forks, and treating those as
 * cards would report failures for files nobody meant to import. Entries are
 * flattened because cards often sit in a subfolder, and two folders can hold
 * cards with the same file name.
 */
internal fun unpackCardArchive(zip: File, dest: File, maxEntries: Int = 400): List<File> {
    if (!dest.exists() && !dest.mkdirs()) return emptyList()
    val perFileLimit = 64L * 1024 * 1024
    val unpacked = mutableListOf<File>()
    runCatching {
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            var entry = stream.nextEntry
            var seen = 0
            while (entry != null && seen < maxEntries) {
                seen++
                val name = entry.name
                val leaf = name.substringAfterLast('/')
                val extension = leaf.substringAfterLast('.', "").lowercase()
                val isCard = !entry.isDirectory
                    && !name.startsWith("__MACOSX/")
                    && !leaf.startsWith(".")
                    && (extension == "png" || extension == "json")
                if (isCard) {
                    // Keep the name the archive shows: the result list has to name a
                    // file the reader can find, and "card-12.png" is nothing they ever saw.
                    val slot = File(dest, "entry-${unpacked.size}")
                    if (!slot.mkdirs()) { entry = stream.nextEntry; continue }
                    val target = File(slot, leaf.ifBlank { "card.$extension" })
                    var written = 0L
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (written <= perFileLimit) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            written += read
                        }
                    }
                    // An entry that runs past the limit is not a card; drop the stub
                    // rather than import half a file.
                    if (written > perFileLimit) target.delete() else unpacked.add(target)
                }
                entry = stream.nextEntry
            }
        }
    }
    return unpacked
}

/** What happened to one file of a batch, in the words the result dialog shows. */
internal enum class ImportLineKind { NewFolder, AddedVersion, Duplicate, Unreadable }

internal data class ImportLine(
    val fileName: String,
    val folder: String?,
    val versionLabel: String?,
    val kind: ImportLineKind,
    val entryId: String? = null,
)

internal data class BatchImportReport(val lines: List<ImportLine>) {
    fun count(kind: ImportLineKind): Int = lines.count { it.kind == kind }
    val total: Int get() = lines.size
}

/**
 * Imports many card files in one pass, with the same routing a single file gets:
 * the folder is chosen by the card's own name, then by content features, and a
 * version that already exists is skipped rather than copied twice.
 *
 * [onProgress] reports how many files are already handled: a folder of sixty cards
 * takes long enough that a silent screen reads as a hang.
 */
internal fun importCardsIntoLibrary(
    ctx: Context,
    files: List<File>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): BatchImportReport {
    val lines = ArrayList<ImportLine>(files.size)
    files.forEachIndexed { index, file ->
        onProgress(index, files.size)
        lines += when (val outcome = importCardIntoLibrary(ctx, file, null, "")) {
            is ImportOutcome.Ok -> ImportLine(
                fileName = file.name,
                folder = outcome.entryName,
                versionLabel = outcome.versionLabel.takeIf { it.isNotBlank() },
                kind = if (outcome.isNewEntry) ImportLineKind.NewFolder else ImportLineKind.AddedVersion,
                entryId = outcome.entryId,
            )
            is ImportOutcome.Duplicate -> ImportLine(
                fileName = file.name,
                folder = outcome.entryName,
                versionLabel = null,
                kind = ImportLineKind.Duplicate,
            )
            ImportOutcome.ParseFailed -> ImportLine(
                fileName = file.name,
                folder = null,
                versionLabel = null,
                kind = ImportLineKind.Unreadable,
            )
        }
    }
    onProgress(files.size, files.size)
    return BatchImportReport(lines)
}

/** The headline of a finished batch import: what landed, and what did not. */
internal fun batchImportMessage(report: BatchImportReport): String {
    if (report.total == 0) return "没有选中任何文件"
    val parts = mutableListOf<String>()
    val newFolders = report.count(ImportLineKind.NewFolder)
    val added = report.count(ImportLineKind.AddedVersion)
    val duplicates = report.count(ImportLineKind.Duplicate)
    val failed = report.count(ImportLineKind.Unreadable)
    if (newFolders > 0) parts.add("新建 $newFolders 个文件夹")
    if (added > 0) parts.add("加入 $added 个版本")
    if (duplicates > 0) parts.add("跳过 $duplicates 张重复")
    if (failed > 0) parts.add("$failed 张无法识别")
    return "共 ${report.total} 张：" + parts.joinToString("，")
}

private fun archiveEntryName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "card" }

/**
 * Writes every version of a folder into one archive, so a folder can move to
 * another device and come back through the batch import it was read by.
 *
 * Entries are named after the folder and the version label rather than the
 * internal ids: the cost is a duplicate name here and there, which the caller
 * resolves with a suffix, and the gain is an archive a person can open and read.
 */
internal fun exportCardsToZip(card: LibraryCard, out: File): Int {
    var written = 0
    val used = mutableSetOf<String>()
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        for (version in card.versions) {
            if (!version.file.isFile) continue
            val extension = if (version.format == "json") "json" else "png"
            val base = archiveEntryName(
                card.name + if (version.version.isBlank()) "" else " " + version.version
            )
            var entryName = "$base.$extension"
            var index = 2
            while (!used.add(entryName.lowercase())) {
                entryName = "$base ($index).$extension"
                index++
            }
            zip.putNextEntry(ZipEntry(entryName))
            version.file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
            written++
        }
    }
    return written
}
