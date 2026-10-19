package com.tavern.app.console.pages

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.backup.BackupEntry
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.MemoryScanState
import com.tavern.app.console.MemoryTaskState
import com.tavern.app.console.memoryTaskBlocksBackup
import com.tavern.app.console.SettingsState
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.RoundedProgressBar
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LogColors
import com.tavern.app.util.AppToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val progress by viewModel.backupProgress.collectAsState()
    val phase by viewModel.backupPhase.collectAsState()
    val log by viewModel.backupLog.collectAsState()
    val result by viewModel.backupResult.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    var showMemoryConflict by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameTargetEntry by remember { mutableStateOf<BackupEntry?>(null) }
    var pendingIncludeKeys by remember { mutableStateOf(true) }
    // Collected rather than read once: whether a memory pass is running decides if the reader
    // gets asked before a backup cuts it off, and that changes while the page is open.
    val memoryTask by MemoryTaskState.running.collectAsState()
    val memoryScan by MemoryScanState.running.collectAsState()
    val appColors = LocalAppColors.current

    val publicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            AppToast.show(context, "未选择公共备份文件夹", Toast.LENGTH_SHORT)
        } else {
            try {
                viewModel.backupManager.persistTreePermission(uri)
                SettingsState.setBackupLocation(context, "public")
                viewModel.startBackup("1.0.0", pendingIncludeKeys)
            } catch (e: Exception) {
                AppToast.show(context, "无法保存目录授权：${e.message}", Toast.LENGTH_LONG)
            }
        }
    }
    val listState = rememberLazyListState()

    if (showMemoryConflict) {
        AlertDialog(
            onDismissRequest = { showMemoryConflict = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text("整理记忆正在进行", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "创建备份要先停止核心服务，正在进行的整理会被中断——" +
                        "已经写入记忆的部分会保留，备份完成后可以重新整理。要继续备份吗？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                DialogConfirmButton(
                    text = "继续备份",
                    onClick = {
                        showMemoryConflict = false
                        showConfirm = true
                    },
                )
            },
            dismissButton = {
                DialogDismissButton(
                    text = "等整理完",
                    onClick = { showMemoryConflict = false },
                )
            },
        )
    }

    LaunchedEffect(Unit) {
        // Keep an in-flight operation's state intact if the user returns here
        // via the top-bar pill mid-backup; only clear stale results.
        if (viewModel.backupState.value !is ConsoleViewModel.OpState.Running) {
            viewModel.clearBackupState()
        }
    }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size) }

    PageScaffold(title = "创建备份", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.page)
        ) {
            if (progress == null && result == null) {
                Text(
                    text = "完整备份聊天记录、记忆库、角色卡、世界书、预设、UI 主题、扩展、应用设置与配置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.xl))
                SoftActionButton(
                    text = "开始备份",
                    onClick = {
                        // Creating a backup stops the core service, which cuts off a memory pass
                        // mid-batch. Say so before the reader commits to the backup.
                        if (memoryTaskBlocksBackup(memoryTask != null, memoryScan != null)) {
                            showMemoryConflict = true
                        } else {
                            showConfirm = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val isDone = result != null && result!!.isSuccess
                val (current, total) = progress ?: (0 to 0)
                val percent = if (isDone) 100 else if (total > 0) (current * 100 / total) else 0
                val barColor = if (isDone) appColors.success else appColors.accent

                Text(
                    text = if (isDone) "备份已完成" else phase.ifEmpty { "正在备份" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isDone) {
                    result!!.getOrNull()?.let { entry ->
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable {
                                renameTargetEntry = entry
                                renameText = entry.name.removeSuffix(".zip")
                                showRenameDialog = true
                            }
                        )
                        Text(
                            text = "${com.tavern.app.util.FormatUtils.fileSize(entry.sizeBytes)}，" +
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.lastModified)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "点按文件名可重命名",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.onSurfaceSecondary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))
                RoundedProgressBar(
                    progress = percent / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = barColor
                )
                if (!isDone && total > 0) {
                    Spacer(modifier = Modifier.height(AppSpacing.xxs))
                    Text(
                        text = "$percent%  ($current/$total)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))
                Surface(
                    shape = RoundedCornerShape(AppRadius.md),
                    color = LogColors.Plate,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    LazyColumn(state = listState, modifier = Modifier.padding(AppSpacing.md)) {
                        val lines = log.ifEmpty { listOf("等待中") }
                        items(lines) { line ->
                            Text(
                                text = line,
                                color = LogColors.Default,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                        item {
                            Text(
                                text = "> _",
                                color = LogColors.Success,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))
                if (result != null) {
                    result!!.fold(
                        onSuccess = {
                            SoftActionButton(text = "返回控制台", onClick = onBack, modifier = Modifier.fillMaxWidth())
                        },
                        onFailure = { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = appColors.error,
                                    modifier = Modifier.size(AppDimens.iconSm)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                Text(
                                    text = "备份失败：${e.message}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = appColors.error
                                )
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            SoftActionButton(
                                text = "重试",
                                onClick = {
                                    viewModel.clearBackupState()
                                    viewModel.startBackup()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.md))
            }
        }
    }

    if (showRenameDialog && renameTargetEntry != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名备份", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("备份名称") },
                    singleLine = true,
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                DialogConfirmButton(text = "确定", onClick = {
                    renameTargetEntry?.let { entry ->
                        if (renameText.isNotBlank()) {
                            // The entry addresses the file by its SAF document id, which
                            // stops resolving once the name changes, so the finished panel
                            // is dropped instead of pointing at a document that is gone.
                            val finalName = if (renameText.endsWith(".zip", ignoreCase = true)) {
                                renameText
                            } else {
                                "$renameText.zip"
                            }
                            if (viewModel.backupManager.renameBackup(entry, renameText)) {
                                AppToast.show(context, "已改名为 $finalName", Toast.LENGTH_SHORT)
                                viewModel.clearBackupState()
                            } else {
                                AppToast.show(context, "这个位置不支持改名，请到文件管理器里重命名", Toast.LENGTH_LONG)
                            }
                            viewModel.invalidateBackupCache()
                        }
                    }
                    showRenameDialog = false
                })
            },
            dismissButton = { DialogDismissButton(onClick = { showRenameDialog = false }) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg)
        )
    }

    if (showConfirm) {
        var location by remember { mutableStateOf(SettingsState.backupLocation()) }
        var includeKeys by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("创建备份", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // Only the branch that carries a risk is allowed to be a coloured
                    // banner; "no keys included" is ordinary information.
                    Surface(
                        color = if (includeKeys) appColors.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(AppRadius.sm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (includeKeys) Icons.Outlined.Warning else Icons.Outlined.Info,
                                contentDescription = null,
                                tint = if (includeKeys) appColors.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(AppDimens.iconSm)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(
                                text = if (includeKeys) {
                                    "备份包含 API key、聊天记录、记忆库与角色卡等敏感数据，文件为明文 zip，请妥善保管。"
                                } else {
                                    "不包含 API key；聊天记录、记忆库与角色卡仍会完整备份。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (includeKeys) appColors.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(AppSpacing.lg))
                    SectionLabel("保存位置", modifier = Modifier.padding(start = 0.dp))
                    // Said once for the group: a private file is deleted with the app, and that is
                    // the only thing about the two locations a reader has to weigh.
                    Text(
                        text = "公共目录用系统文件夹选择器指定；应用私有目录仅本应用可访问，卸载应用会一并删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AppSpacing.sm),
                    )
                    ChoiceRow(
                        selected = location == "public",
                        title = "公共目录",
                        onSelect = { location = "public" }
                    )
                    ChoiceRow(
                        selected = location == "private",
                        title = "应用私有目录",
                        onSelect = { location = "private" }
                    )

                    Spacer(Modifier.height(AppSpacing.md))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(AppSpacing.md))
                    SectionLabel("API Key", modifier = Modifier.padding(start = 0.dp))
                    ChoiceRow(
                        selected = includeKeys,
                        title = "备份 API key",
                        onSelect = { includeKeys = !includeKeys }
                    )
                }
            },
            confirmButton = {
                SoftActionButton(
                    text = "开始备份",
                    onClick = {
                        showConfirm = false
                        pendingIncludeKeys = includeKeys
                        if (location == "public" && !viewModel.backupManager.hasPublicTreePermission()) {
                            publicFolderPicker.launch(null)
                        } else {
                            SettingsState.setBackupLocation(context, location)
                            viewModel.startBackup("1.0.0", includeKeys)
                        }
                    }
                )
            },
            dismissButton = {
                DialogDismissButton(onClick = { showConfirm = false })
            }
        )
    }
}

/**
 * One radio option. The label is the whole row: what a choice costs or where it puts the file is
 * said once, above the group, instead of repeating under every option. The row keeps the height a
 * two-line option had, so a group of these does not read as a stack of thin strips.
 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
