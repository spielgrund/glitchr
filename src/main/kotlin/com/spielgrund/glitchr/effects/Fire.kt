package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fire: flames licking up around the shapes (from their outline into the transparent or dark
 * surroundings, behind them) or burning inside them (rising from each shape's bottom edge,
 * kept within it). The shapes come from the alpha channel or the bright or dark areas; turbulent
 * noise scrolling upwards breaks the flames into flickering tongues – animate the time.
 */
object Fire : Effect("fire", "Fire", "Flames around the shapes (alpha or bright areas) or burning inside them – animate the time") {
    private val palettes = listOf(
        "Fire" to intArrayOf(0x3A0300, 0xA51400, 0xE84A06, 0xFF9A1C, 0xFFD95A, 0xFFF8DC),
        "Blue flame" to intArrayOf(0x020833, 0x0A2AA8, 0x1F6CFF, 0x52B6FF, 0xB8ECFF, 0xF4FCFF),
        "Green" to intArrayOf(0x022A04, 0x0B7A12, 0x25C83A, 0x7DFF5C, 0xD4FF9E, 0xF8FFE8),
        "Purple" to intArrayOf(0x1E0233, 0x5A0C9E, 0xA526E0, 0xE070FF, 0xFFC0FF, 0xFFF4FF),
    )

