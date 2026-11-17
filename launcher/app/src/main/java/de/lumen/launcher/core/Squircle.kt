package de.lumen.launcher.core

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.min

/**
 * Continuous-curvature shapes ("squircles").
 *
 * Icons use a superellipse (|x|^n + |y|^n = 1, n = 5), which closely matches the smooth
 * icon outline known from iOS. Cards and panels use [SmoothRoundedShape], a rounded rectangle
 * whose corners are built from superellipse arcs so the curvature has no visible "kink"
 * where straight edge and arc meet.
 */
object Squircle {
    const val EXPONENT = 5.0
    private const val STEPS = 96

    /** Superellipse filling the given square/rectangle (android.graphics version for bitmaps). */
    fun androidPath(w: Float, h: Float, n: Double = EXPONENT): android.graphics.Path {
        val p = android.graphics.Path()
        val a = w / 2f
        val b = h / 2f
        for (i in 0..STEPS * 4) {
            val t = 2 * Math.PI * i / (STEPS * 4)
            val c = cos(t)
            val s = sin(t)
            val x = a + a * (sign(c) * abs(c).pow(2.0 / n)).toFloat()
            val y = b + b * (sign(s) * abs(s).pow(2.0 / n)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    fun composePath(w: Float, h: Float, n: Double = EXPONENT): Path {
        val p = Path()
        val a = w / 2f
        val b = h / 2f
        for (i in 0..STEPS * 4) {
            val t = 2 * Math.PI * i / (STEPS * 4)
            val c = cos(t)
            val s = sin(t)
            val x = a + a * (sign(c) * abs(c).pow(2.0 / n)).toFloat()
            val y = b + b * (sign(s) * abs(s).pow(2.0 / n)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }
}

/** Superellipse shape for app icons. */
object SquircleShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Squircle.composePath(size.width, size.height))
}

/**
 * Rounded rectangle with continuous ("smooth") corners.
 * Each corner is a quarter superellipse of radius r * 1.28, which visually matches a
 * circular corner of radius r but blends smoothly into the straight edges.
 */
class SmoothRoundedShape(private val radius: androidx.compose.ui.unit.Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() } * 1.28f
        val rr = min(r, min(size.width, size.height) / 2f)
        return Outline.Generic(smoothRectPath(size.width, size.height, rr))
    }

    override fun equals(other: Any?) = other is SmoothRoundedShape && other.radius == radius
    override fun hashCode() = radius.hashCode()
}

fun smoothRectPath(w: Float, h: Float, r: Float, n: Double = 4.0): Path {
    val p = Path()
    if (r <= 0f) {
        p.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)); return p
    }
    val steps = 24
    // corner centers
    val corners = listOf(
        Triple(w - r, r, 0),      // top-right: angles -90..0
        Triple(w - r, h - r, 1),  // bottom-right: 0..90
        Triple(r, h - r, 2),      // bottom-left: 90..180
        Triple(r, r, 3),          // top-left: 180..270
    )
    var first = true
    for ((cx, cy, q) in corners) {
        for (i in 0..steps) {
            val t = Math.toRadians(-90.0 + q * 90.0 + 90.0 * i / steps)
            val c = cos(t)
            val s = sin(t)
            val x = cx + r * (sign(c) * abs(c).pow(2.0 / n)).toFloat()
            val y = cy + r * (sign(s) * abs(s).pow(2.0 / n)).toFloat()
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
    }
    p.close()
    return p
}
