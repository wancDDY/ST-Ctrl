package com.tavern.app.console.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.OptMode
import com.tavern.app.console.OptTier
import com.tavern.app.console.SettingsState
import com.tavern.app.console.components.AppSegmentedControl
import com.tavern.app.console.components.BrandMark
import com.tavern.app.console.components.BrandWordmark
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.PageTopBar
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.components.appRadioButtonColors
import com.tavern.app.console.components.appSwitchColors
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.update.TavernCoreCapabilities
import com.tavern.app.util.AppToast
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Full-bleed like the overview list, so pushing a row does not shift the content
// sideways: groups paint their own surface and every row carries its own gutter.
// Only out-of-row text, such as the page heading and prose, is inset here.
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = AppSpacing.xxl),
            content = content
        )
    }
}

// Rows sit straight on the page background; the group is delimited by the label
// above it and the gap that follows.
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), content = content)
}

@Composable
fun SettingsAccessScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val appColors = LocalAppColors.current
    val accent = appColors.accent
    val warning = appColors.warning
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    val port by com.tavern.app.node.NodeState.port.collectAsState()

    // The title names the two concrete ways in rather than an abstraction over them:
    // "other access methods" leaves the reader guessing what they are alternatives to.
    SettingsSubPage(title = "浏览器与局域网", onBack = onBack) {
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.md)) {
                // The advice covers everything below it, so it opens the card where
                // the reader sees it before choosing a method, not after both.
                // Read as a note rather than as a warning: nothing is wrong yet at this point, and
                // the warning tone is kept for the line that reports an actual problem (no LAN
                // address). Wearing it for advice made the whole page look like an error state.
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(AppDimens.iconSm)
                    )
                    Spacer(Modifier.width(AppSpacing.sm))
                    Text("建议同一时间只使用一种方式访问核心，避免多个入口同时打开导致会话混乱。", style = MaterialTheme.typography.bodySmall, color = muted, lineHeight = 18.sp)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)) {
                Text("浏览器访问", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = onSurface)
                Text("在本机浏览器中打开核心", style = MaterialTheme.typography.labelSmall, color = muted)
                Spacer(Modifier.height(AppSpacing.sm))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("访问地址", style = MaterialTheme.typography.labelSmall, color = muted, letterSpacing = 1.sp)
                        Text("http://127.0.0.1:$port", style = MaterialTheme.typography.labelLarge, color = onSurface)
                    }
                    IconButton(onClick = {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("核心地址", "http://127.0.0.1:$port"))
                        AppToast.show(ctx, "已复制地址", Toast.LENGTH_SHORT)
                    }) {
                        Icon(Icons.Outlined.ContentCopy, null, tint = accent, modifier = Modifier.size(AppDimens.iconMd))
                    }
                }
                Spacer(Modifier.height(AppSpacing.md))
                SoftActionButton(
                    text = "进入",
                    onClick = {
                        val url = "http://127.0.0.1:$port"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            ctx.startActivity(Intent.createChooser(intent, "选择浏览器"))
                        } catch (_: Exception) {
                            AppToast.show(ctx, "未找到浏览器应用", Toast.LENGTH_SHORT)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }


            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)) {
                var lanOn by remember { mutableStateOf(SettingsState.lanAccessEnabled()) }
                val lanIp = com.tavern.app.ApplicationState.lanIp
                var lanToken by remember { mutableStateOf(com.tavern.app.ApplicationState.lanToken) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("局域网电脑访问", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = onSurface)
                        Text("局域网内的电脑通过浏览器访问核心", style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    Switch(
                        checked = lanOn,
                        onCheckedChange = {
                            lanOn = it; SettingsState.setLanAccessEnabled(ctx, it)
                            if (it) com.tavern.app.MainActivity.startLanProxyIfNeededStatic(port)
                            else com.tavern.app.MainActivity.stopLanProxyStatic()
                        },
                        colors = appSwitchColors()
                    )
                }
                if (lanOn) {
                    Spacer(Modifier.height(AppSpacing.md))
                    if (lanIp.isBlank()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = warning,
                                modifier = Modifier.size(AppDimens.iconSm)
                            )
                            Spacer(Modifier.width(AppSpacing.sm))
                            Text("未检测到局域网 IP，请确保手机已连接 WiFi", style = MaterialTheme.typography.bodySmall, color = warning, lineHeight = 18.sp)
                        }
                    } else {
                        val lanUrl = "http://${lanIp}:7999/"
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("访问地址", style = MaterialTheme.typography.labelSmall, color = muted, letterSpacing = 1.sp)
                                Text(lanUrl, style = MaterialTheme.typography.labelLarge, color = onSurface)
                            }
                            IconButton(onClick = {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("核心地址", lanUrl))
                                AppToast.show(ctx, "已复制地址", Toast.LENGTH_SHORT)
                            }) {
                                Icon(Icons.Outlined.ContentCopy, null, tint = accent, modifier = Modifier.size(AppDimens.iconMd))
                            }
                        }
                        Spacer(Modifier.height(AppSpacing.md))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Token", style = MaterialTheme.typography.labelSmall, color = muted, letterSpacing = 1.sp)
                                Text(
                                    SettingsState.lanCustomToken().ifBlank { lanToken }.ifBlank { "---" },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = onSurface, fontFamily = FontFamily.Monospace
                                )
                            }
                            IconButton(onClick = {
                                if (SettingsState.lanCustomToken().isNotBlank()) {
                                    AppToast.show(ctx, "请先将自定义 Token 留空再刷新", Toast.LENGTH_LONG)
                                } else {
                                    lanToken = com.tavern.app.MainActivity.refreshLanTokenStatic()
                                    AppToast.show(ctx, "Token 已刷新", Toast.LENGTH_SHORT)
                                }
                            }) {
                                Icon(Icons.Outlined.Refresh, null, tint = accent, modifier = Modifier.size(AppDimens.iconMd))
                            }
                        }
                        Text("在电脑浏览器中打开该地址，输入上方 Token 即可访问核心。", style = MaterialTheme.typography.labelSmall, color = muted.copy(alpha = AppAlpha.onSurfaceSecondary), modifier = Modifier.padding(top = AppSpacing.sm))
                        Spacer(Modifier.height(AppSpacing.md))
                        val customTokenInitial = SettingsState.lanCustomToken()
                        var customToken by remember { mutableStateOf(customTokenInitial) }
                        var customDirty by remember { mutableStateOf(false) }
                        val focusMgr = LocalFocusManager.current
                        Text("自定义 Token", style = MaterialTheme.typography.bodySmall, color = onSurface)
                        Text("设置后使用固定 Token，不随启动变化；留空则每次启动随机生成", style = MaterialTheme.typography.labelSmall, color = muted)
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = customToken,
                            onValueChange = {
                                customToken = it.filter { c -> c.isLetterOrDigit() }.take(12)
                                customDirty = true
                            },
                            label = { Text("例如 myToken123") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors()
                        )
                        // Buttons only appear after the input changed.
                        if (customDirty) {
                            Spacer(Modifier.height(AppSpacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                SoftActionButton(
                                    text = "应用",
                                    onClick = {
                                        if (customToken.isNotBlank() && !Regex("^[A-Za-z0-9]+$").matches(customToken)) {
                                            AppToast.show(ctx, "自定义 Token 只能包含英文字母和数字", Toast.LENGTH_LONG)
                                            return@SoftActionButton
                                        }
                                        SettingsState.setLanCustomToken(ctx, customToken)
                                        lanToken = customToken.ifBlank { com.tavern.app.ApplicationState.lanToken }
                                        customDirty = false
                                        focusMgr.clearFocus()
                                        AppToast.show(ctx, if (customToken.isBlank()) "已清除自定义 Token，恢复随机" else "自定义 Token 已生效", Toast.LENGTH_SHORT)
                                    },
                                    modifier = Modifier.height(AppDimens.controlHeightCompact)
                                )
                                DialogDismissButton(
                                    text = "取消",
                                    onClick = {
                                        customToken = customTokenInitial
                                        customDirty = false
                                        focusMgr.clearFocus()
                                    },
                                    modifier = Modifier.height(AppDimens.controlHeightCompact)
                                )
                            }
                        }
                        // The enclosing block already carries the gutter, so this
                        // divider needs no inset of its own to line up with the text.
                        Spacer(Modifier.height(AppSpacing.md))
                        var keepOn by remember { mutableStateOf(SettingsState.lanKeepEnabled()) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("保持开启", style = MaterialTheme.typography.bodySmall, color = onSurface)
                                Text("每次启动应用时自动开启局域网访问", style = MaterialTheme.typography.labelSmall, color = muted)
                            }
                            Switch(checked = keepOn, onCheckedChange = {
                                keepOn = it; SettingsState.setLanKeepEnabled(ctx, it)
                            }, colors = appSwitchColors())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsOptimizeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val accent = LocalAppColors.current.accent
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    var coreVersion by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        coreVersion = withContext(Dispatchers.IO) {
            val packageVersion = JsonUtil.readVersion(
                java.io.File(AssetExtractor.getCoreDir(ctx), "package.json"),
                "",
            )
            if (packageVersion.isNotBlank()) {
                packageVersion
            } else {
                java.io.File(ctx.filesDir, "core_version.txt")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    .orEmpty()
            }
        }
    }

    var compatOn by remember { mutableStateOf(SettingsState.compatModeEnabled()) }
    var chatVirtOn by remember { mutableStateOf(SettingsState.tavernChatVirtualEnabled()) }
    var charImportOn by remember { mutableStateOf(SettingsState.tavernCharImportEnabled()) }
    var pauseRenderOn by remember { mutableStateOf(SettingsState.tavernPauseRenderEnabled()) }
    var perfAnimOn by remember { mutableStateOf(SettingsState.tavernPerfAnimEnabled()) }

    SettingsSubPage(title = "核心优化", onBack = onBack) {
        SettingsCard {
            TavernOptGroup(
                title = "兼容渲染",
                summary = "现代 CSS、动态样式和旧 WebView 布局转换"
            ) {
            TavernOptRow(
                title = "兼容渲染修复",
                summary = "转换旧 WebView 不认识的现代 CSS 与页面布局",
                details = "旧版 WebView 不认 color-mix()、oklch()、@layer 这类现代 CSS 写法。这里在服务端改写响应里的样式，页面运行中新建的 style 节点也继续拦截，角色卡等 iframe 单独扫。颜色混合按预乘 Alpha 换算，模糊值写成具体像素，面板宽度裁到视口内，都是为了在老内核上还原同一个效果。",
                checked = compatOn,
                onCheckedChange = {
                    compatOn = it
                    SettingsState.setCompatMode(ctx, it)
                    // The page must be loaded again: the fix is applied while serving it.
                    com.tavern.app.webview.TavernRefresh.request()
                    AppToast.show(ctx, if (it) "兼容渲染已开启，已刷新核心页面" else "兼容渲染已关闭，已刷新核心页面", Toast.LENGTH_SHORT)
                }
            )
            }
            TavernOptGroup(
                title = "长聊天",
                summary = "历史消息渐进渲染与接近顶部自动加载"
            ) {
            TavernOptRow(
                title = "长对话渐进渲染与自动加载",
                summary = "长聊天按需渲染，接近顶部时加载更早消息",
                details = "长聊天打开时先渲染最近的消息，其余随滚动补上。滚动到顶部会继续加载更早的消息，顺序以及头像、昵称、时间都与原版一致。关闭后恢复核心原有的加载方式。",
                checked = chatVirtOn,
                onCheckedChange = {
                    chatVirtOn = it
                    SettingsState.setTavernChatVirtual(ctx, it)
                    AppToast.show(ctx, "重新进入核心后生效", Toast.LENGTH_SHORT)
                },
                enabled = TavernCoreCapabilities.supports(
                    TavernCoreCapabilities.Feature.LONG_CHAT,
                    coreVersion
                ),
                disabledReason = TavernCoreCapabilities.disabledReason(
                    TavernCoreCapabilities.Feature.LONG_CHAT
                )
            )
            }
            TavernOptGroup(
                title = "资源与稳定性",
                summary = "角色卡内存、后台页面和低性能负载"
            ) {
            TavernOptRow(
                title = "角色卡低内存导入",
                summary = "降低批量角色卡和 PNG 导入的内存峰值",
                details = "批量读取角色卡时限制同时处理的数量。导入 PNG 时不重新压缩：保留原图，只读取必要的文字信息。角色卡字段与导入后的文件内容与核心原有处理一致，数据格式不变。",
                checked = charImportOn,
                onCheckedChange = {
                    charImportOn = it
                    SettingsState.setTavernCharImport(ctx, it)
                    AppToast.show(ctx, "重启核心服务后生效", Toast.LENGTH_SHORT)
                },
                enabled = TavernCoreCapabilities.supports(
                    TavernCoreCapabilities.Feature.CHARACTER_IMPORT,
                    coreVersion
                ),
                disabledReason = TavernCoreCapabilities.disabledReason(
                    TavernCoreCapabilities.Feature.CHARACTER_IMPORT
                )
            )
            TavernOptRow(
                title = "后台冻结页面",
                summary = "离开核心后暂停页面绘制和动画任务",
                details = "从核心返回控制台时，页面会进入暂停状态，并取消尚未执行的动画帧。页面持续处于后台超过 30 秒后会释放渲染缓存；再次进入核心时恢复正常绘制。Node 服务、聊天连接和服务端定时任务不属于冻结对象，后台冻结只针对页面本身的绘制和动画。",
                checked = pauseRenderOn,
                onCheckedChange = {
                    pauseRenderOn = it
                    SettingsState.setTavernPauseRender(ctx, it)
                }
            )
            TavernOptRow(
                title = "低性能自动降载",
                summary = "低性能档下减少动画和轮询负载",
                details = "低性能档位会关闭界面过渡动画，并放慢页面轮询的间隔，以减轻旧机型上的卡顿。它只影响界面表现，不改动消息内容、接口请求或扩展逻辑；退出低性能档位后自动恢复。",
                checked = perfAnimOn,
                onCheckedChange = {
                    perfAnimOn = it
                    SettingsState.setTavernPerfAnim(ctx, it)
                    AppToast.show(ctx, "返回核心时生效", Toast.LENGTH_SHORT)
                }
            )
            }
            Text("关闭后恢复核心原有的行为；除排查问题需要外，建议保持开启。",
                style = MaterialTheme.typography.labelSmall, color = muted.copy(alpha = AppAlpha.onSurfaceSecondary),
                modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.sm, bottom = AppSpacing.md))
        }
    }
}

