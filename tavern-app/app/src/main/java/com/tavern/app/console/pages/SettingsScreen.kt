package com.tavern.app.console.pages

import com.tavern.app.console.components.TitledListCard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.tavern.app.console.OptMode
import com.tavern.app.console.SettingsState
import com.tavern.app.console.ThemeState
import com.tavern.app.console.components.appSwitchColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark by ThemeState.isDarkMode.collectAsState()
    val appColors = LocalAppColors.current
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = onBg.copy(alpha = AppAlpha.onSurfaceTertiary)
    val bg = MaterialTheme.colorScheme.background

    val aboutVer = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
    val optMode = SettingsState.optMode()
    val optTier = SettingsState.optTier()
    val factor = SettingsState.currentFactor()
    val renderBudget = when {
        factor <= 0.25 -> "省电渲染"
        factor < 0.5 -> "轻量渲染"
        factor < 0.75 -> "均衡渲染"
        else -> "完整渲染"
    }
    val thermal = SettingsState.thermalLabel()

    val pageScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        // Rows are full-bleed and carry their own gutter, so a section reads as a
        // list rather than as an inset card. Only the page heading and the section
        // labels need the gutter applied here.
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(top = AppSpacing.sm, bottom = AppSpacing.xxl)
        ) {
            Column(Modifier.padding(horizontal = AppSpacing.lg)) {
                Text("设置", style = MaterialTheme.typography.headlineSmall, color = onBg)
                Text("外观、核心、关于", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            Spacer(Modifier.height(AppSpacing.xl))

            TitledListCard(title = "外观") {
                SettingsEntry(
                    title = "主题模式",
                    subtitle = if (isDark) "深色模式" else "浅色模式",
                    onClick = { scope.launch { ThemeState.toggle(ctx) } },
                    checked = isDark,
                    onCheckedChange = { scope.launch { ThemeState.toggle(ctx) } }
                )
            }

            Spacer(Modifier.height(AppSpacing.xxl))

            TitledListCard(title = "核心") {
                SettingsEntry(
                    title = "核心优化",
                    onClick = { onNavigate("settings_optimize") }
                )
                SettingsEntry(
                    title = "性能模式",
                    subtitle = (if (optMode == OptMode.AUTO) "智能自动" else optTier.label) +
                        "、${SettingsState.maxOldSpaceMb()}MB、${SettingsState.uvPoolSize()}线程、$renderBudget" +
                        if (thermal != "正常") "、$thermal" else "",
                    onClick = { onNavigate("settings_perf") }
                )
            }

            Spacer(Modifier.height(AppSpacing.xxl))

            TitledListCard(title = "关于") {
                SettingsEntry(
                    title = "版本与更新",
                    onClick = { onNavigate("update") }
                )
                SettingsEntry(
                    title = "ST Ctrl v$aboutVer",
                    onClick = { onNavigate("settings_about") }
                )
            }
            Spacer(Modifier.height(AppSpacing.xxxl))
        }
    }
}

/**
 * One block of settings rows: full width on the surface, no container of its own.
 * Rounded inset cards are what made a plain list read as a wall of separate
 * objects; the platform's own settings are rows on a surface with hairlines
 * between them.
 */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

/**
 * Row inside a [SettingsGroup]: title, subtitle and an optional switch. The row
 * opens the sub-page while the switch stays a quick toggle on the overview, so
 * frequent settings never need a push.
 *
 * No leading glyph and no trailing arrow: the subtitle already carries the current
 * value, so an arrow would add an element the reference settings screen does without.
 */
/**
 * One settings row. The second line is reserved for the row's own value — a live state, a
 * measurement, the current tier — because a list where every row carries a sentence of prose
 * reads as a page of paragraphs with switches in it, and the value the reader came for gets
 * lost among them. A row that only needs a name passes no subtitle.
 */
@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
    // Same height either way: a row that lost its second line keeps the space, so the settings list
    // does not turn into alternating tall and short strips.
    val verticalPadding = if (subtitle.isNullOrBlank()) AppSpacing.xl else AppSpacing.md
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = AppSpacing.xxl, end = AppSpacing.lg, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (checked != null && onCheckedChange != null) {
            Spacer(Modifier.width(AppSpacing.sm))
            // The switch consumes its own taps, so toggling it never opens the sub-page.
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = appSwitchColors()
            )
        }
    }
}


