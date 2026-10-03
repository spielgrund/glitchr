package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Flow
import com.spielgrund.glitchr.effects.FlowStrokes
import java.awt.geom.Point2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowTest {
    private val src = testImage(240, 160)

    private fun run(strokes: String = "", vararg changes: Pair<String, Int>) =
        Flow.apply(src, Flow.defaultValues(mapOf(*changes), mapOf("strokes" to strokes)), 0)

    @Test
    fun `strokes survive writing and reading`() {
        val strokes = listOf(listOf(Point2D.Double(0.1, 0.2), Point2D.Double(0.5, 0.25)), listOf(Point2D.Double(0.9, 0.9), Point2D.Double(0.8, 0.1)))
        assertEquals(strokes, FlowStrokes.parse(FlowStrokes.format(strokes)))
        assertEquals(emptyList(), FlowStrokes.parse(""))
    }

    @Test
    fun `without strokes everything flows in the base direction and repeats`() {
        for (method in 0..1) {
            // 25 % of the 240 px wide picture
            val out = run("", "shift" to 25, "method" to method, "edge" to 0)
            for (y in 0 until 160 step 11) for (x in 0 until 240 step 7) {
                assertEquals(src[Math.floorMod(x - 60, 240), y], out[x, y], "method $method at $x,$y")
            }
        }
        val down = run("", "shift" to 25, "baseAngle" to 90)
        assertEquals(src[50, Math.floorMod(5 - 40, 160)], down[50, 5])
        // 100 % is one full repetition: the picture is back in place
        assertTrue(run("", "shift" to 100, "method" to 1).data.contentEquals(src.data))
    }

    @Test
    fun `with standstill only the picture near a stroke moves`() {
        // a stroke from left to right through the middle
        val out = run("0.1,0.5 0.9,0.5", "outside" to 1, "reach" to 15, "shift" to 10)
        assertTrue((0 until 240).all { x -> out[x, 5] == src[x, 5] }, "far from the stroke everything stays")
        assertEquals(src[100, 80], out[124, 80], "at the stroke the picture moves along")
    }

    @Test
    fun `a curved stroke is followed around the bend`() {
        // up the left side, then to the right along the top: the flow bends
        val out = run("0.2,0.9 0.2,0.2 0.8,0.2", "reach" to 30, "shift" to 20)
        assertTrue(!out.data.contentEquals(src.data))
    }

    @Test
    fun `sections all move forward as the offset grows`() {
        // flow to the right, sections of 40 px: raising the offset by 10 % moves the content 4 px right
        // everywhere except where a section just starts over
        val a = run("", "mode" to 1, "section" to 40, "shift" to 10, "edge" to 0)
        val b = run("", "mode" to 1, "section" to 40, "shift" to 20, "edge" to 0)
        var same = 0
        var total = 0
        for (y in 0 until 160 step 5) for (x in 0 until 236) {
            total++
            if (b[x + 4, y] == a[x, y]) same++
        }
        assertTrue(same > total * 0.8, "forwards: $same of $total")
        // within a section the picture is moved, not smeared: neighbouring pixels differ like in the source
        assertTrue((0 until 160).any { y -> a[20, y] != a[21, y] })
        val soft = run("", "mode" to 1, "section" to 40, "shift" to 10, "edge" to 1)
        assertTrue(!soft.data.contentEquals(a.data))
    }

    @Test
    fun `in the loop the picture runs along the arrow and jumps from its tip to its tail`() {
        // stroke from x 24 to 216 through the middle (192 px long), band 20 px wide; 25 % = 48 px
        val out = run("0.1,0.5 0.9,0.5", "mode" to 2, "reach" to 20, "edge" to 0, "shift" to 25)
        assertEquals(src[52, 80], out[100, 80], "runs forwards")
        assertEquals(src[52, 90], out[100, 90], "the same next to the stroke within the band")
        assertEquals(src[184, 80], out[40, 80], "from the arrow end to the start")
        assertTrue((0 until 240).all { x -> out[x, 20] == src[x, 20] }, "outside the band everything stays")
        assertTrue(run("0.1,0.5 0.9,0.5", "mode" to 2, "reach" to 20, "edge" to 0, "shift" to 100).data.contentEquals(src.data), "100 % = once around")
    }

    @Test
    fun `the loop can run in sections and fades softly without stretching`() {
        // sections of 48 px along the 192 px stroke, all in step: 50 % = 24 px, wrapping inside each section
        val out = run("0.1,0.5 0.9,0.5", "mode" to 3, "section" to 48, "spread" to 0, "reach" to 20, "edge" to 0, "shift" to 50)
        assertEquals(src[80, 80], out[104, 80], "forwards in section 48..96")
        assertEquals(src[100, 80], out[76, 80], "from the section end to its start")
        val spread = run("0.1,0.5 0.9,0.5", "mode" to 3, "section" to 48, "spread" to 100, "reach" to 20, "edge" to 0, "shift" to 50)
        assertTrue(!spread.data.contentEquals(out.data), "offset sections")
        // soft, without any shift: past the arrow's ends nothing is smeared
        val still = run("0.1,0.5 0.9,0.5", "mode" to 2, "reach" to 20, "edge" to 1, "shift" to 0)
        for (x in listOf(10, 20, 220, 230)) assertEquals(src[x, 80], still[x, 80], "arrow end at $x")
        val soft = run("0.1,0.5 0.9,0.5", "mode" to 3, "section" to 48, "reach" to 20, "edge" to 1, "shift" to 50)
        assertTrue(!soft.data.contentEquals(spread.data))
    }
}
