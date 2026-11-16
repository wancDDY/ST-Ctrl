package com.tavern.app.console.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing

/**
 * A placeholder with the shape of the thing being read.
 *
 * A spinner says "wait"; this says "a list of rows is coming, about this tall". On the four lists
 * this app loads from disk the shape is known before the read starts, so the wait can hold the
 * layout still instead of collapsing to one line and then jumping to a full screen.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.sm),
) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val lift = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val transition = rememberInfiniteTransition(label = "skeleton")
    // One pass, left to right, then a beat of nothing. A gradient that travels back and forth
    // reads as breathing, which is what a placeholder should not do.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonSweep",
    )
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                val span = size.width
                val head = sweep * (span * 2) - span
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, lift, base),
                        start = Offset(head, 0f),
                        end = Offset(head + span, 0f),
                    ),
                )
            }
    )
}

/**
 * Whether enough time has passed for a placeholder to be worth drawing.
 *
 * The delay is the whole point: a list read from disk in eighty milliseconds would otherwise draw a
 * screen of grey bars and replace them before they are read, which looks like something going wrong
 * rather than something loading. Anything faster than this threshold keeps the previous frame.
 */
@Composable
private fun rememberSkeletonShown(): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DELAY_MS)
        shown = true
    }
    return shown
}

private const val SKELETON_DELAY_MS = 130L

/** One list row's worth of placeholder: leading glyph, a title line, and a subtitle line. */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    withSubtitle: Boolean = true,
) {
    // A read that finishes inside a tenth of a second should not draw a placeholder at all: the
    // flicker of a shape that appears and is replaced before it is read looks like a fault, and the
    // content was almost there anyway.
    if (!rememberSkeletonShown()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The row a character list draws measures about 90dp with its 44dp avatar, so the
            // placeholder does too: at md it came out a third shorter and the list grew taller
            // the moment the read finished.
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The avatar plate a character row draws is 44dp with 12dp above and below it. Matching
        // both numbers is what keeps the rows that arrive from moving: a placeholder eight
        // pixels shorter than what replaces it makes the list lurch once the read finishes.
        SkeletonBlock(Modifier.size(AppDimens.iconBadge), RoundedCornerShape(AppRadius.sm))
        Spacer(Modifier.width(AppSpacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth(0.42f).height(14.dp))
            if (withSubtitle) SkeletonBlock(Modifier.fillMaxWidth(0.68f).height(11.dp))
        }
    }
}

/**
 * The first screenful of a list, before its rows exist.
 *
 * As many rows as the viewport takes, clipped at the bottom: a fixed few rows leave the lower
 * part of the screen empty, and the list that arrives then fills it, which reads as the page
 * growing rather than as the list arriving.
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 10,
    withSubtitle: Boolean = true,
) {
    if (!rememberSkeletonShown()) return
    Column(modifier.fillMaxWidth().clipToBounds()) {
        repeat(rows) {
            SkeletonRow(withSubtitle = withSubtitle)
        }
    }
}
