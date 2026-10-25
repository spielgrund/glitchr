package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Light living in the blobs of the picture (its opaque, bright or dark areas): it gathers in
 * every blob, brightest in its middle, and a pulse sets them glowing – a wave of light running
 * out through each blob from its middle (or through all of them from start points), followed by
 * more waves and an afterglow. As bioluminescence the picture sinks into a deep-sea darkness
 * around the glowing blobs; as black light it turns UV violet, whites fluoresce and the blobs
 * shine in vivid neon.
 */
object Luminescence : Effect("luminescence", "Luminescence", "Light gathering in the blobs of the picture and waves of it running through them – bioluminescence or black light; animate the pulse") {
    private val modes = listOf("Bioluminescence", "Black light")

    /** Per pixel: how much it is in a blob, how deep (0 at the blob's edge, 1 in its middle), and how far along the wave's way (0..1). */
    private class Field(val inside: FloatArray, val depth: FloatArray, val phase: FloatArray)

    private val cache = LastResult<Field>()

    override val params = listOf(
        Param.Choice(
            "mode", "Light", modes, 0,
            "Bioluminescence: the picture sinks into darkness, the blobs glow from within · Black light: a UV look – violet darkness, fluorescing whites, the blobs in vivid neon",
        ),
        Param.Choice("source", "Blobs from", shapeSources, 1, "What counts as a blob: the opaque parts (if the picture has no transparency: the bright ones), or the bright or dark areas"),
        Param.Slider("threshold", "Threshold", 0, 100, 55, " %", "For bright or dark areas: from which brightness on"),
        Param.Slider("smooth", "Smoothing", 0, 100, 30, " %", "Rounds off the blobs and merges specks"),
        Param.Slider("collect", "Collected light", 0, 100, 60, " %", "How much light gathers in the blobs, brightest in their middle"),
        Param.Heading("waveHeading", "Wave"),
        Param.Slider("pulse", "Pulse", 0, 10000, 0, " %", "Sets the blobs glowing: a wave of light runs out through them – animate it from 0 to 100 %", decimals = 2),
        Param.Choice("from", "Wave from", listOf("Each blob's middle", "Start points"), 0, "Each blob's middle: every blob lights up from within, all at once · Start points: the wave travels from the points through the blobs, crossing the gaps slowly"),
        Param.Points("points", "Start points", "0.5000,0.5000", "Where the wave starts – drag them in the picture", activeWhen = "from" to 1),
        Param.Slider("waveWidth", "Wave width", 1, 100, 15, " %", "Of the way through the blob"),
        Param.Slider("rings", "Waves", 1, 8, 1, tip = "How many waves follow each other"),
        Param.Slider("afterglow", "Afterglow", 0, 100, 40, " %", "Light the wave leaves behind, fading"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Glow color", 0x2AF5D2),
        Param.Toggle("pictureColors", "Picture's colors", false, "The blobs glow in their own colors, made vivid, instead of the glow color"),
        Param.Slider("darken", "Darkness", 0, 100, 75, " %", "How dark the picture becomes around the light"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %"),
        Param.Slider("glowSize", "Glow size", 50, 20000, 1200, " px", decimals = 2),
        Param.Slider("sparkles", "Sparkles", 0, 100, 30, " %", "Tiny glowing specks in and around the blobs, twinkling with the pulse"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val size = min(w, h).toDouble()
        val noise = Noise(seed)
        val fromPoints = v["from"] == 1
        val key = listOf(src.data.contentHashCode(), w, h, v["source"], v["threshold"], v["smooth"], v["from"], if (fromPoints) v.text("points") else "")
        val field = cache.get(key) { field(src, v, fromPoints) }

        val pulse = v["pulse"] / 10000.0
        val width = max(0.01, v["waveWidth"] / 100.0 * 0.5)
        val rings = v["rings"]
        val spacing = 0.3 + width
        // 0 %: the first wave just before the middle · 100 %: the last one past the far edge
        val lead = pulse * (1 + 4 * width + (rings - 1) * spacing) - 2 * width
        val waving = smoothstep(0.0, 0.03, pulse)
        val collect = v["collect"] / 100.0
        val afterglow = v["afterglow"] / 100.0

        // how much light every pixel holds
        val light = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val inside = field.inside[i]
                if (inside <= 0f) continue
                val phase = field.phase[i].toDouble()
                var wave = 0.0
                for (k in 0 until rings) {
                    val d = (phase - (lead - k * spacing)) / width
                    wave += exp(-d * d) * (1 - k * 0.12)
                }
                val after = if (phase < lead) afterglow * exp(-(lead - phase) / 0.35) else 0.0
                val gathered = collect * field.depth[i].toDouble().pow(0.8) * (0.9 + 0.1 * noise.fbm(x / (size * 0.05), y / (size * 0.05), 2))
                light[i] = (inside * (gathered * 0.45 + (wave + after * 0.5) * waving)).toFloat()
            }
        }

        val black = v["mode"] == 1
        val glow = v["glow"] / 100.0
        val halo = if (glow > 0) Blur.gauss(light, w, h, max(1.0, v["glowSize"] / 100.0)) else null
        val sparkles = v["sparkles"] / 100.0
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val color = v["color"]
        val own = v.bool("pictureColors")
        val darken = v["darken"] / 100.0
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val lc = DoubleArray(3)
            val hsbRow = FloatArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val p = src.data[i]
                val l = luma(p) / 255.0
                // the light's color: the glow color, or the picture's own made vivid (neon under black light)
                if (own) {
                    java.awt.Color.RGBtoHSB(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF, hsbRow)
                    val c = java.awt.Color.HSBtoRGB(hsbRow[0], if (black) 1f else min(1f, hsbRow[1] * 1.5f + 0.3f), 1f)
                    lc[0] = ch(c, 16); lc[1] = ch(c, 8); lc[2] = ch(c, 0)
                } else {
                    lc[0] = ch(color, 16); lc[1] = ch(color, 8); lc[2] = ch(color, 0)
                }
                val li = light[i].toDouble()
                val hi = if (halo == null) 0.0 else min(1.0, halo[i] * 1.2) * glow * 0.6
                // tiny specks, twinkling as the pulse goes on
                var spark = 0.0
                if (sparkles > 0 && (li > 0.02 || hi > 0.05)) {
                    val n = noise.white(x, y)
                    if (n > 1 - sparkles * 0.012) spark = (0.5 + 0.5 * sin(pulse * 40 + n * 977)) * (0.4 + li + hi)
                }
                var r: Double; var g: Double; var b: Double
                if (!black) {
                    // the deep sea: dark, a little blue
                    val keep = 1 - darken
                    r = ch(p, 16) * keep * 0.6; g = ch(p, 8) * keep * 0.8; b = ch(p, 0) * keep
                    val core = max(0.0, li - 0.85) * 0.8
                    val shine = li + hi + spark
                    r += lc[0] * shine + core; g += lc[1] * shine + core; b += lc[2] * shine + core
                } else {
                    // UV violet darkness, whites fluorescing blue-violet, the light in neon
                    val keep = (0.25 + 0.75 * l) * (1 - darken * 0.6)
                    val white = max(0.0, l - 0.7) / 0.3 * 0.5
                    r = 0.30 * keep + 0.55 * white; g = 0.10 * keep + 0.45 * white; b = 0.65 * keep + white
                    val shine = li * 1.2 + hi * 1.2 + spark
                    val core = max(0.0, li - 1.0) * 0.3
                    r += lc[0] * shine + 0.30 * hi * 0.3 + core; g += lc[1] * shine + 0.10 * hi * 0.3 + core; b += lc[2] * shine + 0.65 * hi * 0.3 + core
                }
                val pa = (p ushr 24) / 255.0
                val oa = max(pa, min(1.0, li + hi + spark))
                fun c8(q: Double) = (min(1.0, q) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
            }
        }
        return out
    }

