package com.tavern.app.console.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.console.theme.pressHighlightIndication
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.AppDimens

@Composable
fun CollapsibleSection(label: String, content: String) {
    var expanded by remember { mutableStateOf(false) }
    val motion = LocalFluentMotion.current
    val shape = RoundedCornerShape(AppRadius.md)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.disabled),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // The header runs edge to edge so its ripple follows the card's own
            // corners. Insetting it (as it was) left the inner radius concentric
            // with nothing, which is what made the ripple look off.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = pressHighlightIndication(),
                        onClick = { expanded = !expanded }
                    )
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.disabled),
                    modifier = Modifier.size(AppDimens.iconMd)
                )
            }
            // The preview leaves on the same pair the full text arrives on. Removing it outright
            // (as it was) dropped the row by the preview's own height before the full text had
            // grown, so opening here did not read as the single grow the plugin list performs.
            AnimatedVisibility(
                visible = !expanded,
                enter = motion.enterExpand(),
                exit = motion.exitShrink()
            ) {
                Text(
                    content.take(60).replace("\n", " ") + if (content.length > 60) "…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.md)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = motion.enterExpand(),
                exit = motion.exitShrink()
            ) {
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.md)
                )
            }
        }
    }
}
