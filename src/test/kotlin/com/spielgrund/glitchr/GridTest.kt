package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Grid
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GridTest {
    /** Left half red, right half blue. */
    private val halves = Pixels(120, 60).also { p ->
        for (y in 0 until 60) for (x in 0 until 120) p.data[y * 120 + x] = if (x < 60) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
    }
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun run(vararg c: Pair<String, Int>) = Grid.apply(halves, Grid.defaultValues(mapOf(*c)), 0L)

    @Test
    fun `one cell leaves the picture`() = assertContentEquals(halves.data, run("columns" to 1, "rows" to 1).data)

    @Test
    fun `every cell holds the whole picture`() {
        val out = run("columns" to 3, "rows" to 2)
        // cells 40×30: red left half, blue right half of every cell
        for (c in 0 until 3) for (r in 0 until 2) {
            assertEquals(red, out[c * 40 + 5, r * 30 + 15])
            assertEquals(blue, out[c * 40 + 35, r * 30 + 15])
        }
    }

    @Test
    fun `mirroring every second column`() {
        val out = run("columns" to 2, "rows" to 1, "mirror" to 1)
        assertEquals(red, out[5, 30])
        assertEquals(blue, out[65, 30], "second copy mirrored: blue on its left")
    }

    @Test
    fun `gaps are empty`() {
        val out = run("columns" to 2, "rows" to 2, "gap" to 10, "gapFill" to 1, "gapColor" to 0x00FF00)
        assertEquals(0xFF00FF00.toInt(), out[60, 10], "between the columns")
        assertEquals(0xFF00FF00.toInt(), out[2, 30], "at the outer edge")
        assertEquals(red, out[10, 10])
    }

    @Test
    fun `row offset shifts every second row`() {
        val out = run("columns" to 2, "rows" to 2, "rowOffset" to 50)
        // second row shifted by half a cell (30 px): at x 35 the cell starting at 30 begins, red
        assertEquals(red, out[35, 45])
        assertEquals(blue, out[25, 45])
    }

    @Test
    fun `turned copies`() {
        // half a turn: every copy has red and blue swapped
        val out = run("columns" to 3, "rows" to 2, "tileRotation" to 18000)
        assertEquals(blue, out[5, 15])
        assertEquals(red, out[35, 15])
        // alternating: the neighbour turns the other way, which for half a turn looks the same
        val alt = run("columns" to 2, "rows" to 1, "tileRotation" to 9000, "rotationPattern" to 1, "fit" to 1, "gapFill" to 1)
        assertEquals(red, alt[30, 15], "first copy turned right: red on top")
        assertEquals(blue, alt[90, 15], "second copy turned left: blue on top")
        // turning the whole grid by half a turn turns the picture
        val grid = run("columns" to 1, "rows" to 1, "gridRotation" to 18000)
        assertEquals(blue, grid[5, 30])
    }
}