    override val params = listOf(
        Param.Choice("mode", "Flames", listOf("Around the shapes", "Inside the shapes"), 0,
            "Around: rising from the shapes' outline into the surroundings, behind them · Inside: rising from each shape's bottom edge, within the shape"),
        Param.Choice("source", "Shapes from", listOf("Alpha (else brightness)", "Bright areas", "Dark areas"), 0,
            "What counts as a shape: the opaque parts (if the picture has no transparency: the bright ones), or the bright or dark areas"),
        Param.Slider("threshold", "Threshold", 0, 100, 50, " %", "For bright or dark areas: from which brightness on"),
        Param.Slider("time", "Time", 0, 100000, 0, "", "Moves the flames: animate it and they flicker", decimals = 2),
        Param.Heading("flameHeading", "Flames"),
        Param.Slider("height", "Height", 100, 10000, 1800, " %", "How high the flames reach, of the canvas height", decimals = 2, transform = true),
        Param.Slider("direction", "Direction", -18000, 18000, 0, "°", "Where they rise: 0° = up", decimals = 2),
        Param.Slider("turbulence", "Turbulence", 0, 100, 60, " %", "How much the flames break into tongues"),
        Param.Slider("scale", "Tongue size", 200, 30000, 2500, " px", decimals = 2, canvasMax = true),
        Param.Slider("wobble", "Wobble", 0, 100, 40, " %", "How much the flames sway sideways"),
        Param.Slider("speed", "Speed", 0, 400, 100, " %", "How fast they rise with the time"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("palette", "Colors", palettes.map { it.first }, 0),
        Param.Slider("intensity", "Intensity", 10, 300, 100, " %"),
        Param.Slider("glow", "Glow", 0, 300, 40, " %", "A glowing haze around the flames"),
        Param.Slider("mix", "Mix", 0, 100, 100, " %", "Inside: how much the fire covers the shape"),
    )

    /** The color of [heat] (0..1) on [stops]. */
    private fun ramp(stops: IntArray, heat: Double): Int {
        val f = heat.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = min(stops.size - 2, f.toInt())
        val t = f - i
        val a = stops[i]; val b = stops[i + 1]
        fun ch(sh: Int) = ((a shr sh and 0xFF) + ((b shr sh and 0xFF) - (a shr sh and 0xFF)) * t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val inside = v["mode"] == 1
        val threshold = v["threshold"] / 100.0 * 255
        val hasAlpha = src.data.any { alpha(it) < 250 }
        val source = if (v["source"] == 0 && !hasAlpha) 1 else v["source"]
        // how much each pixel belongs to a shape, 0..1
        val raw = FloatArray(w * h) {
            val c = src.data[it]
            when (source) {
                0 -> alpha(c) / 255f
                1 -> if (luma(c) >= threshold) alpha(c) / 255f else 0f
                else -> if (luma(c) < threshold) alpha(c) / 255f else 0f
            }
        }
        // bright or dark areas smoothed, so speckles don't each catch fire
        val mask = if (source == 0) raw else Blur.gauss(raw, w, h, min(w, h) * 0.02)
        val length = max(2.0, h * v["height"] / 10000.0)
        val a = Math.toRadians(v["direction"] / 100.0)
        // up along the flames, and across them
        val ux = sin(a); val uy = -cos(a)
        val px = cos(a); val py = sin(a)
        val turbulence = v["turbulence"] / 100.0
        val scale = v["scale"] / 100.0
        val wobble = v["wobble"] / 100.0
        val rise = v["time"] / 100.0 * v["speed"] / 100.0 * scale * 1.5
        val samples = min(64, max(8, (length / 3).toInt()))
        val step = length / samples

        fun inShape(x: Double, y: Double): Boolean {
            val ix = x.toInt(); val iy = y.toInt()
            return x >= 0 && y >= 0 && ix < w && iy < h && mask[iy * w + ix] >= 0.5f
        }

        val heat = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                if (inside && mask[i] <= 0f) continue
                // the pixel in the flames' frame: along (height) and across
                val along = x * ux + y * uy
                val across = x * px + y * py
                // the flames sway: the way down to the fuel leans aside
                val sway = noise.fbm(across / (scale * 1.6) + 50, (along - rise * 0.8) / (scale * 1.6), 2) * wobble * length * 0.6
                // how far below the pixel the fuel (around) or the shape's bottom edge (inside) lies
                fun crossed(s: Double): Boolean {
                    val lean = sway * s / length
                    return inside != inShape(x - ux * s + px * lean, y - uy * s + py * lean)
                }
                var d = -1.0
                for (k in 0..samples) {
                    if (!crossed(k * step)) continue
                    // narrowed down between the last step before and this one, so no bands show
                    var lo = max(0.0, (k - 1) * step); var hi = k * step
                    if (k > 0) repeat(5) { val mid = (lo + hi) / 2; if (crossed(mid)) hi = mid else lo = mid }
                    d = hi
                    break
                }
                // inside, a shape shorter than the flames burns all the way up
                if (inside && d < 0) d = length * 2
                if (d < 0) continue
                if (!inside && d == 0.0) continue
                // longer and shorter flames side by side
                val reach = length * (0.55 + 0.45 * (noise.perlin(across / (scale * 1.7) + 9.1, rise * 0.15 / scale) + 1))
                val base = 1 - d / reach
                if (base <= 0) continue
                // tongues: turbulent noise, stretched upwards and rising with the time, eats into the flames – most at their tips
                val n = (noise.fbm(across / scale, (along - rise) / (scale * 1.8), 4) * 1.6 + 0.5).coerceIn(0.0, 1.0)
                val t = base * (1 + turbulence * 0.35) - turbulence * (1 - n) * 1.25 * (1 - base * 0.55)
                heat[i] = (t.coerceIn(0.0, 1.0)).toFloat()
            }
        }

        val stops = palettes[v["palette"]].second
        val intensity = v["intensity"] / 100.0
        val glow = v["glow"] / 100.0
        val halo = if (glow > 0) Blur.gauss(heat, w, h, max(2.0, length * 0.12)) else null
        val glowColor = stops[2]
        val mix = v["mix"] / 100.0
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val hv = min(1.0, heat[i] * intensity)
                // the flame: its color by heat, opaque where hot
                val fa = ((hv - 0.04) / 0.3).coerceIn(0.0, 1.0)
                val fc = ramp(stops, hv)
                val g = (halo?.get(i) ?: 0f) * glow * intensity * 0.9
                val ga = min(1.0, g)
                // the fire layer: flame over its glow
                val la = fa + ga * (1 - fa)
                fun layer(sh: Int) = if (la <= 0) 0.0 else ((fc shr sh and 0xFF) * fa + (glowColor shr sh and 0xFF) * ga * (1 - fa)) / la
                val ba = alpha(base) / 255.0
                if (inside) {
                    // within the shape: the fire over it, then the glow lighting what is around
                    val m = mask[i].toDouble()
                    val k = fa * mix * m
                    fun ch(sh: Int): Int {
                        val b = (base shr sh and 0xFF).toDouble()
                        val f = b + ((fc shr sh and 0xFF) - b) * k
                        return (f + (glowColor shr sh and 0xFF) * g * 0.35).toInt().coerceIn(0, 255)
                    }
                    out.data[i] = ((max(ba, min(1.0, g * 0.6)) * 255 + 0.5).toInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                } else {
                    // around: the picture over the fire, the fire showing where the picture is see-through
                    val a = ba + la * (1 - ba)
                    if (a <= 0.002) { out.data[i] = 0; continue }
                    fun ch(sh: Int): Int {
                        val under = layer(sh) * la * (1 - ba)
                        val over = (base shr sh and 0xFF) * ba
                        // on an opaque picture the fire is added to the dark surroundings
                        val add = if (hasAlpha) 0.0 else layer(sh) * la * (1 - mask[i])
                        return ((over + under) / a + add).toInt().coerceIn(0, 255)
                    }
                    out.data[i] = ((a * 255 + 0.5).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
        }
        return out
    }
}
