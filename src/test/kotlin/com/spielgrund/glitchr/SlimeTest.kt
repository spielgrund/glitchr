package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Slime
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SlimeTest {
    private val src = Pixels(160, 120, IntArray(160 * 120) { 0xFF203060.toInt() })

    private fun run(m: Map<String, Int>) = Slime.apply(src, Slime.defaultValues(m), 2)
    private fun changed(p: Pixels, x0: Int, y0: Int, x1: Int, y1: Int) =
        (y0 until y1).sumOf { y -> (x0 until x1).count { x -> p[x, y] != src[x, y] } }.toDouble() / ((x1 - x0) * (y1 - y0))

    @Test
    fun `the frame slime covers the edges and leaves the middle`() {
        val out = run(emptyMap())
        assertTrue(changed(out, 0, 0, 160, 6) > 0.5, "top edge")
        assertTrue(changed(out, 0, 0, 6, 120) > 0.5, "left edge")
        assertTrue(changed(out, 60, 45, 100, 75) < 0.05, "middle free")
    }

    @Test
    fun `every style, place and the flow give something`() {
        for (style in 0..3) assertTrue(changed(run(mapOf("style" to style)), 0, 0, 160, 120) > 0.05, "style $style")
        assertTrue(changed(run(mapOf("place" to 2)), 60, 45, 100, 75) > 0.1, "everywhere reaches the middle")
        assertTrue(!run(mapOf("time" to 0)).data.contentEquals(run(mapOf("time" to 8000)).data), "the flow moves it")
    }

    @Test
    fun `on a threshold area only where the picture is in range`() {
        val half = Pixels(160, 120, IntArray(160 * 120) { if (it % 160 < 80) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        val out = Slime.apply(half, Slime.defaultValues(mapOf("place" to 1, "shadow" to 0, "refraction" to 0)), 2)
        val leftChanged = (0 until 120).sumOf { y -> (0 until 30).count { x -> out[x, y] != half[x, y] } }
        val rightChanged = (0 until 120).sumOf { y -> (120 until 160).count { x -> out[x, y] != half[x, y] } }
        assertTrue(leftChanged == 0 && rightChanged > 500, "$leftChanged / $rightChanged")
    }

    @Test
    fun `fine detail adds small patches and the second color tints the slime`() {
        val plain = mapOf("detail" to 0, "color2Amount" to 0)
        val none = run(plain)
        val detailed = run(plain + ("detail" to 100))
        assertTrue(changed(detailed, 0, 0, 160, 120) > changed(none, 0, 0, 160, 120), "more slime with fine detail")
        for (spread in 0..2) {
            val tinted = run(plain + mapOf("detail" to 60, "spread" to spread, "color2Amount" to 80, "color2" to 0xFF0000))
            val base = run(plain + mapOf("detail" to 60))
            assertTrue(!tinted.data.contentEquals(base.data), "second color on $spread")
        }
    }

    @Test
    fun `the narrowest frame stays at the very edge`() {
        val thin = run(mapOf("depth" to 1, "amount" to -100))
        assertTrue(changed(thin, 0, 0, 160, 120) > 0, "a little at the edge")
        assertContentEquals(src.data.copyOfRange(30 * 160 + 20, 30 * 160 + 140), thin.data.copyOfRange(30 * 160 + 20, 30 * 160 + 140), "nothing further in")
    }
}
