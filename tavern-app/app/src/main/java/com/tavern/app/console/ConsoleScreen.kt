package com.tavern.app.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.R
import com.tavern.app.console.components.ConsoleCard
import com.tavern.app.console.components.ConsoleTopBar

@Composable
fun ConsoleScreen(onEnterTavern: () -> Unit, onNavigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConsoleTopBar()
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onEnterTavern),
                    shape = RoundedCornerShape(16.dp), color = surface,
                    border = BorderStroke(1.dp, Color(0xFFD4A853).copy(alpha = 0.25f))) {
                    Column {
                        // Top-left text row
                        Row(modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Home, null, tint = Color(0xFFD4A853), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("进入酒馆", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("· SillyTavern", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                        // Logo with comfortable rounded edges
                        Box(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                            Image(painter = painterResource(id = R.drawable.sillytavern_logo),
                                contentDescription = "SillyTavern", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(horizontal = 8.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("备份", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConsoleCard(icon = Icons.Outlined.Archive, title = "创建备份", subtitle = "打包所有用户数据",
                        modifier = Modifier.weight(1f), surfaceColor = surface, onClick = { onNavigate("backup") })
                    ConsoleCard(icon = Icons.Outlined.Unarchive, title = "还原备份", subtitle = "从备份文件恢复",
                        modifier = Modifier.weight(1f), surfaceColor = surface, onClick = { onNavigate("restore") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ConsoleCard(icon = Icons.Outlined.Schedule, title = "自动备份", subtitle = "定时备份 · 保留多份",
                        modifier = Modifier.weight(1f), surfaceColor = surface, onClick = { onNavigate("auto_backup") })
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("管理", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleCard(icon = Icons.Outlined.Dns, title = "服务器状态", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("status") })
                    ConsoleCard(icon = Icons.Outlined.SdCard, title = "存储概览", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("storage") })
                    ConsoleCard(icon = Icons.Outlined.SystemUpdateAlt, title = "更新核心", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("update") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleCard(icon = Icons.Outlined.Extension, title = "扩展管理", subtitle = "扩展程序 · 角色卡",
                        modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("extensions") })
                    ConsoleCard(icon = Icons.Outlined.CleaningServices, title = "清除缓存", modifier = Modifier.weight(1f).fillMaxHeight(), surfaceColor = surface, onClick = { onNavigate("cache") })
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Settings gear — bottom-right corner
        FloatingActionButton(
            onClick = { onNavigate("settings") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Color(0xFFD4A853),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
        ) {
            Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(22.dp))
        }
    }
}
