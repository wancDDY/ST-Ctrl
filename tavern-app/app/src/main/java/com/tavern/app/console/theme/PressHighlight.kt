package com.tavern.app.console.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
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
fun pressHighlightIndication(): PressHighlightIndication {
    val color = MaterialTheme.colorScheme.onSurface
    return remember(color) { PressHighlightIndication(color) }
}

class PressHighlightIndication(private val color: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressHighlightNode(interactionSource, color)

    override fun equals(other: Any?): Boolean =
        other is PressHighlightIndication && other.color == color

    override fun hashCode(): Int = color.hashCode()
}

private class PressHighlightNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {

    private val alpha = Animatable(0f)
    private var observeJob: Job? = null

    override fun onAttach() {
        observeJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val target = if (interaction is PressInteraction.Press) PRESSED_ALPHA else 0f
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
        const val PRESSED_ALPHA = 0.08f
        const val FADE_MS = 80
    }
}
