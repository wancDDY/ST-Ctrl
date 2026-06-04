package com.tavern.app.console.pages

import android.graphics.BitmapFactory
import android.util.Base64
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
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

data class CharacterInfo(
    val name: String,
    val description: String,
    val avatarPath: String,
    val personality: String = "",
    val firstMessage: String = "",
    val scenario: String = "",
    val creator: String = "",
    val tags: List<String> = emptyList(),
    val version: String = ""
)

private val SECTION_LABELS = mapOf(
    "description" to "描述",
    "personality" to "性格",
    "scenario" to "场景",
    "first_mes" to "开场白",
    "mes_example" to "对话示例",
    "creator_notes" to "创作者备注",
    "system_prompt" to "系统提示",
    "post_history_instructions" to "历史后指令",
    "creator" to "创作者",
    "character_version" to "版本",
    "tags" to "标签"
)

/**
 * Extract character card JSON from a PNG file's tEXt chunk.
 * SillyTavern V2 card format: tEXt chunk with keyword "chara", value is base64 JSON.
 */
private fun extractCharacterJson(pngFile: File): JSONObject? {
    var dis: DataInputStream? = null
    return try {
        dis = DataInputStream(FileInputStream(pngFile))
        dis.skipBytes(8) // PNG signature
        var result: JSONObject? = null
        while (result == null) {
            val len = dis.readInt()
            val type = ByteArray(4)
            dis.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)
            when {
                typeStr == "IEND" -> break
                typeStr == "tEXt" -> {
                    val data = ByteArray(len)
                    dis.readFully(data)
                    dis.skipBytes(4) // CRC
                    val nullIdx = data.indexOf(0)
                    if (nullIdx > 0) {
                        val keyword = String(data, 0, nullIdx, Charsets.US_ASCII)
                        if (keyword == "chara") {
                            val b64 = String(data, nullIdx + 1, data.size - nullIdx - 1, Charsets.UTF_8)
                            val jsonBytes = Base64.decode(b64, Base64.DEFAULT)
                            result = JSONObject(String(jsonBytes, Charsets.UTF_8))
                        }
                    }
                }
                else -> dis.skipBytes(len + 4)
            }
        }
        result
    } catch (_: Exception) {
        null
    } finally {
        dis?.close()
    }
}

/**
 * Scan characters directory recursively for .png files and extract metadata.
 */
private fun loadCharacters(coreDir: File): List<CharacterInfo> {
    val charsDir = File(coreDir, "data/default-user/characters")
    if (!charsDir.exists()) return emptyList()

    val result = mutableListOf<CharacterInfo>()
    val seen = mutableSetOf<String>()

    fun scan(dir: File) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory) {
                scan(file)
            } else if (file.extension.lowercase() == "png" && file.name !in seen) {
                seen.add(file.name)
                val json = extractCharacterJson(file)
                if (json != null) {
                    val tags = mutableListOf<String>()
                    val tagsArr = json.optJSONArray("tags")
                    if (tagsArr != null) {
                        for (i in 0 until tagsArr.length()) tags.add(tagsArr.optString(i))
                    }
                    result.add(
                        CharacterInfo(
                            name = json.optString("name", file.nameWithoutExtension),
                            description = json.optString("description", ""),
                            avatarPath = file.absolutePath,
                            personality = json.optString("personality", ""),
                            firstMessage = json.optString("first_mes", ""),
                            scenario = json.optString("scenario", ""),
                            creator = json.optString("creator", ""),
                            tags = tags,
                            version = json.optString("character_version", "")
                        )
                    )
                } else {
                    // Fallback: no metadata, show filename as name
                    result.add(
                        CharacterInfo(
                            name = file.nameWithoutExtension,
                            description = "",
                            avatarPath = file.absolutePath
                        )
                    )
                }
            }
        }
    }

    scan(charsDir)
    return result
}

@Composable
fun CharacterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var characters by remember { mutableStateOf<List<CharacterInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedChar by remember { mutableStateOf<CharacterInfo?>(null) }

    LaunchedEffect(Unit) {
        characters = withContext(Dispatchers.IO) {
            loadCharacters(AssetExtractor.getCoreDir(ctx))
        }
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text("${characters.size} 个角色", fontSize = 13.sp, color = Color(0xFF8A8A80))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("角色管理", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("浏览已安装的角色卡", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(16.dp))

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFD4A853))
                }
            } else if (characters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Face, null, tint = Color(0xFF5A5A60), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("暂无角色", color = Color(0xFF8A8A80), fontSize = 15.sp)
                        Text("将角色卡 PNG 放入 characters 目录", color = Color(0xFF5A5A60), fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(characters, key = { it.avatarPath }) { char ->
                        CharacterGridItem(char, onClick = { selectedChar = char })
                    }
                }
            }
        }
    }

    // Detail dialog
    selectedChar?.let { char ->
        CharacterDetailDialog(char, onDismiss = { selectedChar = null })
    }
}

@Composable
private fun CharacterGridItem(char: CharacterInfo, onClick: () -> Unit) {
    val avatar = remember(char.avatarPath) {
        try {
            BitmapFactory.decodeFile(char.avatarPath)?.asImageBitmap()
        } catch (_: Exception) { null }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (avatar != null) {
                Image(
                    bitmap = avatar,
                    contentDescription = char.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(Color(0xFF2A2A35)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Person, null, tint = Color(0xFF5A5A60), modifier = Modifier.size(36.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                char.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (char.version.isNotBlank()) {
                Text(char.version, fontSize = 10.sp, color = Color(0xFF6A6A70), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CharacterDetailDialog(char: CharacterInfo, onDismiss: () -> Unit) {
    val avatar = remember(char.avatarPath) {
        try {
            BitmapFactory.decodeFile(char.avatarPath)?.asImageBitmap()
        } catch (_: Exception) { null }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with avatar and name
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF0A0A10))
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (avatar != null) {
                            Image(
                                bitmap = avatar, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(char.name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = Color.White)
                            if (char.creator.isNotBlank()) {
                                Text("创作者: ${char.creator}", fontSize = 12.sp, color = Color(0xFF8A8A80))
                            }
                            if (char.tags.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(char.tags.joinToString(" · "), fontSize = 11.sp, color = Color(0xFFD4A853))
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFF8A8A80))
                        }
                    }
                }

                // Scrollable detail sections
                Column(
                    modifier = Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    // Display fields in a sensible order
                    val fields = listOf(
                        "description" to char.description,
                        "personality" to char.personality,
                        "scenario" to char.scenario,
                        "first_mes" to char.firstMessage
                    ).filter { it.second.isNotBlank() }

                    fields.forEachIndexed { idx, (key, value) ->
                        DetailSection(SECTION_LABELS[key] ?: key, value)
                        if (idx < fields.size - 1) Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (fields.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center) {
                            Text("无详细信息", color = Color(0xFF6A6A70), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, content: String) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = Color(0xFFD4A853), letterSpacing = 0.5.sp)
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0A0A10)
    ) {
        Text(
            content, fontSize = 13.sp,
            color = Color(0xFFC0C0C8),
            lineHeight = 20.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}
