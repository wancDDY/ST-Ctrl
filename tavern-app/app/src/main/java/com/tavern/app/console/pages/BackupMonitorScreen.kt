package com.tavern.app.console.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tavern.app.backup.BackupState
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.TaskTerminal
import com.tavern.app.console.theme.AppSpacing

/**
 * Live output for an automatic backup. The backup runs outside the manual create-backup
 * screen, so this page follows the shared state and never starts a second copy.
 */
@Composable
fun BackupMonitorScreen(onBack: () -> Unit) {
    val running by BackupState.running.collectAsState()
    val detail by BackupState.detail.collectAsState()
    val current by BackupState.current.collectAsState()
    val total by BackupState.total.collectAsState()
    val lines by BackupState.log.collectAsState()
    val startedAt by BackupState.startedAt.collectAsState()
    val status = when {
        running -> detail ?: "准备中…"
        lines.isNotEmpty() -> "备份已结束"
        else -> "等待备份"
    }
    val fraction = if (total > 0) current.toFloat() / total.toFloat() else null

    PageScaffold(title = "备份", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.page, vertical = AppSpacing.md)
        ) {
            Text(
                text = if (running) "自动备份中" else status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (running) {
                    "备份期间可以停留在这里查看进度"
                } else {
                    "备份已结束，可以返回备份记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AppSpacing.md))
            TaskTerminal(
                command = "backup --auto",
                lines = lines.ifEmpty { listOf("等待备份输出…") },
                status = status,
                startedAt = startedAt,
                running = running,
                fraction = fraction,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 16,
            )
        }
    }
}
