package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Horns
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HornsTest {
    private val dark = argb(255, 20, 25, 30)
    private val bright = argb(255, 250, 220, 190)

    /** Dark, with one bright spot (radius 8) in the middle of 160 × 120. */
    private val spot = Pixels(160, 120, IntArray(160 * 120) { i ->
        if (hypot(i % 160 + 0.5 - 80, i / 160 + 0.5 - 60) < 8) bright else dark
    })

    private fun grown(p: Pixels) = p.data.count { it != dark }

    @Test
    fun `nothing chosen, nothing grows, and the start mask can be shown`() {
        assertContentEquals(spot.data, Horns.apply(spot, Horns.defaultValues(mapOf("lower" to 255, "upper" to 255)), 3L).data)
        val mask = Horns.apply(spot, Horns.defaultValues(mapOf("showMask" to 1)), 3L)
        assertEquals(-1, mask[80, 60])
        assertEquals(0xFF000000.toInt(), mask[10, 10])
    }

    @Test
    fun `horns grow out of the start mask and curl, reproducibly`() {
        val v = Horns.defaultValues(mapOf("horns" to 6, "hornLength" to 50, "hornWidth" to 8))
        val out = Horns.apply(spot, v, 3L)
        assertContentEquals(out.data, Horns.apply(spot, v, 3L).data)
        // outside the spot the horns carry its bright color
        val outside = (0 until 160 * 120).count { i ->
            hypot(i % 160 + 0.5 - 80, i / 160 + 0.5 - 60) > 10 && red(out.data[i]) > 120
        }
        assertTrue(outside > 100, "Hörner aussen $outside")
        // far away nothing
        assertEquals(dark, out[2, 2])
    }

    @Test
    fun `fans widen, branch and carry gills`() {
        val base = mapOf("hornShape" to 1, "horns" to 3, "hornLength" to 50, "hornWidth" to 6)
        val v = Horns.defaultValues(base)
        val fan = Horns.apply(spot, v, 3L)
        assertContentEquals(fan.data, Horns.apply(spot, v, 3L).data)
        // a fan covers much more than a horn of the same length and root width
        val horn = Horns.apply(spot, Horns.defaultValues(base + ("hornShape" to 0)), 3L)
        assertTrue(grown(fan) > grown(horn) * 2, "Fächer ${grown(fan)}, Horn ${grown(horn)}")
        // the gills: without them the fan is smoother
        val plain = Horns.apply(spot, Horns.defaultValues(base + ("gills" to 0)), 3L)
        assertTrue(!plain.data.contentEquals(fan.data))
        assertEquals(dark, fan[2, 2])
    }

    @Test
    fun `splitting carries the area out, curls its halves and grows on from the split`() {
        val v = Horns.defaultValues(
            mapOf(
                "hornShape" to 2, "region" to 1, "regionX" to 500, "regionY" to 500, "regionW" to 120, "regionH" to 160,
                "direction" to 270, "hornLength" to 30, "splitLevels" to 2,
            ),
        )
        val out = Horns.apply(spot, v, 3L)
        assertContentEquals(out.data, Horns.apply(spot, v, 3L).data)
        // above and below the area its bright spot shows up again
        assertTrue((0 until 40).any { y -> (60 until 100).any { x -> red(out[x, y]) > 150 } }, "oben nichts")
        assertTrue((80 until 120).any { y -> (60 until 100).any { x -> red(out[x, y]) > 150 } }, "unten nichts")
        // more levels reach further
        val one = Horns.apply(spot, Horns.defaultValues(mapOf("hornShape" to 2, "region" to 1, "regionW" to 120, "regionH" to 160, "direction" to 270, "hornLength" to 30, "splitLevels" to 1)), 3L)
        assertTrue(grown(out) > grown(one), "zwei Stufen ${grown(out)}, eine ${grown(one)}")
    }

    @Test
    fun `the ellipse limits where it grows from`() {
        val mask = Horns.apply(spot, Horns.defaultValues(mapOf("region" to 1, "regionX" to 250, "regionY" to 250, "regionW" to 100, "regionH" to 100, "showMask" to 1)), 3L)
        assertEquals(-1, mask[40, 30])
        assertEquals(0xFF000000.toInt(), mask[80, 60])
    }

    @Test
    fun `many cloned stems in random directions, reproducibly`() {
        val base = mapOf("hornShape" to 2, "region" to 1, "regionW" to 120, "regionH" to 160, "hornLength" to 25, "splitLevels" to 2)
        val few = Horns.apply(spot, Horns.defaultValues(base + mapOf("splitStems" to 2)), 3L)
        val v = Horns.defaultValues(base + mapOf("splitStems" to 40, "splitSpread" to 1))
        val many = Horns.apply(spot, v, 3L)
        assertContentEquals(many.data, Horns.apply(spot, v, 3L).data)
        assertTrue(grown(many) > grown(few) * 2, "viele ${grown(many)}, wenige ${grown(few)}")
    }

    @Test
    fun `the front angle turns how the area is read`() {
        // a spot that is red on its upper half and blue on its lower half
        val halves = Pixels(160, 120, IntArray(160 * 120) { i ->
            val y = i / 160 + 0.5
            if (hypot(i % 160 + 0.5 - 80, y - 60) < 10) (if (y < 60) argb(255, 240, 30, 30) else argb(255, 30, 30, 240)) else dark
        })
        val base = mapOf("hornShape" to 2, "region" to 1, "regionW" to 150, "regionH" to 200, "hornLength" to 30, "splitLevels" to 1, "splitStems" to 1, "direction" to 0)
        val up = Horns.apply(halves, Horns.defaultValues(base + ("splitFront" to 270)), 3L)
        val down = Horns.apply(halves, Horns.defaultValues(base + ("splitFront" to 90)), 3L)
        assertTrue(!up.data.contentEquals(down.data))
    }

    @Test
    fun `stems can be turned and the texture mirrored`() {
        val halves = Pixels(160, 120, IntArray(160 * 120) { i ->
            val x = i % 160 + 0.5
            if (hypot(x - 80, i / 160 + 0.5 - 60) < 10) (if (x < 80) argb(255, 240, 30, 30) else argb(255, 30, 30, 240)) else dark
        })
        val base = mapOf("hornShape" to 2, "region" to 1, "regionW" to 150, "regionH" to 200, "hornLength" to 30, "splitLevels" to 1, "splitStems" to 2, "direction" to 270)
        val plain = Horns.apply(halves, Horns.defaultValues(base), 3L)
        val turned = Horns.apply(halves, Horns.defaultValues(base + ("splitTilt" to 45)), 3L)
        val mirrored = Horns.apply(halves, Horns.defaultValues(base + ("splitMirror" to 1)), 3L)
        assertTrue(!plain.data.contentEquals(turned.data), "drehen ändert nichts")
        assertTrue(!plain.data.contentEquals(mirrored.data), "spiegeln ändert nichts")
        // mirrored sideways: where red was, blue is now (above the spot)
        val redPlain = (0 until 40).sumOf { y -> (60 until 100).count { x -> red(plain[x, y]) > 150 && plain[x, y] and 0xFF < 100 } }
        val redMirrored = (0 until 40).sumOf { y -> (60 until 100).count { x -> red(mirrored[x, y]) > 150 && mirrored[x, y] and 0xFF < 100 } }
        val leftRedPlain = (0 until 40).sumOf { y -> (60 until 80).count { x -> red(plain[x, y]) > 150 && plain[x, y] and 0xFF < 100 } }
        val leftRedMirrored = (0 until 40).sumOf { y -> (60 until 80).count { x -> red(mirrored[x, y]) > 150 && mirrored[x, y] and 0xFF < 100 } }
        assertTrue(redPlain > 0 && redMirrored > 0, "rot $redPlain / $redMirrored")
        assertTrue(leftRedPlain != leftRedMirrored, "links rot $leftRedPlain / $leftRedMirrored")
    }
}
