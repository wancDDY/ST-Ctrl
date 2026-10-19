package com.tavern.app.console.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LogColors
import com.tavern.app.util.FormatUtils
import kotlinx.coroutines.delay

private const val TERMINAL_LINE_SP = 11
private const val TERMINAL_LINE_HEIGHT_SP = 15

/**
 * Live output of one long task, drawn as a terminal: the operation as a command line, the
 * steps it has taken, and a footer carrying the elapsed time and the bytes moved.
 *
 * The elapsed counter runs on its own timer rather than on progress events. A transfer that
 * reports no length produces no events at all for its whole duration, and a clock frozen at
 * 0s is indistinguishable from a task that has died.
 */
@Composable
fun TaskTerminal(
    command: String,
    lines: List<String>,
    status: String,
    startedAt: Long,
    running: Boolean,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    downloaded: Long = 0L,
    total: Long = 0L,
    /**
     * The action that ends the run, named for what it actually does: cancelling the install or
     * the update. A bare "stop" reads as pause, and this does not pause — it abandons the
     * transfer.
     */
    cancelLabel: String? = null,
    onCancel: (() -> Unit)? = null,
    hint: String? = null,
    maxLines: Int = 8,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(250L)
        }
    }
    val seconds = ((now - startedAt) / 1000L).coerceAtLeast(0L)
    val scroll = rememberScrollState()
    val shown = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
    LaunchedEffect(shown.size) {
        // One frame behind: the added line has to be measured before the scroll can reach it.
        delay(32L)
        scroll.scrollTo(scroll.maxValue)
    }

    val meta = buildString {
        append(seconds).append("s")
        if (downloaded > 0L) {
            append("，").append(FormatUtils.fileSize(downloaded))
            if (total > 0L) append(" / ").append(FormatUtils.fileSize(total))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(LogColors.Plate)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = LocalAppColors.current.accent
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                command,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = LogColors.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (cancelLabel != null && onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(cancelLabel, color = LogColors.Error, fontSize = 12.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (maxLines * TERMINAL_LINE_HEIGHT_SP).dp)
                .verticalScroll(scroll)
        ) {
            shown.forEach { line ->
                Text(
                    line,
                    fontSize = TERMINAL_LINE_SP.sp,
                    lineHeight = TERMINAL_LINE_HEIGHT_SP.sp,
                    fontFamily = FontFamily.Monospace,
                    color = terminalLineColor(line)
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))
        TerminalProgressLine(fraction = fraction, color = LocalAppColors.current.accent)
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                status + if (running) " ▌" else "",
                modifier = Modifier.weight(1f),
                fontSize = TERMINAL_LINE_SP.sp,
                fontFamily = FontFamily.Monospace,
                color = LogColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                meta,
                fontSize = TERMINAL_LINE_SP.sp,
                fontFamily = FontFamily.Monospace,
                color = LogColors.Muted
            )
        }
        if (hint != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(hint, fontSize = 10.sp, color = LogColors.Muted)
        }
    }
}

/**
 * A two-pixel track. A measured fraction fills it; an unmeasured one runs a window across
 * it, because a transfer that reports no length is still moving and a full bar sitting
 * still would say the opposite.
 */
@Composable
private fun TerminalProgressLine(fraction: Float?, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(LogColors.Default.copy(alpha = 0.14f))
    ) {
        if (fraction != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(color)
            )
        } else {
            val transition = rememberInfiniteTransition(label = "terminal")
            val head by transition.animateFloat(
                initialValue = -0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "head"
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val track = maxWidth
                Box(
                    modifier = Modifier
                        .offset(x = track * head)
                        .width(track * 0.25f)
                        .fillMaxHeight()
                        .background(color)
                )
            }
        }
    }
}

private fun terminalLineColor(line: String): Color = when {
    line.startsWith("✗") -> LogColors.Error
    line.startsWith("✓") -> LogColors.Success
    else -> LogColors.Default
}
