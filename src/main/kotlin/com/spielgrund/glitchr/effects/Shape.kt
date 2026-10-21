package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One geometric shape – line, triangle, polygon, star, circle or ring – filled with a
 * color or a gradient and optionally outlined. Every shape is a signed distance field
 * (negative inside), which gives clean antialiased edges, soft edges, rounded corners,
 * outlines and the "from the edge" gradient from the same numbers. Only used as a
 * generator: the input is the base color the shape lies on (or transparency).
 */
object Shape : Effect("shape", "Shape", "Line, triangle, polygon, star, circle or ring, filled with a color or gradient") {
    private val shapes = listOf("Line", "Triangle", "Polygon", "Star", "Circle", "Ring")
    private val fills = listOf("Color", "Gradient", "None")
    private val gradientTypes = listOf("Linear", "Radial", "Angle", "From the edge")

    override val params = listOf(
        Param.Choice("shape", "Shape", shapes, 3),
        Param.Slider("size", "Size", 100, 300000, 25000, " px", "Radius; for a line: half its length", canvasMax = true, decimals = 2, transform = true),
        Param.Slider("stretch", "Stretch", 10, 1000, 100, " %", "Width against height; 100 % = regular"),
        Param.Slider("rotation", "Rotation", -18000, 18000, 0, "°", decimals = 2),
        Param.Slider("centerX", "Center X", -20000, 20000, 0, " %", "From the middle of the canvas, in canvas widths", decimals = 2, transform = true),
        Param.Slider("centerY", "Center Y", -20000, 20000, 0, " %", "From the middle of the canvas, in canvas heights", decimals = 2, transform = true),
        Param.Slider("sides", "Corners", 3, 64, 6, tip = "Corners of the polygon, points of the star"),
        Param.Slider("inner", "Star depth", 1, 99, 45, " %", "Inner radius of the star against the outer one"),
        Param.Slider("thickness", "Thickness", 1, 3000, 40, " px", "Line width; ring width", canvasMax = true),
        Param.Toggle("roundCaps", "Round line ends", true),
        Param.Slider("rounding", "Rounding", 0, 1000, 0, " px", "Rounds the corners; the shape grows by that much"),
        Param.Slider("softness", "Softness", 0, 500, 0, " px", "Soft edge"),

        Param.Heading("fillHeading", "Fill"),
        Param.Choice("fill", "Fill", fills, 1),
        Param.Color("color", "Color", 0xFF4FA3),
        Param.Choice("gradientType", "Gradient", gradientTypes, tip = "Linear, around the center, around like a clock, or inwards from the edge"),
        Param.Ramp("ramp", "Gradient colors", RampPalette.SUNSET.ramp.format()),
        Param.Slider("gradientAngle", "Gradient angle", 0, 35900, 9000, "°", "Turns with the shape", decimals = 2),
        Param.Slider("gradientRepeats", "Gradient repeats", 1, 32, 1),
        Param.Toggle("gradientMirror", "Mirror repeats", true),

        Param.Heading("outlineHeading", "Outline"),
        Param.Slider("outline", "Outline", 0, 500, 0, " px", "Width of the outline, centered on the edge; 0 = none"),
        Param.Color("outlineColor", "Outline color", 0xFFFFFF),

        Param.Heading("backgroundHeading", "Background"),
        Param.Toggle("transparent", "Transparent background", false, "Only the shape; otherwise it lies on the base color"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val shape = v["shape"]
        val r = max(0.5, (v["size"] / 100.0).toDouble())
        val stretch = v["stretch"] / 100.0
        val sx = if (stretch >= 1) stretch else 1.0
        val sy = if (stretch >= 1) 1.0 else 1 / stretch
        val rot = Math.toRadians(v["rotation"] / 100.0)
        val cosR = cos(rot)
        val sinR = sin(rot)
        val cx = w / 2.0 + v["centerX"] / 10000.0 * w
        val cy = h / 2.0 + v["centerY"] / 10000.0 * h
        val thickness = v["thickness"].toDouble()
        val rounding = v["rounding"].toDouble()
        val soft = v["softness"].toDouble()
        val outline = v["outline"].toDouble()
        val fill = v["fill"]
        val gradientType = v["gradientType"]
        val ramp = ColorRamp.parse(v.text("ramp")).lut
        val ga = Math.toRadians(v["gradientAngle"] / 100.0)
        val gux = cos(ga)
        val guy = sin(ga)
        val repeats = v["gradientRepeats"]
        val mirror = v.bool("gradientMirror")
        val color = v["color"]
        val outlineColor = v["outlineColor"]
        val transparent = v.bool("transparent")

        // corners for the polygon shapes, already stretched; pointing up
        val corners: DoubleArray? = when (shape) {
            1, 2 -> {
                val n = if (shape == 1) 3 else v["sides"]
                DoubleArray(2 * n) { k ->
                    val a = -PI / 2 + 2 * PI * (k / 2) / n
                    if (k % 2 == 0) cos(a) * r * sx else sin(a) * r * sy
                }
            }
            3 -> {
                val n = v["sides"] * 2
                val inner = v["inner"] / 100.0
                DoubleArray(2 * n) { k ->
                    val i = k / 2
                    val a = -PI / 2 + 2 * PI * i / n
                    val rr = if (i % 2 == 0) r else r * inner
                    if (k % 2 == 0) cos(a) * rr * sx else sin(a) * rr * sy
                }
            }
            else -> null
        }?.also { c ->
            // centered on their bounds, not on the circle around them: a triangle (or any odd
            // number of corners) otherwise sits too low, with its tip far above the center
            val xs = c.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = c.filterIndexed { i, _ -> i % 2 == 1 }
            val mx = (xs.min() + xs.max()) / 2
            val my = (ys.min() + ys.max()) / 2
            for (i in c.indices) c[i] -= if (i % 2 == 0) mx else my
        }
        val roundCaps = v.bool("roundCaps")
        // the circle and ring are ellipses when stretched: measured in the unstretched frame, scaled back
        val ellipseScale = min(sx, sy)

        /** Signed distance from the shape's edge at shape-local ([x], [y]). */
        fun distance(x: Double, y: Double): Double {
            val d = when (shape) {
                0 -> {
                    // along x, half length r
                    if (roundCaps) hypot(max(abs(x) - r, 0.0), y) - thickness / 2
                    else box(x, y, r, thickness / 2)
                }
                4 -> (hypot(x / sx, y / sy) - r) * ellipseScale
                5 -> (abs(hypot(x / sx, y / sy) - (r - thickness / 2)) - thickness / 2) * ellipseScale
                else -> polygon(corners!!, x, y)
            }
            return d - rounding
        }

        // reach of the shape from its center, for the bounding box and the gradients
        val reach = when (shape) {
            0 -> r + thickness / 2
            1, 2, 3 -> (0 until corners!!.size / 2).maxOf { hypot(corners[2 * it], corners[2 * it + 1]) }
            else -> r * max(sx, sy)
        } + rounding
        val margin = reach + outline / 2 + soft + 2
        val x0 = max(0, floor(cx - margin).toInt())
        val x1 = min(w, ceil(cx + margin).toInt())
        val y0 = max(0, floor(cy - margin).toInt())
        val y1 = min(h, ceil(cy + margin).toInt())

        // the deepest point inside, for the gradient from the edge
        val depth = if (fill == 1 && gradientType == 3) {
            var deepest = 1.0
            val step = max(1.0, reach / 200)
            var yy = -reach
            while (yy <= reach) {
                var xx = -reach
                while (xx <= reach) {
                    deepest = max(deepest, -distance(xx, yy))
                    xx += step
                }
                yy += step
            }
            deepest
        } else 1.0

        fun gradientAt(x: Double, y: Double, d: Double): Int {
            val t = when (gradientType) {
                1 -> hypot(x, y) / reach
                2 -> (atan2(x * guy - y * gux, x * gux + y * guy) / (2 * PI) + 0.5)
                3 -> -d / depth
                else -> (x * gux + y * guy) / (2 * reach) + 0.5
            }.coerceIn(0.0, 1.0)
            val rt = t * repeats
            val p = when {
                repeats == 1 -> rt
                mirror -> (rt - 2 * floor(rt / 2)).let { if (it > 1) 2 - it else it }
                else -> if (rt >= repeats) 1.0 else rt - floor(rt)
            }
            return ramp[(p * 255 + 0.5).toInt().coerceIn(0, 255)]
        }

        // coverage of an edge at distance d (negative inside): one pixel of antialiasing plus the softness
        val feather = 1.0 + soft
        fun cover(d: Double) = (0.5 - d / feather).coerceIn(0.0, 1.0)

        val out = if (transparent) Pixels(w, h) else src.copy()
        parallelRows(y1 - y0) { row ->
            val py = y0 + row
            for (px in x0 until x1) {
                // into the shape's frame: around its center, turned back
                val dx = px + 0.5 - cx
                val dy = py + 0.5 - cy
                val lx = dx * cosR + dy * sinR
                val ly = -dx * sinR + dy * cosR
                val d = distance(lx, ly)
                if (d - outline / 2 > feather) continue
                // fill, then the outline over it (as straight colors with coverage)
                var a = 0.0
                var cr = 0.0
                var cg = 0.0
                var cb = 0.0
                fun over(rgb: Int, alpha: Double) {
                    if (alpha <= 0) return
                    val na = alpha + a * (1 - alpha)
                    cr = (red(rgb) * alpha + cr * a * (1 - alpha)) / na
                    cg = (green(rgb) * alpha + cg * a * (1 - alpha)) / na
                    cb = (blue(rgb) * alpha + cb * a * (1 - alpha)) / na
                    a = na
                }
                if (fill != 2) over(if (fill == 0) color else gradientAt(lx, ly, d), cover(d))
                if (outline > 0) over(outlineColor, cover(abs(d) - outline / 2))
                if (a <= 0) continue
                val i = py * w + px
                val under = out.data[i]
                val ua = (under ushr 24) / 255.0
                val oa = a + ua * (1 - a)
                fun mix(c: Double, u: Int) = ((c * a + u * ua * (1 - a)) / oa + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = argb((oa * 255 + 0.5).toInt(), mix(cr, red(under)), mix(cg, green(under)), mix(cb, blue(under)))
            }
        }
        return out
    }

    /** Signed distance to a box with half sizes [hx], [hy] around the origin. */
    private fun box(x: Double, y: Double, hx: Double, hy: Double): Double {
        val qx = abs(x) - hx
        val qy = abs(y) - hy
        return hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
    }

    /** Signed distance to the closed polygon [c] (x, y pairs), negative inside (after Inigo Quilez). */
    private fun polygon(c: DoubleArray, x: Double, y: Double): Double {
        val n = c.size / 2
        var d = (x - c[0]) * (x - c[0]) + (y - c[1]) * (y - c[1])
        var s = 1.0
        var j = n - 1
        for (i in 0 until n) {
            val vix = c[2 * i]
            val viy = c[2 * i + 1]
            val ex = c[2 * j] - vix
            val ey = c[2 * j + 1] - viy
            val wx = x - vix
            val wy = y - viy
            val t = ((wx * ex + wy * ey) / (ex * ex + ey * ey)).coerceIn(0.0, 1.0)
            val bx = wx - ex * t
            val by = wy - ey * t
            d = min(d, bx * bx + by * by)
            val c1 = y >= viy
            val c2 = y < c[2 * j + 1]
            val c3 = ex * wy > ey * wx
            if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
            j = i
        }
        return s * sqrt(d)
    }
}
