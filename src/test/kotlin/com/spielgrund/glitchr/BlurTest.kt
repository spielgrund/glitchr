package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Blur
import com.spielgrund.glitchr.image.Pixels
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlurTest {
    /** Black with one white vertical line in the middle. */
    private val line = Pixels(120, 80).also { p ->
        p.data.fill(0xFF000000.toInt())
        for (y in 0 until 80) p.data[y * 120 + 60] = -1
    }

    private fun run(src: Pixels, vararg changes: Pair<String, Int>) = Blur.apply(src, Blur.defaultValues(mapOf(*changes)), 1L)

    private fun g(c: Int) = c shr 8 and 0xFF

    @Test
    fun `gauss and box spread the line sideways, keeping the total brightness`() {
        for (type in 0..1) {
            val out = run(line, "type" to type, "radius" to 6)
            assertTrue(g(out[60, 40]) < 200 && g(out[63, 40]) > 5, "type $type spreads")
            val total = (0 until 120).sumOf { g(out[it, 40]) }
            assertTrue(abs(total - 255) < 12, "type $type: sum $total")
        }
    }

    @Test
    fun `directional blur follows the angle`() {
        // along the line (90°) nothing changes, across it (0°) the line spreads
        val along = run(line, "type" to 2, "radius" to 8, "angle" to 90)
        assertEquals(255, g(along[60, 40]))
        assertEquals(0, g(along[62, 40]))
        val across = run(line, "type" to 2, "radius" to 8, "angle" to 0)
        assertTrue(g(across[62, 40]) > 0 && g(across[60, 40]) < 100)
    }

    @Test
    fun `radial blur leaves the center sharp`() {
        val dot = Pixels(101, 101).also { it.data.fill(0xFF000000.toInt()); it.data[50 * 101 + 50] = -1 }
        assertEquals(255, g(run(dot, "type" to 3, "radius" to 30)[50, 50]))
        val spin = Pixels(101, 101).also { it.data.fill(0xFF000000.toInt()); it.data[50 * 101 + 95] = -1 }
        val out = run(spin, "type" to 4, "radius" to 30)
        assertTrue(g(out[95, 50]) < 200 && g(out[95, 48]) > 0, "spin smears the dot tangentially")
    }

    @Test
    fun `a single channel blurs, the others stay sharp`() {
        val out = run(line, "channel" to 1, "radius" to 6)
        assertEquals(255, g(out[60, 40]), "green stays sharp")
        assertTrue(out[60, 40] shr 16 and 0xFF < 200, "red blurs")
        assertEquals(0, g(out[63, 40]))
    }

    @Test
    fun `every data error changes the blur and stays deterministic`() {
        val src = testImage(200, 120)
        val clean = run(src, "error" to 0)
        for (error in 1..4) {
            val a = run(src, "error" to error, "errorAmount" to 70)
            val b = run(src, "error" to error, "errorAmount" to 70)
            assertTrue(!a.data.contentEquals(clean.data), "error $error changes nothing")
            assertTrue(a.data.contentEquals(b.data), "error $error not deterministic")
        }
    }
}
