package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Glass
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class GlassTest {
    /** Diagonal color stripes, 120 × 90. */
    private val stripes = Pixels(120, 90, IntArray(120 * 90) { i ->
        val t = ((i % 120 + i / 120) / 6) % 3
        when (t) {
            0 -> argb(255, 220, 40, 40)
            1 -> argb(255, 40, 200, 60)
            else -> argb(255, 40, 60, 220)
        }
    })

    @Test
    fun `every glass and every combination bends the picture, reproducibly`() {
        for (p1 in 1..8) for (p2 in listOf(0, 2, 6)) {
            val v = Glass.defaultValues(mapOf("pattern1" to p1, "pattern2" to p2, "size1" to 30, "size2" to 10))
            val out = Glass.apply(stripes, v, 3L)
            assertContentEquals(out.data, Glass.apply(stripes, v, 3L).data, "pattern $p1 + $p2")
            val changed = (0 until 120 * 90).count { out.data[it] != stripes.data[it] }
            assertTrue(changed > 2000, "pattern $p1 + $p2: $changed")
        }
    }

    @Test
    fun `no pattern and no tint, lines or light leaves the picture as it is`() {
        val v = Glass.defaultValues(
            mapOf("pattern1" to 0, "pattern2" to 0, "tintAmount" to 0, "dispersion" to 0, "lines" to 0, "shading" to 0, "gloss" to 0),
        )
        assertContentEquals(stripes.data, Glass.apply(stripes, v, 3L).data)
    }

    @Test
    fun `glass block joints are drawn and anti-aliased`() {
        val base = mapOf("pattern1" to 1, "pattern2" to 0, "size1" to 30, "lines" to -100, "lineWidth" to 30, "shading" to 0, "gloss" to 0, "tintAmount" to 0)
        val out = Glass.apply(stripes, Glass.defaultValues(base), 3L)
        // on the joint between two blocks (x = 30) it is nearly black
        val c = out[30, 15]
        assertTrue(maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) < 60, Integer.toHexString(c))
        fun colors(aa: Int) = Glass.apply(stripes, Glass.defaultValues(base + ("antialias" to aa)), 3L).data.toSet().size
        assertTrue(colors(2) > colors(0), "smooth ${colors(2)}, hard ${colors(0)}")
    }

    @Test
    fun `the offset shifts every strip by its own amount`() {
        val base = mapOf("pattern1" to 2, "size1" to 20, "strength1" to 0, "shading" to 0, "gloss" to 0, "tintAmount" to 0, "lines" to 0, "dispersion" to 0)
        val still = Glass.apply(stripes, Glass.defaultValues(base), 3L)
        val shifted = Glass.apply(stripes, Glass.defaultValues(base + ("offset" to 30)), 3L)
        val changed = (0 until 120 * 90).count { shifted.data[it] != still.data[it] }
        assertTrue(changed > 3000, "offset $changed")
    }
}
