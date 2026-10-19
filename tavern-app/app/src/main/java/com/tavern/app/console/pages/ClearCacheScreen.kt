package com.tavern.app.console.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tavern.app.console.ConsoleViewModel
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.util.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ClearCacheScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var sizesRefreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(sizesRefreshKey) {
        sizes = withContext(Dispatchers.IO) {
            // These entries hold live service state or in-flight update payloads;
            // clearing them would break a running node or a pending update.
            val protected = setOf(
                "WebView", "tavern-node",
                "data-extract-bak", "ext-backup", "data-update-bak", "ext-update-bak", "core-update-bak",
                "core-update-tmp"
            )
            val webViewCache = File(context.cacheDir, "WebView")
            val webViewSize = if (webViewCache.exists()) webViewCache.walkTopDown()
                .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .sumOf { it.length() } else 0L

            var tempSize = 0L
            context.cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                val isProtected = name in protected || name.startsWith("tavern-update-") ||
                    name.startsWith("ext-tmp-") || (name.startsWith("ext-") && name.endsWith(".zip"))
                if (!isProtected) {
                    tempSize += if (file.isDirectory) file.walkTopDown()
                        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                        .sumOf { it.length() } else file.length()
                }
            }
            webViewSize to tempSize
        }
    }

    PageScaffold(title = "清除缓存", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.page)
                .padding(bottom = AppSpacing.xxl)
        ) {
            when {
                clearing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalAppColors.current.accent
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.md))
                    Text(
                        text = "正在清除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                clearError != null -> Column {
                    Text(
                        text = "清除失败：$clearError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalAppColors.current.error
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    SoftActionButton(
                        text = "重试",
                        onClick = { clearError = null },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> {
                    SectionLabel("可清理内容")
                    Surface(
                        shape = RoundedCornerShape(AppRadius.md),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            ClearRow("WebView 缓存", sizes?.first)
                            ClearRow("其他临时文件", sizes?.second)
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xl))
                    // Neutral ink like every other page action: the app colours an action only
                    // where the action itself is semantic (starting or stopping the service,
                    // deleting something). Clearing the cache destroys nothing — the dialog says
                    // so — and a coloured outline read as a warning about the wrong thing.
                    SoftActionButton(
                        text = "清除缓存",
                        onClick = { showConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showConfirm) ConfirmDialog(
        title = "清除缓存",
        message = "将清除 WebView 缓存和临时文件，不影响用户数据与角色卡。",
        confirmText = "清除",
        onConfirm = {
            showConfirm = false
            clearing = true
            clearError = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { viewModel.clearAppCache() }
                    withContext(Dispatchers.Main) {
                        val msg = if (bytes == 0L) "没有可清除的缓存" else "已释放 ${formatBytes(bytes)}"
                        AppToast.show(context, msg, android.widget.Toast.LENGTH_SHORT)
                        sizesRefreshKey++
                    }
                } catch (e: Exception) {
                    clearError = e.message ?: "未知错误"
                } finally {
                    clearing = false
                }
            }
        },
        onDismiss = { showConfirm = false }
    )
}

@Composable
private fun ClearRow(label: String, bytes: Long?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (bytes != null) formatBytes(bytes) else "计算中",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes <= 0) "0 B" else com.tavern.app.util.FormatUtils.fileSize(bytes)
