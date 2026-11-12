package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Scanner: the picture as if it was laid on a flatbed scanner and something went wrong – the
 * scan head dragged the picture along in streaks of different lengths (the subject moved, or the
 * glass was smeared), light leaked in at the open sides of the lid and blooms in, single sensor
 * pixels left bright or colored lines all the way through, the head stuttered so rows slipped
 * sideways. Darker and paler, as such scans come out.
 */
object Scanner : Effect("scanner", "Scanner", "As if scanned on a flatbed scanner gone wrong – streaks dragged along the scan, light leaking in at the sides, sensor lines, slipped rows") {
    override val params = listOf(
        Param.Choice("direction", "Scan", listOf("Top to bottom", "Left to right"), 0, "Which way the scan head ran: the streaks run that way"),
        Param.Heading("streakHeading", "Streaks"),
        Param.Slider("streaks", "Streaks", 0, 100, 65, " %", "How strongly the picture is dragged along the scan"),
        Param.Slider("length", "Length", 1, 100, 30, " %", "How far the longest streaks reach, of the picture's length"),
        Param.Slider("variation", "Variation", 0, 100, 70, " %", "How much the streaks differ from column to column"),
        Param.Slider("lines", "Sensor lines", 0, 100, 35, " %", "Thin bright and colored lines all the way through"),
        Param.Slider("slip", "Slipped rows", 0, 100, 15, " %", "The head stuttered: rows shifted sideways"),
        Param.Heading("lightHeading", "Light"),
        Param.Slider("leak", "Light leak", 0, 200, 90, " %", "Light coming in at the open sides of the lid, blooming into the picture"),
        Param.Slider("leakWidth", "Leak width", 1, 100, 22, " %", "How far it reaches in, of the width"),
        Param.Color("leakColor", "Leak color", 0xE8F6FF),
        Param.Slider("exposure", "Exposure", -100, 100, -25, " %", "Scans like this come out dark – minus: darker"),
        Param.Slider("saturation", "Saturation", 0, 200, 60, " %"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        // worked out for a scan from top to bottom; left to right is the same, turned
        val across = v["direction"] == 1
        val input = if (across) transpose(src) else src
        val result = scan(input, v, seed)
        return if (across) transpose(result) else result
    }

    private fun transpose(p: Pixels): Pixels {
        val out = Pixels(p.height, p.width)
        parallelRows(p.height) { y -> for (x in 0 until p.width) out.data[x * p.height + y] = p.data[y * p.width + x] }
        return out
    }

    private fun scan(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val random = Random(seed)
        val streaks = v["streaks"] / 100.0
        val maxLen = max(1.0, v["length"] / 100.0 * h)
        val variation = v["variation"] / 100.0
        val slip = v["slip"] / 100.0
        val exposure = 1 + v["exposure"] / 100.0 * 0.8
        val saturation = v["saturation"] / 100.0

        // ---- rows slipped sideways where the head stuttered
        val shift = IntArray(h)
        if (slip > 0) {
            var y = 0
            while (y < h) {
                val run = 1 + random.nextInt(max(2, h / 40))
                val s = if (random.nextDouble() < slip * 0.35) ((random.nextDouble() * 2 - 1) * slip * w * 0.04).roundToInt() else 0
                for (k in y until min(h, y + run)) shift[k] = s
                y += run
            }
        }

        // ---- each column's streak: how far it drags, and how strongly
        // broad swells across the picture, and every column on its own: hard, crisp streaks
        val colRandom = Random(seed xor 0x5CA77E2L)
        val colLen = DoubleArray(w) { x ->
            val n = noise.fbm(x / (w * 0.03 + 1), 3.7, 3) * 0.5 + 0.5
            val own = colRandom.nextDouble()
            val k = (1 - variation) * 0.6 + variation * (0.45 * n * n + 0.75 * own.pow(3.0))
            (k * maxLen).coerceIn(1.0, h.toDouble())
        }
        val colStrength = DoubleArray(w) { x ->
            val n = noise.perlin(x * 0.08 + 40.0, 2.2) * 0.5 + 0.5
            val own = colRandom.nextDouble()
            (streaks * (1 - variation * 0.7 + variation * 0.7 * (0.4 * n + 0.6 * own)) * 1.15).coerceIn(0.0, 1.0)
        }

        // ---- per column, the running sums (of the slipped picture) so a streak's average is quick
        val out = Pixels(w, h)
        parallelRows(w) { x ->
            val sr = DoubleArray(h + 1)
            val sg = DoubleArray(h + 1)
            val sb = DoubleArray(h + 1)
            val sa = DoubleArray(h + 1)
            val col = IntArray(h)
            for (y in 0 until h) {
                val sx = (x - shift[y]).coerceIn(0, w - 1)
                val c = src.data[y * w + sx]
                col[y] = c
                val a = (c ushr 24) / 255.0
                sr[y + 1] = sr[y] + (c shr 16 and 0xFF) * a
                sg[y + 1] = sg[y] + (c shr 8 and 0xFF) * a
                sb[y + 1] = sb[y] + (c and 0xFF) * a
                sa[y + 1] = sa[y] + a
            }
            val len = colLen[x]
            val strength = colStrength[x]
            for (y in 0 until h) {
                val c = col[y]
                var r = (c shr 16 and 0xFF).toDouble()
                var g = (c shr 8 and 0xFF).toDouble()
                var b = (c and 0xFF).toDouble()
                var a = (c ushr 24) / 255.0
                if (strength > 0) {
                    // the picture dragged down: the average over the stretch above (the head carried it along)
                    val y0 = max(0, (y - len).toInt())
                    val n = sa[y + 1] - sa[y0]
                    if (n > 1e-6) {
                        val mr = (sr[y + 1] - sr[y0]) / n
                        val mg = (sg[y + 1] - sg[y0]) / n
                        val mb = (sb[y + 1] - sb[y0]) / n
                        r += (mr - r) * strength
                        g += (mg - g) * strength
                        b += (mb - b) * strength
                        a += (n / (y + 1 - y0) - a) * strength
                    }
                }
                val i = y * w + x
                out.data[i] = ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
            }
        }

        // ---- sensor lines: single columns left bright or colored all the way
        val lineAmount = v["lines"] / 100.0
        val lineColor = DoubleArray(w * 3)
        val lineStrength = DoubleArray(w)
        if (lineAmount > 0) for (x in 0 until w) {
            if (random.nextDouble() < lineAmount * 0.025) {
                lineStrength[x] = 0.25 + random.nextDouble() * 0.75
                // mostly white, sometimes one color channel stuck
                when (random.nextInt(6)) {
                    0 -> { lineColor[x * 3] = 255.0; lineColor[x * 3 + 1] = 60.0; lineColor[x * 3 + 2] = 40.0 }
                    1 -> { lineColor[x * 3] = 70.0; lineColor[x * 3 + 1] = 120.0; lineColor[x * 3 + 2] = 255.0 }
                    else -> { lineColor[x * 3] = 255.0; lineColor[x * 3 + 1] = 255.0; lineColor[x * 3 + 2] = 255.0 }
                }
            }
        }

        // ---- the light leak: in from both sides, uneven along the scan
        val leak = v["leak"] / 100.0
        val leakW = max(1.0, v["leakWidth"] / 100.0 * w)
        val leakColor = v["leakColor"]

        val finished = Pixels(w, h)
        parallelRows(h) { y ->
            // how bright the leak is on each side at this row
            val left = leak * (0.55 + 0.45 * noise.fbm(y / (h * 0.15 + 1), 1.3, 3) * 2).coerceIn(0.0, 2.0)
            val right = leak * (0.55 + 0.45 * noise.fbm(y / (h * 0.15 + 1), 7.9, 3) * 2).coerceIn(0.0, 2.0)
            for (x in 0 until w) {
                val i = y * w + x
                val c = out.data[i]
                var r = (c shr 16 and 0xFF) / 255.0
                var g = (c shr 8 and 0xFF) / 255.0
                var b = (c and 0xFF) / 255.0
                var a = (c ushr 24) / 255.0
                // darker and paler
                val l = 0.3 * r + 0.59 * g + 0.11 * b
                r = (l + (r - l) * saturation) * exposure
                g = (l + (g - l) * saturation) * exposure
                b = (l + (b - l) * saturation) * exposure
                // the leak, with streaks of its own (it is dragged along too)
                if (leak > 0) {
                    val streak = 0.7 + 0.3 * (noise.perlin(x * 0.2, 5.5) * 0.5 + 0.5)
                    val k = 1.4 * (left * exp(-x / leakW) + right * exp(-(w - 1 - x) / leakW)) * streak
                    if (k > 0.001) {
                        // screened on, so the whites bloom out
                        fun screen(q: Double, lc: Double) = 1 - (1 - q) * (1 - min(1.0, lc * k))
                        r = screen(r, (leakColor shr 16 and 0xFF) / 255.0)
                        g = screen(g, (leakColor shr 8 and 0xFF) / 255.0)
                        b = screen(b, (leakColor and 0xFF) / 255.0)
                        a = max(a, min(1.0, k))
                    }
                }
                // sensor lines
                val ls = lineStrength[x]
                if (ls > 0) {
                    r += (lineColor[x * 3] / 255.0 - r) * ls * 0.6
                    g += (lineColor[x * 3 + 1] / 255.0 - g) * ls * 0.6
                    b += (lineColor[x * 3 + 2] / 255.0 - b) * ls * 0.6
                }
                fun c8(q: Double) = (q * 255).roundToInt().coerceIn(0, 255)
                finished.data[i] = (c8(a) shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
            }
        }

        return finished
    }
}
