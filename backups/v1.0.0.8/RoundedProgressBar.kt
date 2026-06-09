package com.tavern.app.console.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A progress bar with fully rounded ends (pill shape).
 *  Pass [progress] 0..1 for determinate, or null for indeterminate pulse. */
@Composable
fun RoundedProgressBar(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    color: Color = Color(0xFFD4A853),
    trackColor: Color = color.copy(alpha = 0.15f),
    height: Dp = 6.dp
) {
    val radius = height / 2

    if (progress != null) {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(300)
        )
        Box(
            modifier = modifier
                .height(height)
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .background(color)
            )
        }
    } else {
        val infinite = rememberInfiniteTransition(label = "indeterminate")
        val alpha by infinite.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        Box(
            modifier = modifier
                .height(height)
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(alpha)
                    .background(color)
            )
        }
    }
}
