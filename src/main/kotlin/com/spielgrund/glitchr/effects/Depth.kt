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
        Param.Heading("depthHeading", "Tiefe (geschätzt)"),
        Param.Slider("depthSharp", "Schärfe", -100, 100, 60, " %", "Scharfe, detailreiche Stellen sind nah (Fotos mit Unschärfe im Hintergrund)"),
        Param.Slider("depthLow", "Unten ist nah", -100, 100, 40, " %", "Wie ein Boden: was weiter unten liegt, ist näher"),
        Param.Slider("depthLight", "Hell ist nah", -100, 100, 0, " %", "Negativ: dunkel ist nah"),
        Param.Slider("depthSat", "Farbig ist nah", -100, 100, 20, " %", "Ferne Dinge sind blasser (Dunst)"),
        Param.Slider("depthCenter", "Mitte ist nah", -100, 100, 20, " %", "Das Motiv in der Mitte ist nah"),
        Param.Slider("depthSmooth", "Glätten", 1, 300, 24, " px", "Wie weit die Tiefe geglättet wird – sie folgt dabei den Kanten im Bild"),
        Param.Slider("depthEdges", "Kantentreue", 0, 100, 60, " %", "Wie genau die Tiefe den Kanten im Bild folgt"),
        Param.Slider("depthContrast", "Tiefenkontrast", 10, 400, 100, " %", "Mehr: die Tiefe trennt deutlicher zwischen nah und fern"),
        Param.Toggle("depthInvert", "Tiefe umkehren", false),
        Param.Toggle("depthShow", "Z-Map zeigen", false, "Zeigt die geschätzte Tiefe: weiss ist nah, schwarz ist fern"),
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
object ZMap : Effect("zmap", "Z-Map", "Schätzt aus dem Bild eine Tiefenkarte: weiss ist nah, schwarz ist fern") {
    override val params = Depth.params.filter { it.key != "depthShow" }
    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels = Depth.show(src, Depth.estimate(src, v))
}
