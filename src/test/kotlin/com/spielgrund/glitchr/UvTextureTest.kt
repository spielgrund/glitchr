package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.UvTexture
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UvTextureTest {
    /** A plain UV ramp: red rises to the right, green downwards (black corner at the top left excluded by +1). */
    private val ramp = Pixels(128, 128, IntArray(128 * 128) { i -> argb(255, (i % 128) * 2 + 1, (i / 128) * 2 + 1, 0) })

    private val base = mapOf("antialias" to 0, "smooth" to 0, "colorA" to 0x000000, "colorB" to 0xFFFFFF)

    @Test
    fun `a checkerboard follows the UVs`() {
        val out = UvTexture.apply(ramp, UvTexture.defaultValues(base + ("repeats" to 2)), 3L)
        // two by two squares over the whole ramp
        assertEquals(0, red(out[20, 20]))
        assertEquals(255, red(out[100, 20]))
        assertEquals(255, red(out[20, 100]))
        assertEquals(0, red(out[100, 100]))
    }

    @Test
    fun `the channels can be swapped and inverted`() {
        val stripes = base + mapOf("pattern" to 1, "repeats" to 1)
        val alongX = UvTexture.apply(ramp, UvTexture.defaultValues(stripes), 3L)
        assertTrue(red(alongX[20, 64]) != red(alongX[100, 64]))
        assertEquals(red(alongX[64, 20]), red(alongX[64, 100]))
        // U from green: the stripes now change downwards
        val alongY = UvTexture.apply(ramp, UvTexture.defaultValues(stripes + ("uChannel" to 1)), 3L)
        assertTrue(red(alongY[64, 20]) != red(alongY[64, 100]))
        val inverted = UvTexture.apply(ramp, UvTexture.defaultValues(stripes + ("uInvert" to 1)), 3L)
        assertEquals(red(alongX[20, 64]), 255 - red(inverted[20, 64]))
    }

    @Test
    fun `moving, turning and scaling change the placement, deterministically`() {
        val plain = UvTexture.apply(ramp, UvTexture.defaultValues(base), 3L)
        for (change in listOf("offsetU" to 100, "rotation" to 30, "scale" to 50)) {
            val moved = UvTexture.apply(ramp, UvTexture.defaultValues(base + change), 3L)
            assertTrue(!plain.data.contentEquals(moved.data), change.first)
        }
        val v = UvTexture.defaultValues(mapOf("pattern" to 8))
        assertContentEquals(UvTexture.apply(ramp, v, 3L).data, UvTexture.apply(ramp, v, 3L).data)
    }

    @Test
    fun `black is no UV, every pattern works, anti-aliasing adds in-between colors`() {
        val withBlack = Pixels(128, 128, ramp.data.copyOf()).apply { for (i in 0 until 128 * 20) data[i] = argb(255, 0, 0, 0) }
        val out = UvTexture.apply(withBlack, UvTexture.defaultValues(base), 3L)
        assertEquals(argb(255, 0, 0, 0), out[64, 5])
        for (p in 0..10) UvTexture.apply(ramp, UvTexture.defaultValues(mapOf("pattern" to p)), 3L)
        fun colors(aa: Int) = UvTexture.apply(ramp, UvTexture.defaultValues(base + mapOf("antialias" to aa, "rotation" to 20)), 3L).data.toSet().size
        assertTrue(colors(2) > colors(0), "glatt ${colors(2)}, hart ${colors(0)}")
    }
}
