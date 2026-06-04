package com.tavern.app.console.pages

import androidx.compose.foundation.background
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
import com.tavern.app.console.ConsoleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StorageScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit
) {
    var storageInfo by remember { mutableStateOf<ConsoleViewModel.StorageInfo?>(null) }
    LaunchedEffect(Unit) { storageInfo = withContext(Dispatchers.IO) { viewModel.getStorageInfo() } }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("存储概览", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(24.dp))

            if (storageInfo == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFD4A853))
                }
            } else {
                val info = storageInfo!!

                // Storage details card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        StorageRow(icon = Icons.Outlined.Storage, label = "核心代码", bytes = info.coreSize)
                        StorageRow(icon = Icons.Outlined.Folder, label = "用户数据", bytes = info.dataSize)
                        StorageRow(icon = Icons.Outlined.Archive, label = "备份文件", bytes = info.backupSize)
                        StorageRow(icon = Icons.Outlined.SdCard, label = "可用空间", bytes = info.freeSpace)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Total usage summary card
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
private fun StorageRow(icon: ImageVector, label: String, bytes: Long) {
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
        Text(formatBytes(bytes), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) { value /= 1024; unitIndex++ }
    return "%.1f %s".format(value, units[unitIndex])
}
