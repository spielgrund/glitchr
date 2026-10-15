package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.parallelRows

/**
 * Reduces the picture to a few colors: found in the picture itself, greys, steps of a
 * gradient or a classic computer palette (Game Boy, CGA, EGA, C64, PICO-8). Dithering
 * mixes the few colors into finer gradients – as a steady pattern, by spreading the
 * error (Floyd–Steinberg, Atkinson) or as noise.
 */
object ReduceColors : Effect("reducecolors", "Reduce colors", "Reduces the picture to a few colors – from the picture, greys, a gradient or retro palettes – with dithering") {
    private val palettes = listOf(
        "From the picture", "Greys", "Gradient", "Black and white", "Game Boy", "CGA", "EGA (16)", "C64", "PICO-8",
    )
    private val dithers = listOf("None", "Ordered (Bayer)", "Floyd–Steinberg", "Atkinson", "Noise")

    private val fixed = mapOf(
        3 to intArrayOf(0x000000, 0xFFFFFF),
        4 to intArrayOf(0x0F380F, 0x306230, 0x8BAC0F, 0x9BBC0F),
        5 to intArrayOf(0x000000, 0x55FFFF, 0xFF55FF, 0xFFFFFF),
        6 to intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        ),
        7 to intArrayOf(
            0x000000, 0xFFFFFF, 0x68372B, 0x70A4B2, 0x6F3D86, 0x588D43, 0x352879, 0xB8C76F,
            0x6F4F25, 0x433900, 0x9A6759, 0x444444, 0x6C6C6C, 0x9AD284, 0x6C5EB5, 0x959595,
        ),
        8 to intArrayOf(
            0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
            0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA,
        ),
    )

    override val params = listOf(
        Param.Choice("palette", "Palette", palettes, 0, "Where the colors come from; the retro palettes have their own number of colors"),
        Param.Slider("colors", "Colors", 2, 256, 8, tip = "From the picture, greys and gradient: how many colors"),
        Param.Ramp("ramp", "Gradient", RampPalette.SUNSET.ramp.format(), "Palette “Gradient”: its colors in even steps"),
        Param.Choice("dither", "Dithering", dithers, 1, "Mixes the few colors into finer gradients"),
        Param.Slider("ditherStrength", "Dithering strength", 0, 200, 100, " %"),
        Param.Slider("ditherScale", "Pattern size", 1, 16, 1, " px", "Ordered and noise: size of a pattern dot"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val n = v["colors"]
        val colors = when (val p = v["palette"]) {
            0 -> dominantColors(src, n, seed)
            1 -> IntArray(n) { val g = it * 255 / (n - 1); (g shl 16) or (g shl 8) or g }
            2 -> ColorRamp.parse(v.text("ramp")).let { r -> IntArray(n) { r.colorAt(it / (n - 1.0)) } }
            else -> fixed.getValue(p)
        }.map { it and 0xFFFFFF }.distinct().toIntArray()
        val dither = v["dither"]
        val strength = v["ditherStrength"] / 100.0
        val scale = v["ditherScale"]
        // the gap between neighbouring colors, roughly: how far a pattern may push a pixel
        val step = 255.0 / Math.cbrt(colors.size.toDouble()).coerceAtLeast(1.0) * strength
        val w = src.width
        val h = src.height

        // nearest color per 6-bit cell, filled in as needed (the same answer from every thread)
        val lut = IntArray(64 * 64 * 64) { -1 }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val cell = ((r.coerceIn(0, 255) shr 2) shl 12) or ((g.coerceIn(0, 255) shr 2) shl 6) or (b.coerceIn(0, 255) shr 2)
            val known = lut[cell]
            if (known >= 0) return known
            val cr = (cell shr 12) * 4 + 2
            val cg = (cell shr 6 and 63) * 4 + 2
            val cb = (cell and 63) * 4 + 2
            var best = 0
            var bestD = Int.MAX_VALUE
            for ((i, c) in colors.withIndex()) {
                val dr = cr - (c shr 16 and 0xFF)
                val dg = cg - (c shr 8 and 0xFF)
                val db = cb - (c and 0xFF)
                // weighted like the eye: green counts most, blue least
                val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                if (d < bestD) { bestD = d; best = i }
            }
            lut[cell] = best
            return best
        }

        val out = Pixels(w, h)
        when (dither) {
            2, 3 -> {
                // error diffusion, row by row; per channel as float
                val er = FloatArray(w * h)
                val eg = FloatArray(w * h)
                val eb = FloatArray(w * h)
                val atkinson = dither == 3
                val s = strength.toFloat()
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val c = src.data[i]
                    val r = (c shr 16 and 0xFF) + er[i]
                    val g = (c shr 8 and 0xFF) + eg[i]
                    val b = (c and 0xFF) + eb[i]
                    val p = colors[nearest(r.toInt(), g.toInt(), b.toInt())]
                    out.data[i] = (c and 0xFF000000.toInt()) or p
                    val dr = (r.coerceIn(0f, 255f) - (p shr 16 and 0xFF)) * s
                    val dg = (g.coerceIn(0f, 255f) - (p shr 8 and 0xFF)) * s
                    val db = (b.coerceIn(0f, 255f) - (p and 0xFF)) * s
                    fun spread(dx: Int, dy: Int, f: Float) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || xx >= w || yy >= h) return
                        val j = yy * w + xx
                        er[j] += dr * f; eg[j] += dg * f; eb[j] += db * f
                    }
                    if (atkinson) {
                        // only 6/8 of the error: lighter, more contrast
                        for ((dx, dy) in listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2)) spread(dx, dy, 1f / 8)
                    } else {
                        spread(1, 0, 7f / 16); spread(-1, 1, 3f / 16); spread(0, 1, 5f / 16); spread(1, 1, 1f / 16)
                    }
                }
            }
            else -> parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = src.data[i]
                    val o = when (dither) {
                        1 -> ((BAYER[((y / scale) and 7) * 8 + ((x / scale) and 7)] + 0.5) / 64 - 0.5) * step
                        4 -> (unitHash(x / scale, y / scale, seed) - 0.5) * step
                        else -> 0.0
                    }.toInt()
                    out.data[i] = (c and 0xFF000000.toInt()) or colors[nearest((c shr 16 and 0xFF) + o, (c shr 8 and 0xFF) + o, (c and 0xFF) + o)]
                }
            }
        }
        return out
    }

    private val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
    )

    private fun unitHash(x: Int, y: Int, seed: Long): Double {
        var h = x * 374761393 + y * 668265263 + seed.toInt() * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }
}
