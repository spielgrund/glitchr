package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Polar
import com.spielgrund.glitchr.effects.Sphere
import com.spielgrund.glitchr.image.Pixels
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolarSphereTest {
    private val src = testImage(240, 160)

    private fun close(a: Int, b: Int, tolerance: Int) = (0..24 step 8).all { s -> abs((a shr s and 0xFF) - (b shr s and 0xFF)) <= tolerance }

    @Test
    fun `polar puts the top row in the middle and the bottom row at the radius`() {
        // top half red, bottom half blue
        val img = Pixels(200, 200).also { p ->
            for (i in p.data.indices) p.data[i] = if (i / 200 < 100) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
        val out = Polar.apply(img, Polar.defaultValues(), 0L)
        assertEquals(0xFFFF0000.toInt(), out[100, 100], "middle")
        assertEquals(0xFF0000FF.toInt(), out[100, 185], "near the radius")
        val flipped = Polar.apply(img, Polar.defaultValues(mapOf("invert" to 1)), 0L)
        assertEquals(0xFF0000FF.toInt(), flipped[100, 100], "flipped middle")
    }

    @Test
    fun `unrolling a polar picture gives the strip back`() {
        val img = testImage(200, 200)
        val round = Polar.apply(img, Polar.defaultValues(mapOf("edge" to 1)), 0L)
        val back = Polar.apply(round, Polar.defaultValues(mapOf("mode" to 1)), 0L)
        // away from the squeezed middle and the seam, the picture comes back
        var good = 0
        var all = 0
        for (y in 60 until 190) for (x in 20 until 180) {
            all++
            if (close(img[x, y], back[x, y], 40)) good++
        }
        assertTrue(good > all * 0.9, "$good of $all pixels came back")
    }

    @Test
    fun `every type keeps the size and is opaque on an opaque picture`() {
        for (mode in 0..3) {
            val out = Polar.apply(src, Polar.defaultValues(mapOf("mode" to mode, "twist" to 300, "repeat" to 3, "seamless" to 1)), 0L)
            assertEquals(src.width, out.width)
            assertTrue(out.data.all { it ushr 24 == 255 }, "polar type $mode")
        }
        for (mode in 0..3) {
            val out = Sphere.apply(src, Sphere.defaultValues(mapOf("mode" to mode, "turn" to 40, "tilt" to 20)), 0L)
            assertEquals(src.height, out.height)
            assertTrue(out.data.all { it ushr 24 == 255 }, "sphere type $mode")
        }
    }

    @Test
    fun `a ball on a transparent background leaves the corners empty and the middle full`() {
        for (mode in 1..3) {
            val out = Sphere.apply(src, Sphere.defaultValues(mapOf("mode" to mode, "background" to 1)), 0L)
            assertEquals(0, out[2, 2] ushr 24, "type $mode corner")
            assertEquals(255, out[120, 80] ushr 24, "type $mode middle")
        }
    }

    @Test
    fun `a bulge of zero without light changes nothing`() {
        val out = Sphere.apply(src, Sphere.defaultValues(mapOf("mode" to 0, "amount" to 0, "antialias" to 0)), 0L)
        for (i in src.data.indices) assertTrue(close(src.data[i], out.data[i], 2), "pixel $i")
    }

    @Test
    fun `anti-aliasing softens the rim of the ball, off leaves it hard`() {
        fun alphas(aa: Int) = Sphere.apply(src, Sphere.defaultValues(mapOf("background" to 1, "antialias" to aa)), 0L)
            .data.map { it ushr 24 }.toSet()
        assertEquals(setOf(0, 255), alphas(0))
        for (aa in 1..2) assertTrue(alphas(aa).size > 5, "anti-aliasing $aa: soft rim")
    }

    @Test
    fun `anti-aliasing calms the squeezed middle of a polar picture`() {
        // fine vertical stripes become thin spokes that alias in the middle
        val stripes = Pixels(200, 200).also { p -> for (i in p.data.indices) p.data[i] = if (i % 200 % 2 == 0) -1 else 0xFF000000.toInt() }
        fun spread(aa: Int): Int {
            val out = Polar.apply(stripes, Polar.defaultValues(mapOf("antialias" to aa)), 0L)
            val values = (90 until 110).flatMap { y -> (90 until 110).map { x -> out[x, y] and 0xFF } }
            return values.max() - values.min()
        }
        assertTrue(spread(2) < spread(0), "4 × 4 ${spread(2)} vs off ${spread(0)}")
    }

    @Test
    fun `turning the globe a full circle looks the same`() {
        val a = Sphere.apply(src, Sphere.defaultValues(mapOf("turn" to 0)), 0L)
        val b = Sphere.apply(src, Sphere.defaultValues(mapOf("turn" to 360)), 0L)
        for (i in a.data.indices) assertTrue(close(a.data[i], b.data[i], 2), "pixel $i")
    }
}
