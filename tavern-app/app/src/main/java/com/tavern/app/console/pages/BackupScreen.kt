package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ConfirmDialog
import java.io.File

@Composable
fun BackupScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    val progress by viewModel.backupProgress.collectAsState()
    val phase by viewModel.backupPhase.collectAsState()
    val log by viewModel.backupLog.collectAsState()
    val result by viewModel.backupResult.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.clearBackupState() }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp) }
            Spacer(modifier = Modifier.height(24.dp))
            Text("创建备份", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("完整备份：聊天记录 · 角色卡 · 世界书 · 预设 · UI主题 · 扩展 · 配置", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(24.dp))

            if (progress != null || result != null) {
                // Backup in progress or done
                val isDone = result != null && result!!.isSuccess
                val (cur, total) = progress ?: (0 to 0)
                val pct = if (isDone) 100 else if (total > 0) (cur * 100 / total) else 0
                val barColor = if (isDone) Color(0xFF5AA87A) else Color(0xFFD4A853)

                if (isDone) {
                    Text("已完成", color = Color(0xFF5AA87A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    result!!.getOrNull()?.let { file ->
                        Text(file.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp,
                            modifier = Modifier.clickable { renameTargetFile = file; renameText = file.nameWithoutExtension; showRenameDialog = true })
                        Text("${file.length() / 1024 / 1024} MB · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(file.lastModified())}", color = Color(0xFF8A8A80), fontSize = 11.sp)
                        Text("点击文件名可重命名", color = Color(0xFF8A8A80).copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                } else {
                    Text(phase.ifEmpty { "正在备份..." }, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = barColor, trackColor = MaterialTheme.colorScheme.surface)
                if (!isDone && total > 0) Text("$pct% ($cur/$total)", color = Color(0xFF8A8A80), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                // Terminal
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0A0A10), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                        val lines = log.ifEmpty { listOf("$ 等待中...") }
                        items(lines) { line -> Text(line, color = Color(0xFF8A8A80), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp) }
                        item { Text("> _", color = Color(0xFF5AA87A), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (result != null) {
                    result!!.fold(
                        onSuccess = { Button(onClick = { viewModel.clearBackupState() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)), shape = RoundedCornerShape(12.dp)) { Text("再次备份", color = Color(0xFFD4A853)) } },
                        onFailure = { e -> Text("❌ ${e.message}", color = Color(0xFFCC4455), fontSize = 14.sp); Button(onClick = { viewModel.clearBackupState(); viewModel.startBackup() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)), shape = RoundedCornerShape(12.dp)) { Text("重试", color = Color(0xFFD4A853)) } }
                    )
                }
            } else {
                // Idle
                Text("备份范围：用户数据 · 扩展程序 · UI 主题 · 用户头像 · 核心配置", color = Color(0xFF8A8A80), fontSize = 12.sp)
                Text("恢复后将完全还原到备份时的状态", color = Color(0xFF8A8A80), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)), shape = RoundedCornerShape(14.dp)) { Text("开始备份", color = Color(0xFFD4A853), fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }

    if (showRenameDialog && renameTargetFile != null) {
        AlertDialog(onDismissRequest = { showRenameDialog = false }, title = { Text("重命名备份") }, text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { renameTargetFile?.let { f -> if (renameText.isNotBlank()) { File(f.parentFile, renameText + ".zip").let { f.renameTo(it) }; viewModel.clearBackupState() } }; showRenameDialog = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurface)
    }

    if (showConfirm) ConfirmDialog(title = "创建备份", message = "完整备份酒馆全部数据（聊天记录、角色卡、世界书、预设、UI主题、扩展程序、配置等）。\n\n备份文件保存到 Documents/TavernBackups/。\n\n恢复后将完全还原到备份时的状态。", onConfirm = { showConfirm = false; viewModel.startBackup() }, onDismiss = { showConfirm = false })
}
