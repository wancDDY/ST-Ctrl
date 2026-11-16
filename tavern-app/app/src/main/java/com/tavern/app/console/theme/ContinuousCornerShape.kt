package com.tavern.app.console.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A rounded rectangle whose corners change curvature gradually instead of switching from a
 * straight edge to a circle at one point.
 *
 * A quarter circle turns at a constant rate and then stops; the eye reads that stop as a corner
 * however large the radius is. The shape drawn here is a superellipse — the corner starts flat,
 * bends hardest in the middle, and flattens out again where it meets the edge — which is what
 * makes a corner read as soft rather than as a circle pushed into a box.
 *
 * Sampled as short segments rather than Bézier curves: at the radii this app uses, fourteen
 * points per corner land within a fraction of a pixel of the curve, and a polygon is one code
 * path with no control-point bookkeeping to get wrong.
 */
class ContinuousCornerShape(
    private val radius: Dp,
    /**
     * Superellipse exponent: 2 is an ellipse (a circle for a square corner), 4.6 is close to the
     * squircle iOS draws. Higher is boxier, lower is rounder.
     */
    private val exponent: Float = 4.6f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        if (r <= 0.5f) {
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }
        return Outline.Generic(path(size.width, size.height, r, exponent))
    }

    private fun path(w: Float, h: Float, r: Float, n: Float): Path {
        val path = Path()
        val power = 2f / n
        fun corner(quadrant: Int): List<Offset> = (0..STEPS).map { step ->
            val t = (PI / 2) * step / STEPS
            val s = sin(t).toFloat().pow(power)
            val c = cos(t).toFloat().pow(power)
            when (quadrant) {
                0 -> Offset(w - r + r * s, r - r * c)
                1 -> Offset(w - r + r * c, h - r + r * s)
                2 -> Offset(r - r * s, h - r + r * c)
                else -> Offset(r - r * c, r - r * s)
            }
        }
        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        for (q in 0..3) {
            corner(q).forEachIndexed { index, point ->
                if (index > 0) path.lineTo(point.x, point.y)
            }
            when (q) {
                0 -> path.lineTo(w, h - r)
                1 -> path.lineTo(r, h)
                2 -> path.lineTo(0f, r)
                else -> path.close()
            }
        }
        return path
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousCornerShape && other.radius == radius && other.exponent == exponent

    override fun hashCode(): Int = 31 * radius.hashCode() + exponent.hashCode()

    private companion object {
        const val STEPS = 14
    }
}
