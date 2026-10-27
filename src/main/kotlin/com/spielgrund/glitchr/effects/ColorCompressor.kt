package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.Color
import kotlin.math.roundToInt

/**
 * A compressor for the colors, like After Effects' Auto Levels taken further: it looks where the
 * lowest and highest values of the picture lie and stretches what lies between onto the whole range.
 * Each slider says how strongly, for its own part, one after the other:
 * - red, green, blue: each channel on its own – its darkest becomes 0, its brightest 255 (this also
 *   takes out color casts);
 * - hue: the arc of hues the picture uses is spread round the colour wheel (all but a small gap, so
 *   its two ends stay apart);
 * - saturation: the dullest becomes grey, the most colourful fully saturated;
 * - luma: the darkest brightness becomes black and the brightest white, the colours kept.
 * A share at both ends can be left out (clipped), so a few stray pixels don't hold it back; the gamma
 * bends the midtones between the ends towards the low or the high one, as with levels.
 * Transparent pixels don't count.
 */
object ColorCompressor : Effect("colorcompressor", "Color compressor", "Stretches the picture's range to the full one – for red, green, blue, hue, saturation and brightness, each as strongly as set") {
    override val params = listOf(
        Param.Slider("red", "Red", 0, 100, 100, " %", "Red's darkest value to 0, its brightest to 255"),
        Param.Slider("green", "Green", 0, 100, 100, " %"),
        Param.Slider("blue", "Blue", 0, 100, 100, " %"),
        Param.Slider("hue", "Hue", 0, 100, 0, " %", "The hues the picture uses spread round the whole colour wheel"),
        Param.Slider("saturation", "Saturation", 0, 100, 0, " %", "The dullest colour to grey, the most colourful to full saturation"),
        Param.Slider("luma", "Luma", 0, 100, 0, " %", "The darkest brightness to black, the brightest to white – the colours stay"),
        Param.Slider("blackClip", "Low clip", 0, 5000, 10, " %", "This share at the low end is left out and goes to the end too – so a few stray pixels don't hold it back", decimals = 2),
        Param.Slider("whiteClip", "High clip", 0, 5000, 10, " %", "This share at the high end is left out", decimals = 2),
        Param.Slider(
            "gamma", "Gamma", -100, 100, 0, " %",
            "The midtones between low and high (as with levels): negative pulls them towards low – darker, less colourful – positive towards high",
        ),
    )

    override val random = false

    /** Hues are spread over this much of the wheel, so the two ends of the arc don't meet. */
    private const val HUE_SPREAD = 330.0

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val low = v["blackClip"] / 10000.0
        val high = v["whiteClip"] / 10000.0
        // the midtones: what lies halfway after the stretch is raised to this power (< 1 towards high, > 1 towards low)
        val power = Math.pow(2.0, -v["gamma"] / 50.0)
        val amounts = listOf("red", "green", "blue").map { v[it] / 100.0 }
        val hueAmount = v["hue"] / 100.0
        val satAmount = v["saturation"] / 100.0
        val lumaAmount = v["luma"] / 100.0
        val data = src.data.copyOf()
        val opaque = { c: Int -> (c ushr 24) != 0 }

        // red, green, blue: each channel stretched on its own
        if (amounts.any { it > 0 }) {
            val hist = Array(3) { LongArray(256) }
            for (c in data) if (opaque(c)) for (k in 0..2) hist[k][c shr (16 - 8 * k) and 0xFF]++
            val luts = Array(3) { k -> lut(range(hist[k], low, high), amounts[k], power) }
            parallelRows(src.height) { y ->
                for (i in y * src.width until (y + 1) * src.width) {
                    val c = data[i]
                    data[i] = (c and 0xFF000000.toInt()) or (luts[0][c shr 16 and 0xFF] shl 16) or (luts[1][c shr 8 and 0xFF] shl 8) or luts[2][c and 0xFF]
                }
            }
        }

