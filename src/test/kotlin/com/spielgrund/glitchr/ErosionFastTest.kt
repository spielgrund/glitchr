package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.ErosionFast
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ErosionFastTest {
    /** Black with a white square in the upper middle. */
    private val square = Pixels(120, 120).also { p ->
        p.data.fill(0xFF000000.toInt())
        for (y in 20 until 50) for (x in 45 until 75) p.data[y * 120 + x] = -1
    }

    /** Nothing shown: no streaks, no light, no rivers. */
    private val quiet = mapOf("streak" to 0, "light" to 0, "rivers" to 0)

    private fun run(img: Pixels, vararg c: Pair<String, Int>) = ErosionFast.apply(img, ErosionFast.defaultValues(quiet + mapOf(*c)), 3L)

    private fun g(c: Int) = c shr 8 and 0xFF

    @Test
    fun `with nothing shown the picture stays`() {
        val src = testImage(160, 100)
        assertContentEquals(src.data, run(src).data)
    }

    @Test
    fun `by angle the colors are pulled downstream`() {
        val down = run(square, "flow" to 2, "angle" to 90, "streak" to 200)
        // the white is pulled below the square, nothing of it reaches the rows above
        val below = (50 until 60).sumOf { y -> (50 until 70).sumOf { x -> g(down[x, y]) } }
        val above = (5 until 15).sumOf { y -> (50 until 70).sumOf { x -> g(down[x, y]) } }
        assertTrue(below > 2 * above + 1000, "below $below, above $above")
    }

    @Test
    fun `rivers branch through the picture`() {
        val src = testImage(200, 150)
        val out = run(src, "rivers" to 3, "riverColor" to 0xFF0000, "riverStrength" to 100, "density" to 80)
        val red = out.data.count { (it shr 16 and 0xFF) > 200 && (it shr 8 and 0xFF) < 60 && (it and 0xFF) < 60 }
        assertTrue(red in 200 until out.data.size / 2, "rivers: $red")
    }

    @Test
    fun `more generations carve deeper valleys`() {
        val src = testImage(200, 150)
        fun lit(gen: Int) = run(src, "light" to 100, "generations" to gen, "strength" to 100)
        assertTrue(!lit(1).data.contentEquals(lit(30).data))
    }

    @Test
    fun `drawn arrows limit the erosion and can lead the water`() {
        val src = testImage(200, 150)
        fun fast(flow: Int) = ErosionFast.apply(
            src, ErosionFast.defaultValues(mapOf("areaWidth" to 20, "flow" to flow, "streak" to 60), mapOf("strokes" to "0.1,0.2 0.9,0.2")), 3L,
        )
        for (flow in listOf(0, 3)) {
            val out = fast(flow)
            assertTrue((0 until 200).all { x -> (100 until 150).all { y -> out[x, y] == src[x, y] } }, "flow $flow: far away everything stays")
            assertTrue((20 until 180).any { x -> out[x, 30] != src[x, 30] }, "flow $flow: eroded at the arrow")
        }
    }

    @Test
    fun `random landscapes differ with the seed`() {
        val src = testImage(160, 120)
        fun out(seed: Long) = ErosionFast.apply(src, ErosionFast.defaultValues(mapOf("flow" to 4, "streak" to 60, "precision" to 0)), seed)
        assertTrue(!out(1).data.contentEquals(src.data), "random erodes")
        assertTrue(!out(1).data.contentEquals(out(2).data), "different seed, different landscape")
    }
}
