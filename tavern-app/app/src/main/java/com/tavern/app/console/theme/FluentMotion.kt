package com.tavern.app.console.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

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
 * Staggered entrance: items fade in and rise, delayed by [index].
 * With [onceReady]=true the animation layer is stripped entirely.
 */
@Composable
fun Modifier.staggeredEntrance(
    index: Int = 0,
    onceReady: Boolean = false,
    stepMs: Int = 40,
    offsetPx: Float = 16f
): Modifier {
    if (onceReady) return this

    val motion = LocalFluentMotion.current
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(index) {
        delay((index * stepMs).toLong())
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = motion.normalMs, easing = motion.easing)
        )
    }
    return graphicsLayer {
        val progress = animatable.value
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
    expandVertically(animationSpec = tween(normalMs, easing = easing)) +
        fadeIn(animationSpec = tween(normalMs, easing = easing))

/**
 * Expandable-row collapse: shrink + fade on the shorter duration and the
 * "leaving" easing. Collapsing on the entrance duration kept the freed space
 * until the fade finished, so the rows below sat still and then jumped.
 */
@Composable
fun FluentMotionSpec.exitShrink(): ExitTransition =
    shrinkVertically(animationSpec = tween(quickMs, easing = easingOut)) +
        fadeOut(animationSpec = tween(quickMs, easing = easingOut))
