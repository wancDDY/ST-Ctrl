package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.node.NodeState

@Composable
fun ServerStatusScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit,
    onStartNode: () -> Unit = {},
    onStopNode: () -> Unit = {}
) {
    val state by viewModel.nodeState.collectAsState()
    val port by viewModel.nodePort.collectAsState()
    val isRunning = state == NodeState.State.RUNNING
    val isStarting = state == NodeState.State.STARTING

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("服务器状态", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(24.dp))

            // Status indicator card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isRunning -> Color(0xFF5AA87A)
                                    isStarting -> Color(0xFFD4A853)
                                    else -> Color(0xFFCC4455)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            when {
                                isRunning -> "运行中"
                                isStarting -> "启动中"
                                else -> "已停止"
                            },
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                isRunning -> "服务器正在正常运行"
                                isStarting -> "服务器正在启动，请稍候…"
                                else -> "服务器当前未启动"
                            },
                            color = Color(0xFF8A8A80),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    InfoRow(icon = Icons.Outlined.Lan, label = "端口", value = port.toString())
                    InfoRow(icon = Icons.Outlined.Language, label = "地址", value = "127.0.0.1:$port")
                    InfoRow(icon = Icons.Outlined.Tag, label = "ST 版本", value = "1.18.0")
                    InfoRow(icon = Icons.Outlined.Info, label = "Node 版本", value = "v24.5.0")
                    InfoRow(
                        icon = if (isRunning) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        label = "服务状态",
                        value = state.name
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isRunning) {
                Button(onClick = onStopNode, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC4455).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.StopCircle, null, tint = Color(0xFFCC4455), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("停止服务", color = Color(0xFFCC4455), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Button(onClick = onStartNode, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5AA87A).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = Color(0xFF5AA87A), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("启动服务", color = Color(0xFF5AA87A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color(0xFFD4A853),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = Color(0xFF8A8A80), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
