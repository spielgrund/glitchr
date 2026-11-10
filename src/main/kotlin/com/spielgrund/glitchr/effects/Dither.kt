package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dithering on its own, in many kinds: ordered patterns (Bayer 2×2, 4×4, 8×8, halftone dots, lines,
 * blue and white noise) or the error spread to the neighbours (Floyd–Steinberg, Atkinson,
 * Jarvis–Judice–Ninke, Stucki, Burkes, Sierra, Sierra Lite). The colors: shades between two colors
 * (1 bit black and white at two levels), levels per RGB channel, colors found in the picture or a
 * retro palette. Before, the picture can be made coarser (big pixels, the pattern growing with them)
 * and its brightness and contrast set, which decide a lot with so few colors.
 */
object Dither : Effect("dither", "Dither", "Few colors mixed into patterns – Bayer, halftone, lines, noise or error diffusion like Floyd–Steinberg and Atkinson") {
    private val modes = listOf(
        "Bayer 2×2", "Bayer 4×4", "Bayer 8×8", "Halftone dots", "Lines", "Blue noise", "White noise",
        "Floyd–Steinberg", "Atkinson", "Jarvis–Judice–Ninke", "Stucki", "Burkes", "Sierra", "Sierra Lite",
    )
    private val palettes = listOf("Shades of two colors", "RGB levels", "From the picture", "Game Boy", "CGA", "EGA (16)", "C64", "PICO-8")
    private const val HALFTONE = 3
    private const val LINES = 4
    private const val BLUE = 5
    private const val WHITE = 6
    private const val FIRST_DIFFUSION = 7
    private const val SHADES = 0
    private const val RGB = 1
    private const val PICTURE = 2

    override val params = listOf(
        Param.Choice(
            "mode", "Dithering", modes, FIRST_DIFFUSION,
            "Bayer: a fixed grid pattern · Halftone dots: round dots growing with the dark, like print · Lines: lines getting thicker · Blue noise: even grain without a grid · White noise: rough grain · Floyd–Steinberg and the others: every pixel passes its rounding error on to its neighbours – Atkinson lighter and with more contrast, Jarvis, Stucki and Sierra smoother",
        ),
        Param.Slider("strength", "Strength", 0, 200, 100, " %", "How much the pattern or the passed-on error mixes the colors: 0 % just rounds to the nearest"),
        Param.Toggle("serpentine", "Serpentine", true, "Error diffusion: every other row runs backwards – fewer diagonal worms"),
        Param.Slider("cell", "Cell size", 2, 64, 6, " px", "Halftone dots and lines: their spacing, in (big) pixels").shownWhen("mode", HALFTONE, LINES),
        Param.Slider("angle", "Angle", 0, 180, 45, "°", "Halftone dots and lines").shownWhen("mode", HALFTONE, LINES),
        Param.Heading("colorsHeading", "Colors"),
        Param.Choice("palette", "Palette", palettes, SHADES, "Shades of two colors: in levels from dark to light – 2 levels is 1 bit · RGB levels: every channel on its own · From the picture: its main colors · retro palettes with their own colors"),
        Param.Slider("levels", "Levels", 2, 64, 2, "", "Shades, RGB levels and from the picture: how many"),
        Param.Color("dark", "Dark", 0x000000).shownWhen("palette", SHADES),
        Param.Color("light", "Light", 0xFFFFFF).shownWhen("palette", SHADES),
        Param.Heading("prepHeading", "Before"),
        Param.Slider("pixel", "Pixel size", 1, 64, 1, " px", "Makes the picture coarser first – the pattern grows with the pixels"),
        Param.Slider("brightness", "Brightness", -100, 100, 0, " %"),
        Param.Slider("contrast", "Contrast", -100, 100, 0, " %"),
    )

