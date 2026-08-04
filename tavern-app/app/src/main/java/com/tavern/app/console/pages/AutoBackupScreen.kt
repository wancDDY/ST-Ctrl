package com.tavern.app.console.pages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.backup.AutoBackupWorker
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val accent = Color(0xFFD4A853)
    val muted = onBg.copy(alpha = 0.4f)

    // ── Local edit buffers — only applied on "保存" click ──
    var enabled by remember { mutableStateOf(viewModel.autoBackupEnabled) }
    var interval by remember { mutableStateOf(viewModel.autoBackupInterval.toString()) }
    var maxKeep by remember { mutableStateOf(viewModel.autoBackupMaxKeep.toString()) }
    // Baseline of the last-saved values — drives the dirty flag.
    var saved by remember { mutableStateOf(Triple(enabled, interval, maxKeep)) }
    val dirty = saved != Triple(enabled, interval, maxKeep)
    var showDiscardDialog by remember { mutableStateOf(false) }

    // ── Live next-backup estimate (re-reads prefs) ──
    var nextBackupHint by remember { mutableStateOf("") }
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
                nextBackupHint = if (today >= nextDay) "下次开 App 即备"
                else "约 ${nextDay - today} 天后自动备份"
            } else {
                nextBackupHint = "首次开 App 即备"
            }
        }
        refreshHint()
        // Refresh on screen resume via snapshot
        snapshotFlow { enabled to interval }.collect { refreshHint() }
    }

    // ── Backup stats ──
    var backupCount by remember { mutableIntStateOf(0) }
    var backupSize by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { backupCount = viewModel.listBackupsCached().size }
        viewModel.refreshStorageInfo()
    }
    val storageInfo by viewModel.storageInfo.collectAsState()
    LaunchedEffect(storageInfo) { backupSize = storageInfo?.backupSize ?: 0L }

    Box(modifier = Modifier.fillMaxSize().background(bg).imePadding()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(24.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ──
            TextButton(onClick = {
                if (dirty) showDiscardDialog = true else onBack()
            }) { Text("← 返回", color = accent, fontSize = 14.sp) }
            Spacer(modifier = Modifier.height(4.dp))
            Text("自动备份", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = onBg)
            Text("打开 App 时检查，距上次备份满间隔天后自动执行", fontSize = 13.sp, color = muted,
                modifier = Modifier.padding(start = 2.dp))
            Spacer(modifier = Modifier.height(24.dp))

            // ── Status row ──
            Surface(shape = RoundedCornerShape(14.dp), color = surface,
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("$backupCount 份备份", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = onBg)
                        Text(FormatUtils.fileSize(backupSize), fontSize = 13.sp, color = muted)
                        if (enabled && nextBackupHint.isNotBlank()) {
                            Text(nextBackupHint, fontSize = 12.sp, color = accent,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accent,
                            checkedTrackColor = accent.copy(alpha = 0.25f)
                        )
                    )
                }
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(20.dp))

                // ── Interval ──
                Text("备份间隔", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onBg)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(1 to "每天", 2 to "2天", 3 to "3天", 7 to "每周").forEach { (d, label) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (interval == d.toString()) accent else accent.copy(alpha = 0.08f),
                            modifier = Modifier.clickable { interval = d.toString() }
                        ) {
                            Text(label, fontSize = 13.sp,
                                color = if (interval == d.toString()) Color(0xFF0A0A10) else accent,
                                fontWeight = if (interval == d.toString()) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
                // Custom input
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("自定义天数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = accent.copy(alpha = 0.15f),
                        focusedTextColor = onBg, unfocusedTextColor = onBg,
                        focusedLabelColor = accent, unfocusedLabelColor = muted
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ── Max keep ──
                Text("保留数量", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onBg)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(2 to "2份", 3 to "3份", 5 to "5份", 10 to "10份").forEach { (n, label) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (maxKeep == n.toString()) accent else accent.copy(alpha = 0.08f),
                            modifier = Modifier.clickable { maxKeep = n.toString() }
                        ) {
                            Text(label, fontSize = 13.sp,
                                color = if (maxKeep == n.toString()) Color(0xFF0A0A10) else accent,
                                fontWeight = if (maxKeep == n.toString()) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = maxKeep,
                    onValueChange = { maxKeep = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("自定义数量") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = accent.copy(alpha = 0.15f),
                        focusedTextColor = onBg, unfocusedTextColor = onBg,
                        focusedLabelColor = accent, unfocusedLabelColor = muted
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                // ── Save button — disabled (grey) until something changed ──
                Button(
                    onClick = {
                        val iv = interval.toIntOrNull() ?: 3
                        val max = maxKeep.toIntOrNull() ?: 3
                        viewModel.setAutoBackup(enabled)
                        viewModel.setAutoBackupInterval(iv)
                        viewModel.setAutoBackupMaxKeep(max)
                        saved = Triple(enabled, interval, maxKeep)
                        // Recalc hint immediately
                        val lastMs = ctx.getSharedPreferences("tavern_console_prefs", android.content.Context.MODE_PRIVATE)
                            .getLong("auto_backup_last_ms", 0L)
                        if (lastMs > 0) {
                            val lastDay = lastMs / 86_400_000L
                            val nextDay = lastDay + iv
                            val today = System.currentTimeMillis() / 86_400_000L
                            nextBackupHint = if (today >= nextDay) "下次开 App 即备"
                            else "约 ${nextDay - today} 天后自动备份"
                        } else {
                            nextBackupHint = "首次开 App 即备"
                        }
                        // Dismiss keyboard
                        focused.clearFocus()
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                    },
                    enabled = dirty,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color(0xFF0A0A10),
                        disabledContainerColor = accent.copy(alpha = 0.12f),
                        disabledContentColor = accent.copy(alpha = 0.35f)
                    )
                ) {
                    Text("保存设置", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
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
