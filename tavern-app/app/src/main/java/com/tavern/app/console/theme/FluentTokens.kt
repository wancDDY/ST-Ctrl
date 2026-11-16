package com.tavern.app.console.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tavern.app.R

// Win11 Fluent palette. "Approximate Mica": solid layers, no translucency blur.

object FluentColors {
    // Light. Accent is a darker teal than the dark-theme accent so accent text and
    // glyphs clear 4.5:1 on the light background; the bright teal of the dark theme
    // is unreadable there.
    val LightBackground = Color(0xFFF3F3F3)
    val LightSurface = Color(0xFFFFFFFF)
    // The surface-container family. Left unset, Material falls back to its own baseline palette,
    // which is lavender-tinted: every menu, dialog and sheet drawn by a Material component came out
    // a different colour from the cards around it.
    val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
    val LightSurfaceContainerLow = Color(0xFFFAFAFA)
    val LightSurfaceContainer = Color(0xFFF5F5F5)
    val LightSurfaceContainerHigh = Color(0xFFEFEFEF)
    val LightSurfaceContainerHighest = Color(0xFFE9E9E9)
    val LightSurfaceVariant = Color(0xFFEBEBEB)
    val LightOnBackground = Color(0xFF1A1A1A)
    val LightOnSurface = Color(0xFF1A1A1A)
    val LightOnSurfaceVariant = Color(0xFF5F5F5F)
    val LightPrimary = Color(0xFF0F766E)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFD5EAE7)
    val LightOnPrimaryContainer = Color(0xFF04302C)
    val LightSecondary = Color(0xFF5F5F5F)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightSecondaryContainer = Color(0xFFEBEBEB)
    val LightOnSecondaryContainer = Color(0xFF1A1A1A)
    val LightError = Color(0xFFC42B1C)
    val LightOnError = Color(0xFFFFFFFF)
    val LightErrorContainer = Color(0xFFF9D4D1)
    val LightOnErrorContainer = Color(0xFF4C0A04)
    val LightOutline = Color(0x29000000)
    val LightOutlineVariant = Color(0x0D000000)

    // Dark: obsidian black with a teal accent. Pure black carries the layers the way
    // the platform's own dark themes do — background at #000, surface one step up so
    // cards still separate, and the variant one step above that for tags and plates.
    // The accent is the icon's teal used as a *fill* with black on it (switches, the
    // active segment, progress bars, the brand mark); accent *text* only ever sits on
    // these near-black surfaces, where it clears 10:1. Success sits 45 degrees
    // towards green and warning is orange, so neither can be read as the accent now
    // that the accent itself is cool.
    val DarkBackground = Color(0xFF000000)
    val DarkSurface = Color(0xFF121212)
    val DarkSurfaceContainerLowest = Color(0xFF1A1A1A)
    val DarkSurfaceContainerLow = Color(0xFF202020)
    val DarkSurfaceContainer = Color(0xFF262626)
    val DarkSurfaceContainerHigh = Color(0xFF2E2E2E)
    val DarkSurfaceContainerHighest = Color(0xFF383838)
    val DarkSurfaceVariant = Color(0xFF242424)
    val DarkOnBackground = Color(0xFFF2F2F2)
    val DarkOnSurface = Color(0xFFF2F2F2)
    val DarkOnSurfaceVariant = Color(0xFFA8A8A8)
    val DarkPrimary = Color(0xFF2DD4BF)
    val DarkOnPrimary = Color(0xFF121212)
    val DarkPrimaryContainer = Color(0xFF04302C)
    val DarkOnPrimaryContainer = Color(0xFF99F6E4)
    val DarkSecondary = Color(0xFFA8A8A8)
    val DarkOnSecondary = Color(0xFF333333)
    val DarkSecondaryContainer = Color(0xFF242424)
    val DarkOnSecondaryContainer = Color(0xFFF2F2F2)
    val DarkError = Color(0xFFFF6B6B)
    val DarkOnError = Color(0xFF4A1414)
    val DarkErrorContainer = Color(0xFF3A1212)
    val DarkOnErrorContainer = Color(0xFFF5D5D5)
    val DarkOutline = Color(0x29FFFFFF)
    val DarkOutlineVariant = Color(0x0DFFFFFF)

    // Semantic colors (no M3 slot, consumed via LocalAppColors). The *Container
    // values are the low-alpha pill backgrounds paired with the matching text.
    //
    // Both themes carry their own set: the dark values were picked against #1C1C1C
    // and went flat once the background became pure black, which is the same reason
    // the platform's own apps keep a values-night file instead of reusing one set.
    // Warning is the warm end of the scale, which the cool accent leaves free.
    val LightSuccess = Color(0xFF1B7A32)
    val LightSuccessContainer = Color(0xFFDCF0E0)
    val LightWarning = Color(0xFFA8480A)
    val LightWarningContainer = Color(0xFFFBE6D4)
    val DarkSuccess = Color(0xFF5DD16B)
    val DarkSuccessContainer = Color(0xFF10301A)
    val DarkWarning = Color(0xFFFFA733)
    val DarkWarningContainer = Color(0xFF3A2606)

    // One accent, not two. A separate blue for filled actions, held apart so they would
    // not read as chrome, makes the app carry a teal accent and a blue accent at the same
    // time, and the eye reads that as two products stitched together long before it can
    // say why. The accent carries filled actions too, and emphasis comes from the fill and
    // the size rather than from a second hue.
    val LightAction = LightPrimary
    val LightOnAction = LightOnPrimary
    // Read as a label rather than as a block, so it has to clear body-text contrast
    // on the near-black card it sits on.
    val DarkAction = DarkPrimary
    val DarkOnAction = DarkOnPrimary
}

