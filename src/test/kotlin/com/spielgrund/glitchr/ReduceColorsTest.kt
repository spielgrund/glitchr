package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.ReduceColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReduceColorsTest {
    private val src = testImage(160, 120)

    private fun colorsOf(vararg c: Pair<String, Int>) =
        ReduceColors.apply(src, ReduceColors.defaultValues(mapOf(*c)), 3L).data.map { it and 0xFFFFFF }.toSet()

    @Test
    fun `at most the chosen number of colors, with every dithering`() {
        for (dither in 0..4) for (n in listOf(2, 5, 16)) {
            val found = colorsOf("colors" to n, "dither" to dither)
            assertTrue(found.size in 2..n, "dither $dither, $n colors: ${found.size}")
        }
    }

    @Test
    fun `retro palettes use only their own colors`() {
        assertEquals(setOf(0x000000, 0xFFFFFF), colorsOf("palette" to 3, "dither" to 0))
        assertTrue(colorsOf("palette" to 4, "dither" to 2).all { it in setOf(0x0F380F, 0x306230, 0x8BAC0F, 0x9BBC0F) })
    }

    @Test
    fun `greys are grey`() {
        assertTrue(colorsOf("palette" to 1, "colors" to 4, "dither" to 0).all { (it shr 16) == (it and 0xFF) })
    }

    @Test
    fun `transparency is kept`() {
        val half = src.copy().also { p -> for (i in 0 until p.data.size / 2) p.data[i] = p.data[i] and 0x00FFFFFF }
        val out = ReduceColors.apply(half, ReduceColors.defaultValues(), 1L)
        assertTrue((0 until half.data.size).all { (out.data[it] ushr 24) == (half.data[it] ushr 24) })
    }
}
