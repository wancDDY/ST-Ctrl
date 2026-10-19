package com.tavern.app.console.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tavern.app.console.FileItem
import com.tavern.app.console.FileManager
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    accent: Color,
    muted: Color,
    onSurface: Color,
    modifier: Modifier = Modifier,
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
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    val iconTint = when {
        item.isDirectory -> accent
        FileManager.isImage(item.extension) -> MaterialTheme.colorScheme.primary
        FileManager.isText(item.extension) -> LocalAppColors.current.warning
        else -> muted
    }
    val highlightBg = if (isHighlighted) accent.copy(alpha = AppAlpha.subtle) else Color.Transparent
    // A highlighted row is marked by the accent itself; a half-strength accent reads
    // as a third tier that no other border in the app has.
    val highlightBorder = if (isHighlighted) accent else Color.Transparent
    Surface(
        modifier
            .fillMaxWidth().padding(vertical = 2.dp)
            // The selection band keeps its rounded shape; the row itself is not
            // clipped, so the touch feedback spans it with square corners.
            .then(if (isSelected) Modifier.background(accent.copy(alpha = AppAlpha.hairline), RoundedCornerShape(8.dp)) else Modifier)
            .then(if (isHighlighted) Modifier.background(highlightBg, RoundedCornerShape(8.dp)) else Modifier)
            .then(if (isHighlighted) Modifier.border(2.dp, highlightBorder, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = AppSpacing.lg, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    colors = appCheckboxColors(uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(Modifier.width(4.dp))
            }
            if (!selectMode && FileManager.isImage(item.extension)) {
                // Image files show a thumbnail (the shared-element transition source)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(item.file).crossfade(true).build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name + if (item.isDirectory) "/" else "", color = onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    if (!item.isDirectory) { Text(FileManager.formatSize(item.size), color = muted, style = MaterialTheme.typography.labelSmall); Text("，", color = muted, style = MaterialTheme.typography.labelSmall) }
                    Text(FileManager.formatDate(item.lastModified), color = muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!selectMode) {
                TrailingChevron()
            }
        }
    }
}