// Console log palette. The log pane keeps a dark plate in both themes so that
// server output stays legible and the severity colours stay distinguishable;
// each value is tuned for contrast against LogPlate rather than against the
// page background. Severities sit at a lower saturation than a default terminal
// scheme so six of them side by side still read as one scale.

object LogColors {
    val Plate = Color(0xFF16161A)
    val Default = Color(0xFFD4D4D8)
    val Muted = Color(0xFF8A8A93)
    val Error = Color(0xFFDD8F88)
    val Success = Color(0xFF8FBF8F)
    val Warning = Color(0xFFD6BE7E)
    val Info = Color(0xFF86B8D8)
    val Trace = Color(0xFFBBA0CC)
    val Debug = Color(0xFF7FB8B0)
}

// Semantic tokens: supplementary colors consumed by components. Containers are
// kept alongside their foreground so a pill never guesses its own background.

data class AppColors(
    val accent: Color,
    val action: Color,
    val onAction: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    /**
     * The plate under a selected chip, a chosen option, or a running banner.
     *
     * One wash for every "this one is active" surface. At full strength the accent reads as a
     * warning block and competes with the filled buttons that are actually meant to be pressed,
     * and three different alphas in three screens made one state look like three different
     * things. Text on the wash stays [accent]; the fill is what changes with the state.
     */
    val accentWash: Color = accent.copy(alpha = 0.14f)
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(
        accent = Color(0xFF5EEAD4),
        action = Color(0xFF8AB4F8),
        onAction = Color(0xFFFFFFFF),
        success = Color(0xFF7FB37A),
        successContainer = Color(0xFF23331F),
        warning = Color(0xFFE0A458),
        warningContainer = Color(0xFF3A2E18),
        error = Color(0xFFFF6B6B),
        errorContainer = Color(0xFF3A1212)
    )
}

// Fluent motion spec

val FluentEasing: Easing = CubicBezierEasing(0.2f, 0.6f, 0.2f, 1f)
// Exit/collapse. The previous fast-out curve (0.9, 0.1, 1, 0.2) covered most of the distance in
// the first few frames, so a short block read as the content snapping away rather than folding.
val FluentEasingOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
// Long moves: gentle decel, avoids slamming.
val FluentEasingMove: Easing = CubicBezierEasing(0.33f, 0f, 0.1f, 1f)

data class FluentMotionSpec(
    val quickMs: Int = 150,
    val normalMs: Int = 250,
    // Opening a block is read, not glanced at: the reader watches where the new content came
    // from. At the control's own duration it is over before the eye arrives.
    val expandMs: Int = 340,
    val pageMs: Int = 320,
    val easing: Easing = FluentEasing,
    val easingOut: Easing = FluentEasingOut,
    val easingMove: Easing = FluentEasingMove
)

val LocalFluentMotion = staticCompositionLocalOf { FluentMotionSpec() }

// Win11 type scale (Segoe UI Variable weight mapping). Screens read these via
// MaterialTheme.typography instead of literal fontSize values.
//
// The app name on the about page is the one display element in the console, so the
// scale carries a step above the headings for it.

/**
 * One line height per size, at roughly 1.5x, which is where Chinese body text stops
 * looking cramped.
 *
 * These are the defaults rather than something each call site raises: the same size
 * used to carry four different heights depending on who wrote the screen (11sp text
 * appeared with 15, 16, 17 and 18), and two similar paragraphs in one card then set
 * different rhythms. A screen that wants a looser block should say so once in a shared
 * style, not per Text.
 */
