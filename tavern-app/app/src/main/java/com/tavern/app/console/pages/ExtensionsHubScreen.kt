package com.tavern.app.console.pages

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

// ─── Data ───

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
    val entries: List<WorldBookEntry> = emptyList()
)

data class RegexScriptInfo(
    val name: String,
    val findRegex: String = "",
    val replaceString: String = ""
)

// ─── PNG parser ───

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

// ─── Helpers: extract extensions from card JSON ───

/** Navigate card JSON → data → field, falling back to top-level. */
private fun cardData(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

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
                    replaceString = s.optString("replaceString", "")
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
                    replaceString = s.optString("replaceString", "")
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

// ─── Load characters ───

private fun loadCharacters(coreDir: File): List<CharCardInfo> {
    val charsDir = File(coreDir, "data/default-user/characters")
    if (!charsDir.exists()) return emptyList()
    val result = mutableListOf<CharCardInfo>()
    val seenNames = mutableSetOf<String>()
    val worldsDir = File(coreDir, "data/default-user/worlds")

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

                // Build CJK segments for smart matching
                val cjkTerms = mutableSetOf<String>()
                cjkTerms.add(name); cjkTerms.add(fsName)
                fun extractCjk(s: String) {
                    val cjk = StringBuilder()
                    for (ch in s) {
                        if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                            cjk.append(ch)
                        } else {
                            if (cjk.length >= 2) cjkTerms.add(cjk.toString())
                            cjk.clear()
                        }
                    }
                    if (cjk.length >= 2) cjkTerms.add(cjk.toString())
                }
                extractCjk(name); extractCjk(fsName)

                fun wbNameMatches(wbName: String): Boolean =
                    cjkTerms.any { wbName.contains(it, ignoreCase = true) }

                // World books: embedded character_book + extensions.world + CJK fallback
                val wbList = mutableListOf<WorldBookInfo>()
                val wbSeen = mutableSetOf<String>()
                // Helper to add world book file
                fun addWorldBook(f: File) {
                    val key = f.nameWithoutExtension
                    if (key in wbSeen) return
                    wbSeen.add(key)
                    try {
                        val j = JSONObject(f.readText())
                        val (ec, entries) = parseEntries(j.optJSONObject("entries") ?: j.optJSONArray("entries"))
                        wbList.add(WorldBookInfo(key, f.absolutePath, ec, entries))
                    } catch (_: Exception) {}
                }
                // 1) Embedded character_book — always belongs to this character (no name filter needed)
                extractCharBook(json)?.let {
                    wbList.add(it); wbSeen.add(it.name)
                }
                // 2) extensions.world — explicitly associated by the card (no name filter needed)
                val worldFileName = cardData(json).optJSONObject("extensions")?.optString("world", "") ?: ""
                if (worldFileName.isNotBlank() && worldsDir.exists()) {
                    val matchFile = File(worldsDir, worldFileName)
                    val target = if (matchFile.exists()) matchFile
                        else File(worldsDir, "$worldFileName.json").takeIf { it.exists() }
                    if (target != null) {
                        addWorldBook(target)
                    }
                }
                // 3) Fallback: world book files matching by CJK segments (name-based heuristics)
                if (worldsDir.exists()) {
                    worldsDir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                        ?.filter { f -> wbNameMatches(f.name) && f.nameWithoutExtension !in wbSeen }
                        ?.forEach { addWorldBook(it) }
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
                    worldBooks = wbList.distinctBy { it.name }
                ))
            }
        }
    }
    scan(charsDir)
    return result
}

// ─── Notes persistence ───

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

//  MAIN PAGE

@Composable
fun ExtensionsHubScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val accent = Color(0xFFD4A853)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", color = accent, fontSize = 15.sp) }
            Spacer(modifier = Modifier.height(8.dp))
            Text("扩展管理", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("管理扩展程序和角色卡", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = accent,
                divider = { HorizontalDivider(color = accent.copy(alpha = 0.2f)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("扩展", "角色卡").forEachIndexed { idx, title ->
                    Tab(selected = selectedTab == idx, onClick = { selectedTab = idx },
                        text = {
                            Text(title,
                                fontWeight = if (selectedTab == idx) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedTab == idx) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                fontSize = 15.sp)
                        })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            when (selectedTab) {
                0 -> ExtensionsScreen(onBack = {}, showHeader = false)
                1 -> CharactersTab()
            }
        }
    }
}

//  CHARACTERS TAB

@Composable
private fun CharactersTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharCardInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedChar by remember { mutableStateOf<CharCardInfo?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            characters = withContext(Dispatchers.IO) { loadCharacters(AssetExtractor.getCoreDir(ctx)) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFD4A853))
            }
            characters.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Face, null, tint = Color(0xFF5A5A60), modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("暂无角色卡", color = Color(0xFF8A8A80), fontSize = 15.sp)
                    Text("在酒馆中安装角色卡后在此管理", color = Color(0xFF5A5A60), fontSize = 12.sp)
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(characters, key = { it.avatarPath }) { char ->
                    CharGridItem(char, onClick = { selectedChar = char })
                }
            }
        }

    selectedChar?.let { char ->
        CharDetailDialog(char = char, ctx = ctx, onDismiss = { selectedChar = null; refresh() })
    }
}

