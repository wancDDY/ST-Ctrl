package com.tavern.app.console.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CoreUpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var coreVersion by remember { mutableStateOf("加载中…") }

    LaunchedEffect(Unit) {
        coreVersion = withContext(Dispatchers.IO) {
            // Read ST version from core/package.json
            val pkgJson = File(AssetExtractor.getCoreDir(ctx), "package.json")
            if (pkgJson.exists()) {
                try {
                    val json = org.json.JSONObject(pkgJson.readText())
                    json.optString("version", "未知")
                } catch (_: Exception) { "未知" }
            } else {
                // Fallback to core_version.txt
                val verFile = File(ctx.filesDir, "core_version.txt")
                if (verFile.exists()) verFile.readText().trim()
                else "未知"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color(0xFFD4A853), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("更新核心", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("SillyTavern 核心代码", fontSize = 13.sp, color = Color(0xFF8A8A80))
            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前版本", fontSize = 12.sp, color = Color(0xFF8A8A80))
                        Text("SillyTavern $coreVersion", fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { /* TODO: check GitHub releases */ },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4A853).copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.SystemUpdateAlt, null, tint = Color(0xFFD4A853), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("检查更新", color = Color(0xFFD4A853), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("更新检查需要网络连接。更新不会影响您的用户数据。", color = Color(0xFF8A8A80), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("此功能仍在开发中，敬请期待", color = Color(0xFF8A8A80).copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}
