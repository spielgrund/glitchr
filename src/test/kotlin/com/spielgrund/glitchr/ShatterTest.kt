package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Shatter
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShatterTest {
    private val src = Pixels(120, 80, IntArray(120 * 80) { i -> (0xFF shl 24) or ((i % 120) * 2 shl 16) or ((i / 120) * 3 shl 8) or 90 })

    private fun run(m: Map<String, Int>) = Shatter.apply(src, Shatter.defaultValues(m), 4)
    private fun gone(p: Pixels) = p.data.count { it ushr 24 == 0 }

    @Test
    fun `nothing at 0 percent, everything gone at 100, more and more in between`() {
        assertContentEquals(src.data, run(mapOf("progress" to 0)).data)
        assertEquals(src.data.size, gone(run(mapOf("progress" to 10000))))
        val counts = listOf(2000, 4000, 6000, 8000).map { gone(run(mapOf("progress" to it))) }
        assertTrue(counts.zipWithNext().all { (a, b) -> a < b }, "$counts")
    }

    @Test
    fun `assemble plays it backwards`() {
        assertContentEquals(src.data, run(mapOf("progress" to 10000, "assemble" to 1)).data)
        assertContentEquals(run(mapOf("progress" to 3000)).data, run(mapOf("progress" to 7000, "assemble" to 1)).data)
    }

    @Test
    fun `the front starts on its side`() {
        // the default front runs to the left: the right side goes first
        val p = run(mapOf("progress" to 2500, "ragged" to 0))
        fun goneIn(x0: Int, x1: Int) = (0 until 80).sumOf { y -> (x0 until x1).count { x -> p[x, y] ushr 24 == 0 } }
        assertTrue(goneIn(90, 120) > 0 && goneIn(0, 30) == 0)
    }

    @Test
    fun `every pixel belongs to a piece, dust is finer than shards`() {
        for (shape in 0..3) {
            val (label, count) = Shatter.pieces(120, 80, shape, 20, 0.8, 0.3, 1)
            assertTrue(label.all { it in 0 until count })
        }
        val shards = Shatter.pieces(120, 80, 0, 20, 0.8, 0.0, 1).first.toSet().size
        val dust = Shatter.pieces(120, 80, 3, 20, 0.8, 0.0, 1).first.toSet().size
        assertTrue(dust > shards * 10, "$dust vs $shards")
    }

    @Test
    fun `a color or the darkened picture behind`() {
        val col = run(mapOf("progress" to 10000, "behind" to 1, "behindColor" to 0x102030))
        assertTrue(col.data.all { it == 0xFF102030.toInt() })
        val dark = run(mapOf("progress" to 10000, "behind" to 2))
        assertEquals(90 * 35 / 100, dark.data[500] and 0xFF, "a third as bright")
    }
}
