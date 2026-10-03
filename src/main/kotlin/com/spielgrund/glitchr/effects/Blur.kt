package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Blur: Gaussian, box, directional (motion), radial (zoom) and spin. It can blur all
 * channels, a single one, only the brightness or only the color. The data errors imitate
 * a blur that goes wrong in memory: sums overflow, rows are read with the wrong length,
 * bits flip, or the running sum isn't reset between rows.
 */
object Blur : Effect("blur", "Blur", "Blur: Gauss, box, directional, radial and spin – also for single channels and with data errors") {
    private val types = listOf("Gauss", "Box", "Direction", "Radial (zoom)", "Spin")
    private val channels = listOf("All channels", "Red", "Green", "Blue", "Alpha", "Brightness", "Color")
    private val errors = listOf("None", "Overflow", "Row width", "Bit errors", "Carry-over")

    override val params = listOf(
        Param.Choice("type", "Type", types),
        Param.Slider("radius", "Radius", 0, 500, 12, " px", "Strength of the blur; for radial and spin its length at the picture edge"),
        Param.Slider("angle", "Angle", 0, 359, 0, "°", "For “Direction”"),
        Param.Slider("centerX", "Center X", 0, 100, 50, " %", "For radial and spin"),
        Param.Slider("centerY", "Center Y", 0, 100, 50, " %", "For radial and spin"),
        Param.Choice("channel", "Channel", channels, tip = "Which channels are blurred; the others stay sharp"),
        Param.Choice(
            "error", "Data errors", errors,
            tip = "Overflow: sums overflow and wrap around · Row width: the data is read with the wrong row length · " +
                "Bit errors: single bits flip in stripes · Carry-over: the running sum is not reset between rows",
        ),
        Param.Slider("errorAmount", "Error strength", 0, 100, 50, " %"),
    )

    private const val ALL = 0
    private const val ALPHA = 4
    private const val LUMA = 5
    private const val CHROMA = 6

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val channel = v["channel"]
        val error = v["error"]
        val amount = v["errorAmount"] / 100.0
        val ycc = channel == LUMA || channel == CHROMA
        // planes 0..2 are RGB (premultiplied when all channels blur) or YCbCr, plane 3 is alpha
        val selected = BooleanArray(4) { p ->
            when (channel) {
                ALL -> true
                in 1..3 -> p == channel - 1
                ALPHA -> p == 3
                LUMA -> p == 0
                else -> p == 1 || p == 2
            }
        }
        val premultiplied = channel == ALL
        val planes = decode(src.data, w, h, ycc, premultiplied)
        if (error == 2 && amount > 0) {
            // the blurred data is read with a wrong row length: it shears diagonally
            val extra = 1 + (amount * w * 0.08).roundToInt()
            val size = w * h
            val sheared = IntArray(size) { i -> src.data[Math.floorMod(i / w * (w + extra) + i % w, size)] }
            val wrong = decode(sheared, w, h, ycc, premultiplied)
            for (p in 0..3) if (selected[p]) planes[p] = wrong[p]
        }

        val type = v["type"]
        val radius = v["radius"]
        for (p in 0..3) if (selected[p]) planes[p] = when (type) {
            0 -> gauss(planes[p], w, h, radius / 2.0)
            1 -> boxV(boxH(planes[p], w, h, radius), w, h, radius)
            else -> sampled(planes[p], w, h, type, radius, v)
        }

