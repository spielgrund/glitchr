package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Sphere transform: looking up into a dome (and down into a bowl). The horizon line stays straight
 * and in place; above it every upright line of the picture runs to one point, the zenith, straight
 * above the pole's X – like the ribs of a dome seen from below. The more the top is drawn in, the
 * nearer the zenith comes: at 100 % it sits on the top edge and the picture's top edge has drawn
 * together into it. Below the horizon the same towards the nadir; each half only changes its own
 * side. The pole can also pull the picture towards itself into long streaks.
 *
 * For every output pixel: the ray from the pole through it gives the picture's column (where that
 * ray meets the horizon); its height above the horizon stays – unless the pull draws it from lower down.
 */
object SphereTransform : Effect("spheretransform", "Sphere transform", "Looking up into a dome: above the straight horizon the picture's lines run together into the zenith, below into the nadir – and the pole can pull the sky up into long streaks") {
    private val beyonds = listOf("Transparent", "Stretch edge", "Mirror", "Repeat")

    override val params = listOf(
        Param.Slider("horizonY", "Horizon", -10000, 10000, 0, " %", "Where the horizon lies in the picture, from the middle: −100 % = the top edge – it stays straight. The handle drags it", decimals = 2),
        Param.Slider("centerX", "Pole X", -10000, 10000, 0, " %", "Where the zenith and nadir lie across, from the middle – the handle drags it", decimals = 2),
        Param.Slider("top", "Top into the zenith", 0, 100, 0, " %", "Above the horizon the picture's lines run together into the zenith: the more, the nearer it comes – 100 % = on the top edge, the picture's top edge drawn together into it"),
        Param.Slider("bottom", "Bottom into the nadir", 0, 100, 0, " %", "The same below the horizon, towards the nadir"),
        Param.Slider("pullTop", "Pull into the zenith", 0, 100, 0, " %", "The zenith draws the picture up to itself: what lies just above the horizon is stretched up into long streaks"),
        Param.Slider("pullBottom", "Pull into the nadir", 0, 100, 0, " %", "The same downwards: the ground is pulled down into streaks towards the nadir"),
        Param.Slider("bend", "Pull curve", 20, 500, 100, " %", "How strongly the pull grows towards the pole: more = longer streaks from lower down"),
        Param.Choice("beyond", "Beyond the edges", beyonds, 2, "What comes in from beyond the picture's sides where its lines are drawn together"),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "3 × 3"), 1),
    )

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "horizonY", percent = true)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val top = v["top"] / 100.0
        val bottom = v["bottom"] / 100.0
        val pullTop = v["pullTop"] / 100.0
        val pullBottom = v["pullBottom"] / 100.0
        if (top == 0.0 && bottom == 0.0 && pullTop == 0.0 && pullBottom == 0.0) return src.copy()
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val hy = h / 2.0 * (1 + v["horizonY"] / 10000.0)
        val above = hy.coerceAtLeast(1.0)
        val below = (h - hy).coerceAtLeast(1.0)
        val bend = v["bend"] / 100.0
        val edge = when (v["beyond"]) { 1 -> Edge.CLAMP; 2 -> Edge.MIRROR; 3 -> Edge.WRAP; else -> null }
        // how far the poles lie from the horizon: far away (lines upright) down to right on the picture's edge
        val zenith = if (top > 0) above / top else Double.POSITIVE_INFINITY
        val nadir = if (bottom > 0) below / bottom else Double.POSITIVE_INFINITY
        val gammaTop = 1 + (3 * pullTop + 9 * pullTop * pullTop) * bend
        val gammaBottom = 1 + (3 * pullBottom + 9 * pullBottom * pullBottom) * bend

        // every row's average color: near the pole a row is squeezed into a few pixels, so it fades to that
        // instead of flickering with countless copies
        val means = Array(h) { y ->
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
            for (x in 0 until w) { val c = src.data[y * w + x]; val ca = (c ushr 24).toDouble(); a += ca; r += (c shr 16 and 0xFF) * ca; g += (c shr 8 and 0xFF) * ca; b += (c and 0xFF) * ca }
            if (a <= 0) doubleArrayOf(0.0, 0.0, 0.0, 0.0) else doubleArrayOf(a / w, r / a, g / a, b / a)
        }

        /** The picture's color seen at output point ([x], [y]). */
        fun look(x: Double, y: Double): Int {
            val up = y < hy
            val pole = if (up) zenith else nadir
            val half = if (up) above else below
            val dy = abs(y - hy)
            // the ray from the pole through here meets the horizon at sx: the picture's column
            val toPole = pole - dy
            if (toPole <= 1e-9) return sampleBilinear(src, cx, if (up) 0.0 else h.toDouble(), Edge.CLAMP)
            val sx = if (pole.isInfinite()) x else cx + (x - cx) * pole / toPole
            // the height stays – the pull takes it from nearer the horizon, stretched up to the picture's edge
            val g = if (up) gammaTop else gammaBottom
            val off = if (dy < half) half * (dy / half).pow(g) else dy
            val sy = if (up) hy - off else hy + off
            val c = if (edge == null) (if (sx < 0 || sx >= w || sy < 0 || sy >= h) 0 else sampleBilinear(src, sx, sy, Edge.CLAMP))
            else sampleBilinear(src, sx, sy.coerceIn(0.0, h.toDouble()), edge)
            // squeezed hard near the pole: towards the row's average
            val squeeze = if (pole.isInfinite()) 1.0 else pole / toPole
            if (squeeze < 3 || edge == null) return c
            val k = ((squeeze - 3) / 9).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            val m = means[sy.toInt().coerceIn(0, h - 1)]
            fun mix(sh: Int, p: Double) = ((c shr sh and 0xFF) * (1 - k) + p * k).roundToInt().coerceIn(0, 255)
            return (mix(24, m[0]) shl 24) or (mix(16, m[1]) shl 16) or (mix(8, m[2]) shl 8) or mix(0, m[3])
        }

        val ss = v["antialias"].coerceIn(0, 2) + 1
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                for (sy in 0 until ss) for (sx in 0 until ss) {
                    val c = look(x + (sx + 0.5) / ss, y + (sy + 0.5) / ss)
                    val ca = (c ushr 24).toDouble()
                    a += ca; r += (c shr 16 and 0xFF) * ca; g += (c shr 8 and 0xFF) * ca; b += (c and 0xFF) * ca
                }
                out.data[y * w + x] = if (a <= 0) 0 else
                    ((a / (ss * ss)).roundToInt() shl 24) or ((r / a).roundToInt() shl 16) or ((g / a).roundToInt() shl 8) or (b / a).roundToInt()
            }
        }
        return out
    }
}
