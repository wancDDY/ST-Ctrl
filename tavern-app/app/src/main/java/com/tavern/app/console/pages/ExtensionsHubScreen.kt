package com.tavern.app.console.pages

import com.tavern.app.console.components.ExpandChevron
import com.tavern.app.console.AgentClient
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tavern.app.console.components.CollapsibleSection
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.ListGroup
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.SkeletonList
import com.tavern.app.console.components.PageTopBar
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.components.appOutlinedButtonBorder
import com.tavern.app.console.components.appOutlinedButtonColors
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppPanelShape
import com.tavern.app.console.theme.AppCardShape
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.console.theme.pressHighlightIndication
import com.tavern.app.util.AppToast
import com.tavern.app.util.AssetExtractor
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CharCardInfo(
    val name: String,
    val description: String,
    val avatarPath: String,
    val fsName: String = "",        // filesystem entry name (dir or filename w/o ext)
    val personality: String = "",
    val firstMessage: String = "",
    val scenario: String = "",
    val creator: String = "",
    val tags: List<String> = emptyList(),
    val version: String = "",
    val regexScripts: List<RegexScriptInfo> = emptyList(),
    val helperScripts: List<HelperScriptInfo> = emptyList(),
    val worldBooks: List<WorldBookInfo> = emptyList(),
    // SillyTavern lists only `characters/*.png`, read flat and with a case-sensitive
    // suffix. A card that breaks either rule is on disk but will never appear in the
    // tavern, so the console has to say so instead of pretending the card works.
    val coreReadable: Boolean = true,
    // Library bookkeeping: cards live in the app-private library; format and the
    // installed flag decide which detail actions a card offers.
    val format: String = "png",     // "png" | "json"
    val libraryId: String = "",
    val libraryVersionId: String = "",
    val coreFileName: String = "",  // name of the core copy, when installed
    val imported: Boolean = false,  // a core copy exists
    val source: String = "",        // "core-migrated" | "imported"
)

data class WorldBookEntry(
    val key: String,
    val comment: String,
    val content: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList()
)

data class WorldBookInfo(
    val name: String,
    val path: String,
    val entryCount: Int = 0,
    val entries: List<WorldBookEntry> = emptyList(),
    // Where the book came from: embedded in the card, bound by the character, a
    // global selection, or a file that merely shares the character's name.
    val origin: String = ""
)

data class RegexScriptInfo(
    val name: String,
    val findRegex: String = "",
    val replaceString: String = "",
    val enabled: Boolean = true
)

/** A script the card ships for the TavernHelper extension. */
data class HelperScriptInfo(
    val name: String,
    val enabled: Boolean = true,
    val type: String = "script"
)

internal fun parseCharCard(pngFile: File): JSONObject? {
    var dis: DataInputStream? = null
    return try {
        dis = DataInputStream(FileInputStream(pngFile))
        dis.skipBytes(8)
        var result: JSONObject? = null
        while (result == null) {
            val len = dis.readInt()
            val type = ByteArray(4); dis.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)
            when {
                typeStr == "IEND" -> break
                typeStr == "tEXt" -> {
                    val data = ByteArray(len); dis.readFully(data); dis.skipBytes(4)
                    val nullIdx = data.indexOf(0)
                    val kw = String(data, 0, nullIdx, Charsets.US_ASCII)
                    if (nullIdx > 0 && (kw == "chara" || kw == "ccv3")) {
                        val b64 = String(data, nullIdx + 1, data.size - nullIdx - 1, Charsets.UTF_8)
                        result = JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
                    }
                }
                else -> dis.skipBytes(len + 4)
            }
        }
        result
    } catch (_: Exception) { null }
    finally { dis?.close() }
}

// Helpers: extract extensions from card JSON

/** Navigate card JSON → data → field, falling back to top-level. */
private fun cardData(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

/**
 * Scripts the card hands to TavernHelper. Most cards run their variable, status-bar
 * and table logic from here rather than from plain regex, so a card listing that
 * shows only regex_scripts is incomplete; folders nest their children.
 */
private fun extractHelperScripts(json: JSONObject): List<HelperScriptInfo> {
    val root = cardData(json).optJSONObject("extensions")
        ?.optJSONObject("tavern_helper")
        ?.optJSONArray("scripts") ?: return emptyList()
    val out = mutableListOf<HelperScriptInfo>()

    fun walk(arr: org.json.JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val node = arr.optJSONObject(i) ?: continue
            val type = node.optString("type", "script")
            val scriptName = node.optString("name", "")
            if (scriptName.isNotBlank() && type != "folder") {
                if (out.none { it.name == scriptName }) {
                    out.add(HelperScriptInfo(scriptName, node.optBoolean("enabled", true), type))
                }
            }
            walk(node.optJSONArray("scripts"))
        }
    }
    walk(root)
    return out
}

/** Extract regex scripts from the character card extensions.
 *  ST stores these under data.extensions.regex_scripts (a JSONArray). */
private fun extractRegex(json: JSONObject): List<RegexScriptInfo> {
    val out = mutableListOf<RegexScriptInfo>()

    fun addFromArray(arr: org.json.JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val sn = s.optString("scriptName", s.optString("name", ""))
            if (sn.isNotBlank() && out.none { it.name == sn }) {
                out.add(RegexScriptInfo(
                    name = sn,
                    findRegex = s.optString("findRegex", ""),
                    replaceString = s.optString("replaceString", ""),
                    enabled = !s.optBoolean("disabled", false)
                ))
            }
        }
    }

    fun addFromObject(obj: org.json.JSONObject?) {
        if (obj == null) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val s = obj.optJSONObject(keys.next()) ?: continue
            val sn = s.optString("scriptName", s.optString("name", ""))
            if (sn.isNotBlank() && out.none { it.name == sn }) {
                out.add(RegexScriptInfo(
                    name = sn,
                    findRegex = s.optString("findRegex", ""),
                    replaceString = s.optString("replaceString", ""),
                    enabled = !s.optBoolean("disabled", false)
                ))
            }
        }
    }

    val exts = cardData(json).optJSONObject("extensions") ?: json.optJSONObject("extensions") ?: return out
    // Primary: regex_scripts as array (ST's actual key)
    addFromArray(exts.optJSONArray("regex_scripts"))
    // Also try regex_scripts as object
    addFromObject(exts.optJSONObject("regex_scripts"))
    // Fallback: regex.scripts (alternate format)
    val regexObj = exts.optJSONObject("regex")
    if (regexObj != null) {
        addFromArray(regexObj.optJSONArray("scripts"))
        addFromObject(regexObj.optJSONObject("scripts"))
    }

    return out
}

/** Parse entries from a JSON object or array into full entry data. */
private fun parseEntries(entriesObj: Any?): Pair<Int, List<WorldBookEntry>> {
    val list = mutableListOf<WorldBookEntry>()
    var count = 0
    fun addEntry(key: String, entry: JSONObject) {
        val comment = entry.optString("comment", entry.optString("key", key))
        val content = entry.optString("content", "")
        val keys = mutableListOf<String>()
        val kArr = entry.optJSONArray("keys")
        if (kArr != null) for (i in 0 until kArr.length()) keys.add(kArr.optString(i, ""))
        val skArr = entry.optJSONArray("secondary_keys")
        val secondaryKeys = mutableListOf<String>()
        if (skArr != null) for (i in 0 until skArr.length()) secondaryKeys.add(skArr.optString(i, ""))
        list.add(WorldBookEntry(key, comment, content, keys, secondaryKeys))
        count++
    }
    when (entriesObj) {
        is JSONObject -> {
            val keys = entriesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = entriesObj.optJSONObject(k) ?: continue
                addEntry(k, entry)
            }
        }
        is org.json.JSONArray -> {
            for (i in 0 until entriesObj.length()) {
                val entry = entriesObj.optJSONObject(i) ?: continue
                val key = entry.optString("key", entry.optString("uid", "$i"))
                addEntry(key, entry)
            }
        }
    }
    return count to list
}

/**
 * The world info selections, wherever this core keeps them.
 *
 * SillyTavern 1.19 moved them to settings.world_info_settings.world_info; older cores
 * used settings.world_info. Reading only the old key silently showed every card with
 * just its embedded book, and the delete missed the additional ones.
 */
private fun worldInfoSelections(settings: JSONObject?): JSONObject? {
    val nested = settings?.optJSONObject("world_info_settings")?.optJSONObject("world_info")
    return nested ?: settings?.optJSONObject("world_info")
}

/** The core of a name: decoration, versions and punctuation removed. */
private fun nameCore(value: String): String {
    return value
        .replace(Regex("[《》【】\\[\\]()（）<>\"'`·・,，.。!！?？:：;；、\\s]+"), " ")
        .split(" ")
        .filter { it.isNotBlank() && !it.matches(Regex("(?i)v?\\d+([._-]\\d+)*")) }
        .maxByOrNull { token -> token.count { it.code > 0x2E80 } }
        .orEmpty()
        .lowercase()
}

/**
 * Whether a world book file belongs to a card by name.
 *
 * A book created by a script is named after the character with a suffix of the
 * script's own and is registered as nobody's book, so the name is the only link
 * left. The comparison runs on the identifying core of both names and only accepts
 * a prefix match, which keeps a book named after a longer title out of a card whose
 * name is only a prefix of it.
 */
private fun relatedWorldName(fileName: String, cardName: String, cardFsName: String): Boolean {
    val fileCore = nameCore(fileName)
    if (fileCore.length < 2) return false
    return listOf(cardName, cardFsName).any { candidate ->
        val core = nameCore(candidate)
        core.length >= 2 && (fileCore.startsWith(core) || core.startsWith(fileCore))
    }
}

/** The core's world book file for a name, if one exists. */
private fun worldFileFor(coreDir: File, name: String): File? {
    if (name.isBlank()) return null
    val dir = File(coreDir, "data/default-user/worlds")
    if (!dir.exists()) return null
    File(dir, name).takeIf { it.isFile }?.let { return it }
    File(dir, "$name.json").takeIf { it.isFile }?.let { return it }
    return dir.listFiles()?.firstOrNull {
        it.isFile && it.nameWithoutExtension.equals(name, ignoreCase = true)
    }
}

/**
 * World books that only the card being deleted referred to. SillyTavern never removes
 * world files itself, so a card that carried its own book leaves it behind; a file is
 * removed only when no remaining card names it and it is not selected globally.
 */
private fun orphanedWorldBooks(
    coreDir: File,
    deletedCard: File,
    candidates: Set<String>,
    deletedLabels: Set<String>,
): List<File> {
    if (candidates.isEmpty()) return emptyList()
    val keep = mutableSetOf<String>()
    val settings = runCatching {
        JSONObject(File(coreDir, "data/default-user/settings.json").readText())
    }.getOrNull()
    val worldInfo = worldInfoSelections(settings)
    worldInfo?.optJSONArray("globalSelect")?.let { arr ->
        for (i in 0 until arr.length()) keep.add(arr.optString(i))
    }
    worldInfo?.optJSONArray("charLore")?.let { arr ->
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            // The row keyed by the card being deleted protects nothing: its book is the
            // one that just became an orphan. SillyTavern stores the card's file name
            // without the extension, so every label the card answers to is compared.
            if (deletedLabels.any { label -> label.equals(entry.optString("name"), ignoreCase = true) }) continue
            entry.optJSONArray("extraBooks")?.let { books ->
                for (j in 0 until books.length()) keep.add(books.optString(j))
            }
        }
    }
    File(coreDir, "data/default-user/characters")
        .walkTopDown()
        .filter { it.isFile && it.extension.equals("png", ignoreCase = true) && it.absolutePath != deletedCard.absolutePath }
        .forEach { file ->
            val json = parseCharCard(file) ?: return@forEach
            when (val world = cardData(json).optJSONObject("extensions")?.opt("world")) {
                is String -> if (world.isNotBlank()) keep.add(world)
                is JSONArray -> for (i in 0 until world.length()) keep.add(world.optString(i))
            }
            cardData(json).optJSONObject("character_book")
                ?.optString("name")?.takeIf { it.isNotBlank() }?.let { keep.add(it) }
            // A book that only shares this card's name belongs to it as well: the same
            // rule that lists it has to protect it from another card's deletion.
            val otherName = json.optString("name", file.nameWithoutExtension)
            candidates.forEach { candidate ->
                if (relatedWorldName(candidate, otherName, file.nameWithoutExtension)) keep.add(candidate)
            }
        }
    return candidates
        .mapNotNull { worldFileFor(coreDir, it) }
        .filter { file -> keep.none { it.equals(file.nameWithoutExtension, ignoreCase = true) } }
        .distinct()
}

