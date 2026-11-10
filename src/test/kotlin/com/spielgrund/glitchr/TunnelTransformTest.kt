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
}
