package com.tavern.app.console.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppCardShape
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.AppFigures
import com.tavern.app.console.theme.rowPressable

// A card is a fill, not a frame: an outlined rectangle around every block turned
// the pages into a grid of boxes, and the fill alone already separates a block
// from the page behind it.

/**
 * A block of [ListRow]s. Rows sit straight on the page background with no painted
 * block and no separators between them: a group is delimited by the [SectionLabel]
 * above it and the gap that follows.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), content = content)
}

/**
 * Horizontal list row: leading mark, title with optional subtitle, optional
 * trailing slot. Used for grouped entries where one item owns the full width.
 * Carries no container of its own — wrap rows in a [ListGroup].
 *
 * [leading] replaces the icon when the mark is not a vector (an avatar, a
 * thumbnail); it is measured at whatever size the caller gives it, so a row that
 * needs the platform's list-item weight can pass a 44dp image.
 */
@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconBadgeColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    // A row without a second line takes the one-line height, and a row with one takes
    // the two-line height. Padding per case inverted that: the one-line row got 24 and
    // ended up taller than its neighbour with a subtitle, which read as a gap in the
    // list rather than as two kinds of row.
    //
    // A row with an avatar takes the two-line height whether or not it has a subtitle: the mark
    // is 44–48dp and the one-line height left it 4dp of air above and below, which read as a
    // list of thumbnails rather than as list rows.
    val rowHeight = if (!subtitle.isNullOrBlank() || leading != null) {
        AppDimens.rowDouble
    } else {
        AppDimens.rowSingle
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .then(if (onClick != null) Modifier.rowPressable(onClick = onClick) else Modifier)
            // The row carries the page gutter itself, and SectionLabel carries the same
            // 16 inside; both therefore land on one edge whether or not the container pads.
            // A row that used a wider inset was the reason titles sat right of the label.
            .padding(horizontal = AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leading != null -> leading()
            iconBadgeColor != null -> Box(
                modifier = Modifier
                    .size(AppDimens.iconBadge)
                    .clip(CircleShape)
                    .background(iconBadgeColor.copy(alpha = AppAlpha.iconBadge)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconBadgeColor,
                    modifier = Modifier.size(AppDimens.iconBadgeGlyph)
                )
            }
            else -> Icon(
                imageVector = icon,
                contentDescription = null,
                // The accent marks what the row leads to. A page of grey glyphs reads as
                // disabled, and the icons are how a reader finds the entry they want before
                // reading any of the titles.
                tint = LocalAppColors.current.accent,
                modifier = Modifier.size(AppDimens.iconInline)
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            trailing()
        }
    }
}

/**
 * Section label above a group of entries. Semibold in the body colour, the way the
 * reference apps name a block: the accent is spent on state and on actions, and a
 * page of accent-coloured headings reads as decoration.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.xl, bottom = AppSpacing.sm)
    )
}

/**
 * Value tile: a label with the measurement underneath. Cards are reserved for
 * real data — never a wrapper around a decorative icon plus a caption.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(AppCardShape),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                text = value,
                // The value is a reading, not a sentence: fixed advance so a port that
                // goes from four digits to five does not shift the tile's edge.
                style = AppFigures.style(MaterialTheme.typography.titleMedium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
