package com.tavern.app.console.theme

import androidx.compose.ui.unit.dp

// Single source of truth for spacing, corner radii, control sizes and alpha
// levels. Screens must consume these instead of literal dp values so the whole
// console keeps one rhythm.

object AppSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    // Horizontal page gutter. Matches the rows' own horizontal padding, so a
    // section label and the row text under it sit on the same vertical line.
    val page = 16.dp
    val sectionGap = 20.dp
}

object AppRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

object AppDimens {
    val topBarHeight = 56.dp
    // Bottom navigation container: the M3 default is 80dp, which is more
    // vertical space than this four-tab bar needs.
    val navBarHeight = 64.dp
    val controlHeight = 48.dp
    val controlHeightCompact = 40.dp
    // Dense button height. The tap area still reaches controlHeight through the
    // minimum touch target Material applies, so this only shrinks what is drawn.
    val controlHeightDense = 32.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val iconXl = 28.dp
    // Brand mark as drawn inside the app. It is the primary visual of the startup
    // and about screens rather than a row glyph, so it does not belong to the icon
    // ladder above. The compact size carries the wordmark beside it on the about
    // page and stands alone on the resume screen: a plate that shares its line with
    // text reads as oversized once the wordmark next to it is display sized.
    val brandMark = 72.dp
    val brandMarkCompact = 56.dp
    // Circular tinted plate behind a row's leading icon.
    val iconBadge = 44.dp
    val iconBadgeGlyph = 22.dp
    val statusDot = 8.dp
    val hairline = 1.dp
    // Progress bars: one weight for bars that sit inside content, one hairline
    // for the full-width bar that must stay quieter than everything around it.
    val progressBar = 6.dp
    val progressHairline = 2.dp
}

// Border and container alphas. Keep this list short; screens pick the nearest
// step rather than inventing a new value.
object AppAlpha {
    const val hairline = 0.08f
    const val iconBadge = 0.10f
    const val subtle = 0.12f
    const val tint = 0.16f
    const val strong = 0.28f
    const val onSurfaceSecondary = 0.70f
    const val onSurfaceTertiary = 0.55f
    const val disabled = 0.38f
}
