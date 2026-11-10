package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.LightRays
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.green
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class LightRaysTest {
    private fun run(src: Pixels, m: Map<String, Int>) = LightRays.apply(src, LightRays.defaultValues(mapOf("centerX" to 0, "centerY" to 0, "breakup" to 0) + m), 1)

    @Test
    fun `rays shoot away from the point`() {
        val dot = Pixels(120, 120, IntArray(120 * 120) { val x = it % 120; val y = it / 120
            if (x in 89..91 && y in 59..61) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })
        val out = run(dot, mapOf("length" to 60, "strength" to 300))
        assertTrue(green(out[108, 60]) > 4, "outwards")
        assertTrue(green(out[72, 60]) < 3, "not back towards the point")
    }

    @Test
    fun `a light behind shines through the bright gaps, the dark parts cast shadows`() {
        // dark on the left half, bright on the right
        val half = Pixels(120, 120, IntArray(120 * 120) { if (it % 120 < 60) 0xFF101010.toInt() else 0xFFC0C0C0.toInt() })
        val out = run(half, mapOf("source" to 1, "lightSize" to 30, "threshold" to 50, "raysOnly" to 1))
        assertTrue(green(out[100, 60]) > 20, "through the bright side")
        assertTrue(green(out[20, 60]) < 3, "shadow on the dark side")
    }

    @Test
    fun `a dark picture stays as it is, the breakup and time change the rays`() {
        val dark = Pixels(60, 40, IntArray(60 * 40) { 0xFF303030.toInt() })
        assertContentEquals(dark.data, run(dark, emptyMap()).data)
        val bright = Pixels(80, 60, IntArray(80 * 60) { if ((it % 80) / 8 % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })
        val broken = mapOf("breakup" to 80)
        assertTrue(!run(bright, broken + ("time" to 0)).data.contentEquals(run(bright, broken + ("time" to 6000)).data))
    }
}
