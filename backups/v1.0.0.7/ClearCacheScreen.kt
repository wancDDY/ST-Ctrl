package com.tavern.app.console.pages

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
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ClearCacheScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf<Long?>(null) }
    val ctx = LocalContext.current
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    LaunchedEffect(Unit) {
        sizes = withContext(Dispatchers.IO) {
            val wc = File(ctx.cacheDir, "WebView")
            val webviewSize = if (wc.exists()) wc.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
            val tmpSize = ctx.cacheDir.listFiles()?.filter { it.name != "WebView" && it.name != "tavern-node" }?.sumOf { it.length() } ?: 0L
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

            if (cleared != null) {
                Text("✅ 已释放 ${formatBytes(cleared!!)}", color = Color(0xFF5AA87A), fontSize = 16.sp)
            } else {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        ClearRow("WebView 缓存", sizes?.first)
                        ClearRow("临时文件", sizes?.second)
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
        onConfirm = { showConfirm = false; cleared = viewModel.clearAppCache() }, onDismiss = { showConfirm = false })
}

@Composable
private fun ClearRow(label: String, bytes: Long?) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
        Text(if (bytes != null) formatBytes(bytes) else "计算中…", color = Color(0xFF8A8A80), fontSize = 14.sp)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
