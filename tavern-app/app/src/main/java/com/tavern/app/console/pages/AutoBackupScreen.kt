package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.ConsoleViewModel

@Composable
fun AutoBackupScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit
) {
    var enabled by remember { mutableStateOf(viewModel.autoBackupEnabled) }
    var interval by remember { mutableStateOf(viewModel.autoBackupInterval) }
    var maxKeep by remember { mutableStateOf(viewModel.autoBackupMaxKeep) }

    val intervalLabels = mapOf(1 to "每天", 3 to "每3天", 7 to "每周")

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("自动备份", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

            Spacer(modifier = Modifier.height(24.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("自动备份", color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
                        Text("定时打包备份用户数据", color = Color(0xFF8A8A80), fontSize = 12.sp)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it; viewModel.setAutoBackup(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFD4A853),
                            checkedTrackColor = Color(0xFFD4A853).copy(alpha = 0.3f)
                        )
                    )
                }
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("备份频率", color = Color(0xFF8A8A80), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        intervalLabels.forEach { (days, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                                RadioButton(
                                    selected = interval == days,
                                    onClick = { interval = days; viewModel.setAutoBackupInterval(days) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFD4A853))
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("保留最近 $maxKeep 份", color = Color(0xFF8A8A80), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = maxKeep.toFloat(),
                    onValueChange = { maxKeep = it.toInt(); viewModel.setAutoBackupMaxKeep(it.toInt()) },
                    valueRange = 3f..14f,
                    steps = 10,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFD4A853),
                        activeTrackColor = Color(0xFFD4A853)
                    )
                )
            }
        }
    }
}