@Composable
fun SettingsPerfScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val appColors = LocalAppColors.current
    val accent = appColors.accent
    val warning = appColors.warning
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)

    val mode = SettingsState.optMode()
    var selMode by remember { mutableStateOf(mode) }
    val tier = SettingsState.optTier()
    var selTier by remember { mutableStateOf(tier) }
    val heap = SettingsState.maxOldSpaceMb()
    val pool = SettingsState.uvPoolSize()
    val thermal = SettingsState.thermalLabel()
    val factor = SettingsState.currentFactor()
    val fPct = "${(factor * 100).toInt()}%"
    val renderBudget = when {
        factor <= 0.25 -> "省电渲染"
        factor < 0.5 -> "轻量渲染"
        factor < 0.75 -> "均衡渲染"
        else -> "完整渲染"
    }
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val ramMb = SettingsState.heuristicTotalRamMb

    SettingsSubPage(title = "性能模式", onBack = onBack) {
        // The page had one card holding an unlabelled selector, a bare parameter
        // list and either a note or a radio list, with no heading anywhere: nothing
        // said which part was the choice and which was the result. It now uses the
        // labelled blocks the rest of the settings pages use, so the choice, the
        // running values and the manual tiers each announce themselves.
        TavernOptGroup(
            title = "调节方式",
            summary = "自动按机型调节，或自己指定档位"
        ) {
            AppSegmentedControl(
                options = listOf("智能自动", "手动设置"),
                selectedIndex = if (selMode == OptMode.AUTO) 0 else 1,
                onSelect = {
                    val m = if (it == 0) OptMode.AUTO else OptMode.MANUAL
                    selMode = m
                    SettingsState.setOptMode(ctx, m)
                },
            )
        }

        TavernOptGroup(
            title = "当前参数",
            summary = "实际生效的分配与渲染设置"
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg)) {
                ParamRow("分配系数", fPct, onSurface)
                ParamRow("堆内存", "$heap MB", onSurface)
                ParamRow("IO 线程", "$pool / $cpuCores 核", onSurface)
                ParamRow("WebView 渲染", renderBudget, onSurface)
                ParamRow("温控", thermal, if (thermal == "正常") onSurface else warning)
            }
        }

        if (selMode == OptMode.AUTO) {
            TavernOptGroup(
                title = "自动调节依据",
                summary = "内存、温度与崩溃记录"
            ) {
                Text(
                    "根据 ${ramMb}MB RAM、温度、崩溃记录自动调节。",
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 15.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = AppSpacing.lg)
                )
            }
        } else {
            TavernOptGroup(
                title = "档位",
                summary = "每档给出堆内存、线程数与渲染比例"
            ) {
                OptTier.entries.forEach { t ->
                    val act = selTier == t
                    val th = (SettingsState.maxHeapMb() * t.factor).toInt().coerceIn(128, 4096)
                    val tp = (SettingsState.maxPoolSize() * t.factor).toInt().coerceIn(2, cpuCores)
                    // The radio is the selected mark; a tinted plate behind it was a
                    // second one, and a filled row reads as a highlighted block on a
                    // page where nothing else is filled.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg)
                            .padding(bottom = AppSpacing.sm)
                            // No clip and no fill: the row sits on the page
                            // background, so the touch feedback spans the whole row
                            // with square corners, the way a list row does. A clip
                            // here rounded the feedback into a plate the row is not.
                            .clickable {
                                selTier = t; SettingsState.setOptTier(ctx, t)
                            }
                            .padding(AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = act, onClick = null,
                            colors = appRadioButtonColors())
                        Spacer(Modifier.width(AppSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (act) FontWeight.SemiBold else FontWeight.Normal, color = onSurface)
                            Text("${th}MB、${tp}线程、渲染${(t.factor * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                    }
                }
                Text(
                    "Node 参数重启后生效，WebView 参数返回核心时自动应用。",
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 15.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs)
                )
            }
        }
    }
}

