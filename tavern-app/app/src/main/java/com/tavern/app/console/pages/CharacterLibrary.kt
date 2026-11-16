package com.tavern.app.console.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject
import com.tavern.app.util.AssetExtractor

/**
 * Character card library. One directory per character card under the app-private
 * character-library directory: the directory is the card folder, its versions/
 * holds every imported card body for that character. A version is either deployed
 * into the core (a copy exists in the core characters directory under a name of its
 * own) or kept here only; deploying a second version adds a character rather than
 * replacing the first, because the core files chats under the png name. The core directory is never the
 * source of truth, this library is, and the agent reads from it.
 */
internal const val CHARACTER_LIBRARY_DIR = "character-library"
internal const val CORE_MIGRATED_SOURCE = "核心迁移"
internal const val IMPORTED_SOURCE = "导入"

/** One card body inside a card folder; the deployable unit. */
internal data class LibraryVersion(
    val id: String,
    val name: String,        // display name, defaults to the source file name
    val format: String,      // "png" | "json"
    val file: File,          // versions/<id>/card.png | card.json
    val meta: JSONObject,
    val imported: Boolean,   // this version is the one deployed to the core
    val coreFileName: String?, // core copy name, when deployed
    val sizeBytes: Long = 0L, // what this version occupies on disk
) {
    val source: String get() = meta.optString("source", "")
    val importedAt: Long get() = meta.optLong("importedAt", 0L)
    // Written on every deploy, so "which version was deployed last" survives a reload; a version
    // deployed before this field existed reads 0 and sorts last.
    val deployedAt: Long get() = meta.optLong("deployedAt", 0L)
    // Free-form version label the user types (v1.1, 2.0.0…); blank when they
    // never set one, and the row just leaves the subtitle empty.
    val version: String get() = meta.optString("version", "")
}

/** One character card folder; the renameable unit holding several versions. */
internal data class LibraryCard(
    val id: String,
    val name: String,        // display name, renameable like a folder
    val meta: JSONObject,
    val versions: List<LibraryVersion>,
    /** The name this folder deploys under in the core; chosen once, then never re-derived. */
    val coreFileName: String? = null,
    /** The version pinned as the folder icon, if the reader picked one. */
    val coverVersionId: String? = null,
    /** Every version of this folder added up; what deleting the folder would free. */
    val sizeBytes: Long = 0L,
) {
    /**
     * Versions the core currently holds.
     *
     * More than one is normal: deploying a second version adds a character instead of replacing
     * the first, because the core names a chat directory after the png. Replacing the file would
     * hand the new version every chat the old one already had.
     */
    val deployedVersions: List<LibraryVersion> get() = versions.filter { it.imported }

    /** The one deployed last; what the folder row and the delete dialog talk about. */
    val deployedVersion: LibraryVersion?
        get() = deployedVersions.maxByOrNull { it.deployedAt } ?: deployedVersions.firstOrNull()

    val coreStatus: String
        get() = if (deployedVersions.isNotEmpty()) "installed" else "uninstalled"

    /** The most recently imported version; the folder avatar shows its body. */
    val latestVersion: LibraryVersion?
        get() = versions.maxWithOrNull(compareBy<LibraryVersion> { it.importedAt }.thenBy { it.id })

    /**
     * The card art the folder shows. A pinned version wins; a pin whose version no
     * longer exists is ignored rather than leaving the folder without a face.
     */
    val coverVersion: LibraryVersion?
        get() = versions.firstOrNull { it.id == coverVersionId } ?: latestVersion
}

internal fun libraryRoot(ctx: Context): File = File(ctx.filesDir, CHARACTER_LIBRARY_DIR)

private fun cardsDir(ctx: Context): File = File(libraryRoot(ctx), "cards")

internal fun cardDir(ctx: Context, id: String): File = File(cardsDir(ctx), id)

private fun versionsDir(ctx: Context, cardId: String): File = File(cardDir(ctx, cardId), "versions")

private fun versionDir(ctx: Context, cardId: String, versionId: String): File =
    File(versionsDir(ctx, cardId), versionId)

/** Persists a meta file (entry or version) into its directory. */
internal fun writeCardMeta(dir: File, meta: JSONObject): Boolean =
    runCatching { File(dir, "meta.json").writeText(meta.toString(2)); true }.getOrDefault(false)

/**
 * true when the library already holds a card folder with this display name.
 * The library is keyed by name: a same-named card joins the existing folder as
 * a new version instead of starting a twin folder.
 */
internal fun libraryHasName(cardsDir: File, name: String): Boolean =
    cardsDir.listFiles()?.any { dir ->
        if (!dir.isDirectory) return@any false
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            meta.optString("name", "").equals(name, ignoreCase = true)
        }.getOrDefault(false)
    } ?: false

/**
 * Name folding used by import routing: brackets, whitespace and a trailing
 * version marker are dropped so differently titled copies of one card share
 * the same folded key.
 */
