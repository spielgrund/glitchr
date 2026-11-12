package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Fireflies
import com.spielgrund.glitchr.effects.Shatter
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertTrue

/** Directions count as everywhere: 0° = right, 90° = down, 270° = up. */
class DirectionTest {
    @Test
    fun `fireflies fly where the direction points`() {
        val dark = Pixels(100, 100, IntArray(10000) { 0xFF000000.toInt() })
        fun light(dir: Int, left: Boolean, top: Boolean): Int {
            val out = Fireflies.apply(dark, Fireflies.defaultValues(mapOf("source" to 1, "direction" to dir, "spread" to 1000, "wander" to 0, "glow" to 0, "time" to 3000)), 2L)
            var sum = 0
            for (y in 0 until 100) for (x in 0 until 100) {
                if ((x < 50) == left && (y < 50) == top) sum += out.data[y * 100 + x] and 0xFF
            }
            return sum
        }
        // 0° to the right, 90° down
        assertTrue(light(0, left = false, top = true) + light(0, left = false, top = false) > 3 * (light(0, left = true, top = true) + light(0, left = true, top = false)))
        assertTrue(light(9000, left = true, top = false) + light(9000, left = false, top = false) > 3 * (light(9000, left = true, top = true) + light(9000, left = false, top = true)))
        // the default rises
        assertTrue(Fireflies.defaultValues()["direction"] == 27000)
    }

    @Test
    fun `shatter pieces fly down at 90 degrees`() {
        // the top half of the picture, the bottom half transparent
        val src = Pixels(100, 100)
        for (i in 0 until 5000) src.data[i] = 0xFFFFFFFF.toInt()
        fun below(dir: Int) = Shatter.apply(src, Shatter.defaultValues(mapOf("direction" to dir, "scatter" to 0, "progress" to 5000, "distance" to 100)), 1L)
            .data.copyOfRange(6000, 10000).count { (it ushr 24) > 0 }
        assertTrue(below(90) > below(270) + 50, "down ${below(90)} vs up ${below(270)}")
    }
}