/**
 * One style factory for the whole scale, on the platform's own typeface.
 *
 * The system face is what the phone sets for itself — a bundled Chinese font costs ten megabytes
 * and looks the same on the devices that already ship something similar. Text keeps a 1.5x line
 * height so Chinese paragraphs breathe; headings take a tighter ratio, because at display sizes 1.5
 * pulls a two-line title apart.
 *
 * Two weights only: regular for text, demibold for headings and for the few labels that have to
 * stand apart. A third weight reads as an accident rather than as emphasis.
 *
 * Tabular figures are on everywhere — every count, floor number and percentage in this app sits in a
 * column, and proportional digits make that column jitter as the values change.
 */
private fun appText(size: Int, weight: FontWeight, ratio: Float): TextStyle = TextStyle(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * ratio).sp,
    fontFeatureSettings = "tnum",
)

val FluentTypography = Typography(
    headlineLarge = appText(32, FontWeight.SemiBold, 1.25f),
    headlineMedium = appText(26, FontWeight.SemiBold, 1.25f),
    headlineSmall = appText(22, FontWeight.SemiBold, 1.3f),
    titleLarge = appText(18, FontWeight.SemiBold, 1.35f),
    titleMedium = appText(16, FontWeight.SemiBold, 1.4f),
    titleSmall = appText(14, FontWeight.SemiBold, 1.45f),
    bodyLarge = appText(14, FontWeight.Normal, 1.5f),
    bodyMedium = appText(13, FontWeight.Normal, 1.5f),
    bodySmall = appText(12, FontWeight.Normal, 1.5f),
    labelLarge = appText(14, FontWeight.SemiBold, 1.45f),
    labelMedium = appText(12, FontWeight.SemiBold, 1.35f),
    labelSmall = appText(11, FontWeight.Normal, 1.45f)
)

// Brand wordmark face. The app name is set in Rajdhani on every surface that draws
// it, while the rest of the UI keeps the system face: a display font that also ran
// the body copy would read as decoration, and the name alone is what has to be
// recognisable. Its squared, straight-sided forms sit with the mark's plate where
// the previous rounded face read as a second, unrelated lettering. The face ships
// with the app under the SIL Open Font License (attribution in assets/NOTICE); only
// the Bold weight is packaged because the wordmark is never set at another one.
object BrandTypography {
    val family: FontFamily = FontFamily(Font(R.font.rajdhani_bold, FontWeight.Bold))

    // Tighter than the UI face. The wordmark is short, and at display sizes the
    // default tracking pulls its glyph groups apart instead of setting them as one
    // unit. Rajdhani already sets its pairs close, so the value carries over
    // unchanged from the face it replaced.
    private val tracking = (-0.02).em

    // Wordmark styles are taken from the scale above rather than declared here, so
    // the mark keeps the rhythm of the screen it sits on.
    fun wordmark(base: TextStyle): TextStyle = base.copy(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        letterSpacing = tracking
    )
}

/**
 * Face for readings taken off the running service: port, uptime, memory, core version.
 *
 * These are the only numbers in the app that are read as an instrument rather than as
 * prose, so they are the only ones set in a monospace face. A port that changes width
 * between 8000 and 8080 shifts everything beside it; a fixed advance holds the column.
 */
object AppFigures {
    val family: FontFamily = FontFamily.Monospace

    fun style(base: TextStyle): TextStyle = base.copy(
        fontFamily = family,
        fontFeatureSettings = "tnum"
    )
}

// One ladder for everything Material draws, the same three steps the console's own panels use:
// small parts, controls, surfaces. Left at a single step, every menu and dialog came out at the
// control radius while the card beside it was drawn rounder, and two panels of the same rank did
// not share an edge.
//
// These stay quarter circles. `Shapes` declares its slots as `CornerBasedShape`, and the
// continuous-corner shape is a plain `Shape` — Material's own menus and sheets cannot take it.
// The radii still match, and the two shapes are within a pixel of each other at these sizes; the
// surfaces this app draws itself use `AppCardShape` / `AppPanelShape`.
val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.sm),
    small = RoundedCornerShape(AppRadius.sm),
    medium = RoundedCornerShape(AppRadius.md),
    large = RoundedCornerShape(AppRadius.lg),
    extraLarge = RoundedCornerShape(AppRadius.lg)
)