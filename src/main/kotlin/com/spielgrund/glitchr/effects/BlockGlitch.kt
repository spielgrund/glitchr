package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Random rectangles that are displaced, channel-rotated, inverted, smeared, pixelated or median-filtered, like a broken video stream. */
object BlockGlitch : Effect("blocks", "Block-Glitch", "Random rectangles are shifted, swapped, smeared or coarsened") {
    private val modes = listOf(
        "Shift", "Swap channels", "Invert", "Smear", "Big pixel", "Median", "Mixed",
    )
    private const val MIXED = 6

    override val params = listOf(
        Param.Choice("mode", "Type", modes, tip = "Big pixel: the whole block in its average color. Median: flat color areas as with a median filter"),
        Param.Slider("count", "Count", 1, 600, 40),
        Param.Slider("minSize", "Min. size", 2, 1000, 8, " px"),
        Param.Slider("maxSize", "Max. size", 2, 2000, 160, " px"),
        Param.Slider("shift", "Max. offset", 0, 2000, 80, " px", "Only for Shift"),
        Param.Slider("radius", "Median radius", 1, 30, 6, " px", "Only for Median: the larger, the flatter"),
        Param.Choice("channels", "Channels", channelOptions),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val random = Random(seed)
        val lo = min(v["minSize"], v["maxSize"])
        val hi = max(v["minSize"], v["maxSize"])
        val maxShift = v["shift"]
        val radius = v["radius"]
        val w = src.width
        val h = src.height

        val out = src.copy()
        repeat(v["count"]) {
            val bw = random.nextInt(lo, hi + 1)
            val bh = random.nextInt(lo, hi + 1)
            val x0 = random.nextInt(-bw / 2, w)
            val y0 = random.nextInt(-bh / 2, h)
            val mode = if (v["mode"] == MIXED) random.nextInt(MIXED) else v["mode"]
            val sx = if (maxShift == 0) 0 else random.nextInt(-maxShift, maxShift + 1)
            val sy = if (maxShift == 0) 0 else random.nextInt(-maxShift, maxShift + 1) / 4
            val bits = channelBits(v["channels"], random)

            val x1 = max(0, x0)
            val y1 = max(0, y0)
            val x2 = min(w, x0 + bw)
            val y2 = min(h, y0 + bh)
            if (x1 >= x2 || y1 >= y2) return@repeat
            val average = if (mode == 4) average(out, x1, y1, x2, y2) else 0
            val median = if (mode == 5) median(out, x1, y1, x2, y2, radius) else null

            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    val i = y * w + x
                    val c = out.data[i]
                    val n = when (mode) {
                        0 -> src.data[Edge.WRAP.resolve(y - sy, h) * w + Edge.WRAP.resolve(x - sx, w)]
                        1 -> argb(alpha(c), blue(c), red(c), green(c))
                        2 -> c xor 0x00FFFFFF
                        3 -> out.data[y1 * w + x] // top row of the block, pulled down
                        4 -> average
                        else -> median!![(y - y1) * (x2 - x1) + x - x1]
                    }
                    out.data[i] = mergeChannels(c, n, bits)
                }
            }
        }
        return out
    }

    private fun average(img: Pixels, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        var a = 0L; var r = 0L; var g = 0L; var b = 0L
        for (y in y1 until y2) for (x in x1 until x2) {
            val c = img.data[y * img.width + x]
            a += alpha(c); r += red(c); g += green(c); b += blue(c)
        }
        val n = (x2 - x1).toLong() * (y2 - y1)
        return argb((a / n).toInt(), (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * Per-channel median of the (2·radius+1)² neighborhood of every pixel in the block,
     * row by row with a sliding histogram (Huang), so the cost hardly depends on the radius.
     */
    private fun median(img: Pixels, x1: Int, y1: Int, x2: Int, y2: Int, radius: Int): IntArray {
        val w = img.width
        val h = img.height
        val bw = x2 - x1
        val result = IntArray(bw * (y2 - y1))
        parallelRows(y2 - y1) { dy ->
            val y = y1 + dy
            val top = max(0, y - radius)
            val bottom = min(h - 1, y + radius)
            val hist = IntArray(3 * 256)
            var total = 0
            fun column(x: Int, delta: Int) {
                if (x < 0 || x >= w) return
                for (yy in top..bottom) {
                    val c = img.data[yy * w + x]
                    hist[red(c)] += delta
                    hist[256 + green(c)] += delta
                    hist[512 + blue(c)] += delta
                }
                total += delta * (bottom - top + 1)
            }
            for (x in x1 - radius..x1 + radius) column(x, 1)
            for (x in x1 until x2) {
                if (x > x1) {
                    column(x - radius - 1, -1)
                    column(x + radius, 1)
                }
                val half = total / 2
                fun med(offset: Int): Int {
                    var sum = 0
                    for (k in 0 until 256) {
                        sum += hist[offset + k]
                        if (sum > half) return k
                    }
                    return 255
                }
                result[dy * bw + x - x1] = argb(alpha(img.data[y * w + x]), med(0), med(256), med(512))
            }
        }
        return result
    }
}