    /** The blobs, their depth and the wave's way through them – kept while only the pulse or the look changes. */
    private fun field(src: Pixels, v: Values, fromPoints: Boolean): Field {
        val w = src.width
        val h = src.height
        val size = min(w, h).toDouble()
        val raw = shapeMask(src, v["source"], v["threshold"] / 100.0 * 255)
        val smooth = v["smooth"] / 100.0
        val soft = if (smooth > 0) Blur.gauss(raw, w, h, smooth * size * 0.01) else raw
        val inside = BooleanArray(w * h) { soft[it] >= 0.5f }
        val cover = FloatArray(w * h) { smoothstep(0.5, 0.62, soft[it].toDouble()).toFloat() }
        // depth: the distance to the blob's edge, of the deepest point of the blob
        val (dist, _) = distanceTransform(BooleanArray(w * h) { !inside[it] }, w, h)
        val (label, count) = labelAreas(inside, w, h)
        val deepest = DoubleArray(count)
        val middle = IntArray(count) { -1 }
        for (i in label.indices) {
            val b = label[i]
            if (b >= 0 && dist[i] > deepest[b]) { deepest[b] = dist[i]; middle[b] = i }
        }
        val depth = FloatArray(w * h) { i -> label[i].let { b -> if (b < 0) 0f else (dist[i] / max(1.0, deepest[b])).toFloat() } }
        // the wave's way: from each blob's middle through the blob, or from the points through all of them (slowly across the gaps)
        val seeds = if (fromPoints) PointList.parse(v.text("points")).map { p -> (p.y * h).toInt().coerceIn(0, h - 1) * w + (p.x * w).toInt().coerceIn(0, w - 1) }.toIntArray()
        else middle.filter { it >= 0 }.toIntArray()
        val cost = FloatArray(w * h) { if (inside[it]) 1f else if (fromPoints) 4f else Float.POSITIVE_INFINITY }
        val times = spreadTimesScaled(cost, w, h, seeds)
        val longest = DoubleArray(max(1, count))
        var overall = 0.0
        for (i in times.indices) {
            val b = label[i]
            val t = times[i]
            if (b < 0 || t.isInfinite()) continue
            longest[b] = max(longest[b], t.toDouble())
            overall = max(overall, t.toDouble())
        }
        val phase = FloatArray(w * h) { i ->
            val b = label[i]
            val t = times[i]
            when {
                b < 0 -> 0f
                // a blob the way didn't reach (too small for the shrunk grid): by its depth
                t.isInfinite() -> 1 - depth[i]
                fromPoints -> (t / max(1.0, overall)).toFloat()
                else -> (t / max(1.0, longest[b])).toFloat()
            }
        }
        return Field(cover, depth, phase)
    }
}
