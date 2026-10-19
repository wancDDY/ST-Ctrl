package com.tavern.app.console.theme

import androidx.compose.ui.graphics.toArgb
import com.tavern.app.console.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The access dialog is a platform dialog over a native WebView, so it is painted
 * from resolvePalette with no composition running. Both modes therefore have to
 * carry the token values for every role the dialog paints, and the two modes have
 * to actually differ — a dialog that falls back to one fixed set is the fault
 * this covers.
 */
class AppPaletteTest {

    @Test
    fun `dark mode carries the token values the dialog paints with`() {
        val palette = ViewPalette.paletteFor(dark = true)
        assertEquals(FluentColors.DarkSurface.toArgb(), palette.surface)
        assertEquals(FluentColors.DarkOnSurface.toArgb(), palette.onSurface)
        assertEquals(FluentColors.DarkOnSurfaceVariant.toArgb(), palette.onSurfaceVariant)
        assertEquals(FluentColors.DarkPrimary.toArgb(), palette.accent)
    }

    @Test
    fun `light mode carries the token values the dialog paints with`() {
        val palette = ViewPalette.paletteFor(dark = false)
        assertEquals(FluentColors.LightSurface.toArgb(), palette.surface)
        assertEquals(FluentColors.LightOnSurface.toArgb(), palette.onSurface)
        assertEquals(FluentColors.LightOnSurfaceVariant.toArgb(), palette.onSurfaceVariant)
        assertEquals(FluentColors.LightPrimary.toArgb(), palette.accent)
    }

    @Test
    fun `the two modes differ in every role the dialog paints with`() {
        val dark = ViewPalette.paletteFor(dark = true)
        val light = ViewPalette.paletteFor(dark = false)
        assertNotEquals(dark.surface, light.surface)
        assertNotEquals(dark.onSurface, light.onSurface)
        assertNotEquals(dark.onSurfaceVariant, light.onSurfaceVariant)
        assertNotEquals(dark.accent, light.accent)
    }

    @Test
    fun `resolvePalette builds both schemes from the tokens`() {
        val (darkScheme, darkColors) = resolvePalette(dark = true)
        assertEquals(FluentColors.DarkSurface, darkScheme.surface)
        assertEquals(FluentColors.DarkOnSurface, darkScheme.onSurface)
        assertEquals(FluentColors.DarkOnSurfaceVariant, darkScheme.onSurfaceVariant)
        assertEquals(FluentColors.DarkPrimary, darkColors.accent)

        val (lightScheme, lightColors) = resolvePalette(dark = false)
        assertEquals(FluentColors.LightSurface, lightScheme.surface)
        assertEquals(FluentColors.LightOnSurface, lightScheme.onSurface)
        assertEquals(FluentColors.LightOnSurfaceVariant, lightScheme.onSurfaceVariant)
        assertEquals(FluentColors.LightPrimary, lightColors.accent)
    }

    // The night flags are spelled out because the unit-test android.jar carries no
    // values for framework fields: NIGHT_MASK 0x30, NIGHT_YES 0x20, NIGHT_NO 0x10.
    @Test
    fun `an unset theme setting follows the system night mode`() {
        assertTrue(ThemeState.isNightMode(0x20))
        assertFalse(ThemeState.isNightMode(0x10))
    }
}
