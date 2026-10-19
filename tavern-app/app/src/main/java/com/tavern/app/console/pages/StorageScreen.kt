package com.tavern.app.console.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ListGroup
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.LoadingState
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StorageScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit
) {
    val storageInfo by viewModel.storageInfo.collectAsState()
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        loadError = false
        val result = viewModel.refreshStorageInfo()
        if (result.coreSize < 0) loadError = true
    }

    PageScaffold(title = "存储概览", onBack = onBack) {
        val info = storageInfo
        when {
            loadError -> Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "读取存储信息失败",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalAppColors.current.error
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    TextButton(onClick = {
                        scope.launch {
                            loadError = false
                            viewModel.refreshStorageInfo()
                        }
                    }) {
                        Text("重试", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            info == null -> LoadingState(
                text = "正在统计存储占用",
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f)
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = AppSpacing.xxl)
            ) {
                SectionLabel("占用明细")
                // Every block the app itself writes is split on demand; the free
                // space row is the device's number and stays closed.
                ListGroup {
                    StorageEntry(
                        icon = Icons.Outlined.Android,
                        label = "应用本体",
                        bytes = info.apkSize + info.nativeLibSize
                    ) {
                        StorageDetail("安装包", formatBytes(info.apkSize))
                        StorageDetail("运行库", formatBytes(info.nativeLibSize))
                    }
                    StorageEntry(
                        icon = Icons.Outlined.Storage,
                        label = "核心代码",
                        bytes = info.coreSize
                    ) {
                        StorageDetail("核心程序", formatBytes(info.coreProgramSize))
                        StorageDetail("依赖模块", formatBytes(info.coreModulesSize))
                        StorageDetail("已装扩展", formatBytes(info.coreExtensionsSize))
                    }
                    StorageEntry(
                        icon = Icons.Outlined.Folder,
                        label = "用户数据",
                        bytes = info.dataSize
                    ) {
                        StorageDetail("角色与聊天", formatBytes(info.charactersSize + info.chatsSize))
                        StorageDetail("向量数据", formatBytes(info.vectorsSize))
                        StorageDetail("缩略图与头像", formatBytes(info.thumbnailsSize))
                        StorageDetail("其他数据", formatBytes(info.otherDataSize))
                    }
                    StorageEntry(
                        icon = Icons.Outlined.Cached,
                        label = "其他占用",
                        bytes = info.otherSize
                    ) {
                        StorageDetail("缓存", formatBytes(info.cacheSize))
                        StorageDetail("数据库", formatBytes(info.databaseSize))
                        StorageDetail("设置", formatBytes(info.prefsSize))
                        StorageDetail("页面数据", formatBytes(info.webviewSize))
                        StorageDetail("其余", formatBytes(info.otherRestSize))
                    }
                    StorageEntry(
                        icon = Icons.Outlined.Archive,
                        label = "备份文件",
                        bytes = info.backupSize
                    ) {
                        StorageDetail("备份份数", "${info.backupCount} 份")
                        if (info.latestBackupAt > 0) {
                            StorageDetail(
                                "最近备份",
                                "${formatBackupStamp(info.latestBackupAt)}，${formatBytes(info.latestBackupSize)}"
                            )
                        }
                    }
                    ListRow(
                        icon = Icons.Outlined.SdCard,
                        title = "可用空间",
                        trailing = { StorageSize(info.freeSpace) }
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.lg, bottom = AppSpacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "总计占用",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatBytes(
                            info.apkSize + info.nativeLibSize + info.coreSize + info.dataSize +
                                info.otherSize + info.backupSize
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalAppColors.current.accent
                    )
                }
            }
        }
    }
}

/**
 * One block of the overview: the row itself, then what the block is made of.
 * Tapping the row opens the split with the same gesture and timing the extension
 * list uses for its entries, so a disclosure behaves identically on both pages.
 */
@Composable
private fun StorageEntry(
    icon: ImageVector,
    label: String,
    bytes: Long,
    detail: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val motion = LocalFluentMotion.current
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            icon = icon,
            title = label,
            onClick = { expanded = !expanded },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageSize(bytes)
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.disabled),
                        modifier = Modifier.size(AppDimens.iconMd)
                    )
                }
            }
        )
        AnimatedVisibility(
            visible = expanded,
            enter = motion.enterExpand(),
            exit = motion.exitShrink()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        // Lines up under the row's title: leading inset + glyph + gap.
                        start = AppSpacing.lg + AppDimens.iconMd + AppSpacing.md,
                        end = AppSpacing.page,
                        bottom = AppSpacing.md
                    ),
                content = detail
            )
        }
    }
}

/** One line of a split: what it covers on the left, its amount on the right. */
@Composable
private fun StorageDetail(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The size column, shared so the numbers line up whether or not the row expands. */
@Composable
private fun StorageSize(bytes: Long) {
    Text(
        text = formatBytes(bytes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatBytes(bytes: Long): String = com.tavern.app.util.FormatUtils.fileSize(bytes)

private fun formatBackupStamp(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