// The page's own copy, shown where it belongs rather than behind another tap: the
// app's identity above the two repository entries, the licence documents after them,
// and the disclaimer last.
private const val ABOUT_TECH_LINES =
    "Kotlin、Jetpack Compose、Node.js JNI、WebView\n" +
        "随包字体 Rajdhani 按 SIL Open Font License 1.1 分发。"

private const val ABOUT_DISCLAIMER_LINES =
    "本项目仅用于个人学习、技术研究交流。\n" +
        "项目基于 AGPL-3.0 开源协议分发，请遵守对应开源许可证要求。\n" +
        "请勿用于非法用途，使用产生的一切后果由使用者自行承担。\n" +
        "本应用与第三方 AI API 交互产生的费用、内容及合规问题，由使用者自行承担。"

// The rows spell the repositories without their scheme, the way the page has always
// shown them, and the tap opens the https address.
private const val ABOUT_SOURCE_REPO = "github.com/wancDDY/ST-Ctrl"
private const val ABOUT_UPSTREAM_REPO = "github.com/SillyTavern/SillyTavern"

@Composable
fun SettingsAboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    val appVersion = remember(ctx) {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    SettingsSubPage(title = "关于", onBack = onBack) {
        SettingsCard {
            // The page opens with the brand: the mark carries the wordmark beside it
            // and the version sits under the name, so the plate is recognisable
            // before any entry below is read. The group stays against the start
            // edge, where the rows under it begin. The mark is the compact plate
            // rather than the startup one — a mark sharing its line with text reads
            // as oversized at the size a full-screen lockup uses.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark(size = AppDimens.brandMarkCompact)
                Spacer(Modifier.width(AppSpacing.md))
                Column(Modifier.weight(1f)) {
                    BrandWordmark(
                        style = MaterialTheme.typography.headlineLarge,
                        color = onSurface
                    )
                    Text(
                        text = "v$appVersion",
                        style = MaterialTheme.typography.labelMedium,
                        color = muted
                    )
                }
            }
            // The identity opens the list in full rather than folded: the paragraphs
            // are what the page is read for, and hiding them behind a tap also hid
            // where the repository entries below belong. No author line: who wrote it
            // belongs to the licence chain's NOTICE, which ships with the package.
            AboutBlock(
                icon = Icons.Outlined.Info,
                title = "应用信息"
            ) {
                AboutParagraph(
                    "本应用（ST Ctrl）是 SillyTavern 的 Android 容器程序，非官方产品。"
                )
                AboutParagraph(
                    "基于 SillyTavern 构建，整体按 AGPL-3.0 发布；壳层中未派生自上游的文件按 MIT。"
                )
                AboutParagraph(
                    "SillyTavern 是开源项目，版权归其原始作者及社区贡献者所有。"
                )
            }
            ListRow(
                icon = Icons.Outlined.Code,
                title = "应用源码",
                onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$ABOUT_SOURCE_REPO")))
                },
                subtitle = ABOUT_SOURCE_REPO,
                trailing = { CopyLinkButton("https://$ABOUT_SOURCE_REPO") }
            )
            ListRow(
                icon = Icons.Outlined.Public,
                title = "上游项目",
                onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$ABOUT_UPSTREAM_REPO")))
                },
                subtitle = ABOUT_UPSTREAM_REPO,
                trailing = { CopyLinkButton("https://$ABOUT_UPSTREAM_REPO") }
            )
            // One row, one sheet: the three files answer the same question, and three
            // rows of file names made the page look like three subjects. The second tap
            // picks which text to read.
            AboutLicenceEntry()
            // The closing notes are plain text under a plain heading: nothing here
            // opens, so a glyph would be decoration and the chevron would promise a
            // tap that does nothing.
            AboutTextBlock(title = "技术栈", body = ABOUT_TECH_LINES)
            AboutTextBlock(title = "免责声明", body = ABOUT_DISCLAIMER_LINES)
        }
    }
}

