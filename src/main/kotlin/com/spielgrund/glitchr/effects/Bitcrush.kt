package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.channel
import com.spielgrund.glitchr.image.parallelRows
import kotlin.random.Random

/** Fewer bits per color channel, with optional dithering and a pixel mosaic. */
object Bitcrush : Effect("bitcrush", "Bitcrush", "Reduces the color depth and coarsens the pixels") {
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    override val params = listOf(
        Param.Slider("bits", "Bits per channel", 1, 8, 3),
        Param.Choice("dither", "Dithering", listOf("Off", "Bayer 4×4", "Noise")),
        Param.Slider("pixel", "Pixel size", 1, 128, 1, " px"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val levels = (1 shl v["bits"]) - 1
        val dither = v["dither"]
        val size = v["pixel"]
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val random = Random(seed + y)
            val sy = y / size * size
            for (x in 0 until w) {
                val c = src.data[sy * w + x / size * size]
                // threshold offset in -0.5..0.5 of a quantization step
                val t = when (dither) {
                    1 -> (bayer[(y / size and 3) * 4 + (x / size and 3)] + 0.5f) / 16f - 0.5f
                    2 -> random.nextFloat() - 0.5f
                    else -> 0f
                }
                fun q(k: Int) = (channel(c, k) / 255f * levels + t + 0.5f).toInt().coerceIn(0, levels) * 255 / levels
                out.data[y * w + x] = argb(alpha(c), q(0), q(1), q(2))
            }
        }
        return out
    }
}
