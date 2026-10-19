package com.tavern.app.console

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.documentfile.provider.DocumentFile
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListGroup
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.util.AppToast
import com.tavern.app.util.FormatUtils

// Data tab. The folder grant is shown as its own entry so it can be inspected
// and changed at any time, not only during the first backup.

@Composable
fun ConsoleDataTab(
    viewModel: ConsoleViewModel,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var location by remember { mutableStateOf(SettingsState.backupLocation()) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var hasPublicGrant by remember { mutableStateOf(viewModel.backupManager.hasPublicTreePermission()) }
    var folderName by remember { mutableStateOf<String?>(null) }
    val storageInfo by viewModel.storageInfo.collectAsState()

    val refreshFolderName = {
        folderName = runCatching {
            viewModel.backupManager.publicTreeUri()?.let { uri ->
                DocumentFile.fromTreeUri(context, uri)?.name
            }
        }.getOrNull()
        hasPublicGrant = viewModel.backupManager.hasPublicTreePermission()
    }

    // Granting a folder only stores the destination; it never starts a backup.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            AppToast.show(context, "已取消选择", Toast.LENGTH_SHORT)
        } else {
            try {
                viewModel.backupManager.persistTreePermission(uri)
                SettingsState.setBackupLocation(context, "public")
                location = "public"
                refreshFolderName()
                AppToast.show(context, "备份文件夹已更新", Toast.LENGTH_SHORT)
            } catch (e: Exception) {
                AppToast.show(context, "无法保存文件夹授权：${e.message}", Toast.LENGTH_LONG)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageInfo()
        refreshFolderName()
    }

    val autoEnabled = viewModel.autoBackupEnabled
    val interval = viewModel.autoBackupInterval
    val maxKeep = viewModel.autoBackupMaxKeep
    // Everything the app occupies on the phone, including the installed package and the
    // data the app keeps outside files/core; the overview page shows the same sum.
    val totalUsed = storageInfo?.let {
        it.apkSize + it.nativeLibSize + it.coreSize + it.dataSize + it.otherSize + it.backupSize
    }

    val folderLabel = when {
        location == "private" -> "应用私有目录"
        !hasPublicGrant -> "公共目录，未授权"
        !folderName.isNullOrBlank() -> "公共目录，$folderName"
        else -> "公共目录"
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(top = AppSpacing.sm, bottom = AppSpacing.xxl)
    ) {
        SectionLabel("文件")
        ListGroup {
            ListRow(
                icon = Icons.Outlined.FolderOpen,
                title = "文件管理",
                onClick = { onNavigate("files") }
            )
            ListRow(
                icon = Icons.Outlined.Folder,
                title = "已授权文件夹",
                subtitle = folderLabel,
                onClick = { showFolderDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

        SectionLabel("备份")
        ListGroup {
            ListRow(
                icon = Icons.Outlined.Archive,
                title = "创建备份",
                onClick = { onNavigate("backup_run") }
            )
            ListRow(
                icon = Icons.Outlined.Unarchive,
                title = "恢复备份",
                onClick = { onNavigate("restore") }
            )
            ListRow(
                icon = Icons.Outlined.Schedule,
                title = "自动备份",
                subtitle = if (autoEnabled) "已开启，每 $interval 天保留 $maxKeep 份" else "已关闭",
                onClick = { onNavigate("auto_backup") }
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

        SectionLabel("存储")
        ListGroup {
            ListRow(
                icon = Icons.Outlined.Save,
                title = "存储概览",
                subtitle = totalUsed?.let {
                    "占用 ${FormatUtils.fileSize(it)}，可用 ${FormatUtils.fileSize(storageInfo?.freeSpace ?: 0L)}"
                },
                onClick = { onNavigate("storage") }
            )
            ListRow(
                icon = Icons.Outlined.CleaningServices,
                title = "清除缓存",
                onClick = { onNavigate("cache") }
            )
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("备份文件夹", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (hasPublicGrant) {
                                SettingsState.setBackupLocation(context, "public")
                                location = "public"
                                showFolderDialog = false
                            } else {
                                showFolderDialog = false
                                folderPicker.launch(null)
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = location == "public",
                            onClick = null
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "公共目录",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (folderName.isNullOrBlank()) "未选择文件夹" else folderName!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Always available, so the folder can be changed later
                        // without switching modes first.
                        TextButton(onClick = {
                            showFolderDialog = false
                            folderPicker.launch(null)
                        }) {
                            Text(
                                text = if (hasPublicGrant) "更换" else "选择",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            SettingsState.setBackupLocation(context, "private")
                            location = "private"
                            showFolderDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = location == "private",
                            onClick = null
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "应用私有目录",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "仅本应用可访问，卸载应用会一并删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                DialogDismissButton(text = "关闭", onClick = { showFolderDialog = false })
            }
        )
    }
}
