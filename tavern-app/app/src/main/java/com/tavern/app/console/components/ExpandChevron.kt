package com.tavern.app.console.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import com.tavern.app.console.theme.LocalFluentMotion

/**
 * The disclosure arrow, turning rather than being swapped.
 *
 * Swapping two icons on a boolean put the one part of an expansion that had no motion: the block
 * took a third of a second to open and the arrow flipped in a single frame. One glyph rotates to
 * both states instead, timed with the block, so the two finish together.
 */
@Composable
fun ExpandChevron(
    expanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val motion = LocalFluentMotion.current
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motion.expandMs, easing = motion.easingMove),
        label = "chevronTurn",
    )
    Icon(
        Icons.Outlined.ExpandMore,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.rotate(turn),
    )
}
