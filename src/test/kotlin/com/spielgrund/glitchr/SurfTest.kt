package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Surf
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SurfTest {
    /** White with a red ellipse in the middle of the upper half. */
    private val blob = Pixels(300, 240).also { p ->
        for (y in 0 until 240) for (x in 0 until 300) {
            val inside = ((x - 150) / 60.0).let { it * it } + ((y - 70) / 30.0).let { it * it } < 1
            p.data[y * 300 + x] = if (inside) 0xFFFF2020.toInt() else -1
        }
    }

    private fun run(vararg c: Pair<String, Int>) =
        Surf.apply(blob, Surf.defaultValues(mapOf("areas" to 10, "wobble" to 0, "spray" to 0, "outline" to 0, "colorMode" to 3, "color" to 0x0000FF, *c)), 1L)

    private fun blue(p: Pixels, y0: Int, y1: Int) = (y0 until y1).sumOf { y -> (0 until 300).count { x -> (p[x, y] and 0xFF) > 200 && (p[x, y] shr 16 and 0xFF) < 200 } }

    @Test
    fun `waves run out in the direction only`() {
        val down = run("direction" to 9000)
        assertTrue(blue(down, 110, 240) > 300, "waves below: ${blue(down, 110, 240)}")
        assertTrue(blue(down, 0, 35) == 0, "none above")
        val up = run("direction" to 27000)
        assertTrue(blue(up, 0, 35) > 100 && blue(up, 110, 240) == 0, "turned: waves above only")
    }

    @Test
    fun `a whole phase step loops`() {
        assertContentEquals(run("phase" to 0).data, run("phase" to 36000).data)
        assertTrue(!run("phase" to 0).data.contentEquals(run("phase" to 18000).data))
    }

    @Test
    fun `the spray travels with its wave and loops after one wave`() {
        fun spray(phase: Int) = run("spray" to 100, "phase" to phase)
        assertContentEquals(spray(0).data, spray(36000).data)
        assertTrue(!spray(0).data.contentEquals(spray(9000).data))
    }

    @Test
    fun `mixing in the custom color`() {
        // red waves (from the blob) with green mixed in half
        val mixed = run("colorMode" to 0, "color" to 0x00FF00, "colorMix" to 50, "width" to 80)
        assertTrue(mixed.data.any { (it shr 16 and 0xFF) in 100..160 && (it shr 8 and 0xFF) in 100..160 }, "red and green mixed")
    }

    @Test
    fun `spray adds clouds`() {
        val calm = run()
        val spray = run("spray" to 100)
        assertTrue(blue(spray, 100, 240) > blue(calm, 100, 240) + 50)
    }
}
