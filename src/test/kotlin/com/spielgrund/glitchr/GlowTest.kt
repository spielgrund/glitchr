package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Glow
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class GlowTest {
    /** Black with a small red light in the middle. */
    private val dot = Pixels(120, 120).also { p ->
        p.data.fill(0xFF000000.toInt())
        for (y in 57 until 63) for (x in 57 until 63) p.data[y * 120 + x] = 0xFFFF2020.toInt()
    }

    private fun run(img: Pixels, vararg c: Pair<String, Int>) = Glow.apply(img, Glow.defaultValues(mapOf(*c)), 0)

    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF

    @Test
    fun `dark parts below the threshold do not glow`() {
        val dark = Pixels(60, 60).also { it.data.fill(0xFF303030.toInt()) }
        assertContentEquals(dark.data, run(dark).data)
    }

    @Test
    fun `a light glows into its surroundings in its own color`() {
        val out = run(dot, "threshold" to 20, "radius" to 8)
        assertTrue(r(out[66, 60]) > 20, "Glow neben dem Licht: ${r(out[66, 60])}")
        assertTrue(r(out[66, 60]) > g(out[66, 60]) + 10, "in der Farbe des Lichts")
        assertTrue(r(out[66, 60]) > r(out[100, 60]), "nimmt mit der Entfernung ab")
        // white glow instead
        val white = run(dot, "threshold" to 20, "radius" to 8, "color" to 1)
        assertTrue(kotlin.math.abs(r(white[66, 60]) - g(white[66, 60])) < 6, "weiss")
    }

    @Test
    fun `streaks reach far along their angle`() {
        val plain = run(dot, "threshold" to 20, "radius" to 4)
        val streaked = run(dot, "threshold" to 20, "radius" to 4, "streak" to 100, "streakLength" to 100, "streakAngle" to 0)
        assertTrue(r(streaked[100, 60]) > r(plain[100, 60]) + 10, "waagrecht weit")
        assertTrue(r(streaked[60, 100]) <= r(plain[60, 100]) + 2, "senkrecht nicht")
    }

    @Test
    fun `glow only shows the glow on black`() {
        val out = run(dot, "threshold" to 20, "radius" to 8, "glowOnly" to 1)
        assertTrue(out[5, 5] == 0xFF000000.toInt(), "schwarz ohne Glow: ${Integer.toHexString(out[5, 5])}")
        assertTrue(r(out[66, 60]) > 20)
    }
}
