package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ColorStop(val pos: Double, val rgb: Int)

/**
 * A color gradient through any number of [stops] (as in Noiser). Stored as text:
 * `0:#1a0b3d 0.5:#e0457b 1:#ffe6a8`.
 */
class ColorRamp(stops: List<ColorStop>) {
    val stops: List<ColorStop> = stops.sortedBy { it.pos }.ifEmpty { listOf(ColorStop(0.0, 0), ColorStop(1.0, 0xFFFFFF)) }

    /** 256 precomputed colors. */
    val lut = IntArray(256) { colorAt(it / 255.0) }

    fun colorAt(t: Double): Int {
        if (t <= stops.first().pos) return stops.first().rgb
        if (t >= stops.last().pos) return stops.last().rgb
        val k = stops.indexOfLast { it.pos <= t }
        val a = stops[k]
        val b = stops[k + 1]
        if (b.pos == a.pos) return b.rgb
        val f = (t - a.pos) / (b.pos - a.pos)
        fun ch(shift: Int): Int {
            val ca = (a.rgb shr shift) and 0xFF
            val cb = (b.rgb shr shift) and 0xFF
            return (ca + (cb - ca) * f).roundToInt() shl shift
        }
        return ch(16) or ch(8) or ch(0)
    }

    fun reversed() = ColorRamp(stops.map { ColorStop(1 - it.pos, it.rgb) })

    fun format() = stops.joinToString(" ") { "%.4f:#%06x".format(java.util.Locale.ROOT, it.pos, it.rgb) }

    companion object {
        fun parse(text: String): ColorRamp {
            val stops = text.trim().split(Regex("\\s+")).mapNotNull { part ->
                val pos = part.substringBefore(':').toDoubleOrNull() ?: return@mapNotNull null
                val rgb = part.substringAfter('#', "").toIntOrNull(16) ?: return@mapNotNull null
                ColorStop(pos.coerceIn(0.0, 1.0), rgb and 0xFFFFFF)
            }
            return if (stops.size >= 2) ColorRamp(stops) else RampPalette.SUNSET.ramp
        }
    }
}

/** Built-in gradients as starting points (the Noiser palettes and a few more). */
enum class RampPalette(private val label: String, private vararg val stops: Pair<Double, Int>) {
    SUNSET("Sunset", 0.0 to 0x1A0B3D, 0.5 to 0xE0457B, 1.0 to 0xFFE6A8),
    GRAY("Greyscale", 0.0 to 0x000000, 1.0 to 0xFFFFFF),
    TERRAIN(
        "Terrain",
        0.0 to 0x0B1F4B, 0.35 to 0x1D5FA8, 0.45 to 0x3A9AD9, 0.48 to 0xE8D7A0, 0.55 to 0x5AA14B,
        0.7 to 0x2F6B2C, 0.82 to 0x7A6A58, 0.92 to 0xD8D8D8, 1.0 to 0xFFFFFF,
    ),
    FIRE("Fire", 0.0 to 0x000000, 0.35 to 0x7A0A00, 0.6 to 0xE0470B, 0.8 to 0xFFB319, 1.0 to 0xFFF6D0),
    OCEAN("Ocean", 0.0 to 0x020A1A, 0.5 to 0x0E4D80, 0.8 to 0x3FB8D6, 1.0 to 0xE8FBFF),
    NEON("Neon", 0.0 to 0x0D0221, 0.33 to 0x541388, 0.66 to 0xF52A86, 1.0 to 0x2DE2E6),
    TOXIC("Toxic", 0.0 to 0x0A0F0A, 0.6 to 0x2E7D32, 1.0 to 0xD4FF3A),
    DUOTONE("Duotone", 0.0 to 0x14125A, 1.0 to 0x19C3A0),
    THERMAL("Thermal", 0.0 to 0x000000, 0.25 to 0x3B0F70, 0.5 to 0xB5367A, 0.75 to 0xFB8861, 1.0 to 0xFCFDBF),
    RAINBOW(
        "Rainbow", 0.0 to 0xFF0000, 0.17 to 0xFFFF00, 0.33 to 0x00FF00, 0.5 to 0x00FFFF, 0.67 to 0x0000FF,
        0.83 to 0xFF00FF, 1.0 to 0xFF0000,
    );

