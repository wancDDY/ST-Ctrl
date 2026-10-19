package com.tavern.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tavern.app.console.ThemeState
import com.tavern.app.console.components.BrandLockup
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.AppTheme
import com.tavern.app.console.theme.ApplySystemBars
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.node.NodeState
import androidx.compose.foundation.layout.width

@Composable
fun TavernTheme(content: @Composable () -> Unit) {
    val isDark by ThemeState.isDarkMode.collectAsState()
    ApplySystemBars(isDark)
    AppTheme(darkTheme = isDark, content = content)
}

// Startup surfaces use the theme background directly; no ambient decoration.
// The node boot phase is short and the user only needs to read one line of
// status, so the screen stays a single centred column.

@Composable
fun ResumeScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        BrandLockup(markSize = AppDimens.brandMarkCompact)
    }
}

@Composable
fun StartupScreen(onStart: () -> Unit, onEnterEarly: () -> Unit, onViewLog: () -> Unit) {
    val state by NodeState.state.collectAsState()
    val error by NodeState.lastError.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)).togetherWith(fadeOut(animationSpec = tween(160)))
            },
            label = "stateTransition",
            modifier = Modifier.fillMaxSize()
        ) { currentState ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (currentState) {
                    NodeState.State.IDLE,
                    NodeState.State.STARTING -> LoadingContent(onStart = onStart, onEnterEarly = onEnterEarly)

                    NodeState.State.STOPPING -> StoppingContent()

                    NodeState.State.ERROR -> ErrorContent(
                        error = error ?: "未知错误",
                        onRetry = onStart,
                        onViewLog = onViewLog
                    )

                    NodeState.State.RUNNING -> BrandLockup()
                }
            }
        }
    }
}


@Composable
private fun LoadingContent(onStart: () -> Unit, onEnterEarly: () -> Unit) {
    val phaseText by NodeState.phaseText.collectAsState()
    val bootStartedAt by NodeState.bootStartedAt.collectAsState()

    // Offer "enter anyway" while a boot attempt is in flight, so a slow start
    // never traps the user on this screen.
    val showEnterEarly = bootStartedAt > 0

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.xxxl)
        ) {
            BrandLockup()

            Spacer(modifier = Modifier.height(AppSpacing.xxxl))

            // A hairline bar replaces the spinner: quieter, and it reads as
            // progress rather than as a decorative animation.
            LinearProgressIndicator(
                modifier = Modifier.width(140.dp).height(2.dp),
                color = LocalAppColors.current.accent,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.tint)
            )

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // The phase caption is the only text that changes during boot, so
            // it is the only status line on the screen.
            //
            // Drawn without a cross-fade: the caption updates every 200 extracted
            // entries, which is faster than any transition, so an animation here
            // restarts before it finishes and the line flickers for the whole
            // extraction.
            Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = phaseText.ifEmpty { "正在启动服务" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (showEnterEarly) {
            TextButton(
                onClick = onEnterEarly,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = AppSpacing.xxxl)
            ) {
                Text(
                    text = "直接进入",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(error: String, onRetry: () -> Unit, onViewLog: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = AppSpacing.xxxl)
    ) {
        // Plain surface block instead of a tinted disc behind a glyph: the
        // failure message is the important part, not a decoration.
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.md),
            color = LocalAppColors.current.errorContainer
        ) {
            Text(
                text = "启动失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(AppSpacing.xxl))

        SoftActionButton(
            text = "重试",
            onClick = onRetry,
            modifier = Modifier.widthIn(min = 160.dp)
        )

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        TextButton(onClick = onViewLog) {
            Text(
                text = "查看日志",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StoppingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(AppSpacing.md))
        Text(
            text = "正在停止服务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
