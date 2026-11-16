package com.tavern.app.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.FluentColors
import com.tavern.app.console.theme.LogColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the design-token contract: one spacing rhythm, one corner scale, and
 * text colours that stay legible against the surfaces they are used on.
 */
class DesignTokensTest {

    // The scale is the contract, not merely "increasing": a seventh value lets two rows
    // gap by 14 here and 16 there, which is what makes a screen feel assembled rather
    // than designed. Anything outside this list is a bug in the screen, not a new step.
    @Test
    fun `spacing scale is exactly four to thirty-two`() {
        val steps = listOf(
            AppSpacing.xs, AppSpacing.sm, AppSpacing.md,
            AppSpacing.lg, AppSpacing.xl, AppSpacing.xxl
        ).map { it.value }
        assertEquals(listOf(4f, 8f, 12f, 16f, 24f, 32f), steps)
        assertTrue(AppSpacing.page.value in steps)
        assertTrue(AppSpacing.sectionGap.value in steps)
    }

    @Test
    fun `corner scale is exactly six ten sixteen`() {
        val radii = listOf(AppRadius.sm, AppRadius.md, AppRadius.lg).map { it.value }
        assertEquals(listOf(6f, 10f, 16f), radii)
    }

    @Test
    fun `control heights meet the minimum touch target`() {
        assertTrue(AppDimens.controlHeight >= 48.dp)
        assertTrue(AppDimens.controlHeightCompact >= 40.dp)
    }

    @Test
    fun `containers are fully opaque`() {
        assertEquals(1.0f, FluentColors.LightSurface.alpha, 0.0f)
        assertEquals(1.0f, FluentColors.DarkSurface.alpha, 0.0f)
        assertEquals(1.0f, LogColors.Plate.alpha, 0.0f)
    }

    // The accent carries labels and values on the light background, so both theme
    // accents have to clear body-text contrast there and against their own surface.
    @Test
    fun `light accent text clears the accessibility contrast floor`() {
        assertTrue(
            "light accent on light surface",
            contrastRatio(FluentColors.LightPrimary, FluentColors.LightSurface) >= 4.5
        )
        assertTrue(
            "light accent on light background",
            contrastRatio(FluentColors.LightPrimary, FluentColors.LightBackground) >= 4.5
        )
    }

    @Test
    fun `dark accent text clears the accessibility contrast floor`() {
        assertTrue(
            "dark accent on dark surface",
            contrastRatio(FluentColors.DarkPrimary, FluentColors.DarkSurface) >= 4.5
        )
        assertTrue(
            "dark accent on dark background",
            contrastRatio(FluentColors.DarkPrimary, FluentColors.DarkBackground) >= 4.5
        )
    }

    @Test
    fun `body text clears the contrast floor in both themes`() {
        assertTrue(contrastRatio(FluentColors.LightOnBackground, FluentColors.LightBackground) >= 4.5)
        assertTrue(contrastRatio(FluentColors.DarkOnBackground, FluentColors.DarkBackground) >= 4.5)
    }

    @Test
    fun `log severity colours stay legible on the log plate`() {
        listOf(
            LogColors.Default, LogColors.Muted, LogColors.Error, LogColors.Success,
            LogColors.Warning, LogColors.Info, LogColors.Trace, LogColors.Debug
        ).forEach { colour ->
            assertTrue(
                "log colour $colour on plate",
                contrastRatio(colour, LogColors.Plate) >= 3.0
            )
        }
    }

    @Test
    fun `semi transparent alphas stay within range`() {
        listOf(
            AppAlpha.hairline, AppAlpha.subtle, AppAlpha.tint, AppAlpha.strong,
            AppAlpha.onSurfaceSecondary, AppAlpha.onSurfaceTertiary, AppAlpha.disabled
        ).forEach { alpha ->
            assertTrue("alpha $alpha in (0,1)", alpha > 0f && alpha < 1f)
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(c: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
