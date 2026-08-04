package com.tavern.app.console.pages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ClearCacheScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var sizesRefreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(sizesRefreshKey) {
        sizes = withContext(Dispatchers.IO) {
            // Protected items same as ConsoleViewModel.clearAppCache
            val protected = setOf(
                "WebView", "tavern-node",
                "data-extract-bak", "ext-backup", "data-update-bak", "ext-update-bak", "core-update-bak",
                "core-update-tmp"
            )
            // WebView cache size (recursive, skip symlinks)
            val wc = File(ctx.cacheDir, "WebView")
            val webviewSize = if (wc.exists()) wc.walkTopDown()
                .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .sumOf { it.length() } else 0L
            // Temp files (non-protected, recursive for dirs)
            var tmpSize = 0L
            ctx.cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                val isProtected = n in protected || n.startsWith("tavern-update-") || n.startsWith("ext-tmp-") || (n.startsWith("ext-") && n.endsWith(".zip"))
                if (!isProtected) {
                    tmpSize += if (f.isDirectory) f.walkTopDown()
                        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                        .sumOf { it.length() } else f.length()
                }
            }
            webviewSize to tmpSize
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp) }
            Spacer(modifier = Modifier.height(24.dp))
            Text("清除缓存", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("清理临时文件释放存储空间", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(24.dp))

            if (clearing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color(0xFFD4A853), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在清除…", color = Color(0xFFD4A853), fontSize = 14.sp)
                }
            } else if (clearError != null) {
                Text("清除失败: $clearError", color = Color(0xFFCC4455), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { clearError = null }) { Text("重试", color = Color(0xFFD4A853)) }
            } else {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        ClearRow("WebView 缓存", sizes?.first)
                        ClearRow("其他临时文件", sizes?.second)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC4455).copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)) {
                    Text("清除缓存", color = Color(0xFFCC4455), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showConfirm) ConfirmDialog(title = "清除缓存", message = "将清除 WebView 缓存和临时文件。不影响用户数据和角色卡。", confirmText = "清除",
        onConfirm = {
            showConfirm = false
            clearing = true
            clearError = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { viewModel.clearAppCache() }
                    withContext(Dispatchers.Main) {
                        val msg = if (bytes == 0L) "没有可清除的缓存" else "已释放 ${formatBytes(bytes)}"
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        sizesRefreshKey++ // trigger re-scan
                    }
                } catch (e: Exception) {
                    clearError = e.message ?: "未知错误"
                } finally {
                    clearing = false
                }
            }
        },
        onDismiss = { showConfirm = false })
}

@Composable
private fun ClearRow(label: String, bytes: Long?) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
        Text(if (bytes != null) formatBytes(bytes) else "计算中…", color = Color(0xFF8A8A80), fontSize = 14.sp)
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes <= 0) "0 B" else com.tavern.app.util.FormatUtils.fileSize(bytes)
