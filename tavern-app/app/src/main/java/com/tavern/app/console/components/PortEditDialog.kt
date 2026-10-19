package com.tavern.app.console.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.tavern.app.console.SettingsState
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.util.AppToast

/**
 * Shared Node port editor, reachable from the home stat tile and from the
 * server status details so the port can be changed wherever it is shown.
 */
@Composable
fun PortEditDialog(
    currentPort: Int,
    onDismiss: () -> Unit,
    onSaved: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf(currentPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AppRadius.lg),
        title = { Text("服务端口", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter { it.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    text = "范围 1024-65535。保存后会自动重启服务，并重新加载核心页面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            DialogConfirmButton(text = "保存", onClick = {
                val parsed = input.toIntOrNull()
                if (parsed == null || parsed !in 1024..65535) {
                    AppToast.show(context, "端口需在 1024-65535 之间", Toast.LENGTH_SHORT)
                } else {
                    SettingsState.setNodePort(context, parsed)
                    onSaved(parsed)
                    onDismiss()
                    AppToast.show(context, "端口已改为 $parsed，正在重启服务", Toast.LENGTH_LONG)
                }
            })
        },
        dismissButton = {
            DialogDismissButton(text = "取消", onClick = onDismiss)
        }
    )
}
