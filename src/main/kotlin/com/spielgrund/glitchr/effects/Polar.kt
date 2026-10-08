package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Polar coordinates: bends the picture into a circle (its width runs around the center,
 * its height from the center outwards) or unrolls a circle back into a strip. Little
 * planet uses a stereographic projection, so the ground becomes a small globe and the
 * sky reaches out to the corners; the tunnel puts the picture's height into depth, an
 * endless tube towards the center. The picture can run around several times, twist into
 * a spiral and be mirrored so the seam where its ends meet disappears.
 */
object Polar : Effect("polar", "Polar coordinates", "Bends the picture into a circle, a little planet or a tunnel – or unrolls a circle") {
    private val modes = listOf("Rectangular → polar", "Polar → rectangular", "Little planet", "Tunnel")
    private const val TO_POLAR = 0
    private const val TO_RECT = 1
    private const val PLANET = 2
    private const val TUNNEL = 3

    override val params = listOf(
        Param.Choice(
            "mode", "Type", modes,
            tip = "Rectangular → polar: the width runs around the center, the top lands in the middle · " +
                "Polar → rectangular: unrolls a circle around the center into a strip · " +
                "Little planet: the bottom becomes a small globe, the top reaches out to the corners · " +
                "Tunnel: an endless tube, the picture repeats into the depth",
        ),
        Param.Slider("radius", "Radius", 5, 400, 100, " %", "Size of the circle, planet or tunnel opening (100 % = half the shorter side)"),
        Param.Toggle("invert", "Flip inside/out", false, "Swaps which edge of the picture lies in the middle"),
        Param.Slider("rotation", "Rotation", 0, 359, 0, "°"),
        Param.Slider("repeat", "Repeat", 1, 16, 1, tip = "How often the picture runs around the circle"),
        Param.Slider("twist", "Twist", -1440, 1440, 0, "°", "Turns the outside against the middle into a spiral (per radius)"),
        Param.Slider("fade", "Depth fade", 0, 100, 50, " %", "Tunnel: darkens towards the far end, where the rings get too fine to see"),
        Param.Toggle("seamless", "Hide seam", false, "Runs the picture forth and mirrored back, so its left and right ends meet without a seam"),
        Param.Slider("centerX", "Center X", -2000, 2000, 0, " px", "Pixels from the middle of the canvas – can also be dragged with the handle in the picture"),
        Param.Slider("centerY", "Center Y", -2000, 2000, 0, " px"),
        Param.Choice("edge", "Edge", Edge.labels, default = Edge.WRAP.ordinal, tip = "What appears beyond the picture's top and bottom"),
        Param.Choice(
            "antialias", "Anti-aliasing", listOf("Off", "2 × 2", "4 × 4"), 1,
            "Samples per pixel – the middle of a circle and the far end of a tunnel squeeze whole rows into a few pixels",
        ),
    )

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val mode = v["mode"]
        val cx = w / 2.0 + v["centerX"]
        val cy = h / 2.0 + v["centerY"]
        val radius = max(1.0, min(w, h) / 2.0 * v["radius"] / 100.0)
        val invert = v.bool("invert")
        val rotation = v["rotation"] * PI / 180
        val repeat = v["repeat"].toDouble()
        val twist = v["twist"] * PI / 180
        val seamless = v.bool("seamless")
        val edge = Edge.entries[v["edge"]]
        val fade = if (mode == TUNNEL) v["fade"] / 100.0 * 3 else 0.0

        /** Horizontal position in the picture for a share [u] of the way around (one turn = 0..1). */
        fun across(u: Double): Double {
            var s = u * repeat
            s -= floor(s)
            if (seamless) s = 1 - abs(2 * s - 1)
            return s * w
        }

        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val c = IntArray(sub * sub)
            for (x in 0 until w) {
                for (s in c.indices) {
                    val px = x + (s % sub + 0.5) / sub
                    val py = y + (s / sub + 0.5) / sub
                    c[s] = if (mode == TO_RECT) {
                        // the strip's width runs around the circle, its height from the middle outwards
                        val t = py / h
                        val r = (if (invert) 1 - t else t) * radius
                        val a = px / w * 2 * PI - PI / 2 + rotation + twist * r / radius
                        sampleBilinear(src, cx + r * cos(a), cy + r * sin(a), edge)
                    } else {
                        val dx = px - cx
                        val dy = py - cy
                        val r = hypot(dx, dy)
                        // angle measured clockwise from the top, so the picture's left edge starts at twelve o'clock
                        var u = (atan2(dy, dx) + PI / 2 - rotation - twist * r / radius) / (2 * PI)
                        u -= floor(u)
                        // share of the way from the middle outwards: 0 = top of the picture
                        val t = when (mode) {
                            // stereographic: the planet's surface at radius, infinity at the far corners
                            PLANET -> 1 - 2 / PI * atan(r / radius)
                            TUNNEL -> radius / max(r, 1e-6)
                            else -> r / radius
                        }
                        sampleBilinear(src, across(u), (if (invert) 1 - t else t) * h, edge)
                    }
                }
                var color = averageArgb(c)
                if (fade > 0) {
                    val r = hypot(x + 0.5 - cx, y + 0.5 - cy)
                    color = darken(color, min(1.0, r / radius).pow(fade))
                }
                out.data[y * w + x] = color
            }
        }
        return out
    }
}

/** [c] with its color multiplied by [f] (0..1), transparency kept. */
private fun darken(c: Int, f: Double): Int {
    fun ch(s: Int) = ((c shr s and 0xFF) * f + 0.5).toInt() shl s
    return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
}

/** The mean of ARGB colors, weighted by their opacity so transparent samples don't darken. */
internal fun averageArgb(c: IntArray): Int {
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (p in c) {
        val pa = p ushr 24
        a += pa
        r += (p shr 16 and 0xFF) * pa
        g += (p shr 8 and 0xFF) * pa
        b += (p and 0xFF) * pa
    }
    if (a == 0) return 0
    return ((a + c.size / 2) / c.size shl 24) or ((r + a / 2) / a shl 16) or ((g + a / 2) / a shl 8) or ((b + a / 2) / a)
}