internal fun normalizeCardName(name: String): String =
    name.replace(Regex("""[「」《》【】\[\]（）()【】]"""), "")
        .replace(Regex("""\s+"""), "")
        .replace(Regex("""(?:v|ver\.?|版本)?\d+(?:\.\d+)*\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .lowercase()

/**
 * A stable fingerprint of a card's content, used when the folded names do not
 * match: the same character re-uploaded under a different title (a reworked
 * copy, a translated name) shares the creator and the description.
 */
internal fun cardFeatureOf(json: JSONObject): String {
    val data = json.optJSONObject("data") ?: return ""
    val creator = normalizeCardName(data.optString("creator", ""))
    val description = data.optString("description", "").trim().lowercase()
    // A card with neither folds to "::" — the same string for every such card, which would
    // make each of them join whichever folder the scan reached first. No feature, no match.
    if (creator.isBlank() && description.isBlank()) return ""
    return "$creator::$description"
}

/**
 * What an import can fill in on its own: the card's name and the version label.
 *
 * A card carries both — the folder name comes from `data.name`, the version from
 * `data.character_version` — and asking the reader to retype them is asking them
 * to copy what the file already says. The file name is the last fallback for the
 * older cards that carry neither.
 */
internal data class ImportDefaults(val name: String, val version: String)

internal fun importDefaultsOf(json: JSONObject, fileName: String): ImportDefaults {
    val data = json.optJSONObject("data")
    fun first(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    val name = first(
        data?.optString("name"),
        json.optString("name"),
        json.optString("char_name"),
        fileName.substringBeforeLast('.'),
    )
    val version = first(
        data?.optString("character_version"),
        json.optString("character_version"),
        json.optString("char_version"),
    )
    return ImportDefaults(name, version)
}

/** The folder whose display name folds to [name]; what an edited name is asking for. */
internal fun findEntryByNameAt(cardsDir: File, name: String): String? {
    val key = normalizeCardName(name)
    if (key.isBlank()) return null
    return cardsDir.listFiles()?.firstOrNull { dir ->
        dir.isDirectory && runCatching {
            normalizeCardName(JSONObject(File(dir, "meta.json").readText()).optString("name", "")) == key
        }.getOrDefault(false)
    }?.name
}

/**
 * Finds the folder an imported card belongs to. Exact on the folded name first;
 * when no folder shares the name, falls back to content features so a card that
 * is clearly the same character joins its folder. Returns the entry id and how
 * it was matched, or null to create a new folder.
 */
internal fun findMatchingEntryAt(cardsDir: File, json: JSONObject): Pair<String, String>? {
    // A v2 card keeps its name under data; reading only the top level made every v2 import
    // fall through to the fingerprint, which is blank for most cards.
    val nameKey = normalizeCardName(importDefaultsOf(json, "").name)
    val feature = cardFeatureOf(json)
    if (nameKey.isBlank() && feature.isBlank()) return null
    var featureHit: Pair<String, String>? = null
    cardsDir.listFiles()?.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        val entryName = runCatching {
            JSONObject(File(dir, "meta.json").readText()).optString("name", "")
        }.getOrDefault("")
        if (nameKey.isNotBlank() && normalizeCardName(entryName) == nameKey) {
            return dir.name to "name"
        }
        // First content match wins; a later version of the same card shares the
        // fingerprint, so this is a routing aid, not a uniqueness claim.
        if (featureHit == null && feature.isNotBlank()) {
            val versionFeatureHit = File(dir, "versions").listFiles()?.any { vDir ->
                if (!vDir.isDirectory) return@any false
                runCatching {
                    val vMeta = JSONObject(File(vDir, "meta.json").readText())
                    val format = vMeta.optString("format", "png")
                    cardFeatureOf(cardJsonOf(File(vDir, cardBodyName(format))) ?: return@any false) == feature
                }.getOrDefault(false)
            } ?: false
            if (versionFeatureHit) featureHit = dir.name to "feature"
        }
    }
    return featureHit
}

/** Card body name inside a version directory; fixed so files never collide. */
internal fun cardBodyName(format: String): String =
    if (format == "json") "card.json" else "card.png"

/** Card JSON regardless of the on-disk format (png embeds it, json is plain). */
internal fun cardJsonOf(file: File): JSONObject? =
    if (file.extension.equals("json", ignoreCase = true)) {
        runCatching { JSONObject(file.readText()) }.getOrNull()
    } else {
        parseCharCard(file)
    }

private fun safeCoreName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().takeIf { it.isNotBlank() } ?: "character"

/**
 * The file name the core lists a card under. The core only lists png files in
 * its characters directory, so a json card is deployed as png after being
 * transcoded, never copied as json.
 */
internal fun coreFileNameFor(format: String, name: String): String {
    val base = safeCoreName(name)
    return coreCardName("$base.png")
}

/**
 * The name one version deploys under.
 *
 * A chat is filed in a directory named after the card's png, and the core reads that directory as
 * the character's identity. So the name is per version, not per folder: a version that already
 * owns a file keeps it — through renames and re-deploys — and a version that has none gets a new
 * one. Deploying 1.6 over 1.3 would otherwise hand 1.6 every chat 1.3 already has.
 */
internal fun coreFileNameForVersion(
    charsDir: File,
    cardsDir: File,
    card: LibraryCard,
    version: LibraryVersion,
): String {
    // Names a sibling still answers on. A version whose copy was deleted by hand does not hold its
    // name — that is how the file gets cleaned up — so only files that exist count here.
    val reserved = card.versions
        .filter { it.id != version.id }
        .mapNotNull { it.coreFileName?.takeIf { name -> File(charsDir, name).isFile } }
        .map { it.lowercase() }
        .toSet()
    version.coreFileName?.takeIf { name ->
        name.isNotBlank() &&
            !reserved.contains(name.lowercase()) &&
            !namesAnotherVersion(name, card, version)
    }?.let { return it }
    // A folder deployed by an older build kept the name at the folder level. The version that was
    // deployed there adopts it: answering on a second name would orphan the chats it already has.
    card.coreFileName?.takeIf { name ->
        version.imported && !reserved.contains(name.lowercase())
    }?.let { return it }
    return freeCoreName(
        charsDir,
        cardsDir,
        card.id,
        coreFileNameFor(version.format, deployNameOf(card, version)),
        reserved,
    )
}

/**
 * Display name plus the version label, unless the folder name already carries it.
 *
 * The label goes into the file name so the core's character list and the chat directories stay
 * tellable apart when two versions of one character are deployed side by side.
 */
internal fun deployNameOf(card: LibraryCard, version: LibraryVersion): String {
    val label = version.version.trim()
    if (label.isEmpty()) return card.name
    if (card.name.contains(label, ignoreCase = true)) return card.name
    // A folder named after an older version — one holding both 1.3 and 1.6 — would otherwise
    // produce "name 1.3 1.6": a name that still reads as 1.3 and lands right next to it.
    // Replacing the folder's label keeps one file per version obviously so.
    val older = card.versions
        .filter { it.id != version.id }
        .map { it.version.trim() }
        .filter { it.isNotEmpty() && card.name.contains(it, ignoreCase = true) }
        .maxByOrNull { it.length }
    if (older != null) return card.name.replaceFirst(older, label, ignoreCase = true)
    return card.name + " " + label
}

/**
 * Whether a recorded file name carries a sibling's version label and not this version's.
 *
 * That is what a name written by the build that named files after the folder looks like: 1.6 was
 * filed under the folder's own label before per-version names existed, so deploying it again
 * would put it back on 1.3's file — and take 1.3's chats with it.
 */
internal fun namesAnotherVersion(name: String, card: LibraryCard, version: LibraryVersion): Boolean {
    val mine = version.version.trim()
    if (mine.isEmpty()) return false
    val lower = name.lowercase()
    if (lower.contains(mine.lowercase())) return false
    return card.versions.any { other ->
        other.id != version.id && other.version.trim().isNotEmpty() &&
            lower.contains(other.version.trim().lowercase())
    }
}

private fun rememberCoreFileName(cardsDir: File, card: LibraryCard, name: String) {
    // Read the file rather than the in-memory copy: a rename may have touched the
    // same meta since the folder was loaded, and writing the stale object back
    // would undo it.
    val dir = File(cardsDir, card.id)
    val meta = metaOf(File(dir, "meta.json")) ?: card.meta
    meta.put("coreFile", name)
    writeCardMeta(dir, meta)
    card.meta.put("coreFile", name)
}

/**
 * [base], or the first free variant of it. A name is taken when a file of that
 * name sits in the core, or when another folder claims it: after a rename the old
 * folder keeps its old file name, so a card imported later under that same
 * display name must not deploy on top of it and inherit its chats.
 */
internal fun freeCoreName(
    charsDir: File,
    cardsDir: File,
    cardId: String,
    base: String,
    reserved: Set<String> = emptySet(),
): String {
    val claimed = claimedCoreNames(cardsDir, cardId)
    fun taken(name: String): Boolean =
        reserved.contains(name.lowercase()) || claimed.contains(name.lowercase()) || File(charsDir, name).exists()
    if (!taken(base)) return base
    val stem = base.removeSuffix(".png")
    for (index in 2..99) {
        val candidate = "$stem ($index).png"
        if (!taken(candidate)) return candidate
    }
    return base
}

private fun claimedCoreNames(cardsDir: File, exceptId: String): Set<String> {
    val names = mutableSetOf<String>()
    cardsDir.listFiles()?.forEach { dir ->
        if (!dir.isDirectory || dir.name == exceptId) return@forEach
        metaOf(File(dir, "meta.json"))?.optString("coreFile", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { names.add(it.lowercase()) }
        File(dir, "versions").listFiles()?.forEach { vDir ->
            metaOf(File(vDir, "meta.json"))?.optString("coreFileName", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { names.add(it.lowercase()) }
        }
    }
    return names
}

private fun metaOf(file: File): JSONObject? =
    if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null

/**
 * Migrates the flat card layout (card body + meta directly inside the card
 * folder) into the versioned layout (card body inside versions/<id>/). Idempotent:
 * a folder that already has versions/ is left alone.
 */
internal fun migrateLegacyLayout(ctx: Context): Int = migrateLegacyLayoutAt(libraryRoot(ctx))

internal fun migrateLegacyLayoutAt(root: File): Int {
    val cards = File(root, "cards")
    if (!cards.exists()) return 0
    var migrated = 0
    cards.listFiles()?.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        if (File(dir, "versions").exists()) return@forEach
        var body = File(dir, "card.png")
        if (!body.isFile) body = File(dir, "card.json")
        if (!body.isFile) return@forEach
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return@forEach
        val oldMeta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@forEach
        val versionId = oldMeta.optString("id", dir.name)
        val vDir = File(File(dir, "versions"), versionId)
        if (!vDir.mkdirs()) return@forEach
        if (!body.renameTo(File(vDir, body.name))) {
            if (!body.copyTo(File(vDir, body.name), overwrite = true).isFile) return@forEach
            body.delete()
        }
        // The old meta becomes the version meta; the entry meta only keeps the
        // folder-level facts (renameable name, creation time).
        if (!writeCardMeta(vDir, oldMeta)) return@forEach
        val entryMeta = JSONObject()
            .put("id", oldMeta.optString("id", dir.name))
            .put("name", oldMeta.optString("name", dir.name))
            .put("createdAt", oldMeta.optLong("importedAt", 0L))
        if (!writeCardMeta(dir, entryMeta)) return@forEach
        migrated++
    }
    return migrated
}

/**
 * Scans the core characters directory and copies every card the library does
 * not know yet as a fresh folder with one deployed version. Only top-level,
 * core-readable png cards are mirrored: subdirectories and misnamed files are
 * exactly what the tavern never lists, so they would only clutter the library.
 * The core's own default set is skipped as well — it ships with the core and
 * no user ever imported it. Migration is idempotent
 * on the core file name, so renamed core cards become new folders while
 * untouched ones are skipped.
 */
internal fun migrateCoreCards(ctx: Context, coreDir: File): Int =
    migrateCoreCardsAt(cardsDir(ctx), File(coreDir, "data/default-user/characters"))

/**
 * Pulls characters that only exist in the core into the library.
 *
 * A folder that already records this core file owns it, whatever the version's source: a card
 * imported and then deployed is the same card. Matching only rows that came from a migration
 * meant every deploy answered the next reload with a second folder of the same name.
 */
internal fun migrateCoreCardsAt(cardsDir: File, charsDir: File): Int {
    if (!charsDir.exists()) return 0
    val defaults = stDefaultCardNames(charsDir)
    val library = loadLibraryAt(cardsDir, charsDir)
    val known = (
        library.mapNotNull { it.coreFileName } +
            library.flatMap { it.versions }.mapNotNull { it.coreFileName }
        )
        .map { it.lowercase() }
        .toSet()
    var added = 0
    charsDir.listFiles()?.forEach { file ->
        if (!file.isFile) return@forEach
        if (!isCoreReadableCard(file, charsDir)) return@forEach
        if (file.name.removeSuffix(".png").lowercase() in defaults) return@forEach
        val name = cardJsonOf(file)?.optString("name", "")
            ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        if (file.name.lowercase() in known) return@forEach
        val id = UUID.randomUUID().toString()
        val dir = File(cardsDir, id)
        if (!dir.mkdirs()) return@forEach
        val vDir = File(File(dir, "versions"), id)
        if (!vDir.mkdirs()) return@forEach
        val body = File(vDir, cardBodyName("png"))
        if (!file.copyTo(body, overwrite = true).isFile) return@forEach
        val entryMeta = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("createdAt", System.currentTimeMillis())
        if (!writeCardMeta(dir, entryMeta)) return@forEach
        val vMeta = JSONObject()
            .put("id", id)
            .put("name", file.nameWithoutExtension)
            .put("format", "png")
            .put("source", CORE_MIGRATED_SOURCE)
            .put("coreFileName", file.name)
            .put("coreStatus", "installed")
            .put("importedAt", System.currentTimeMillis())
        if (!writeCardMeta(vDir, vMeta)) return@forEach
        added++
    }
    return added
}

/**
 * The core ships a default character set that no user imported. Its primary
 * card name is a constant of the upstream product, so it stays in code; the
 * rest of the set is read from the core folder rather than hard-coded, so it
 * follows the bundled core.
 */
internal fun stDefaultCardNames(charsDir: File): Set<String> {
    val names = mutableSetOf("seraphina", "default_seraphina")
    File(charsDir, "Seraphina").listFiles()?.forEach { file ->
        if (file.isFile) names.add(file.name.removeSuffix(".png").lowercase())
    }
    return names
}

/**
 * Drops library folders that mirror the tavern's default character set. Only
 * entries that were migrated from the core are touched; a user import of a
 * same-named card is never removed. Idempotent; returns the number of folders
 * removed. Used once to clean up folders mirrored before migration learned to
 * skip the default set.
 */
internal fun purgeStDefaultCardsAt(root: File, charsDir: File): Int {
    if (!charsDir.exists()) return 0
    val defaults = stDefaultCardNames(charsDir)
    val cards = File(root, "cards")
    if (!cards.exists()) return 0
    var removed = 0
    cards.listFiles()?.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
            ?: return@forEach
        val versions = File(dir, "versions").listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { vDir ->
                runCatching { JSONObject(File(vDir, "meta.json").readText()) }.getOrNull()
            }
            ?: emptyList()
        // Every version has to come from the core migration. A folder that also holds a
        // reader's own import is that reader's card, whatever it happens to be called.
        if (versions.isEmpty()) return@forEach
        if (versions.any { it.optString("source") != CORE_MIGRATED_SOURCE }) return@forEach
        val coreNames = versions.mapNotNull { it.optString("coreFileName").takeIf { n -> n.isNotBlank() } }
        val isDefault = meta.optString("name", dir.name).lowercase() in defaults ||
            coreNames.any { it.lowercase().removeSuffix(".png") in defaults }
        if (isDefault && quarantine(dir, root)) removed++
    }
    return removed
}

/**
 * Moves a folder out of the shelf instead of deleting it.
 *
 * This runs on every load, and a name is not proof that a folder holds nothing the
 * reader made: a wrong judgement would destroy the folder outright, with no copy
 * anywhere and nothing said. The moved folder stays on disk under .trash, cheap to
 * inspect and cheap to restore by hand.
 */
private fun quarantine(dir: File, root: File): Boolean {
    val trash = File(root, ".trash")
    if (!trash.exists() && !trash.mkdirs()) return false
    val target = File(trash, dir.name + "-" + System.currentTimeMillis())
    return runCatching { dir.renameTo(target) }.getOrDefault(false)
}

internal fun purgeStDefaultCards(ctx: Context, coreDir: File): Int =
    purgeStDefaultCardsAt(libraryRoot(ctx), File(coreDir, "data/default-user/characters"))

/**
 * What a folder occupies, counted from file lengths rather than by reading the
 * files: the shelf asks this for every version on every load, and the answer only
 * needs to be right to the kilobyte.
 */
internal fun directorySize(dir: File): Long {
    var total = 0L
    dir.listFiles()?.forEach { file ->
        total += if (file.isDirectory) directorySize(file) else file.length()
    }
    return total
}

/** Byte counts as a reader would say them; one decimal is enough to compare. */
internal fun formatCardSize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${(bytes + 512L) / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Enumerates the library and resolves each version's core state at load time. */
internal fun loadLibrary(ctx: Context, coreDir: File): List<LibraryCard> =
    loadLibraryAt(cardsDir(ctx), File(coreDir, "data/default-user/characters"))

/** The same read, on plain directories, so the migration rules can be exercised off-device. */
internal fun loadLibraryAt(cards: File, charsDir: File): List<LibraryCard> {
    if (!cards.exists()) return emptyList()
    return cards.listFiles()?.sortedBy { it.name }?.mapNotNull { dir ->
        if (!dir.isDirectory) return@mapNotNull null
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return@mapNotNull null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@mapNotNull null
        val versionsDir = File(dir, "versions")
        val versions = versionsDir.listFiles()
            ?.sortedBy { it.name }
            ?.mapNotNull { vDir ->
                if (!vDir.isDirectory) return@mapNotNull null
                val vMetaFile = File(vDir, "meta.json")
                if (!vMetaFile.isFile) return@mapNotNull null
                val vMeta = runCatching { JSONObject(vMetaFile.readText()) }.getOrNull()
                    ?: return@mapNotNull null
                val format = vMeta.optString("format", "png")
                val body = File(vDir, cardBodyName(format))
                if (!body.isFile) return@mapNotNull null
                val coreName = vMeta.optString("coreFileName", "")
                val coreFile = if (coreName.isNotBlank()) File(charsDir, coreName) else null
                val installed = vMeta.optString("coreStatus") == "installed" && coreFile?.isFile == true
                LibraryVersion(
                    id = vMeta.optString("id", vDir.name),
                    name = vMeta.optString("name", body.nameWithoutExtension),
                    format = format,
                    file = body,
                    meta = vMeta,
                    imported = installed,
                    coreFileName = coreName.takeIf { it.isNotBlank() },
                    sizeBytes = directorySize(vDir)
                )
            } ?: emptyList()
        if (versions.isEmpty()) return@mapNotNull null
        LibraryCard(
            id = meta.optString("id", dir.name),
            name = meta.optString("name", dir.name),
            meta = meta,
            versions = versions,
            coreFileName = meta.optString("coreFile", "").takeIf { it.isNotBlank() },
            coverVersionId = meta.optString("coverVersionId", "").takeIf { it.isNotBlank() },
            sizeBytes = versions.sumOf { it.sizeBytes }
        )
    } ?: emptyList()
}

/**
 * The label a new version gets when the card carries none: one past the highest
 * number already in use.
 *
 * Labels are free text, so one folder can hold "v1.2", a hand-written name and "3" at once.
 * Only the leading number of each is read, and a label without one is skipped
 * rather than counted — a hand-written name must not push the counter, or the
 * fourth import would be called v2 because three rows exist.
 */
internal fun nextVersionLabel(labels: List<String>): String {
    val numbered = labels.mapNotNull { label ->
        Regex("""(\d+)""").find(label.trim())?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull()
    return "v" + ((numbered ?: labels.size) + 1)
}

/**
 * [label], or the first free variant of it.
 *
 * Two files can be the same card at the same version and still differ — one ships
 * the scripted front end, one is plain text — and both belong in the folder. Sharing
 * a label would make the two rows indistinguishable, so the second takes a suffix.
 */
internal fun uniqueVersionLabel(label: String, existing: List<String>): String {
    val taken = existing.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    if (label.lowercase() !in taken) return label
    var index = 2
    while ("${label} ($index)".lowercase() in taken) index++
    return "$label ($index)"
}

/** The version labels a folder already carries, newest state read straight from disk. */
internal fun versionLabelsAt(cardsDir: File, entryId: String): List<String> =
    File(File(cardsDir, entryId), "versions").listFiles()
        ?.mapNotNull { metaOf(File(it, "meta.json"))?.optString("version") }
        ?: emptyList()

/**
 * Result of an import; a matching card joins the existing folder as a version. */
internal sealed class ImportOutcome {
    data class Ok(
        val entryId: String,
        val entryName: String,
        val versionId: String,
        val versionName: String,
        val versionLabel: String,
        val isNewEntry: Boolean,
        /** "name" | "feature" when joined an existing folder, null on a new folder. */
        val matchedBy: String?,
    ) : ImportOutcome()
    /** The source is byte-identical to a version already in the folder. */
    data class Duplicate(
        val entryId: String,
        val entryName: String,
        val versionName: String,
    ) : ImportOutcome()
    object ParseFailed : ImportOutcome()
}

internal fun sha256Of(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun sha256Of(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * True when any version body already in the folder is byte-identical to [src].
 * The same card imported twice must not grow duplicate versions in the folder.
 */
/**
 * True when the folder already holds this card, whatever its picture looks like.
 *
 * A card that circulates twice usually differs only in the art or in how the png was
 * encoded; comparing file bytes let those land as a second version whose row read
 * identically to the first. Only the payload decides — a genuinely different build of
 * the same card (scripted front end versus plain text) has a different payload and is
 * still kept as its own version.
 */
internal fun hasSameCardAt(cardsDir: File, entryId: String, json: JSONObject): Boolean {
    val versionsDir = File(File(cardsDir, entryId), "versions")
    if (!versionsDir.isDirectory) return false
    val wanted = payloadDigest(json)
    versionsDir.listFiles()?.forEach { vDir ->
        if (!vDir.isDirectory) return@forEach
        val vMeta = runCatching { JSONObject(File(vDir, "meta.json").readText()) }.getOrNull()
            ?: return@forEach
        val body = File(vDir, cardBodyName(vMeta.optString("format", "png")))
        if (!body.isFile) return@forEach
        val stored = cardJsonOf(body) ?: return@forEach
        if (payloadDigest(stored) == wanted) return true
    }
    return false
}

/** A stable fingerprint of a card's content, with key order and formatting removed. */
internal fun payloadDigest(json: JSONObject): String {
    val payload = json.optJSONObject("data") ?: json
    return sha256Of(canonicalJson(payload).toByteArray())
}

private fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().sorted()
        .joinToString(",", "{", "}") { key -> "\"" + key + "\":" + canonicalJson(value.opt(key)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> canonicalJson(value.opt(index)) }
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Which folder a card belongs in, given the name the reader confirmed.
 *
 * An untouched name keeps the fingerprint fallback, so a re-titled copy of the
 * same character still joins its folder. An edited name is taken literally: the
 * reader has said which folder it belongs to, and a content match must not
 * overrule them into a different character's folder.
 */
internal fun resolveImportTarget(
    cardsDir: File,
    json: JSONObject,
    editedName: String?,
    cardName: String = importDefaultsOf(json, "").name,
): Pair<String, String>? {
    val edited = editedName?.trim()?.takeIf { it.isNotBlank() } ?: return findMatchingEntryAt(cardsDir, json)
    if (normalizeCardName(edited) == normalizeCardName(cardName)) return findMatchingEntryAt(cardsDir, json)
    return findEntryByNameAt(cardsDir, edited)?.let { it to "name" }
}

/**
 * Imports a card file (json or png) into the library.
 *
 * The folder is chosen by the name the reader confirmed, then by the card's own
 * name, then by content features — never by the folder being viewed, so a card
 * cannot land in the wrong character's folder just because it was dropped there.
 * A name the reader edited routes by that name alone: they have said which
 * folder it belongs to, and the fingerprint must not overrule them. A card that
 * matches none starts a new folder; a body identical to a stored version is
 * reported as [Duplicate] instead of being copied again.
 */
internal fun importCardIntoLibrary(
    ctx: Context,
    src: File,
    preferredName: String? = null,
    versionLabel: String = "",
): ImportOutcome = importCardIntoLibraryAt(cardsDir(ctx), src, preferredName, versionLabel)

/**
 * The import itself, on a plain directory.
 *
 * Files and metadata only, so the routing and the duplicate check can be exercised
 * without an Android context — which is where a batch of sixty cards goes wrong.
 */
internal fun importCardIntoLibraryAt(
    cardsDir: File,
    src: File,
    preferredName: String? = null,
    versionLabel: String = "",
): ImportOutcome {
    val format = if (src.extension.equals("json", ignoreCase = true)) "json" else "png"
    val json = cardJsonOf(src) ?: return ImportOutcome.ParseFailed
    val defaults = importDefaultsOf(json, src.name)
    val edited = preferredName?.trim()?.takeIf { it.isNotBlank() }
    val name = edited ?: defaults.name.ifBlank { src.nameWithoutExtension }
    val match = resolveImportTarget(cardsDir, json, edited, defaults.name)
    val targetId = match?.first
    // A card that carries no version still gets one, so the folder reads as a
    // sequence instead of a pile of rows labelled nothing.
    val existingLabels = targetId?.let { versionLabelsAt(cardsDir, it) } ?: emptyList()
    // The card's own version comes next: a batch import never asks, and falling
    // straight through to a sequence number threw away "2.0" the file carried.
    val label = uniqueVersionLabel(
        versionLabel.trim()
            .ifBlank { defaults.version.trim() }
            .ifBlank { nextVersionLabel(existingLabels) },
        existingLabels
    )
    val versionId = UUID.randomUUID().toString()
    // The import cache filename ("import-card") carries no meaning; name the
    // version after the card so the folder reads human rather than technical.
    val rawVersionName = src.nameWithoutExtension
    val versionName = if (rawVersionName == "import-card") name else rawVersionName
    if (targetId != null) {
        if (hasSameCardAt(cardsDir, targetId, json)) {
            return ImportOutcome.Duplicate(targetId, name, versionName)
        }
        val vDir = File(File(cardsDir, targetId), "versions/$versionId")
        if (!vDir.mkdirs()) return ImportOutcome.ParseFailed
        val body = File(vDir, cardBodyName(format))
        if (!src.copyTo(body, overwrite = true).isFile) return ImportOutcome.ParseFailed
        val vMeta = JSONObject()
            .put("id", versionId)
            .put("name", versionName)
            .put("format", format)
            .put("source", IMPORTED_SOURCE)
            .put("coreStatus", "uninstalled")
            .put("importedAt", System.currentTimeMillis())
        if (label.isNotBlank()) vMeta.put("version", label)
        if (!writeCardMeta(vDir, vMeta)) return ImportOutcome.ParseFailed
        return ImportOutcome.Ok(targetId, name, versionId, versionName, label, isNewEntry = false, matchedBy = match.second)
    }
    val id = UUID.randomUUID().toString()
    val dir = File(cardsDir, id)
    if (!dir.mkdirs()) return ImportOutcome.ParseFailed
    val vDir = File(File(dir, "versions"), versionId)
    if (!vDir.mkdirs()) return ImportOutcome.ParseFailed
    val body = File(vDir, cardBodyName(format))
    if (!src.copyTo(body, overwrite = true).isFile) return ImportOutcome.ParseFailed
    val entryMeta = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdAt", System.currentTimeMillis())
    if (!writeCardMeta(dir, entryMeta)) return ImportOutcome.ParseFailed
    val vMeta = JSONObject()
        .put("id", versionId)
        .put("name", versionName)
        .put("format", format)
        .put("source", IMPORTED_SOURCE)
        .put("coreStatus", "uninstalled")
        .put("importedAt", System.currentTimeMillis())
    if (label.isNotBlank()) vMeta.put("version", label)
    if (!writeCardMeta(vDir, vMeta)) return ImportOutcome.ParseFailed
    return ImportOutcome.Ok(id, name, versionId, versionName, label, isNewEntry = true, matchedBy = null)
}


/** Renames the card folder's display name. */
internal fun renameCard(ctx: Context, cardId: String, newName: String): Boolean =
    renameCardAt(cardsDir(ctx), cardId, newName)

internal fun renameCardAt(cardsDir: File, cardId: String, newName: String): Boolean {
    val clean = newName.trim()
    if (clean.isEmpty() || clean.equals(cardId, ignoreCase = true)) return false
    val dir = File(cardsDir, cardId)
    if (!dir.isDirectory) return false
    val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return false
    meta.put("name", clean)
    return writeCardMeta(dir, meta)
}

/**
 * Versions of one folder that repeat a payload another version already holds.
 *
 * A folder built before the duplicate check compared content can carry two rows of
 * the same card; this is what a cleanup acts on. A deployed version is never listed,
 * because deleting the copy the core is reading would take the chats with it.
 */
internal fun duplicateVersionIdsAt(cardsDir: File, entryId: String): List<String> {
    val versionsDir = File(File(cardsDir, entryId), "versions")
    if (!versionsDir.isDirectory) return emptyList()
    val kept = mutableMapOf<String, String>()
    val doomed = mutableListOf<String>()
    // Deployed first: if two rows are the same card, the cleanup has to keep the one
    // the chats are reading, whatever the directory names happen to sort like.
    versionsDir.listFiles()
        ?.sortedWith(compareBy({ metaOf(File(it, "meta.json"))?.optString("coreStatus") != "installed" }, { it.name }))
        ?.forEach { vDir ->
        if (!vDir.isDirectory) return@forEach
        val meta = metaOf(File(vDir, "meta.json")) ?: return@forEach
        val body = File(vDir, cardBodyName(meta.optString("format", "png")))
        val json = cardJsonOf(body) ?: return@forEach
        val digest = payloadDigest(json)
        val deployed = meta.optString("coreStatus") == "installed"
        if (kept[digest] == null) {
            kept[digest] = vDir.name
        } else if (!deployed) {
            doomed.add(vDir.name)
        }
    }
    return doomed
}

internal fun duplicateVersionIds(ctx: Context, entryId: String): List<String> =
    duplicateVersionIdsAt(cardsDir(ctx), entryId)

/** Drops the duplicate versions of one folder; returns how many went. */
internal fun removeDuplicateVersions(ctx: Context, entryId: String): Int {
    val ids = duplicateVersionIds(ctx, entryId)
    ids.forEach { id -> versionDir(ctx, entryId, id).deleteRecursively() }
    return ids.size
}

/**
 * Pins one version as the folder's icon, or clears the pin so the newest import
 * shows again. The pin is a version id, so it survives renames and deploys; if
 * that version is deleted the folder falls back to its newest one instead of
 * showing nothing.
 */
internal fun setCoverVersion(ctx: Context, cardId: String, versionId: String?): Boolean =
    setCoverVersionAt(cardsDir(ctx), cardId, versionId)

internal fun setCoverVersionAt(cardsDir: File, cardId: String, versionId: String?): Boolean {
    val dir = File(cardsDir, cardId)
    val meta = metaOf(File(dir, "meta.json")) ?: return false
    if (versionId.isNullOrBlank()) meta.remove("coverVersionId") else meta.put("coverVersionId", versionId)
    return writeCardMeta(dir, meta)
}

internal fun deployVersion(ctx: Context, card: LibraryCard, version: LibraryVersion, coreDir: File): Boolean {
    val deployed = deployVersionAt(cardsDir(ctx), card, version, coreDir)
    if (deployed) {
        version.meta.optString("coreFileName").takeIf { it.isNotBlank() }?.let {
            CharacterDeploymentNotice.mark(ctx, it, card.name)
        }
    }
    return deployed
}

/** The name [version] would deploy under; the confirmation dialog names it before writing. */
internal fun deployTargetName(ctx: Context, card: LibraryCard, version: LibraryVersion): String {
    val charsDir = File(AssetExtractor.getCoreDir(ctx), "data/default-user/characters")
    return coreFileNameForVersion(charsDir, cardsDir(ctx), card, version)
}

/**
 * Writes one version into the core as its own character.
 *
 * The core files a chat under a directory named after the png, and reads that directory as the
 * character's identity. So a deploy that overwrote the folder's single file would not "update a
 * card": it would hand the new version every chat the old one had, with state, variables and
 * greetings that were written for the old body. Each version therefore answers on its own name —
 * the one it already owns if it has been deployed before, a fresh one otherwise — and the other
 * versions keep their files and their chats.
 */
internal fun deployVersionAt(cardsDir: File, card: LibraryCard, version: LibraryVersion, coreDir: File): Boolean {
    val charsDir = File(coreDir, "data/default-user/characters")
    // Reconcile before choosing a name: a version whose copy was removed by hand still carries
    // the flag, and letting it hold its name would push this deploy onto a "(2)" suffix for no
    // reason. A version deployed next to this one keeps its file and its flag.
    card.versions.filter { it.id != version.id && it.meta.optString("coreStatus") == "installed" }
        .forEach { old ->
            val oldName = old.coreFileName ?: return@forEach
            if (File(charsDir, oldName).isFile) return@forEach
            old.meta.put("coreStatus", "uninstalled")
            writeCardMeta(File(File(cardsDir, card.id), "versions/${old.id}"), old.meta)
        }
    val target = File(charsDir, coreFileNameForVersion(charsDir, cardsDir, card, version))
    // Written before anything else moves: a failure here leaves the core as it was.
    if (!writeCardBodyToCore(version, target)) return false
    version.meta.put("coreFileName", target.name)
        .put("coreStatus", "installed")
        .put("deployedAt", System.currentTimeMillis())
    if (!writeCardMeta(File(File(cardsDir, card.id), "versions/${version.id}"), version.meta)) return false
    // The folder keeps answering on the first file it deployed, so a rename still finds the chats
    // that were started before versions had names of their own.
    if (card.coreFileName.isNullOrBlank()) rememberCoreFileName(cardsDir, card, target.name)
    return true
}

/** Writes a version's card body into the core as a png (json transcoded first). */
internal fun writeCardBodyToCore(version: LibraryVersion, target: File): Boolean {
    val parent = target.parentFile ?: return false
    if (!parent.exists() && !parent.mkdirs()) return false
    val bytes = if (version.format == "json") {
        val json = cardJsonOf(version.file) ?: return false
        encodeJsonCardAsPng(json) ?: return false
    } else {
        runCatching { version.file.readBytes() }.getOrNull() ?: return false
    }
    return writeBytesAtomically(target, bytes)
}

/**
 * Writes through a temporary file and renames it into place.
 *
 * The core reads this file directly. Writing over it leaves a window where the card is
 * truncated: it still lists, and every chat that uses it fails to open. The rename
 * replaces the entry in one step, so readers see either the old card or the new one.
 */
internal fun writeBytesAtomically(target: File, bytes: ByteArray): Boolean {
    val parent = target.parentFile ?: return false
    val temp = File(parent, target.name + ".stctrl-tmp")
    val written = runCatching { temp.writeBytes(bytes) }.isSuccess
    if (!written) {
        runCatching { temp.delete() }
        return false
    }
    // File.renameTo cannot replace an existing file on every filesystem, and the target
    // always exists on the second deploy; Files.move with REPLACE_EXISTING is a rename on
    // POSIX (one step, readers see old or new) and still works elsewhere.
    val moved = runCatching {
        java.nio.file.Files.move(
            temp.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }.isSuccess
    if (!moved) {
        runCatching { temp.delete() }
        return false
    }
    return target.isFile
}

/**
 * Removes one version from a card folder. When that version is deployed, its
 * core copy and cached thumbnail are cleaned as well; the folder and the other
 * versions stay.
 */
internal fun removeVersion(ctx: Context, card: LibraryCard, version: LibraryVersion, coreDir: File): Boolean {
    var ok = true
    // Two rows can claim one core file when an older build left the flag on both. Removing
    // either one must not take the copy the surviving row is deployed under, or the character
    // disappears from the core while the library still says it is there.
    val sharedCore = version.coreFileName?.let { name ->
        card.versions.any { other ->
            other.id != version.id && other.coreFileName == name &&
                other.meta.optString("coreStatus") == "installed"
        }
    } == true
    if (version.imported && !sharedCore) {
        version.coreFileName?.let { coreName ->
            val coreFile = File(File(coreDir, "data/default-user/characters"), coreName)
            if (coreFile.isFile && !coreFile.delete()) ok = false
        }
        version.coreFileName?.let { coreName ->
            val userDir = File(coreDir, "data/default-user")
            val thumbBase = coreName
            listOf(thumbBase, thumbBase.removeSuffix(".png") + ".png").distinct().forEach { thumbName ->
                listOf("avatar", "").forEach { sub ->
                    val thumb = if (sub.isEmpty()) File(userDir, "thumbnails/$thumbName")
                    else File(userDir, "thumbnails/$sub/$thumbName")
                    if (thumb.isFile && !thumb.delete()) ok = false
                }
            }
        }
    }
    val dir = versionDir(ctx, card.id, version.id)
    if (dir.exists() && !dir.deleteRecursively()) ok = false
    // A pin pointing at the version just removed would keep naming a folder icon
    // that no longer exists; the reader set it, so the pin goes with it.
    if (card.coverVersionId == version.id) setCoverVersion(ctx, card.id, null)
    return ok
}

/**
 * Takes a version out of the core and leaves it in the library.
 *
 * The core panel lists what the tavern can see, and removing an entry there means "this should
 * not be deployed" — not "throw the card away". Both live in this file, one call apart, so the
 * difference is kept explicit rather than inferred from which page the caller is on: the version
 * directory stays, its flag goes back to uninstalled, and the chats it already owns stay where
 * they are because the name they are filed under is kept.
 */
internal fun undeployVersion(ctx: Context, card: LibraryCard, version: LibraryVersion, coreDir: File): Boolean {
    var ok = true
    version.coreFileName?.let { coreName ->
        val coreFile = File(File(coreDir, "data/default-user/characters"), coreName)
        if (coreFile.isFile && !coreFile.delete()) ok = false
        val userDir = File(coreDir, "data/default-user")
        listOf(coreName, coreName.removeSuffix(".png") + ".png").distinct().forEach { thumbName ->
            listOf("avatar", "").forEach { sub ->
                val thumb = if (sub.isEmpty()) File(userDir, "thumbnails/$thumbName")
                else File(userDir, "thumbnails/$sub/$thumbName")
                if (thumb.isFile && !thumb.delete()) ok = false
            }
        }
    }
    version.meta.put("coreStatus", "uninstalled")
    if (!writeCardMeta(versionDir(ctx, card.id, version.id), version.meta)) ok = false
    return ok
}

/** Renames a version inside its folder; the body and any core copy keep their names. */
internal fun renameVersion(ctx: Context, cardId: String, versionId: String, newName: String): Boolean =
    renameVersionAt(cardsDir(ctx), cardId, versionId, newName)

internal fun renameVersionAt(cardsDir: File, cardId: String, versionId: String, newName: String): Boolean {
    val name = newName.trim()
    if (name.isEmpty()) return false
    val vDir = File(File(File(cardsDir, cardId), "versions"), versionId)
    val meta = runCatching { JSONObject(File(vDir, "meta.json").readText()) }.getOrNull() ?: return false
    meta.put("name", name)
    return writeCardMeta(vDir, meta)
}

/**
 * Writes the free-form version label shown under the version row. An empty
 * string clears it back to a blank subtitle; the version body and core copy
 * are untouched.
 */
internal fun setVersionLabel(ctx: Context, cardId: String, versionId: String, label: String): Boolean {
    val vDir = versionDir(ctx, cardId, versionId)
    val meta = runCatching { JSONObject(File(vDir, "meta.json").readText()) }.getOrNull() ?: return false
    meta.put("version", label.trim())
    return writeCardMeta(vDir, meta)
}

/** The version's card body file, read for exporting. */
internal fun versionBodyFile(ctx: Context, cardId: String, versionId: String): File? {
    val vDir = versionDir(ctx, cardId, versionId)
    if (!vDir.isDirectory) return null
    val meta = runCatching { JSONObject(File(vDir, "meta.json").readText()) }.getOrNull() ?: return null
    return File(vDir, cardBodyName(meta.optString("format", "png"))).takeIf { it.isFile }
}

/**
 * Moves a version to another card folder (file-manager "move"). The version
 * directory travels whole with its meta, so deploy state is preserved; the
 * source folder is removed when it ends up with no versions left.
 */
internal fun moveVersion(ctx: Context, entryId: String, versionId: String, targetEntryId: String): Boolean =
    moveVersionAt(cardsDir(ctx), entryId, versionId, targetEntryId)

internal fun moveVersionAt(cardsDir: File, entryId: String, versionId: String, targetEntryId: String): Boolean {
    if (entryId == targetEntryId) return false
    val src = File(File(File(cardsDir, entryId), "versions"), versionId)
    if (!src.isDirectory) return false
    val dstParent = File(File(cardsDir, targetEntryId), "versions")
    if (!dstParent.isDirectory && !dstParent.mkdirs()) return false
    if (File(dstParent, versionId).exists()) return false
    if (!src.renameTo(File(dstParent, versionId))) return false
    // The moved version cannot keep serving as the icon of the folder it left.
    val srcDir = File(cardsDir, entryId)
    val srcMeta = metaOf(File(srcDir, "meta.json"))
    if (srcMeta != null && srcMeta.optString("coverVersionId") == versionId) {
        srcMeta.remove("coverVersionId")
        writeCardMeta(srcDir, srcMeta)
    }
    val srcVersions = src.parentFile
    val srcEntry = srcVersions?.parentFile
    val remaining = srcVersions?.listFiles()?.filter { it.isDirectory } ?: emptyList()
    if (remaining.isEmpty()) {
        srcVersions?.deleteRecursively()
        srcEntry?.deleteRecursively()
    }
    return true
}

/**
 * Removes a card folder from the library. When a version is deployed, its core
 * copy and cached thumbnail are cleaned as well. The caller is responsible for
 * the rest of the core state (chats, world book orphans, character-scoped
 * settings) when the card is deployed.
 */
internal fun removeLibraryCard(ctx: Context, card: LibraryCard, coreDir: File): Boolean {
    var ok = true
    card.deployedVersion?.let { deployed ->
        deployed.coreFileName?.let { coreName ->
            val coreFile = File(File(coreDir, "data/default-user/characters"), coreName)
            if (coreFile.isFile && !coreFile.delete()) ok = false
        }
        deployed.coreFileName?.let { coreName ->
            val userDir = File(coreDir, "data/default-user")
            val thumbBase = coreName
            listOf(thumbBase, thumbBase.removeSuffix(".png") + ".png").distinct().forEach { thumbName ->
                listOf("avatar", "").forEach { sub ->
                    val thumb = if (sub.isEmpty()) File(userDir, "thumbnails/$thumbName")
                    else File(userDir, "thumbnails/$sub/$thumbName")
                    if (thumb.isFile && !thumb.delete()) ok = false
                }
            }
        }
    }
    val dir = cardDir(ctx, card.id)
    if (dir.exists() && !dir.deleteRecursively()) ok = false
    return ok
}

/**
 * Encodes a card json into a png character card the core lists. The card data
 * goes into the chara tEXt chunk, the pixel image is the card's own avatar when
 * it embeds one, otherwise a generated placeholder.
 */
internal fun encodeJsonCardAsPng(json: JSONObject): ByteArray? = runCatching {
    val card = wrapAsCardV2(json)
    val data = card.getJSONObject("data")
    val avatar = decodeAvatar(data.optString("avatar", ""))
        ?: placeholderAvatar(data.optString("name", "character").take(1).ifBlank { "?" })
    val png = ByteArrayOutputStream().also { avatar.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    val text = "chara\u0000" + Base64.encodeToString(card.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val textBytes = text.toByteArray(Charsets.UTF_8)

    // The compressed png has signature (8) then chunks; the chara tEXt chunk
    // must sit between IHDR and the rest.
    val ihdrEnd = 8 + 25
    val out = ByteArrayOutputStream()
    out.write(png, 0, ihdrEnd)
    writeChunk(out, "tEXt", textBytes)
    out.write(png, ihdrEnd, png.size - ihdrEnd)
    out.toByteArray()
}.getOrNull()

/** Normalizes v1 flat json or v2 nested json into a chara_card_v2 document. */
private fun wrapAsCardV2(json: JSONObject): JSONObject {
    val data = json.optJSONObject("data") ?: json
    return JSONObject()
        .put("spec", "chara_card_v2")
        .put("spec_version", "2.0")
        .put("data", data)
        // V1 mirrors of the fields that matter most. The importer reads the name off the top
        // level, so a png written without these landed in the library as the staging file name
        // ("import-card") instead of the character it contains.
        .apply {
            for (key in listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")) {
                val value = data.optString(key, "")
                if (value.isNotEmpty()) put(key, value)
            }
        }
}

/** The card's own avatar when it embeds one (ccv3 keeps it in data.avatar). */
private fun decodeAvatar(b64: String): Bitmap? {
    if (b64.isBlank() || b64.equals("none", ignoreCase = true)) return null
    val raw = if (b64.startsWith("data:image/")) b64.substringAfter(',') else b64
    val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull() ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/** Placeholder avatar: first character of the name over a color from the name. */
private fun placeholderAvatar(ch: String): Bitmap {
    val size = 400
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val hue = (ch.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) } % 360).toFloat()
    canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.72f)))
    val paint = Paint().apply {
        color = Color.WHITE
        textSize = 200f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(ch, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    return bmp
}

/** Appends one png chunk: length, type, data, crc over type+data. */
private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
    out.write(intBytes(data.size))
    out.write(typeBytes)
    out.write(data)
    out.write(intBytes(crc))
}

private fun intBytes(v: Int): ByteArray = byteArrayOf(
    (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
)
