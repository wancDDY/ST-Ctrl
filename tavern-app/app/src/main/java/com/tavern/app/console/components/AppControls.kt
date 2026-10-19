package com.tavern.app.console.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors

// Shared looks for the controls every page uses. A page that restates one of
// these locally is how the console ended up with three chevron glyphs and two
// dozen hand-written field colour sets.

/**
 * The console's single text-field look: the accent outlines a focused field, a
 * hairline neutral the rest of the time. Text and cursor colours are left at the
 * theme's defaults so a field cannot drift from the same field on another page.
 */
@Composable
fun appTextFieldColors(): TextFieldColors {
    val accent = LocalAppColors.current.accent
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.subtle),
        cursorColor = accent,
    )
}

/**
 * The one "this row opens something" mark. One glyph, size and tint everywhere:
 * a list whose rows alternate between a thin chevron and a thicker arrow reads as
 * two different controls.
 *
 * An expanding row uses [androidx.compose.material.icons.outlined.ExpandMore]
 * instead — pointing right means "go there", pointing down means "show more".
 */
@Composable
fun TrailingChevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
        modifier = modifier.size(AppDimens.iconMd),
    )
}

/**
 * The console's one switch look: a white thumb on the chosen colour when on, a
 * muted thumb on a hairline track when off, both dimmed while the row is
 * disabled. Every switch consumes this, so two switches on one page cannot
 * disagree about what "off" looks like.
 */
@Composable
fun appSwitchColors(trackColor: Color = LocalAppColors.current.accent): SwitchColors {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onBackground = MaterialTheme.colorScheme.onBackground
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = trackColor,
        uncheckedThumbColor = onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
        uncheckedTrackColor = onBackground.copy(alpha = AppAlpha.hairline),
        disabledCheckedThumbColor = onSurface.copy(alpha = AppAlpha.disabled),
        disabledCheckedTrackColor = trackColor.copy(alpha = AppAlpha.subtle),
        disabledUncheckedThumbColor = onSurface.copy(alpha = AppAlpha.strong),
        disabledUncheckedTrackColor = onSurface.copy(alpha = AppAlpha.hairline),
    )
}

/**
 * The tinted "quiet" button: accent at low alpha with the accent as its own
 * label, for secondary actions that must not compete with a filled button.
 *
 * The alpha is [AppAlpha.subtle]; the stronger [AppAlpha.tint] is a container
 * fill, and using both here is how two quiet buttons on one page end up with
 * different weights.
 */
@Composable
fun appTonalButtonColors(accent: Color = LocalAppColors.current.accent): ButtonColors {
    return ButtonDefaults.buttonColors(
        containerColor = accent.copy(alpha = AppAlpha.subtle),
        contentColor = accent,
        disabledContainerColor = accent.copy(alpha = AppAlpha.hairline),
        disabledContentColor = accent.copy(alpha = AppAlpha.strong),
    )
}

/**
 * Outline colours plus the border that has to match them. Restating an outlined
 * button locally means restating the border too, which is how the same control
 * ended up with several edge alphas.
 */
@Composable
fun appOutlinedButtonColors(
    contentColor: Color = LocalAppColors.current.accent,
): ButtonColors {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return ButtonDefaults.outlinedButtonColors(
        contentColor = contentColor,
        disabledContentColor = onSurface.copy(alpha = AppAlpha.disabled),
    )
}

/**
 * Border for [appOutlinedButtonColors]. Drawn at [AppAlpha.strong] of the same
 * colour as the label, so an error-coloured outline always matches its text; the
 * default tracks the colour factory's default so the pair cannot be mismatched.
 */
@Composable
fun appOutlinedButtonBorder(contentColor: Color = LocalAppColors.current.accent): BorderStroke {
    return BorderStroke(AppDimens.hairline, contentColor.copy(alpha = AppAlpha.strong))
}

/**
 * The console's one radio look. Only the selected colour is pinned: the ring of
 * an unselected radio and the disabled states stay at the theme defaults.
 */
@Composable
fun appRadioButtonColors(selectedColor: Color = LocalAppColors.current.accent): RadioButtonColors {
    return RadioButtonDefaults.colors(selectedColor = selectedColor)
}

/**
 * The console's one checkbox look. [uncheckedColor] is set because the box sits
 * on a row background rather than a plain surface in most places.
 */
@Composable
fun appCheckboxColors(
    checkedColor: Color = LocalAppColors.current.accent,
    uncheckedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
): CheckboxColors {
    return CheckboxDefaults.colors(checkedColor = checkedColor, uncheckedColor = uncheckedColor)
}

/**
 * The segmented control's only colour set: the chosen segment is the accent
 * plate with a surface label, exactly the active state the console used to draw
 * by hand. The unused segment stays transparent so the plate reads as a
 * selected mark, not as a container.
 */
@Composable
fun appSegmentedControlColors(): SegmentedButtonColors {
    val accent = LocalAppColors.current.accent
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    return SegmentedButtonDefaults.colors(
        activeContainerColor = accent,
        activeContentColor = MaterialTheme.colorScheme.surface,
        activeBorderColor = accent,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = onSurfaceVariant,
        inactiveBorderColor = onSurface.copy(alpha = AppAlpha.subtle),
        disabledActiveContainerColor = accent.copy(alpha = AppAlpha.subtle),
        disabledActiveContentColor = onSurface.copy(alpha = AppAlpha.disabled),
        disabledActiveBorderColor = Color.Transparent,
        disabledInactiveContainerColor = Color.Transparent,
        disabledInactiveContentColor = onSurface.copy(alpha = AppAlpha.disabled),
        disabledInactiveBorderColor = onSurface.copy(alpha = AppAlpha.hairline),
    )
}

/**
 * The console's one segmented control: equal-width segments, the active one on
 * the accent plate. Material 3's own row and buttons are used underneath, so
 * the control keeps the platform's selection semantics and the shared colours.
 */
@Composable
fun AppSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg)) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = appSegmentedControlColors(),
                label = { Text(label) },
            )
        }
    }
}


