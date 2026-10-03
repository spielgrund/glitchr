package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.SlitScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlitScanTest {
    private val src = testImage(240, 160)

    private fun run(vararg changes: Pair<String, Int>) = SlitScan.apply(src, SlitScan.defaultValues(mapOf(*changes)), 2L)

    @Test
    fun `rows slide further and further`() {
        // one pixel per line, linear up to 159 px: row y moves y pixels to the right
        val out = run("size" to 1, "offset" to 159)
        for (y in listOf(0, 40, 159)) for (x in 0 until 240) {
            assertEquals(src[Math.floorMod(x - y, 240), y], out[x, y], "row $y, x $x")
        }
    }

    @Test
    fun `negative offset slides the other way`() {
        val out = run("size" to 1, "offset" to -159)
        assertEquals(src[Math.floorMod(10 + 159, 240), 159], out[10, 159])
    }

    @Test
    fun `lines of several pixels move together`() {
        val out = run("size" to 20, "offset" to 70)
        // 8 lines: line k moves 10 k pixels
        for (y in 20 until 40) assertEquals(src[Math.floorMod(50 - 10, 240), y], out[50, y])
    }

    @Test
    fun `at 90 degrees the columns slide down`() {
        val out = run("size" to 1, "offset" to 239, "angle" to 90)
        // column x (counted from the right, the start at 90°) moves down
        val k = 239 - 100
        assertEquals(src[100, Math.floorMod(30 - k, 160)], out[100, 30])
    }

    @Test
    fun `exponential stays calm at first, random differs per seed`() {
        val exp = run("size" to 1, "offset" to 159, "curve" to 1)
        assertTrue((0 until 16).all { y -> (0 until 240).all { x -> exp[x, y] == src[x, y] } }, "hardly any offset at the top")
        val a = SlitScan.apply(src, SlitScan.defaultValues(mapOf("curve" to 2)), 1L)
        val b = SlitScan.apply(src, SlitScan.defaultValues(mapOf("curve" to 2)), 2L)
        assertTrue(!a.data.contentEquals(b.data))
    }

    @Test
    fun `the Y offset runs the picture through the lines`() {
        // 0°, one pixel per line, only Y: row y shows the source row y - y/2 (linear up to 79.5 px)
        val out = run("size" to 1, "offset" to 0, "offsetY" to 159)
        for (y in listOf(0, 60, 159)) for (x in 0 until 240 step 17) {
            assertEquals(src[x, Math.floorMod(y - y, 160)], out[x, y], "row $y")
        }
        val half = run("size" to 1, "offset" to 0, "offsetY" to -159)
        // negative: row y shows source row 2 y (wrapped)
        assertEquals(src[5, Math.floorMod(2 * 50, 160)], half[5, 50])
    }

    @Test
    fun `the picture can be shifted first and repeats at the edge`() {
        val out = run("offset" to 0, "shiftX" to 50, "shiftY" to -30)
        for (y in 0 until 160 step 13) for (x in 0 until 240 step 11) {
            assertEquals(src[Math.floorMod(x - 50, 240), Math.floorMod(y + 30, 160)], out[x, y])
        }
    }
}
