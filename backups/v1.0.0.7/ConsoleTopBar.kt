package com.tavern.app.console.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.ThemeState
import com.tavern.app.node.NodeState

@Composable
fun ConsoleTopBar(modifier: Modifier = Modifier) {
    val state by NodeState.state.collectAsState()
    val isRunning = state == NodeState.State.RUNNING
    val textColor = MaterialTheme.colorScheme.onBackground

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "ST Ctrl", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = textColor, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = if (isRunning) Color(0xFF5AA87A) else Color(0xFFCC4455)
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = dotColor, radius = size.minDimension / 2) }
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = if (isRunning) "运行中" else "已停止", fontSize = 11.sp, color = textColor.copy(alpha = 0.6f))
        }
    }
}
