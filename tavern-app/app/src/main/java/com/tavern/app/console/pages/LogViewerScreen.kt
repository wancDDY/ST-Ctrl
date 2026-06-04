package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        logLines = withContext(Dispatchers.IO) {
            val coreDir = AssetExtractor.getCoreDir(ctx)
            val lines = mutableListOf<String>()
            // Read content.log
            val logFile = File(coreDir, "data/default-user/content.log")
            if (logFile.exists()) {
                try {
                    logFile.readLines().takeLast(200).forEach { lines.add(it) }
                } catch (_: Exception) {}
            }
            if (lines.isEmpty()) lines.add("(暂无日志)")
            lines
        }
        loading = false
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("日志查看", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("最近 200 条服务器日志", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(16.dp))

            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFD4A853))
                }
            } else {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0A0A10), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                        items(logLines) { line ->
                            val color = when {
                                line.contains("error", ignoreCase = true) || line.contains("fail", ignoreCase = true) -> Color(0xFFCC4455)
                                line.contains("warn", ignoreCase = true) -> Color(0xFFD4A853)
                                else -> Color(0xFF8A8A80)
                            }
                            Text(line, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
