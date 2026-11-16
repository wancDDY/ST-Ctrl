package com.tavern.app.console.theme

import androidx.compose.ui.graphics.Shape

/**
 * The two corner shapes the console uses above the smallest step.
 *
 * Continuous curvature throughout: a quarter circle turns at a fixed rate and then stops, and the
 * eye reads that stop as a corner. These are instances rather than per-call constructors so a
 * screenful of cards shares one shape and one path.
 */
val AppCardShape: Shape = ContinuousCornerShape(AppRadius.md)

val AppPanelShape: Shape = ContinuousCornerShape(AppRadius.lg)