/**
 * Removes the records SillyTavern and its extensions keep about one card.
 *
 * A deleted card has to leave no trace: the tavern remembers in accountStorage that it
 * already offered to import the card's world book and its regex, and the helper extension
 * remembers which avatars it offered scripts to (the tavern's own delete clears the same
 * two lists). Those records are what make a card imported again behave like a card that
 * was never here — with them in place the dialogs stay away and the book the card carries
 * is never imported, which leaves the World Info panel empty until the page is reloaded.
 *
 * @param labels every name the card answers to: its display name, its file name with and
 *   without the extension.
 * @param regexNames the names of the regex the card brought along. Imported regex carry no
 *   owner, so their names are the only link back to the card.
 * @return true when something was removed, so the caller only rewrites a changed file.
 */
internal fun purgeCardState(
    settings: JSONObject,
    labels: Set<String>,
    regexNames: Set<String>,
): Boolean {
    if (labels.isEmpty()) return false
    var touched = false

    fun isMine(value: String?): Boolean =
        value != null && labels.any { it.equals(value, ignoreCase = true) }

    /** The array without this card's labels, or null when nothing matched. */
    fun filtered(array: JSONArray?): JSONArray? {
        if (array == null) return null
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optString(i)
            if (isMine(item)) touched = true else kept.put(item)
        }
        return if (kept.length() == array.length()) null else kept
    }

    // The "already asked" markers. One key per dialog, suffixed with the avatar file name;
    // the regex extension also keys some of them by API preset, which no label matches.
    settings.optJSONObject("accountStorage")?.let { storage ->
        val keys = storage.keys().asSequence().toList()
        for (key in keys) {
            if (!key.startsWith("Alert")) continue
            if (isMine(key.substringAfter('_', ""))) {
                storage.remove(key)
                touched = true
            }
        }
    }

    val extensions = settings.optJSONObject("extension_settings")
    if (extensions != null) {
        filtered(extensions.optJSONArray("character_allowed_regex"))
            ?.let { extensions.put("character_allowed_regex", it) }

        val script = extensions.optJSONObject("tavern_helper")?.optJSONObject("script")
        script?.optJSONObject("popuped")?.let { popuped ->
            filtered(popuped.optJSONArray("characters"))?.let { popuped.put("characters", it) }
        }
        script?.optJSONObject("enabled")?.let { enabled ->
            filtered(enabled.optJSONArray("characters"))?.let { enabled.put("characters", it) }
        }

        // Quick replies a preset bound to this card.
        extensions.optJSONObject("quickReplyV2")?.optJSONObject("characterConfigs")?.let { configs ->
            val keys = configs.keys().asSequence().toList()
            for (key in keys) {
                if (isMine(key)) {
                    configs.remove(key)
                    touched = true
                }
            }
        }
    }

    // The world info row that binds the card's extra books, keyed by file name.
    val container = settings.optJSONObject("world_info_settings")?.optJSONObject("world_info")
        ?: settings.optJSONObject("world_info")
    container?.optJSONArray("charLore")?.let { lore ->
        val kept = JSONArray()
        for (i in 0 until lore.length()) {
            val entry = lore.optJSONObject(i) ?: continue
            if (isMine(entry.optString("name"))) {
                touched = true
                continue
            }
            kept.put(entry)
        }
        if (kept.length() != lore.length()) container.put("charLore", kept)
    }

    settings.optJSONArray("regex_scripts")?.let { imported ->
        if (regexNames.isNotEmpty()) {
            val kept = JSONArray()
            for (i in 0 until imported.length()) {
                val item = imported.optJSONObject(i) ?: continue
                val name = item.optString("scriptName", item.optString("name", ""))
                if (name in regexNames) {
                    touched = true
                    continue
                }
                kept.put(item)
            }
            if (kept.length() != imported.length()) settings.put("regex_scripts", kept)
        }
    }

    return touched
}

/** Extract embedded character_book (lorebook) from card JSON. */
private fun extractCharBook(json: JSONObject): WorldBookInfo? {
    val book = cardData(json).optJSONObject("character_book") ?: return null
    val (count, entries) = parseEntries(
        book.optJSONObject("entries") ?: book.optJSONArray("entries")
    )
    if (count == 0) return null
    return WorldBookInfo(
        name = book.optString("name", "内嵌世界书"),
        path = "",
        entryCount = count,
        entries = entries
    )
}

/**
 * Whether the tavern will list this file. SillyTavern reads the characters directory without
 * recursion and keeps only names ending in the exact lowercase suffix, so a card in a
 * subdirectory or saved as `card.PNG` exists for the console and not for the tavern.
 */
internal fun isCoreReadableCard(file: File, charactersDir: File): Boolean =
    file.parentFile?.absolutePath == charactersDir.absolutePath &&
        file.name.endsWith(".png")

/** The name a card has to carry to be listed, for the repair action in the card detail. */
internal fun coreCardName(fileName: String): String =
    if (fileName.endsWith(".png", ignoreCase = true)) {
        fileName.dropLast(4) + ".png"
    } else {
        fileName
    }

/**
 * Puts a card where the tavern will list it: same file in the characters root, with the
 * lowercase suffix. Returns the repaired file, or null when the move cannot happen — a target
 * with that name already exists, which is the case the caller reports instead of overwriting.
 */
internal fun repairCardForCore(file: File, charactersDir: File): File? {
    val target = File(charactersDir, coreCardName(file.name))
    if (target.absolutePath == file.absolutePath || target.exists()) {
        return null
    }

    return if (file.renameTo(target)) target else null
}

/**
 * Library view of one version inside a card folder. Uninstalled versions
 * expose only what travels inside the card file itself.
 */
internal fun loadVersionInfo(
    ctx: android.content.Context,
    card: LibraryCard,
    version: LibraryVersion,
    coreDir: File,
): CharCardInfo? {
    val json = cardJsonOf(version.file) ?: return null
    val fsName = if (version.imported) {
        (version.coreFileName?.substringBeforeLast('.') ?: card.name)
    } else {
        version.name
    }
    return buildCharCardInfo(
        json = json,
        cardFile = version.file,
        name = card.name,
        fsName = fsName,
        imported = version.imported,
        coreFileName = version.coreFileName,
        format = version.format,
        libraryId = card.id,
        libraryVersionId = version.id,
        source = version.source,
        coreDir = coreDir
    )
}

/**
 * Core-side card browser: every png the tavern actually lists in its characters
 * directory, independent of the library. Used by the characters sub-tab, which
 * shows the core as the tavern sees it.
 */
/**
 * The card list from the file system alone: a name per png and the path to its avatar.
 *
 * Reading metadata for every png takes seconds on a phone, so the list starts from file names and
 * avatar paths. The full read replaces it when it finishes.
 */
internal fun listCoreCharacters(coreDir: File): List<CharCardInfo> {
    val charsDir = File(coreDir, "data/default-user/characters")
    if (!charsDir.exists()) return emptyList()
    return charsDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        ?.map { file ->
            CharCardInfo(
                name = file.nameWithoutExtension,
                description = "",
                avatarPath = file.absolutePath,
                fsName = file.nameWithoutExtension,
                imported = true,
                coreFileName = file.name,
                source = "核心",
            )
        } ?: emptyList()
}

internal fun loadCoreCharacters(ctx: android.content.Context, coreDir: File): List<CharCardInfo> {
    val charsDir = File(coreDir, "data/default-user/characters")
    if (!charsDir.exists()) return emptyList()
    return charsDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        ?.mapNotNull { file ->
            val json = parseCharCard(file) ?: return@mapNotNull null
            val name = json.optString("name", "").takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            buildCharCardInfo(
                json = json,
                cardFile = file,
                name = name,
                fsName = file.nameWithoutExtension,
                imported = true,
                coreFileName = file.name,
                format = "png",
                libraryId = "",
                libraryVersionId = "",
                source = "核心",
                coreDir = coreDir
            )
        } ?: emptyList()
}

/**
 * Assembles the detail view of one card body, whether it lives in the library
 * (a version) or directly in the core (the core browser).
 */
private fun buildCharCardInfo(
    json: JSONObject,
    cardFile: File,
    name: String,
    fsName: String,
    imported: Boolean,
    coreFileName: String?,
    format: String,
    libraryId: String,
    libraryVersionId: String,
    source: String,
    coreDir: File,
): CharCardInfo {
    val charsDir = File(coreDir, "data/default-user/characters")
    val coreFile = coreFileName?.takeIf { it.isNotBlank() }?.let { File(charsDir, it) }
    val coreReadable = !imported || (coreFile != null && isCoreReadableCard(coreFile, charsDir))
    val regexScripts = extractRegex(json).toMutableList()
    // Companion json beside the core copy can hold regex the card carries.
    if (imported && coreFile != null) {
        coreFile.parentFile?.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) && it.name != coreFile.name }
            ?.forEach { f ->
                runCatching {
                    val fileJson = JSONObject(f.readText())
                    val regexArr = fileJson.optJSONArray("scripts")
                        ?: fileJson.optJSONObject("regex")?.optJSONArray("scripts")
                        ?: fileJson.optJSONArray("regex")
                    if (regexArr != null) {
                        for (i in 0 until regexArr.length()) {
                            val s = regexArr.optJSONObject(i) ?: continue
                            val sn = s.optString("scriptName", s.optString("name", "脚本${regexScripts.size + 1}"))
                            if (regexScripts.none { it.name == sn }) {
                                regexScripts.add(RegexScriptInfo(
                                    name = sn,
                                    findRegex = s.optString("findRegex", ""),
                                    replaceString = s.optString("replaceString", "")
                                ))
                            }
                        }
                    }
                }
            }
    }
    val tags = mutableListOf<String>()
    json.optJSONArray("tags")?.let { arr -> for (i in 0 until arr.length()) tags.add(arr.optString(i)) }
    return CharCardInfo(
        name = name,
        description = json.optString("description", ""),
        avatarPath = cardFile.absolutePath,
        fsName = fsName,
        personality = json.optString("personality", ""),
        firstMessage = json.optString("first_mes", ""),
        scenario = json.optString("scenario", ""),
        creator = json.optString("creator", ""),
        tags = tags,
        version = json.optString("character_version", ""),
        regexScripts = regexScripts,
        helperScripts = extractHelperScripts(json),
        worldBooks = libraryWorldBooks(json, coreDir, name, fsName, imported, coreFileName),
        coreReadable = coreReadable,
        format = format,
        libraryId = libraryId,
        libraryVersionId = libraryVersionId,
        coreFileName = coreFileName ?: "",
        imported = imported,
        source = source
    )
}

/**
 * World books a library card can list: the embedded book always, and for
 * installed cards the core-side bindings (extensions.world, charLore extras)
 * and worlds files that share the card's name.
 */
