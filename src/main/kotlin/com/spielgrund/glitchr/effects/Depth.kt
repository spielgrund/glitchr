package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A depth map guessed from the picture alone (no learned model): several cues, each
 * turned into "how near" 0..1 and weighted – sharpness (what is in focus is near), the
 * position (lower is nearer, like the ground), brightness, saturation (distant things are
 * hazy and grey) and the middle (the subject). The sum is smoothed edge-aware with a
 * guided filter along the picture's own edges, so the depth jumps where the picture does
 * and is calm in between. 1 = near, 0 = far.
 */
internal object Depth {
    val params = listOf(
        Param.Heading("depthHeading", "Depth (estimated)"),
        Param.Slider("depthSharp", "Sharpness", -100, 100, 60, " %", "Sharp, detailed areas are near (photos with a blurred background)"),
        Param.Slider("depthLow", "Low is near", -100, 100, 40, " %", "Like a floor: what lies further down is nearer"),
        Param.Slider("depthLight", "Bright is near", -100, 100, 0, " %", "Negative: dark is near"),
        Param.Slider("depthSat", "Colorful is near", -100, 100, 20, " %", "Distant things are paler (haze)"),
        Param.Slider("depthCenter", "Middle is near", -100, 100, 20, " %", "The subject in the middle is near"),
        Param.Slider("depthSmooth", "Smooth", 1, 300, 24, " px", "How far the depth is smoothed – it follows the edges in the picture"),
        Param.Slider("depthEdges", "Edge fidelity", 0, 100, 60, " %", "How closely the depth follows the edges in the picture"),
        Param.Slider("depthContrast", "Depth contrast", 10, 400, 100, " %", "More: the depth separates near and far more clearly"),
        Param.Toggle("depthInvert", "Invert depth", false),
        Param.Toggle("depthShow", "Show Z-map", false, "Shows the estimated depth: white is near, black is far"),
    )

    /** How near every pixel is, 0..1. */
    fun estimate(src: Pixels, v: Values): FloatArray {
        val w = src.width
        val h = src.height
        val n = w * h
        val luma = FloatArray(n) { val c = src.data[it]; ((0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)) / 255).toFloat() }
        val sum = FloatArray(n)
        fun add(weight: Int, cue: () -> FloatArray) {
            if (weight == 0) return
            val c = normalize(cue())
            val k = weight / 100f
            for (i in 0 until n) sum[i] += k * c[i]
        }
        val size = max(w, h)
        add(v["depthSharp"]) {
            // local contrast: the gradient, gathered over a few percent of the picture
            val g = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                fun at(xx: Int, yy: Int) = luma[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
                g[y * w + x] = hypot((at(x + 1, y) - at(x - 1, y)).toDouble(), (at(x, y + 1) - at(x, y - 1)).toDouble()).toFloat()
            }
            Grow.blur(g, w, h, max(2, size / 60))
        }
        add(v["depthLow"]) { FloatArray(n) { (it / w + 0.5f) / h } }
        add(v["depthLight"]) { luma }
        add(v["depthSat"]) {
            val s = FloatArray(n) {
                val c = src.data[it]
                val mx = max(red(c), max(green(c), blue(c)))
                val mn = min(red(c), min(green(c), blue(c)))
                if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            }
            Grow.blur(s, w, h, max(2, size / 80))
        }
        add(v["depthCenter"]) {
            FloatArray(n) {
                val dx = ((it % w + 0.5) / w - 0.5) * 2
                val dy = ((it / w + 0.5) / h - 0.5) * 2
                (1 - min(1.0, hypot(dx, dy) / 1.2)).toFloat()
            }
        }
        var depth = normalize(sum)
        // edge-aware smoothing along the picture
        depth = guided(luma, depth, w, h, max(1, (v["depthSmooth"] / 2.0).roundToInt()), v["depthEdges"] / 100f)
        depth = normalize(depth)
        val gamma = 100.0 / v["depthContrast"]
        val invert = v.bool("depthInvert")
        for (i in 0 until n) {
            // contrast around the middle
            val d = depth[i].toDouble()
            val c = if (d < 0.5) 0.5 * (2 * d).pow(gamma) else 1 - 0.5 * (2 * (1 - d)).pow(gamma)
            depth[i] = (if (invert) 1 - c else c).toFloat()
        }
        return depth
    }

    /** The depth as a grey picture (white = near), keeping the picture's alpha. */
    fun show(src: Pixels, depth: FloatArray) = Pixels(src.width, src.height, IntArray(depth.size) {
        val g = (depth[it] * 255).roundToInt().coerceIn(0, 255)
        argb(alpha(src.data[it]), g, g, g)
    })

    /** Stretched to 0..1 between its 1st and 99th percentile. */
    private fun normalize(a: FloatArray): FloatArray {
        val sorted = a.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.01).toInt()]
        val hi = sorted[min(sorted.size - 1, (sorted.size * 0.99).toInt())]
        val span = hi - lo
        return FloatArray(a.size) { if (span < 1e-6f) 0.5f else ((a[it] - lo) / span).coerceIn(0f, 1f) }
    }

    /**
     * Guided filter (He et al.): [p] smoothed over [r], but following the edges of the
     * guide [g]; [edges] 0..1 how closely (small epsilon = close).
     */
    private fun guided(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, edges: Float): FloatArray {
        val n = g.size
        val eps = (0.2f * (1 - edges)).pow(2) + 1e-4f
        fun blur(a: FloatArray) = Grow.blur(a, w, h, r)
        val mg = blur(g)
        val mp = blur(p)
        val mgp = blur(FloatArray(n) { g[it] * p[it] })
        val mgg = blur(FloatArray(n) { g[it] * g[it] })
        val a = FloatArray(n) { (mgp[it] - mg[it] * mp[it]) / (mgg[it] - mg[it] * mg[it] + eps) }
        val b = FloatArray(n) { mp[it] - a[it] * mg[it] }
        val ma = blur(a)
        val mb = blur(b)
        return FloatArray(n) { ma[it] * g[it] + mb[it] }
    }
}

/** Shows the guessed depth as a grey picture: a Z-map, white near, black far. */
object ZMap : Effect("zmap", "Z-Map", "Estimates a depth map from the picture: white is near, black is far") {
    override val params = Depth.params.filter { it.key != "depthShow" }
    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels = Depth.show(src, Depth.estimate(src, v))
}