        // hue and saturation, in HSB: the hue's arc spread, the saturation stretched
        if (hueAmount > 0 || satAmount > 0) {
            val hues = DoubleArray(360)
            val sats = LongArray(256)
            val hsb = FloatArray(3)
            for (c in data) {
                if (!opaque(c)) continue
                Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, hsb)
                sats[(hsb[1] * 255).roundToInt()]++
                // greys have no hue to speak of: the hues counted by how colourful they are
                if (hsb[1] > 0.05f && hsb[2] > 0.05f) hues[(hsb[0] * 360).toInt() % 360] += hsb[1].toDouble()
            }
            val arc = if (hueAmount > 0) hueArc(hues, low + high) else null
            val satLut = lut(range(sats, low, high), satAmount, power)
            parallelRows(src.height) { y ->
                val f = FloatArray(3)
                for (i in y * src.width until (y + 1) * src.width) {
                    val c = data[i]
                    if (!opaque(c)) continue
                    Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, f)
                    var h = f[0] * 360.0
                    if (arc != null && f[1] > 0) {
                        // from the arc's middle outwards, wider
                        val (middle, length) = arc
                        val d = ((h - middle + 540) % 360) - 180
                        h = middle + d + (d * HUE_SPREAD / length - d) * hueAmount
                    }
                    val s = satLut[(f[1] * 255).roundToInt()] / 255f
                    data[i] = (c and 0xFF000000.toInt()) or (Color.HSBtoRGB((((h % 360) + 360) % 360 / 360).toFloat(), s, f[2]) and 0xFFFFFF)
                }
            }
        }

        // luma: the brightness stretched, the colours kept (each channel moved by the same amount)
        if (lumaAmount > 0) {
            fun luma(c: Int) = 0.2126 * (c shr 16 and 0xFF) + 0.7152 * (c shr 8 and 0xFF) + 0.0722 * (c and 0xFF)
            val hist = LongArray(256)
            for (c in data) if (opaque(c)) hist[luma(c).roundToInt().coerceIn(0, 255)]++
            val r = range(hist, low, high)
            if (r != null) parallelRows(src.height) { y ->
                for (i in y * src.width until (y + 1) * src.width) {
                    val c = data[i]
                    val l = luma(c)
                    val stretched = Math.pow(((l - r.first) / (r.second - r.first)).coerceIn(0.0, 1.0), power) * 255
                    val shift = (stretched - l) * lumaAmount
                    fun ch(s: Int) = ((c shr s and 0xFF) + shift).roundToInt().coerceIn(0, 255)
                    data[i] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
        }
        return Pixels(src.width, src.height, data)
    }

    /**
     * A lookup: [r]'s low end to 0, its high end to 255, the midtones bent by [power] (gamma), mixed with
     * the value itself by [amount]; unchanged without a range.
     */
    private fun lut(r: Pair<Int, Int>?, amount: Double, power: Double) = IntArray(256) { value ->
        val stretched = if (r == null) value.toDouble() else Math.pow(((value - r.first).toDouble() / (r.second - r.first)).coerceIn(0.0, 1.0), power) * 255
        (value + (stretched.coerceIn(0.0, 255.0) - value) * amount).roundToInt().coerceIn(0, 255)
    }

    /**
     * The arc of the colour wheel the picture's hues lie on: its middle and length in degrees – the wheel
     * less its widest gap, a gap holding at most [clip] of all the hue. Null when nothing is to be spread
     * (no colours, or they already fill nearly all of it).
     */
    internal fun hueArc(hues: DoubleArray, clip: Double): Pair<Double, Double>? {
        val total = hues.sum()
        if (total <= 0) return null
        val allowed = clip * total
        // the widest run of degrees (going round) with at most the allowed hue in it
        var bestStart = 0
        var bestLength = 0
        var sum = 0.0
        var end = 0
        for (start in 0 until 360) {
            if (end < start) { end = start; sum = 0.0 }
            while (end - start < 359 && sum + hues[end % 360] <= allowed) { sum += hues[end % 360]; end++ }
            if (end - start > bestLength) { bestLength = end - start; bestStart = start }
            if (end > start) sum -= hues[start % 360]
        }
        val length = 360.0 - bestLength
        if (length >= HUE_SPREAD || length <= 0) return null
        // the arc starts where the gap ends
        val arcStart = bestStart + bestLength
        return ((arcStart + length / 2) % 360) to length
    }

    /**
     * The lowest and highest value of the counts [hist], the shares [low] and [high] of them left out at
     * the ends; null when there is nothing to stretch (no pixels, or all one value).
     */
    internal fun range(hist: LongArray, low: Double, high: Double): Pair<Int, Int>? {
        val total = hist.sum()
        if (total == 0L) return null
        var a = 0
        var seen = 0L
        while (a < 255 && seen + hist[a] <= low * total) seen += hist[a++]
        while (a < 255 && hist[a] == 0L) a++
        var b = 255
        seen = 0L
        while (b > 0 && seen + hist[b] <= high * total) seen += hist[b--]
        while (b > 0 && hist[b] == 0L) b--
        return if (b <= a) null else a to b
    }
}
