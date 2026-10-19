package com.tavern.app.console.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.util.WebViewVersion

/**
 * Offers compat rendering where the engine behind the app's pages predates the CSS the
 * core ships. The switch acts at once, because the alternative — a choice the reader
 * confirms in a second place — is what the dialog exists to avoid.
 *
 * Laid out by hand rather than by [androidx.compose.material3.AlertDialog]: the platform
 * width is narrow enough to wrap the switch subtitle under the switch, and the answer
 * here is a decision, so it gets the room to be read as one.
 */
@Composable
fun CompatRenderDialog(
    webViewMajor: Int,
    compatEnabled: Boolean,
    onCompatChange: (Boolean) -> Unit,
    notes: List<String>,
    browserText: String,
    onBrowser: () -> Unit,
    enterText: String,
    onEnter: () -> Unit,
    // Whichever way the reader just chose is the way on, so it carries the accent; the
    // other one steps back instead of two buttons competing for the same tap.
    enterIsPrimary: Boolean,
    onDismissRequest: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(AppRadius.lg),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(horizontal = AppSpacing.xl, vertical = AppSpacing.xl)) {
                Text(
                    text = "建议开启兼容渲染",
                    style = MaterialTheme.typography.titleLarge,
                    color = onSurface,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "本机 WebView 版本为 $webViewMajor，低于核心界面需要的 " +
                        "${WebViewVersion.MIN_SUPPORTED_MAJOR}。这类内核不认识核心使用的" +
                        "新式样式，页面可能显示错乱。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    lineHeight = 22.sp,
                )
                Spacer(modifier = Modifier.height(AppSpacing.xl))
                HorizontalDivider(color = onSurface.copy(alpha = AppAlpha.hairline))
                Spacer(modifier = Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "兼容渲染修复",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "转换旧 WebView 不认识的现代 CSS 与页面布局",
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.lg))
                    Switch(
                        checked = compatEnabled,
                        onCheckedChange = onCompatChange,
                        colors = appSwitchColors(),
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                notes.forEachIndexed { index, note ->
                    if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The way on sits on the right, where the action that continues is
                    // expected; leaving for a browser is the alternative, so it reads first.
                    if (enterIsPrimary) {
                        DialogDismissButton(text = browserText, onClick = onBrowser)
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        DialogConfirmButton(text = enterText, onClick = onEnter)
                    } else {
                        DialogConfirmButton(text = browserText, onClick = onBrowser)
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        DialogDismissButton(text = enterText, onClick = onEnter)
                    }
                }
            }
        }
    }
}
