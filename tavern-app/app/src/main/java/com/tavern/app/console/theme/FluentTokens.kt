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
import androidx.compose.ui.unit.dp
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

    // The action colour: a filled install/download button is the one control in the
    // console that is meant to be noticed, and the theme accent is the brand teal that
    // lands on switches, tabs and progress bars — using it here would make an action
    // read as another piece of chrome. Blue is the colour the platform's own
    // installers use for the same action, so it needs no explaining.
    val LightAction = Color(0xFF1565C0)
    val LightOnAction = Color(0xFFFFFFFF)
    // Read as a label rather than as a block, so it has to clear body-text contrast
    // on the near-black card it sits on.
    val DarkAction = Color(0xFF8AB4F8)
    val DarkOnAction = Color(0xFFFFFFFF)
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
    val errorContainer: Color
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

val FluentTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
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

// Win11 corner spec: windows 8dp / controls 4dp

val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp)
)