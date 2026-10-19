package com.tavern.app.console.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tavern.app.console.ConsoleTask
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.node.NodeState

private fun compactStatusText(text: String, isRunning: Boolean): String {
    if (!isRunning) {
        return text
    }
    val fraction = Regex("(\\d+)\\s*/\\s*(\\d+)").find(text)
    val percent = fraction?.let {
        val done = it.groupValues[1].toIntOrNull()
        val total = it.groupValues[2].toIntOrNull()
        if (done != null && total != null && total > 0) "${done * 100 / total}%" else null
    }
    return when {
        text.startsWith("正在备份") && percent != null -> "备份 $percent"
        text.startsWith("正在还原") && percent != null -> "还原 $percent"
        text.startsWith("正在备份") -> "备份中"
        text.startsWith("正在还原") -> "还原中"
        else -> text.removePrefix("正在")
    }
}

internal data class TaskIslandDisplay(
    val text: String?,
    val running: Boolean,
    val info: Boolean
)

/**
 * Notice for an update found in the background. The island is one narrow slot,
 * so the text names the version instead of repeating the same headline for both
 * cases.
 */
internal fun pendingNoticeText(updates: com.tavern.app.update.PeriodicUpdateChecker.PendingUpdates): String? =
    when {
        updates.appVersion != null && updates.coreVersion != null ->
            "v${updates.appVersion}，核心 ${updates.coreVersion}"
        updates.appVersion != null -> "新版本 v${updates.appVersion}"
        updates.coreVersion != null -> "核心 ${updates.coreVersion}"
        else -> null
    }

internal fun resolveTaskIslandDisplay(
    tasks: List<ConsoleTask>,
    doneText: String?,
    versionNoticeText: String?
): TaskIslandDisplay {
    // Two operations at once cannot both be named in one slot, and shortening either one drops
    // the part that identifies it; the count stays true for however many are running.
    val text = when {
        tasks.size > 1 -> "${tasks.size} 个任务进行中"
        tasks.size == 1 -> compactStatusText(tasks[0].label, true)
        else -> doneText ?: versionNoticeText
    }
    return TaskIslandDisplay(
        text = text,
        running = tasks.isNotEmpty(),
        info = tasks.isEmpty() && doneText == null && versionNoticeText != null
    )
}

/**
 * Console home top bar: brand on the left, run state on the right, and the task island in the
 * middle. The island reports work that outlived its page, so tapping it returns to that work.
 *
 * The island sits between the two rather than replacing the run state: the run state answers a
 * different question, and hiding it whenever something was running made the bar look like it had
 * changed meaning rather than gained an item.
 */
@Composable
fun ConsoleTopBar(
    modifier: Modifier = Modifier,
    tasks: List<ConsoleTask> = emptyList(),
    doneNotice: CompletionNotice? = null,
    versionNoticeText: String? = null,
    updateNoticeText: String? = null,
    onInflightClick: () -> Unit = {},
    onVersionNoticeClick: () -> Unit = {},
    onUpdateNoticeClick: () -> Unit = {}
) {
    val state by NodeState.state.collectAsState()
    val isRunning = state == NodeState.State.RUNNING
    val isStarting = state == NodeState.State.STARTING
    val textColor = MaterialTheme.colorScheme.onBackground
    val appColors = LocalAppColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppDimens.topBarHeight)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The mark takes the icon ladder's large step: at the extra-large one it
        // outweighed the name beside it, which is the pair the about page sets the
        // proportion for.
        BrandMark(size = AppDimens.iconLg)
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        BrandWordmark(
            style = MaterialTheme.typography.titleLarge,
            color = textColor
        )

        // A pending update outranks the changelog reminder: it is the one the
        // user can act on. Both share the slot, so only one is shown at a time.
        val chip = resolveTaskIslandDisplay(
            tasks = tasks,
            doneText = doneNotice?.text,
            versionNoticeText = updateNoticeText ?: versionNoticeText
        )

        // The slot keeps a gutter of its own on both sides. A long report has to shrink into an
        // ellipsis rather than grow into the brand name or the run state: those two sit where the
        // reader expects to find them, and a pill that touched either would look like one thing
        // running into another.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.md),
            contentAlignment = Alignment.Center
        ) {
            if (chip.text != null) {
                InFlightChipHost(
                    text = chip.text,
                    isRunning = chip.running,
                    isInfo = chip.info,
                    isUpdate = updateNoticeText != null,
                    completionSuccess = doneNotice?.success,
                    onClick = {
                        if (chip.running) {
                            onInflightClick()
                        } else if (chip.info) {
                            if (updateNoticeText != null) onUpdateNoticeClick() else onVersionNoticeClick()
                        }
                    },
                    modifier = Modifier.widthIn(max = 200.dp)
                )
            }
        }

        RunStateBadge(isRunning = isRunning, isStarting = isStarting, color = textColor)
    }
}

