package com.tavern.app.console.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Windows raises a flat rectangle over the pressed control; Material grows a circle out of the
 * touch point. Everywhere else the ripple is fine — a short row slices the circle down to
 * something rectangular-looking — but a control that grows when it opens ends up tall enough for
 * the whole disc to show behind its content. Only those controls take this indication, through
 * `indication = pressHighlightIndication()`; the node's own clip still rounds it where the node
 * is rounded.
 */
@Composable
fun pressHighlightIndication(
    alpha: Float = PressHighlightIndication.DEFAULT_ALPHA,
): PressHighlightIndication {
    val color = MaterialTheme.colorScheme.onSurface
    return remember(color, alpha) { PressHighlightIndication(color, alpha) }
}

class PressHighlightIndication(
    private val color: Color,
    private val pressedAlpha: Float = DEFAULT_ALPHA,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressHighlightNode(interactionSource, color, pressedAlpha)

    override fun equals(other: Any?): Boolean =
        other is PressHighlightIndication && other.color == color && other.pressedAlpha == pressedAlpha

    override fun hashCode(): Int = 31 * color.hashCode() + pressedAlpha.hashCode()

    companion object {
        /** Enough to read on a white card; 8% was not, which is why the switch lost its feedback. */
        const val DEFAULT_ALPHA = 0.08f
        /** For a whole card: a larger surface needs less depth, not more. */
        const val CARD_ALPHA = 0.10f
        /** For a list row: taller than a control, so it carries a little more than 8%. */
        const val ROW_ALPHA = 0.10f
    }
}

/**
 * Press feedback for a list row.
 *
 * The row keeps the platform ripple: a flat fill cannot be seen on a long press that is still
 * held, while the ripple spreads from the finger and says the press is being held. The rows
 * reach both screen edges, so the disc is clipped by the row's own rectangle instead of
 * stopping short of the gutter.
 *
 * The colour is the theme's ink, lightened for the dark theme by the same token. The platform
 * default is a dark state layer, which on a black page is darker than the page it is drawn on
 * and looks like no feedback at all.
 */
fun Modifier.rowPressable(
    enabled: Boolean = true,
    radius: Dp = Dp.Unspecified,
    onClick: () -> Unit,
): Modifier = composed {
    // Full-strength ink: the ripple multiplies this with its own state-layer alpha, so a colour
    // that is already faded comes out at a hundredth and the row looks unresponsive.
    val ink = MaterialTheme.colorScheme.onSurface
    clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(color = ink, radius = radius),
        onClick = onClick,
    )
}

private class PressHighlightNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val pressedAlpha: Float,
) : Modifier.Node(), DrawModifierNode {

    private val alpha = Animatable(0f)
    private var observeJob: Job? = null

    override fun onAttach() {
        observeJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val target = if (interaction is PressInteraction.Press) pressedAlpha else 0f
                launch { alpha.animateTo(target, tween(durationMillis = FADE_MS)) }
            }
        }
    }

    override fun onDetach() {
        observeJob?.cancel()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (alpha.value > 0f) {
            drawRect(color = color, alpha = alpha.value)
        }
    }

    private companion object {
        const val FADE_MS = 80
    }
}
