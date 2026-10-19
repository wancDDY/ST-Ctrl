package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.backup.AutoBackupWorker
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.SettingsState
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.appSwitchColors
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tavern.app.console.theme.AppRadius
import androidx.compose.ui.draw.clip

@Composable
fun AutoBackupScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val focused = LocalFocusManager.current
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val surface = MaterialTheme.colorScheme.surface
    val appColors = LocalAppColors.current
    val accent = appColors.accent
    val muted = onBg.copy(alpha = AppAlpha.onSurfaceTertiary)

    // Local edit buffers — only applied on "Save" click
    var enabled by remember { mutableStateOf(viewModel.autoBackupEnabled) }
    var interval by remember { mutableStateOf(viewModel.autoBackupInterval.toString()) }
    var maxKeep by remember { mutableStateOf(viewModel.autoBackupMaxKeep.toString()) }
    var backupLocation by remember { mutableStateOf(SettingsState.backupLocation()) }
    var includeApiKeys by remember { mutableStateOf(viewModel.autoBackupIncludeApiKeys) }
    // Baseline of the last-saved values — drives the dirty flag.
    var saved by remember { mutableStateOf(Triple(enabled, interval, maxKeep)) }
    var savedLocation by remember { mutableStateOf(backupLocation) }
    var savedIncludeApiKeys by remember { mutableStateOf(includeApiKeys) }
    val dirty = saved != Triple(enabled, interval, maxKeep) ||
        savedLocation != backupLocation || savedIncludeApiKeys != includeApiKeys
    var showDiscardDialog by remember { mutableStateOf(false) }
    val backupManager = viewModel.backupManager
    var nextBackupHint by remember { mutableStateOf("") }
    var publicGranted by remember { mutableStateOf(backupManager.hasPublicTreePermission()) }

    BackHandler {
        if (dirty) showDiscardDialog = true else onBack()
    }

    fun persistSettings() {
        val iv = interval.toIntOrNull() ?: 3
        val max = maxKeep.toIntOrNull() ?: 3
        viewModel.setAutoBackup(enabled)
        viewModel.setAutoBackupInterval(iv)
        viewModel.setAutoBackupMaxKeep(max)
        SettingsState.setBackupLocation(ctx, backupLocation)
        AutoBackupWorker.setIncludeApiKeys(ctx, includeApiKeys)
        saved = Triple(enabled, interval, maxKeep)
        savedLocation = backupLocation
        savedIncludeApiKeys = includeApiKeys
        val lastMs = ctx.getSharedPreferences("tavern_console_prefs", android.content.Context.MODE_PRIVATE)
            .getLong("auto_backup_last_ms", 0L)
        nextBackupHint = if (lastMs > 0) {
            val lastDay = lastMs / 86_400_000L
            val nextDay = lastDay + iv
            val today = System.currentTimeMillis() / 86_400_000L
            if (today >= nextDay) "下次启动应用时备份" else "约 ${nextDay - today} 天后自动备份"
        } else {
            "首次启动应用时备份"
        }
        focused.clearFocus()
        AppToast.show(ctx, "已保存", Toast.LENGTH_SHORT)
    }

    val publicFolderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                backupManager.persistTreePermission(uri)
                viewModel.invalidateBackupCache()
                backupLocation = "public"
                publicGranted = backupManager.hasPublicTreePermission()
                persistSettings()
            } catch (e: Exception) {
                AppToast.show(ctx, "无法保存目录授权：${e.message}", Toast.LENGTH_LONG)
            }
        }
    }

    // Live next-backup estimate (re-reads prefs)
    LaunchedEffect(Unit) {
        fun refreshHint() {
            if (!enabled) { nextBackupHint = ""; return }
            val iv = interval.toIntOrNull() ?: 3
            val lastMs = ctx.getSharedPreferences("tavern_console_prefs", android.content.Context.MODE_PRIVATE)
                .getLong("auto_backup_last_ms", 0L)
            if (lastMs > 0) {
                val lastDay = lastMs / 86_400_000L
                val nextDay = lastDay + iv
                val today = System.currentTimeMillis() / 86_400_000L
                nextBackupHint = if (today >= nextDay) "下次启动应用时备份"
                else "约 ${nextDay - today} 天后自动备份"
            } else {
                nextBackupHint = "首次启动应用时备份"
            }
        }
        refreshHint()
        // Refresh on screen resume via snapshot
        snapshotFlow { enabled to interval }.collect { refreshHint() }
    }

    // Backup stats
    var backupCount by remember { mutableIntStateOf(0) }
    var backupSize by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { backupCount = viewModel.listBackupsCached().size }
        viewModel.refreshStorageInfo()
    }
    val storageInfo by viewModel.storageInfo.collectAsState()
    LaunchedEffect(storageInfo) { backupSize = storageInfo?.backupSize ?: 0L }

    PageScaffold(
        title = "自动备份",
        onBack = { if (dirty) showDiscardDialog = true else onBack() },
        modifier = Modifier.background(bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = AppSpacing.page)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "每次打开应用时检查，距上次备份满设定天数后自动执行",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
            Spacer(modifier = Modifier.height(AppSpacing.lg))

            // Status row
            Surface(shape = RoundedCornerShape(AppRadius.md), color = surface,
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (enabled) "自动备份已开启" else "自动备份已关闭",
                            style = MaterialTheme.typography.titleMedium, color = onBg
                        )
                        Text(
                            if (enabled && nextBackupHint.isNotBlank()) nextBackupHint
                            else "$backupCount 份备份，${FormatUtils.fileSize(backupSize)}",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = appSwitchColors()
                    )
                }
            }

            if (!enabled) {
                Spacer(modifier = Modifier.height(AppSpacing.xl))
                Surface(shape = RoundedCornerShape(12.dp), color = surface.copy(alpha = AppAlpha.onSurfaceSecondary),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "开启后将按设定间隔自动创建备份；手动备份与还原不受影响。",
                        style = MaterialTheme.typography.bodySmall, lineHeight = 22.sp, color = muted,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(28.dp))

                AutoSettingHeader(Icons.Outlined.Schedule, "备份间隔", "每次打开应用时检查，超过间隔后自动执行", accent)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(1 to "每天", 2 to "2 天", 3 to "3 天", 7 to "每周").forEach { (d, label) ->
                        Surface(
                            onClick = { interval = d.toString() },
                            shape = RoundedCornerShape(AppRadius.md),
                            color = if (interval == d.toString()) accent else accent.copy(alpha = AppAlpha.hairline),
                            modifier = Modifier.clip(RoundedCornerShape(AppRadius.md))
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall,
                                color = if (interval == d.toString()) MaterialTheme.colorScheme.surface else accent,
                                fontWeight = if (interval == d.toString()) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
                // Custom input
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("自定义天数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = appTextFieldColors()
                )

                Spacer(modifier = Modifier.height(28.dp))

                AutoSettingHeader(Icons.Outlined.History, "保留份数", "超过后自动删除最旧的自动备份", accent)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(2 to "2份", 3 to "3份", 5 to "5份", 10 to "10份").forEach { (n, label) ->
                        Surface(
                            onClick = { maxKeep = n.toString() },
                            shape = RoundedCornerShape(AppRadius.md),
                            color = if (maxKeep == n.toString()) accent else accent.copy(alpha = AppAlpha.hairline),
                            modifier = Modifier.clip(RoundedCornerShape(AppRadius.md))
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall,
                                color = if (maxKeep == n.toString()) MaterialTheme.colorScheme.surface else accent,
                                fontWeight = if (maxKeep == n.toString()) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = maxKeep,
                    onValueChange = { maxKeep = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("自定义数量") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = appTextFieldColors()
                )

                Spacer(modifier = Modifier.height(28.dp))
                AutoSettingHeader(Icons.Outlined.FolderOpen, "备份位置", "备份 zip 写入哪个目录", accent)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { backupLocation = "public" }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = backupLocation == "public", onClick = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
                        Text("公共目录", style = MaterialTheme.typography.bodyLarge, color = onBg)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "用户选择的文件夹，可选择已有目录或新建目录",
                            style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp, color = muted
                        )
                    }
                    // The state sits on the row it describes; a line under the list
                    // read as if it applied to the whole group.
                    if (publicGranted || backupLocation == "public") {
                        Text(
                            if (publicGranted) "已授权" else "未授权",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (publicGranted) appColors.success else muted
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { backupLocation = "private" }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = backupLocation == "private", onClick = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
                        Text("应用私有目录", style = MaterialTheme.typography.bodyLarge, color = onBg)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "仅本应用可访问，卸载应用会一并删除",
                            style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp, color = muted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                AutoSettingHeader(Icons.Outlined.Key, "API Key", "敏感密钥是否写入备份", accent)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { includeApiKeys = !includeApiKeys }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeApiKeys, onCheckedChange = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
                        Text("自动备份包含 API key", style = MaterialTheme.typography.bodyLarge, color = onBg)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (includeApiKeys) "备份可完整还原连接配置" else "备份不写入密钥文件",
                            style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp, color = muted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save button — disabled until something changed, visible even when off.
            SoftActionButton(
                text = "保存设置",
                onClick = {
                    if (backupLocation == "public" && !backupManager.hasPublicTreePermission()) {
                        publicFolderPicker.launch(null)
                    } else {
                        persistSettings()
                    }
                },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(AppSpacing.xxxl))
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "放弃更改？",
            message = "有未保存的更改，返回将丢失这些修改。",
            confirmText = "放弃并返回",
            dismissText = "继续编辑",
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }
}

@Composable
private fun AutoSettingHeader(
    icon: ImageVector,
    title: String,
    summary: String,
    accent: Color
) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            // Every group heading in the console is semibold in the body colour; the
            // accent is spent on state and on actions.
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(summary, style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp, color = muted)
        }
    }
}
