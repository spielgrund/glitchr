package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Light leaks like on film: light that got into the camera bleeds over the picture in warm, glowing
 * patches – from the edges, out of a corner, as a band across or in blobs anywhere. Each leak runs
 * through the colors of a palette from its rim to its hot core, which burns out towards white; the
 * outlines are soft and uneven. The time lets them drift and flicker like a film running through.
 */
object LightLeaks : Effect("lightleaks", "Light leaks", "Film light leaks – warm glowing patches bleeding in from the edges, a corner, as a band or in blobs; drifting and flickering") {
    private val styles = listOf("From the edges", "Corner burst", "Band across", "Blobs")
    private const val EDGES = 0
    private const val CORNER = 1
    private const val BAND = 2
    private val sides = listOf("Left", "Right", "Top", "Bottom", "Left and right", "All around")
    private val palettes = listOf("Warm", "Sunset", "Magenta", "Cool", "Rainbow", "Custom")
    private const val RAINBOW = 4
    private const val CUSTOM = 5

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: the leaks drift and flicker", decimals = 3),
        Param.Choice("style", "Leaks", styles, EDGES, "From the edges: bleeding in from a side · Corner burst: flaring out of a corner · Band across: a glowing band through the picture · Blobs: patches anywhere"),
        Param.Choice("side", "Side", sides, 4).shownWhen("style", EDGES),
        Param.Slider("count", "Count", 1, 12, 3, "", "How many leaks"),
        Param.Slider("size", "Size", 5, 200, 55, " %", "Of the shorter side"),
        Param.Slider("angle", "Angle", 0, 17999, 6000, "°", "Of the band", decimals = 2).shownWhen("style", BAND),
        Param.Slider("ragged", "Ragged", 0, 100, 45, " %", "Uneven, torn outlines"),
        Param.Heading("colorHeading", "Color"),
        Param.Choice("palette", "Palette", palettes, 0, "From the rim to the core of every leak"),
        Param.Color("color1", "Rim color", 0xD8104A).shownWhen("palette", CUSTOM),
        Param.Color("color2", "Core color", 0xFFB040).shownWhen("palette", CUSTOM),
        Param.Slider("strength", "Strength", 0, 300, 100, " %"),
        Param.Slider("burn", "Burn", 0, 100, 40, " %", "The cores burn out towards white"),
        Param.Slider("wash", "Wash out", 0, 100, 30, " %", "The picture loses contrast under the leaks, like fogged film"),
        Param.Choice("blend", "Blend", listOf("Screen", "Add"), tip = "Screen: soft, never above white · Add: stronger, burns out sooner"),
        Param.Heading("animHeading", "Animation"),
        Param.Slider("drift", "Drift", 0, 200, 50, " %", "How much they wander over time"),
        Param.Slider("flicker", "Flicker", 0, 100, 35, " %", "How much their brightness flickers over time"),
        Param.Slider("speed", "Speed", 1, 400, 60, " %"),
        Param.Toggle("leaksOnly", "Leaks only", false, "Shows only the light on black, e.g. to blend it yourself in the layer"),
    )

    /** The palette from the rim (0) to the core (1), as r, g, b 0..1. */
    private fun paletteColor(palette: Int, t: Double, c1: Int, c2: Int): DoubleArray {
        val stops = when (palette) {
            1 -> intArrayOf(0xB0106E, 0xFF2A30, 0xFF8A2C, 0xFFE0A0)
            2 -> intArrayOf(0x6A10FF, 0xF0189A, 0xFF5AA8, 0xFFD6EC)
            3 -> intArrayOf(0x1030FF, 0x1488FF, 0x40D8FF, 0xD8FAFF)
            CUSTOM -> intArrayOf(c1, c2, c2, 0xFFFFFF)
            RAINBOW -> {
                val h = (t * 0.85 + 0.95) % 1.0
                val f = { n: Double -> val k = (n + h * 6) % 6; 1 - max(0.0, min(min(k, 4 - k), 1.0)) }
                return doubleArrayOf(f(5.0), f(3.0), f(1.0))
            }
            else -> intArrayOf(0xFF1800, 0xFF4A00, 0xFF9A18, 0xFFE2A0)
        }
        val p = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = min(stops.size - 2, p.toInt())
        val f = p - i
        val a = stops[i]
        val b = stops[i + 1]
        return doubleArrayOf(
            (red(a) * (1 - f) + red(b) * f) / 255.0,
            (green(a) * (1 - f) + green(b) * f) / 255.0,
            (blue(a) * (1 - f) + blue(b) * f) / 255.0,
        )
    }

    /** A leak: its middle, its two radii along and across, turned, how bright, where in the palette. */
    private class Leak(val x: Double, val y: Double, val along: Double, val across: Double, val cos: Double, val sin: Double, val power: Double, val hue: Double, val salt: Double)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val short = min(w, h).toDouble()
        val random = Random(seed)
        val noise = Noise(seed)
        val t = v["time"] / 1000.0 * v["speed"] / 100.0
        val drift = v["drift"] / 100.0
        val flicker = v["flicker"] / 100.0
        val size = short * v["size"] / 100.0
        val style = v["style"]
        val side = v["side"]
        val bandAngle = Math.toRadians(v["angle"] / 100.0)

        val leaks = List(v["count"]) { k ->
            val r = size * (0.6 + random.nextDouble() * 0.8)
            // wandering along over time, each its own way
            val wander = noise.perlin(t * 0.25 + k * 3.7, 1.3) * drift
            val wander2 = noise.perlin(t * 0.21 + k * 5.1, 8.9) * drift
            val power = (1 - flicker + flicker * (noise.fbm(t * 1.7 + k * 11.3, 4.4, 3) * 1.4 + 0.6).coerceIn(0.0, 1.2)) * (0.7 + 0.5 * random.nextDouble())
            val hue = random.nextDouble() * 0.35
            val salt = random.nextDouble() * 100
            when (style) {
                EDGES -> {
                    val s = when (side) { 4 -> k % 2; 5 -> k % 4; else -> side }
                    val pos = (random.nextDouble() + wander * 0.25).let { it - kotlin.math.floor(it) }
                    val out = r * (0.15 + 0.25 * random.nextDouble())
                    when (s) {
                        0 -> Leak(-out, pos * h, r * 1.6, r, 0.0, 1.0, power, hue, salt)
                        1 -> Leak(w + out, pos * h, r * 1.6, r, 0.0, 1.0, power, hue, salt)
                        2 -> Leak(pos * w, -out, r * 1.6, r, 1.0, 0.0, power, hue, salt)
                        else -> Leak(pos * w, h + out, r * 1.6, r, 1.0, 0.0, power, hue, salt)
                    }
                }
                CORNER -> {
                    val c = (k + random.nextInt(4)) % 4
                    val cx = if (c % 2 == 0) 0.0 else w.toDouble()
                    val cy = if (c < 2) 0.0 else h.toDouble()
                    val a = random.nextDouble() * PI + wander
                    Leak(cx + cos(a) * r * 0.2, cy + sin(a) * r * 0.2, r * 1.3, r * 0.9, cos(a), sin(a), power, hue, salt)
                }
                BAND -> {
                    // spread across the band, side by side
                    val off = (k - (v["count"] - 1) / 2.0) * r * 0.9 + wander * r
                    val along = (random.nextDouble() - 0.5) * max(w, h) * 0.8 + wander2 * r
                    val ca = cos(bandAngle)
                    val sa = -sin(bandAngle)
                    Leak(w / 2.0 + ca * along - sa * off, h / 2.0 + sa * along + ca * off, max(w, h) * 0.7, r * 0.3, ca, sa, power, hue, salt)
                }
                else -> Leak(
                    (random.nextDouble() + wander * 0.2) * w, (random.nextDouble() + wander2 * 0.2) * h,
                    r * (0.5 + random.nextDouble() * 0.4), r * (0.35 + random.nextDouble() * 0.3),
                    cos(random.nextDouble() * PI), sin(random.nextDouble() * PI), power, hue, salt,
                )
            }
        }

        val palette = v["palette"]
        val c1 = v["color1"]
        val c2 = v["color2"]
        val strength = v["strength"] / 100.0
        val burn = v["burn"] / 100.0
        val wash = v["wash"] / 100.0
        val ragged = v["ragged"] / 100.0
        val add = v["blend"] == 1
        val only = v.bool("leaksOnly")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                var lr = 0.0
                var lg = 0.0
                var lb = 0.0
                var cover = 0.0
                for (l in leaks) {
                    val dx = px - l.x
                    val dy = py - l.y
                    val u = (dx * l.cos + dy * l.sin) / l.along
                    val q = (-dx * l.sin + dy * l.cos) / l.across
                    var d2 = u * u + q * q
                    if (d2 > 9) continue
                    // an uneven, torn outline
                    if (ragged > 0) d2 *= 1 + ragged * 0.9 * noise.fbm(px / (l.across * 0.7) + l.salt, py / (l.across * 0.7) - l.salt + t * 0.1, 3)
                    val f = exp(-d2 * 2.2) * l.power
                    if (f < 0.002) continue
                    // rim to core through the palette, the core burning out
                    val col = paletteColor(palette, (f * 1.1 + l.hue - 0.15).coerceIn(0.0, 1.0), c1, c2)
                    val white = burn * f.pow(2.5)
                    lr += (col[0] * (1 - white) + white) * f
                    lg += (col[1] * (1 - white) + white) * f
                    lb += (col[2] * (1 - white) + white) * f
                    cover += f
                }
                val i = y * w + x
                val c = src.data[i]
                lr *= strength * 2.2
                lg *= strength * 2.2
                lb *= strength * 2.2
                if (only) {
                    out.data[i] = argb(255, ((1 - exp(-lr)) * 255).roundToInt(), ((1 - exp(-lg)) * 255).roundToInt(), ((1 - exp(-lb)) * 255).roundToInt())
                    continue
                }
                // fogged film: the picture flattens towards its middle grey under the leaks
                val k = (wash * min(1.0, cover * strength)).coerceIn(0.0, 1.0) * 0.35
                fun ch(base: Int, l: Double): Int {
                    val b = base + (128 - base) * k * (if (base < 128) 1.0 else 0.4)
                    return (if (add) b + l * 255 else 255 - (255 - b) * exp(-l)).roundToInt().coerceIn(0, 255)
                }
                out.data[i] = argb(alpha(c), ch(red(c), lr), ch(green(c), lg), ch(blue(c), lb))
            }
        }
        return out
    }
}
