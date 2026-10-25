package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** What both keys share: the key (how much of each pixel stays, 0..1) made softer at its edge, shown or applied. */
private object Matte {
    val params = listOf(
        Param.Slider("edgeBlur", "Edge blur", 0, 2000, 0, " px", "Softens the key's edge", decimals = 2),
        Param.Slider("choke", "Choke", -5000, 5000, 0, " px", "Positive: the kept part shrinks by that much · negative: it grows", decimals = 2),
        Param.Toggle("invertKey", "Invert", false, "Keeps what would be keyed out, and keys out the rest"),
        Param.Toggle("matte", "Show matte", false, "Shows the key itself: white stays, black is keyed out"),
    )

    fun apply(src: Pixels, keep: FloatArray, v: Values): Pixels {
        val w = src.width
        val h = src.height
        if (v.bool("invertKey")) for (i in keep.indices) keep[i] = 1 - keep[i]
        // choke: blurred, then cut off above or below the middle – the edge moves in (or out) by about that far
        val choke = v["choke"] / 100.0
        var shaped = keep
        if (abs(choke) >= 0.5) {
            val b = Blur.gauss(keep, w, h, abs(choke) * 1.5)
            val t = 0.5f + (if (choke > 0) 0.248f else -0.248f)
            shaped = FloatArray(b.size) { smooth(t - 0.08f, t + 0.08f, b[it]) }
        }
        val blurred = Blur.gauss(shaped, w, h, v["edgeBlur"] / 100.0 / 2)
        val out = Pixels(w, h)
        val show = v.bool("matte")
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val k = blurred[i]
                val c = src.data[i]
                if (show) {
                    val g = (k * alpha(c) + 0.5f).toInt().coerceIn(0, 255)
                    out.data[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                } else {
                    out.data[i] = ((alpha(c) * k + 0.5f).toInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
                }
            }
        }
        return out
    }

    fun smooth(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x >= e0) 1f else 0f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}

/** Luma key: the dark (or bright) parts of the picture become transparent, softly from a threshold on. */
object LumaKey : Effect("lumakey", "Luma key", "Makes the dark or bright parts of the picture transparent") {
    override val params = listOf(
        Param.Choice("keyOut", "Key out", listOf("Dark", "Bright"), 0, "Which parts become transparent"),
        Param.Slider("threshold", "Threshold", 0, 100, 25, " %", "Brightness where it turns"),
        Param.Slider("softness", "Softness", 0, 100, 10, " %", "How gradually: 0 = a hard cut"),
    ) + Matte.params

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val t = v["threshold"] / 100f
        val soft = v["softness"] / 100f
        val bright = v["keyOut"] == 1
        val keep = FloatArray(src.data.size) {
            val l = luma(src.data[it]) / 255f
            val dark = Matte.smooth(t - soft / 2, t + soft / 2, l)
            if (bright) 1 - dark else dark
        }
        return Matte.apply(src, keep, v)
    }
}

