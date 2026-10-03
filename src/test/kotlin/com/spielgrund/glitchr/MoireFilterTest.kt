package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.MoireFilter
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MoireFilterTest {
    private val dir = File("target/test-output/moirefilter").apply { mkdirs() }

    /** Fine horizontal stripes, [period] px, as in a photo of fabric or a screen. */
    private fun stripes(w: Int, h: Int, period: Double) = Pixels(w, h).also { p ->
        for (y in 0 until h) for (x in 0 until w) {
            val v = (127.5 + 127.5 * sin(2 * PI * y / period)).toInt()
            p.data[y * w + x] = argb(255, v, v, v)
        }
    }

    /** Brightness column-averaged over blocks of [block] rows: what is left of the picture from afar. */
    private fun coarseRange(p: Pixels, block: Int): Int {
        val means = (0 until p.height / block).map { b ->
            var sum = 0L
            for (y in b * block until (b + 1) * block) for (x in 0 until p.width) sum += red(p[x, y])
            (sum / (block * p.width)).toInt()
        }
        return means.max() - means.min()
    }

    @Test
    fun `every mode keeps the size and alpha and changes the picture`() {
        val src = testImage(160, 120).let { t -> Pixels(t.width, t.height, IntArray(t.data.size) { t.data[it] and 0x80FFFFFF.toInt() or (if (it % 2 == 0) 0 else 0x7F000000) }) }
        for (mode in 0..4) {
            val out = MoireFilter.apply(src, MoireFilter.defaultValues(mapOf("mode" to mode)), 1L)
            ImageIO.write(out.toImage(), "png", File(dir, "mode$mode.png"))
            assertContentEquals(src.data.map { it ushr 24 }, out.data.map { it ushr 24 }, "type $mode")
            assertTrue(!out.data.contentEquals(src.data), "type $mode changes nothing")
        }
    }

    @Test
    fun `aliasing folds fine stripes into coarse moire bands`() {
        // stripes finer than the sampling step: sampled without filter they beat into broad bands
        val src = stripes(200, 240, 3.3)
        val before = coarseRange(src, 12)
        val out = MoireFilter.apply(src, MoireFilter.defaultValues(mapOf("mode" to 3, "pitch" to 30, "angle" to 0, "deviation" to 0)), 0)
        assertTrue(coarseRange(out, 12) > before + 60, "before $before, after ${coarseRange(out, 12)}")
    }

    @Test
    fun `amplifying brings out weak bands`() {
        // fine stripes whose contrast swells slowly: a weak moire
        val w = 200
        val h = 240
        val src = Pixels(w, h).also { p ->
            for (y in 0 until h) for (x in 0 until w) {
                val band = 10 * sin(2 * PI * y / 60)
                val v = (127.5 + band + 60 * sin(2 * PI * y / 3.0)).toInt().coerceIn(0, 255)
                p.data[y * w + x] = argb(255, v, v, v)
            }
        }
        val out = MoireFilter.apply(src, MoireFilter.defaultValues(mapOf("mode" to 4, "pitch" to 30, "band" to 40, "gain" to 400)), 0)
        assertTrue(coarseRange(out, 6) > coarseRange(src, 6) * 2)
    }
}