// A titled block whose prose sits under the title on the same page. Nothing here
// opens, so the heading is a label rather than a control and the block carries no
// chevron.
@Composable
private fun AboutBlock(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = LocalAppColors.current.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(AppDimens.iconMd)
        )
        Spacer(Modifier.width(AppSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = onSurface)
            Spacer(Modifier.height(AppSpacing.xs))
            content()
        }
    }
}

// A heading with prose under it and no leading mark: the blocks that only state
// something carry no glyph, so the page does not read as a list of controls.
@Composable
private fun AboutTextBlock(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.lg)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(AppSpacing.xs))
        AboutParagraph(body)
    }
}

@Composable
private fun AboutParagraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
        modifier = Modifier.padding(bottom = AppSpacing.xxs)
    )
}

// A licence document opens in a sheet rather than a browser: the file ships in the
// APK, so it reads offline and in place. One row covers every text: the first tap
// opens the sheet, the second picks which file to read, because three rows of file
// names read as three unrelated subjects and each cost a trip out of the page.
@Composable
private fun AboutLicenceEntry() {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<AboutLicence?>(null) }

    ListRow(
        icon = Icons.Outlined.Description,
        title = "开源许可",
        subtitle = "AGPL-3.0、MIT、修改说明",
        onClick = { open = true },
        trailing = { TrailingChevron() }
    )

    if (!open) return

    val selected = picked
    if (selected == null) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("开源许可") },
            text = {
                Column {
                    AboutLicence.entries.forEach { item ->
                        ListRow(
                            icon = Icons.Outlined.Description,
                            title = item.label,
                            subtitle = item.summary,
                            onClick = { picked = item },
                            trailing = { TrailingChevron() }
                        )
                    }
                }
            },
            confirmButton = {
                DialogDismissButton(text = "关闭", onClick = { open = false })
            },
        )
    } else {
        // Keyed on the file so switching entries cannot show the previous text while
        // the next one is being read.
        val body by produceState(initialValue = "", selected) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.assets.open(selected.asset).bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }
        }
        AlertDialog(
            onDismissRequest = { picked = null; open = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text(selected.label) },
            text = {
                val lines = remember(body) { body.lines() }
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(lines.size) { index ->
                        Text(lines[index], style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            },
            confirmButton = {
                // Back to the list rather than out of the sheet: the reader came here
                // to read one text and may want another.
                DialogDismissButton(text = "返回", onClick = { picked = null })
            },
        )
    }
}

