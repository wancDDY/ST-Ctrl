package com.tavern.app.console.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConsoleCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    surfaceColor: Color = Color(0xFF0E0E16),
    iconColor: Color = Color(0xFFD4A853),
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val isDark = surfaceColor == Color(0xFF0E0E16) || surfaceColor.red < 0.3f || surfaceColor.blue < 0.1f
    val textColor = if (isDark) Color(0xFFF0EDE0) else Color(0xFF1A1A1A)
    val subColor = if (isDark) Color(0xFF8A8A80) else Color(0xFF6A6A6A)

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(if (hero) 16.dp else 12.dp),
        color = surfaceColor,
        border = if (hero) BorderStroke(1.dp, Color(0xFFD4A853).copy(alpha = 0.25f)) else null
    ) {
        Row(
            modifier = Modifier.padding(if (hero) 18.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(if (hero) 28.dp else 22.dp))
            Spacer(modifier = Modifier.width(if (hero) 14.dp else 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = if (hero) 17.sp else 13.sp, fontWeight = if (hero) FontWeight.SemiBold else FontWeight.Medium, color = textColor)
                if (subtitle != null) Text(text = subtitle, fontSize = 11.sp, color = subColor)
            }
            if (trailing != null) { Spacer(modifier = Modifier.width(8.dp)); trailing() }
        }
    }
}
