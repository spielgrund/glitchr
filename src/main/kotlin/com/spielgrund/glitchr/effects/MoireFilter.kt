package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Puts moiré into a picture the ways it happens for real, or brings out moiré that is
 * already there:
 *
 * - Photographed screen: the picture shown on a screen of RGB subpixels, photographed
 *   by a camera whose pixel grid is slightly turned and scaled against it.
 * - Halftone: printed as a halftone (dots, lines, grid or rings); in color with one
 *   screen per ink (cyan, magenta, yellow) at the classic angles, slightly different
 *   screen widths make them beat. Without smoothing the fine screen aliases on top.
 * - Overlay grid: a fine line grating laid over the picture (multiplied); fine
 *   textures in the picture beat against it. A second grating with a different width
 *   adds a moiré of its own; in color the gratings are shifted per channel.
 * - Aliasing: the picture sampled on a turned grid without filtering, then smoothly
 *   enlarged again – fine textures fold into coarse moiré, like a careless downscale.
 * - Amplify existing moiré: a band-pass between the grid width (removes the fine
 *   lines) and the band width (the size of the moiré bands) is amplified and added back;
 *   "Moiré only" leaves the fine lines out.
 */
object MoireFilter : Effect("moirefilter", "Moiré filter", "Adds moiré (photographed screen, halftone, overlaid grid, aliasing) or amplifies existing moiré") {
    private val modes = listOf(
        "Photographed screen", "Halftone", "Overlay grid", "Aliasing", "Amplify existing moiré",
    )

    override val params = listOf(
        Param.Choice("mode", "Type", modes),
        Param.Heading("screenHeading", "Grid"),
        Param.Slider(
            "pitch", "Grid pitch", 10, 400, 30, " px", decimals = 1,
            tip = "Screen pixel, halftone cell, line spacing or sampling step. Amplify: lines finer than this are removed",
        ),
        Param.Slider(
            "angle", "Twist", -4500, 4500, 150, "°", decimals = 2,
            tip = "Angle of the grid against the picture (screen: of the camera against the screen) – small angles give wide moiré bands",
        ),
        Param.Slider(
            "deviation", "Deviation", -300, 300, 40, " %", decimals = 1,
            tip = "Screen: scale of the camera against the screen. Halftone (colored): difference of the screen rulings of the inks. " +
                "Overlay grid: pitch of a second grid (0 = none)",
        ),
        Param.Choice("pattern", "Grid shape", listOf("Dots", "Lines", "Grid", "Rings"), tip = "For halftone and overlay grid"),
        Param.Slider("width", "Line width", 5, 95, 50, " %", "Overlay grid: share of the line in the spacing"),
        Param.Toggle("color", "Colored", true, "Screen: RGB subpixels. Halftone: one screen per ink. Grid: offset per channel – rainbow-colored moiré"),
        Param.Toggle("smooth", "Anti-aliasing", false, "Off: the grid is sampled hard and adds aliasing moiré on top"),
        Param.Slider("softness", "Softness", 0, 20, 0, " px", "Blurs afterwards, like a slightly unsharp camera"),
        Param.Heading("amplifyHeading", "Amplify"),
        Param.Slider("band", "Band width", 3, 300, 40, " px", "Size of the moiré bands that are amplified"),
        Param.Slider("gain", "Gain", 0, 1000, 300, " %"),
        Param.Toggle("onlyMoire", "Moiré only", false, "Leave out the fine lines, show only the (amplified) moiré bands"),
        Param.Heading("mixHeading", "Mix"),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mode = v["mode"]
        var out = when (mode) {
            1 -> halftone(src, v)
            2 -> grating(src, v)
            3 -> aliasing(src, v)
            4 -> amplify(src, v)
            else -> screen(src, v)
        }
        val softness = v["softness"]
        if (softness > 0 && mode != 4) out = Channels.of(out).blur(softness).toPixels(out)
        val amount = v["amount"] / 100f
        if (amount >= 1f) return out
        return Pixels(src.width, src.height, IntArray(src.data.size) { i ->
            val a = src.data[i]
            val b = out.data[i]
            fun mix(x: Int, y: Int) = (x + (y - x) * amount + 0.5f).toInt()
            (a and 0xFF000000.toInt()) or (mix(red(a), red(b)) shl 16) or (mix(green(a), green(b)) shl 8) or mix(blue(a), blue(b))
        })
    }