/** Color key: a color (a green screen, say) becomes transparent, near ones softly; its spill on the rest is taken out. */
object ColorKey : Effect("colorkey", "Color key", "Makes one color transparent, like a green screen – with tolerance, softness and spill suppression") {
    override val params = listOf(
        Param.Color("keyColor", "Key color", 0x00FF00),
        Param.Slider("tolerance", "Tolerance", 0, 100, 25, " %", "How far a color may be from the key color and still go"),
        Param.Slider("softness", "Softness", 0, 100, 10, " %", "How gradually: 0 = a hard cut"),
        Param.Slider("lumaWeight", "Brightness weight", 0, 100, 30, " %", "How much brightness counts: low = shadows and highlights of the key color go too"),
        Param.Slider("spill", "Spill suppression", 0, 100, 50, " %", "Takes the key color's tint out of what stays (green fringes on hair)"),
    ) + Matte.params

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val key = v["keyColor"]
        val tol = v["tolerance"] / 100f
        val soft = v["softness"] / 100f
        val lw = v["lumaWeight"] / 100f
        val spill = v["spill"] / 100f
        // colors as brightness and two color differences (YCbCr), so brightness can count less
        fun ycc(c: Int, out: FloatArray) {
            val r = red(c).toFloat(); val g = green(c).toFloat(); val b = blue(c).toFloat()
            out[0] = 0.299f * r + 0.587f * g + 0.114f * b
            out[1] = -0.1687f * r - 0.3313f * g + 0.5f * b
            out[2] = 0.5f * r - 0.4187f * g - 0.0813f * b
        }
        val k = FloatArray(3).also { ycc(key, it) }
        val kr = red(key); val kg = green(key); val kb = blue(key)
        // the key's strongest channel against the others: what spill is
        val keyChroma = sqrt(k[1] * k[1] + k[2] * k[2]).coerceAtLeast(1f)
        val keep = FloatArray(src.data.size)
        val cleaned = Pixels(src.width, src.height)
        parallelRows(src.height) { y ->
            val p = FloatArray(3)
            for (x in 0 until src.width) {
                val i = y * src.width + x
                val c = src.data[i]
                ycc(c, p)
                val dy = (p[0] - k[0]) * lw
                val d = sqrt(dy * dy + (p[1] - k[1]) * (p[1] - k[1]) + (p[2] - k[2]) * (p[2] - k[2])) / 255f
                keep[i] = Matte.smooth(tol * 0.6f, tol * 0.6f + soft * 0.6f + 1e-4f, d)
                var out = c
                if (spill > 0) {
                    // how much of the pixel's tint points towards the key's: that much becomes grey
                    val along = (p[1] * k[1] + p[2] * k[2]) / keyChroma
                    val s = (along / keyChroma).coerceIn(0f, 1f) * spill
                    if (s > 0) {
                        val r = red(c); val g = green(c); val b = blue(c)
                        // the key's channels pulled down to the others (green to the mean of red and blue, say)
                        fun pull(ch: Int, keyCh: Int, o1: Int, o2: Int): Int {
                            val strong = keyCh >= max(max(kr, kg), kb) - 8
                            return if (strong) (ch - (ch - max(o1, o2).coerceAtMost(ch)) * s).toInt() else ch
                        }
                        out = (c and 0xFF000000.toInt()) or (pull(r, kr, g, b) shl 16) or (pull(g, kg, r, b) shl 8) or pull(b, kb, r, g)
                    }
                }
                cleaned.data[i] = out
            }
        }
        return Matte.apply(cleaned, keep, v)
    }
}

/**
 * Find key: keeps what a simple picture analysis finds (see [AutoSelect]) – the subject, the
 * background, the sky or the near parts – and keys out the rest. Every kind has its own
 * settings, shown while it is chosen; the sky's are stored under their own names. The analysis
 * is kept while only the matte settings change.
 */
object FindKey : Effect("findkey", "Find key", "Keeps the subject, the background, the sky or the near parts found by picture analysis – the rest becomes transparent") {
    private val kinds = AutoSelect.Kind.entries
    private val cache = LastResult<FloatArray>()

    /** The setting's name here: the sky's settings share names with the subject's, so they get their own. */
    private fun keyFor(kind: AutoSelect.Kind, key: String) = if (kind == AutoSelect.Kind.SKY) "sky" + key.replaceFirstChar { it.uppercase() } else key

    override val params: List<Param> = listOf(
        Param.Choice("find", "Find", kinds.map { it.label }, 0, "What stays: found by simple analysis of the picture – its settings below steer it"),
    ) + listOf(AutoSelect.Kind.SUBJECT, AutoSelect.Kind.SKY, AutoSelect.Kind.DEPTH).flatMap { kind ->
        // subject and background share their settings
        val shown = if (kind == AutoSelect.Kind.SUBJECT) intArrayOf(0, 1) else intArrayOf(kinds.indexOf(kind))
        AutoSelect.params(kind).mapNotNull { p ->
            val key = keyFor(kind, p.key)
            when (p) {
                is Param.Slider -> Param.Slider(key, p.label, p.min, p.max, p.default, p.unit, p.tip, decimals = p.decimals)
                is Param.Toggle -> Param.Toggle(key, p.label, p.default != 0, p.tip)
                else -> null
            }?.shownWhen("find", *shown)
        }
    } + Matte.params

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val kind = kinds[v["find"].coerceIn(0, kinds.size - 1)]
        val settings = AutoSelect.params(kind).filter { it is Param.Slider || it is Param.Toggle }.associate { it.key to v[keyFor(kind, it.key)] }
        val found = cache.get(listOf(src.data.contentHashCode(), src.width, src.height, kind, settings)) { AutoSelect.select(src, kind, settings) }
        return Matte.apply(src, found.copyOf(), v)
    }
}
