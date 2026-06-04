package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.components.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
fun RestartScreen(onBack: () -> Unit, onDoRestart: suspend () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bg = MaterialTheme.colorScheme.background

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack, enabled = !restarting) { Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp) }
            Spacer(modifier = Modifier.height(24.dp))
            Text("一键重启", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("停止并重新启动酒馆服务", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(32.dp))

            if (restarting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (done) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF5AA87A), modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("重启完成", color = Color(0xFF5AA87A), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)), shape = RoundedCornerShape(12.dp)) {
                            Text("返回控制台", color = Color(0xFFD4A853))
                        }
                    } else {
                        CircularProgressIndicator(color = Color(0xFFD4A853))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("正在重启服务…", color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
                    }
                }
            } else {
                Button(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Cached, null, tint = Color(0xFFD4A853), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重启服务", color = Color(0xFFD4A853), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showConfirm) ConfirmDialog(title = "重启服务", message = "将停止并重新启动 Node.js 服务。\n酒馆页面将暂时不可用。确定继续？", confirmText = "重启",
        onConfirm = { showConfirm = false; restarting = true; scope.launch { onDoRestart(); done = true } },
        onDismiss = { showConfirm = false })
}
