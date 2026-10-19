package com.tavern.app.console.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tavern.app.R
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.BrandTypography

// The app name, spelled once for every surface that draws it.
const val BRAND_NAME = "ST Ctrl"

// The mark, the wordmark and the lockup they form are one asset: the console bar,
// the startup screens and the about page each draw part of it, and separate files
// are how the three drift into three different brands.

// Plate radius snapped to the token ladder. The launcher icon rounds its plate at
// roughly 22% of the edge, so the value follows the mark's drawn size: one radius
// for all three sizes either rounds the large plate past a squircle or squares off
// the small one.
private fun markRadius(size: Dp): Dp = when {
    size <= 32.dp -> AppRadius.sm
    size <= 72.dp -> AppRadius.md
    else -> AppRadius.lg
}

/**
 * The app's own launcher icon, reused in-app so the brand on the splash screen
 * and in the console bar matches the icon on the user's home screen: the same
 * plate colour and the same foreground drawable as the adaptive icon.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(markRadius(size)))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // The surface is a plate the launcher would fill, and the art inside it
            // stops short of the edges so a mask can cut them; scaling it up centres
            // the mark the way the shelf entry shows it. The factor matches the art's
            // own safe zone: a wordmark that reaches the plate's edge reads as clipped
            // rather than as a mark on a plate.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.15f
                    scaleY = 1.15f
                }
        )
    }
}

/**
 * The app name in the display face.
 *
 * [style] comes from the type scale so each surface keeps its own size; the face,
 * the weight and the tracking replace whatever the caller's base style carried,
 * which is what makes the three surfaces read as one wordmark. It is a single
 * short word, so it is never wrapped: a wrapped wordmark reads as a broken one,
 * and the width it needs is far below what any supported font scale leaves beside
 * the mark.
 */
@Composable
fun BrandWordmark(
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    Text(
        text = BRAND_NAME,
        style = BrandTypography.wordmark(style),
        color = color,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * The stacked lockup used by the full-screen startup surfaces: the mark above the
 * wordmark, centred. Two of those surfaces used to draw the mark alone, which made
 * one startup sequence look like two different screens.
 *
 * The name sits one step below the about page's display size and the mark stays
 * larger than the about page's inline one, so the full-screen pair keeps the
 * proportion of the pair that opens the about page.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = AppDimens.brandMark,
    wordmarkStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(size = markSize)
        Spacer(modifier = Modifier.height(AppSpacing.xl))
        BrandWordmark(style = wordmarkStyle, color = color)
    }
}