    /** The 2ⁿ×2ⁿ Bayer matrix, its thresholds 0..1. */
    private fun bayer(order: Int): DoubleArray {
        var m = intArrayOf(0)
        var size = 1
        repeat(order) {
            val next = IntArray(size * size * 4)
            val s2 = size * 2
            for (y in 0 until size) for (x in 0 until size) {
                val b = m[y * size + x] * 4
                next[y * s2 + x] = b
                next[y * s2 + x + size] = b + 2
                next[(y + size) * s2 + x] = b + 3
                next[(y + size) * s2 + x + size] = b + 1
            }
            m = next
            size = s2
        }
        return DoubleArray(m.size) { (m[it] + 0.5) / m.size }
    }

    private val bayers = listOf(bayer(1), bayer(2), bayer(3))

    /** A 64×64 blue noise tile, thresholds 0..1, made once (see [voidAndCluster]). */
    private val blueNoise by lazy { voidAndCluster(64) }

    /**
     * Blue noise by Ulichney's void-and-cluster: every cell gets a rank so that the first n cells are
     * always spread as evenly as possible – no grid, no clumps. The "energy" of a cell is how crowded
     * its surroundings are with set cells (a Gaussian, wrapping at the edges).
     */
    internal fun voidAndCluster(size: Int): DoubleArray {
        val n = size * size
        val sigma = 1.5
        val kernel = DoubleArray(n) { i ->
            val dx = min(i % size, size - i % size)
            val dy = min(i / size, size - i / size)
            kotlin.math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
        }
        val on = BooleanArray(n)
        val energy = DoubleArray(n)
        fun toggle(p: Int, set: Boolean) {
            on[p] = set
            val px = p % size
            val py = p / size
            val sign = if (set) 1 else -1
            for (y in 0 until size) {
                val ky = ((y - py) % size + size) % size * size
                for (x in 0 until size) energy[y * size + x] += sign * kernel[ky + ((x - px) % size + size) % size]
            }
        }
        fun tightest() = (0 until n).filter { on[it] }.maxBy { energy[it] }
        fun loosest() = (0 until n).filter { !on[it] }.minBy { energy[it] }

        // a random start, then made even: the most crowded set cell moves into the biggest gap
        val random = kotlin.random.Random(1)
        val ones = n / 10
        while (on.count { it } < ones) random.nextInt(n).let { if (!on[it]) toggle(it, true) }
        while (true) {
            val c = tightest()
            toggle(c, false)
            val v = loosest()
            if (v == c) { toggle(c, true); break }
            toggle(v, true)
        }
        val start = on.copyOf()
        val startEnergy = energy.copyOf()
        val rank = IntArray(n)
        // the start's cells ranked by taking the most crowded away, one after another
        for (k in ones - 1 downTo 0) { val c = tightest(); toggle(c, false); rank[c] = k }
        start.copyInto(on)
        startEnergy.copyInto(energy)
        // the rest by filling the biggest gap, one after another
        for (k in ones until n) { val v = loosest(); toggle(v, true); rank[v] = k }
        return DoubleArray(n) { (rank[it] + 0.5) / n }
    }

