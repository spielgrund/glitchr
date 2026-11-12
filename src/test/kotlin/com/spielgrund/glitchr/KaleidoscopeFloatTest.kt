package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Kaleidoscope
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertFalse

class KaleidoscopeFloatTest {
    private val src = Pixels(80, 60, IntArray(4800) { i -> (0xFF shl 24) or ((i % 80 * 3) shl 16) or ((i / 80 * 4) shl 8) or 90 })

    private fun run(vararg m: Pair<String, Int>) = Kaleidoscope.apply(src, Kaleidoscope.defaultValues(mapOf(*m)), 0L).data

    @Test
    fun `fractional settings change the picture smoothly`() {
        // between whole folds, levels and distances the picture is neither the one nor the other
        assertFalse(run("segments" to 650).contentEquals(run("segments" to 600)))
        assertFalse(run("segments" to 650).contentEquals(run("segments" to 700)))
        val one = run("levels" to 100); val half = run("levels" to 150); val two = run("levels" to 200)
        assertFalse(half.contentEquals(one)); assertFalse(half.contentEquals(two))
        assertFalse(run("levels" to 200, "innerDistance" to 3550).contentEquals(run("levels" to 200, "innerDistance" to 3500)))
    }
}
