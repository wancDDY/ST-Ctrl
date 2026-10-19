package com.tavern.app.console.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.LocalAppColors

/**
 * Shared loading state: spinner + optional caption + soft fade-in.
 * Reused across all loading/computing/waiting screens for consistency.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    text: String = "加载中",
    spinnerSize: Int = 28,
) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "loadingFade"
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp).alpha(alpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = LocalAppColors.current.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(spinnerSize.dp)
        )
        if (text.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
