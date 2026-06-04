package com.tavern.app.console.pages

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.backup.BackupMetadata
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ConfirmDialog
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@Composable
fun RestoreScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit,
    onRestoreComplete: () -> Unit
) {
    var backups by remember { mutableStateOf<List<Pair<File, BackupMetadata>>?>(null) }
    var selected by remember { mutableStateOf<Pair<File, BackupMetadata>?>(null) }
    var selectedBackup by remember { mutableStateOf<Pair<File, BackupMetadata>?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    val progress by viewModel.restoreProgress.collectAsState()
    val phase by viewModel.restorePhase.collectAsState()
    val log by viewModel.restoreLog.collectAsState()
    val result by viewModel.restoreResult.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // File picker for importing backup from file system
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val tmpFile = File(ctx.cacheDir, "imported-backup-${System.currentTimeMillis()}.zip")
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Validate: try to read metadata
                    try {
                        val meta = viewModel.backupManager.readMetadata(tmpFile)
                        if (meta != null) {
                            selected = tmpFile to meta
                            showConfirm = true
                        } else {
                            Toast.makeText(ctx, "该文件不是有效的酒馆备份", Toast.LENGTH_LONG).show()
                            tmpFile.delete()
                        }
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "无法读取备份文件，文件可能已损坏", Toast.LENGTH_LONG).show()
                        tmpFile.delete()
                    }
                } catch (_: Exception) {
                    Toast.makeText(ctx, "无法读取选择的文件", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.clearRestoreState()
        selected = null
        backups = viewModel.backupManager.listBackups()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = Color(0xFFD4A853),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("还原备份", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                TextButton(onClick = { filePicker.launch(arrayOf("application/zip", "*/*")) }) {
                    Text("选择文件", color = Color(0xFFD4A853), fontSize = 14.sp)
                }
            }
            Text("选择备份文件恢复用户数据", fontSize = 13.sp, color = Color(0xFF8A8A80))

            Spacer(modifier = Modifier.height(20.dp))

            if (result != null) {
                result!!.fold(
                    onSuccess = {
                        LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Color(0xFF5AA87A), trackColor = MaterialTheme.colorScheme.surface)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("还原成功", color = Color(0xFF5AA87A), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        selected?.let { (file, meta) ->
                            Text(file.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                            Text("${meta.fileCount} 文件 · ${meta.totalSizeBytes / 1024 / 1024} MB", color = Color(0xFF8A8A80), fontSize = 12.sp)
                        }
                        Text("建议重启应用以应用更改", color = Color(0xFF8A8A80), fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onRestoreComplete,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("返回控制台", color = Color(0xFFD4A853)) }
                            Button(
                                onClick = {
                                    ctx.getSystemService(android.app.ActivityManager::class.java)
                                        .appTasks?.forEach { it.finishAndRemoveTask() }
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853)),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("重启应用", color = Color(0xFF08080E), fontWeight = FontWeight.Medium) }
                        }
                    },
                    onFailure = { e ->
                        Text("❌ 还原失败: ${e.message}", color = Color(0xFFCC4455), fontSize = 14.sp)
                    }
                )
            } else if (progress != null) {
                val (cur, total) = progress!!
                val pct = if (total > 0) (cur * 100 / total) else 0
                Text(phase, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(progress = { if (total > 0) cur.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Color(0xFFD4A853), trackColor = MaterialTheme.colorScheme.surface)
                if (total > 0) Text("$pct% ($cur/$total)", color = Color(0xFF8A8A80), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                // Terminal
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0A0A10), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val listState = rememberLazyListState()
                    val lines = log.ifEmpty { listOf("\$ 等待中...") }
                    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size) }
                    LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                        items(lines) { line ->
                            Text(line, color = Color(0xFF8A8A80), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 16.sp)
                        }
                        item { Text("> _", color = Color(0xFF5AA87A), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else if (backups == null) {
                CircularProgressIndicator(color = Color(0xFFD4A853))
            } else if (backups!!.isEmpty()) {
                Text("暂无备份文件", color = Color(0xFF8A8A80), fontSize = 15.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(backups!!) { (file, meta) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        selectedBackup = file to meta
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        file.name.replace(".zip", "").replace("TavernBackup_", ""),
                                        color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clickable {
                                            renameTargetFile = file
                                            renameText = file.nameWithoutExtension
                                            showRenameDialog = true
                                        }
                                    )
                                    Text(
                                        "${meta.fileCount} 文件 · ${meta.totalSizeBytes / 1024 / 1024} MB",
                                        color = Color(0xFF8A8A80), fontSize = 12.sp
                                    )
                                }
                                IconButton(onClick = { deleteTarget = file }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除备份",
                                        tint = Color(0xFFCC4455)
                                    )
                                }
                                Text("›", color = Color(0xFFD4A853), fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog && renameTargetFile != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名备份") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = renameTargetFile
                    if (file != null && renameText.isNotBlank()) {
                        val newFile = File(file.parentFile, renameText + ".zip")
                        if (file.renameTo(newFile)) {
                            scope.launch { backups = viewModel.backupManager.listBackups() }
                        }
                    }
                    showRenameDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    if (selectedBackup != null) {
        val (file, meta) = selectedBackup!!
        var contents by remember(file) { mutableStateOf<List<String>?>(null) }
        LaunchedEffect(file) {
            contents = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { getBackupContents(file) }
        }
        AlertDialog(
            onDismissRequest = { selectedBackup = null },
            title = {
                Text(file.nameWithoutExtension)
            },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${meta.fileCount} 文件",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${meta.totalSizeBytes / 1024 / 1024} MB",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { selected = file to meta; showConfirm = true; selectedBackup = null },
                            modifier = Modifier.weight(1f)) { Text("恢复", color = Color(0xFFD4A853)) }
                        FilledTonalButton(onClick = {
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/zip"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                ctx.startActivity(android.content.Intent.createChooser(intent, "打开方式"))
                            } catch (_: Exception) { Toast.makeText(ctx, "无法打开", Toast.LENGTH_SHORT).show() }
                        }, modifier = Modifier.weight(1f)) { Text("打开位置", color = Color(0xFFD4A853), fontSize = 13.sp) }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF3A3A3A))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "备份内容",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (contents == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFD4A853))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在读取…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                    } else if (contents!!.isEmpty()) {
                        Text("无法读取备份内容", color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(contents!!) { entry ->
                                Text(entry, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 2.dp).clickable {
                                        try {
                                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/zip"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                            ctx.startActivity(android.content.Intent.createChooser(intent, "打开方式"))
                                        } catch (_: Exception) { Toast.makeText(ctx, "无法打开", Toast.LENGTH_SHORT).show() }
                                    })
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedBackup = null }) { Text("关闭") }
            },
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    if (showConfirm && selected != null) {
        ConfirmDialog(
            title = "还原备份",
            message = "将用「${selected!!.first.name}」覆盖当前所有用户数据。此操作不可撤销，确定继续？",
            confirmText = "还原",
            onConfirm = { showConfirm = false; viewModel.startRestore(selected!!.first) },
            onDismiss = { showConfirm = false }
        )
    }

    if (deleteTarget != null) {
        ConfirmDialog(
            title = "删除备份",
            message = "确定要删除此备份吗？",
            confirmText = "删除",
            onConfirm = {
                val file = deleteTarget!!
                deleteTarget = null
                scope.launch {
                    viewModel.backupManager.deleteBackup(file)
                    backups = viewModel.backupManager.listBackups()
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

private fun getBackupContents(zipFile: File): List<String> {
    val entries = mutableListOf<String>()
    try {
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val size = if (entry.size > 0) " (${entry.size / 1024} KB)" else ""
                    entries.add(entry.name + size)
                }
                entry = zis.nextEntry
            }
        }
    } catch (_: Exception) {}
    return entries
}

