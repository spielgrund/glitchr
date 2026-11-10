package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.LightLeaks
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.red
import kotlin.test.Test
import kotlin.test.assertTrue

class LightLeaksTest {
    private val src = Pixels(200, 150, IntArray(200 * 150) { 0xFF202020.toInt() })

    private fun run(m: Map<String, Int>) = LightLeaks.apply(src, LightLeaks.defaultValues(m), 3)
    private fun redness(p: Pixels, x0: Int, x1: Int) = (0 until 150).sumOf { y -> (x0 until x1).sumOf { x -> red(p[x, y]) - blue(p[x, y]) } }

    @Test
    fun `warm leaks bleed in from the sides`() {
        val out = run(mapOf("side" to 0, "drift" to 0, "ragged" to 0))
        assertTrue(redness(out, 0, 20) > 20 * redness(out, 180, 200).coerceAtLeast(1), "left warm, right untouched")
    }

    @Test
    fun `every style lights the picture and the time makes them flicker`() {
        for (style in 0..3) assertTrue(redness(run(mapOf("style" to style)), 0, 200) > 10000, "style $style")
        assertTrue(!run(mapOf("time" to 0)).data.contentEquals(run(mapOf("time" to 3000)).data), "animated")
    }

    @Test
    fun `the cool palette is blue, strength 0 leaves the picture`() {
        val cool = run(mapOf("palette" to 3))
        assertTrue(redness(cool, 0, 200) < 0, "blue")
        assertTrue(run(mapOf("strength" to 0, "wash" to 0)).data.contentEquals(src.data), "untouched")
    }
}
