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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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

    fun perfTitle(mode: PerfMode): String = when (mode) {
        PerfMode.FULL     -> "性能优先"
        PerfMode.LIGHT    -> "轻度优化"
        PerfMode.BALANCED -> "均衡模式"
        PerfMode.SAVE     -> "深度优化"
    }

    fun perfIcon(mode: PerfMode) = when (mode) {
        PerfMode.FULL     -> Icons.Outlined.Bolt
        PerfMode.LIGHT    -> Icons.Outlined.Speed
        PerfMode.BALANCED -> Icons.Outlined.Tune
        PerfMode.SAVE     -> Icons.Outlined.BatterySaver
    }

    var expandedMode by remember { mutableStateOf<PerfMode?>(null) }

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
            Text("外观 · 启动 · 性能 · 关于", fontSize = 13.sp, color = muted)
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
                        .clip(RoundedCornerShape(16.dp))
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
                            Text(if (isDark) "深色模式" else "浅色模式",
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

            //  SECTION: 启动
            Text("启动", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))

            // 快速启动
            var fastOn by remember { mutableStateOf(com.tavern.app.console.SettingsState.fastStart()) }
            Surface(shape = RoundedCornerShape(14.dp), color = surface,
                border = BorderStroke(0.5.dp, divider),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.FlashOn, null, tint = accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("快速启动", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                        Text("服务在手机后台低功耗静默运行，可加快启动 App 的速度", fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = fastOn, onCheckedChange = {
                        fastOn = it; com.tavern.app.console.SettingsState.setFastStart(ctx, it)
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent,
                        uncheckedThumbColor = muted, uncheckedTrackColor = divider))
                }
            }

            // 后台酒馆
            var keepExpanded by remember { mutableStateOf(false) }
            var keepOn by remember { mutableStateOf(com.tavern.app.console.SettingsState.keepTavernAlive()) }
            Surface(shape = RoundedCornerShape(14.dp), color = surface,
                border = BorderStroke(0.5.dp, divider),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { keepExpanded = !keepExpanded }
                        .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2E7D32).copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Cached, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("后台酒馆", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Text("返回控制台时酒馆继续运行", fontSize = 12.sp, color = muted)
                        }
                        Switch(checked = keepOn, onCheckedChange = {
                            keepOn = it; com.tavern.app.console.SettingsState.setKeepTavernAlive(ctx, it)
                        }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF2E7D32),
                            uncheckedThumbColor = muted, uncheckedTrackColor = divider))
                    }
                    AnimatedVisibility(visible = keepExpanded) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                            HorizontalDivider(color = divider)
                            Spacer(Modifier.height(10.dp))
                            Text("开启后，从酒馆返回控制台时酒馆在后台继续运行，再次进入无需重新加载。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                            Text("关闭后，返回控制台即退出酒馆。控制台关闭，酒馆也关闭。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            //  SECTION: 性能模式
            Text("性能模式", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Text("根据需求选择性能策略，降低发热与耗电",
                fontSize = 12.sp, color = muted.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(14.dp))

            PerfMode.entries.forEach { mode ->
                val selected = currentPerf == mode
                val expanded = expandedMode == mode
                val title = perfTitle(mode)

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) accent.copy(alpha = 0.1f) else surface,
                    border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.35f))
                    else BorderStroke(0.5.dp, divider),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { expandedMode = if (expanded) null else mode }
                                .padding(16.dp),
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
                            }
                            Icon(
                                perfIcon(mode), null,
                                tint = if (selected) accent else muted.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                null,
                                tint = muted.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                                HorizontalDivider(color = divider)
                                Spacer(modifier = Modifier.height(10.dp))
                                SettingsState.description(mode).forEach { line ->
                                    if (line.isBlank()) Spacer(modifier = Modifier.height(4.dp))
                                    else Text(line, fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                                }
                                if (!selected) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            SettingsState.setPerfMode(ctx, mode)
                                            expandedMode = null
                                            val msg = if (mode != PerfMode.FULL)
                                                "已应用「$title」。CPU 策略将在下次启动时生效" else "已应用「$title」"
                                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("应用", color = Color(0xFF08080E), fontWeight = FontWeight.Medium)
                                    }
                                    if (mode != PerfMode.FULL) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("CPU 策略需重启 APP 后生效", fontSize = 11.sp, color = muted)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("当前使用中", fontSize = 12.sp, color = Color(0xFF5AA87A))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            //  SECTION: 版权 & 免责声明
            Text("关于", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))

            var aboutExpanded by remember { mutableStateOf(false) }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { aboutExpanded = !aboutExpanded }
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ST Ctrl v1.0.1", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Text(
                                "SillyTavern 的 Android 容器程序",
                                fontSize = 12.sp, color = muted
                            )
                        }
                        Icon(
                            if (aboutExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, tint = muted, modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = aboutExpanded) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = divider)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("作者：wancDDY", fontSize = 13.sp, color = onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("github.com/wancDDY/ST-Ctrl",
                                    fontSize = 12.sp, color = accent,
                                    modifier = Modifier.clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wancDDY/ST-Ctrl"))
                                        ctx.startActivity(intent)
                                    })
                                IconButton(onClick = {
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("", "https://github.com/wancDDY/ST-Ctrl"))
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.ContentCopy, null, tint = muted, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("基于 SillyTavern 构建 · MIT 开源", fontSize = 12.sp, color = muted)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("版权归属", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "SillyTavern（酒馆）是开源项目，版权归其原始作者及社区贡献者所有。",
                                fontSize = 12.sp, color = muted, lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("项目地址：", fontSize = 12.sp, color = muted)
                                Text("github.com/SillyTavern/SillyTavern",
                                    fontSize = 12.sp, color = accent,
                                    modifier = Modifier.clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SillyTavern/SillyTavern"))
                                        ctx.startActivity(intent)
                                    })
                                IconButton(onClick = {
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("", "https://github.com/SillyTavern/SillyTavern"))
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.ContentCopy, null, tint = muted, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
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
                                "本应用仅供学习交流使用，不提供 AI 模型服务。\n" +
                                        "使用本应用与第三方 AI API 交互所产生的费用、" +
                                        "内容及合规性问题，由用户自行承担。" +
                                        "请遵守相关服务条款和法律法规。\n" +
                                        "安装第三方扩展时请注意来源可信度。",
                                fontSize = 12.sp, color = muted, lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("技术栈", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Kotlin · Jetpack Compose · Node.js · WebView",
                                fontSize = 12.sp, color = muted, lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
