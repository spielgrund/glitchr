package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Filler
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillerTest {
    /** 4 × 3 tiles of 20 px, each its own grey (with a slight gradient), separated by 2 px black lines. */
    private val tiles = Pixels(88, 66, IntArray(88 * 66) { i ->
        val x = i % 88
        val y = i / 88
        if (x % 22 >= 20 || y % 22 >= 20) argb(255, 0, 0, 0)
        else {
            val g = 60 + ((x / 22) * 3 + y / 22) * 15 + (x % 22) / 4
            argb(255, g, g, g)
        }
    })

    private fun tileColors(p: Pixels) = (0 until 3).flatMap { ty -> (0 until 4).map { tx -> p[tx * 22 + 10, ty * 22 + 10] } }

    @Test
    fun `every tile is found as an area and filled evenly with its own color`() {
        for (finder in listOf(0, 4)) {
            val out = Filler.apply(tiles, Filler.defaultValues(mapOf("finder" to finder, "fill" to 1)), 3L)
            for (ty in 0 until 3) for (tx in 0 until 4) {
                val c = out[tx * 22 + 10, ty * 22 + 10]
                // the whole tile (its gradient too) got one color
                for (y in 2 until 18) for (x in 2 until 18) assertEquals(c, out[tx * 22 + x, ty * 22 + y], "method $finder, tile $tx/$ty")
            }
            assertTrue(tileColors(out).toSet().size >= 10, "method $finder: ${tileColors(out).toSet().size} colors")
        }
    }

    @Test
    fun `the same seed fills the same, another seed differently`() {
        val v = Filler.defaultValues()
        assertContentEquals(Filler.apply(tiles, v, 3L).data, Filler.apply(tiles, v, 3L).data)
        assertTrue(!Filler.apply(tiles, v, 3L).data.contentEquals(Filler.apply(tiles, v, 4L).data))
    }

    @Test
    fun `all fills and finders work`() {
        for (finder in 0..4) for (fill in 0..5) {
            val out = Filler.apply(tiles, Filler.defaultValues(mapOf("finder" to finder, "fill" to fill, "lower" to 50)), 3L)
            assertEquals(tiles.data.size, out.data.size)
        }
        // grey fills are grey
        val grey = Filler.apply(tiles, Filler.defaultValues(mapOf("fill" to 0)), 3L)
        for (c in tileColors(grey)) assertTrue((c shr 16 and 0xFF) == (c and 0xFF), Integer.toHexString(c))
    }

    @Test
    fun `small areas join their neighbours or stay`() {
        // a single odd pixel in the middle of a tile
        val dotted = Pixels(88, 66, tiles.data.copyOf()).apply { data[10 * 88 + 10] = argb(255, 255, 0, 0) }
        val joined = Filler.apply(dotted, Filler.defaultValues(mapOf("minArea" to 5)), 3L)
        assertEquals(joined[11, 11], joined[10, 10])
        val kept = Filler.apply(dotted, Filler.defaultValues(mapOf("minArea" to 5, "small" to 0, "antialias" to 0)), 3L)
        assertEquals(argb(255, 255, 0, 0), kept[10, 10])
    }

    @Test
    fun `areas can be cut out, the choice inverted`() {
        // the black grid of lines is the largest area and touches the border
        for (cut in listOf(1, 2)) {
            val out = Filler.apply(tiles, Filler.defaultValues(mapOf("cutout" to cut, "antialias" to 0)), 3L)
            assertEquals(0, out[21, 10] ushr 24, "cut-out $cut: line")
            // a tile in the middle (not touching the border)
            assertEquals(255, out[32, 32] ushr 24, "cut-out $cut: tile")
        }
        // by color: black
        val black = Filler.apply(tiles, Filler.defaultValues(mapOf("cutout" to 3, "cutColor" to 0, "antialias" to 0)), 3L)
        assertEquals(0, black[21, 10] ushr 24)
        // inverted: only the lines stay
        val inverted = Filler.apply(tiles, Filler.defaultValues(mapOf("cutout" to 1, "cutInvert" to 1, "antialias" to 0)), 3L)
        assertEquals(255, inverted[21, 10] ushr 24)
        assertEquals(0, inverted[10, 10] ushr 24)
        // random: some tiles clear, some not
        val random = Filler.apply(tiles, Filler.defaultValues(mapOf("cutout" to 6, "cutShare" to 50, "antialias" to 0)), 3L)
        val clear = tileColors(random).count { it ushr 24 == 0 }
        assertTrue(clear in 1..11, "random $clear")
    }

    @Test
    fun `anti-aliasing samples the borders finer, also the cut out ones`() {
        // a round spot (its rim runs across the pixels) on black
        val disk = Pixels(80, 80, IntArray(80 * 80) { i ->
            if (kotlin.math.hypot(i % 80 + 0.5 - 40, i / 80 + 0.5 - 40) < 25.3) argb(255, 230, 230, 230) else argb(255, 0, 0, 0)
        })
        fun alphas(aa: Int) = Filler.apply(disk, Filler.defaultValues(mapOf("cutout" to 1, "antialias" to aa, "minArea" to 1)), 3L).data.map { it ushr 24 }.toSet()
        assertEquals(setOf(0, 255), alphas(0))
        assertTrue(alphas(1).size > 2, "2 × 2: ${alphas(1)}")
        assertTrue(alphas(2).size > alphas(1).size, "4 × 4: ${alphas(2)}")
        // straight tile borders on the pixel grid stay crisp, the middle of a tile is untouched
        val hard = Filler.apply(tiles, Filler.defaultValues(mapOf("antialias" to 0)), 3L)
        val soft = Filler.apply(tiles, Filler.defaultValues(mapOf("antialias" to 2)), 3L)
        assertEquals(hard[10, 10], soft[10, 10])
    }
}
