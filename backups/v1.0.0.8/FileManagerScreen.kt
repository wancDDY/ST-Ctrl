package com.tavern.app.console.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tavern.app.console.FileItem
import com.tavern.app.console.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.*

@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = Color(0xFFD4A853)
    val muted = onBg.copy(alpha = 0.55f)
    val divider = onBg.copy(alpha = 0.08f)

    val fm = remember { FileManager(ctx) }
    var currentDir by remember { mutableStateOf(fm.baseDir) }
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var viewText by remember { mutableStateOf<FileItem?>(null) }
    var viewImage by remember { mutableStateOf<FileItem?>(null) }
    var menuTarget by remember { mutableStateOf<FileItem?>(null) }
    var createName by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }

    // File to export (set by long-press menu)
    var exportTarget by remember { mutableStateOf<FileItem?>(null) }

    // Multi-select
    var selectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var multiCompressTargets by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var compressTarget by remember { mutableStateOf<FileItem?>(null) }
    var compressName by remember { mutableStateOf("") }
    var decompressTarget by remember { mutableStateOf<FileItem?>(null) }
    var decompressUseFolder by remember { mutableStateOf(true) }
    var decompressOverwrite by remember { mutableStateOf(false) }
    var isDecompressing by remember { mutableStateOf(false) }
    var decompressPhase by remember { mutableStateOf("") }

    // Highlight newly imported file
    var highlightPath by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Compression progress
    var isCompressing by remember { mutableStateOf(false) }
    var compressMsg by remember { mutableStateOf("") }

    // reload function — must be defined before launcher callbacks that reference it
    fun reload() {
        scope.launch {
            isLoading = true; loadError = null
            fm.listDirectory(currentDir).fold(onSuccess = { items = it }, onFailure = { loadError = it.message })
            isLoading = false
        }
        selectMode = false
        selectedPaths = emptySet()
    }

    // Import conflict state
    var importConflictUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importConflictName by remember { mutableStateOf("") }

    fun doImport(uri: android.net.Uri, name: String) {
        scope.launch {
            val dest = File(currentDir, name)
            withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { out -> input.copyTo(out) }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已导入: $name", Toast.LENGTH_SHORT).show()
                        highlightPath = dest.absolutePath
                        reload()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = FileManager.resolveFilename(ctx, uri)
        val dest = File(currentDir, name)
        if (dest.exists()) {
            importConflictUri = uri
            importConflictName = name
        } else {
            doImport(uri, name)
        }
    }

    // Export launcher (create document)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null || exportTarget == null) return@rememberLauncherForActivityResult
        val item = exportTarget!!
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        item.file.inputStream().use { it.copyTo(out) }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已导出: ${item.name}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
            exportTarget = null
        }
    }
    // Trigger export when target is set
    LaunchedEffect(exportTarget) {
        exportTarget?.let { exportLauncher.launch(it.name) }
    }

    LaunchedEffect(currentDir) { reload() }

    // Scroll to and highlight newly imported file
    LaunchedEffect(items, highlightPath) {
        val path = highlightPath ?: return@LaunchedEffect
        val idx = items.indexOfFirst { it.file.absolutePath == path }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
        }
        // auto-clear highlight after 3 seconds
        kotlinx.coroutines.delay(3000)
        highlightPath = null
    }

    // Back button: select mode → exit select, parent dir → go up, root → exit
    androidx.activity.compose.BackHandler {
        if (selectMode) {
            selectMode = false
            selectedPaths = emptySet()
        } else {
            val parent = currentDir.parentFile
            if (parent != null && parent.absolutePath.startsWith(fm.baseDir.absolutePath)) {
                currentDir = parent
            } else {
                onBack()
            }
        }
    }

    val segments = remember(currentDir) {
        val rel = currentDir.absolutePath.removePrefix(fm.baseDir.absolutePath).trimStart('/')
        if (rel.isEmpty()) emptyList() else rel.split("/").filter { it.isNotEmpty() }
    }

    // Build the full path display string
    val pathDisplay = remember(currentDir) {
        val rel = currentDir.absolutePath.removePrefix(fm.baseDir.absolutePath).trimStart('/')
        if (rel.isEmpty()) fm.baseDir.absolutePath
        else "${fm.baseDir.name}/${rel}"
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            // Top bar: back + select + import
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = accent, fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                // Select mode toggle
                TextButton(onClick = {
                    selectMode = !selectMode
                    if (!selectMode) selectedPaths = emptySet()
                }) {
                    Icon(
                        if (selectMode) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        null, tint = if (selectMode) accent else muted, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("选择", color = if (selectMode) accent else muted, fontSize = 13.sp)
                }
                // Import button
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FileUpload, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入", color = accent, fontSize = 13.sp)
                }
            }

            Text("文件管理", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onBg)
            Text(pathDisplay, fontSize = 11.sp, color = muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))

            // Breadcrumb — each segment is a button, separator is part of the button text for alignment
            ScrollableRow(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                // Root
                TextButton(onClick = { currentDir = fm.baseDir },
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("core", color = if (segments.isEmpty()) accent else muted, fontSize = 13.sp,
                        fontWeight = if (segments.isEmpty()) FontWeight.SemiBold else FontWeight.Normal)
                }
                segments.forEachIndexed { i, s ->
                    TextButton(onClick = { currentDir = File(fm.baseDir, segments.take(i + 1).joinToString("/")) },
                        contentPadding = PaddingValues(horizontal = 2.dp)) {
                        Text("  /  $s", color = if (i == segments.lastIndex) accent else muted, fontSize = 13.sp,
                            fontWeight = if (i == segments.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }

            // Select-mode header — outside Surface, below breadcrumb
            if (selectMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selectMode = false; selectedPaths = emptySet() }) {
                        Text("取消", color = muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${selectedPaths.size} 项", color = onSurface, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (selectedPaths.size == items.size) selectedPaths = emptySet()
                        else selectedPaths = items.map { it.file.absolutePath }.toSet()
                    }) {
                        Text(if (selectedPaths.size == items.size) "取消全选" else "全选", color = accent, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = divider)
            }

            // File list
            Surface(Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(12.dp), color = surface) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                    }
                    loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", color = Color(0xFFCC4455), fontSize = 14.sp)
                            loadError?.let { Text(it, color = muted, fontSize = 12.sp) }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { reload() }) { Text("重试", color = accent) }
                        }
                    }
                    items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此目录为空", color = muted, fontSize = 13.sp)
                    }
                    else -> {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)) {
                            items(items, key = { it.file.absolutePath }) { item ->
                                FileRow(
                                    item, accent, muted, onSurface,
                                    selectMode = selectMode,
                                    isSelected = item.file.absolutePath in selectedPaths,
                                    isHighlighted = item.file.absolutePath == highlightPath,
                                    onClick = {
                                        if (selectMode) {
                                            selectedPaths = if (item.file.absolutePath in selectedPaths)
                                                selectedPaths - item.file.absolutePath
                                            else
                                                selectedPaths + item.file.absolutePath
                                        } else {
                                            if (item.isDirectory) currentDir = item.file
                                            else if (FileManager.isText(item.extension)) viewText = item
                                            else if (FileManager.isImage(item.extension)) viewImage = item
                                            else Toast.makeText(ctx, "不支持预览此类型", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onLongClick = {
                                        if (!selectMode) {
                                            selectMode = true
                                            selectedPaths = setOf(item.file.absolutePath)
                                        }
                                        menuTarget = item
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Batch action bar — shown when multi items selected
        if (selectMode && selectedPaths.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = surface, shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Batch compress — opens rename dialog for zip name
                    TextButton(onClick = {
                        multiCompressTargets = items.filter { it.file.absolutePath in selectedPaths }
                        compressName = if (multiCompressTargets.size == 1)
                            multiCompressTargets.first().name.removeSuffix("/") + ".zip"
                        else "archive_${selectedPaths.size}.zip"
                    }) {
                        Icon(Icons.Outlined.Archive, null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("压缩", color = accent, fontSize = 13.sp)
                    }
                    // Batch export — compress to zip then export (Android only supports single file export)
                    TextButton(onClick = {
                        val targets = items.filter { it.file.absolutePath in selectedPaths }
                        if (targets.size == 1 && !targets.first().isDirectory) {
                            exportTarget = targets.first()
                        } else {
                            scope.launch {
                                isCompressing = true
                                compressMsg = "正在打包 ${targets.size} 个文件…"
                                val zipName = "export_${targets.size}.zip"
                                val zipFile = File(currentDir, zipName)
                                fm.compressItems(targets, zipFile).fold(
                                    onSuccess = {
                                        isCompressing = false
                                        Toast.makeText(ctx, "已打包: $zipName", Toast.LENGTH_SHORT).show()
                                        exportTarget = FileItem(zipFile, zipFile.name, false, zipFile.length(), zipFile.lastModified(), "zip")
                                    },
                                    onFailure = {
                                        isCompressing = false
                                        Toast.makeText(ctx, "打包失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.FileDownload, null, tint = Color(0xFF6B8EC2), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出", color = Color(0xFF6B8EC2), fontSize = 13.sp)
                    }
                    // Batch delete
                    TextButton(onClick = {
                        val targets = items.filter { it.file.absolutePath in selectedPaths }
                        if (targets.isNotEmpty()) {
                            deleteTarget = targets.first()
                            // We'll handle batch delete in the delete dialog
                        }
                    }) {
                        Icon(Icons.Outlined.Delete, null, tint = Color(0xFFCC4455), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = Color(0xFFCC4455), fontSize = 13.sp)
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { showCreate = true; createName = ""; actionError = null },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = accent, contentColor = Color(0xFF08080E), shape = CircleShape
        ) { Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(24.dp)) }
    }

    // Context menu — single vs batch
    menuTarget?.let { item ->
        val batchMode = selectMode && selectedPaths.size > 1
        val isZip = item.extension == "zip"
        DropdownMenu(expanded = true, onDismissRequest = { menuTarget = null }, offset = DpOffset(16.dp, 0.dp)) {
            if (batchMode) {
                // ── Batch context menu ──
                DropdownMenuItem(
                    text = { Text("已选 ${selectedPaths.size} 项", color = onSurface, fontWeight = FontWeight.SemiBold) },
                    onClick = { menuTarget = null }, leadingIcon = { Icon(Icons.Outlined.CheckBox, null, tint = accent) },
                    enabled = false
                )
                HorizontalDivider()
                DropdownMenuItem(text = { Text("压缩选中 (${selectedPaths.size}项)") }, onClick = {
                    menuTarget = null
                    multiCompressTargets = items.filter { it.file.absolutePath in selectedPaths }
                    compressName = "archive_${selectedPaths.size}.zip"
                }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                DropdownMenuItem(text = { Text("导出选中 (${selectedPaths.size}项)") }, onClick = {
                    menuTarget = null
                    val targets = items.filter { it.file.absolutePath in selectedPaths }
                    if (targets.size == 1 && !targets.first().isDirectory) {
                        exportTarget = targets.first()
                    } else {
                        scope.launch {
                            isCompressing = true
                            compressMsg = "正在打包 ${targets.size} 个文件…"
                            val fn = "export_${targets.size}.zip"
                            val zf = File(currentDir, fn)
                            fm.compressItems(targets, zf).fold(
                                onSuccess = {
                                    isCompressing = false
                                    Toast.makeText(ctx, "已打包: $fn", Toast.LENGTH_SHORT).show()
                                    exportTarget = FileItem(zf, zf.name, false, zf.length(), zf.lastModified(), "zip")
                                },
                                onFailure = {
                                    isCompressing = false
                                    Toast.makeText(ctx, "打包失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) })
                DropdownMenuItem(text = { Text("删除选中 (${selectedPaths.size}项)", color = Color(0xFFCC4455)) }, onClick = {
                    menuTarget = null
                    deleteTarget = items.first { it.file.absolutePath in selectedPaths }
                }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFCC4455)) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("取消选择") }, onClick = {
                    menuTarget = null; selectedPaths = emptySet(); selectMode = false
                }, leadingIcon = { Icon(Icons.Outlined.Deselect, null) })
            } else {
                // ── Single-item context menu ──
                if (item.isDirectory) {
                    DropdownMenuItem(text = { Text("打开") }, onClick = { menuTarget = null; currentDir = item.file },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) })
                } else {
                    DropdownMenuItem(text = { Text("查看") }, onClick = {
                        menuTarget = null
                        if (FileManager.isText(item.extension)) viewText = item
                        else if (FileManager.isImage(item.extension)) viewImage = item
                    }, leadingIcon = { Icon(Icons.Outlined.Visibility, null) })
                }
                DropdownMenuItem(text = { Text("重命名") }, onClick = {
                    menuTarget = null; renameTarget = item; renameText = item.name; actionError = null
                }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) })
                DropdownMenuItem(text = { Text("压缩") }, onClick = {
                    menuTarget = null
                    compressTarget = item
                    compressName = item.name.removeSuffix("/") + ".zip"
                }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                if (isZip) {
                    DropdownMenuItem(text = { Text("解压到当前目录") }, onClick = {
                        menuTarget = null; decompressTarget = item
                    }, leadingIcon = { Icon(Icons.Outlined.Unarchive, null) })
                }
                if (!item.isDirectory) {
                    DropdownMenuItem(text = { Text("导出") }, onClick = {
                        menuTarget = null; exportTarget = item
                    }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) })
                }
                DropdownMenuItem(text = { Text("复制路径") }, onClick = {
                    menuTarget = null
                    val c = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    c.setPrimaryClip(ClipData.newPlainText("", item.file.absolutePath))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                DropdownMenuItem(text = { Text("删除", color = Color(0xFFCC4455)) }, onClick = {
                    menuTarget = null; deleteTarget = item; actionError = null
                }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFCC4455)) })
            }
        }
    }

    // Create folder
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建文件夹", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(createName, { createName = it; actionError = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("名称", fontSize = 13.sp, color = muted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.copy(alpha = 0.5f), unfocusedBorderColor = divider, cursorColor = accent, focusedTextColor = onSurface, unfocusedTextColor = onSurface),
                        shape = RoundedCornerShape(8.dp))
                    actionError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFCC4455), fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch { fm.createDirectory(currentDir, createName).fold(onSuccess = { showCreate = false; reload() }, onFailure = { actionError = it.message }) }
            }, enabled = createName.isNotBlank()) { Text("创建", color = if (createName.isNotBlank()) accent else muted) } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消", color = muted) } },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Rename
    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(item.name, color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(renameText, { renameText = it; actionError = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.copy(alpha = 0.5f), unfocusedBorderColor = divider, cursorColor = accent, focusedTextColor = onSurface, unfocusedTextColor = onSurface),
                        shape = RoundedCornerShape(8.dp))
                    actionError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFCC4455), fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch { fm.renameItem(item, renameText).fold(onSuccess = { renameTarget = null; reload() }, onFailure = { actionError = it.message }) }
            }, enabled = renameText.isNotBlank() && renameText != item.name) { Text("确定", color = if (renameText.isNotBlank() && renameText != item.name) accent else muted) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消", color = muted) } },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete — handles both single and batch
    deleteTarget?.let { item ->
        val batchTargets = items.filter { it.file.absolutePath in selectedPaths }
        val isBatch = selectMode && batchTargets.size > 1
        val deleteList = if (isBatch) batchTargets else listOf(item)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (isBatch) "确认批量删除" else "确认删除", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("此操作不可撤销。", color = muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    deleteList.take(6).forEach { t ->
                        Text(
                            if (t.isDirectory) "📁 ${t.name}/" else "📄 ${t.name} (${FileManager.formatSize(t.size)})",
                            color = onSurface, fontSize = 13.sp
                        )
                    }
                    if (deleteList.size > 6) Text("…及其他 ${deleteList.size - 6} 项", color = muted, fontSize = 12.sp)
                    actionError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFCC4455), fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    var failed = 0
                    deleteList.forEach { d ->
                        fm.deleteItem(d).onFailure { failed++ }
                    }
                    if (failed == 0) {
                        deleteTarget = null; selectedPaths = emptySet(); selectMode = false; reload()
                    } else {
                        actionError = "${failed}/${deleteList.size} 删除失败"
                    }
                }
            }) { Text("删除 ${deleteList.size} 项", color = Color(0xFFCC4455), fontWeight = FontWeight.Medium) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消", color = muted) } },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Compress single item dialog
    compressTarget?.let { item ->
        val defaultName = item.name.removeSuffix("/") + ".zip"
        AlertDialog(
            onDismissRequest = { compressTarget = null; compressName = "" },
            title = { Text("压缩", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(if (item.isDirectory) "📁 ${item.name}/" else "📄 ${item.name} (${FileManager.formatSize(item.size)})",
                        color = onSurface, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(compressName, { compressName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("压缩包名称", fontSize = 12.sp, color = muted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.copy(alpha = 0.5f), unfocusedBorderColor = divider, cursorColor = accent, focusedTextColor = onSurface, unfocusedTextColor = onSurface),
                        shape = RoundedCornerShape(8.dp))
                    actionError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFCC4455), fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    val name = compressName.ifBlank { defaultName }
                    val dest = File(currentDir, name)
                    compressTarget = null; compressName = ""
                    isCompressing = true; compressMsg = "正在压缩…"
                    fm.compressItems(listOf(item), dest).fold(
                        onSuccess = { isCompressing = false; Toast.makeText(ctx, "已压缩: $name", Toast.LENGTH_SHORT).show(); reload() },
                        onFailure = { isCompressing = false; actionError = it.message }
                    )
                }
            }, enabled = compressName.isNotBlank() || defaultName.isNotBlank()) { Text("压缩", color = accent) } },
            dismissButton = { TextButton(onClick = { compressTarget = null; compressName = "" }) { Text("取消", color = muted) } },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Multi-compress dialog (batch)
    if (multiCompressTargets.isNotEmpty()) {
        val defaultName = if (multiCompressTargets.size == 1)
            multiCompressTargets.first().name.removeSuffix("/") + ".zip"
        else "archive_${multiCompressTargets.size}.zip"
        AlertDialog(
            onDismissRequest = { multiCompressTargets = emptyList(); compressName = "" },
            title = { Text("批量压缩", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("已选择 ${multiCompressTargets.size} 个项目", color = onSurface, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    multiCompressTargets.take(5).forEach { t ->
                        Text(if (t.isDirectory) "📁 ${t.name}/" else "📄 ${t.name}", color = muted, fontSize = 12.sp)
                    }
                    if (multiCompressTargets.size > 5) Text("…及其他 ${multiCompressTargets.size - 5} 项", color = muted, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(compressName, { compressName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("压缩包名称", fontSize = 12.sp, color = muted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.copy(alpha = 0.5f), unfocusedBorderColor = divider, cursorColor = accent, focusedTextColor = onSurface, unfocusedTextColor = onSurface),
                        shape = RoundedCornerShape(8.dp))
                    actionError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFCC4455), fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = {
                val targets = multiCompressTargets.toList()
                scope.launch {
                    val name = compressName.ifBlank { defaultName }
                    val dest = File(currentDir, name)
                    multiCompressTargets = emptyList(); compressName = ""
                    isCompressing = true
                    compressMsg = "正在压缩 ${targets.size} 个项目…"
                    fm.compressItems(targets, dest).fold(
                        onSuccess = {
                            isCompressing = false
                            selectedPaths = emptySet(); selectMode = false
                            Toast.makeText(ctx, "已压缩: $name", Toast.LENGTH_SHORT).show()
                            reload()
                        },
                        onFailure = {
                            isCompressing = false
                            actionError = it.message
                        }
                    )
                }
            }) { Text("压缩", color = accent) } },
            dismissButton = { TextButton(onClick = { multiCompressTargets = emptyList(); compressName = "" }) { Text("取消", color = muted) } },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Import conflict dialog
    importConflictUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importConflictUri = null },
            title = { Text("文件已存在", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = { Text("「$importConflictName」已存在，要如何处理？", color = onSurface, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val name = importConflictName
                    importConflictUri = null
                    doImport(uri, name)
                }) { Text("覆盖", color = Color(0xFFCC4455)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val base = importConflictName.substringBeforeLast(".")
                    val ext = importConflictName.substringAfterLast(".", "")
                    val newName = if (ext.isNotEmpty() && ext != importConflictName)
                        "${base}_1.$ext" else "${importConflictName}_1"
                    importConflictUri = null
                    doImport(uri, newName)
                }) { Text("重命名", color = accent) }
            },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Decompress dialog
    decompressTarget?.let { item ->
        val targetDir = if (decompressUseFolder) {
            val name = item.name.removeSuffix(".zip")
            File(currentDir, name)
        } else currentDir

        AlertDialog(
            onDismissRequest = { if (!isDecompressing) decompressTarget = null },
            title = { Text("解压", color = onBg, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("将 ${item.name} 解压到:", color = onSurface, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(targetDir.absolutePath, color = muted, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { decompressUseFolder = true }
                            .padding(vertical = 4.dp)) {
                        RadioButton(selected = decompressUseFolder, onClick = { decompressUseFolder = true })
                        Text("以文件夹形式解压", color = onSurface, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { decompressUseFolder = false }
                            .padding(vertical = 4.dp)) {
                        RadioButton(selected = !decompressUseFolder, onClick = { decompressUseFolder = false })
                        Text("直接解压到当前目录", color = onSurface, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { decompressOverwrite = !decompressOverwrite }
                            .padding(vertical = 4.dp)) {
                        Checkbox(checked = decompressOverwrite, onCheckedChange = { decompressOverwrite = it })
                        Text("覆盖同名文件", color = onSurface, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                    }

                    if (isDecompressing) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = accent, trackColor = accent.copy(alpha = 0.15f))
                        Spacer(Modifier.height(4.dp))
                        Text(decompressPhase, color = muted, fontSize = 12.sp)
                    }

                    actionError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFCC4455), fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = {
                actionError = null
                isDecompressing = true; decompressPhase = "解压中..."
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            targetDir.mkdirs()
                            var done = 0
                            java.util.zip.ZipInputStream(item.file.inputStream().buffered()).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    val outFile = File(targetDir, entry.name)
                                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator) &&
                                        outFile.canonicalPath != targetDir.canonicalPath) {
                                        withContext(Dispatchers.Main) { actionError = "路径穿越防护: ${entry.name}" }
                                        return@withContext
                                    }
                                    if (entry.isDirectory) outFile.mkdirs()
                                    else {
                                        if (outFile.exists() && !decompressOverwrite) {
                                            // skip
                                        } else {
                                            outFile.parentFile?.mkdirs()
                                            FileOutputStream(outFile).use { zis.copyTo(it) }
                                        }
                                    }
                                    zis.closeEntry()
                                    done++
                                    withContext(Dispatchers.Main) {
                                        decompressPhase = "已解压 $done 个文件..."
                                    }
                                    entry = zis.nextEntry
                                }
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "解压完成", Toast.LENGTH_SHORT).show()
                                isDecompressing = false; decompressTarget = null
                                reload()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                actionError = "解压失败: ${e.message}"; isDecompressing = false
                            }
                        }
                    }
                }
            }, enabled = !isDecompressing) { Text("解压", color = if (isDecompressing) muted else accent) } },
            dismissButton = { TextButton(onClick = { if (!isDecompressing) decompressTarget = null }) { Text("取消", color = muted) } },
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Compression progress dialog
    if (isCompressing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("处理中", fontWeight = FontWeight.SemiBold, color = onBg) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(compressMsg, color = onSurface, fontSize = 14.sp)
                }
            },
            confirmButton = {},
            containerColor = surface, shape = RoundedCornerShape(16.dp)
        )
    }

    // Full-screen text editor overlay (not Dialog — inherits Activity IME resize)
    viewText?.let { item -> VirtualizedEditor(item, fm, accent, onClose = { viewText = null }) }
    viewImage?.let { item -> ImageDialog(item, onClose = { viewImage = null }) }
}

// ── Helpers ──

@Composable
private fun ScrollableRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), content = content)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextEditorOverlay(item: FileItem, fm: FileManager, accent: Color, onClose: () -> Unit) {
    var txt by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val onSurface = Color(0xFF1A1A1A)
    val muted = Color(0xFF888888)
    val hasChanges = txt != original
    val transformer = remember(item) { SyntaxHighlightTransform(item.extension) }

    // ── Pinch-to-zoom state ──
    var fontScale by remember { mutableFloatStateOf(1f) }
    val baseFontSize = 13
    val lineHeightSp = remember(fontScale) { (baseFontSize * fontScale * 1.54f).sp }
    val fontSizeSp = remember(fontScale) { (baseFontSize * fontScale).sp }

    // ── Undo / redo ──
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }
    var prevTxt by remember { mutableStateOf("") }
    var isUndoRedo by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        fm.readText(item.file).fold(
            onSuccess = { txt = it; original = it; tfv = TextFieldValue(it); prevTxt = it },
            onFailure = { txt = "读取失败: ${it.message}" }
        )
        loading = false
    }

    // Back handler
    androidx.activity.compose.BackHandler { if (hasChanges) showExitDialog = true else onClose() }

    // ── Cursor tracking ──
    val curLine = remember(tfv) {
        val sel = tfv.selection.start.coerceAtMost(tfv.text.length)
        tfv.text.substring(0, sel).count { it == '\n' } + 1
    }
    val lineHPx = with(density) { lineHeightSp.toPx() }.toInt()
    val lineCount = remember(txt) { txt.count { it == '\n' } + 1 }

    // ── Shared scroll state — wraps BOTH line numbers AND text editor ──
    val textScroll = rememberScrollState()

    // ── Keyboard-aware cursor tracking ──
    // Two triggers: 1) onTextLayout (cursor moved), 2) onSizeChanged (keyboard appeared)
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope2 = rememberCoroutineScope()
    var cursorRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    fun onZoom(z: Float) { fontScale = (fontScale * z).coerceIn(0.5f, 3f) }

    fun doUndo() {
        if (undoStack.isEmpty()) return
        isUndoRedo = true
        redoStack.add(txt)
        val prev = undoStack.removeAt(undoStack.lastIndex)
        tfv = TextFieldValue(prev); txt = prev; prevTxt = prev
        isUndoRedo = false
    }
    fun doRedo() {
        if (redoStack.isEmpty()) return
        isUndoRedo = true
        undoStack.add(txt)
        val next = redoStack.removeAt(redoStack.lastIndex)
        tfv = TextFieldValue(next); txt = next; prevTxt = next
        isUndoRedo = false
    }

    // ── Full-screen overlay ──
    Box(
        Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).statusBarsPadding()
            // Custom pinch-to-zoom: only consumes 2+ finger events, single-finger passes through
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // Manual zoom calc from first two pressed pointers
                            val c = pressed.take(2)
                            val prevD = (c[0].previousPosition - c[1].previousPosition).getDistance()
                            val curD  = (c[0].position - c[1].position).getDistance()
                            if (prevD > 1f) {
                                val z = curD / prevD
                                if (z != 1f) onZoom(z)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Top bar ──
            Surface(color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (hasChanges) showExitDialog = true else onClose() }, enabled = !saving, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("←", color = accent, fontSize = 18.sp)
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(item.name, color = Color(0xFF1A1A1A), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("UTF-8 · ${item.extension.uppercase()}", color = muted, fontSize = 10.sp)
                    }
                    if (hasChanges) Text("●", color = accent, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                    // Undo
                    IconButton(onClick = { doUndo() }, enabled = undoStack.isNotEmpty(), modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Outlined.Undo, "撤销", tint = if (undoStack.isNotEmpty()) onSurface else muted.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                    // Redo
                    IconButton(onClick = { doRedo() }, enabled = redoStack.isNotEmpty(), modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Outlined.Redo, "重做", tint = if (redoStack.isNotEmpty()) onSurface else muted.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                    // Save
                    TextButton(
                        onClick = {
                            scope.launch {
                                saving = true; saveError = null
                                withContext(Dispatchers.IO) { fm.writeText(item.file, txt) }
                                    .fold(
                                        onSuccess = { original = txt; undoStack.clear(); redoStack.clear(); Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() },
                                        onFailure = { saveError = it.message }
                                    )
                                saving = false
                            }
                        },
                        enabled = hasChanges && !saving, contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text(if (saving) "…" else "保存", color = if (hasChanges && !saving) Color(0xFF2E7D32) else muted, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                }
            }
            saveError?.let { Text(it, color = Color(0xFFCC4455), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }

            // ── Editor area ──
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp)) }
            } else {
                // Shared scroll: wraps line numbers + separator + text field together
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(textScroll)
                        .onSizeChanged { _ ->
                            // Keyboard appeared/disappeared — re-scroll to cursor
                            scope2.launch {
                                kotlinx.coroutines.delay(300)
                                bringIntoView.bringIntoView(cursorRect)
                            }
                        }
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        // Line numbers — inside shared scroll, always synced
                        Column(modifier = Modifier.width(36.dp).padding(top = 8.dp, end = 4.dp)) {
                            for (ln in 1..lineCount) {
                                Text(ln.toString(),
                                    fontFamily = FontFamily.Monospace, fontSize = fontSizeSp,
                                    lineHeight = lineHeightSp, color = muted,
                                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                            }
                            // Extra space so last lines scroll above keyboard
                            Spacer(Modifier.height(300.dp))
                        }
                        // Separator — height matches line number content
                        val sepH = with(density) { ((lineCount * lineHPx) + 300 * density.density).toDp() }
                        Box(Modifier.width(1.dp).height(sepH).background(muted.copy(alpha = 0.15f)))
                        Spacer(Modifier.width(6.dp))
                        // Editor
                        BasicTextField(
                            value = tfv,
                            onValueChange = { newVal ->
                                if (!isUndoRedo) {
                                    undoStack.add(prevTxt)
                                    if (undoStack.size > 100) undoStack.removeAt(0)
                                    redoStack.clear()
                                }
                                prevTxt = newVal.text
                                tfv = newVal; txt = newVal.text
                            },
                            onTextLayout = { layoutResult ->
                                val rect = layoutResult.getCursorRect(tfv.selection.start)
                                cursorRect = rect
                                scope2.launch {
                                    bringIntoView.bringIntoView(rect)
                                }
                            },
                            modifier = Modifier.weight(1f)
                                .bringIntoViewRequester(bringIntoView)
                                .onFocusChanged { fs ->
                                    if (fs.isFocused) {
                                        scope2.launch {
                                            kotlinx.coroutines.delay(350)
                                            bringIntoView.bringIntoView(cursorRect)
                                        }
                                    }
                                },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace, fontSize = fontSizeSp,
                                color = onSurface, lineHeight = lineHeightSp),
                            visualTransformation = transformer,
                            cursorBrush = SolidColor(accent),
                            decorationBox = { inner -> Box(Modifier.padding(vertical = 8.dp)) { inner() } }
                        )
                    }
                }
            }
        }
    }

    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("未保存的修改", fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A)) },
            text = { Text("你有未保存的修改，退出将丢失这些更改。", color = muted) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    scope.launch {
                        saving = true
                        withContext(Dispatchers.IO) { fm.writeText(item.file, txt) }
                            .fold(onSuccess = { onClose() }, onFailure = { saveError = it.message })
                        saving = false
                    }
                }) { Text("保存并退出", color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitDialog = false }) { Text("取消", color = muted) }
                    TextButton(onClick = onClose) { Text("不保存", color = Color(0xFFC62828)) }
                }
            },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ImageDialog(item: FileItem, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).clickable(onClick = onClose)) {
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(item.file).crossfade(true).build(), null,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center), contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, color = Color(0xFFF0EDE0), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFFF0EDE0)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(item: FileItem, accent: Color, muted: Color, onSurface: Color, selectMode: Boolean = false, isSelected: Boolean = false, isHighlighted: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    val icon = when {
        item.isDirectory -> Icons.Outlined.Folder
        FileManager.isImage(item.extension) -> Icons.Outlined.Image
        FileManager.isText(item.extension) -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    val iconTint = when {
        item.isDirectory -> accent
        FileManager.isImage(item.extension) -> Color(0xFF6B8EC2)
        FileManager.isText(item.extension) -> Color(0xFF6B5B9E)
        else -> muted.copy(alpha = 0.5f)
    }
    // Highlight: golden pulse border
    val highlightBg = if (isHighlighted) accent.copy(alpha = 0.12f) else Color.Transparent
    val highlightBorder = if (isHighlighted) accent.copy(alpha = 0.6f) else Color.Transparent
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .then(if (isSelected) Modifier.background(accent.copy(alpha = 0.08f)) else Modifier)
            .then(if (isHighlighted) Modifier.background(highlightBg, RoundedCornerShape(8.dp)) else Modifier)
            .then(if (isHighlighted) Modifier.border(2.dp, highlightBorder, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Checkbox in select mode
            if (selectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted.copy(alpha = 0.4f)),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name + if (item.isDirectory) "/" else "", color = if (item.isDirectory) onSurface else onSurface.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    if (!item.isDirectory) { Text(FileManager.formatSize(item.size), color = muted, fontSize = 11.sp); Text(" · ", color = muted.copy(alpha = 0.4f), fontSize = 11.sp) }
                    Text(FileManager.formatDate(item.lastModified), color = muted, fontSize = 11.sp)
                }
            }
            if (!selectMode) {
                Icon(Icons.Outlined.ChevronRight, null, tint = muted.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Syntax Highlighting ──

private val SynKeyword  = Color(0xFF7B2FBE)
private val SynString   = Color(0xFF2E7D32)
private val SynNumber   = Color(0xFF1565C0)
private val SynComment  = Color(0xFF999999)

// Keywords per language
private val KW_JS = Pattern.compile("\\b(function|var|let|const|if|else|for|while|do|switch|case|return|new|this|class|extends|super|import|export|default|from|async|await|try|catch|throw|typeof|instanceof|in|of|delete|void|yield|static|get|set|true|false|null|undefined|break|continue)\\b")
private val KW_JSON = Pattern.compile("\\b(true|false|null)\\b")
private val KW_PY = Pattern.compile("\\b(def|class|if|elif|else|for|while|try|except|finally|with|as|import|from|return|yield|raise|pass|break|continue|and|or|not|in|is|lambda|True|False|None|self|async|await)\\b")
private val KW_KT = Pattern.compile("\\b(val|var|fun|class|object|interface|enum|data|sealed|abstract|open|override|private|protected|public|internal|companion|init|constructor|this|super|if|else|when|for|while|do|return|break|continue|try|catch|finally|throw|import|package|as|is|in|null|true|false|suspend|inline|let|run|also|apply|with|String|Int|Long|Float|Double|Boolean|List|Map|Set)\\b")
private val KW_JAVA = Pattern.compile("\\b(public|private|protected|static|final|abstract|class|interface|enum|extends|implements|new|this|super|if|else|for|while|do|switch|case|return|break|continue|try|catch|throw|throws|import|package|void|int|long|float|double|boolean|char|byte|short|true|false|null|instanceof)\\b")
private val KW_C = Pattern.compile("\\b(auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|int|long|register|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|NULL|true|false|include|define|ifdef|ifndef|endif|pragma|class|namespace|using|template|typename|new|delete|this|try|catch|operator|public|private|protected)\\b")
private val KW_SH = Pattern.compile("\\b(if|then|else|elif|fi|for|while|do|done|case|esac|in|function|return|exit|export|local|readonly|declare|source|echo|printf|test|eval|exec|set|unset|shift)\\b")

private val RE_STRING  = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
private val RE_SSTRING = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
private val RE_NUMBER  = Pattern.compile("\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b")
private val RE_DSLASH  = Pattern.compile("//[^\n]*")
private val RE_HASH    = Pattern.compile("#[^\n]*")
private val RE_BLOCK   = Pattern.compile("/\\*[\\s\\S]*?\\*/")

class SyntaxHighlightTransform(ext: String) : VisualTransformation {

    private val kw: Pattern?
    private val hasDSlash: Boolean
    private val hasHash: Boolean

    init {
        when (ext.lowercase()) {
            "js" -> { kw = KW_JS; hasDSlash = true; hasHash = false }
            "ts" -> { kw = KW_JS; hasDSlash = true; hasHash = false }
            "json" -> { kw = KW_JSON; hasDSlash = false; hasHash = false }
            "py" -> { kw = KW_PY; hasDSlash = false; hasHash = true }
            "kt" -> { kw = KW_KT; hasDSlash = true; hasHash = false }
            "java" -> { kw = KW_JAVA; hasDSlash = true; hasHash = false }
            "c", "cpp", "h", "hpp" -> { kw = KW_C; hasDSlash = true; hasHash = false }
            "sh" -> { kw = KW_SH; hasDSlash = false; hasHash = true }
            "yaml", "yml", "cfg", "conf", "ini", "properties", "toml" -> { kw = null; hasDSlash = false; hasHash = true }
            "xml", "html", "htm" -> { kw = null; hasDSlash = false; hasHash = false }
            else -> { kw = null; hasDSlash = true; hasHash = true }
        }
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(AnnotatedString(""), OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(raw)
        val n = raw.length
        try {
            if (hasDSlash) applyPattern(builder, raw, RE_DSLASH, SynComment, n)
            if (hasHash)   applyPattern(builder, raw, RE_HASH, SynComment, n)
            applyPattern(builder, raw, RE_BLOCK, SynComment, n)
            applyPattern(builder, raw, RE_STRING, SynString, n)
            applyPattern(builder, raw, RE_SSTRING, SynString, n)
            if (kw != null) applyPattern(builder, raw, kw, SynKeyword, n)
            applyPattern(builder, raw, RE_NUMBER, SynNumber, n)
        } catch (_: Exception) { return TransformedText(AnnotatedString(raw), OffsetMapping.Identity) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun applyPattern(builder: AnnotatedString.Builder, raw: String, pat: Pattern, color: Color, maxLen: Int) {
        val m = pat.matcher(raw)
        val italic = color == SynComment
        while (m.find()) {
            val s = m.start(); val e = m.end().coerceAtMost(maxLen)
            if (s < e) builder.addStyle(SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null), s, e)
        }
    }
}
