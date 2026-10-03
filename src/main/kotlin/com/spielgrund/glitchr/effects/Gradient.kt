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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * A color gradient over the whole canvas: linear, mirrored, radial, conic (around the
 * center), diamond or square. It can repeat (optionally mirrored), be bent by a curve,
 * be cut into hard steps and pass through a middle color. Dithering hides the banding
 * of smooth 8-bit gradients. Only used as a generator; the input only gives the size.
 */
object Gradient : Effect("gradient", "Gradient", "Color gradient: linear, mirrored, radial, angle, diamond or square") {
    private val types = listOf("Linear", "Linear mirrored", "Radial", "Angle", "Diamond", "Square")

    override val params = listOf(
        Param.Choice("type", "Type", types),
        Param.Color("color1", "Color 1", 0x000000),
        Param.Color("color2", "Color 2", 0xFFFFFF),
        Param.Toggle("useMid", "Middle color", false, "The gradient runs through a third color in the middle"),
        Param.Color("mid", "Middle color", 0xFF4FA3),
        Param.Slider("midPos", "Position of the middle color", 1, 99, 50, " %"),
        Param.Slider("angle", "Angle", 0, 359, 90, "°", "Direction of the gradient; for angle: where it starts"),
        Param.Slider("centerX", "Center X", -50, 150, 50, " %"),
        Param.Slider("centerY", "Center Y", -50, 150, 50, " %"),
        Param.Slider("size", "Size", 5, 400, 100, " %", "100 %: the gradient spans the whole canvas"),
        Param.Slider("repeats", "Repeats", 1, 64, 1),
        Param.Toggle("mirror", "Mirror repeats", true, "Every second repeat runs back instead of starting over hard"),
        Param.Slider("curve", "Distribution", 10, 1000, 100, " %", "Below 100 % more of color 2, above more of color 1"),
        Param.Slider("steps", "Steps", 0, 64, 0, tip = "Hard color steps instead of a smooth gradient; 0 = stepless"),
        Param.Toggle("dither", "Dithering", true, "Fine noise against visible banding in the gradient"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val type = v["type"]
        val a = v["angle"] * PI / 180
        val ux = cos(a)
        val uy = sin(a)
        val cx = w * v["centerX"] / 100.0
        val cy = h * v["centerY"] / 100.0
        val size = v["size"] / 100.0
        // extents at 100 %: linear spans the canvas along the direction, the round shapes reach its corners
        val along = max(1.0, abs(w * ux) + abs(h * uy)) * size
        val radius = max(1.0, hypot(w.toDouble(), h.toDouble()) / 2) * size
        val repeats = v["repeats"]
        val mirror = v.bool("mirror")
        val curve = v["curve"] / 100.0
        val steps = v["steps"]
        val dither = v.bool("dither")
        val c1 = v["color1"]
        val c2 = v["color2"]
        val mid = if (v.bool("useMid")) v["mid"] else null
        val midPos = v["midPos"] / 100.0

        /** Position 0..1 in the gradient at canvas point ([x], [y]). */
        fun position(x: Double, y: Double): Double {
            val dx = x - cx
            val dy = y - cy
            val u = dx * ux + dy * uy
            val n = -dx * uy + dy * ux
            val t = when (type) {
                1 -> abs(u) / (along / 2)
                2 -> hypot(dx, dy) / radius
                3 -> (atan2(n, u) / (2 * PI)).let { it - floor(it) }
                4 -> (abs(u) + abs(n)) / (radius * 1.2)
                5 -> max(abs(u), abs(n)) / (radius * 0.85)
                else -> u / along + 0.5
            }
            // repeat: plain (a hard jump each time) or mirrored (back and forth)
            val r = t * repeats
            val p = when {
                repeats == 1 && type != 3 -> r
                mirror -> (r - 2 * floor(r / 2)).let { if (it > 1) 2 - it else it }
                else -> r - floor(r)
            }
            var q = p.coerceIn(0.0, 1.0).pow(curve)
            if (steps > 0) q = (floor(q * steps).coerceAtMost(steps - 1.0)) / max(1, steps - 1)
            return q
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val t = position(x + 0.5, y + 0.5)
                val (from, to, f) = when {
                    mid == null -> Triple(c1, c2, t)
                    t < midPos -> Triple(c1, mid, t / midPos)
                    else -> Triple(mid, c2, (t - midPos) / (1 - midPos))
                }
                // dithering: a fixed per-pixel offset of up to half a color step each way
                val d = if (dither && steps == 0) hash(x, y) - 0.5 else 0.0
                fun ch(a: Int, b: Int) = (a + (b - a) * f + d + 0.5).toInt().coerceIn(0, 255)
                out.data[y * w + x] = argb(255, ch(red(from), red(to)), ch(green(from), green(to)), ch(blue(from), blue(to)))
            }
        }
        return out
    }

    /** Fixed pseudo-random value 0..1 of a pixel. */
    private fun hash(x: Int, y: Int): Double {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }
}
