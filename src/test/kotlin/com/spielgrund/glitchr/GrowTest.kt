package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Grow
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrowTest {
    private val dark = argb(255, 20, 25, 30)
    private val bright = argb(255, 250, 220, 190)

    /** Dark, with one bright spot (radius 8) in the middle of 160 × 120. */
    private val spot = Pixels(160, 120, IntArray(160 * 120) { i ->
        if (hypot(i % 160 + 0.5 - 80, i / 160 + 0.5 - 60) < 8) bright else dark
    })

    private fun grow(changes: Map<String, Int>, seed: Long = 3L) =
        Grow.apply(spot, Grow.defaultValues(mapOf("precision" to 0, "noise" to 0, "immunity" to 20) + changes), seed)

    private fun grown(p: Pixels) = p.data.count { it != dark }

    @Test
    fun `nothing chosen, nothing grows, and the start mask can be shown`() {
        assertContentEquals(spot.data, Grow.apply(spot, Grow.defaultValues(mapOf("lower" to 255, "upper" to 255)), 3L).data)
        val mask = Grow.apply(spot, Grow.defaultValues(mapOf("showMask" to 1)), 3L)
        assertEquals(-1, mask[80, 60])
        assertEquals(0xFF000000.toInt(), mask[10, 10])
    }

    @Test
    fun `the infection spreads further with more steps and carries the start pixels`() {
        val short = grow(mapOf("steps" to 10, "content" to 2))
        val long = grow(mapOf("steps" to 40, "content" to 2))
        assertTrue(grown(long) > grown(short) * 2, "short ${grown(short)}, long ${grown(long)}")
        // what grew is the spot's own color, laid over the dark (edges partly)
        val changed = long.data.filter { it != dark }
        assertTrue(changed.count { it == bright } > changed.size * 3 / 4)
        assertTrue(changed.all { red(it) >= red(dark) })
    }

    @Test
    fun `branching leaves gaps, round grows solid`() {
        val round = grow(mapOf("steps" to 60, "form" to -50, "content" to 2))
        val branching = grow(mapOf("steps" to 60, "form" to 90, "content" to 2))
        // share of grown pixels within the box the growth spans
        fun fill(p: Pixels): Double {
            val xs = p.data.indices.filter { p.data[it] != dark }
            val x0 = xs.minOf { it % 160 }; val x1 = xs.maxOf { it % 160 }
            val y0 = xs.minOf { it / 160 }; val y1 = xs.maxOf { it / 160 }
            return xs.size.toDouble() / ((x1 - x0 + 1) * (y1 - y0 + 1))
        }
        assertTrue(fill(branching) < fill(round) - 0.15, "branching ${fill(branching)}, round ${fill(round)}")
    }

    @Test
    fun `a direction pushes the growth that way`() {
        val out = grow(mapOf("steps" to 40, "direction" to 0, "directionStrength" to 100, "content" to 2))
        val right = out.data.indices.count { out.data[it] != dark && it % 160 > 90 }
        val left = out.data.indices.count { out.data[it] != dark && it % 160 < 70 }
        assertTrue(right > left * 2, "right $right, left $left")
    }

    @Test
    fun `the growth time can be shown as rings, the same seed grows the same`() {
        val rings = grow(mapOf("steps" to 40, "content" to 3, "background" to 1))
        // start pixels take the start of the ramp (thermal: black), the far front its end (light)
        assertTrue(red(rings[80, 60]) < 30)
        assertContentEquals(grow(mapOf("steps" to 30)).data, grow(mapOf("steps" to 30)).data)
        assertTrue(!grow(mapOf("steps" to 30, "immunity" to 80), 1L).data.contentEquals(grow(mapOf("steps" to 30, "immunity" to 80), 2L).data))
    }

    @Test
    fun `stretching draws the start picture out onto the grown area`() {
        // a spot that is red on its left half and blue on its right half
        val halves = Pixels(160, 120, IntArray(160 * 120) { i ->
            val x = i % 160 + 0.5
            if (hypot(x - 80, i / 160 + 0.5 - 60) < 8) (if (x < 80) argb(255, 240, 30, 30) else argb(255, 30, 30, 240)) else dark
        })
        val out = Grow.apply(halves, Grow.defaultValues(mapOf("content" to 1, "lower" to 50, "precision" to 0, "noise" to 0, "immunity" to 10, "steps" to 80, "form" to 0)), 3L)
        // near the growth's outer edge, well outside the spot: the left side shows its red half
        // stretched out, the right side its blue
        val grown = (0 until 160).filter { out[it, 60] != dark }
        assertTrue(grown.first() < 80 - 14 && grown.last() > 80 + 14, "grown ${grown.first()}..${grown.last()}")
        val left = out[grown.first() + 3, 60]
        val right = out[grown.last() - 3, 60]
        assertTrue(red(left) > 150 && com.spielgrund.glitchr.image.blue(left) < 100, "left ${Integer.toHexString(left)}")
        assertTrue(com.spielgrund.glitchr.image.blue(right) > 150 && red(right) < 100, "right ${Integer.toHexString(right)}")
        // what did not grow stays
        assertEquals(dark, out[5, 5])
    }

    @Test
    fun `smudging drags the whole picture along the growth, also beyond the grown area`() {
        // stripes: a bright spot (start) in a picture of vertical stripes
        val stripes = Pixels(160, 120, IntArray(160 * 120) { i ->
            val x = i % 160
            when {
                hypot(x + 0.5 - 80, i / 160 + 0.5 - 60) < 8 -> bright
                (x / 4) % 2 == 0 -> argb(255, 60, 60, 60)
                else -> argb(255, 120, 120, 120)
            }
        })
        val v = Grow.defaultValues(mapOf("precision" to 0, "noise" to 0, "immunity" to 10, "steps" to 40, "direction" to 0, "directionStrength" to 100, "smear" to 0, "push" to 100, "reach" to 60))
        val out = Grow.apply(stripes, v, 3L)
        // the growth runs to the right: to the right of the spot the picture is pulled along, so the
        // spot's bright color shows where stripes were
        assertTrue((90 until 130).any { red(out[it, 60]) > 200 }, "nothing dragged along")
        // pixels outside the grown area move too (pulled softly)
        val grownOnly = Grow.apply(stripes, Grow.defaultValues(mapOf("precision" to 0, "noise" to 0, "immunity" to 10, "steps" to 40, "direction" to 0, "directionStrength" to 100, "content" to 2)), 3L)
        val movedOutside = (0 until 160 * 120).count { grownOnly.data[it] == stripes.data[it] && out.data[it] != stripes.data[it] }
        assertTrue(movedOutside > 500, "moved outside $movedOutside")
        // far away nothing moves
        assertEquals(stripes[5, 5], out[5, 5])
    }
}
