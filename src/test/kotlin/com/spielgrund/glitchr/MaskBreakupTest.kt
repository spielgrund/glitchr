package com.spielgrund.glitchr

import com.spielgrund.glitchr.model.Mask
import com.spielgrund.glitchr.model.MaskBreakup
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.ScatterStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskBreakupTest {
    /** A hard-edged mask: full effect in the left half of a 100×100 canvas, none in the right. */
    private fun mask(breakup: MaskBreakup) = Mask().apply {
        mode = MaskMode.LINEAR
        linearStart = RelPoint(0.49, 0.5)
        linearEnd = RelPoint(0.51, 0.5)
        hardEdge = true
        threshold = 50
        this.breakup = breakup
    }

    private fun values(m: Mask): Array<FloatArray> {
        val shape = m.shape()
        return Array(100) { y -> FloatArray(100).also { shape.row(y, 100, 100, it) } }
    }

    /** How many pixels on the "wrong" side of the edge (with a margin of [margin] px). */
    private fun crossed(v: Array<FloatArray>, margin: Int) =
        (0 until 100).sumOf { y -> (0 until 50 - margin).count { v[y][it] < 0.5f } + (50 + margin until 100).count { v[y][it] > 0.5f } }

    @Test
    fun `without breakup the edge stays straight`() {
        assertEquals(0, crossed(values(mask(MaskBreakup())), 2))
    }

    @Test
    fun `noise speckles the mask`() {
        val v = values(mask(MaskBreakup(noise = 100)))
        assertTrue(crossed(v, 2) > 1000, "speckles on both sides: ${crossed(v, 2)}")
        // in grains of 4 px the speckles come in blocks
        val coarse = values(mask(MaskBreakup(noise = 100, grain = 4)))
        assertTrue((0 until 100 step 4).all { y -> (0 until 100 step 4).filter { it !in 44..56 }.all { x -> coarse[y][x] == coarse[y + 3][x + 3] } })
    }

    @Test
    fun `every scatter style tears up the edge, but not far away`() {
        for (style in ScatterStyle.entries) {
            val v = values(mask(MaskBreakup(scatter = 10, style = style, runLength = 8)))
            val near = crossed(v, 2)
            // only sideways moves break a vertical edge
            if (style != ScatterStyle.COLUMNS && style != ScatterStyle.STREAKS_Y) assertTrue(near > 20, "$style breaks the edge: $near")
            // streaks smear a value along their length (up to 1.5 × 8 px) on top of the scatter
            val reach = if (style == ScatterStyle.STREAKS_X) 10 + 12 else 10
            assertEquals(0, crossed(v, reach + 1), "$style stays within its reach")
        }
    }

    @Test
    fun `rows shift as a whole`() {
        val v = values(mask(MaskBreakup(scatter = 20, style = ScatterStyle.ROWS)))
        for (y in 0 until 100) {
            val edge = (0 until 100).first { v[y][it] < 0.5f }
            assertTrue((0 until edge).all { v[y][it] > 0.5f } && (edge until 100).all { v[y][it] < 0.5f }, "row $y has one edge")
        }
        assertTrue((0 until 100).map { y -> (0 until 100).first { v[y][it] < 0.5f } }.toSet().size > 5, "rows at different places")
    }
}
