package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.FlowStrokes
import java.awt.geom.Point2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowBrushTest {
    /** A straight stroke across a 100 x 100 canvas at y = 50, a point every 5 px. */
    private val line = listOf((0..20).map { Point2D.Double(it * 0.05, 0.5) })

    @Test
    fun `the push brush moves the points under it, the middle most`() {
        val pushed = FlowStrokes.push(line, 100, 100, 50.0, 50.0, 20.0, 0.0, 10.0, 100.0)[0]
        // the point under the middle goes the whole way, those further out less, beyond the brush none
        assertEquals(0.6, pushed[10].y, 1e-9)
        assertTrue(pushed[8].y > 0.5 && pushed[8].y < 0.6)
        assertEquals(0.5, pushed[2].y, 1e-9)
        assertEquals(0.5, pushed[18].y, 1e-9)
    }

    @Test
    fun `pushed apart, the stroke gets points in between`() {
        val pushed = FlowStrokes.push(line, 100, 100, 50.0, 50.0, 30.0, 0.0, 40.0, 3.0)[0]
        assertTrue(pushed.size > line[0].size)
        assertTrue(pushed.zipWithNext().all { (a, b) -> a.distance(b) * 100 <= 3.0 + 1e-6 })
    }

    @Test
    fun `the smooth brush takes out a kink, the ends stay`() {
        val kinked = listOf(line[0].mapIndexed { i, p -> if (i == 10) Point2D.Double(p.x, 0.7) else p })
        var s = kinked
        repeat(10) { s = FlowStrokes.smooth(s, 100, 100, 50.0, 50.0, 30.0, 0.5) }
        assertTrue(s[0][10].y < 0.6, "kink smoothed: ${s[0][10].y}")
        assertEquals(kinked[0].first(), s[0].first())
        assertEquals(kinked[0].last(), s[0].last())
        // far from the brush nothing changes
        assertEquals(0.5, s[0][1].y, 1e-9)
    }
}