//  GRID ITEM

@Composable
private fun CharGridItem(char: CharCardInfo, onClick: () -> Unit) {
    val avatar = remember(char.avatarPath) {
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(char.avatarPath, opts)?.asImageBitmap()
        } catch (_: Exception) { null }
    }
    val ctx = LocalContext.current
    var notes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(char.name) {
        notes = withContext(Dispatchers.IO) { loadAllNotes(ctx) }
    }
    val hasNote = notes[char.name]?.isNotBlank() == true

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                if (avatar != null) {
                    Image(bitmap = avatar, contentDescription = char.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(CircleShape))
                } else {
                    Box(modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF2A2A35)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Person, null, tint = Color(0xFF5A5A60), modifier = Modifier.size(36.dp))
                    }
                }
                if (hasNote) Icon(Icons.Outlined.StickyNote2, null,
                    tint = Color(0xFFD4A853).copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp).align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(char.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (char.version.isNotBlank())
                Text(char.version, fontSize = 10.sp, color = Color(0xFF6A6A70), maxLines = 1)
        }
    }
}

//  DETAIL DIALOG

@Composable
private fun CharDetailDialog(char: CharCardInfo, ctx: android.content.Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
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

    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        // Build a detailed warning message about what will be deleted
        val parts = mutableListOf<String>()
        parts.add("• 角色卡「${char.name}」")
        if (char.worldBooks.isNotEmpty()) parts.add("• 关联世界书 (${char.worldBooks.size} 本)")
        if (char.regexScripts.isNotEmpty()) parts.add("• 关联正则 (${char.regexScripts.size} 个)")
        parts.add("• 聊天记录")
        parts.add("• 玩家备注")
        val warnMsg = "以下内容将被永久删除，此操作不可撤销：\n\n${parts.joinToString("\n")}"

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

                        // 1. Delete character entry
                        val avatarFile = File(char.avatarPath)
                        val avatarParent = avatarFile.parentFile
                        // If avatar is in a subdirectory of characters/ (e.g., characters/XXX/xxx.png), delete the dir
                        if (avatarParent != null && avatarParent.parentFile?.absolutePath == charsDir.absolutePath) {
                            avatarParent.deleteRecursively()
                        } else {
                            // Flat file: just delete the avatar PNG
                            avatarFile.delete()
                        }

                        // 2. Delete matching chat directory (use fsName for filesystem match)
                        val chatsDir = File(coreDir, "data/default-user/chats")
                        if (chatsDir.exists()) {
                            chatsDir.listFiles()
                                ?.filter { it.isDirectory && it.name.equals(char.fsName, ignoreCase = true) }
                                ?.forEach { it.deleteRecursively() }
                        }

                        // 3. Delete associated world books (same logic as detection)
                        val worldsDir = File(coreDir, "data/default-user/worlds")
                        if (worldsDir.exists()) {
                            // 3a. world books matching by extensions.world or contains
                            char.worldBooks.forEach { wb ->
                                if (wb.path.isNotBlank()) File(wb.path).delete()
                            }
                            // 3b. Also try contains fallback for any leftover
                            worldsDir.listFiles()
                                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                                ?.filter { it.name.contains(char.name, ignoreCase = true) || it.name.contains(char.fsName, ignoreCase = true) }
                                ?.forEach { it.delete() }
                        }

                        // 4. Delete matching group chats
                        val groupsDir = File(coreDir, "data/default-user/groups")
                        if (groupsDir.exists()) {
                            groupsDir.listFiles()
                                ?.filter { it.isDirectory && (it.name.equals(char.fsName, ignoreCase = true) || it.name.equals(char.name, ignoreCase = true)) }
                                ?.forEach { it.deleteRecursively() }
                        }

                        // 5. Delete note
                        deleteNote(ctx, char.name)
                    }
                    onDismiss()
                    Toast.makeText(ctx, "已删除「${char.name}」及相关数据", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0A10)).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (avatar != null) Image(bitmap = avatar, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(CircleShape))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(char.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (char.creator.isNotBlank()) Text("创作者: ${char.creator}", fontSize = 12.sp, color = Color(0xFF8A8A80))
                            if (char.tags.isNotEmpty()) Text(char.tags.joinToString(" · "), fontSize = 11.sp, color = Color(0xFFD4A853))
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFF8A8A80)) }
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
                        Icon(Icons.Outlined.StickyNote2, null, tint = Color(0xFFD4A853), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("玩家备注", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD4A853))
                        Spacer(modifier = Modifier.weight(1f))
                        if (!isEditingNote && noteText.isNotBlank())
                            TextButton(onClick = { isEditingNote = true }) { Text("编辑", color = Color(0xFFD4A853), fontSize = 12.sp) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isEditingNote) {
                        OutlinedTextField(value = noteText, onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("添加备注...", color = Color(0xFF5A5A60), fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFC0C0C8), unfocusedTextColor = Color(0xFFC0C0C8),
                                focusedBorderColor = Color(0xFFD4A853), unfocusedBorderColor = Color(0xFF3A3A42),
                                cursorColor = Color(0xFFD4A853),
                                focusedContainerColor = Color(0xFF0A0A10), unfocusedContainerColor = Color(0xFF0A0A10)),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { isEditingNote = false; noteText = notesMap[char.name] ?: "" })
                            { Text("取消", color = Color(0xFF8A8A80), fontSize = 12.sp) }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                isEditingNote = false
                                if (noteText.isBlank()) { deleteNote(ctx, char.name); notesMap = notesMap.toMutableMap().also { it.remove(char.name) } }
                                else { saveNote(ctx, char.name, noteText); notesMap = notesMap.toMutableMap().also { it[char.name] = noteText } }
                                Toast.makeText(ctx, "备注已保存", Toast.LENGTH_SHORT).show()
                            }) { Text("保存", color = Color(0xFFD4A853), fontSize = 12.sp) }
                        }
                    } else if (noteText.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0A10)) {
                            Text(noteText, fontSize = 13.sp, color = Color(0xFFC0C0C8), lineHeight = 20.sp,
                                modifier = Modifier.padding(12.dp).fillMaxWidth())
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0A10),
                            modifier = Modifier.fillMaxWidth().clickable { isEditingNote = true }) {
                            Text("点击添加备注", fontSize = 13.sp, color = Color(0xFF5A5A60), modifier = Modifier.padding(12.dp).fillMaxWidth())
                        }
                    }

                    // World books (character-specific) — two-level like regex
                    Spacer(modifier = Modifier.height(18.dp))
                    var wbSectionExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0A10),
                        modifier = Modifier.fillMaxWidth().clickable { wbSectionExpanded = !wbSectionExpanded }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Public, null, tint = Color(0xFFD4A853), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("世界书", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD4A853))
                                Spacer(modifier = Modifier.weight(1f))
                                if (char.worldBooks.isNotEmpty()) Text("${char.worldBooks.size} 本", fontSize = 11.sp, color = Color(0xFF6A6A70))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    if (wbSectionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, tint = Color(0xFF5A5A60), modifier = Modifier.size(18.dp)
                                )
                            }
                            AnimatedVisibility(visible = wbSectionExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                if (char.worldBooks.isEmpty()) {
                                    Text("暂无", fontSize = 12.sp, color = Color(0xFF5A5A60),
                                        modifier = Modifier.padding(top = 8.dp))
                                } else {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        char.worldBooks.forEach { wb ->
                                            var wbExpanded by remember { mutableStateOf(false) }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp), color = Color(0xFF14141E),
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable { wbExpanded = !wbExpanded }
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(wb.name, fontSize = 12.sp, color = Color(0xFFB0B0B8),
                                                            modifier = Modifier.weight(1f))
                                                        if (wb.entryCount > 0) Text("${wb.entryCount} 条目", fontSize = 10.sp, color = Color(0xFF6A6A70))
                                                        Icon(
                                                            if (wbExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                            null, tint = Color(0xFF5A5A60), modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    AnimatedVisibility(visible = wbExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                                        if (wb.entries.isNotEmpty()) {
                                                            Column(modifier = Modifier.padding(top = 6.dp)) {
                                                                wb.entries.forEach { entry ->
                                                                    var entryExpanded by remember { mutableStateOf(false) }
                                                                    Surface(
                                                                        shape = RoundedCornerShape(4.dp), color = Color(0xFF08080E),
                                                                        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).clickable { entryExpanded = !entryExpanded }
                                                                    ) {
                                                                        Column(modifier = Modifier.padding(8.dp)) {
                                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                                Text(entry.comment, fontSize = 11.sp, color = Color(0xFF8A8A80),
                                                                                    modifier = Modifier.weight(1f))
                                                                                Icon(
                                                                                    if (entryExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                                                    null, tint = Color(0xFF5A5A60), modifier = Modifier.size(14.dp)
                                                                                )
                                                                            }
                                                                            AnimatedVisibility(visible = entryExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                                                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                                                                    if (entry.keys.isNotEmpty() || entry.secondaryKeys.isNotEmpty()) {
                                                                                        val allKeys = entry.keys + entry.secondaryKeys
                                                                                        Text(allKeys.joinToString(", "), fontSize = 10.sp,
                                                                                            color = Color(0xFFD4A853).copy(alpha = 0.7f))
                                                                                        Spacer(modifier = Modifier.height(4.dp))
                                                                                    }
                                                                                    if (entry.content.isNotBlank()) {
                                                                                        Text(entry.content.take(300), fontSize = 10.sp,
                                                                                            color = Color(0xFF6A6A70), lineHeight = 15.sp)
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            Text("无条目数据", fontSize = 11.sp, color = Color(0xFF5A5A60),
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
                    var regexExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0A10),
                        modifier = Modifier.fillMaxWidth().clickable { regexExpanded = !regexExpanded }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Code, null, tint = Color(0xFFD4A853), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正则", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD4A853))
                                Spacer(modifier = Modifier.weight(1f))
                                if (char.regexScripts.isNotEmpty()) Text("${char.regexScripts.size} 个", fontSize = 11.sp, color = Color(0xFF6A6A70))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    if (regexExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, tint = Color(0xFF5A5A60), modifier = Modifier.size(18.dp)
                                )
                            }
                            AnimatedVisibility(visible = regexExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                if (char.regexScripts.isEmpty()) {
                                    Text("暂无", fontSize = 12.sp, color = Color(0xFF5A5A60),
                                        modifier = Modifier.padding(top = 8.dp))
                                } else {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        char.regexScripts.forEach { rs ->
                                            var rsExpanded by remember { mutableStateOf(false) }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp), color = Color(0xFF14141E),
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable { rsExpanded = !rsExpanded }
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(rs.name, fontSize = 12.sp, color = Color(0xFFB0B0B8),
                                                            modifier = Modifier.weight(1f))
                                                        if (!rsExpanded && rs.findRegex.isNotBlank())
                                                            Text(rs.findRegex.take(12) + if (rs.findRegex.length > 12) "…" else "",
                                                                fontSize = 10.sp, color = Color(0xFF5A5A60), maxLines = 1)
                                                        Icon(
                                                            if (rsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                            null, tint = Color(0xFF5A5A60), modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    AnimatedVisibility(visible = rsExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                                        Column(modifier = Modifier.padding(top = 6.dp)) {
                                                            if (rs.findRegex.isNotBlank()) {
                                                                Text("匹配", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6A6A70))
                                                                Text(rs.findRegex, fontSize = 11.sp, color = Color(0xFF8A8A80),
                                                                    lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                                                            }
                                                            if (rs.replaceString.isNotBlank()) {
                                                                Spacer(modifier = Modifier.height(6.dp))
                                                                Text("替换", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6A6A70))
                                                                Text(rs.replaceString.take(200), fontSize = 11.sp, color = Color(0xFF8A8A80),
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

                    // Delete
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE05555)),
                        border = BorderStroke(1.dp, Color(0xFFE05555).copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.DeleteForever, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除此角色卡", fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

//  COLLAPSIBLE SECTION

@Composable
private fun CollapsibleSection(label: String, content: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp), color = Color(0xFF0A0A10),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB0B0B8))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    tint = Color(0xFF6A6A70), modifier = Modifier.size(20.dp))
            }
            if (!expanded) Text(content.take(60).replace("\n", " ") + if (content.length > 60) "…" else "",
                fontSize = 11.sp, color = Color(0xFF5A5A60), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(content, fontSize = 13.sp, color = Color(0xFFC0C0C8), lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
