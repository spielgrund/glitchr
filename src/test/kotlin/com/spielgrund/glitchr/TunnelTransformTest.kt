package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.TunnelTransform
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunnelTransformTest {
    /** A white canvas with a black dot of radius 3 at the middle and a black ring of radius 40. */
    private val src = Pixels(120, 120, IntArray(120 * 120) { i ->
        val r = Math.hypot(i % 120 + 0.5 - 60, i / 120 + 0.5 - 60)
        if (r < 3 || kotlin.math.abs(r - 40) < 1.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    })

    private fun turn(vararg c: Pair<String, Int>) = TunnelTransform.apply(src, TunnelTransform.defaultValues(mapOf("radius" to 8000, *c)), 1L)

    /** Middle of the dark pixels in the rows around y = 60 within [x0, x1]. */
    private fun darkX(p: Pixels, x0: Int, x1: Int): Double {
        val xs = (x0..x1).filter { x -> (58..61).any { y -> (p[x, y] and 0xFF) < 100 } }
        return xs.average()
    }

    @Test
    fun `the far end swings furthest, nearer rings less, the edge stays`() {
        val right = turn("turnX" to 3333)
        // the dot (the far end) moves right by a good bit
        val dot = darkX(right, 45, 90)
        assertTrue(dot > 70, "dot moved right: $dot")
        // the ring's left side moves right too, but less
        val ringLeft = darkX(right, 10, 35)
        assertTrue(ringLeft > 20.5 && ringLeft - 20 < dot - 60, "ring moves less: $ringLeft")
        // the corners lie beyond the radius: untouched
        assertEquals(src[2, 2], right[2, 2])
        assertEquals(src[117, 117], right[117, 117])
        // to the left and up it goes the other way; no turn changes nothing
        assertTrue(darkX(turn("turnX" to -3333), 30, 75) < 50, "left")
        val up = turn("turnX" to 0, "turnY" to -4167)
        assertTrue((25..50).any { y -> (up[60, y] and 0xFF) < 100 } && (up[60, 60] and 0xFF) > 100, "up")
        assertTrue(turn("turnX" to 0).data.contentEquals(src.data))
        // the handle is where the far end swings to
        assertEquals(com.spielgrund.glitchr.effects.CanvasHandle("turnX", "turnY", percent = true), TunnelTransform.canvasHandle)
        assertTrue(TunnelTransform.params.none { it.key == "centerX" })
    }

    @Test
    fun `square, the rings are squares`() {
        // a gradient from left to right, so any shift sideways shows
        val ramp = Pixels(120, 120, IntArray(120 * 120) { i -> val g = i % 120 * 2; (0xFF shl 24) or (g shl 16) or (g shl 8) or g })
        fun turn(shape: Int) = TunnelTransform.apply(ramp, TunnelTransform.defaultValues(mapOf("radius" to 7000, "turnX" to 3333, "shape" to shape)), 1L)
        val round = turn(0)
        val square = turn(1)
        // radius 70 %: round 59 px (of the half diagonal), square 42 px (of half the side)
        // (110, 60) lies 50 px out to the side: inside the circle, beyond the square
        assertTrue((round[110, 60] and 0xFF) < (ramp[110, 60] and 0xFF), "round moves it")
        assertEquals(ramp[110, 60], square[110, 60])
        // towards the corner the square reaches as far as the circle: (95, 95) moves
        assertTrue((square[95, 95] and 0xFF) < (ramp[95, 95] and 0xFF), "square moves the diagonal")
        // mirrored top to bottom, the same
        assertEquals(square[95, 95], square[95, 24])
    }

    @Test
    fun `copies nest smaller and smaller, the deepest moved furthest`() {
        fun copies(vararg c: Pair<String, Int>) = TunnelTransform.apply(src, TunnelTransform.defaultValues(mapOf("mode" to 1, *c)), 1L)
        // their part of the picture, turned right by 50 %: the dot in the middle goes with the deepest copy, 30 px over
        val part = copies("content" to 1, "turnX" to 5000)
        assertTrue((part[90, 60] and 0xFF) < 100 && (part[60, 60] and 0xFF) > 100, "dot moved")
        // the outermost copy is the picture itself: the corner stays
        assertEquals(src[1, 1] and 0xFFFFFF, part[1, 1] and 0xFFFFFF)
        // soft edges: on a gradient each copy, moved over, jumps against the one around it – blended softly, no more
        val ramp = Pixels(120, 120, IntArray(120 * 120) { i -> val g = i % 120 * 2; (0xFF shl 24) or (g shl 16) or (g shl 8) or g })
        fun cut(soft: Int) = TunnelTransform.apply(ramp, TunnelTransform.defaultValues(mapOf("mode" to 1, "content" to 1, "turnX" to 10000, "soft" to soft)), 1L)
        fun jump(p: Pixels) = (1 until 120).maxOf { x -> kotlin.math.abs((p[x, 60] and 0xFF) - (p[x - 1, 60] and 0xFF)) }
        assertTrue(jump(cut(0)) > 8, "hard edges jump: ${jump(cut(0))}")
        assertTrue(jump(cut(30)) * 2 < jump(cut(0)), "soft edges blend: ${jump(cut(30))} against ${jump(cut(0))}")
        // the whole picture made small: at half the size the ring of radius 40 comes back at 20 px (between copies 2 and 1)
        val whole = copies("scale" to 50)
        assertTrue((77..82).minOf { whole[it, 60] and 0xFF } < 128 && (77..82).all { (src[it, 60] and 0xFF) > 128 }, "ring copied smaller")
        assertTrue(TunnelTransform.params.first { it.key == "radius" }.shownFor == ("mode" to setOf(0)))
    }
}
