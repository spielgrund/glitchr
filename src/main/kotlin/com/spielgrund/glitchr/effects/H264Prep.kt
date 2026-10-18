package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prepares the picture for H.264 (MP4), best as an adjustment layer on top. The encoder
 * works on a grid of small blocks (4 × 4, in High profile also 8 × 8): it turns every block
 * into frequencies and rounds them – fine detail and noise get lost or smear into
 * blocks, and colors only exist at half resolution (4:2:0). This effect does the same work
 * beforehand, in a way that keeps the result sharp: it lifts the block's finer frequencies
 * (so what survives the rounding is crisp), drops the tiny ones that only cost data and
 * turn into mosquito noise, can round them like the encoder at a given quality, and halves
 * the colors on the encoder's own 2 × 2 grid, so they don't bleed. The grid is the canvas'
 * – export at 100 % so it lines up with the encoder's.
 */
object H264Prep : Effect("h264prep", "H.264 prep", "Prepares the picture for MP4/H.264 on the encoder's 4 × 4 block grid, so the video stays as sharp as possible") {
    private val sizes = listOf(4, 8)

    override val params = listOf(
        Param.Choice("block", "Block grid", sizes.map { "$it × $it" }, 0, "The encoder's transform blocks: 4 × 4 always, 8 × 8 in High profile"),
        Param.Slider("sharpen", "Sharpen", 0, 200, 40, " %", "Lifts the finer frequencies of every block, so detail stays crisp after encoding"),
        Param.Slider("denoise", "Denoise", 0, 100, 25, " %", "Drops tiny frequencies (noise, grain) that cost data and turn into flicker and mosquito noise"),
        Param.Slider("qp", "Match quality (QP)", 0, 51, 0, tip = "Rounds the frequencies like the encoder at this quantizer, so you see the result beforehand and it compresses predictably; 0 = off"),
        Param.Toggle("chroma", "Colors on the 4:2:0 grid", true, "Averages the colors on the encoder's own 2 × 2 grid, so color edges don't smear further"),
        Param.Toggle("safe", "Video levels (16–235)", false, "Keeps brightness within TV range, so players don't clip lights and shadows"),
    )

    override val random = false

    /** The orthonormal DCT matrix for [n] points: row u, column x. */
    private fun dct(n: Int) = Array(n) { u ->
        DoubleArray(n) { x -> (if (u == 0) sqrt(1.0 / n) else sqrt(2.0 / n)) * cos((2 * x + 1) * u * PI / (2 * n)) }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = sizes[v["block"]]
        val c = dct(n)
        val sharpen = v["sharpen"] / 100.0
        val denoise = v["denoise"] / 100.0 * 6
        val qp = v["qp"]
        // the H.264 quantizer step: doubles every 6 steps, 0.625 at QP 0
        val step = if (qp > 0) 0.625 * 2.0.pow(qp / 6.0) else 0.0
        val chroma = v.bool("chroma")
        val safe = v.bool("safe")

        // BT.601 luma and color differences
        val yy = FloatArray(w * h)
        val cb = FloatArray(w * h)
        val cr = FloatArray(w * h)
        for (i in 0 until w * h) {
            val p = src.data[i]
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            yy[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        // luma, block by block on the grid from the top left corner
        val bw = (w + n - 1) / n
        val bh = (h + n - 1) / n
        val out = FloatArray(w * h)
        parallelRows(bh) { by ->
            val block = Array(n) { DoubleArray(n) }
            val tmp = Array(n) { DoubleArray(n) }
            val coef = Array(n) { DoubleArray(n) }
            for (bx in 0 until bw) {
                val x0 = bx * n
                val y0 = by * n
                for (j in 0 until n) for (i in 0 until n) {
                    // beyond the edge the last row/column repeats, as the encoder pads it
                    val x = (x0 + i).coerceAtMost(w - 1)
                    val y = (y0 + j).coerceAtMost(h - 1)
                    block[j][i] = yy[y * w + x].toDouble()
                }
                // forward: coef = C · block · Cᵀ
                for (j in 0 until n) for (u in 0 until n) { var s = 0.0; for (x in 0 until n) s += block[j][x] * c[u][x]; tmp[j][u] = s }
                for (vv in 0 until n) for (u in 0 until n) { var s = 0.0; for (y in 0 until n) s += c[vv][y] * tmp[y][u]; coef[vv][u] = s }
                for (vv in 0 until n) for (u in 0 until n) {
                    if (u == 0 && vv == 0) {
                        if (step > 0) coef[0][0] = (coef[0][0] / step).roundToInt() * step
                        continue
                    }
                    // how fine this frequency is, 0..1
                    val fine = (u + vv) / (2.0 * (n - 1))
                    var k = coef[vv][u] * (1 + sharpen * fine)
                    // small ones are noise: dropped, the finer the more readily
                    if (abs(k) < denoise * (0.5 + fine)) k = 0.0
                    // like the encoder: rounded towards zero a little (dead zone)
                    if (step > 0) k = (if (k >= 0) 1 else -1) * kotlin.math.floor(abs(k) / step + 0.6) * step
                    coef[vv][u] = k
                }
                // back: block = Cᵀ · coef · C
                for (vv in 0 until n) for (x in 0 until n) { var s = 0.0; for (u in 0 until n) s += coef[vv][u] * c[u][x]; tmp[vv][x] = s }
                for (y in 0 until n) for (x in 0 until n) {
                    var s = 0.0
                    for (vv in 0 until n) s += c[vv][y] * tmp[vv][x]
                    val px = x0 + x
                    val py = y0 + y
                    if (px < w && py < h) out[py * w + px] = s.toFloat()
                }
            }
        }

        if (chroma) {
            // average the colors on the 2 × 2 grid the encoder stores them on
            for (y in 0 until h step 2) for (x in 0 until w step 2) {
                var sb = 0f
                var sr = 0f
                var count = 0
                for (dy in 0..1) for (dx in 0..1) {
                    if (x + dx >= w || y + dy >= h) continue
                    sb += cb[(y + dy) * w + x + dx]
                    sr += cr[(y + dy) * w + x + dx]
                    count++
                }
                for (dy in 0..1) for (dx in 0..1) {
                    if (x + dx >= w || y + dy >= h) continue
                    cb[(y + dy) * w + x + dx] = sb / count
                    cr[(y + dy) * w + x + dx] = sr / count
                }
            }
        }

        val result = Pixels(w, h)
        for (i in 0 until w * h) {
            var l = out[i]
            if (safe) l = 16f + l * (219f / 255f)
            val r = l + 1.402f * cr[i]
            val g = l - 0.344136f * cb[i] - 0.714136f * cr[i]
            val b = l + 1.772f * cb[i]
            fun ch(x: Float) = (x + 0.5f).toInt().coerceIn(if (safe) 16 else 0, if (safe) 235 else 255)
            result.data[i] = argb(src.data[i] ushr 24, ch(r), ch(g), ch(b))
        }
        return result
    }
}