private fun libraryWorldBooks(
    json: JSONObject,
    coreDir: File,
    name: String,
    fsName: String,
    imported: Boolean,
    coreFileName: String?,
): List<WorldBookInfo> {
    val wbList = mutableListOf<WorldBookInfo>()
    val wbSeen = mutableSetOf<String>()
    fun addWorldBook(f: File, origin: String) {
        val key = f.nameWithoutExtension
        if (key in wbSeen) return
        wbSeen.add(key)
        runCatching {
            val j = JSONObject(f.readText())
            val (ec, entries) = parseEntries(j.optJSONObject("entries") ?: j.optJSONArray("entries"))
            wbList.add(WorldBookInfo(key, f.absolutePath, ec, entries, origin))
        }
    }
    fun resolveWorld(bookName: String): File? {
        if (bookName.isBlank()) return null
        val worldsDir = File(coreDir, "data/default-user/worlds")
        if (!worldsDir.exists()) return null
        File(worldsDir, bookName).takeIf { it.isFile }?.let { return it }
        File(worldsDir, "$bookName.json").takeIf { it.isFile }?.let { return it }
        return worldsDir.listFiles()?.firstOrNull {
            it.isFile && it.nameWithoutExtension.equals(bookName, ignoreCase = true)
        }
    }
    extractCharBook(json)?.let { book ->
        wbList.add(book.copy(origin = "内嵌")); wbSeen.add(book.name)
    }
    if (!imported) return wbList
    val boundNames = LinkedHashSet<String>()
    when (val worldField = cardData(json).optJSONObject("extensions")?.opt("world")) {
        is String -> if (worldField.isNotBlank()) boundNames.add(worldField)
        is JSONArray -> for (i in 0 until worldField.length()) {
            worldField.optString(i).takeIf { it.isNotBlank() }?.let { boundNames.add(it) }
        }
    }
    val settings = runCatching {
        JSONObject(File(coreDir, "data/default-user/settings.json").readText())
    }.getOrNull()
    worldInfoSelections(settings)?.optJSONArray("charLore")?.let { lore ->
        if (coreFileName != null) {
            val noExt = coreFileName.substringBeforeLast('.')
            for (i in 0 until lore.length()) {
                val entry = lore.optJSONObject(i) ?: continue
                val owner = entry.optString("name", "")
                val mine = owner.equals(coreFileName, ignoreCase = true) ||
                    owner.equals(noExt, ignoreCase = true)
                if (!mine) continue
                entry.optJSONArray("extraBooks")?.let { extras ->
                    for (j in 0 until extras.length()) {
                        extras.optString(j).takeIf { it.isNotBlank() }?.let { boundNames.add(it) }
                    }
                }
            }
        }
    }
    boundNames.forEach { bound -> resolveWorld(bound)?.let { addWorldBook(it, "角色绑定") } }
    val worldsDir = File(coreDir, "data/default-user/worlds")
    if (worldsDir.exists()) {
        worldsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.filter { f ->
                val n = f.nameWithoutExtension
                n !in wbSeen && relatedWorldName(n, name, fsName)
            }
            ?.forEach { addWorldBook(it, "名称关联") }
    }
    return wbList.distinctBy { it.name }
}


private fun getNotesFile(ctx: android.content.Context) = File(ctx.filesDir, "character_notes.json")

private fun loadAllNotes(ctx: android.content.Context): MutableMap<String, String> {
    val file = getNotesFile(ctx)
    if (!file.exists()) return mutableMapOf()
    return try {
        val json = JSONObject(file.readText())
        val map = mutableMapOf<String, String>()
        json.keys().forEachRemaining { map[it] = json.optString(it) }
        map
    } catch (_: Exception) { mutableMapOf() }
}

private fun saveNote(ctx: android.content.Context, charName: String, note: String) {
    val file = getNotesFile(ctx)
    val json = if (file.exists()) try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    json.put(charName, note)
    file.writeText(json.toString())
}

private fun deleteNote(ctx: android.content.Context, charName: String) {
    val file = getNotesFile(ctx)
    val json = if (file.exists()) try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    json.remove(charName)
    file.writeText(json.toString())
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExtensionsHubScreen(
    onBack: () -> Unit,
    onRefreshTavern: () -> Unit = {},
    onNavigateToFiles: (String) -> Unit = {},
    onNavigate: (String) -> Unit = {},
    asTab: Boolean = false
) {
    // The hub mirrors the data tab: a scrolling column of section labels and
    // grouped entry rows. Each section opens its own full page; no sub-tab row
    // and no duplicated title inside the page.
    var subPage by remember { mutableStateOf<String?>(null) }
    // The hub keeps its sub-pages as internal state, so without this the system back button fell
    // through to the nav graph and took the reader from a sub-page straight to the home tab.
    BackHandler(enabled = subPage != null) { subPage = null }
    val requestedTab by com.tavern.app.console.ExtensionsTabRequest.tab.collectAsState()
    // Library counts refresh every time the hub comes back to its landing list:
    // importing or deploying never leaves this composable, so a one-shot load kept
    // showing the numbers from before the change. The previous value stays visible
    // while the new one is read, which is what keeps the subtitle from blanking.
    val ctxHub = LocalContext.current
    var libraryCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(subPage) {
        if (subPage != null) return@LaunchedEffect
        libraryCounts = withContext(Dispatchers.IO) {
            val cards = loadLibrary(ctxHub, AssetExtractor.getCoreDir(ctxHub))
            cards.size to cards.count { it.deployedVersion != null }
        }
    }
    LaunchedEffect(requestedTab) {
        val index = requestedTab ?: return@LaunchedEffect
        // The hub's sub-pages are internal state, so a re-tap on the bottom
        // "extensions" tab must reset it here; the nav route never changes.
        subPage = when (index) {
            com.tavern.app.console.ExtensionsTabRequest.RESET_TAB -> null
            com.tavern.app.console.ExtensionsTabRequest.MEMORY_TAB -> "memory"
            else -> null
        }
        com.tavern.app.console.ExtensionsTabRequest.consume()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (subPage) {
            "plugins" -> ExtensionsScreen(
                onBack = { subPage = null },
                showHeader = true
            )
            "characters" -> Column(modifier = Modifier.fillMaxSize()) {
                PageTopBar(title = "角色", onBack = { subPage = null })
                CoreCharactersContent(onNavigateToFiles = onNavigateToFiles)
            }
            "memory" -> ChatMemoryScreen(onBack = { subPage = null })
            "library" -> LibraryContent(
                onBack = { subPage = null },
                onRefreshTavern = onRefreshTavern,
                onNavigateToFiles = onNavigateToFiles
            )
            else -> ExtensionsSections(
                onOpenPlugins = { subPage = "plugins" },
                onOpenCharacters = { subPage = "characters" },
                onOpenMemory = { subPage = "memory" },
                onOpenLibrary = { subPage = "library" },
                libraryCounts = libraryCounts,
            )
        }
    }
}

/**
 * The extensions landing view: one grouped list of four rectangle entries —
 * the same shape as the data tab rows. Each entry opens its own full page;
 * the entries themselves are the only tabs this screen has.
 */
@Composable
private fun ExtensionsSections(
    onOpenPlugins: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenLibrary: () -> Unit,
    libraryCounts: Pair<Int, Int>?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = AppSpacing.sm, bottom = AppSpacing.xxl)
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.lg)) {
            Text(
                "扩展",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(AppSpacing.xl))
        val counts = libraryCounts
        ListGroup {
            ListRow(
                icon = Icons.Outlined.Extension,
                title = "插件",
                subtitle = "安装、更新与启停核心插件",
                onClick = onOpenPlugins
            )
            ListRow(
                icon = Icons.Outlined.Face,
                title = "角色",
                subtitle = if (counts != null) "已部署 ${counts.second} 张角色卡"
                    else "已部署到核心的角色卡",
                onClick = onOpenCharacters
            )
            ListRow(
                icon = Icons.Outlined.StickyNote2,
                title = "记忆库",
                subtitle = "查看与整理每个聊天的记忆",
                onClick = onOpenMemory
            )
            ListRow(
                icon = Icons.Outlined.Folder,
                title = "仓库",
                subtitle = if (counts != null) "${counts.first} 张角色卡，${counts.second} 张已部署"
                    else "存储角色卡及全部版本",
                onClick = onOpenLibrary
            )
        }
    }
}

@Composable
private fun CoreCharactersContent(onNavigateToFiles: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharCardInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedChar by remember { mutableStateOf<CharCardInfo?>(null) }
    var selectedVersionInfo by remember { mutableStateOf<CharCardInfo?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            val coreDir = AssetExtractor.getCoreDir(ctx)
            val listed = withContext(Dispatchers.IO) { listCoreCharacters(coreDir) }
            if (listed.isNotEmpty()) {
                characters = listed
                loading = false
            }
            characters = withContext(Dispatchers.IO) { loadCoreCharacters(ctx, coreDir) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loading -> SkeletonList(modifier = Modifier.fillMaxSize())
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = AppSpacing.xs),
            ) {
                item {
                    ListGroup {
                        if (characters.isEmpty()) {
                            ListRow(
                                icon = Icons.Outlined.Face,
                                title = "核心里还没有角色卡",
                                subtitle = "在仓库导入并部署后会出现在这里"
                            )
                        } else {
                            characters.forEach { char ->
                                CharRow(char = char, onClick = { selectedChar = char })
                            }
                        }
                    }
                }
            }
        }
    }

    selectedChar?.let { char ->
        CharDetailDialog(char = char, ctx = ctx, onDismiss = { selectedChar = null }, onRefresh = { refresh() }, onNavigateToFiles = onNavigateToFiles)
    }
    selectedVersionInfo?.let { info ->
        CharDetailDialog(
            char = info,
            ctx = ctx,
            onDismiss = { selectedVersionInfo = null },
            onRefresh = { selectedVersionInfo = null; refresh() },
            onNavigateToFiles = onNavigateToFiles
        )
    }
}

/**
 * The character-card library view: search and filter on top, folders below,
 * folder contents in place. The add button imports a card from any source file.
 */