    /** Error diffusion kernels: (dx, dy, weight), the weights summing to the divisor (Atkinson passes on only 6/8). */
    private val kernels: List<Pair<List<Triple<Int, Int, Int>>, Int>> = listOf(
        listOf(Triple(1, 0, 7), Triple(-1, 1, 3), Triple(0, 1, 5), Triple(1, 1, 1)) to 16,
        listOf(Triple(1, 0, 1), Triple(2, 0, 1), Triple(-1, 1, 1), Triple(0, 1, 1), Triple(1, 1, 1), Triple(0, 2, 1)) to 8,
        listOf(
            Triple(1, 0, 7), Triple(2, 0, 5), Triple(-2, 1, 3), Triple(-1, 1, 5), Triple(0, 1, 7), Triple(1, 1, 5), Triple(2, 1, 3),
            Triple(-2, 2, 1), Triple(-1, 2, 3), Triple(0, 2, 5), Triple(1, 2, 3), Triple(2, 2, 1),
        ) to 48,
        listOf(
            Triple(1, 0, 8), Triple(2, 0, 4), Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 8), Triple(1, 1, 4), Triple(2, 1, 2),
            Triple(-2, 2, 1), Triple(-1, 2, 2), Triple(0, 2, 4), Triple(1, 2, 2), Triple(2, 2, 1),
        ) to 42,
        listOf(Triple(1, 0, 8), Triple(2, 0, 4), Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 8), Triple(1, 1, 4), Triple(2, 1, 2)) to 32,
        listOf(
            Triple(1, 0, 5), Triple(2, 0, 3), Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 5), Triple(1, 1, 4), Triple(2, 1, 2),
            Triple(-1, 2, 2), Triple(0, 2, 3), Triple(1, 2, 2),
        ) to 32,
        listOf(Triple(1, 0, 2), Triple(-1, 1, 1), Triple(0, 1, 1)) to 4,
    )

    /** Rounds a color to the palette: what it stands for per channel (for the error) and the color shown. */
    private class Quantizer(val palette: Int, val levels: Int, val dark: Int, val light: Int, val colors: IntArray) {
        private val lut = IntArray(64 * 64 * 64) { -1 }
        private val stepSize = 255.0 / (levels - 1)

        /** The gap between neighbouring colors, roughly: how far a pattern may push a pixel. */
        val step = when (palette) {
            SHADES, RGB -> stepSize
            else -> 255.0 / Math.cbrt(colors.size.toDouble()).coerceAtLeast(1.0)
        }

        /** Writes the represented r, g, b into [rep] and returns the color to show. */
        fun round(r: Double, g: Double, b: Double, rep: DoubleArray): Int = when (palette) {
            SHADES -> {
                val l = (0.299 * r + 0.587 * g + 0.114 * b).coerceIn(0.0, 255.0)
                val k = (l / stepSize).roundToInt()
                val q = k * stepSize
                rep[0] = q; rep[1] = q; rep[2] = q
                mix(dark, light, k / (levels - 1.0))
            }
            RGB -> {
                fun ch(c: Double) = ((c.coerceIn(0.0, 255.0) / stepSize).roundToInt() * stepSize)
                rep[0] = ch(r); rep[1] = ch(g); rep[2] = ch(b)
                (rep[0].roundToInt() shl 16) or (rep[1].roundToInt() shl 8) or rep[2].roundToInt()
            }
            else -> {
                val c = colors[nearest(r.roundToInt(), g.roundToInt(), b.roundToInt())]
                rep[0] = (c shr 16 and 0xFF).toDouble(); rep[1] = (c shr 8 and 0xFF).toDouble(); rep[2] = (c and 0xFF).toDouble()
                c
            }
        }

        private fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).roundToInt()
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** The nearest palette color, per 6-bit cell, filled in as needed (the same answer from every thread). */
        private fun nearest(r: Int, g: Int, b: Int): Int {
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
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val px = v["pixel"]
        // the (coarser) picture to work on, its channels after brightness and contrast
        val sw = (w + px - 1) / px
        val sh = (h + px - 1) / px
        val r = DoubleArray(sw * sh)
        val g = DoubleArray(sw * sh)
        val b = DoubleArray(sw * sh)
        val a = IntArray(sw * sh)
        val bright = v["brightness"] / 100.0 * 255
        val contrast = v["contrast"] / 100.0
        val gain = if (contrast >= 0) 1 / max(0.01, 1 - contrast) else 1 + contrast
        fun tone(c: Double) = (c - 127.5) * gain + 127.5 + bright
        parallelRows(sh) { y ->
            for (x in 0 until sw) {
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var sa = 0; var n = 0
                for (yy in y * px until min(h, y * px + px)) for (xx in x * px until min(w, x * px + px)) {
                    val c = src.data[yy * w + xx]
                    sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF; sa += c ushr 24; n++
                }
                val i = y * sw + x
                r[i] = tone(sr / n); g[i] = tone(sg / n); b[i] = tone(sb / n); a[i] = sa / n
            }
        }

        val palette = v["palette"]
        val levels = v["levels"]
        val colors = when (palette) {
            SHADES, RGB -> IntArray(0)
            PICTURE -> dominantColors(src, levels, seed).map { it and 0xFFFFFF }.distinct().toIntArray()
            else -> ReduceColors.fixed.getValue(palette + 1)
        }
        val q = Quantizer(palette, levels, v["dark"], v["light"], colors)
        val strength = v["strength"] / 100.0
        val mode = v["mode"]
        val small = IntArray(sw * sh)

        if (mode >= FIRST_DIFFUSION) {
            val (kernel, divisor) = kernels[mode - FIRST_DIFFUSION]
            val serpentine = v.bool("serpentine")
            val rep = DoubleArray(3)
            for (y in 0 until sh) {
                val back = serpentine && y % 2 == 1
                for (step in 0 until sw) {
                    val x = if (back) sw - 1 - step else step
                    val i = y * sw + x
                    small[i] = q.round(r[i], g[i], b[i], rep)
                    val er = (r[i].coerceIn(0.0, 255.0) - rep[0]) * strength / divisor
                    val eg = (g[i].coerceIn(0.0, 255.0) - rep[1]) * strength / divisor
                    val eb = (b[i].coerceIn(0.0, 255.0) - rep[2]) * strength / divisor
                    for ((dx, dy, f) in kernel) {
                        val xx = if (back) x - dx else x + dx
                        val yy = y + dy
                        if (xx < 0 || xx >= sw || yy >= sh) continue
                        val j = yy * sw + xx
                        r[j] += er * f; g[j] += eg * f; b[j] += eb * f
                    }
                }
            }
        } else {
            val cell = v["cell"].toDouble()
            val angle = Math.toRadians(v["angle"].toDouble())
            val ca = cos(angle)
            val sa = sin(angle)
            parallelRows(sh) { y ->
                val rep = DoubleArray(3)
                for (x in 0 until sw) {
                    // the threshold here, 0..1
                    val t = when (mode) {
                        0, 1, 2 -> bayers[mode].let { m -> val s = 2 shl mode; m[(y % s) * s + x % s] }
                        HALFTONE -> {
                            // a round spot in every cell of a turned grid: low in the middle, so dots grow with the dark
                            val u = (x * ca + y * sa) / cell
                            val vv = (-x * sa + y * ca) / cell
                            0.5 - (cos(2 * PI * u) + cos(2 * PI * vv)) / 4
                        }
                        LINES -> {
                            val u = (-x * sa + y * ca) / cell
                            abs1(2 * (u - floor(u)) - 1)
                        }
                        // the tile shifted by the seed, so Reroll gives another grain
                        BLUE -> blueNoise[((y + (seed ushr 8).toInt()) and 63) * 64 + ((x + seed.toInt()) and 63)]
                        else -> hash(x, y, seed)
                    }
                    val o = (t - 0.5) * q.step * strength
                    val i = y * sw + x
                    small[i] = q.round(r[i] + o, g[i] + o, b[i] + o, rep)
                }
            }
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val row = y / px * sw
            for (x in 0 until w) {
                val i = row + x / px
                out.data[y * w + x] = (a[i] shl 24) or small[i]
            }
        }
        return out
    }

    private fun abs1(x: Double) = if (x < 0) -x else x

    private fun hash(x: Int, y: Int, seed: Long): Double {
        var k = x * 374761393 + y * 668265263 + seed.toInt() * 1442695041
        k = (k xor (k ushr 13)) * 1274126177
        return ((k xor (k ushr 16)) and 0xFFFF) / 65535.0
    }
}