    val ramp get() = ColorRamp(stops.map { (pos, rgb) -> ColorStop(pos, rgb) })

    override fun toString() = label
}

/**
 * Colors the picture through a gradient (gradient map): every pixel's value – its
 * brightness, or saturation, hue, a channel … – picks a color on the ramp. The ramp can
 * run several times (back and forth or with hard jumps) and be shifted along, and is
 * mixed with the picture in several ways; "Color only" keeps the picture's brightness.
 */
object Ramp : Effect("ramp", "Ramp", "Colors the picture through a gradient by brightness, saturation, hue or a channel") {
    private val modes = listOf("Replace", "Color only", "Soft light", "Overlay", "Multiply", "Screen", "Difference")

    override val params = listOf(
        Param.Ramp("ramp", "Gradient", RampPalette.SUNSET.ramp.format(), "Click: add a color stop · Drag: move · Double-click: choose color · Right-click: remove"),
        Param.Choice("source", "Ramp by", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Choice("mode", "Mix", modes, tip = "Color only: the gradient gives the color, the brightness stays the picture's"),
        Param.Slider("repeats", "Repeats", 1, 32, 1, tip = "The gradient runs through several times – color bands"),
        Param.Toggle("mirror", "Mirror repeats", true, "Every second repeat runs back; off: hard jumps"),
        Param.Slider("shift", "Shift", 0, 100, 0, " %", "Shifts the gradient along the values (starts over at the end)"),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val ramp = ColorRamp.parse(v.text("ramp").ifBlank { RampPalette.SUNSET.ramp.format() })
        val source = v["source"]
        val mode = v["mode"]
        val repeats = v["repeats"]
        val mirror = v.bool("mirror")
        val shift = v["shift"] / 100.0
        val amount = v["amount"] / 100.0
        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                if (alpha(c) == 0) {
                    out.data[i] = c
                    continue
                }
                // grays have no hue: they take the start of the ramp
                val value = thresholdValue(c, source).coerceAtLeast(0) / 255.0
                var t = value * repeats + shift
                t = when {
                    repeats == 1 && shift == 0.0 -> value
                    mirror -> (t - 2 * floor(t / 2)).let { if (it > 1) 2 - it else it }
                    else -> (t - floor(t)).let { if (it == 0.0 && t > 0) 1.0 else it }
                }
                val g = ramp.lut[(t * 255).roundToInt().coerceIn(0, 255)]
                val base = doubleArrayOf(red(c) / 255.0, green(c) / 255.0, blue(c) / 255.0)
                val color = doubleArrayOf((g shr 16 and 0xFF) / 255.0, (g shr 8 and 0xFF) / 255.0, (g and 0xFF) / 255.0)
                val mixed = DoubleArray(3) { k ->
                    val b = base[k]
                    val r = color[k]
                    when (mode) {
                        2 -> if (r < 0.5) b - (1 - 2 * r) * b * (1 - b) else b + (2 * r - 1) * (softLight(b) - b)
                        3 -> if (b < 0.5) 2 * b * r else 1 - 2 * (1 - b) * (1 - r)
                        4 -> b * r
                        5 -> 1 - (1 - b) * (1 - r)
                        6 -> abs(b - r)
                        else -> r
                    }
                }
                if (mode == 1) {
                    // only the color: back to the picture's own brightness
                    val shiftL = luminance(base) - luminance(mixed)
                    for (k in 0..2) mixed[k] += shiftL
                }
                fun ch(k: Int) = ((base[k] + (mixed[k] - base[k]) * amount) * 255).roundToInt().coerceIn(0, 255)
                out.data[i] = (c and 0xFF000000.toInt()) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
        }
        return out
    }

    private fun softLight(b: Double) = if (b <= 0.25) ((16 * b - 12) * b + 4) * b else sqrt(b)

    private fun luminance(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
