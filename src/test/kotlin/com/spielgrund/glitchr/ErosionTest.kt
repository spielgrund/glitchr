package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Erosion
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ErosionTest {
    /** Black with a white square in the upper middle. */
    private val square = Pixels(120, 120).also { p ->
        p.data.fill(0xFF000000.toInt())
        for (y in 20 until 50) for (x in 45 until 75) p.data[y * 120 + x] = -1
    }

    private fun run(img: Pixels, vararg c: Pair<String, Int>) = Erosion.apply(img, Erosion.defaultValues(mapOf(*c)), 3L)

    private fun g(c: Int) = c shr 8 and 0xFF

    @Test
    fun `without strength nothing moves`() {
        assertContentEquals(square.data, run(square, "strength" to 0, "channels" to 0).data)
    }

    @Test
    fun `by angle the colors are washed in that direction`() {
        val down = run(square, "flow" to 2, "angle" to 90, "channels" to 0)
        val below = (55 until 70).sumOf { y -> (50 until 70).sumOf { x -> g(down[x, y]) } }
        val above = (5 until 18).sumOf { y -> (50 until 70).sumOf { x -> g(down[x, y]) } }
        assertTrue(below > 1000 && above < below / 4, "unten $below, oben $above")
    }

    @Test
    fun `by height the bright material runs down into the dark`() {
        val out = run(square, "flow" to 0, "channels" to 0, "generations" to 40)
        val ring = (52 until 58).sumOf { y -> (45 until 75).sumOf { x -> g(out[x, y]) } }
        assertTrue(ring > 200, "Rand $ring")
    }

    @Test
    fun `more generations carry further`() {
        fun reach(gen: Int) = run(square, "flow" to 2, "angle" to 90, "channels" to 0, "generations" to gen)
            .let { out -> (50 until 120).count { y -> g(out[60, y]) > 30 } }
        assertTrue(reach(60) > reach(5), "weiter: ${reach(60)} > ${reach(5)}")
    }

    @Test
    fun `coarser precision washes the same way and keeps the picture's detail`() {
        // on this small test picture 8 px would leave only 15 × 15 cells, so up to 4 px
        for (precision in 1..2) {
            val down = run(square, "flow" to 2, "angle" to 90, "channels" to 0, "precision" to precision)
            val below = (55 until 70).sumOf { y -> (50 until 70).sumOf { x -> g(down[x, y]) } }
            val above = (5 until 18).sumOf { y -> (50 until 70).sumOf { x -> g(down[x, y]) } }
            assertTrue(below > 1000 && above < below / 4, "Genauigkeit $precision: unten $below, oben $above")
        }
        // untouched by the erosion, the full picture stays exactly as it was
        assertContentEquals(square.data, run(square, "strength" to 0, "channels" to 0, "precision" to 2).data)
    }

    @Test
    fun `drawn arrows limit the erosion to their surroundings`() {
        val src = testImage(200, 150)
        // one arrow across the upper part, 20 px wide area
        val out = Erosion.apply(src, Erosion.defaultValues(mapOf("areaWidth" to 20, "precision" to 0), mapOf("strokes" to "0.1,0.2 0.9,0.2")), 3L)
        assertTrue((0 until 200).all { x -> (100 until 150).all { y -> out[x, y] == src[x, y] } }, "weit weg bleibt alles")
        assertTrue((20 until 180).any { x -> out[x, 30] != src[x, 30] }, "am Pfeil wird erodiert")
        // flowing along the arrows works too
        val along = Erosion.apply(src, Erosion.defaultValues(mapOf("flow" to 3, "areaWidth" to 30), mapOf("strokes" to "0.1,0.5 0.9,0.5")), 3L)
        assertTrue(!along.data.contentEquals(src.data))
    }

    @Test
    fun `random landscapes differ with the seed`() {
        val src = testImage(160, 120)
        fun out(seed: Long) = Erosion.apply(src, Erosion.defaultValues(mapOf("flow" to 4, "streak" to 60, "precision" to 0)), seed)
        assertTrue(!out(1).data.contentEquals(src.data), "Zufall erodiert")
        assertTrue(!out(1).data.contentEquals(out(2).data), "anderer Zufall, andere Landschaft")
    }
}
