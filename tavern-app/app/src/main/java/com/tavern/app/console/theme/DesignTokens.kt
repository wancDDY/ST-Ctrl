package com.tavern.app.console.theme

import androidx.compose.ui.unit.dp

// Single source of truth for spacing, corner radii, control sizes and alpha
// levels. Screens must consume these instead of literal dp values so the whole
// console keeps one rhythm.

/**
 * The spacing scale is the six steps 4 / 8 / 12 / 16 / 24 / 32, and nothing else.
 *
 * A seventh value is always a mistake in practice: it lets two rows gap by 14 in one
 * place and 16 in another, which reads as sloppy long before anyone can name why. When
 * a layout seems to need an in-between value, the alignment is what needs fixing.
 */
object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    // Horizontal page gutter. Matches the rows' own horizontal padding, so a
    // section label and the row text under it sit on the same vertical line.
    val page = 16.dp
    val sectionGap = 24.dp
}

/**
 * Three corner radii: 6 for small parts, 10 for controls, 16 for surfaces.
 *
 * A nested corner follows the outer one: outer radius minus the padding between them,
 * otherwise the inner corner looks tighter than the frame it sits in.
 */
object AppRadius {
    val sm = 6.dp
    val md = 10.dp
    val lg = 16.dp

    /** Fully rounded, for chips and round plates; not a step on the scale above. */
    val pill = 999.dp
}

object AppDimens {
    val topBarHeight = 56.dp
    // Two row heights, one line and two. The height is fixed here instead of coming
    // from the row's own padding: padding per case made a one-line row taller than a
    // two-line one, so the shorter entry sat in the taller strip.
    val rowSingle = 56.dp
    val rowDouble = 72.dp
    // Bottom navigation container: the M3 default is 80dp, which is more
    // vertical space than this four-tab bar needs.
    val navBarHeight = 64.dp
    val controlHeight = 48.dp
    val controlHeightCompact = 40.dp
    // Dense button height. The tap area still reaches controlHeight through the
    // minimum touch target Material applies, so this only shrinks what is drawn.
    val controlHeightDense = 32.dp
    val iconSm = 16.dp
    // A glyph that shares a line with body text. The vector inside a 20dp box draws at
    // roughly 15dp, so a row set at 14sp carries a mark slightly smaller than its first
    // line — which is what keeps the text, not the icon, as the thing being read.
    val iconInline = 18.dp
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