@Composable
private fun LibraryContent(
    onBack: () -> Unit = {},
    onRefreshTavern: () -> Unit = {},
    onNavigateToFiles: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<LibraryCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<LibraryCard?>(null) }
    var selectedVersionInfo by remember { mutableStateOf<CharCardInfo?>(null) }
    var renamingCard by remember { mutableStateOf<LibraryCard?>(null) }
    var libraryQuery by remember { mutableStateOf("") }
    var libraryFilter by remember { mutableStateOf(LibraryFilter.All) }
    // The shelf leaves composition while a folder is open, so the scroll offset has
    // to live above both branches; otherwise every return started at the top.
    val shelfScroll = rememberScrollState()
    // The picked file waiting for confirmation. The dialog is filled from the card itself —
    // name and version are already in the file — so importing is one read and one tap.
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    // A batch can be dozens of cards; both the count while it runs and the list of
    // what happened afterwards belong on screen, not in a toast that ends in a blink.
    var importProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var importReport by remember { mutableStateOf<BatchImportReport?>(null) }
    var highlightCardId by remember { mutableStateOf<String?>(null) }
    // The folder view is a level of this page, not a separate destination: without
    // this the system back button skipped it and dropped the reader on the hub root.
    BackHandler(enabled = selectedCard != null) { selectedCard = null }

    fun refresh() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                migrateLegacyLayout(ctx)
                val coreDir = AssetExtractor.getCoreDir(ctx)
                migrateCoreCards(ctx, coreDir)
                purgeStDefaultCards(ctx, coreDir)
            }
            val coreDir = AssetExtractor.getCoreDir(ctx)
            val loaded = withContext(Dispatchers.IO) { loadLibrary(ctx, coreDir) }
            cards = loaded
            val current = selectedCard
            if (current != null) {
                selectedCard = loaded.firstOrNull { it.id == current.id } ?: current
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(highlightCardId, cards, loading, importReport) {
        val target = highlightCardId ?: return@LaunchedEffect
        if (loading || importReport != null || cards.none { it.id == target }) return@LaunchedEffect
        libraryQuery = ""
        libraryFilter = LibraryFilter.All
        delay(3000)
        if (highlightCardId == target) highlightCardId = null
    }

    // Real import via SAF; the folder is chosen by the folded display name, then by
    // content features — never by the folder being viewed. One file keeps the
    // name and version confirmation; several files, or an archive, go straight
    // through and report what landed, because confirming each one defeats the point.
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val staged = withContext(Dispatchers.IO) { uris.mapNotNull { stageImport(ctx, it) } }
            if (staged.isEmpty()) {
                importing = false
                AppToast.show(ctx, "导入失败：没有读到文件", Toast.LENGTH_SHORT)
                return@launch
            }
            val single = staged.singleOrNull()?.takeIf { it.extension != "zip" }
            if (single != null) {
                val pending = withContext(Dispatchers.IO) {
                    cardJsonOf(single)?.let {
                        PendingImport(single, importDefaultsOf(it, single.name), uris.single())
                    }
                }
                importing = false
                if (pending == null) {
                    AppToast.show(ctx, "导入失败：文件不是可识别的角色卡", Toast.LENGTH_SHORT)
                } else {
                    pendingImport = pending
                }
                return@launch
            }
            val cards = withContext(Dispatchers.IO) {
                staged.flatMap { file ->
                    if (file.extension == "zip") {
                        val dest = File(ctx.cacheDir, "unzip-${System.nanoTime()}")
                        unpackCardArchive(file, dest).also { file.delete() }
                    } else {
                        listOf(file)
                    }
                }
            }
            if (cards.isEmpty()) {
                importing = false
                AppToast.show(ctx, "这里没有可导入的角色卡", Toast.LENGTH_SHORT)
                return@launch
            }
            importProgress = 0 to cards.size
            val report = withContext(Dispatchers.IO) {
                importCardsIntoLibrary(ctx, cards) { done, total ->
                    // Called from the import loop; the readout is UI state.
                    scope.launch(Dispatchers.Main) { importProgress = done to total }
                }
            }
            importProgress = null
            importing = false
            importReport = report
            highlightCardId = report.lines.lastOrNull { line ->
                line.entryId != null && (line.kind == ImportLineKind.NewFolder || line.kind == ImportLineKind.AddedVersion)
            }?.entryId
            refresh()
        }
    }

    val accentColor = LocalAppColors.current.action
    Column(modifier = Modifier.fillMaxSize()) {
        // The folder page carries its own top bar (back, title, archive/edit), so the
        // shelf header appears only on the folder list to avoid stacking two bars.
        if (selectedCard == null) {
            PageTopBar(title = "仓库", onBack = onBack)
        }
        Box(modifier = Modifier.weight(1f)) {
            val openCard = selectedCard
            if (openCard != null) {
            LibraryFolderView(
                card = openCard,
                allCards = cards,
                onBack = { selectedCard = null },
                onRename = { renamingCard = openCard },
                onOpenVersion = { version ->
                    scope.launch {
                        val info = withContext(Dispatchers.IO) {
                            loadVersionInfo(ctx, openCard, version, AssetExtractor.getCoreDir(ctx))
                        }
                        selectedVersionInfo = info
                    }
                },
                onDeleted = {
                    selectedCard = null
                    refresh()
                },
                onDeployed = { refresh() },
                onNavigateToFiles = onNavigateToFiles,
                onAddVersion = {
                    importLauncher.launch(IMPORT_MIME_TYPES)
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> Column(modifier = Modifier.fillMaxSize()) {
                        LibraryHeader(
                            query = libraryQuery,
                            onQuery = { libraryQuery = it },
                            filter = libraryFilter,
                            onFilter = { libraryFilter = it },
                        )
                        SkeletonList(modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(shelfScroll)
                            .padding(bottom = AppSpacing.xxl)
                    ) {
                        LibraryHeader(
                            query = libraryQuery,
                            onQuery = { libraryQuery = it },
                            filter = libraryFilter,
                            onFilter = { libraryFilter = it },
                        )
                        val query = libraryQuery.trim()
                        val filtered = cards.filter { card ->
                            val nameOk = query.isEmpty() || card.name.contains(query, ignoreCase = true)
                            val stateOk = when (libraryFilter) {
                                LibraryFilter.All -> true
                                LibraryFilter.Deployed -> card.deployedVersion != null
                                LibraryFilter.Undeployed -> card.deployedVersion == null
                            }
                            nameOk && stateOk
                        }
                        // Sorted by the folder name read as pinyin. Han-Latin ships with the
                        // platform's ICU, so no table is carried.
                        val ordered = filtered.sortedBy { cardSortKey(it.name) }
                        ListGroup {
                            if (filtered.isEmpty()) {
                                ListRow(
                                    icon = Icons.Outlined.Face,
                                    title = "没有匹配的角色卡",
                                    subtitle = if (cards.isEmpty()) "点右下角的 + 从文件导入" else "换个关键词或筛选条件试试"
                                )
                            } else {
                                ordered.forEach { card ->
                                    CardFolderRow(
                                        card = card,
                                        highlighted = highlightCardId == card.id && importReport == null,
                                        onClick = { selectedCard = card }
                                    )
                                }
                            }
                        }

                    }
                }
            }
            // FAB — hidden while an import is running
            if (!importing) {
                FloatingActionButton(
                    onClick = {
                        importLauncher.launch(IMPORT_MIME_TYPES)
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(AppSpacing.xl),
                    containerColor = LocalAppColors.current.action,
                    contentColor = LocalAppColors.current.onAction,
                    shape = CircleShape
                ) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(AppDimens.iconLg)) }
            }
        }
        }
    }

    renamingCard?.let { card ->
        RenameCardDialog(
            card = card,
            ctx = ctx,
            onDismiss = { renamingCard = null },
            onRenamed = { refresh() }
        )
    }
    importProgress?.let { (done, total) ->
        ImportProgressDialog(done = done, total = total)
    }

    importReport?.let { report ->
        ImportResultDialog(report = report, onDismiss = { importReport = null })
    }

    pendingImport?.let { pending ->
        ImportCardDialog(
            pending = pending,
            cards = cards,
            busy = importing,
            onDismiss = { pendingImport = null },
            onConfirm = { name, version ->
                pendingImport = null
                scope.launch {
                    importing = true
                    val outcome = withContext(Dispatchers.IO) {
                        try {
                            importCardIntoLibrary(ctx, pending.src, name, version)
                        } catch (_: Exception) {
                            ImportOutcome.ParseFailed
                        }
                    }
                    highlightCardId = when (outcome) {
                        is ImportOutcome.Ok -> outcome.entryId
                        is ImportOutcome.Duplicate -> outcome.entryId
                        ImportOutcome.ParseFailed -> null
                    }
                    importing = false
                    AppToast.show(ctx, importToast(outcome), Toast.LENGTH_SHORT)
                    refresh()
                }
            }
        )
    }
    selectedVersionInfo?.let { info ->
        CharDetailDialog(
            char = info,
            ctx = ctx,
            onDismiss = { selectedVersionInfo = null },
            onRefresh = { selectedVersionInfo = null; refresh() },
            onNavigateToFiles = onNavigateToFiles
        )
    }
}

private enum class LibraryFilter { All, Deployed, Undeployed }
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardFolderRow(
    card: LibraryCard,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val accent = LocalAppColors.current.accent
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    // Opacity drops to 0.2 and returns, twice, over two seconds: the keyframes
    // the tavern uses after an import, so the flash ends on its own.
    val flash = remember { Animatable(1f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            bringIntoViewRequester.bringIntoView()
            flash.snapTo(1f)
            flash.animateTo(
                1f,
                animationSpec = keyframes {
                    durationMillis = 2000
                    1f at 0
                    0.2f at 500
                    1f at 1000
                    0.2f at 1500
                },
            )
        } else {
            flash.snapTo(1f)
        }
    }
    val highlightModifier = modifier
        .bringIntoViewRequester(bringIntoViewRequester)
        .alpha(flash.value)
        .border(
            if (highlighted) 1.dp else 0.dp,
            accent.copy(alpha = 0.35f * (1f - flash.value)),
            AppCardShape,
        )
    val avatar = card.coverVersion
    // The deploy state is the line the shelf is about, matching the card rows in
    // the characters tab; the version count tells how full the folder is.
    val stateLine = listOf(
        "${card.versions.size} 个版本",
        if (card.deployedVersion != null) "已部署" else "未部署",
        formatCardSize(card.sizeBytes)
    ).joinToString("，")
    ListRow(
        icon = Icons.Outlined.Face,
        title = card.name,
        subtitle = stateLine,
        onClick = onClick,
        modifier = highlightModifier,
        leading = {
            if (avatar == null || avatar.format == "json") {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(AppCardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Face,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppDimens.iconMd)
                    )
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(File(avatar.file.absolutePath)).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(AppCardShape)
                )
            }
        }
    )
}

/**
 * Folder view of one character card: the renameable display name up top, every
 * imported version below. A version is either deployed to the core or kept in
 * the folder only; the deploy action switches which one the core lists.
 */
