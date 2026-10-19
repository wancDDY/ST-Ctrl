package com.tavern.app.console.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tavern.app.console.ThemeState

/**
 * App theme (Win11 Fluent is the only style).
 *
 * Exposes darkTheme; callers keep consuming MaterialTheme.colorScheme /
 * LocalAppColors / LocalFluentMotion. Backs it with the full Fluent palette
 * (accent = app teal), type scale, corner spec, motion spec.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val (scheme, appColors) = remember(darkTheme) { resolvePalette(darkTheme) }

    // The access dialog is a platform dialog over a native WebView, where no
    // composition runs and MaterialTheme cannot be read. It resolves the mode on
    // its own, so this is only the fallback for a caller without a context.
    SideEffect { ViewPalette.publish(scheme, appColors) }

    MaterialTheme(colorScheme = scheme, typography = FluentTypography, shapes = FluentShapes) {
        CompositionLocalProvider(
            LocalAppColors provides appColors,
            LocalFluentMotion provides FluentMotionSpec()
        ) {
            content()
        }
    }
}

/**
 * Turn one mode into the colours the app draws with. The single place a mode
 * becomes values, so the console and the View layers cannot drift apart.
 *
 * Free of composition on purpose: the tavern hands the window a native WebView,
 * and the access dialog raised over it is built while no composition runs.
 */
fun resolvePalette(dark: Boolean): Pair<ColorScheme, AppColors> =
    colorSchemeFor(dark) to appColorsFor(dark)

/**
 * The current theme as plain ARGB, for the View layers that cannot read
 * MaterialTheme.
 *
 * Resolved from the app's theme setting rather than from the last composition:
 * the tavern is a native WebView, where composition has stopped and a published
 * value would still be the previous mode. Publishing stays as the fallback for a
 * caller that has no context, so the colours stay defined in FluentTokens alone
 * either way.
 */
object ViewPalette {

    data class Palette(
        val surface: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val accent: Int
    )

    @Volatile
    private var published: Palette? = null

    fun publish(scheme: ColorScheme, colors: AppColors) {
        published = paletteOf(scheme, colors)
    }

    /**
     * The colours in force right now, resolved from the app's theme setting on
     * every call. A published value belongs to the mode that was last composed,
     * and over the tavern's native WebView no composition has run at all, so it
     * is only the fallback for a caller that has no context to read the setting
     * from.
     */
    fun current(context: Context? = null): Palette =
        if (context != null) paletteOf(resolvePalette(ThemeState.isDark(context)))
        else published ?: paletteOf(resolvePalette(false))

    internal fun paletteFor(dark: Boolean): Palette = paletteOf(resolvePalette(dark))

    private fun paletteOf(resolved: Pair<ColorScheme, AppColors>): Palette {
        val (scheme, colors) = resolved
        return paletteOf(scheme, colors)
    }

    private fun paletteOf(scheme: ColorScheme, colors: AppColors): Palette = Palette(
        surface = scheme.surface.toArgb(),
        onSurface = scheme.onSurface.toArgb(),
        onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
        accent = colors.accent.toArgb()
    )
}

private fun colorSchemeFor(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = FluentColors.DarkPrimary,
    onPrimary = FluentColors.DarkOnPrimary,
    primaryContainer = FluentColors.DarkPrimaryContainer,
    onPrimaryContainer = FluentColors.DarkOnPrimaryContainer,
    secondary = FluentColors.DarkSecondary,
    onSecondary = FluentColors.DarkOnSecondary,
    secondaryContainer = FluentColors.DarkSecondaryContainer,
    onSecondaryContainer = FluentColors.DarkOnSecondaryContainer,
    error = FluentColors.DarkError,
    onError = Color.White,
    errorContainer = FluentColors.DarkErrorContainer,
    onErrorContainer = FluentColors.DarkOnErrorContainer,
    background = FluentColors.DarkBackground,
    onBackground = FluentColors.DarkOnBackground,
    surface = FluentColors.DarkSurface,
    onSurface = FluentColors.DarkOnSurface,
    surfaceVariant = FluentColors.DarkSurfaceVariant,
    onSurfaceVariant = FluentColors.DarkOnSurfaceVariant,
    outline = FluentColors.DarkOutline,
    outlineVariant = FluentColors.DarkOutlineVariant
) else lightColorScheme(
    primary = FluentColors.LightPrimary,
    onPrimary = FluentColors.LightOnPrimary,
    primaryContainer = FluentColors.LightPrimaryContainer,
    onPrimaryContainer = FluentColors.LightOnPrimaryContainer,
    secondary = FluentColors.LightSecondary,
    onSecondary = FluentColors.LightOnSecondary,
    secondaryContainer = FluentColors.LightSecondaryContainer,
    onSecondaryContainer = FluentColors.LightOnSecondaryContainer,
    error = FluentColors.LightError,
    onError = Color.White,
    errorContainer = FluentColors.LightErrorContainer,
    onErrorContainer = FluentColors.LightOnErrorContainer,
    background = FluentColors.LightBackground,
    onBackground = FluentColors.LightOnBackground,
    surface = FluentColors.LightSurface,
    onSurface = FluentColors.LightOnSurface,
    surfaceVariant = FluentColors.LightSurfaceVariant,
    onSurfaceVariant = FluentColors.LightOnSurfaceVariant,
    outline = FluentColors.LightOutline,
    outlineVariant = FluentColors.LightOutlineVariant
)

private fun appColorsFor(dark: Boolean): AppColors = if (dark) AppColors(
    accent = FluentColors.DarkPrimary,
    action = FluentColors.DarkAction,
    onAction = FluentColors.DarkOnAction,
    success = FluentColors.DarkSuccess,
    successContainer = FluentColors.DarkSuccessContainer,
    warning = FluentColors.DarkWarning,
    warningContainer = FluentColors.DarkWarningContainer,
    error = FluentColors.DarkError,
    errorContainer = FluentColors.DarkErrorContainer
) else AppColors(
    accent = FluentColors.LightPrimary,
    action = FluentColors.LightAction,
    onAction = FluentColors.LightOnAction,
    success = FluentColors.LightSuccess,
    successContainer = FluentColors.LightSuccessContainer,
    warning = FluentColors.LightWarning,
    warningContainer = FluentColors.LightWarningContainer,
    error = FluentColors.LightError,
    errorContainer = FluentColors.LightErrorContainer
)
