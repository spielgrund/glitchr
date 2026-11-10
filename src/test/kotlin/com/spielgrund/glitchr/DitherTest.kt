package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Dither
import com.spielgrund.glitchr.effects.ReduceColors
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.luma
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DitherTest {
    // a grey ramp from left to right, half transparent in the bottom row
    private val src = Pixels(128, 64, IntArray(128 * 64) { i ->
        val g = (i % 128) * 2
        val a = if (i / 128 == 63) 0x80 else 0xFF
        (a shl 24) or (g shl 16) or (g shl 8) or g
    })

    private fun run(m: Map<String, Int>) = Dither.apply(src, Dither.defaultValues(m), 1)

    @Test
    fun `every mode keeps 1 bit to black and white and the tones on average`() {
        for (mode in 0 until 14) {
            val out = run(mapOf("mode" to mode))
            assertTrue(out.data.all { it and 0xFFFFFF == 0 || it and 0xFFFFFF == 0xFFFFFF }, "mode $mode: two colors")
            // a band of columns: as bright on average as the ramp there
            for (x0 in listOf(16, 64, 100)) {
                val cols = x0 until x0 + 16
                val want = cols.sumOf { luma(src[it, 0]) } / 16.0
                val got = (0 until 60).sumOf { y -> cols.sumOf { luma(out[it, y]) } } / (60 * 16.0)
                assertTrue(kotlin.math.abs(want - got) < 40, "mode $mode at $x0: $got instead of $want")
            }
            assertEquals(0x80, out[5, 63] ushr 24, "transparency kept")
        }
    }

    @Test
    fun `without strength it just rounds`() {
        val out = run(mapOf("mode" to 1, "strength" to 0))
        assertTrue((0 until 128).all { x -> (out[x, 10] and 0xFF == 255) == (x * 2 >= 128) })
    }

    @Test
    fun `the colors come from the palette, two colors, levels`() {
        val gb = ReduceColors.fixed.getValue(4).toSet()
        assertTrue(run(mapOf("palette" to 3)).data.all { it and 0xFFFFFF in gb })
        val duo = run(mapOf("dark" to 0x102040, "light" to 0xF0E0A0)).data.map { it and 0xFFFFFF }.toSet()
        assertEquals(setOf(0x102040, 0xF0E0A0), duo)
        assertEquals(4, run(mapOf("levels" to 4)).data.map { it and 0xFF }.toSet().size)
    }

    @Test
    fun `big pixels are blocks of one color`() {
        val out = run(mapOf("pixel" to 4))
        for (y in 0 until 60 step 4) for (x in 0 until 128 step 4) {
            val c = out[x, y]
            assertTrue((0 until 4).all { dy -> (0 until 4).all { dx -> out[x + dx, y + dy] == c } })
        }
    }

    @Test
    fun `the blue noise ranks every cell once and spreads the first ones evenly`() {
        val tile = Dither.voidAndCluster(16)
        assertEquals(256, tile.toSet().size)
        val first = tile.indices.filter { tile[it] < 0.1 }
        // no two of the first tenth right next to each other
        for (a in first) for (b in first) if (a != b) {
            val dx = kotlin.math.abs(a % 16 - b % 16).let { minOf(it, 16 - it) }
            val dy = kotlin.math.abs(a / 16 - b / 16).let { minOf(it, 16 - it) }
            assertTrue(dx > 1 || dy > 1, "$a and $b touch")
        }
    }
}
