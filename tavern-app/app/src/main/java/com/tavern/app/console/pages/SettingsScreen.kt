package com.tavern.app.console.pages

import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.PerfMode
import com.tavern.app.console.SettingsState
import com.tavern.app.console.ThemeState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark by ThemeState.isDarkMode.collectAsState()
    val currentPerf by SettingsState.perfMode.collectAsState()

    val accent = Color(0xFFD4A853)
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onBg.copy(alpha = 0.55f)
    val divider = onBg.copy(alpha = 0.08f)

    fun perfMeta(mode: PerfMode): Pair<String, String> = when (mode) {
        PerfMode.FULL     -> "性能优先" to "高渲染 · 5分钟保活 · 默认缓存"
        PerfMode.LIGHT    -> "轻度优化" to "普通渲染 · 10分钟保活 · 优先本地缓存"
        PerfMode.BALANCED -> "均衡模式" to "普通渲染 · 15分钟保活 · 优先本地缓存"
        PerfMode.SAVE     -> "深度优化" to "普通渲染 · 30分钟保活 · 优先本地缓存"
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = accent, fontSize = 15.sp) }
            Spacer(modifier = Modifier.height(8.dp))
            Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = onBg, letterSpacing = 1.sp)
            Text("外观 · 性能 · 关于", fontSize = 13.sp, color = muted)
            Spacer(modifier = Modifier.height(28.dp))

            //  SECTION: 外观
            Text("外观", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { ThemeState.toggle(ctx) } }
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                .background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                                null, tint = accent, modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("主题模式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Text(if (isDark) "深色 · 琥珀暗夜" else "浅色 · 暖白晨曦",
                                fontSize = 12.sp, color = muted)
                        }
                    }
                    Switch(
                        checked = isDark,
                        onCheckedChange = { scope.launch { ThemeState.toggle(ctx) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = muted,
                            uncheckedTrackColor = divider
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            //  SECTION: 性能模式
            Text("性能模式", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Text("根据需求选择性能策略，降低发热与耗电",
                fontSize = 12.sp, color = muted.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(14.dp))

            PerfMode.entries.forEach { mode ->
                val selected = currentPerf == mode
                val (title, subtitle) = perfMeta(mode)

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) accent.copy(alpha = 0.1f) else surface,
                    border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.35f))
                    else BorderStroke(0.5.dp, divider),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clickable { SettingsState.setPerfMode(ctx, mode) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(22.dp).clip(CircleShape)
                                .border(2.dp, if (selected) accent else muted.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected)
                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accent))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) accent else onSurface)
                            Text(subtitle, fontSize = 12.sp,
                                color = if (selected) accent.copy(alpha = 0.7f) else muted,
                                lineHeight = 18.sp)
                        }
                        Icon(
                            when (mode) {
                                PerfMode.FULL     -> Icons.Outlined.Bolt
                                PerfMode.LIGHT    -> Icons.Outlined.Speed
                                PerfMode.BALANCED -> Icons.Outlined.Tune
                                PerfMode.SAVE     -> Icons.Outlined.BatterySaver
                            }, null,
                            tint = if (selected) accent else muted.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            //  SECTION: 版权 & 免责声明
            Text("关于", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("ST Ctrl v1.0.0", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "本应用是 SillyTavern（酒馆）的 Android 容器/外壳程序，" +
                                "提供 Android 设备上的 Node.js 运行环境、前台服务保活、" +
                                "WebView 访问、备份还原、扩展管理等便利功能。",
                        fontSize = 12.sp, color = muted, lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = divider)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("版权归属", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "SillyTavern（酒馆）是开源项目，版权归其原始作者及社区贡献者所有。" +
                                "项目地址：github.com/SillyTavern/SillyTavern\n\n" +
                                "本应用（ST Ctrl）是 SillyTavern 的 Android 容器程序，" +
                                "提供在 Android 设备上运行酒馆所需的环境和便利功能，" +
                                "不修改酒馆的任何源代码或功能逻辑，" +
                                "亦非 SillyTavern 官方产品。",
                        fontSize = 12.sp, color = muted, lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("免责声明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "本应用仅供学习交流使用，不提供 AI 模型服务。\n\n" +
                                "使用本应用与第三方 AI API 交互所产生的费用、" +
                                "内容及合规性问题，由用户自行承担。" +
                                "请遵守相关服务条款和法律法规。\n\n" +
                                "安装第三方扩展时请注意来源可信度。",
                        fontSize = 12.sp, color = muted, lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("技术栈", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Kotlin · Jetpack Compose · Node.js (embedded) · WebView · Android Foreground Service",
                        fontSize = 12.sp, color = muted, lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