        // the errors hit only the blurred data; the alpha of a full blur stays intact
        val hit = BooleanArray(4) { selected[it] && !(premultiplied && it == 3) }
        if (error == 4 && amount > 0) for (p in 0..3) if (hit[p]) carry(planes[p], w, h, amount)
        val wrap = error == 1 || error == 4
        val gain = if (error == 1) 1 + amount * 3 else 1.0
        val out = encode(planes, w, h, ycc, premultiplied, hit, wrap, gain)
        if (error == 3 && amount > 0) {
            // in YCbCr the planes are mixed back into RGB: then the bits flip in all color channels
            val shifts = if (ycc) listOf(16, 8, 0) else (0..3).filter { hit[it] }.map { if (it == 3) 24 else 16 - 8 * it }
            flipBits(out, w, h, shifts, amount, radius, seed)
        }
        return Pixels(w, h, out)
    }

    private fun decode(data: IntArray, w: Int, h: Int, ycc: Boolean, premultiplied: Boolean): Array<FloatArray> {
        val planes = Array(4) { FloatArray(w * h) }
        parallelRows(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = data[i]
                val a = alpha(c).toFloat()
                var r = red(c).toFloat()
                var g = green(c).toFloat()
                var b = blue(c).toFloat()
                if (ycc) {
                    planes[0][i] = 0.299f * r + 0.587f * g + 0.114f * b
                    planes[1][i] = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
                    planes[2][i] = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
                } else {
                    if (premultiplied) { r *= a / 255; g *= a / 255; b *= a / 255 }
                    planes[0][i] = r; planes[1][i] = g; planes[2][i] = b
                }
                planes[3][i] = a
            }
        }
        return planes
    }

    private fun encode(
        planes: Array<FloatArray>, w: Int, h: Int, ycc: Boolean, premultiplied: Boolean,
        hit: BooleanArray, wrap: Boolean, gain: Double,
    ): IntArray {
        val out = IntArray(w * h)
        parallelRows(h) { y ->
            val c = FloatArray(4)
            for (i in y * w until (y + 1) * w) {
                for (p in 0..3) {
                    var value = planes[p][i].toDouble()
                    if (wrap && hit[p]) {
                        value = Math.floorMod((value * gain).roundToInt(), 256).toDouble()
                    }
                    c[p] = value.toFloat()
                }
                val a = c[3]
                var r: Float
                var g: Float
                var b: Float
                if (ycc) {
                    r = c[0] + 1.402f * (c[2] - 128)
                    g = c[0] - 0.344136f * (c[1] - 128) - 0.714136f * (c[2] - 128)
                    b = c[0] + 1.772f * (c[1] - 128)
                } else {
                    r = c[0]; g = c[1]; b = c[2]
                    if (premultiplied) {
                        val k = if (a > 0.5f) 255 / a else 0f
                        r *= k; g *= k; b *= k
                    }
                }
                out[i] = argb(clamp(a), clamp(r), clamp(g), clamp(b))
            }
        }
        return out
    }

    private fun clamp(v: Float) = v.roundToInt().coerceIn(0, 255)

    /** Gaussian blur as three box blurs. */
    internal fun gauss(a: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        if (sigma < 0.5) return a
        var p = a
        for (r in boxesForGauss(sigma)) p = boxV(boxH(p, w, h, r), w, h, r)
        return p
    }

    private fun boxesForGauss(sigma: Double): IntArray {
        val n = 3
        var wl = floor(sqrt(12 * sigma * sigma / n + 1)).toInt()
        if (wl % 2 == 0) wl--
        val m = ((12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4.0 * wl - 4)).roundToInt()
        return IntArray(n) { if (it < m) (wl - 1) / 2 else (wl + 1) / 2 }
    }

    private fun boxH(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return a
        val out = FloatArray(a.size)
        val norm = 1.0 / (2 * r + 1)
        parallelRows(h) { y ->
            val row = y * w
            var sum = 0.0
            for (i in -r..r) sum += a[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                out[row + x] = (sum * norm).toFloat()
                sum += a[row + min(x + r + 1, w - 1)] - a[row + max(x - r, 0)]
            }
        }
        return out
    }

    private fun boxV(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return a
        val out = FloatArray(a.size)
        val norm = 1.0 / (2 * r + 1)
        parallelRows(w) { x ->
            var sum = 0.0
            for (i in -r..r) sum += a[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum * norm).toFloat()
                sum += a[min(y + r + 1, h - 1) * w + x] - a[max(y - r, 0) * w + x]
            }
        }
        return out
    }

    /** Directional, radial and spin blur: averages samples along a line or arc through each pixel. */
    private fun sampled(a: FloatArray, w: Int, h: Int, type: Int, radius: Int, v: Values): FloatArray {
        if (radius <= 0) return a
        val n = min(2 * radius + 1, 97)
        val angle = Math.toRadians(v["angle"].toDouble())
        val dx = cos(angle)
        val dy = sin(angle)
        val cx = v["centerX"] / 100.0 * w
        val cy = v["centerY"] / 100.0 * h
        // radial and spin: the blur is [radius] pixels long at the farthest corner
        val far = hypot(max(cx, w - cx), max(cy, h - cy)).coerceAtLeast(1.0)
        val strength = radius / far
        val out = FloatArray(a.size)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var sum = 0.0
                for (i in 0 until n) {
                    val t = i.toDouble() / (n - 1) - 0.5
                    val sx: Double
                    val sy: Double
                    when (type) {
                        2 -> { sx = x + dx * 2 * radius * t; sy = y + dy * 2 * radius * t }
                        3 -> {
                            val f = 1 + strength * t
                            sx = cx + (x + 0.5 - cx) * f - 0.5; sy = cy + (y + 0.5 - cy) * f - 0.5
                        }
                        else -> {
                            val turn = strength * t
                            val c = cos(turn)
                            val s = sin(turn)
                            val px = x + 0.5 - cx
                            val py = y + 0.5 - cy
                            sx = cx + px * c - py * s - 0.5; sy = cy + px * s + py * c - 0.5
                        }
                    }
                    sum += bilinear(a, w, h, sx, sy)
                }
                out[y * w + x] = (sum / n).toFloat()
            }
        }
        return out
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
        val fx = x.coerceIn(0.0, w - 1.0)
        val fy = y.coerceIn(0.0, h - 1.0)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx
        val bottom = a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    /**
     * The running sum is not reset at the start of a row: each row starts where the one
     * before ended, and the difference is dragged along the whole row, piling up downwards.
     */
    private fun carry(a: FloatArray, w: Int, h: Int, amount: Double) {
        var offset = 0.0
        var previousEnd = a[w - 1]
        for (y in 1 until h) {
            val row = y * w
            // the leftover of the previous row, measured before it was shifted itself
            offset += amount * (previousEnd - a[row])
            previousEnd = a[row + w - 1]
            for (x in 0 until w) a[row + x] += offset.toFloat()
        }
    }

    /** Bits flip in streaks: runs along a row where one bit of the blurred channel is inverted. */
    private fun flipBits(out: IntArray, w: Int, h: Int, shifts: List<Int>, amount: Double, radius: Int, seed: Long) {
        parallelRows(h) { y ->
            val rnd = Random(seed * 31 + y)
            val runs = (amount * amount * 6 * rnd.nextDouble()).toInt() + if (rnd.nextDouble() < amount * 0.5) 1 else 0
            repeat(runs) {
                val start = rnd.nextInt(w)
                val length = 1 + rnd.nextInt(max(8, radius * 8) * (1 + (amount * 3).toInt()))
                val bit = 1 shl (3 + rnd.nextInt(5))
                val shift = shifts[rnd.nextInt(shifts.size)]
                for (x in start until min(w, start + length)) out[y * w + x] = out[y * w + x] xor (bit shl shift)
            }
        }
    }
}
