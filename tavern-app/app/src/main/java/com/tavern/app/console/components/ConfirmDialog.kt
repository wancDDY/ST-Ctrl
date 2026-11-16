package com.tavern.app.console.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppPanelShape

/** Theme-aware confirm dialog with proper primary/outlined buttons. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    /** Null draws the confirm button alone, for a dialog that only informs. */
    dismissText: String? = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
        shape = AppPanelShape,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            DialogConfirmButton(text = confirmText, onClick = onConfirm)
        },
        dismissButton = dismissText?.let { label ->
            { DialogDismissButton(text = label, onClick = onDismiss) }
        },
    )
}
