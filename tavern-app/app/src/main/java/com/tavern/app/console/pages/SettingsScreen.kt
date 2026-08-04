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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.SettingsState
import com.tavern.app.console.OptMode
import com.tavern.app.console.OptTier
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
    val accent = Color(0xFFD4A853)
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onBg.copy(alpha = 0.55f)
    val divider = onBg.copy(alpha = 0.08f)

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = accent, fontSize = 15.sp) }
            Spacer(Modifier.height(8.dp))
            Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = onBg, letterSpacing = 1.sp)
            Text("外观 · 启动 · 优化 · 关于", fontSize = 13.sp, color = muted)
            Spacer(Modifier.height(4.dp))
            Text("点击卡片即可展开详情", fontSize = 11.sp, color = accent.copy(alpha = 0.6f))
            Spacer(Modifier.height(20.dp))

            // ═══ 外观 ═══
            SectionHeader("外观")
            Surface(shape = RoundedCornerShape(16.dp), color = surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { scope.launch { ThemeState.toggle(ctx) } }.padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(if (isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, null, tint = accent, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("主题模式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Text(if (isDark) "深色模式" else "浅色模式", fontSize = 12.sp, color = muted)
                        }
                    }
                    Switch(checked = isDark, onCheckedChange = { scope.launch { ThemeState.toggle(ctx) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent, uncheckedThumbColor = muted, uncheckedTrackColor = divider))
                }
            }

            Spacer(Modifier.height(28.dp))

            // ═══ 启动 ═══
            SectionHeader("启动")

            var keepExpanded by remember { mutableStateOf(false) }
            var keepOn by remember { mutableStateOf(SettingsState.keepTavernAlive()) }
            ExpandableItem(icon = Icons.Outlined.Cached, iconBg = Color(0xFF2E7D32), title = "后台酒馆", subtitle = "返回控制台时酒馆继续运行",
                checked = keepOn, onCheckedChange = { keepOn = it; SettingsState.setKeepTavernAlive(ctx, it) },
                trackColor = Color(0xFF2E7D32), expanded = keepExpanded, onToggleExpand = { keepExpanded = !keepExpanded }
            ) {
                Text("开启后，从酒馆返回控制台时酒馆在后台继续运行，再次进入无需重新加载。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                Text("关闭后，返回控制台即退出酒馆。控制台关闭，酒馆也关闭。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
            }

            // ═══ 其他访问方式（浏览器 + 局域网）═══
            var accessExpanded by remember { mutableStateOf(false) }
            ExpandableItem(
                icon = Icons.Outlined.Language,
                iconBg = Color(0xFF1565C0),
                title = "其他访问方式",
                subtitle = "浏览器 · 局域网电脑",
                checked = false,
                onCheckedChange = {},
                trackColor = Color(0xFF1565C0),
                switchEnabled = false,
                expanded = accessExpanded,
                onToggleExpand = { accessExpanded = !accessExpanded }
            ) {
                Column {
                    // 顶部提示：只用一个方式
                    Text("⚠ 建议同一时间只使用一种方式访问酒馆，避免多个入口同时打开导致会话混乱。", fontSize = 12.sp, color = Color(0xFFE6A23C), lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = divider)
                    Spacer(Modifier.height(10.dp))

                    // ── 浏览器访问 ──
                    Text("浏览器访问", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Text("用手机上的浏览器打开酒馆", fontSize = 11.sp, color = muted)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("访问地址", fontSize = 11.sp, color = muted, letterSpacing = 1.sp)
                            Text("http://127.0.0.1:${com.tavern.app.node.NodeState.port.value}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onSurface)
                        }
                        IconButton(onClick = {
                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("酒馆地址", "http://127.0.0.1:${com.tavern.app.node.NodeState.port.value}"))
                            Toast.makeText(ctx, "已复制地址", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Outlined.ContentCopy, null, tint = accent, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val url = "http://127.0.0.1:${com.tavern.app.node.NodeState.port.value}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                ctx.startActivity(Intent.createChooser(intent, "选择浏览器"))
                            } catch (_: Exception) {
                                Toast.makeText(ctx, "未找到浏览器应用", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) { Text("进入", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = divider)
                    Spacer(Modifier.height(10.dp))

                    // ── 局域网电脑访问 ──
                    var lanOn by remember { mutableStateOf(SettingsState.lanAccessEnabled()) }
                    val lanIp = com.tavern.app.ApplicationState.lanIp
                    var lanToken by remember { mutableStateOf(com.tavern.app.ApplicationState.lanToken) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("局域网电脑访问", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Text("电脑浏览器通过局域网访问酒馆", fontSize = 11.sp, color = muted)
                        }
                        Switch(
                            checked = lanOn,
                            onCheckedChange = {
                                lanOn = it; SettingsState.setLanAccessEnabled(ctx, it)
                                if (it) com.tavern.app.MainActivity.startLanProxyIfNeededStatic(com.tavern.app.node.NodeState.port.value)
                                else com.tavern.app.MainActivity.stopLanProxyStatic()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1565C0),
                                uncheckedThumbColor = muted, uncheckedTrackColor = divider)
                        )
                    }
                    if (lanOn) {
                        Spacer(Modifier.height(10.dp))
                        if (lanIp.isBlank()) {
                            Text("⚠ 未检测到局域网 IP，请确保手机已连接 WiFi", fontSize = 12.sp, color = Color(0xFFE6A23C), lineHeight = 18.sp)
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("访问地址", fontSize = 11.sp, color = muted, letterSpacing = 1.sp)
                                    Text("http://${lanIp}:7999/", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onSurface)
                                }
                                IconButton(onClick = {
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("酒馆地址", "http://${lanIp}:7999/?t=${lanToken}"))
                                    Toast.makeText(ctx, "已复制含 Token 的完整地址", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Outlined.ContentCopy, null, tint = accent, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Token", fontSize = 11.sp, color = muted, letterSpacing = 1.sp)
                                    Text(
                                        SettingsState.lanCustomToken().ifBlank { lanToken }.ifBlank { "---" },
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = onSurface, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                IconButton(onClick = {
                                    if (SettingsState.lanCustomToken().isNotBlank()) {
                                        Toast.makeText(ctx, "请先将自定义 Token 留空再刷新", Toast.LENGTH_LONG).show()
                                    } else {
                                        lanToken = com.tavern.app.MainActivity.refreshLanTokenStatic()
                                        Toast.makeText(ctx, "Token 已刷新", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Outlined.Refresh, null, tint = accent, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text("电脑浏览器打开地址后输入上方 Token 即可访问酒馆", fontSize = 11.sp, color = muted.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(12.dp))
                            // ── Custom token ──
                            val customTokenInitial = SettingsState.lanCustomToken()
                            var customToken by remember { mutableStateOf(customTokenInitial) }
                            var customDirty by remember { mutableStateOf(false) }
                            val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
                            Text("自定义 Token", fontSize = 13.sp, color = onSurface)
                            Text("设置后使用固定 Token，不随启动变化（留空 = 用随机 Token）", fontSize = 11.sp, color = muted)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = customToken,
                                onValueChange = {
                                    customToken = it.filter { c -> c.isLetterOrDigit() }.take(12)
                                    customDirty = true
                                },
                                label = { Text("例如 myToken123") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accent, unfocusedBorderColor = accent.copy(alpha = 0.15f),
                                    focusedTextColor = onSurface, unfocusedTextColor = onSurface,
                                    focusedLabelColor = accent, unfocusedLabelColor = muted
                                )
                            )
                            // Buttons only appear after the input changed.
                            if (customDirty) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            if (customToken.isNotBlank() && !Regex("^[A-Za-z0-9]+$").matches(customToken)) {
                                                Toast.makeText(ctx, "自定义 Token 只能包含英文字母和数字", Toast.LENGTH_LONG).show()
                                                return@Button
                                            }
                                            SettingsState.setLanCustomToken(ctx, customToken)
                                            lanToken = customToken.ifBlank { com.tavern.app.ApplicationState.lanToken }
                                            customDirty = false
                                            focusMgr.clearFocus()
                                            Toast.makeText(ctx, if (customToken.isBlank()) "已清除自定义 Token，恢复随机" else "自定义 Token 已生效", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.height(40.dp)
                                    ) { Text("应用", color = Color.White, fontSize = 14.sp) }
                                    Button(
                                        onClick = {
                                            customToken = customTokenInitial
                                            customDirty = false
                                            focusMgr.clearFocus()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.height(40.dp)
                                    ) { Text("取消", color = accent, fontSize = 14.sp) }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = divider)
                            Spacer(Modifier.height(8.dp))
                            var keepOn by remember { mutableStateOf(SettingsState.lanKeepEnabled()) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("保持开启", fontSize = 13.sp, color = onSurface)
                                    Text("每次启动 App 自动开启局域网访问", fontSize = 11.sp, color = muted)
                                }
                                Switch(checked = keepOn, onCheckedChange = {
                                    keepOn = it; SettingsState.setLanKeepEnabled(ctx, it)
                                }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1565C0),
                                    uncheckedThumbColor = muted, uncheckedTrackColor = divider))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ═══ 优化 ═══
            SectionHeader("优化")

            var compatOn by remember { mutableStateOf(SettingsState.compatModeEnabled()) }
            var compatExpanded by remember { mutableStateOf(false) }
            ExpandableItem(icon = Icons.Outlined.Handyman, iconBg = Color(0xFF6B5B9E), title = "兼容模式", subtitle = "修复旧设备 CSS 渲染问题",
                checked = compatOn, onCheckedChange = {
                    compatOn = it; SettingsState.setCompatMode(ctx, it)
                    Toast.makeText(ctx, if (it) "兼容模式已开启，刷新酒馆页面后生效" else "兼容模式已关闭", Toast.LENGTH_SHORT).show()
                }, trackColor = Color(0xFF6B5B9E), expanded = compatExpanded, onToggleExpand = { compatExpanded = !compatExpanded }
            ) { Text("适配 WebView 版本较老的设备。这类设备的浏览器内核不支持酒馆用到的部分新式 CSS 特性，开启后会自动转换，避免主题和扩展出现透明、错乱、模糊失效等渲染问题。\n如果开启后仍遇到渲染问题，可以使用浏览器访问酒馆，但不建议使用手机自带的浏览器。", fontSize = 12.sp, color = muted, lineHeight = 18.sp) }

            Spacer(Modifier.height(14.dp))

            // ═══ 性能模式 ═══
            var perfExpanded by remember { mutableStateOf(false) }
            val mode = SettingsState.optMode()
            var selMode by remember { mutableStateOf(mode) }
            val tier = SettingsState.optTier()
            var selTier by remember { mutableStateOf(tier) }
            val heap = SettingsState.maxOldSpaceMb()
            val pool = SettingsState.uvPoolSize()
            val thermal = SettingsState.thermalLabel()
            val factor = SettingsState.currentFactor()
            val fPct = "${(factor * 100).toInt()}%"
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val ramMb = SettingsState.heuristicTotalRamMb

            Surface(shape = RoundedCornerShape(16.dp), color = surface,
                border = BorderStroke(0.5.dp, divider),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { perfExpanded = !perfExpanded }) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF2196F3).copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Tune, null, tint = Color(0xFF2196F3), modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("性能模式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (selMode == OptMode.AUTO) "智能自动" else selTier.label, fontSize = 12.sp, color = muted)
                                Text(" · ${heap}MB · ${pool}线程", fontSize = 12.sp, color = muted)
                                if (thermal != "正常") { Text(" · $thermal", fontSize = 12.sp, color = Color(0xFFE6A23C)) }
                            }
                        }
                        Icon(if (perfExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = muted, modifier = Modifier.size(20.dp))
                    }

                    AnimatedVisibility(visible = perfExpanded) {
                        Column {
                            Spacer(Modifier.height(14.dp)); HorizontalDivider(color = divider); Spacer(Modifier.height(14.dp))

                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(divider.copy(alpha = 0.3f)), horizontalArrangement = Arrangement.Center) {
                                listOf(OptMode.AUTO to "智能自动", OptMode.MANUAL to "手动设置").forEach { (m, label) ->
                                    val active = selMode == m
                                    Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) Color.White else muted,
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                            .background(if (active) Color(0xFF2196F3) else Color.Transparent)
                                            .clickable { selMode = m; SettingsState.setOptMode(ctx, m) }.padding(vertical = 10.dp),
                                        textAlign = TextAlign.Center)
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            if (selMode == OptMode.AUTO) {
                                Column(Modifier.fillMaxWidth()) {
                                    ParamRow("分配系数", fPct, onSurface)
                                    ParamRow("堆内存", "$heap MB", onSurface)
                                    ParamRow("IO 线程", "$pool / $cpuCores 核", onSurface)
                                    ParamRow("温控", thermal, if (thermal == "正常") onSurface else Color(0xFFE6A23C))
                                    Text("根据 ${ramMb}MB RAM、温度、崩溃记录自动调节", fontSize = 11.sp, color = muted.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
                                }
                            } else {
                                OptTier.entries.forEach { t ->
                                    val act = selTier == t
                                    val th = (SettingsState.maxHeapMb() * t.factor).toInt().coerceIn(128, 1024)
                                    val tp = (SettingsState.maxPoolSize() * t.factor).toInt().coerceIn(2, cpuCores)
                                    Surface(shape = RoundedCornerShape(12.dp),
                                        color = if (act) Color(0xFF2196F3).copy(alpha = 0.1f) else Color.Transparent,
                                        border = BorderStroke(1.dp, if (act) Color(0xFF2196F3).copy(alpha = 0.4f) else divider),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                            selTier = t; SettingsState.setOptTier(ctx, t)
                                        }) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = act, onClick = { selTier = t; SettingsState.setOptTier(ctx, t) },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2196F3)), modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(t.label, fontSize = 14.sp, fontWeight = if (act) FontWeight.SemiBold else FontWeight.Normal, color = onSurface)
                                                Text("${th}MB · ${tp}线程", fontSize = 11.sp, color = muted)
                                            }
                                        }
                                    }
                                }
                                Text("重启酒馆后生效", fontSize = 11.sp, color = muted.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            // ═══ 关于 ═══
            SectionHeader("关于")
            var aboutExpanded by remember { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(16.dp), color = surface, tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { aboutExpanded = !aboutExpanded }) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val appVer = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0" } catch (_: Exception) { "1.0.0" }
                            Text("ST Ctrl v$appVer", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Text("SillyTavern 的 Android 容器程序", fontSize = 12.sp, color = muted)
                        }
                        Icon(if (aboutExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = muted, modifier = Modifier.size(20.dp))
                    }
                    AnimatedVisibility(visible = aboutExpanded) {
                        Column {
                            Spacer(Modifier.height(12.dp)); HorizontalDivider(color = divider); Spacer(Modifier.height(12.dp))
                            Text("作者：wancDDY", fontSize = 13.sp, color = onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("github.com/wancDDY/ST-Ctrl", fontSize = 12.sp, color = accent, modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wancDDY/ST-Ctrl"))) })
                                IconButton(onClick = { val c = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager; c.setPrimaryClip(ClipData.newPlainText("", "https://github.com/wancDDY/ST-Ctrl")); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.ContentCopy, null, tint = muted, modifier = Modifier.size(14.dp)) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("基于 SillyTavern 构建 · MIT 开源", fontSize = 12.sp, color = muted)
                            Spacer(Modifier.height(12.dp))
                            Text("版权归属", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("SillyTavern（酒馆）是开源项目，版权归其原始作者及社区贡献者所有。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("项目地址：", fontSize = 12.sp, color = muted)
                                Text("github.com/SillyTavern/SillyTavern", fontSize = 12.sp, color = accent, modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SillyTavern/SillyTavern"))) })
                                IconButton(onClick = { val c = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager; c.setPrimaryClip(ClipData.newPlainText("", "https://github.com/SillyTavern/SillyTavern")); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.ContentCopy, null, tint = muted, modifier = Modifier.size(14.dp)) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("本应用（ST Ctrl）是 SillyTavern 的 Android 容器程序，不修改酒馆源代码，亦非官方产品。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("免责声明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("本应用仅供学习交流使用，不提供 AI 模型服务。\n使用本应用与第三方 AI API 交互所产生的费用、内容及合规性问题，由用户自行承担。", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("技术栈", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("Kotlin · Jetpack Compose · Node.js JNI · WebView", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ParamRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        Text(value, fontSize = 13.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = Color(0xFFD4A853).copy(alpha = 0.7f), letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
}

@Composable
private fun ExpandableItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trackColor: Color,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    switchEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onToggleExpand() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(iconBg.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconBg, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
                }
                if (switchEnabled) {
                    Switch(checked = checked, onCheckedChange = onCheckedChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = trackColor,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            uncheckedTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)))
                } else { Spacer(Modifier.width(52.dp)) }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))
                    content()
                }
            }
        }
    }
}
