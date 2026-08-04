package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.tavern.app.console.ConsoleViewModel

@Composable
fun StorageScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit
) {
    val storageInfo by viewModel.storageInfo.collectAsState()
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        loadError = false
        val result = viewModel.refreshStorageInfo()
        if (result.coreSize < 0) loadError = true
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("存储概览", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(24.dp))

            if (loadError) {
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", color = Color(0xFFCC4455), fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                        scope.launch { loadError = false; viewModel.refreshStorageInfo() }
                    }) {
                            Text("重试", color = Color(0xFFD4A853), fontSize = 14.sp)
                        }
                    }
                }
            } else if (storageInfo == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFD4A853))
                }
            } else {
                val info = storageInfo!!

                // Storage details card
                var dataExpanded by remember { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        StorageRow(icon = Icons.Outlined.Storage, label = "核心代码", bytes = info.coreSize)
                        StorageRow(icon = Icons.Outlined.Folder, label = "用户数据", bytes = info.dataSize,
                            expanded = dataExpanded, onExpand = { dataExpanded = !dataExpanded })
                        // Sub-items under user data
                        if (dataExpanded) {
                            if (info.charactersSize > 0 || info.chatsSize > 0)
                                StorageRow(indent = true, label = "角色与聊天", bytes = info.charactersSize + info.chatsSize)
                            if (info.vectorsSize > 0)
                                StorageRow(indent = true, label = "向量数据", bytes = info.vectorsSize)
                            if (info.thumbnailsSize > 0)
                                StorageRow(indent = true, label = "缩略图与头像", bytes = info.thumbnailsSize)
                            if (info.otherDataSize > 0)
                                StorageRow(indent = true, label = "其他数据", bytes = info.otherDataSize)
                        }
                        StorageRow(icon = Icons.Outlined.Archive, label = "备份文件", bytes = info.backupSize)
                        StorageRow(icon = Icons.Outlined.SdCard, label = "可用空间", bytes = info.freeSpace)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Total usage
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("总计占用", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                        Text(
                            formatBytes(info.coreSize + info.dataSize + info.backupSize),
                            color = Color(0xFFD4A853),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageRow(
    icon: ImageVector? = null,
    label: String,
    bytes: Long,
    indent: Boolean = false,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onExpand != null) Modifier.clickable(onClick = onExpand) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (indent) 6.dp else 14.dp)
            .padding(start = if (indent) 34.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color(0xFFD4A853),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        if (onExpand != null) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = Color(0xFFD4A853),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label.trimStart(), color = if (indent) Color(0xFF6A6A60) else Color(0xFF8A8A80), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(bytes: Long): String = com.tavern.app.util.FormatUtils.fileSize(bytes)
