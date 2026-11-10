package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.ThinFilm
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinFilmTest {
    private fun film(src: Pixels, vararg c: Pair<String, Int>) = ThinFilm.apply(src, ThinFilm.defaultValues(mapOf(*c)), 3L)
    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF

    /** Thickness from the picture only: a gray ramp from left (0 nm) to right (1500 nm), shown as film only. */
    private fun ramp(vararg c: Pair<String, Int>): Pixels {
        val src = Pixels(301, 4, IntArray(301 * 4) { i -> val v = 255 * (i % 301) / 300; (0xFF shl 24) or (v shl 16) or (v shl 8) or v })
        return film(src, "blend" to 4, "strength" to 100, "picture" to 100, "drain" to 0, "thin" to 0, "thick" to 1500, *c)
    }

    @Test
    fun `the colors follow the interference chart`() {
        val p = ramp()
        // thinner than light, a soap film reflects nothing: black
        val black = p[0, 1]
        assertTrue(r(black) < 30 && g(black) < 30 && b(black) < 30, "black film")
        // the first order: whitish, then golden around 150–200 nm (red and green over blue)
        assertTrue((150 / 5..200 / 5).any { x -> p[x, 1].let { r(it) > b(it) + 60 && g(it) > b(it) } }, "golden in the first order")
        // further on all the hues come by – something clearly blue and something clearly green
        val colors = (0 until 301).map { p[it, 1] }
        assertTrue(colors.any { b(it) > r(it) + 60 && b(it) > g(it) }, "blue")
        assertTrue(colors.any { g(it) > r(it) + 60 && g(it) > b(it) }, "green")
        // far out the orders blur into pale pinks and greens
        val far = colors.drop(260)
        assertTrue(far.all { maxOf(r(it), g(it), b(it)) - minOf(r(it), g(it), b(it)) < 140 }, "pale at high orders")
    }

    @Test
    fun `oil on water has no black film, the slant shifts the colors, the time moves the swirls`() {
        assertTrue(ramp("material" to 1)[0, 1].let { maxOf(r(it), g(it), b(it)) } > 60, "oil on water is never black")
        assertTrue(!ramp("angle" to 60).data.contentEquals(ramp().data), "angle shifts")
        val src = Pixels(80, 60, IntArray(80 * 60) { 0xFF404850.toInt() })
        val one = film(src)
        assertTrue(one.data.toSet().size > 100, "many colors")
        assertTrue(!film(src, "time" to 3000).data.contentEquals(one.data), "flows")
        // no strength: the picture stays; its transparency is kept
        assertTrue(film(src, "strength" to 0).data.contentEquals(src.data))
        val clear = Pixels(10, 10, IntArray(100) { 0x00404850 })
        assertEquals(0, film(clear)[5, 5] ushr 24)
    }
}
