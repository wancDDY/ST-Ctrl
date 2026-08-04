package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.log.TavernLog
import kotlinx.coroutines.*

private fun ansiToAnnotated(raw: String) = buildAnnotatedString {
    var i = 0
    var depth = 0
    while (i < raw.length) {
        when {
            depth < 20 && raw.startsWith(TavernLog.C_RED, i)     -> { i += TavernLog.C_RED.length;     depth++; pushStyle(SpanStyle(color = Color(0xFFFF6B6B))) }
            depth < 20 && raw.startsWith(TavernLog.C_GREEN, i)   -> { i += TavernLog.C_GREEN.length;   depth++; pushStyle(SpanStyle(color = Color(0xFF69DB7C))) }
            depth < 20 && raw.startsWith(TavernLog.C_YELLOW, i)  -> { i += TavernLog.C_YELLOW.length;  depth++; pushStyle(SpanStyle(color = Color(0xFFFFD43B))) }
            depth < 20 && raw.startsWith(TavernLog.C_BLUE, i)    -> { i += TavernLog.C_BLUE.length;    depth++; pushStyle(SpanStyle(color = Color(0xFF74C0FC))) }
            depth < 20 && raw.startsWith(TavernLog.C_MAGENTA, i) -> { i += TavernLog.C_MAGENTA.length; depth++; pushStyle(SpanStyle(color = Color(0xFFDA77F2))) }
            depth < 20 && raw.startsWith(TavernLog.C_CYAN, i)    -> { i += TavernLog.C_CYAN.length;    depth++; pushStyle(SpanStyle(color = Color(0xFF63E6BE))) }
            depth < 20 && raw.startsWith(TavernLog.C_GRAY, i)    -> { i += TavernLog.C_GRAY.length;    depth++; pushStyle(SpanStyle(color = Color(0xFF8A8A80))) }
            raw.startsWith(TavernLog.C_RESET, i)   -> { i += TavernLog.C_RESET.length;   if (depth > 0) { depth--; try { pop() } catch (_: Exception) {} } }
            else -> { append(raw[i]); i++ }
        }
    }
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rawLines by TavernLog.lines.collectAsState()
    var autoScroll by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { TavernLog.pollServerLog(ctx) }
            delay(3000)
        }
    }

    val displayLines = remember(rawLines) {
        rawLines.filter {
            !it.contains("ssl_client_socket") && !it.contains("handshake failed") &&
            !it.contains("Adding missing config") && !it.contains("Migrating config values")
        }.filter { it.startsWith(TavernLog.C_BLUE) }
         .takeLast(5000)
         .ifEmpty { listOf("${TavernLog.C_GRAY}暂无服务端日志$TavernLog.C_RESET") }
    }

    LaunchedEffect(displayLines.size) {
        if (autoScroll && displayLines.isNotEmpty()) listState.animateScrollToItem(displayLines.size - 1)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 3f) } }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { TavernLog.clear(ctx) }) {
                    Icon(Icons.Outlined.ClearAll, null, tint = Color(0xFF8A8A80), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空", color = Color(0xFF8A8A80), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("服务端日志", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))

            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0A0A10),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                        items(displayLines) { line ->
                            Text(ansiToAnnotated(line),
                                fontSize = (11f * scale).sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = (16f * scale).sp)
                        }
                        item { Text("> _", color = Color(0xFF5AA87A), fontSize = (11f * scale).sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
            }
        }
}
