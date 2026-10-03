package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Color correction in one pass, in this order:
 *
 * 1. Exposure and tonemapping in linear light (Reinhard, filmic ACES, Hable), highlight
 *    compression per channel (rolls the highlights off above a knee – or, negative,
 *    stretches them so they blow out and the contrast rises; the hues tip), then levels (black point, white point,
 *    gamma), contrast, highlights and shadows.
 * 2. HSL: hue and lightness in HSL; saturation like old Photoshop – every channel is
 *    pushed away from the pixel's gray, so high values oversteer into hard, glowing
 *    colors; vibrance pushes the dull colors most. Channels (saturation) and lightness
 *    beyond their range are clipped, wrap around (overflow: too bright starts again from
 *    black) or fold back (mirror).
 * 3. Curves for RGB and each channel (see [Curves]).
 *
 * Coloring through a gradient is its own effect, [Ramp].
 */
object ColorCorrect : Effect("colorcorrect", "Color correction", "Exposure, tone mapping, levels, HSL with overflow, curves") {
    private val tonemaps = listOf("Off", "Reinhard", "Filmic (ACES)", "Hable")
    private val edges = listOf("Clip", "Overflow", "Mirror")

    override val params = listOf(
        Param.Heading("toneHeading", "Exposure and tone mapping"),
        Param.Slider("exposure", "Exposure", -500, 500, 0, " EV", decimals = 2, tip = "In stops, in linear light"),
        Param.Choice("tonemap", "Tone mapping", tonemaps, tip = "Brings overexposed highlights back softly instead of clipping them"),
        Param.Slider(
            "highlightCompression", "Highlight compression", -200, 200, 0, " %",
            "Positive: the highlights above the knee are softly compressed and pulled down. " +
                "Negative: they are stretched, burn out brighter and the contrast rises sharply. Works on every channel – the hues shift",
        ),
        Param.Slider("highlightKnee", "Knee", 0, 95, 50, " %", "The highlight compression works from this brightness up"),
        Param.Slider("blackPoint", "Black point", 0, 254, 0, tip = "Levels: whatever is this dark becomes black"),
        Param.Slider("whitePoint", "White point", 1, 255, 255, tip = "Levels: whatever is this bright becomes white"),
        Param.Slider("gamma", "Gamma", 10, 1000, 100, decimals = 2, tip = "Above 1 brightens the midtones, below darkens them"),
        Param.Slider("contrast", "Contrast", 0, 400, 100, " %"),
        Param.Slider("highlights", "Highlights", -100, 100, 0, " %", "Negative brings bright areas back"),
        Param.Slider("shadows", "Shadows", -100, 100, 0, " %", "Positive brightens dark areas"),
        Param.Heading("hslHeading", "HSL"),
        Param.Slider("hue", "Hue", -180, 180, 0, "°"),
        Param.Slider(
            "saturation", "Saturation", 0, 1000, 100, " %",
            "Pushes every channel away from the pixel's grey – high values overdrive like old Photoshop",
        ),
        Param.Choice(
            "saturationEdge", "Saturation beyond", edges,
            tip = "Clip: the channels hit their limit hard (garish, burning colors). Overflow: beyond full saturation " +
                "it starts again at grey (color bands). Mirror: it runs back from full saturation towards grey",
        ),
        Param.Slider("lightness", "Brightness", -100, 100, 0, " %"),
        Param.Choice(
            "lightnessEdge", "Brightness beyond", edges,
            tip = "What happens to brightness above white or below black: clip, start over (bright jumps to dark) or mirror back",
        ),
        Param.Slider("vibrance", "Vibrance", -100, 100, 0, " %", "Saturates mainly the pale colors, spares the already strong ones"),
        Param.Heading("curvesHeading", "Curves"),
        Param.Curve("curves", "Curves", "Click sets a point, dragging moves it, right-click removes it"),
        Param.Heading("mixHeading", "Mix"),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    override val random = false

    private val toLinear = DoubleArray(256) { c ->
        val v = c / 255.0
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toScreen(l: Double): Double = when {
        l <= 0.0031308 -> 12.92 * l
        else -> 1.055 * l.pow(1 / 2.4) - 0.055
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val exposure = 2.0.pow(v["exposure"] / 100.0)
        val tonemap = v["tonemap"]
        val compression = v["highlightCompression"] / 100.0
        val knee = v["highlightKnee"] / 100.0
        val black = v["blackPoint"] / 255.0
        val white = max(black + 1 / 255.0, v["whitePoint"] / 255.0)
        val gamma = v["gamma"] / 100.0
        val contrast = v["contrast"] / 100.0
        val highlights = v["highlights"] / 100.0
        val shadows = v["shadows"] / 100.0
        val hue = v["hue"] / 360.0
        val saturation = v["saturation"] / 100.0
        val lightness = v["lightness"] / 100.0
        val vibrance = v["vibrance"] / 100.0
        val curves = Curves.luts(v.text("curves"))
        val saturationEdge = v["saturationEdge"]
        val lightnessEdge = v["lightnessEdge"]
        val amount = v["amount"] / 100.0
        val toneOn = exposure != 1.0 || tonemap != 0
        val levelsOn = black != 0.0 || white != 1.0 || gamma != 1.0
        val hslOn = hue != 0.0 || saturation != 1.0 || lightness != 0.0 || vibrance != 0.0

        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            val c3 = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                if (alpha(c) == 0) {
                    out.data[i] = c
                    continue
                }
                c3[0] = red(c) / 255.0
                c3[1] = green(c) / 255.0
                c3[2] = blue(c) / 255.0

                // 1. exposure and tonemapping in linear light
                if (toneOn) for (k in 0..2) {
                    val l = toLinear[(c3[k] * 255).roundToInt().coerceIn(0, 255)] * exposure
                    c3[k] = toScreen(tonemap(l, tonemap))
                }
                // highlight compression on each channel: the hues tip – compressed highlights
                // turn paler, stretched ones blow out channel by channel into hard colors
                if (compression != 0.0) for (k in 0..2) c3[k] = compressHighlight(c3[k], knee, compression)
                // levels
                if (levelsOn) for (k in 0..2) {
                    val t = (c3[k] - black) / (white - black)
                    c3[k] = if (t > 0) t.pow(1 / gamma) else t
                }
                // contrast around the middle
                if (contrast != 1.0) for (k in 0..2) c3[k] = (c3[k] - 0.5) * contrast + 0.5
                // highlights and shadows, weighted by the brightness
                if (highlights != 0.0 || shadows != 0.0) {
                    val l = luminance(c3).coerceIn(0.0, 1.0)
                    val lift = shadows * (1 - l) * (1 - l) * 0.5 + highlights * l * l * 0.5
                    for (k in 0..2) c3[k] += lift
                }
                // 2. HSL
                if (hslOn) hsl(c3, hue, saturation, lightness, vibrance, saturationEdge, lightnessEdge)
                // 3. curves: RGB, then each channel
                if (curves != null) for (k in 0..2) c3[k] = Curves.apply(curves[k + 1], Curves.apply(curves[0], c3[k]))
                var r = channel(c3[0])
                var g = channel(c3[1])
                var b = channel(c3[2])
                if (amount < 1.0) {
                    fun mix(o: Int, n: Int) = (o + (n - o) * amount).roundToInt()
                    r = mix(red(c), r); g = mix(green(c), g); b = mix(blue(c), b)
                }
                out.data[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Linear light through a tonemapping operator. */
    private fun tonemap(l: Double, op: Int): Double = when (op) {
        1 -> l / (1 + l)
        // ACES fitted; the fit runs slightly above 1 for very bright light, so it is capped
        2 -> min(1.0, (l * (2.51 * l + 0.03)) / (l * (2.43 * l + 0.59) + 0.14))
        3 -> {
            fun hable(x: Double) = ((x * (0.15 * x + 0.05) + 0.004) / (x * (0.15 * x + 0.5) + 0.06)) - 0.02 / 0.3
            hable(l * 2) / hable(11.2)
        }
        else -> l
    }

    private fun channel(value: Double) = (value * 255).roundToInt().coerceIn(0, 255)

    private fun luminance(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    /** A value meant to lie in 0..1: clipped, wrapped around (overflow) or folded back (mirror). */
    private fun edge(v: Double, mode: Int): Double = when (mode) {
        1 -> if (v in 0.0..1.0) v else v - floor(v)
        2 -> (v - 2 * floor(v / 2)).let { if (it > 1) 2 - it else it }
        else -> v.coerceIn(0.0, 1.0)
    }

    /**
     * Brightness [l] above [knee] rolled off ([amount] > 0: the more, the lower white ends
     * up) or stretched ([amount] < 0: rising ever faster, far beyond white).
     */
    internal fun compressHighlight(l: Double, knee: Double, amount: Double): Double {
        if (l <= knee) return l
        val u = (l - knee) / (1 - knee)
        val strength = abs(amount) * 4
        return if (amount > 0) knee + (l - knee) / (1 + strength * u)
        else knee + (l - knee) * (1 + strength * u)
    }

    /**
     * HSL changes on [c] in place: hue and lightness in HSL (lightness beyond its range
     * through [edge]); then the saturation, Photoshop style, by pushing each channel away
     * from the pixel's gray – channels beyond 0..1 through [edge] as well.
     */
    private fun hsl(c: DoubleArray, dh: Double, sat: Double, dl: Double, vibrance: Double, satEdge: Int, lightEdge: Int) {
        val r = c[0].coerceIn(0.0, 1.0)
        val g = c[1].coerceIn(0.0, 1.0)
        val b = c[2].coerceIn(0.0, 1.0)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        var h = 0.0
        var s = 0.0
        if (d > 1e-9) {
            s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
        }
        h = (h + dh).let { it - floor(it) }
        // vibrance: the less saturated, the more it is pushed
        val push = sat * (1 + vibrance * (1 - s))
        val ll = edge(l + dl, lightEdge)
        val q = if (ll < 0.5) ll * (1 + s) else ll + s - ll * s
        val p = 2 * ll - q
        fun channel(t0: Double): Double {
            val t = t0 - floor(t0)
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        c[0] = channel(h + 1.0 / 3)
        c[1] = channel(h)
        c[2] = channel(h - 1.0 / 3)
        if (push != 1.0) saturate(c, push, satEdge)
    }

    /**
     * Pushes [c] away from its gray by [push]. Clipping leaves the channels unclamped, so
     * they slam into the ends at the very end (old Photoshop). Overflow and mirror work on
     * the saturation itself: 1 is the push at which the first channel reaches its end;
     * beyond it the saturation starts again from gray (overflow) or runs back (mirror),
     * the hue stays.
     */
    private fun saturate(c: DoubleArray, push: Double, mode: Int) {
        val gray = luminance(c)
        val d = DoubleArray(3) { c[it] - gray }
        if (mode == 0) {
            for (k in 0..2) c[k] = gray + d[k] * push
            return
        }
        // the largest push that keeps every channel inside 0..1
        var full = Double.MAX_VALUE
        for (k in 0..2) {
            if (d[k] > 1e-9) full = min(full, (1 - gray) / d[k])
            else if (d[k] < -1e-9) full = min(full, gray / -d[k])
        }
        if (full == Double.MAX_VALUE) return // gray: nothing to push
        val m = edge(push / full, mode) * full
        for (k in 0..2) c[k] = gray + d[k] * m
    }
}

/**
 * Tone curves for RGB and the single channels, stored as text:
 * `rgb:0,0 128,150 255,255;r:…;g:…;b:…` (input,output pairs 0..255). A channel without
 * points is a straight line. Between the points the curve runs smoothly without
 * overshooting (monotone cubic interpolation).
 */
object Curves {
    val channels = listOf("rgb", "r", "g", "b")

    /** The points of every channel (index as in [channels]), sorted by input. */
    fun parse(text: String): List<List<Pair<Int, Int>>> {
        val map = text.split(';').mapNotNull { part ->
            val name = part.substringBefore(':', "").trim()
            if (name !in channels) return@mapNotNull null
            val points = part.substringAfter(':').trim().split(' ').mapNotNull { p ->
                val xy = p.split(',')
                if (xy.size != 2) null
                else {
                    val px = xy[0].trim().toIntOrNull()
                    val py = xy[1].trim().toIntOrNull()
                    if (px == null || py == null) null else px.coerceIn(0, 255) to py.coerceIn(0, 255)
                }
            }.sortedBy { it.first }.distinctBy { it.first }
            name to points
        }.toMap()
        return channels.map { name -> map[name]?.takeIf { it.size >= 2 } ?: listOf(0 to 0, 255 to 255) }
    }

    fun format(curves: List<List<Pair<Int, Int>>>): String =
        channels.indices.filter { !isIdentity(curves[it]) }
            .joinToString(";") { k -> channels[k] + ":" + curves[k].joinToString(" ") { "${it.first},${it.second}" } }

    fun isIdentity(points: List<Pair<Int, Int>>) = points.all { it.first == it.second } && points.size >= 2 &&
        points.first().first == 0 && points.last().first == 255

    /** Lookup tables (257 entries, output 0..1) per channel; null if all curves are straight. */
    fun luts(text: String): List<DoubleArray>? {
        val curves = parse(text)
        if (curves.all(::isIdentity)) return null
        return curves.map(::lut)
    }

    /** The curve through [points] sampled at 0..256 (/256), output 0..1. */
    fun lut(points: List<Pair<Int, Int>>): DoubleArray {
        val n = points.size
        val xs = DoubleArray(n) { points[it].first / 255.0 }
        val ys = DoubleArray(n) { points[it].second / 255.0 }
        // monotone cubic (Fritsch–Carlson) slopes
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-9, xs[it + 1] - xs[it]) }
        val m = DoubleArray(n) { i ->
            when (i) {
                0 -> d[0]
                n - 1 -> d[n - 2]
                else -> if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
            }
        }
        for (i in 0 until n - 1) {
            if (abs(d[i]) < 1e-12) { m[i] = 0.0; m[i + 1] = 0.0; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9) {
                val t = 3 / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return DoubleArray(257) { k ->
            val x = k / 256.0
            when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    var i = 0
                    while (i < n - 2 && x > xs[i + 1]) i++
                    val h = xs[i + 1] - xs[i]
                    val t = (x - xs[i]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                }
            }.coerceIn(0.0, 1.0)
        }
    }

    /** [value] through [lut]; beyond 0..1 the curve continues straight. */
    fun apply(lut: DoubleArray, value: Double): Double {
        val clamped = value.coerceIn(0.0, 1.0)
        val pos = clamped * 256
        val i = pos.toInt().coerceAtMost(255)
        val f = pos - i
        return lut[i] + (lut[i + 1] - lut[i]) * f + (value - clamped)
    }
}
