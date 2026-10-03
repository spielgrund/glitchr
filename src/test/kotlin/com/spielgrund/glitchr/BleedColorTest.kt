package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.PixelBleed
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleedColorTest {
    private val src = Pixels(50, 10, IntArray(500) { argb(255, 220, 220, 220) })

    private fun run(vararg settings: Pair<String, Int>) =
        PixelBleed.apply(src, Values(PixelBleed.defaults().apply { putAll(settings) }), 3L)

    @Test
    fun `streaks end in the chosen color`() {
        val red = argb(255, 255, 0, 0)
        val out = run("length" to 1, "endColor" to 0xFF0000)
        assertTrue(out.data.count { it == red } > 100, "single-pixel streaks are exactly the target color")
    }

    @Test
    fun `longer streaks fade from the pixel towards the color`() {
        val out = run("length" to 40, "endColor" to 0x000000, "step" to 1, "direction" to 2)
        // streak lengths are random; a 1-pixel streak is its own last pixel and takes the end color
        val starts = (0 until 10).map { y -> out[0, y] and 0xFF }
        assertTrue(starts.count { it == 220 } > 5, "streaks start with the pixel color: $starts")
        assertTrue(out.data.any { (it and 0xFF) in 1..219 }, "a gradient in between")
        assertTrue(out.data.any { (it and 0xFFFFFF) == 0 }, "streaks end black")
    }

    @Test
    fun `color strength 0 keeps the pixel color, 50 mixes half`() {
        val none = run("colorMix" to 0, "endColor" to 0xFF0000)
        assertTrue(none.data.all { it == argb(255, 220, 220, 220) })
        val half = run("colorMix" to 50, "length" to 1, "endColor" to 0x000000)
        assertTrue(half.data.any { it == argb(255, 110, 110, 110) }, "half color strength at the streak end")
    }

    @Test
    fun `no length randomness makes every streak the full length`() {
        // on a 50 px row, full-length streaks of 20 px start at 0, 21 and 42 (one untouched pixel after each)
        val out = run("jitter" to 0, "length" to 20, "direction" to 2, "endColor" to 0x000000, "step" to 1)
        val row = (0 until 50).map { out[it, 0] and 0xFF }
        assertEquals(0, row[19], "streak ends after exactly 20 px")
        assertEquals(220, row[20], "after that one pixel stays unchanged")
        assertEquals(220, row[21], "next streak starts")
    }
}
