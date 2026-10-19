package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.CollapsibleSection
import com.tavern.app.console.components.ConsoleTabRow
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.LoadingState
import com.tavern.app.console.components.appOutlinedButtonBorder
import com.tavern.app.console.components.appOutlinedButtonColors
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.console.theme.pressHighlightIndication
import com.tavern.app.console.theme.staggeredEntrance
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import com.tavern.app.console.components.PageTopBar
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.theme.AppRadius

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
    val worldBooks: List<WorldBookInfo> = emptyList()
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

private fun parseCharCard(pngFile: File): JSONObject? {
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

private fun loadCharacters(coreDir: File): List<CharCardInfo> {
    val charsDir = File(coreDir, "data/default-user/characters")
    if (!charsDir.exists()) return emptyList()
    val result = mutableListOf<CharCardInfo>()
    val seenNames = mutableSetOf<String>()
    val worldsDir = File(coreDir, "data/default-user/worlds")
    // SillyTavern binds extra world books to a card by its file name, in
    // world_info.charLore. The global selection is deliberately not read here: those
    // books are not this card's, and listing them under every card reads as if the
    // card brought them along.
    val settings = runCatching {
        JSONObject(File(coreDir, "data/default-user/settings.json").readText())
    }.getOrNull()
    val worldInfo = worldInfoSelections(settings)
    val charLore = worldInfo?.optJSONArray("charLore")

    fun scan(dir: File) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory) scan(file)
            else if (file.extension.equals("png", ignoreCase = true)) {
                val json = parseCharCard(file) ?: return@forEach
                val name = json.optString("name", file.nameWithoutExtension)
                if (name in seenNames) return@forEach
                seenNames.add(name)

                val tags = mutableListOf<String>()
                val tagsArr = json.optJSONArray("tags")
                if (tagsArr != null) for (i in 0 until tagsArr.length()) tags.add(tagsArr.optString(i))

                // Common: character's own directory and filesystem entry name
                val charDir = file.parentFile
                val fsName = if (charDir != null && charDir.parentFile?.name == "characters") charDir.name else file.nameWithoutExtension

                // Regex: from card metadata + from char directory files
                val regexScripts = extractRegex(json).toMutableList()
                if (charDir != null && charDir.isDirectory) {
                    charDir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                        ?.forEach { f ->
                            try {
                                val fileJson = JSONObject(f.readText())
                                // Check if this file contains regex data
                                val regexArr = fileJson.optJSONArray("scripts")
                                    ?: fileJson.optJSONObject("regex")?.optJSONArray("scripts")
                                    ?: fileJson.optJSONArray("regex")
                                if (regexArr != null) {
                                    for (i in 0 until regexArr.length()) {
                                        val s = regexArr.optJSONObject(i) ?: continue
                                        val sn = s.optString("scriptName", s.optString("name", "脚本${regexScripts.size+1}"))
                                        if (regexScripts.none { it.name == sn }) {
                                            regexScripts.add(RegexScriptInfo(
                                                name = sn,
                                                findRegex = s.optString("findRegex", ""),
                                                replaceString = s.optString("replaceString", "")
                                            ))
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                }

                // World books: embedded book, character-bound names, global selection,
                // then files that merely share the character's name.
                val wbList = mutableListOf<WorldBookInfo>()
                val wbSeen = mutableSetOf<String>()
                fun addWorldBook(f: File, origin: String) {
                    val key = f.nameWithoutExtension
                    if (key in wbSeen) return
                    wbSeen.add(key)
                    try {
                        val j = JSONObject(f.readText())
                        val (ec, entries) = parseEntries(j.optJSONObject("entries") ?: j.optJSONArray("entries"))
                        wbList.add(WorldBookInfo(key, f.absolutePath, ec, entries, origin))
                    } catch (_: Exception) {}
                }
                // A bound name resolves to a file in worlds/; ST stores the book's own
                // name, with or without the .json extension.
                fun resolveWorld(bookName: String): File? {
                    if (bookName.isBlank() || !worldsDir.exists()) return null
                    File(worldsDir, bookName).takeIf { it.isFile }?.let { return it }
                    File(worldsDir, "$bookName.json").takeIf { it.isFile }?.let { return it }
                    return worldsDir.listFiles()?.firstOrNull {
                        it.isFile && it.nameWithoutExtension.equals(bookName, ignoreCase = true)
                    }
                }
                // 1) Embedded character_book. It sits inside the card, so it is the
                //    card's own data whatever it is called — an earlier name check
                //    dropped real books whose title differs from the card's, and the
                //    origin label is enough to tell the user where it came from.
                extractCharBook(json)?.let { book ->
                    wbList.add(book.copy(origin = "内嵌")); wbSeen.add(book.name)
                }
                // 2) Character lore: the primary book in extensions.world (a name or a
                //    list of names) plus the extra books ST keeps per card file.
                val boundNames = LinkedHashSet<String>()
                when (val worldField = cardData(json).optJSONObject("extensions")?.opt("world")) {
                    is String -> if (worldField.isNotBlank()) boundNames.add(worldField)
                    is JSONArray -> for (i in 0 until worldField.length()) {
                        worldField.optString(i).takeIf { it.isNotBlank() }?.let { boundNames.add(it) }
                    }
                }
                // The card's embedded book is listed from the card itself. No file is
                // written for it here: the tavern asks to import it when the chat is
                // opened, and a file written behind the page would make its list show a
                // book the user never confirmed.
                if (charLore != null) {
                    for (i in 0 until charLore.length()) {
                        val loreEntry = charLore.optJSONObject(i) ?: continue
                        val owner = loreEntry.optString("name", "")
                        val mine = owner.equals(file.name, ignoreCase = true) ||
                            owner.equals(file.nameWithoutExtension, ignoreCase = true)
                        if (!mine) continue
                        val extras = loreEntry.optJSONArray("extraBooks") ?: continue
                        for (j in 0 until extras.length()) {
                            extras.optString(j).takeIf { it.isNotBlank() }?.let { boundNames.add(it) }
                        }
                    }
                }
                boundNames.forEach { bound -> resolveWorld(bound)?.let { addWorldBook(it, "角色绑定") } }
                // 3) worlds/ directory: books named after the character. Script-generated
                //    books land here with their own suffix and no binding.
                if (worldsDir.exists()) {
                    worldsDir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                        ?.filter { f ->
                            val n = f.nameWithoutExtension
                            n !in wbSeen && relatedWorldName(n, name, fsName)
                        }
                        ?.forEach { addWorldBook(it, "名称关联") }
                }

                result.add(CharCardInfo(
                    name = name,
                    description = json.optString("description", ""),
                    avatarPath = file.absolutePath,
                    fsName = fsName,
                    personality = json.optString("personality", ""),
                    firstMessage = json.optString("first_mes", ""),
                    scenario = json.optString("scenario", ""),
                    creator = json.optString("creator", ""),
                    tags = tags,
                    version = json.optString("character_version", ""),
                    regexScripts = regexScripts,
                    helperScripts = extractHelperScripts(json),
                    worldBooks = wbList.distinctBy { it.name }
                ))
            }
        }
    }
    scan(charsDir)
    return result
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
    asTab: Boolean = false
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val requestedTab by com.tavern.app.console.ExtensionsTabRequest.tab.collectAsState()
    // The task island can send the reader here from anywhere, and the sub-tab it means is usually
    // not the one this screen was left on.
    LaunchedEffect(requestedTab) {
        val index = requestedTab ?: return@LaunchedEffect
        selectedTab = index
        com.tavern.app.console.ExtensionsTabRequest.consume()
    }

    val ctx = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // No inset at the bottom: the sub-tab content owns the space down to the
        // bottom bar. A page margin there left a band of background under the last
        // row, which reads as the list stopping short of the bar rather than
        // continuing behind it.
        Column(modifier = Modifier.fillMaxSize().padding(top = AppSpacing.sm)) {
            // As a top-level tab the bar already names the area and the tabs
            // speak for themselves, so the whole header row is dropped.
            if (!asTab) {
                PageTopBar(title = "扩展管理", onBack = onBack)
            }

            // The tab row keeps the page gutter, and so does the one sub-tab whose
            // content is not built from rows. The others are full-bleed rows and cards
            // that carry their own inset: given the page gutter as well they read as a
            // box floating inside the screen, and the platform's pull feedback then
            // outlines that box instead of the screen edge.
            ConsoleTabRow(
                titles = listOf("插件", "角色", "角色记忆"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(horizontal = AppSpacing.page),
            )

            // The content starts at the tab divider. A spacer here separated the tabs
            // from a list whose first row already carries its own top padding, so the
            // two insets stacked into a band the user reads as a gap under the bar.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (selectedTab) {
                    0 -> ExtensionsScreen(onBack = {}, showHeader = false)
                    1 -> CharactersTab(onNavigateToFiles = onNavigateToFiles)
                    // Its header, status card and tables are laid out edge to edge and
                    // rely on this host for the page gutter.
                    2 -> MemoryCenterScreen(
                        modifier = Modifier.padding(horizontal = AppSpacing.page),
                    )
                }
            }
        }

    }
}

@Composable
private fun CharactersTab(onNavigateToFiles: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharCardInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedChar by remember { mutableStateOf<CharCardInfo?>(null) }
    // Entrance animation plays once: tab-level flag, not reset when items scroll out.
    var entrancePlayed by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            characters = withContext(Dispatchers.IO) { loadCharacters(AssetExtractor.getCoreDir(ctx)) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Once the stagger finishes (max index*stepMs + normalMs), set the flag and never animate again.
    LaunchedEffect(characters) {
        if (characters.isNotEmpty()) {
            kotlinx.coroutines.delay((characters.size * 35 + 300).toLong())
            entrancePlayed = true
        }
    }

    when {
            loading -> LoadingState(text = "正在加载插件…", modifier = Modifier.fillMaxSize())
            characters.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Face, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("暂无角色卡", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Text("在核心中安装角色卡后在此管理", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(characters, key = { _, it -> it.avatarPath }) { index, char ->
                    CharRow(
                        char = char,
                        onClick = { selectedChar = char },
                        modifier = Modifier.staggeredEntrance(
                            index = index,
                            onceReady = entrancePlayed,
                            stepMs = 35,
                            offsetPx = 22f
                        )
                    )
                }
            }
        }

    selectedChar?.let { char ->
        CharDetailDialog(char = char, ctx = ctx, onDismiss = { selectedChar = null }, onRefresh = { refresh() }, onNavigateToFiles = onNavigateToFiles)
    }
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
        subtitle = listOf(char.version, char.fsName.takeIf { !it.equals(char.name, ignoreCase = true) })
            .filter { !it.isNullOrBlank() }
            .joinToString("，")
            .ifBlank { null },
        onClick = onClick,
        modifier = modifier,
        leading = {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(char.avatarPath)).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AppDimens.iconBadge)
                    .clip(RoundedCornerShape(AppRadius.sm))
            )
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
        // The chat count is the part of a card deletion the user cannot see from this
        // screen and cannot undo. World books, regex scripts and TavernHelper scripts
        // stay behind: they are separate files, and another card may bind the same one.
        val chatCount = remember(char.avatarPath) {
            val chatsDir = File(AssetExtractor.getCoreDir(ctx), "data/default-user/chats")
            listOf(char.name, char.fsName).distinct()
                .sumOf { label ->
                    File(chatsDir, label).walkTopDown()
                        .count { it.isFile && it.extension.equals("jsonl", ignoreCase = true) }
                }
        }
        val parts = mutableListOf<String>()
        parts.add("• 角色卡「${char.name}」")
        parts.add("• 聊天记录（$chatCount 个会话文件）")
        parts.add("• 玩家备注")
        parts.add("• 该角色的记忆索引（下次打开角色记忆时清理）")
        parts.add("• 仅被该角色卡引用的世界书（其他角色卡引用的会保留）")
        // Both live inside the card and, for the copies the tavern already imported,
        // are removed with the character-scoped settings.
        if (char.regexScripts.isNotEmpty() || char.helperScripts.isNotEmpty()) {
            parts.add("• 随卡带入的正则与核心助手脚本（含核心中已导入的副本）")
        }
        val kept = mutableListOf<String>()
        // The character's own folder holds expressions and other companion files that
        // never travel inside the PNG, and the delete keeps them.
        val companionCount = remember(char.avatarPath) {
            File(char.avatarPath).parentFile
                ?.takeIf { it.isDirectory }
                ?.listFiles()
                ?.count { it.isFile && it.name != File(char.avatarPath).name } ?: 0
        }
        if (companionCount > 0) kept.add("表情/附加数据等 $companionCount 个文件（重新导入该角色卡后可继续使用）")
        if (char.worldBooks.isNotEmpty()) kept.add("其他角色卡引用的世界书")
        val keepLine = if (kept.isEmpty()) "" else
            "\n\n以下内容会保留——它们在独立文件里，其他角色卡也可能在用：\n• " + kept.joinToString("\n• ")
        val warnMsg = "以下内容将被永久删除，此操作不可撤销：\n\n${parts.joinToString("\n")}$keepLine"

        ConfirmDialog(
            title = "删除角色卡",
            message = warnMsg,
            confirmText = "确认删除",
            dismissText = "取消",
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val coreDir = AssetExtractor.getCoreDir(ctx)
                        val charsDir = File(coreDir, "data/default-user/characters")

                        var delErrors = 0

                        // The card's own world bindings, read before the file is gone.
                        // They decide which world books become orphans.
                        val boundWorlds = mutableSetOf<String>()
                        runCatching {
                            val cardJson = parseCharCard(File(char.avatarPath))
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

                        // 1. The card file only. A card can sit in a folder of its own,
                        //    and SillyTavern keeps the character's expressions and other
                        //    companion files there — they are not inside the PNG, and a
                        //    re-import picks them up again. Removing the folder with the
                        //    card is what made a re-imported character look incomplete.
                        val avatarFile = File(char.avatarPath)
                        val avatarParent = avatarFile.parentFile
                        if (!avatarFile.delete() && avatarFile.exists()) delErrors++
                        if (avatarParent != null
                            && avatarParent.parentFile?.absolutePath == charsDir.absolutePath
                            && avatarParent.listFiles().isNullOrEmpty()
                        ) {
                            avatarParent.delete()
                        }

                        // 2. Chats live in chats/<character name>/, which is not always
                        //    the avatar file name — default_Seraphina.png keeps its
                        //    chats in chats/Seraphina/. Both labels are checked.
                        val chatsDir = File(coreDir, "data/default-user/chats")
                        listOf(char.name, char.fsName).distinct().forEach { label ->
                            val dir = File(chatsDir, label)
                            if (dir.exists() && !dir.deleteRecursively() && dir.exists()) delErrors++
                        }

                        // 3. The card's cached thumbnail, if the core generated one.
                        val userDir = File(coreDir, "data/default-user")
                        val thumbName = File(char.avatarPath).name
                        listOf(thumbName, thumbName.removeSuffix(".png") + ".png").distinct().forEach { name ->
                            listOf("avatar", "").forEach { sub ->
                                val thumb = if (sub.isEmpty()) File(userDir, "thumbnails/$name")
                                else File(userDir, "thumbnails/$sub/$name")
                                if (thumb.isFile && !thumb.delete()) delErrors++
                            }
                        }

                        // 4. World books only this card referred to. The core never
                        //    removes world files, so a card carrying its own book would
                        //    leave the file behind; anything another card names, or that
                        //    is selected globally, is kept.
                        val deletedLabels = setOf(
                            char.name,
                            char.fsName,
                            File(char.avatarPath).name,
                            File(char.avatarPath).nameWithoutExtension
                        ).filter { it.isNotBlank() }.toSet()
                        val orphans = orphanedWorldBooks(coreDir, File(char.avatarPath), worldCandidates, deletedLabels)
                        orphans.forEach { if (!it.delete()) delErrors++ }

                        // 5. Character-scoped state the tavern keeps outside the card: the
                        //    world info row binding extra books, the allow-list for the
                        //    card's regex, the TavernHelper switches keyed by its file
                        //    name, and any regex already imported from it. Left behind,
                        //    the tavern keeps acting as if the card still exists.
                        val regexNames = char.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()
                        val selectionTouched = runCatching {
                            val settingsFile = File(coreDir, "data/default-user/settings.json")
                            if (!settingsFile.isFile) return@runCatching false
                            val settingsJson = JSONObject(settingsFile.readText())
                            if (!purgeCardState(settingsJson, deletedLabels, regexNames)) {
                                return@runCatching false
                            }
                            settingsFile.writeText(settingsJson.toString(2))
                            true
                        }.getOrDefault(false)

                        deleteNote(ctx, char.name)

                        withContext(Dispatchers.Main) {
                            onDismiss()
                            val msg = when {
                                delErrors > 0 -> "已删除「${char.name}」，有 $delErrors 项需手动处理"
                                selectionTouched -> "已删除「${char.name}」及相关数据，已刷新核心页面"
                                else -> "已删除「${char.name}」及相关数据"
                            }
                            // The page holds the card list and the world info selections; a
                            // reload is what makes the deletion visible in the tavern.
                            com.tavern.app.webview.TavernRefresh.request()
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
            shape = RoundedCornerShape(AppRadius.md), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (avatar != null) Image(bitmap = avatar, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(CircleShape))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(char.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (char.creator.isNotBlank()) Text("创作者：${char.creator}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (char.tags.isNotEmpty()) Text(char.tags.joinToString("、"), style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }

                // Scrollable content
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    // Basic info — collapsible
                    val fields = listOf(
                        "描述" to char.description,
                        "性格" to char.personality,
                        "场景" to char.scenario,
                        "开场白" to char.firstMessage
                    ).filter { it.second.isNotBlank() }
                    fields.forEachIndexed { idx, (label, value) ->
                        CollapsibleSection(label, value)
                        if (idx < fields.size - 1) Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Notes
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                        Spacer(modifier = Modifier.width(6.dp))
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
                            Text(noteText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp,
                                modifier = Modifier.padding(12.dp).fillMaxWidth())
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.sm)).clickable { isEditingNote = true }) {
                            Text("点按添加备注", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp).fillMaxWidth())
                        }
                    }

                    // World books (character-specific) — two-level like regex
                    Spacer(modifier = Modifier.height(18.dp))
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
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Public, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("世界书", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                Spacer(modifier = Modifier.weight(1f))
                                if (char.worldBooks.isNotEmpty()) Text("${char.worldBooks.size} 本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    if (wbSectionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd)
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
                                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(wb.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.weight(1f))
                                                        if (wb.origin.isNotBlank()) Text(wb.origin, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        if (wb.entryCount > 0) Text("  ${wb.entryCount} 条目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Icon(
                                                            if (wbExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                            null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm)
                                                        )
                                                    }
                                                    AnimatedVisibility(visible = wbExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                                        if (wb.entries.isNotEmpty()) {
                                                            Column(modifier = Modifier.padding(top = 6.dp)) {
                                                                wb.entries.forEach { entry ->
                                                                    var entryExpanded by remember { mutableStateOf(false) }
                                                                    Surface(
                                                                        shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                                                                        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
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
                                                                                Icon(
                                                                                    if (entryExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm)
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
                                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            Text("无条目数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(top = 6.dp))
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
                    Spacer(modifier = Modifier.height(18.dp))
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
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Extension, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("核心助手脚本", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text("${char.helperScripts.size} 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        if (helperExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd)
                                    )
                                }
                                AnimatedVisibility(visible = helperExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                                        char.helperScripts.forEach { hs ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
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
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Code, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正则", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                Spacer(modifier = Modifier.weight(1f))
                                if (char.regexScripts.isNotEmpty()) Text("${char.regexScripts.size} 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    if (regexExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd)
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
                                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(rs.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.weight(1f))
                                                        if (!rs.enabled) Text("关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        if (!rsExpanded && rs.findRegex.isNotBlank())
                                                            Text(rs.findRegex.take(12) + if (rs.findRegex.length > 12) "…" else "",
                                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                                        Icon(
                                                            if (rsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                            null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm)
                                                        )
                                                    }
                                                    AnimatedVisibility(visible = rsExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                                        Column(modifier = Modifier.padding(top = 6.dp)) {
                                                            if (rs.findRegex.isNotBlank()) {
                                                                Text("匹配", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                Text(rs.findRegex, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                                                            }
                                                            if (rs.replaceString.isNotBlank()) {
                                                                Spacer(modifier = Modifier.height(6.dp))
                                                                Text("替换", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                Text(rs.replaceString.take(200), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
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

                    // Chat & Group sessions
                    Spacer(modifier = Modifier.height(18.dp))
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
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("聊天记录", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.accent)
                                Spacer(modifier = Modifier.weight(1f))
                                Text("${chatFiles.size} 个聊天，${groupNames.size} 个群聊", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(if (chatSectionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconMd))
                            }
                            AnimatedVisibility(visible = chatSectionExpanded, enter = motion.enterExpand(), exit = motion.exitShrink()) {
                                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                                    val dateFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
                                    if (groupNames.isNotEmpty()) {
                                        Text("群聊", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        groupNames.forEach { gName ->
                                            Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                                                    Icon(Icons.Outlined.People, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(gName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }
                                    Text("聊天", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (chatFiles.isEmpty()) {
                                        Text("暂无聊天记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                                    } else {
                                        chatFiles.forEach { file ->
                                            val sizeStr = com.tavern.app.util.FormatUtils.fileSize(file.length())
                                            val dateStr = remember(file.lastModified()) { dateFmt.format(java.util.Date(file.lastModified())) }
                                            val chatDir = file.parentFile?.absolutePath ?: ""
                                            val relPath = "data/default-user/chats/${char.fsName}/${file.name}"
                                            val canNavigate = File(chatDir).exists()
                                            Surface(shape = RoundedCornerShape(AppRadius.sm), color = MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppDimens.iconSm))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                                            Text(relPath, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        }
                                                        if (canNavigate) {
                                                            TextButton(onClick = { onNavigateToFiles(chatDir) },
                                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                                                Icon(Icons.Outlined.FolderOpen, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                                                Spacer(modifier = Modifier.width(2.dp))
                                                                Text("跳转", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                                                            }
                                                        }
                                                        TextButton(onClick = {
                                                            pendingChatFile = file
                                                            chatExportLauncher.launch(file.nameWithoutExtension + ".jsonl")
                                                        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                                            Icon(Icons.Outlined.Download, null, tint = LocalAppColors.current.accent, modifier = Modifier.size(AppDimens.iconSm))
                                                            Spacer(modifier = Modifier.width(2.dp))
                                                            Text("导出", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text("$sizeStr，修改：$dateStr", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Export — real export (SAF, user picks destination), placed above the delete button.
                    Spacer(modifier = Modifier.height(24.dp))
                    Box {
                        OutlinedButton(
                            onClick = { showExportMenu = true }, modifier = Modifier.fillMaxWidth(),
                            colors = appOutlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            border = appOutlinedButtonBorder(contentColor = MaterialTheme.colorScheme.onSurface),
                            shape = RoundedCornerShape(AppRadius.md)
                        ) {
                            Icon(Icons.Outlined.Download, null, modifier = Modifier.size(AppDimens.iconMd))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出角色卡", style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
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
                        border = appOutlinedButtonBorder(contentColor = LocalAppColors.current.error),
                        shape = RoundedCornerShape(AppRadius.md)
                    ) {
                        Icon(Icons.Outlined.DeleteForever, null, modifier = Modifier.size(AppDimens.iconMd))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除此角色卡", style = MaterialTheme.typography.labelLarge)
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
                    val json = parseCharCard(File(char.avatarPath))
                    out.write((if (json != null) json.toString(2) else "{}").toByteArray(Charsets.UTF_8))
                } else {
                    File(char.avatarPath).inputStream().use { it.copyTo(out) }
                }
            } != null
        } catch (_: Exception) { false }
    }
    AppToast.show(ctx, if (ok) "已导出 ${char.name}.$kind" else "导出失败", Toast.LENGTH_SHORT)
}