    /** Turns ([dx], [dy]) by [ca]/[sa] (cosine and sine of the angle). */
    private inline fun <T> turned(dx: Double, dy: Double, ca: Double, sa: Double, f: (Double, Double) -> T) =
        f(dx * ca + dy * sa, -dx * sa + dy * ca)

    private fun angle(v: Values) = v["angle"] / 100.0 * PI / 180

    /** Nearest pixel of [src] at ([x], [y]), clamped to the picture. */
    private fun at(src: Pixels, x: Double, y: Double) =
        src.data[y.toInt().coerceIn(0, src.height - 1) * src.width + x.toInt().coerceIn(0, src.width - 1)]

    /** Keeps the alpha of [c] with new [r], [g], [b] (0..255, clamped). */
    private fun rgb(c: Int, r: Double, g: Double, b: Double) =
        (c and 0xFF000000.toInt()) or (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    /**
     * The picture on a screen (every screen pixel showing a [pitch]-sized block of it as
     * three RGB stripes in a black matrix), taken by a camera that is turned and scaled
     * against it; every camera pixel averages 3×3 points of the screen.
     */
    private fun screen(src: Pixels, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val pitch = v["pitch"] / 10.0
        val a = angle(v)
        val ca = cos(a)
        val sa = sin(a)
        val scale = 1 + v["deviation"] / 1000.0
        val color = v.bool("color")
        // lit part of a screen pixel: stripes 80 % of their third wide, rows 84 % high
        val lit = if (color) 0.8 / 3 * 0.84 else 0.84 * 0.84
        val gain = 1 / lit
        val n = 3
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (sy in 0 until n) for (sx in 0 until n) {
                    turned(x + (sx + 0.5) / n - w / 2.0, y + (sy + 0.5) / n - h / 2.0, ca, sa) { u, t ->
                        val px = u * scale / pitch + w / 2.0 / pitch
                        val py = t * scale / pitch + h / 2.0 / pitch
                        val col = floor(px)
                        val row = floor(py)
                        val fy = py - row
                        if (fy < 0.08 || fy > 0.92) return@turned
                        val c = at(src, (col + 0.5) * pitch, (row + 0.5) * pitch)
                        val fx = px - col
                        if (color) {
                            val sub = (fx * 3).toInt().coerceIn(0, 2)
                            val inStripe = fx * 3 - sub
                            if (inStripe < 0.1 || inStripe > 0.9) return@turned
                            when (sub) {
                                0 -> r += red(c)
                                1 -> g += green(c)
                                else -> b += blue(c)
                            }
                        } else {
                            if (fx < 0.08 || fx > 0.92) return@turned
                            r += red(c); g += green(c); b += blue(c)
                        }
                    }
                }
                val k = gain / (n * n)
                out.data[y * w + x] = rgb(src.data[y * w + x], r * k, g * k, b * k)
            }
        }
        return out
    }

    /**
     * Spot function of a halftone screen at screen position ([u], [v]) in cells: 0 where
     * the first ink goes, 1 where the last goes. An ink coverage c inks where spot < c.
     */
    private fun spot(pattern: Int, u: Double, v: Double): Double {
        fun line(t: Double) = abs(t - floor(t + 0.5)) * 2
        return when (pattern) {
            1 -> line(v)
            2 -> min(line(u), line(v)) * 2
            3 -> line(hypot(u, v))
            else -> 0.5 - (cos(2 * PI * u) + cos(2 * PI * v)) / 4 // round dots that grow into a checkerboard
        }
    }

    /** Halftone print: black on white, or cyan, magenta and yellow each with its own screen. */
    private fun halftone(src: Pixels, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val pitch = v["pitch"] / 10.0
        val base = angle(v)
        val dev = v["deviation"] / 1000.0
        val pattern = v["pattern"]
        val color = v.bool("color")
        // classic screen angles for cyan, magenta, yellow (and black alone)
        val angles = if (color) doubleArrayOf(15.0, 75.0, 0.0) else doubleArrayOf(45.0)
        val cosines = DoubleArray(angles.size) { cos(base + angles[it] * PI / 180) }
        val sines = DoubleArray(angles.size) { sin(base + angles[it] * PI / 180) }
        val pitches = DoubleArray(angles.size) { pitch * (1 + dev * (it - 1)) }
        val n = if (v.bool("smooth")) 3 else 1
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val c = src.data[y * w + x]
                // ink coverage per screen: 1 - channel (CMY) or 1 - brightness
                val inks = if (color) doubleArrayOf(1 - red(c) / 255.0, 1 - green(c) / 255.0, 1 - blue(c) / 255.0)
                else doubleArrayOf(1 - luma(c) / 255.0)
                val paper = DoubleArray(3)
                for (sy in 0 until n) for (sx in 0 until n) {
                    val dx = x + (sx + 0.5) / n - w / 2.0
                    val dy = y + (sy + 0.5) / n - h / 2.0
                    for (s in inks.indices) {
                        val inked = turned(dx, dy, cosines[s], sines[s]) { u, t -> spot(pattern, u / pitches[s], t / pitches[s]) < inks[s] }
                        if (!inked) {
                            if (color) paper[s] += 1.0 else { paper[0] += 1.0; paper[1] += 1.0; paper[2] += 1.0 }
                        }
                    }
                }
                val k = 255.0 / (n * n)
                out.data[y * w + x] = rgb(c, paper[0] * k, paper[1] * k, paper[2] * k)
            }
        }
        return out
    }

    /** A fine grating multiplied over the picture; optionally a second one with another width. */
    private fun grating(src: Pixels, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val pitch = v["pitch"] / 10.0
        val a = angle(v)
        val ca = cos(a)
        val sa = sin(a)
        val dev = v["deviation"] / 1000.0
        val second = if (dev != 0.0) pitch * (1 + dev) else 0.0
        val pattern = v["pattern"]
        val width = v["width"] / 100.0
        val color = v.bool("color")
        val n = if (v.bool("smooth")) 3 else 1

        /** Whether the grating of cell size [p] has a line at pattern position ([u], [t]), shifted by [shift] cells. */
        fun line(u: Double, t: Double, p: Double, shift: Double) = when (pattern) {
            0 -> spot(0, u / p + shift, t / p + shift) < width * 0.8
            else -> spot(pattern, u / p + shift, t / p + shift) < width
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val c = src.data[y * w + x]
                val light = DoubleArray(3)
                for (sy in 0 until n) for (sx in 0 until n) {
                    val dx = x + (sx + 0.5) / n - w / 2.0
                    val dy = y + (sy + 0.5) / n - h / 2.0
                    for (ch in 0 until 3) {
                        // in color every channel's grating is shifted by a third of a cell
                        val shift = if (color) ch / 3.0 else 0.0
                        var open = turned(dx, dy, ca, sa) { u, t -> !line(u, t, pitch, shift) }
                        if (open && second > 0) open = !line(dx, dy, second, shift)
                        if (open) light[ch] += 1.0
                    }
                }
                val k = 1.0 / (n * n)
                out.data[y * w + x] = rgb(c, red(c) * light[0] * k, green(c) * light[1] * k, blue(c) * light[2] * k)
            }
        }
        return out
    }

    /** Samples the picture on a turned grid without filtering and enlarges it smoothly again. */
    private fun aliasing(src: Pixels, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val pitch = v["pitch"] / 10.0 * (1 + v["deviation"] / 1000.0)
        val a = angle(v)
        val ca = cos(a)
        val sa = sin(a)
        val cx = w / 2.0
        val cy = h / 2.0

        /** The picture at grid node ([i], [j]): a single pixel, no averaging – that is what folds fine detail. */
        fun node(i: Int, j: Int): Int {
            val gu = i * pitch
            val gt = j * pitch
            // back from the turned grid to the picture
            return at(src, cx + gu * ca - gt * sa, cy + gu * sa + gt * ca)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                turned(x + 0.5 - cx, y + 0.5 - cy, ca, sa) { u, t ->
                    val gu = u / pitch
                    val gt = t / pitch
                    val i = floor(gu).toInt()
                    val j = floor(gt).toInt()
                    val fu = gu - i
                    val ft = gt - j
                    val c00 = node(i, j); val c10 = node(i + 1, j); val c01 = node(i, j + 1); val c11 = node(i + 1, j + 1)
                    fun ch(f: (Int) -> Int) =
                        (f(c00) * (1 - fu) + f(c10) * fu) * (1 - ft) + (f(c01) * (1 - fu) + f(c11) * fu) * ft
                    out.data[y * w + x] = rgb(src.data[y * w + x], ch(::red), ch(::green), ch(::blue))
                }
            }
        }
        return out
    }

    /**
     * Band-pass between the grid width and the band width, amplified: the moiré bands
     * stand out, the fine lines stay (or are left out with "Moiré only").
     */
    private fun amplify(src: Pixels, v: Values): Pixels {
        val channels = Channels.of(src)
        val fine = max(1, (v["pitch"] / 10.0).roundToInt())
        val lowPass = channels.blur(fine)
        val coarse = lowPass.blur(max(fine + 1, v["band"]))
        val gain = v["gain"] / 100.0
        val only = v.bool("onlyMoire")
        val out = Pixels(src.width, src.height)
        for (i in src.data.indices) {
            fun ch(k: Int): Double {
                val band = lowPass.c[k][i] - coarse.c[k][i]
                return (if (only) lowPass.c[k][i] else channels.c[k][i]) + band * gain
            }
            out.data[i] = rgb(src.data[i], ch(0), ch(1), ch(2))
        }
        return out
    }

    /** The three color channels as floats, for blurring. */
    private class Channels(val w: Int, val h: Int, val c: Array<FloatArray>) {
        companion object {
            fun of(p: Pixels) = Channels(
                p.width, p.height,
                arrayOf(
                    FloatArray(p.data.size) { red(p.data[it]).toFloat() },
                    FloatArray(p.data.size) { green(p.data[it]).toFloat() },
                    FloatArray(p.data.size) { blue(p.data[it]).toFloat() },
                ),
            )
        }

        /** Three box blurs with radius [r] (close to a Gaussian); edges repeat. */
        fun blur(r: Int): Channels = Channels(w, h, Array(3) { k ->
            var a = c[k]
            repeat(3) { a = box(a, r) }
            a
        })

        private fun box(src: FloatArray, r: Int): FloatArray {
            val tmp = FloatArray(src.size)
            val out = FloatArray(src.size)
            val size = 2f * r + 1
            parallelRows(h) { y ->
                var sum = 0f
                for (i in -r..r) sum += src[y * w + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = sum / size
                    sum += src[y * w + (x + r + 1).coerceIn(0, w - 1)] - src[y * w + (x - r).coerceIn(0, w - 1)]
                }
            }
            parallelRows(w) { x ->
                var sum = 0f
                for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = sum / size
                    sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
                }
            }
            return out
        }

        /** Back to pixels, with the alpha of [like]. */
        fun toPixels(like: Pixels) = Pixels(w, h, IntArray(w * h) { i ->
            (like.data[i] and 0xFF000000.toInt()) or
                (c[0][i].roundToInt().coerceIn(0, 255) shl 16) or
                (c[1][i].roundToInt().coerceIn(0, 255) shl 8) or
                c[2][i].roundToInt().coerceIn(0, 255)
        })
    }
}
