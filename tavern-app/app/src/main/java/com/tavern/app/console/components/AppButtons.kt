package com.tavern.app.console.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors

// Buttons follow the emphasis levels the platform's own settings app uses: a
// page's actions are outlined in neutral ink, a dialog's actions are labels with
// no container at all, and the only colour a button carries is the semantic one
// of an action that is about to destroy something. A tinted or filled container
// turns a single action into a block of colour, which is what the accent is kept
// away from — it belongs on switches, the segmented control and progress bars,
// where it says something the user is reading for.

/**
 * A page's action. Outlined rather than filled: the console's pages are settings
 * and lists, and one filled button among them reads as an advertisement.
 * [color] is passed only where the action itself is semantic (a confirmation of
 * something destructive, or a dismissal of an error).
 */
@Composable
fun SoftActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        colors = appOutlinedButtonColors(contentColor = color),
        border = appOutlinedButtonBorder(contentColor = color),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(end = 4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * The action button: install, download, update. It is a button and reads as one —
 * a soft fill in the action colour with the label on top — because a page-level
 * action has to look pressable. The action colour keeps it apart from the theme
 * accent, which belongs to state rather than to actions.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val action = LocalAppColors.current.action
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        colors = appTonalButtonColors(accent = action),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = action,
                modifier = Modifier
                    .padding(end = AppSpacing.xs)
                    .size(AppDimens.iconMd)
            )
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = action)
    }
}

/**
 * A dialog's confirming action: a label, no container. The accent goes on this one
 * and nowhere else in the row — the platform's own dialogs colour the action that
 * carries the decision and leave the dismissal plain, which is also the only way a
 * pair of containerless labels tells the user which one commits.
 */
@Composable
fun DialogConfirmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = LocalAppColors.current.accent
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
fun DialogDismissButton(
    text: String = "取消",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary)
        )
    }
}