// The three texts the licence chain ships, in the order it reads them.
private enum class AboutLicence(
    val label: String,
    val summary: String,
    val asset: String
) {
    AGPL("GNU AGPL-3.0", "整体许可", "LICENSE"),
    NOTICE("修改说明与许可范围", "修改登记与 MIT 范围", "NOTICE"),
    MIT("MIT", "壳层未派生自上游的文件", "LICENSE-MIT"),
}

// Drawn at the compact control size: the default 48dp button would be the tallest
// element in the list and lift the two link rows off the rows around them.
@Composable
private fun CopyLinkButton(url: String) {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    IconButton(
        onClick = {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", url))
            AppToast.show(ctx, "已复制", Toast.LENGTH_SHORT)
        },
        modifier = Modifier.size(AppDimens.controlHeightCompact)
    ) {
        Icon(Icons.Outlined.ContentCopy, null, tint = muted, modifier = Modifier.size(AppDimens.iconSm))
    }
}

@Composable
private fun ParamRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, fontWeight = FontWeight.Medium)
    }
}


@Composable
private fun TavernOptRow(
    title: String,
    summary: String,
    details: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    disabledReason: String? = null
) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    val appColors = LocalAppColors.current
    val warning = appColors.warning
    val onSurface = MaterialTheme.colorScheme.onSurface
    val motion = LocalFluentMotion.current

    var showDetails by remember { mutableStateOf(false) }
    val accent = LocalAppColors.current.accent

    // The switch is the row's subject and the summary says what it does; the notes
    // explain how, and seven paragraphs of them open turn the option list into a wall
    // of text. They stay folded until asked for.
    Column(
        Modifier
            .fillMaxWidth()
            // This row keeps Material's ripple rather than the console's flat press highlight:
            // it is a settings row read as a block, and the highlight across its full width
            // reads heavier here than the ripple it replaces.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { showDetails = !showDetails }
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onSurface.copy(
                        alpha = if (enabled) 1f else AppAlpha.disabled
                    )
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 16.sp,
                    color = if (enabled) muted else muted.copy(alpha = AppAlpha.disabled),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!enabled && !disabledReason.isNullOrBlank()) {
                    Text(
                        disabledReason,
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 14.sp,
                        color = warning,
                        modifier = Modifier.padding(top = AppSpacing.xxs)
                    )
                }
            }
            // The arrow marks the row as expandable and shares the line with the
            // switch: a row of its own added 48dp of empty height to every option
            // in the list.
            IconButton(
                onClick = { showDetails = !showDetails },
                modifier = Modifier
                    .padding(top = AppSpacing.xxs)
                    .size(AppDimens.controlHeightCompact)
            ) {
                Icon(
                    if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (showDetails) "收起说明" else "展开说明",
                    tint = if (enabled) accent else muted.copy(alpha = AppAlpha.disabled),
                    modifier = Modifier.size(AppDimens.iconMd)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.padding(top = AppSpacing.xxs).scale(0.78f),
                colors = appSwitchColors()
            )
        }
        AnimatedVisibility(
            visible = showDetails,
            enter = motion.enterExpand(),
            exit = motion.exitShrink()
        ) {
            Text(
                details,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
                modifier = Modifier.padding(top = AppSpacing.xxs, end = AppSpacing.sm)
            )
        }
    }
}

@Composable
private fun TavernOptGroup(
    title: String,
    summary: String,
    content: @Composable () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    val accent = LocalAppColors.current.accent

    // The group header labels the block; its rows stay open so the whole page
    // reads as one list instead of a set of disclosures.
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = accent)
            Text(summary, style = MaterialTheme.typography.labelSmall, lineHeight = 15.sp, color = muted)
        }
        content()
    }
}

