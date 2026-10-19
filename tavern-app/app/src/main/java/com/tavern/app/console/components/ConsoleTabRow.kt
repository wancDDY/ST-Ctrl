package com.tavern.app.console.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.LocalAppColors

/**
 * Tab row for console pages that switch between sibling views. One shared
 * implementation is what keeps those pages looking alike: separate copies drift
 * apart on the first tweak, and the pages then read as different designs.
 * The row stays transparent so the page background carries the grouping; the
 * selected tab is marked by the accent ink and the TabRow indicator alone, since
 * these tabs switch a view rather than choose a value.
 */
@Composable
fun ConsoleTabRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAppColors.current.accent
    val onBackground = MaterialTheme.colorScheme.onBackground
    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        contentColor = accent,
        divider = { HorizontalDivider(color = onBackground.copy(alpha = AppAlpha.hairline)) },
        modifier = modifier.fillMaxWidth()
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                text = {
                    Text(
                        title,
                        fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedIndex == index) {
                            accent
                        } else {
                            onBackground.copy(alpha = AppAlpha.onSurfaceSecondary)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            )
        }
    }
}
