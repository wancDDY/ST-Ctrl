package com.tavern.app.console.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tavern.app.backup.BackupScope
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppCardShape
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.rowPressable

/**
 * The six things a backup can carry, as switches.
 *
 * Shared by the automatic backup screen and the one-off backup dialog: the same
 * choice governs both, and a reader who ticked something off in one place should
 * not find it back on in the other.
 */
@Composable
internal fun BackupScopeSection(
    scope: BackupScope,
    onBg: Color,
    muted: Color,
    onChange: (BackupScope) -> Unit,
) {
    BackupScopeRow(
        title = "聊天与角色数据",
        detail = "聊天记录、角色、世界书、记忆",
        checked = scope.chats,
        onBg = onBg,
        muted = muted,
        onToggle = { onChange(scope.copy(chats = !scope.chats)) }
    )
    BackupScopeRow(
        title = "角色卡仓库",
        detail = "仓库里的角色卡与全部版本，存放在应用私有目录",
        checked = scope.library,
        onBg = onBg,
        muted = muted,
        onToggle = { onChange(scope.copy(library = !scope.library)) }
    )
    BackupScopeRow(
        title = "扩展程序",
        detail = "已安装的扩展及其数据",
        checked = scope.extensions,
        onBg = onBg,
        muted = muted,
        onToggle = { onChange(scope.copy(extensions = !scope.extensions)) }
    )
    BackupScopeRow(
        title = "核心配置",
        detail = "核心的 config.yaml",
        checked = scope.coreConfig,
        onBg = onBg,
        muted = muted,
        onToggle = { onChange(scope.copy(coreConfig = !scope.coreConfig)) }
    )
    BackupScopeRow(
        title = "应用设置",
        detail = "主题、端口、开关与自动备份计划",
        checked = scope.appSettings,
        onBg = onBg,
        muted = muted,
        onToggle = { onChange(scope.copy(appSettings = !scope.appSettings)) }
    )
    BackupScopeRow(
        title = "API Key",
        detail = if (scope.apiKeys) "备份可完整还原连接配置" else "备份不写入密钥文件",
        checked = scope.apiKeys,
        onBg = onBg,
        muted = muted,
        onToggle = { onChange(scope.copy(apiKeys = !scope.apiKeys)) }
    )
    if (scope.isEmpty) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "一项都没选，备份会停在这里。",
            style = MaterialTheme.typography.labelSmall,
            color = muted
        )
    }
}

@Composable
private fun BackupScopeRow(
    title: String,
    detail: String,
    checked: Boolean,
    onBg: Color,
    muted: Color,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Full-bleed like every other list in the console, with the page gutter carried
            // inside the row. Rounded with the dialog it is drawn in: these rows sit in the backup
            // dialog, and a square band inside a rounded panel reads as a block from elsewhere.
            .clip(AppCardShape)
            .rowPressable(onClick = onToggle)
            .heightIn(min = AppDimens.rowDouble)
            .padding(horizontal = AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        // Twelve, not four: the box and the first line of text were close enough to read as one
        // crowded pair, which is what made the rows feel tight even though they were not.
        //
        // The box carries the state on its own. Fading the text to say "off" made five of six
        // rows read as disabled rather than as switched off, and put the faintest type on the
        // page exactly where the reader is looking for the one thing they wanted to turn back on.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = onBg)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