@Composable
private fun LibraryFolderView(
    card: LibraryCard,
    allCards: List<LibraryCard>,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onOpenVersion: (LibraryVersion) -> Unit,
    onDeleted: () -> Unit,
    onDeployed: () -> Unit,
    onNavigateToFiles: (String) -> Unit,
    onAddVersion: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var menuVersion by remember { mutableStateOf<LibraryVersion?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryVersion?>(null) }
    var versionEditTarget by remember { mutableStateOf<LibraryVersion?>(null) }
    var moveTarget by remember { mutableStateOf<LibraryVersion?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryVersion?>(null) }
    var exportTarget by remember { mutableStateOf<File?>(null) }
    // Deploying replaces what every chat of this character reads, so it asks first.
    var deployTarget by remember { mutableStateOf<LibraryVersion?>(null) }
    // Folders filled before the duplicate check compared content can hold two rows
    // of one card; the count decides whether the cleanup entry shows at all.
    var duplicateCount by remember(card.id, card.versions.size) { mutableStateOf(0) }
    LaunchedEffect(card.id, card.versions.size) {
        duplicateCount = withContext(Dispatchers.IO) { duplicateVersionIds(ctx, card.id).size }
    }

    // Whole-folder export: the archive the batch import reads back, so a folder can
    // move between devices without going through the core.
    val exportArchiveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(ctx.cacheDir, "export-${System.nanoTime()}.zip")
                    val count = exportCardsToZip(card, temp)
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { input -> input.copyTo(out) }
                    }
                    temp.delete()
                    count
                }.getOrDefault(0)
            }
            AppToast.show(
                ctx,
                if (written > 0) "已导出 $written 个版本" else "导出失败",
                Toast.LENGTH_SHORT
            )
        }
    }

    val exportPngLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val body = exportTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        body.inputStream().use { input -> input.copyTo(out) }
                    } != null
                } catch (_: Exception) { false }
            }
            AppToast.show(ctx, if (ok) "已导出「${card.name}」" else "导出失败", Toast.LENGTH_SHORT)
        }
    }

    val exportJsonLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val body = exportTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        body.inputStream().use { input -> input.copyTo(out) }
                    } != null
                } catch (_: Exception) { false }
            }
            AppToast.show(ctx, if (ok) "已导出「${card.name}」" else "导出失败", Toast.LENGTH_SHORT)
        }
    }

    fun doExport(v: LibraryVersion) {
        val body = versionBodyFile(ctx, card.id, v.id)
        if (body == null) {
            AppToast.show(ctx, "导出失败：版本文件不存在", Toast.LENGTH_SHORT)
            return
        }
        exportTarget = body
        if (v.format == "json") {
            exportJsonLauncher.launch("${card.name}.json")
        } else {
            exportPngLauncher.launch("${card.name}.png")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(card.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${card.versions.size} 个版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // One file per deployed version, because the core names a chat directory after
                // it. Naming them here is how the reader tells which ones are already in the core.
                val deployedNow = card.deployedVersions
                Text(
                    when {
                        deployedNow.isEmpty() -> "未部署"
                        deployedNow.size == 1 ->
                            "已部署到 ${deployedNow.first().coreFileName ?: card.name}"
                        else -> "已部署 ${deployedNow.size} 个版本"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.Edit, "重命名", tint = LocalAppColors.current.action)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel("版本") }
                item {
                    ListGroup {
                        card.versions.forEach { v ->
                            ListRow(
                                icon = Icons.Outlined.Face,
                                title = v.name,
                                // The pinned cover is marked where the reader looks for
                                // it, next to the version number rather than in the menu.
                                subtitle = listOf(
                                    v.version,
                                    if (card.coverVersionId == v.id) "封面" else "",
                                    formatCardSize(v.sizeBytes)
                                ).filter { it.isNotBlank() }.joinToString("，"),
                                onClick = { onOpenVersion(v) },
                                leading = {
                                    if (v.format == "json") {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(AppCardShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Outlined.Face,
                                                null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(AppDimens.iconMd)
                                            )
                                        }
                                    } else {
                                        AsyncImage(
                                            model = ImageRequest.Builder(ctx).data(File(v.file.absolutePath)).crossfade(true).build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(AppCardShape)
                                        )
                                    }
                                },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (v.imported) {
                                            Text("已部署", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.action)
                                        } else {
                                            TextButton(
                                                onClick = { deployTarget = v },
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("部署", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.action)
                                            }
                                        }
                                        // File-manager actions for this version: export,
                                        // rename, move to another card folder, delete.
                                        Box {
                                            IconButton(onClick = { menuVersion = v }) {
                                                Icon(Icons.Outlined.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd))
                                            }
                                            DropdownMenu(
                                                expanded = menuVersion == v,
                                                onDismissRequest = { menuVersion = null },
                                                containerColor = MaterialTheme.colorScheme.surface,
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("导出") },
                                                    onClick = {
                                                        menuVersion = null
                                                        doExport(v)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("重命名") },
                                                    onClick = {
                                                        menuVersion = null
                                                        renameTarget = v
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("编辑版本号") },
                                                    onClick = {
                                                        menuVersion = null
                                                        versionEditTarget = v
                                                    }
                                                )
                                                // A folder with one version has nothing to
                                                // choose between, so the action is hidden
                                                // rather than shown and useless.
                                                if (card.versions.size > 1) {
                                                    val pinned = card.coverVersionId == v.id
                                                    DropdownMenuItem(
                                                        text = { Text(if (pinned) "取消封面" else "设为封面") },
                                                        onClick = {
                                                            menuVersion = null
                                                            scope.launch {
                                                                val ok = withContext(Dispatchers.IO) {
                                                                    setCoverVersion(
                                                                        ctx,
                                                                        card.id,
                                                                        if (pinned) null else v.id
                                                                    )
                                                                }
                                                                AppToast.show(
                                                                    ctx,
                                                                    when {
                                                                        !ok -> "封面没有保存"
                                                                        pinned -> "已恢复为最新版本作封面"
                                                                        else -> "已把「${v.name}」设为封面"
                                                                    },
                                                                    Toast.LENGTH_SHORT
                                                                )
                                                                if (ok) onDeployed()
                                                            }
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("移动到其他角色卡") },
                                                    onClick = {
                                                        menuVersion = null
                                                        moveTarget = v
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("删除") },
                                                    onClick = {
                                                        menuVersion = null
                                                        deleteTarget = v
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                if (duplicateCount > 0) {
                    item {
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val removed = withContext(Dispatchers.IO) {
                                        removeDuplicateVersions(ctx, card.id)
                                    }
                                    duplicateCount = 0
                                    AppToast.show(ctx, "已清理 $removed 个重复版本", Toast.LENGTH_SHORT)
                                    onDeployed()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.page),
                            shape = AppCardShape
                        ) {
                            Icon(Icons.Outlined.DeleteForever, null, modifier = Modifier.size(AppDimens.iconMd))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("清理重复版本（$duplicateCount）", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    OutlinedButton(
                        onClick = { exportArchiveLauncher.launch("${card.name}.zip") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.page),
                        shape = AppCardShape
                    ) {
                        Icon(Icons.Outlined.Download, null, modifier = Modifier.size(AppDimens.iconMd))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出为卡包（zip）", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.page),
                        colors = appOutlinedButtonColors(contentColor = LocalAppColors.current.error),
                        border = appOutlinedButtonBorder(contentColor = MaterialTheme.colorScheme.outlineVariant),
                        shape = AppCardShape
                    ) {
                        Icon(Icons.Outlined.DeleteForever, null, modifier = Modifier.size(AppDimens.iconMd))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除角色卡「${card.name}」（含所有版本）", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xxl))
                }
            }
            // The folder's add button; routing still follows the card name and
            // features, never this folder.
            FloatingActionButton(
                onClick = onAddVersion,
                modifier = Modifier.align(Alignment.BottomEnd).padding(AppSpacing.xl),
                containerColor = LocalAppColors.current.action,
                contentColor = LocalAppColors.current.onAction,
                shape = CircleShape
            ) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(AppDimens.iconLg)) }
        }
    }

    deployTarget?.let { v ->
        DeployConfirmDialog(
            card = card,
            version = v,
            onDismiss = { deployTarget = null },
            onConfirm = {
                deployTarget = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        deployVersion(ctx, card, v, AssetExtractor.getCoreDir(ctx))
                    }
                    AppToast.show(
                        ctx,
                        if (ok) "已部署「${v.name}」，刷新核心后可以看到" else "部署失败",
                        Toast.LENGTH_SHORT
                    )
                    if (ok) {
                        com.tavern.app.webview.TavernRefresh.request()
                        onDeployed()
                    }
                }
            }
        )
    }

    if (showDeleteConfirm) {
        DeleteEntryConfirmDialog(
            card = card,
            ctx = ctx,
            onDismiss = { showDeleteConfirm = false },
            onDeleted = {
                showDeleteConfirm = false
                onDeleted()
            }
        )
    }

    renameTarget?.let { v ->
        RenameVersionDialog(
            version = v,
            cardId = card.id,
            ctx = ctx,
            onDismiss = { renameTarget = null },
            onRenamed = { renameTarget = null; onDeployed() }
        )
    }
    versionEditTarget?.let { v ->
        EditVersionLabelDialog(
            version = v,
            cardId = card.id,
            ctx = ctx,
            onDismiss = { versionEditTarget = null },
            onSaved = { versionEditTarget = null; onDeployed() }
        )
    }
    moveTarget?.let { v ->
        MoveVersionDialog(
            version = v,
            sourceCard = card,
            allCards = allCards,
            ctx = ctx,
            onDismiss = { moveTarget = null },
            onMoved = { moveTarget = null; onDeployed() }
        )
    }
    deleteTarget?.let { v ->
        DeleteVersionConfirmDialog(
            version = v,
            card = card,
            ctx = ctx,
            onDismiss = { deleteTarget = null },
            onDeleted = { deleteTarget = null; onDeployed() }
        )
    }
}

@Composable
private fun RenameCardDialog(
    card: LibraryCard,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
) {
    var text by remember { mutableStateOf(card.name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("重命名角色卡", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "改名只改这里显示的名称。核心里的文件名不变，已有聊天仍按原文件关联。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("角色卡名称") },
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = renameCard(ctx, card.id, text.trim())
                AppToast.show(ctx, if (ok) "已重命名" else "重命名失败：名称无效", Toast.LENGTH_SHORT)
                if (ok) onRenamed()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Asks before replacing the core copy.
 *
 * A deploy overwrites the file every existing chat of this character reads, and
 * the version being replaced is not always the one the reader has in mind. The
 * dialog names the file that will be written, lists what changed since the
 * deployed version, and keeps the changes no deploy can undo apart from the rest.
 */
@Composable
private fun DeployConfirmDialog(
    card: LibraryCard,
    version: LibraryVersion,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ctx = LocalContext.current
    val deployed = card.deployedVersion
    // Already in the core means this deploy rewrites the file its chats are filed under;
    // otherwise it adds a character and leaves every existing chat where it is.
    val inCore = version.imported && version.coreFileName != null
    // Reading two card bodies and resolving the target name is file and JSON work, so it runs off
    // the frame thread and the dialog fills in when it returns.
    var report by remember(version.id) { mutableStateOf<Triple<List<CardChange>, Boolean, String>?>(null) }
    LaunchedEffect(version.id) {
        report = withContext(Dispatchers.IO) {
            val target = deployTargetName(ctx, card, version)
            val after = runCatching { cardJsonOf(version.file) }.getOrNull()
            if (after == null) {
                Triple(emptyList<CardChange>(), false, target)
            } else {
                val before = deployed?.let { old -> runCatching { cardJsonOf(old.file) }.getOrNull() }
                Triple(compareCardBodies(before, after), hasVariableRisk(before, after), target)
            }
        }
    }
    val changes = report?.first
    val risk = report?.second == true
    val target = report?.third.orEmpty()
    val shown = changes?.take(6) ?: emptyList()
    val hidden = (changes?.size ?: 0) - shown.size

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("部署「${version.name}」", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    when {
                        target.isEmpty() -> "正在核对这一版要写进核心的文件…"
                        inCore -> "会覆盖核心里同名的角色卡（$target），已有聊天继续使用它。"
                        else -> "会作为另一个角色写进核心（$target）：它的聊天从空白开始。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (risk && inCore) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "这一版的变量脚本或初始状态与上一版不同，已有聊天里保存的状态可能不兼容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAppColors.current.error
                    )
                    Text(
                        "部署不能修正聊天里已经存下的旧数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    report == null -> Text(
                        "正在核对这一版和核心里的差别…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    changes != null && changes.isEmpty() && deployed != null -> Text(
                        if (inCore) "和核心里这一份一样，部署会重写同一份文件。"
                        else "和已部署的「${deployed.name}」内容相同。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    shown.isNotEmpty() -> {
                        Text(
                            "和已部署的「${deployed?.name}」相比：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        shown.forEach { change ->
                            Text(
                                "· ${change.label}" + if (change.detail.isBlank()) "" else "：${change.detail}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (hidden > 0) {
                            Text(
                                "还有 $hidden 处不同",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("部署") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** Confirms deletion of a whole card folder, cleaning core state of the deployed version. */
@Composable
private fun DeleteEntryConfirmDialog(
    card: LibraryCard,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val coreDir = AssetExtractor.getCoreDir(ctx)
    var deployedInfo by remember { mutableStateOf<CharCardInfo?>(null) }
    var chatCount by remember { mutableStateOf(0) }
    LaunchedEffect(card.deployedVersion?.id) {
        withContext(Dispatchers.IO) {
            deployedInfo = card.deployedVersion?.let { v ->
                loadVersionInfo(ctx, card, v, coreDir)
            }
            val chatsDir = File(coreDir, "data/default-user/chats")
            // Every deployed version has its own chat directory; a folder can hold several.
            val names = card.deployedVersions
                .mapNotNull { it.coreFileName?.substringBeforeLast('.') }
                .ifEmpty { listOf(card.name) }
                .distinct()
            chatCount = names.sumOf { label ->
                File(chatsDir, label).walkTopDown()
                    .count { it.isFile && it.extension.equals("jsonl", ignoreCase = true) }
            }
        }
    }

    val parts = mutableListOf<String>()
    parts.add(if (deployedInfo?.imported == true) "• 角色卡「${card.name}」（仓库与核心，${card.versions.size} 个版本）" else "• 角色卡「${card.name}」（仅仓库，${card.versions.size} 个版本）")
    if (deployedInfo?.imported == true) {
        parts.add("• 聊天记录（$chatCount 个文件）")
        parts.add("• 这些聊天整理出来的记忆")
        parts.add("• 仅被该角色卡引用的世界书（其他角色卡引用的会保留）")
        if (deployedInfo!!.regexScripts.isNotEmpty() || deployedInfo!!.helperScripts.isNotEmpty()) {
            parts.add("• 随卡带入的正则与核心助手脚本（含核心中已导入的副本）")
        }
    }
    parts.add("• 玩家备注")
    val warnMsg = "以下内容将被永久删除，此操作不可撤销：\n\n${parts.joinToString("\n")}"

    ConfirmDialog(
        title = "删除角色卡",
        message = warnMsg,
        confirmText = "确认删除",
        dismissText = "取消",
        onConfirm = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    var delErrors = 0
                    var selectionTouched = false
                    val info = deployedInfo
                    if (info != null && info.imported) {
                        val (e, s) = purgeCardFromCoreState(ctx, info, coreDir)
                        delErrors += e
                        selectionTouched = s
                        // The chats are gone, so their archives are cleared with the card. This dialog does not show
                        // memory, so nothing else would report a sidecar left behind.
                        val port = AgentClient.findPort()
                        if (port != null) AgentClient.memoryCleanOrphans(port)
                    }
                    if (!removeLibraryCard(ctx, card, coreDir)) delErrors++
                    deleteNote(ctx, card.name)
                    withContext(Dispatchers.Main) {
                        val msg = when {
                            delErrors > 0 -> "已删除「${card.name}」，有 $delErrors 项需手动处理"
                            selectionTouched -> "已删除「${card.name}」及相关数据，已刷新核心页面"
                            else -> "已删除「${card.name}」及相关数据"
                        }
                        if (info?.imported == true) {
                            com.tavern.app.webview.TavernRefresh.request()
                        }
                        AppToast.show(ctx, msg, Toast.LENGTH_SHORT)
                        onDeleted()
                    }
                }
            }
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun CharRow(char: CharCardInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    // A row rather than a tile: the avatar is the leading mark, the name is the
    // title and the file it came from is the summary, so a card list reads in the
    // same shape as every other list in the console and stops wasting the width a
    // two-column grid leaves empty below two characters.
    ListRow(
        icon = Icons.Outlined.Face,
        title = char.name,
        subtitle = listOf(
            char.version.takeIf { it.isNotBlank() },
            char.fsName.takeIf { it.isNotBlank() && !it.equals(char.name, ignoreCase = true) },
            // The installed flag is the state the shelf is about: an uninstalled
            // card exists here only, an installed one also lives in the core.
            "未导入".takeIf { !char.imported },
            // On the row, because the reader compares this list with the tavern's and the
            // difference has to be explained where it is seen.
            "核心不显示这张卡".takeIf { !char.coreReadable },
        ).filterNotNull().joinToString("，").ifBlank { null },
        onClick = onClick,
        modifier = modifier,
        leading = {
            // A json card has no avatar; the mark keeps the row in shape.
            if (char.format == "json") {
                Icon(
                    Icons.Outlined.Face,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(AppDimens.iconBadge)
                        .clip(RoundedCornerShape(AppRadius.sm))
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(File(char.avatarPath)).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(AppDimens.iconBadge)
                        .clip(RoundedCornerShape(AppRadius.sm))
                )
            }
        },
        trailing = { TrailingChevron() }
    )
}

@Composable
private fun CharDetailDialog(char: CharCardInfo, ctx: android.content.Context, onDismiss: () -> Unit, onRefresh: () -> Unit = {}, onNavigateToFiles: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val motion = LocalFluentMotion.current
    val avatar = remember(char.avatarPath) {
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(char.avatarPath, opts)?.asImageBitmap()
        } catch (_: Exception) { null }
    }

    var notesMap by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(char.name) {
        notesMap = withContext(Dispatchers.IO) { loadAllNotes(ctx) }
    }
    var noteText by remember { mutableStateOf(notesMap[char.name] ?: "") }
    var isEditingNote by remember { mutableStateOf(false) }

    // Real export via SAF (user chooses destination), not a share intent.
    var showExportMenu by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) } // "json" | "png"
    // Two launchers each pin the correct MIME so exports don't become .bin.
    val jsonExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> scope.launch { handleExportResult(uri, "json", char, ctx) } }
    val pngExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> scope.launch { handleExportResult(uri, "png", char, ctx) } }

    // Chat-log export (real SAF export, not share).
    var pendingChatFile by remember { mutableStateOf<File?>(null) }
    val chatExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/jsonl")
    ) { uri ->
        val src = pendingChatFile
        pendingChatFile = null
        if (uri != null && src != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        } != null
                    } catch (_: Exception) { false }
                }
                AppToast.show(ctx, if (ok) "已导出聊天记录" else "导出失败", Toast.LENGTH_SHORT)
            }
        }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        val coreDir = AssetExtractor.getCoreDir(ctx)
        // Chat count is only meaningful for deployed cards; the library entry
        // itself cannot be seen from the tavern and needs no explanation.
        val chatCount = remember(char.avatarPath) {
            if (!char.imported) 0 else {
                val chatsDir = File(coreDir, "data/default-user/chats")
                listOf(char.name, char.fsName).distinct()
                    .sumOf { label ->
                        File(chatsDir, label).walkTopDown()
                            .count { it.isFile && it.extension.equals("jsonl", ignoreCase = true) }
                    }
            }
        }
        // Two removals share this button: a core-only card is deleted, a deployed library card only
        // leaves the core. The dialog lists both.
        val fromLibrary = char.libraryId.isNotBlank()
        val parts = mutableListOf<String>()
        if (fromLibrary) {
            // Removes what the tavern holds for this character. The library copy stays.
            parts.add("• 角色卡文件与缩略图")
            parts.add("• 聊天记录（$chatCount 个文件）")
            parts.add("• 这些聊天整理出来的记忆")
            parts.add("• 仅被这张卡引用的世界书（其他卡引用的会保留）")
            if (char.regexScripts.isNotEmpty() || char.helperScripts.isNotEmpty()) {
                parts.add("• 随卡带入的正则与核心助手脚本（含已导入的副本）")
            }
            parts.add("• 玩家备注")
        } else {
            parts.add(if (char.imported) "• 版本「${char.name}」（已部署到核心）" else "• 版本「${char.name}」（仅仓库）")
            if (char.imported) {
                parts.add("• 聊天记录（$chatCount 个文件）")
                parts.add("• 仅被该角色卡引用的世界书（其他角色卡引用的会保留）")
                // Both live inside the card and, for the copies the tavern already
                // imported, are removed with the character-scoped settings.
                if (char.regexScripts.isNotEmpty() || char.helperScripts.isNotEmpty()) {
                    parts.add("• 随卡带入的正则与核心助手脚本（含核心中已导入的副本）")
                }
            }
            parts.add("• 玩家备注")
        }
        val warnMsg = if (fromLibrary) {
            // Only the paths named here are removed; everything else survives.
            "会从酒馆里删除：\n\n${parts.joinToString("\n")}\n\n仓库里的卡片和版本会保留，可以重新部署。"
        } else {
            "以下内容将被永久删除，此操作不可撤销：\n\n${parts.joinToString("\n")}"
        }

        ConfirmDialog(
            title = if (fromLibrary) "取消部署此角色卡" else "删除此版本",
            message = warnMsg,
            confirmText = if (fromLibrary) "取消部署" else "确认删除",
            dismissText = "取消",
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        var delErrors = 0
                        var selectionTouched = false

                        if (fromLibrary) {
                            val libCards = loadLibrary(ctx, coreDir)
                            val card = libCards.firstOrNull { it.id == char.libraryId }
                            val version = card?.versions?.firstOrNull { it.id == char.libraryVersionId }
                            if (card == null || version == null) {
                                delErrors++
                            } else {
                                // Same cleaning as delete, plus the flag. The library folder is not touched.
                                val (e, s) = purgeCardFromCoreState(ctx, char, coreDir)
                                delErrors += e
                                selectionTouched = s
                                if (!undeployVersion(ctx, card, version, coreDir)) delErrors++
                                deleteNote(ctx, char.name)
                                val port = AgentClient.findPort()
                                if (port != null) AgentClient.memoryCleanOrphans(port)
                            }
                        } else {
                            if (char.imported) {
                                val (e, s) = purgeCardFromCoreState(ctx, char, coreDir)
                                delErrors += e
                                selectionTouched = s
                            }
                            // Core browser card: no library version, remove the
                            // core copy and its cached thumbnails directly.
                            if (char.coreFileName.isNotBlank()) {
                                val coreFile = File(File(coreDir, "data/default-user/characters"), char.coreFileName)
                                if (coreFile.isFile && !coreFile.delete()) delErrors++
                                val userDir = File(coreDir, "data/default-user")
                                listOf(char.coreFileName, char.coreFileName.removeSuffix(".png") + ".png")
                                    .distinct().forEach { thumbName ->
                                        listOf("avatar", "").forEach { sub ->
                                            val thumb = if (sub.isEmpty()) File(userDir, "thumbnails/$thumbName")
                                            else File(userDir, "thumbnails/$sub/$thumbName")
                                            if (thumb.isFile && !thumb.delete()) delErrors++
                                        }
                                    }
                            }
                            deleteNote(ctx, char.name)
                        }

                        withContext(Dispatchers.Main) {
                            onDismiss()
                            val msg = when {
                                delErrors > 0 -> "已处理「${char.name}」，有 $delErrors 项需手动处理"
                                fromLibrary -> "已取消部署「${char.name}」，酒馆里已清空，仓库里还留着"
                                selectionTouched -> "已删除「${char.name}」及相关数据，已刷新核心页面"
                                else -> "已删除「${char.name}」及相关数据"
                            }
                            if (char.imported) {
                                com.tavern.app.webview.TavernRefresh.request()
                            }
                            AppToast.show(ctx, msg, Toast.LENGTH_SHORT)
                            onRefresh()
                        }
                    }
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = AppCardShape, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (avatar != null) Image(bitmap = avatar, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(CircleShape))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(char.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            if (char.creator.isNotBlank()) Text("创作者：${char.creator}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (char.tags.isNotEmpty()) Text(char.tags.joinToString("、"), style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }

                // Scrollable content
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                    if (!char.coreReadable) {
                        // For a deployed library version the unreadable file is the
                        // core copy, not the library card body; the repair must act
                        // on the file the tavern scans.
                        val charsDirNow = File(
                            AssetExtractor.getCoreDir(ctx),
                            "data/default-user/characters"
                        )
                        val visibleFile = if (char.libraryId.isNotBlank() && char.coreFileName.isNotBlank()) {
                            File(charsDirNow, char.coreFileName)
                        } else {
                            File(char.avatarPath)
                        }
                        val fileName = visibleFile.name
                        val nested = visibleFile.parentFile?.name != "characters"
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(AppRadius.sm),
                            // Neutral plate: the heading already says the card will not be
                            // listed, so an amber fill only adds colour to a layout that is
                            // otherwise quiet.
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "核心不会列出这张卡",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (char.format == "json") {
                                        "它是 json 文件。核心只认角色卡目录（characters）里小写的 .png，json 不会出现在列表里。"
                                    } else if (nested) {
                                        "它放在子目录里。核心只读取 characters 目录这一层，所以文件还在、列表里没有。"
                                    } else {
                                        "它的文件名是「$fileName」，扩展名不是小写的 .png。核心只认小写的 .png，所以文件还在、列表里没有。"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                // Renaming a json to .png would corrupt it; the repair
                                // path is only meaningful for png files.
                                if (char.format != "json") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SoftActionButton(
                                        text = if (nested) "移到角色卡目录" else "改成小写 .png",
                                    onClick = {
                                        scope.launch {
                                            val fixed = withContext(Dispatchers.IO) {
                                                repairCardForCore(visibleFile, charsDirNow)
                                            }
                                            AppToast.show(
                                                ctx,
                                                if (fixed != null) {
                                                    "已修复，刷新核心页面后可以看到这张卡"
                                                } else {
                                                    "没有修复：目标位置已有同名文件"
                                                },
                                                Toast.LENGTH_LONG
                                            )
                                            // The dialog holds the pre-repair card: leaving it
                                            // open would keep showing "not listed" right after
                                            // the file was moved, so the list is what the reader
                                            // comes back to.
                                            if (fixed != null) {
                                                onRefresh()
                                                onDismiss()
                                            }
                                        }
                                    }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    // Basic info — collapsible
                    val fields = listOf(
                        "描述" to char.description,
                        "性格" to char.personality,
                        "场景" to char.scenario,
                        "开场白" to char.firstMessage
                    ).filter { it.second.isNotBlank() }
                    fields.forEachIndexed { idx, (label, value) ->
                        CollapsibleSection(label, value)
                        if (idx < fields.size - 1) Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Notes
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("玩家备注", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                        Spacer(modifier = Modifier.weight(1f))
                        if (!isEditingNote && noteText.isNotBlank())
                            TextButton(onClick = { isEditingNote = true }) { Text("编辑", color = LocalAppColors.current.accent, style = MaterialTheme.typography.bodySmall) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isEditingNote) {
                        OutlinedTextField(value = noteText, onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("添加备注") },
                            // Bounded so it cannot grow past the space the keyboard leaves: an
                            // unbounded field hides its own caret behind the keyboard.
                            maxLines = 4,
                            colors = appTextFieldColors(),
                            textStyle = LocalTextStyle.current.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { isEditingNote = false; noteText = notesMap[char.name] ?: "" })
                            { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                isEditingNote = false
                                if (noteText.isBlank()) { deleteNote(ctx, char.name); notesMap = notesMap.toMutableMap().also { it.remove(char.name) } }
                                else { saveNote(ctx, char.name, noteText); notesMap = notesMap.toMutableMap().also { it[char.name] = noteText } }
                                AppToast.show(ctx, "备注已保存", Toast.LENGTH_SHORT)
                            }) { Text("保存", color = LocalAppColors.current.accent, style = MaterialTheme.typography.bodySmall) }
                        }
                    } else if (noteText.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(noteText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, 
                                modifier = Modifier.padding(12.dp).fillMaxWidth())
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.sm)).clickable { isEditingNote = true }) {
                            Text("点按添加备注", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp).fillMaxWidth())
                        }
                    }

                    // World books (character-specific) — two-level like regex
                    Spacer(modifier = Modifier.height(24.dp))
                    var wbSectionExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.sm))
                    ) {
                        // Header and entries run edge to edge, with the inset applied inside
                        // the clickable: padding outside it shrinks the ripple, so the touch
                        // feedback covers only part of the card.
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AppDimens.controlHeight)
                                    .clip(RoundedCornerShape(AppRadius.sm))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = pressHighlightIndication(),
                                        onClick = { wbSectionExpanded = !wbSectionExpanded }
                                    )
                                    .padding(horizontal = 12.dp)
                                    .heightIn(min = AppDimens.controlHeight),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Public, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("世界书", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                Spacer(modifier = Modifier.weight(1f))
                                if (char.worldBooks.isNotEmpty()) Text("${char.worldBooks.size} 本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                ExpandChevron(
                                    expanded = wbSectionExpanded, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd)
                                )
                            }
                            AnimatedVisibility(visible = wbSectionExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                if (char.worldBooks.isEmpty()) {
                                    Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp))
                                } else {
                                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                                        char.worldBooks.forEach { wb ->
                                            var wbExpanded by remember { mutableStateOf(false) }
                                            Surface(
                                                shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                                                // Modifier.clickable rather than Surface(onClick): the clickable
                                                // overload draws Material's own ripple, which grows into a disc
                                                // behind this card once it is expanded.
                                                modifier = Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(AppRadius.sm))
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = pressHighlightIndication(),
                                                        onClick = { wbExpanded = !wbExpanded }
                                                    )
                                            ) {
                                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(wb.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.weight(1f))
                                                        if (wb.origin.isNotBlank()) Text(wb.origin, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        if (wb.entryCount > 0) Text("  ${wb.entryCount} 条目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        ExpandChevron(
                                                            expanded = wbExpanded, contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm)
                                                        )
                                                    }
                                                    AnimatedVisibility(visible = wbExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                                        if (wb.entries.isNotEmpty()) {
                                                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                                                wb.entries.forEach { entry ->
                                                                    var entryExpanded by remember { mutableStateOf(false) }
                                                                    Surface(
                                                                        shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                                                                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                                                            .clip(RoundedCornerShape(AppRadius.sm))
                                                                            .clickable(
                                                                                interactionSource = remember { MutableInteractionSource() },
                                                                                indication = pressHighlightIndication(),
                                                                                onClick = { entryExpanded = !entryExpanded }
                                                                            )
                                                                    ) {
                                                                        Column(modifier = Modifier.padding(8.dp)) {
                                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                                Text(entry.comment, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                    modifier = Modifier.weight(1f))
                                                                                ExpandChevron(
                                                                                    expanded = entryExpanded, contentDescription = null,
                                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm)
                                                                                )
                                                                            }
                                                                            AnimatedVisibility(visible = entryExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                                                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                                                                    if (entry.keys.isNotEmpty() || entry.secondaryKeys.isNotEmpty()) {
                                                                                        val allKeys = entry.keys + entry.secondaryKeys
                                                                                        Text(allKeys.joinToString(", "), style = MaterialTheme.typography.labelSmall,
                                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                        Spacer(modifier = Modifier.height(4.dp))
                                                                                    }
                                                                                    if (entry.content.isNotBlank()) {
                                                                                        Text(entry.content.take(300), style = MaterialTheme.typography.labelSmall,
                                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            Text("无条目数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(top = 8.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Regex (character-specific, from card) — two-level like world books
                    Spacer(modifier = Modifier.height(24.dp))
                    if (char.helperScripts.isNotEmpty()) {
                        var helperExpanded by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(AppRadius.sm))
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = AppDimens.controlHeight)
                                        .clip(RoundedCornerShape(AppRadius.sm))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = pressHighlightIndication(),
                                            onClick = { helperExpanded = !helperExpanded }
                                        )
                                        .padding(horizontal = 12.dp)
                                        .heightIn(min = AppDimens.controlHeight),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Extension, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("核心助手脚本", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text("${char.helperScripts.size} 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    ExpandChevron(
                                        expanded = helperExpanded, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd)
                                    )
                                }
                                AnimatedVisibility(visible = helperExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                                        char.helperScripts.forEach { hs ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            ) {
                                                Text(hs.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f))
                                                Text(
                                                    if (hs.enabled) "启用" else "关闭",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    var regexExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.sm))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AppDimens.controlHeight)
                                    .clip(RoundedCornerShape(AppRadius.sm))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = pressHighlightIndication(),
                                        onClick = { regexExpanded = !regexExpanded }
                                    )
                                    .padding(horizontal = 12.dp)
                                    .heightIn(min = AppDimens.controlHeight),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Code, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正则", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                Spacer(modifier = Modifier.weight(1f))
                                if (char.regexScripts.isNotEmpty()) Text("${char.regexScripts.size} 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                ExpandChevron(
                                    expanded = regexExpanded, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd)
                                )
                            }
                            AnimatedVisibility(visible = regexExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                if (char.regexScripts.isEmpty()) {
                                    Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp))
                                } else {
                                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                                        char.regexScripts.forEach { rs ->
                                            var rsExpanded by remember { mutableStateOf(false) }
                                            Surface(
                                                shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(AppRadius.sm))
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = pressHighlightIndication(),
                                                        onClick = { rsExpanded = !rsExpanded }
                                                    )
                                            ) {
                                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(rs.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.weight(1f))
                                                        if (!rs.enabled) Text("关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        if (!rsExpanded && rs.findRegex.isNotBlank())
                                                            Text(rs.findRegex.take(12) + if (rs.findRegex.length > 12) "…" else "",
                                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                                        ExpandChevron(
                                                            expanded = rsExpanded, contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm)
                                                        )
                                                    }
                                                    AnimatedVisibility(visible = rsExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                                        Column(modifier = Modifier.padding(top = 8.dp)) {
                                                            if (rs.findRegex.isNotBlank()) {
                                                                Text("匹配", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                Text(rs.findRegex, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                            }
                                                            if (rs.replaceString.isNotBlank()) {
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                Text("替换", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                Text(rs.replaceString.take(200), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Chat & Group sessions only exist for installed cards.
                    if (char.imported) {
                        // Chat & Group sessions
                        Spacer(modifier = Modifier.height(24.dp))
                        var chatFiles by remember { mutableStateOf(listOf<File>()) }
                        var groupNames by remember { mutableStateOf(listOf<String>()) }
                        LaunchedEffect(char.fsName) {
                            withContext(Dispatchers.IO) {
                                val coreDir = AssetExtractor.getCoreDir(ctx)
                                val chatsDir = File(coreDir, "data/default-user/chats/${char.fsName}")
                                chatFiles = if (chatsDir.exists()) {
                                    chatsDir.listFiles()?.filter { it.isFile && it.extension == "jsonl" }
                                        ?.sortedByDescending { it.lastModified() } ?: emptyList()
                                } else emptyList()
                                val groupsDir = File(coreDir, "data/default-user/groups")
                                groupNames = if (groupsDir.exists()) {
                                    groupsDir.listFiles()?.filter { it.isFile && it.extension == "json" }
                                        ?.mapNotNull { f ->
                                            try {
                                                val json = JSONObject(f.readText())
                                                val members = json.optJSONArray("members") ?: return@mapNotNull null
                                                for (i in 0 until members.length()) {
                                                    val m = members.optString(i, "")
                                                    if (m.equals(char.name, ignoreCase = true) || m.equals(char.fsName, ignoreCase = true))
                                                        return@mapNotNull json.optString("name", f.nameWithoutExtension)
                                                }
                                                null
                                            } catch (_: Exception) { null }
                                        } ?: emptyList()
                                } else emptyList()
                            }
                        }
                        var chatSectionExpanded by remember { mutableStateOf(false) }
                        Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.sm))) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = AppDimens.controlHeight)
                                        .clip(RoundedCornerShape(AppRadius.sm))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = pressHighlightIndication(),
                                            onClick = { chatSectionExpanded = !chatSectionExpanded }
                                        )
                                        .padding(horizontal = 12.dp)
                                        .heightIn(min = AppDimens.controlHeight),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("聊天记录", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text("${chatFiles.size} 个聊天，${groupNames.size} 个群聊", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    ExpandChevron(
                                        expanded = chatSectionExpanded,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(AppDimens.iconMd),
                                    )
                                }
                                AnimatedVisibility(visible = chatSectionExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                                        val dateFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
                                        if (groupNames.isNotEmpty()) {
                                            Text("群聊", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            groupNames.forEach { gName ->
                                                Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                                                        Icon(Icons.Outlined.People, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm))
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(gName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }
                                        Text("聊天", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        if (chatFiles.isEmpty()) {
                                            Text("暂无聊天记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                        } else {
                                            chatFiles.forEach { file ->
                                                val sizeStr = com.tavern.app.util.FormatUtils.fileSize(file.length())
                                                val dateStr = remember(file.lastModified()) { dateFmt.format(java.util.Date(file.lastModified())) }
                                                val chatDir = file.parentFile?.absolutePath ?: ""
                                                val relPath = "data/default-user/chats/${char.fsName}/${file.name}"
                                                val canNavigate = File(chatDir).exists()
                                                Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                                    Column(modifier = Modifier.padding(12.dp)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm))
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                                                Text(relPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            }
                                                            if (canNavigate) {
                                                                TextButton(onClick = { onNavigateToFiles(chatDir) },
                                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                                                    Icon(Icons.Outlined.FolderOpen, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text("跳转", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                                                                }
                                                            }
                                                            TextButton(onClick = {
                                                                pendingChatFile = file
                                                                chatExportLauncher.launch(file.nameWithoutExtension + ".jsonl")
                                                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                                                Icon(Icons.Outlined.Download, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("导出", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text("$sizeStr，修改：$dateStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // An undeployed library version can be switched into the core;
                    // the deploy action replaces the core copy under the folder name.
                    if (!char.imported && char.libraryId.isNotBlank()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        val coreDir = AssetExtractor.getCoreDir(ctx)
                                        val card = loadLibrary(ctx, coreDir).firstOrNull { it.id == char.libraryId }
                                        val version = card?.versions?.firstOrNull { it.id == char.libraryVersionId }
                                        if (card == null || version == null) false
                                        else deployVersion(ctx, card, version, coreDir)
                                    }
                                    AppToast.show(
                                        ctx,
                                        if (ok) "已部署到核心，刷新后可以聊天" else "部署失败",
                                        Toast.LENGTH_SHORT
                                    )
                                    if (ok) {
                                        com.tavern.app.webview.TavernRefresh.request()
                                        onRefresh()
                                        onDismiss()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = appOutlinedButtonColors(contentColor = LocalAppColors.current.action),
                            border = appOutlinedButtonBorder(contentColor = LocalAppColors.current.action),
                            shape = AppCardShape
                        ) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(AppDimens.iconMd))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("部署到核心（可聊天）", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    // Export — real export (SAF, user picks destination), placed above the delete button.
                    Spacer(modifier = Modifier.height(24.dp))
                    Box {
                        OutlinedButton(
                            onClick = { showExportMenu = true }, modifier = Modifier.fillMaxWidth(),
                            colors = appOutlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            border = appOutlinedButtonBorder(contentColor = MaterialTheme.colorScheme.onSurface),
                            shape = AppCardShape
                        ) {
                            Icon(Icons.Outlined.Download, null, modifier = Modifier.size(AppDimens.iconMd))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出角色卡", style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出为 JSON") },
                                leadingIcon = { Icon(Icons.Outlined.Description, null, modifier = Modifier.size(AppDimens.iconMd)) },
                                onClick = {
                                    showExportMenu = false
                                    pendingExport = null
                                    jsonExportLauncher.launch("${char.name}.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导出为 PNG（含角色数据）") },
                                leadingIcon = { Icon(Icons.Outlined.Image, null, modifier = Modifier.size(AppDimens.iconMd)) },
                                onClick = {
                                    showExportMenu = false
                                    pendingExport = null
                                    pngExportLauncher.launch("${char.name}.png")
                                }
                            )
                        }
                    }

                    // Delete
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth(),
                        colors = appOutlinedButtonColors(contentColor = LocalAppColors.current.error),
                        border = appOutlinedButtonBorder(contentColor = MaterialTheme.colorScheme.outlineVariant),
                        shape = AppCardShape
                    ) {
                        Icon(Icons.Outlined.DeleteForever, null, modifier = Modifier.size(AppDimens.iconMd))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            // The verb has to match what the button does: a library version leaves
                            // the core and stays in the library, a core-only card is deleted.
                            if (char.libraryId.isNotBlank()) "取消部署" else "删除此角色卡",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}


// SAF export result: write the character JSON/PNG to the user-picked uri.
private suspend fun handleExportResult(
    uri: android.net.Uri?,
    kind: String,
    char: CharCardInfo,
    ctx: android.content.Context
) {
    if (uri == null) return
    val ok = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                if (kind == "json") {
                    val json = cardJsonOf(File(char.avatarPath))
                    out.write((if (json != null) json.toString(2) else "{}").toByteArray(Charsets.UTF_8))
                } else {
                    File(char.avatarPath).inputStream().use { it.copyTo(out) }
                }
            } != null
        } catch (_: Exception) { false }
    }
    AppToast.show(ctx, if (ok) "已导出 ${char.name}.$kind" else "导出失败", Toast.LENGTH_SHORT)
}

/**
 * Core-side cleanup when a card goes away: chats, orphaned world books and the
 * character-scoped settings (world info rows, regex allow-list, helper switches).
 * Returns (delete errors, whether the settings file was touched).
 */
private suspend fun purgeCardFromCoreState(
    ctx: android.content.Context,
    char: CharCardInfo,
    coreDir: File,
): Pair<Int, Boolean> {
    var delErrors = 0
    var selectionTouched = false
    val charsDir = File(coreDir, "data/default-user/characters")
    val coreFile = char.coreFileName.takeIf { it.isNotBlank() }?.let { File(charsDir, it) }
    // The card's own world bindings, read before the file is gone. They decide
    // which world books become orphans.
    val boundWorlds = mutableSetOf<String>()
    runCatching {
        val cardJson = cardJsonOf(File(char.avatarPath))
        if (cardJson != null) {
            when (val world = cardData(cardJson).optJSONObject("extensions")?.opt("world")) {
                is String -> if (world.isNotBlank()) boundWorlds.add(world)
                is JSONArray -> for (i in 0 until world.length()) boundWorlds.add(world.optString(i))
            }
            cardData(cardJson).optJSONObject("character_book")
                ?.optString("name")?.takeIf { it.isNotBlank() }?.let { boundWorlds.add(it) }
        }
    }
    val worldCandidates = (boundWorlds + char.worldBooks.map { it.name })
        .filter { it.isNotBlank() }
        .toSet()

    // Chats live in chats/<character name>/, which is not always the avatar file
    // name — default_Seraphina.png keeps its chats in chats/Seraphina/.
    val chatsDir = File(coreDir, "data/default-user/chats")
    listOf(char.name, char.fsName).distinct().forEach { label ->
        val dir = File(chatsDir, label)
        if (dir.exists() && !dir.deleteRecursively() && dir.exists()) delErrors++
    }

    // World books only this card referred to. The core never removes world
    // files, so a card carrying its own book would leave the file behind;
    // anything another card names, or that is selected globally, is kept.
    val deletedLabels = setOf(
        char.name,
        char.fsName,
        coreFile?.name ?: "",
        coreFile?.nameWithoutExtension ?: ""
    ).filter { it.isNotBlank() }.toSet()
    if (coreFile != null) {
        val orphans = orphanedWorldBooks(coreDir, coreFile, worldCandidates, deletedLabels)
        orphans.forEach { if (!it.delete()) delErrors++ }
    }

    // Character-scoped state the tavern keeps outside the card: world info rows,
    // regex allow-list, TavernHelper switches and regex already imported from it.
    val regexNames = char.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()
    selectionTouched = runCatching {
        val settingsFile = File(coreDir, "data/default-user/settings.json")
        if (!settingsFile.isFile) return@runCatching false
        val settingsJson = JSONObject(settingsFile.readText())
        if (!purgeCardState(settingsJson, deletedLabels, regexNames)) {
            return@runCatching false
        }
        settingsFile.writeText(settingsJson.toString(2))
        true
    }.getOrDefault(false)
    return delErrors to selectionTouched
}

@Composable
private fun RenameVersionDialog(
    version: LibraryVersion,
    cardId: String,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
) {
    var text by remember { mutableStateOf(version.name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("重命名版本", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "改名只影响仓库里的显示；已部署的卡在核心中保持原名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("版本名称") },
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = renameVersion(ctx, cardId, version.id, text.trim())
                AppToast.show(ctx, if (ok) "已重命名" else "重命名失败：名称无效", Toast.LENGTH_SHORT)
                if (ok) onRenamed() else onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** Types the free-form version label (v1.1, 2.0.0…) shown under the version row. */
@Composable
private fun EditVersionLabelDialog(
    version: LibraryVersion,
    cardId: String,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var text by remember { mutableStateOf(version.version) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("编辑版本号", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "写在版本下面的备注，例如 v1.1、2.0.0。留空就不显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("版本号") },
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                setVersionLabel(ctx, cardId, version.id, text)
                AppToast.show(ctx, "已保存", Toast.LENGTH_SHORT)
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Offered right after an import: tag the new version here, or skip and leave
 * the subtitle blank until the user writes it from the folder menu.
 */
// Zip is listed twice because pickers report archives as either type depending on
// the provider, and a card archive is the one format worth accepting wholesale.
private val IMPORT_MIME_TYPES = arrayOf(
    "image/png",
    "application/json",
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

/** The name the picker shows for a document, or null when the provider keeps it back. */
private fun displayNameOf(ctx: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        ctx.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

/**
 * Copies a picked document into the cache under its own file name.
 *
 * The name matters: the importer reads the format from the extension, and a file
 * called "import-card" made every json card look like a png and fail to parse.
 * Keeping the original name also gives a card without a title something human to
 * fall back on. Each import gets its own folder so two picks cannot collide.
 */
private fun stageImport(ctx: android.content.Context, uri: android.net.Uri): File? {
    val shown = displayNameOf(ctx, uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "card.png"
    val safe = shown.replace(Regex("""[\\/:*?"<>|]"""), "_")
    val dir = File(ctx.cacheDir, "import-${System.nanoTime()}")
    if (!dir.mkdirs()) return null
    val target = File(dir, safe)
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }.getOrNull()?.let { target.takeIf { file -> file.isFile && file.length() > 0 } }
}

/**
 * The picked card, its parsed defaults, and the document the picker grants access to. No broad
 * storage permission is requested.
 */
private data class PendingImport(val src: File, val defaults: ImportDefaults, val sourceUri: android.net.Uri)

/**
 * Sort key for the shelf: the folder name read as pinyin. Han-Latin ships with the platform's
 * ICU; names it cannot transliterate fall back to the name itself.
 */
private val cardPinyin by lazy {
    runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()
}

private fun cardSortKey(name: String): String {
    val transliterated = runCatching { cardPinyin?.transliterate(name) }.getOrNull() ?: name
    return transliterated.trim().lowercase()
}

/** What the toast says after an import, keyed by how the card was routed. */
private fun importToast(outcome: ImportOutcome): String {
    val message = when (outcome) {
        is ImportOutcome.Ok -> when {
            outcome.isNewEntry -> "已创建文件夹「${outcome.entryName}」"
            outcome.matchedBy == "feature" -> "内容与「${outcome.entryName}」相同，已加入这一版"
            else -> "已加入「${outcome.entryName}」"
        }
        is ImportOutcome.Duplicate -> "「${outcome.entryName}」已有完全相同的版本，未重复导入"
        ImportOutcome.ParseFailed -> "导入失败：文件不是可识别的角色卡"
    }
    return message
}

/**
 * The import confirmation: name and version, both filled from the card itself.
 *
 * The card already says what it is called and which version it is, so the reader's job here is
 * to disagree, not to type. The line under the fields says where it will land — an existing
 * folder when the name matches one, a new one otherwise — because that is the part an edited
 * name silently changes.
 */
/**
 * Shown while a batch runs. It cannot be dismissed: the import holds files open,
 * and an interrupted batch leaves the reader unsure what landed.
 */
@Composable
private fun ImportProgressDialog(done: Int, total: Int) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("正在导入", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "已处理 $done / $total 张",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {}
    )
}

/**
 * What a batch import actually did, file by file.
 *
 * A toast answers "did anything happen"; this answers "which folder did my card go
 * into, and why was that one skipped", which is the question sixty files raise.
 */
@Composable
private fun ImportResultDialog(report: BatchImportReport, onDismiss: () -> Unit) {
    val groups = listOf(
        ImportLineKind.NewFolder to "新建的文件夹",
        ImportLineKind.AddedVersion to "加入的版本",
        ImportLineKind.Duplicate to "跳过：已有相同内容",
        ImportLineKind.Unreadable to "无法识别",
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("导入结果", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    batchImportMessage(report),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    groups.forEach { (kind, label) ->
                        val rows = report.lines.filter { it.kind == kind }
                        if (rows.isEmpty()) return@forEach
                        item {
                            Text(
                                "$label（${rows.size}）",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = AppSpacing.sm, bottom = AppSpacing.xs)
                            )
                        }
                        rows.forEach { line ->
                            item {
                                Text(
                                    importLineText(line),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

/** One row of the result list: the folder it landed in, or the file that did not. */
private fun importLineText(line: ImportLine): String = when (line.kind) {
    ImportLineKind.NewFolder -> line.folder ?: line.fileName
    ImportLineKind.AddedVersion -> line.folder?.let { folder ->
        if (line.versionLabel.isNullOrBlank()) folder else "$folder（${line.versionLabel}）"
    } ?: line.fileName
    else -> line.fileName
}

@Composable
private fun ImportCardDialog(
    pending: PendingImport,
    cards: List<LibraryCard>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(pending.defaults.name) }
    var typedVersion by remember { mutableStateOf<String?>(null) }
    val cleanName = name.trim()
    val target = cards.firstOrNull { normalizeCardName(it.name) == normalizeCardName(cleanName) }
    // The card's own version wins. Without one, the next free number for the
    // folder the name points at is filled in, so confirming never needs typing —
    // and editing the name updates the suggestion until the reader types their own.
    val version = typedVersion ?: pending.defaults.version.ifBlank {
        nextVersionLabel(target?.versions?.map { it.version } ?: emptyList())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("导入角色卡", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("角色卡名") },
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = version,
                    onValueChange = { typedVersion = it },
                    singleLine = true,
                    label = { Text("版本号") },
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    when {
                        cleanName.isBlank() -> "填一个名字才能导入。"
                        target != null -> "将加入「${target.name}」，作为它的第 ${target.versions.size + 1} 个版本。"
                        else -> "将新建文件夹「$cleanName」。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pending.defaults.version.isBlank() && typedVersion == null) {
                    Text(
                        "这张卡本身没有版本号，已按该文件夹的顺序生成一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cleanName, version.trim()) },
                enabled = cleanName.isNotBlank() && !busy
            ) { Text(if (busy) "导入中…" else "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** Picks another card folder as the destination for a moved version. */
@Composable
private fun MoveVersionDialog(
    version: LibraryVersion,
    sourceCard: LibraryCard,
    allCards: List<LibraryCard>,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
) {
    val targets = allCards.filter { it.id != sourceCard.id }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = AppPanelShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(vertical = AppSpacing.md)) {
                Text(
                    "移动「${version.name}」到",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = AppSpacing.lg)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (targets.isEmpty()) {
                    Text(
                        "没有其他角色卡文件夹可移动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AppSpacing.lg)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(targets.size) { index ->
                            val target = targets[index]
                            ListRow(
                                icon = Icons.Outlined.Face,
                                title = target.name,
                                subtitle = "${target.versions.size} 个版本",
                                onClick = {
                                    val ok = moveVersion(ctx, sourceCard.id, version.id, target.id)
                                    AppToast.show(ctx, if (ok) "已移动到「${target.name}」" else "移动失败", Toast.LENGTH_SHORT)
                                    if (ok) onMoved() else onDismiss()
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun DeleteVersionConfirmDialog(
    version: LibraryVersion,
    card: LibraryCard,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    ConfirmDialog(
        title = "删除版本",
        message = "删除版本「${version.name}」？\n\n" +
            if (version.imported) "该版本已部署，核心中的卡与缩略图也会一并清理。" else "该版本未部署，仅从仓库移除。",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            val ok = removeVersion(ctx, card, version, AssetExtractor.getCoreDir(ctx))
            AppToast.show(ctx, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT)
            if (ok) onDeleted() else onDismiss()
        },
        onDismiss = onDismiss
    )
}

/**
 * The note, search field and filters that sit above the shelf.
 *
 * Shared by the loading and loaded states: rendering it only once the list arrived moved the whole
 * page down by its height, which read as the shelf jumping while it opened.
 */
@Composable
private fun LibraryHeader(
    query: String,
    onQuery: (String) -> Unit,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
) {
    Text(
        "角色卡只保存在本机，卸载应用会一并删除。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = AppSpacing.page, end = AppSpacing.page, top = AppSpacing.xs)
    )
    Column(modifier = Modifier.padding(horizontal = AppSpacing.page, vertical = AppSpacing.xs)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("搜索角色卡") },
            placeholder = { Text("名称或版本号") },
            singleLine = true,
            colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LibraryFilter.All to "全部",
                LibraryFilter.Deployed to "已部署",
                LibraryFilter.Undeployed to "未部署"
            ).forEach { (option, label) ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilter(option) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LocalAppColors.current.accentWash,
                        selectedLabelColor = LocalAppColors.current.accent
                    )
                )
            }
        }
    }
}
