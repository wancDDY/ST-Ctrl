package com.tavern.app.console.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Entrance fade-in with a small upward drift.
 * Animates when [visible] becomes true.
 */
@Composable
fun Modifier.entrance(
    visible: Boolean = true,
    offsetPx: Float = 12f
): Modifier {
    val motion = LocalFluentMotion.current
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = motion.normalMs, easing = motion.easing),
        label = "fluentEntrance"
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * offsetPx
    }
}

/**
 * Expandable-row entrance: vertical grow + fade on the standard duration and
 * easing. Every in-place reveal in the console consumes this pair, so a row
 * opens the same way on any page.
 */
@Composable
fun FluentMotionSpec.enterExpand(): EnterTransition =
    // Top, not the default bottom: growth anchored at the bottom pulls the content up out of the
    // edge, while anchoring it at the top opens the block downward and pushes what follows it
    // down by the same amount, which is what an expandable row is supposed to look like.
    expandVertically(
        expandFrom = Alignment.Top,
        animationSpec = tween(expandMs, easing = easingOut),
    ) + fadeIn(animationSpec = tween(expandMs * 2 / 3, easing = easingOut))

/**
 * Expandable-row collapse: shrink + fade on the shorter duration and the
 * "leaving" easing. Collapsing on the entrance duration kept the freed space
 * until the fade finished, so the rows below sat still and then jumped.
 */
@Composable
fun FluentMotionSpec.exitShrink(): ExitTransition =
    // The mirror of the entrance: the block closes upward and the rows below come back with it.
    shrinkVertically(
        shrinkTowards = Alignment.Top,
        animationSpec = tween(expandMs * 3 / 4, easing = easingOut),
    ) + fadeOut(animationSpec = tween(expandMs * 3 / 4, easing = easingOut))

/**
 * Travel for a thumb — switch, segmented control, any control whose handle moves between slots.
 *
 * A spring rather than a tween: a handle that stops dead reads as the state having been swapped
 * rather than thrown, and the small overshoot is what makes the new position legible. Damping is
 * high enough that it settles in one pass; a second bounce reads as the tap having missed.
 */
fun FluentMotionSpec.thumbSpring(): SpringSpec<Dp> =
    spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

/**
 * Press-down for a whole card.
 *
 * A card that grows on tap cannot use a tint or a ripple: the shape changes underneath the
 * feedback. Scale reads as the card being pushed in, costs no colour, and settles once.
 */
fun FluentMotionSpec.cardPressSpring(): SpringSpec<Float> =
    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
