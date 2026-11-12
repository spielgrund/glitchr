package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.RisingPixels
import com.spielgrund.glitchr.effects.Transform
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RisingPixelsTest {
    /** Transparent canvas with an opaque red square in the lower middle. */
    private fun cutOut(): Pixels {
        val p = Pixels(100, 100)
        for (y in 60 until 80) for (x in 40 until 60) p.data[y * 100 + x] = 0xFFFF0000.toInt()
        return p
    }

    private fun opaqueAbove(p: Pixels, below: Int) = (0 until below * 100).count { (p.data[it] ushr 24) > 0 }

    @Test
    fun `pixels rise from the visible area`() {
        val src = cutOut()
        val out = RisingPixels.apply(src, RisingPixels.defaultValues(mapOf("time" to 1500, "count" to 300)), 3L)
        // above the square there are now red pixels, none below it
        assertTrue(opaqueAbove(out, 60) > 50, "pixels above the area")
        assertTrue((80 * 100 until 100 * 100).none { (out.data[it] ushr 24) > 0 }, "nothing falls")
        // the color comes from the picture, no glow
        val c = (0 until 60 * 100).map { out.data[it] }.first { (it ushr 24) == 255 }
        assertEquals(0xFF0000, c and 0xFFFFFF)
        // without holes the square stays whole
        for (y in 60 until 80) for (x in 40 until 60) assertEquals(0xFFFF0000.toInt(), out.data[y * 100 + x])
    }

    @Test
    fun `dissolve empties the area`() {
        val src = cutOut()
        fun at(t: Int) = RisingPixels.apply(src, RisingPixels.defaultValues(mapOf("mode" to 1, "time" to t, "dissolveTime" to 20, "flightTime" to 10)), 3L)
        fun left(p: Pixels) = (60 until 80).sumOf { y -> (40 until 60).count { x -> p.data[y * 100 + x] == 0xFFFF0000.toInt() } }
        assertEquals(400, left(at(0)))
        val mid = left(at(1000))
        assertTrue(mid in 50..350, "half gone: $mid")
        val end = at(4000)
        assertTrue((0 until 10000).none { (end.data[it] ushr 24) > 0 }, "all gone and landed nowhere")
    }

    @Test
    fun `a rectangle area takes only its part`() {
        val src = Pixels(100, 100, IntArray(10000) { 0xFF0000FF.toInt() })
        for (y in 0 until 100) for (x in 50 until 100) src.data[y * 100 + x] = 0xFF00FF00.toInt()
        // the area: the left half's middle, holes where they left
        val v = RisingPixels.defaultValues(mapOf("area" to 1, "centerX" to -5000, "areaWidth" to 4000, "areaHeight" to 4000, "mode" to 1, "time" to 100000, "dissolveTime" to 10))
        val out = RisingPixels.apply(src, v, 1L)
        assertEquals(0, out.data[50 * 100 + 25], "the area is empty")
        assertEquals(0xFF00FF00.toInt(), out.data[50 * 100 + 75], "the rest is untouched")
        assertEquals(0xFF0000FF.toInt(), out.data[5 * 100 + 5])
    }

    @Test
    fun `shear slants the picture`() {
        val src = Pixels(100, 100)
        for (y in 0 until 100) src.data[y * 100 + 50] = -1 // a vertical line in the middle
        val out = Transform.apply(src, Transform.defaultValues(mapOf("shearX" to 4500, "smooth" to 0)), 0L)
        // 45°: at the top it is shifted one way, at the bottom the other, the middle stays
        fun lineAt(y: Int) = (0 until 100).first { out.data[y * 100 + it] == -1 }
        assertTrue(lineAt(50) in 49..50)
        assertTrue(lineAt(10) in 9..11, "top: ${lineAt(10)}")
        assertTrue(lineAt(90) in 89..91, "bottom: ${lineAt(90)}")
        assertTrue(Transform.apply(src, Transform.defaultValues(), 0L) === src)
    }

    @Test
    fun `holes are as big as the flying pixels`() {
        val src = cutOut()
        // 4 px pieces: every 4 x 4 block of the square is either whole or gone
        val v = RisingPixels.defaultValues(mapOf("mode" to 1, "size" to 400, "time" to 1500, "dissolveTime" to 30, "flightTime" to 1))
        val out = RisingPixels.apply(src, v, 3L)
        var gone = 0
        for (by in 60 until 80 step 4) for (bx in 40 until 60 step 4) {
            val states = (0 until 16).map { out.data[(by + it / 4) * 100 + bx + it % 4] == 0xFFFF0000.toInt() }.toSet()
            assertEquals(1, states.size, "block $bx,$by is whole or gone")
            if (false in states) gone++
        }
        assertTrue(gone in 3..22, "some gone: $gone")
    }

    @Test
    fun `the trail has as many copies as set`() {
        val src = cutOut()
        fun drawn(copies: Int) = RisingPixels.apply(src, RisingPixels.defaultValues(mapOf("count" to 40, "time" to 900, "flightTime" to 100, "rise" to 2000, "trailCopies" to copies, "trailLength" to 30, "fade" to 0, "spread" to 0, "wander" to 0)), 3L)
            .data.count { (it ushr 24) > 0 }
        assertTrue(drawn(5) > drawn(0) + 3, "copies drawn: ${drawn(5)} vs ${drawn(0)}")
    }
}
