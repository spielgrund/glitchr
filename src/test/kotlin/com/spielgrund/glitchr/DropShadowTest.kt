package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.DropShadow
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.green
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DropShadowTest {
    // a white square on a transparent layer
    private val square = Pixels(100, 100, IntArray(100 * 100) { val x = it % 100; val y = it / 100
        if (x in 30..59 && y in 30..59) 0xFFFFFFFF.toInt() else 0 })

    private fun run(src: Pixels, m: Map<String, Int>) = DropShadow.apply(src, DropShadow.defaultValues(m), 1)

    @Test
    fun `the drop shadow falls beside the content, the content stays`() {
        val out = run(square, mapOf("angle" to 0, "distance" to 15, "softness" to 0, "opacity" to 100))
        assertTrue(alpha(out[68, 45]) > 200, "shadow to the right")
        assertEquals(0, alpha(out[22, 45]), "nothing to the left")
        assertEquals(0xFFFFFFFF.toInt(), out[45, 45], "the square itself")
    }

    @Test
    fun `the inner shadow darkens the content along the edge facing away`() {
        val out = run(square, mapOf("kind" to 1, "angle" to 0, "distance" to 6, "softness" to 0, "opacity" to 100))
        assertTrue(green(out[32, 45]) < 60, "dark inside the left edge")
        assertEquals(255, green(out[57, 45]), "light at the right edge")
    }

    @Test
    fun `the long shadow stretches far, dark parts of a picture cast it too`() {
        val long = run(square, mapOf("kind" to 2, "angle" to 0, "distance" to 35, "softness" to 0, "opacity" to 100, "fade" to 0))
        assertTrue(alpha(long[90, 45]) > 200, "far to the right")
        val picture = Pixels(100, 100, IntArray(100 * 100) { if (square.data[it] != 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        val out = run(picture, mapOf("source" to 2, "angle" to 0, "distance" to 15, "softness" to 0, "opacity" to 50))
        assertTrue(green(out[68, 45]) in 100..160, "the white ground shaded beside the dark square")
        assertEquals(255, green(out[22, 45]), "untouched on the other side")
    }
}
