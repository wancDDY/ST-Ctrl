package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.backup.BackupMetadata
import com.tavern.app.backup.BackupEntry
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.SettingsState
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.LoadingState
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.node.NodeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.tavern.app.console.components.PageTopBar
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.SwapHoriz
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.AppRadius

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun RestoreScreen(
    viewModel: ConsoleViewModel,
    onBack: () -> Unit,
    onRestoreComplete: () -> Unit,
    onOpenInFileManager: (String) -> Unit
) {
    var backups by remember { mutableStateOf<List<Pair<BackupEntry, BackupMetadata>>?>(null) }
    var selected by remember { mutableStateOf<Pair<BackupEntry, BackupMetadata>?>(null) }
    var selectedBackup by remember { mutableStateOf<Pair<BackupEntry, BackupMetadata>?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<BackupEntry?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameTargetEntry by remember { mutableStateOf<BackupEntry?>(null) }
    var showTermuxDialog by remember { mutableStateOf(false) }
    var termuxScript by remember { mutableStateOf<String?>(null) }
    var showPublicFolderPermissionDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val progress by viewModel.restoreProgress.collectAsState()
    val phase by viewModel.restorePhase.collectAsState()
    val log by viewModel.restoreLog.collectAsState()
    val result by viewModel.restoreResult.collectAsState()
    val nodeState by NodeState.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Cache backup contents and file counts to avoid re-reading ZIP
    val cachedContents = remember { mutableStateMapOf<String, List<String>>() }
    val cachedFileCounts = remember { mutableStateMapOf<String, Int>() }
    var pendingFolderAction by remember { mutableStateOf<String?>(null) }
    val publicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val action = pendingFolderAction
        pendingFolderAction = null
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            viewModel.backupManager.persistTreePermission(uri)
            viewModel.invalidateBackupCache()
            if (action == "termux") {
                if (viewModel.backupManager.writeTermuxScript()) {
                    termuxScript = viewModel.backupManager.termuxScriptCommand()
                    AppToast.show(ctx, "脚本已写入，请点按左侧复制按钮", Toast.LENGTH_LONG)
                } else {
                    AppToast.show(ctx, "脚本写入失败", Toast.LENGTH_LONG)
                }
            }
            scope.launch {
                backups = viewModel.listBackupsCached()
            }
        } catch (e: Exception) {
            AppToast.show(ctx, "无法保存目录授权：${e.message}", Toast.LENGTH_LONG)
        }
    }

    // File picker for importing backup from file system
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val tmpFile = File(ctx.cacheDir, "imported-backup-${System.currentTimeMillis()}.zip")
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Validate: try to read metadata first, then try compatibility mode
                    try {
                        var meta = viewModel.backupManager.readMetadata(tmpFile)
                        if (meta == null) {
                            meta = viewModel.backupManager.validateBackupZip(tmpFile)
                        }
                        if (meta != null) {
                        selected = BackupEntry(
                            name = tmpFile.name,
                            sizeBytes = tmpFile.length(),
                            lastModified = tmpFile.lastModified(),
                            file = tmpFile
                        ) to meta
                            showConfirm = true
                        } else {
                            AppToast.show(ctx, "该文件不是有效的核心备份", Toast.LENGTH_LONG)
                            tmpFile.delete()
                        }
                    } catch (_: Exception) {
                        AppToast.show(ctx, "无法读取备份文件，文件可能已损坏", Toast.LENGTH_LONG)
                        tmpFile.delete()
                    }
                } catch (_: Exception) {
                    AppToast.show(ctx, "无法读取选择的文件", Toast.LENGTH_LONG)
                }
            }
        }
    }

    // Auto-refresh backup list when user returns from Termux etc.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    viewModel.invalidateBackupCache()
                    backups = viewModel.listBackupsCached()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        // Do not wipe an operation that is still running (the user may have
        // tapped back into this screen via the top-bar pill mid-restore); only
        // clear any stale finished/error result from a previous session.
        if (viewModel.restoreState.value !is ConsoleViewModel.OpState.Running) {
            viewModel.clearRestoreState()
        }
        if (viewModel.backupState.value !is ConsoleViewModel.OpState.Running) {
            viewModel.clearBackupState()
        }
        selected = null
        viewModel.invalidateBackupCache()
        backups = viewModel.listBackupsCached()
        if (SettingsState.backupLocation() == "public" &&
            !viewModel.backupManager.hasPublicTreePermission()
        ) {
            showPublicFolderPermissionDialog = true
        }
    }

    // Once restore finishes (success or failure), delete the imported temp zip
    // so it doesn't linger in cache. Local backups in TavernBackups are kept.
    LaunchedEffect(result) {
        if (result != null) {
            selected?.first?.takeIf { it.isTemporaryImport }?.file?.delete()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageTopBar(
                title = "还原备份",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { filePicker.launch(arrayOf("application/zip", "*/*")) }) {
                        Icon(
                            Icons.Outlined.FileOpen,
                            contentDescription = "选择文件",
                            tint = LocalAppColors.current.accent,
                            modifier = Modifier.size(AppDimens.iconMd)
                        )
                    }
                    IconButton(onClick = {
                        val command = viewModel.backupManager.termuxScriptCommand()
                        if (command != null && viewModel.backupManager.hasPublicTreePermission()) {
                            viewModel.backupManager.writeTermuxScript()
                        }
                        termuxScript = command
                        showTermuxDialog = true
                    }) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = "Termux 迁移",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppDimens.iconMd)
                        )
                    }
                }
            )
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.page)) {
            Text("选择备份文件恢复用户数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            if (result != null) {
                result!!.fold(
                    onSuccess = {
                        com.tavern.app.console.components.RoundedProgressBar(progress = 1f, modifier = Modifier.fillMaxWidth(), color = LocalAppColors.current.success)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("还原成功", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        selected?.let { (entry, meta) ->
                            Text(entry.name, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
                            Text("${meta.totalSizeBytes / 1024 / 1024} MB", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        val restartText = when (nodeState) {
                            NodeState.State.STARTING -> "还原完成，正在后台重启核心服务…"
                            NodeState.State.RUNNING -> "还原完成，核心服务已重启"
                            NodeState.State.ERROR -> "还原完成，但核心服务重启失败"
                            else -> "还原完成，正在准备重启核心服务…"
                        }
                        Text(
                            restartText,
                            color = if (nodeState == NodeState.State.ERROR) LocalAppColors.current.error else MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (selected?.second?.containsAppSettings == true) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "备份中的应用设置已一并恢复",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        SoftActionButton(
                            text = "返回控制台",
                            onClick = onRestoreComplete,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    onFailure = { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = LocalAppColors.current.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("还原失败：${e.message}", color = LocalAppColors.current.error, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                )
            } else if (progress != null) {
                val (cur, total) = progress!!
                val pct = if (total > 0) (cur * 100 / total) else 0
                Text(phase, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(6.dp))
                com.tavern.app.console.components.RoundedProgressBar(progress = if (total > 0) cur.toFloat() / total else 0f, modifier = Modifier.fillMaxWidth(), color = LocalAppColors.current.accent)
                if (total > 0) Text("$pct% ($cur/$total)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(8.dp))
                // Terminal
                Surface(shape = RoundedCornerShape(AppRadius.md), color = com.tavern.app.console.theme.LogColors.Plate, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val listState = rememberLazyListState()
                    val lines = log.ifEmpty { listOf("\$ 等待中...") }
                    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size) }
                    LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                        items(lines) { line ->
                            Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 16.sp)
                        }
                        item { Text("> _", color = LocalAppColors.current.success, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else if (backups == null) {
                LoadingState(text = "正在读取备份…")
            } else if (backups!!.isEmpty()) {
                Text(
                    if (SettingsState.backupLocation() == "public" &&
                        !viewModel.backupManager.hasPublicTreePermission()
                    ) {
                        "公共备份文件夹未授权。可重新连接文件夹，或使用“选择文件”手动还原。"
                    } else {
                        "暂无备份文件"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = AppSpacing.xs)
                ) {
                    items(backups!!) { (entry, meta) ->
                        ListRow(
                            icon = Icons.Outlined.Archive,
                            title = entry.name.removeSuffix(".zip").replace("TavernBackup_", ""),
                            subtitle = buildString {
                                append("${meta.totalSizeBytes / 1024 / 1024} MB")
                                cachedFileCounts[entry.key]?.let { append("，$it 文件") }
                                if (meta.source == "termux") append("，Termux 迁移")
                                if (viewModel.backupManager.isPrivateBackup(entry)) append("，私有")
                            },
                            onClick = { selectedBackup = entry to meta },
                            modifier = Modifier.animateItem(),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { deleteTarget = entry },
                                        modifier = Modifier.size(AppDimens.iconBadge)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "删除备份",
                                            tint = LocalAppColors.current.error,
                                            modifier = Modifier.size(AppDimens.iconMd)
                                        )
                                    }
                                    TrailingChevron()
                                }
                            }
                        )
                    }
                }
            }
            }
        }
    }

    if (showRenameDialog && renameTargetEntry != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名备份") },
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
                    val entry = renameTargetEntry
                    if (entry != null && renameText.isNotBlank()) {
                        if (viewModel.backupManager.renameBackup(entry, renameText)) {
                            viewModel.invalidateBackupCache()
                            scope.launch { backups = viewModel.listBackupsCached() }
                        } else {
                            AppToast.show(ctx, "这个位置不支持改名，请到文件管理器里重命名", Toast.LENGTH_LONG)
                        }
                    }
                    showRenameDialog = false
                })
            },
            dismissButton = {
                DialogDismissButton(onClick = { showRenameDialog = false })
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    if (selectedBackup != null) {
        val (entry, meta) = selectedBackup!!
        val cacheKey = entry.key
        val contents = cachedContents[cacheKey]
        val fileCount = cachedFileCounts[cacheKey]
        LaunchedEffect(entry) {
            if (cacheKey !in cachedContents) {
                val result = viewModel.backupManager.getBackupContents(entry)
                cachedContents[cacheKey] = result
                cachedFileCounts[cacheKey] = result.size
            }
        }
        AlertDialog(
            onDismissRequest = { selectedBackup = null },
            title = {
                Text(entry.name.removeSuffix(".zip"))
            },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (fileCount != null) "$fileCount 个文件" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${meta.totalSizeBytes / 1024 / 1024} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftActionButton(
                            text = "重命名",
                            onClick = {
                                renameTargetEntry = entry
                                renameText = entry.name.removeSuffix(".zip")
                                showRenameDialog = true
                                selectedBackup = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        SoftActionButton(
                            text = "打开位置",
                            onClick = {
                            if (viewModel.backupManager.isPrivateBackup(entry)) {
                                // Private backup — the system file manager can't open
                                // Android/data without root. Open it inside the app's
                                // own file manager instead.
                                selectedBackup = null
                                entry.file?.parentFile?.absolutePath?.let(onOpenInFileManager)
                            } else {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            entry.folderUri ?: entry.uri,
                                            "vnd.android.document/directory"
                                        )
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        ctx.startActivity(android.content.Intent.createChooser(intent, "打开位置"))
                                    } catch (_: Exception) {
                                        AppToast.show(ctx, "没有可用的文件管理器", Toast.LENGTH_SHORT)
                                    }
                                } catch (_: Exception) {
                                    AppToast.show(ctx, "没有可用的文件管理器", Toast.LENGTH_SHORT)
                                }
                            }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "备份内容",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (contents == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalAppColors.current.accent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在读取…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (contents.isEmpty()) {
                        Text("无法读取备份内容", color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(contents) { contentEntry ->
                                Text(contentEntry, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 2.dp).clickable {
                                        if (viewModel.backupManager.isPrivateBackup(entry)) {
                                            // Private backup — open inside the app's file manager
                                            entry.file?.parentFile?.absolutePath?.let(onOpenInFileManager)
                                        } else {
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                    setDataAndType(
                                                        entry.folderUri ?: entry.uri,
                                                        "vnd.android.document/directory"
                                                    )
                                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                try { ctx.startActivity(android.content.Intent.createChooser(intent, "打开位置")) }
                                                catch (_: Exception) { AppToast.show(ctx, "没有可用的文件管理器", Toast.LENGTH_SHORT) }
                                            } catch (_: Exception) { AppToast.show(ctx, "没有可用的文件管理器", Toast.LENGTH_SHORT) }
                                        }
                                    })
                            }
                        }
                    }
                }
            },
            dismissButton = {
                DialogDismissButton(text = "关闭", onClick = { selectedBackup = null })
            },
            confirmButton = {
                // Restoring is the reason the dialog was opened, so it belongs with the
                // dialog's own actions rather than among the row of secondary ones.
                DialogConfirmButton(
                    text = "恢复",
                    onClick = {
                        // The archive's own backup.json is the accurate description of
                        // what is inside; the list entry only carries what a directory
                        // listing can see. The confirmation text tells the reader
                        // whether their app settings are part of this archive, so it
                        // reads the real metadata first.
                        scope.launch {
                            val realMeta = withContext(Dispatchers.IO) {
                                viewModel.backupManager.readMetadata(entry)
                            } ?: meta
                            selected = entry to realMeta
                            showConfirm = true
                            selectedBackup = null
                        }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    if (showConfirm && selected != null) {
        val safeSelected = selected!!
        ConfirmDialog(
            title = "还原备份",
            message = "将用「${safeSelected.first.name}」覆盖当前所有用户数据，还原后服务会自动重启。此操作不可撤销，确定继续？",
            confirmText = "还原",
            onConfirm = { showConfirm = false; viewModel.startRestore(safeSelected.first) },
            onDismiss = {
                showConfirm = false
                // Remove the imported temp zip if the user cancelled
                safeSelected.first.takeIf { it.isTemporaryImport }?.file?.delete()
            }
        )
    }

    if (showPublicFolderPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPublicFolderPermissionDialog = false },
            title = { Text("连接公共备份文件夹") },
            text = {
                Text(
                    "ST Ctrl 需要访问公共备份文件夹，才能显示其中的备份文件。\n\n" +
                        "可在系统文件选择器中选择已有文件夹，也可新建文件夹并选择。\n\n" +
                        "取消后也可通过「选择文件」还原任意 ZIP。",
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                DialogConfirmButton(text = "去选择", onClick = {
                    showPublicFolderPermissionDialog = false
                    pendingFolderAction = "list"
                    publicFolderPicker.launch(null)
                })
            },
            dismissButton = {
                DialogDismissButton(onClick = { showPublicFolderPermissionDialog = false })
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    // Termux data migration
    if (showTermuxDialog) {
        AlertDialog(
            onDismissRequest = { showTermuxDialog = false },
            title = { Text("Termux 数据迁移") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("将 Termux 上的 SillyTavern 数据迁移到 ST Ctrl。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("操作步骤：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(8.dp))
                    MigrationStep(
                        number = "1.",
                        text = "首次使用时，在 Termux 执行以下命令已允许文件访问。"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CommandCopyRow(command = "termux-setup-storage")
                    Spacer(modifier = Modifier.height(10.dp))
                    MigrationStep(
                        number = "2.",
                        text = "打开 Termux，复制并执行迁移命令。"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CommandCopyRow(command = termuxScript)
                    Spacer(modifier = Modifier.height(10.dp))
                    MigrationStep(
                        number = "3.",
                        text = "回到本页后列表会自动刷新。若没有出现新 ZIP，请确认 Termux 命令已执行成功；仍未出现时，可点按“选择文件”手动选择生成的 ZIP。"
                    )
                    MigrationStep(
                        number = "4.",
                        text = "选择迁移备份并还原，完成后核心会自动重启。"
                    )
                }
            },
            confirmButton = {
                SoftActionButton(
                    text = if (viewModel.backupManager.hasPublicTreePermission()) {
                        "重新生成脚本"
                    } else {
                        "选择文件夹并生成脚本"
                    },
                    onClick = {
                        if (!viewModel.backupManager.hasPublicTreePermission()) {
                            pendingFolderAction = "termux"
                            publicFolderPicker.launch(null)
                        } else if (viewModel.backupManager.writeTermuxScript()) {
                            termuxScript = viewModel.backupManager.termuxScriptCommand()
                            AppToast.show(ctx, "脚本已写入，请点按左侧复制按钮", Toast.LENGTH_LONG)
                            viewModel.invalidateBackupCache()
                        } else {
                            AppToast.show(ctx, "写入失败，请重新选择文件夹", Toast.LENGTH_LONG)
                        }
                    }
                )
            },
            dismissButton = {
                DialogDismissButton(text = "关闭", onClick = { showTermuxDialog = false })
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    if (deleteTarget != null) {
        ConfirmDialog(
            title = "删除备份",
            message = "确定删除此备份吗？",
            confirmText = "删除",
            onConfirm = {
                val entry = deleteTarget!!
                deleteTarget = null
                isDeleting = true
                scope.launch {
                    val deleted = viewModel.backupManager.deleteBackup(entry)
                    viewModel.invalidateBackupCache()
                    backups = viewModel.listBackupsCached()
                    isDeleting = false
                    // The list refreshes either way, so a refused delete would have
                    // reported success and then shown the file still there.
                    AppToast.show(
                        ctx,
                        if (deleted) "已删除" else "删除失败，这个位置不允许删除",
                        android.widget.Toast.LENGTH_SHORT
                    )
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun MigrationStep(
    number: String,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CommandCopyRow(
    command: String?
) {
    val hasCommand = !command.isNullOrBlank()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CopyCommandButton(command)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.onSurfaceTertiary),
            shape = RoundedCornerShape(AppRadius.sm),
            border = if (hasCommand) androidx.compose.foundation.BorderStroke(
                AppDimens.hairline,
                MaterialTheme.colorScheme.outlineVariant
            ) else null,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = command ?: "请先选择公共备份文件夹",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
private fun CopyCommandButton(command: String?) {
    val context = LocalContext.current
    val enabled = !command.isNullOrBlank()
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("command", command))
            AppToast.show(context, "已复制", Toast.LENGTH_SHORT)
        },
        enabled = enabled,
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = "复制命令",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else AppAlpha.disabled),
            modifier = Modifier.size(AppDimens.iconSm)
        )
    }
}
