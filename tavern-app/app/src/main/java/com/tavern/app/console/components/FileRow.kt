package com.tavern.app.console.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.FileItem
import com.tavern.app.console.FileManager

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    accent: Color,
    muted: Color,
    onSurface: Color,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    isHighlighted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val icon = when {
        item.isDirectory -> Icons.Outlined.Folder
        FileManager.isImage(item.extension) -> Icons.Outlined.Image
        FileManager.isText(item.extension) -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    val iconTint = when {
        item.isDirectory -> accent
        FileManager.isImage(item.extension) -> Color(0xFF6B8EC2)
        FileManager.isText(item.extension) -> Color(0xFF6B5B9E)
        else -> muted.copy(alpha = 0.5f)
    }
    val highlightBg = if (isHighlighted) accent.copy(alpha = 0.12f) else Color.Transparent
    val highlightBorder = if (isHighlighted) accent.copy(alpha = 0.6f) else Color.Transparent
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .then(if (isSelected) Modifier.background(accent.copy(alpha = 0.08f)) else Modifier)
            .then(if (isHighlighted) Modifier.background(highlightBg, RoundedCornerShape(8.dp)) else Modifier)
            .then(if (isHighlighted) Modifier.border(2.dp, highlightBorder, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted.copy(alpha = 0.4f)),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name + if (item.isDirectory) "/" else "", color = if (item.isDirectory) onSurface else onSurface.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    if (!item.isDirectory) { Text(FileManager.formatSize(item.size), color = muted, fontSize = 11.sp); Text(" · ", color = muted.copy(alpha = 0.4f), fontSize = 11.sp) }
                    Text(FileManager.formatDate(item.lastModified), color = muted, fontSize = 11.sp)
                }
            }
            if (!selectMode) {
                Icon(Icons.Outlined.ChevronRight, null, tint = muted.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
            }
        }
    }
}