@Composable
private fun RunStateBadge(isRunning: Boolean, isStarting: Boolean, color: Color) {
    val appColors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dotColor = when {
            isRunning -> appColors.success
            isStarting -> appColors.warning
            else -> appColors.error
        }
        Canvas(modifier = Modifier.size(AppDimens.statusDot)) {
            drawCircle(color = dotColor, radius = size.minDimension / 2)
        }
        Spacer(modifier = Modifier.width(AppSpacing.xs))
        Text(
            text = when {
                isRunning -> "运行中"
                isStarting -> "启动中"
                else -> "已停止"
            },
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = AppAlpha.onSurfaceTertiary)
        )
    }
}

/**
 * Top-level host for the in-flight chip. Keeps the pill a fixed-height element
 * so the row height does not change while it animates in and out.
 */
@Composable
private fun InFlightChipHost(
    text: String?,
    isRunning: Boolean,
    isInfo: Boolean,
    isUpdate: Boolean,
    completionSuccess: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = text != null,
            // Deliberately not filling the host: the pill has to be as wide as its own text, and
            // the host is what caps it. Filling made every report the width of the slot, which
            // read as a bar rather than as one item.
            enter = fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220), initialOffsetY = { -it / 2 }),
            exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180), targetOffsetY = { it / 2 })
        ) {
            val content = text ?: return@AnimatedVisibility
            val glyphColor = when {
                isRunning -> appColors.accent
                isInfo -> appColors.accent
                completionSuccess == true -> appColors.success
                completionSuccess == false -> appColors.error
                else -> appColors.error
            }
            val completion = completionSuccess != null
            // The island reports four different things in one slot, and a filled
            // plate for each state turned the brand row into a coloured block that
            // changed hue with every operation. The plate stays neutral and the one
            // glyph carries the state.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    // A slim pill: at the control height it filled the bar's full 56dp and read as
                    // a second bar rather than as a status item.
                    .heightIn(min = AppDimens.controlHeightDense)
                    .clickable(enabled = isRunning || isInfo, onClick = onClick)
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xxs),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    completion -> Icon(
                        imageVector = if (completionSuccess == true) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.ErrorOutline
                        },
                        contentDescription = null,
                        tint = glyphColor,
                        modifier = Modifier.size(AppDimens.iconSm)
                    )
                    isInfo -> Icon(
                        imageVector = if (isUpdate) {
                            Icons.Outlined.SystemUpdate
                        } else {
                            Icons.Outlined.NewReleases
                        },
                        contentDescription = if (isUpdate) "发现新版本" else "查看版本概要",
                        tint = glyphColor,
                        modifier = Modifier.size(AppDimens.iconSm)
                    )
                    isRunning -> {
                        val inf = rememberInfiniteTransition(label = "inflight_spin")
                        val angle by inf.animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                            label = "inflight_angle"
                        )
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = "操作进行中",
                            tint = glyphColor,
                            modifier = Modifier
                                .size(AppDimens.iconSm)
                                .graphicsLayer { rotationZ = angle }
                        )
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = content,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (completion) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The expanded list behind an island that reports more than one task. It is drawn over the page
 * instead of taking a row above it: a panel that owned layout space would push the page down at
 * the moment a background task started or finished, moving whatever the reader was looking at.
 *
 * The slot itself cannot name two operations, so this is where the full label of each one lives.
 */
@Composable
fun TaskPanel(
    tasks: List<ConsoleTask>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appColors = LocalAppColors.current
    Surface(
        modifier = modifier.widthIn(min = 200.dp, max = 320.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AppRadius.md),
        shadowElevation = 8.dp,
    ) {
        Column {
            tasks.forEachIndexed { index, task ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.hairline)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(task.kind) }
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(AppDimens.statusDot)
                            .clip(CircleShape)
                            .background(appColors.accent)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!task.detail.isNullOrBlank()) {
                            Text(
                                text = task.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    TrailingChevron()
                }
            }
        }
    }
}

/**
 * One-shot completion signal shown by the top bar for a couple of seconds
 * after a backup/restore finishes (instead of the chip simply vanishing with
 * no feedback for the user).
 */
data class CompletionNotice(
    val success: Boolean,
    val text: String
)
