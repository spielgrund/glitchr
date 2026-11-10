package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.LightStreaks
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.green
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class LightStreaksTest {
    private fun dot(cx: Int, cy: Int) = Pixels(120, 120, IntArray(120 * 120) { val x = it % 120; val y = it / 120
        if (x in cx - 1..cx + 1 && y in cy - 1..cy + 1) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })

    private fun run(src: Pixels, m: Map<String, Int>) = LightStreaks.apply(src, LightStreaks.defaultValues(m), 1)

    @Test
    fun `a star of four rays streaks along the axes, not between`() {
        val out = run(dot(60, 60), mapOf("rays" to 4, "angle" to 0, "length" to 60))
        for ((x, y) in listOf(90 to 60, 30 to 60, 60 to 30, 60 to 90)) assertTrue(green(out[x, y]) > 6, "ray at $x,$y")
        assertTrue(green(out[81, 81]) < 3, "between the rays")
    }

    @Test
    fun `one direction streaks to one side only`() {
        val out = run(dot(60, 60), mapOf("mode" to 2, "angle" to 0, "length" to 60))
        assertTrue(green(out[85, 60]) > 6, "right")
        assertTrue(green(out[35, 60]) < 3, "left")
    }

    @Test
    fun `a dark picture stays as it is`() {
        val dark = Pixels(60, 40, IntArray(60 * 40) { 0xFF303030.toInt() })
        for (mode in 0..2) assertContentEquals(dark.data, run(dark, mapOf("mode" to mode)).data, "mode $mode")
    }
}
