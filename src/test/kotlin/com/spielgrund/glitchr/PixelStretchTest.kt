package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.PixelBleed
import com.spielgrund.glitchr.effects.PixelStretch
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import kotlin.test.Test
import kotlin.test.assertEquals

class PixelStretchTest {
    /** One row: dark, a bright run of four grays in no particular order, darker grays after it. */
    private val row = intArrayOf(10, 11, 12, 230, 200, 250, 210, 20, 21, 22, 23, 24, 25, 26, 27, 28)
    private val src = Pixels(row.size, 1, IntArray(row.size) { argb(255, row[it], row[it], row[it]) })

    /** Rightwards, only the bright run as a whole, stretched by exactly 4 px. */
    private fun values(variant: Int, extra: Map<String, Int> = emptyMap()) = PixelStretch.defaultValues(
        mapOf("variant" to variant, "angle" to 0, "lower" to 150, "length" to 4, "jitter" to 0, "maxRun" to 0) + extra,
    )

    private fun grays(p: Pixels) = p.data.map { red(it) }

    @Test
    fun `cover lays the stretched run over the following pixels`() {
        val out = PixelStretch.apply(src, values(0), 1L)
        assertEquals(listOf(10, 11, 12, 230, 230, 200, 200, 250, 250, 210, 210, 24, 25, 26, 27, 28), grays(out))
    }

    @Test
    fun `push shifts the following pixels along`() {
        val out = PixelStretch.apply(src, values(1), 1L)
        assertEquals(listOf(10, 11, 12, 230, 230, 200, 200, 250, 250, 210, 210, 20, 21, 22, 23, 24), grays(out))
    }

    @Test
    fun `max run 1 pulls every pixel on its own`() {
        // cover: the first pixel covers the rest of the run (like Pixelbleed); push: every pixel gets its own 4 copies
        assertEquals(listOf(10, 11, 12, 230, 230, 230, 230, 230, 21, 22), grays(PixelStretch.apply(src, values(0, mapOf("maxRun" to 1)), 1L)).take(10))
        assertEquals(listOf(10, 11, 12, 230, 230, 230, 230, 230, 200, 200), grays(PixelStretch.apply(src, values(1, mapOf("maxRun" to 1)), 1L)).take(10))
    }

    @Test
    fun `every pixel is pulled on its own by default`() {
        assertEquals(1, PixelStretch.defaultValues()["maxRun"])
    }

    @Test
    fun `threshold by hue selects a color range, also across red, and never grays`() {
        val red = argb(255, 230, 20, 20)
        val green = argb(255, 20, 200, 20)
        val gray = argb(255, 128, 128, 128)
        val line = Pixels(8, 1, intArrayOf(gray, red, gray, gray, green, gray, gray, gray))
        fun pulled(lower: Int, upper: Int, invert: Int = 0) = PixelStretch.apply(
            line, values(0, mapOf("thresholdMode" to 6, "lower" to lower, "upper" to upper, "invert" to invert, "length" to 1)), 0L,
        ).data.toList()
        // greens (85 ± 20): only the green pixel is pulled onto the gray after it
        assertEquals(green, pulled(65, 105)[5])
        assertEquals(gray, pulled(65, 105)[2])
        // 240..15 wraps around red
        assertEquals(red, pulled(240, 15)[2])
        assertEquals(gray, pulled(240, 15)[5])
        // inverted: everything with a hue outside green – but the grays still don't count
        val inverted = pulled(65, 105, invert = 1)
        assertEquals(red, inverted[2])
        assertEquals(gray, inverted[5])
    }

    @Test
    fun `pushing moves the picture into the empty canvas`() {
        val w = 12
        val img = Pixels(w, 1, IntArray(w) { if (it < 6) argb(255, 240, 240, 240) else 0 })
        val out = PixelStretch.apply(img, values(1, mapOf("length" to 3)), 0L)
        assertEquals(9, out.data.count { it ushr 24 == 255 })
    }

    @Test
    fun `pixelbleed keeps its mean threshold by default and can bleed by saturation`() {
        assertEquals(1, PixelBleed.defaultValues()["thresholdMode"])
        val gray = argb(255, 200, 200, 200)
        val vivid = argb(255, 250, 40, 40)
        val dark = argb(255, 5, 5, 5)
        val line = Pixels(6, 1, intArrayOf(gray, dark, dark, vivid, dark, dark))
        val v = PixelBleed.defaultValues(mapOf("thresholdMode" to 4, "threshold" to 128, "direction" to 2, "length" to 2, "jitter" to 0, "colorMix" to 0))
        val out = PixelBleed.apply(line, v, 0L).data.toList()
        // the bright gray has no saturation and stays put, the vivid red bleeds
        assertEquals(listOf(gray, dark, dark, vivid, vivid, dark), out)
    }
}
