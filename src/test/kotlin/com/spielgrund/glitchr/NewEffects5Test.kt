package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Beat
import com.spielgrund.glitchr.effects.FrameInput
import com.spielgrund.glitchr.effects.MinMax
import com.spielgrund.glitchr.effects.Morph
import com.spielgrund.glitchr.effects.Scanner
import com.spielgrund.glitchr.effects.Uzumaki
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewEffects5Test {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** Black with a single white pixel at (30, 30). */
    private val dot = Pixels(61, 61, IntArray(61 * 61) { if (it == 30 * 61 + 30) white else black })

    @Test
    fun `maximum grows white in the blob's shape, minimum grows black`() {
        fun grown(vararg c: Pair<String, Int>) = MinMax.apply(dot, MinMax.defaultValues(mapOf(*c)), 1L)
        // a circle of radius 10
        val circle = grown("radius" to 100)
        assertEquals(white, circle[40, 30]); assertEquals(white, circle[30, 20])
        assertEquals(black, circle[38, 38], "the corner of the square lies outside the circle")
        assertEquals(white, grown("radius" to 100, "shape" to 0)[38, 38], "but inside the square")
        // a vertical line only forwards (balance 100 %): down, not up, not sideways
        val line = grown("radius" to 100, "shape" to 3, "angle" to 9000, "balance" to 100)
        assertEquals(white, line[30, 38]); assertEquals(black, line[30, 22]); assertEquals(black, line[33, 30])
        // the brightness mode keeps a pixel's color whole
        val red = Pixels(21, 21, IntArray(21 * 21) { if (it == 10 * 21 + 10) 0xFFFF2020.toInt() else 0xFF101010.toInt() })
        assertEquals(0xFFFF2020.toInt(), MinMax.apply(red, MinMax.defaultValues(mapOf("radius" to 30, "channels" to 1)), 1L)[12, 10])
        // minimum on the inverted picture: the black dot grows
        val inverted = Pixels(61, 61, IntArray(61 * 61) { if (it == 30 * 61 + 30) black else white })
        assertEquals(black, MinMax.apply(inverted, MinMax.defaultValues(mapOf("operation" to 1, "radius" to 50)), 1L)[34, 30])
        // min then max removes the single speck
        assertEquals(black, grown("operation" to 3, "radius" to 30)[30, 30])
    }

    private fun at(frame: Int, fps: Double = 25.0, bpm: Double = 150.0) = object : FrameInput {
        override val frame = frame
        override val frameCount = 200
        override fun at(f: Int): Pixels = error("not needed")
        override val fps = fps
        override val bpm = bpm
    }

    @Test
    fun `beat fires on the beat in the animation's tempo and follows its curve`() {
        val img = testImage(80, 60)
        // 150 bpm at 25 fps: a beat every 10 frames; a kick is strongest right on it
        val v = Beat.defaultValues(mapOf("scale" to 50))
        val onBeat = Beat.apply(img, v, 1L, at(10))
        assertContentEquals(onBeat.data, Beat.apply(img, v, 1L, at(20)).data)
        assertTrue(!onBeat.data.contentEquals(img.data))
        // just before the next beat the kick has died away: (almost) the picture itself
        val late = Beat.apply(img, v, 1L, at(19))
        fun diff(a: Pixels, b: Pixels) = a.data.indices.sumOf { kotlin.math.abs((a.data[it] and 0xFF) - (b.data[it] and 0xFF)) }
        assertTrue(diff(late, img) < diff(onBeat, img) / 10, "decayed")
        // the curves: kick snaps and decays, saw up rises, a drawn falling line starts high
        assertEquals(1.0, Beat.curveAt(0, 0.0, null), 1e-9)
        assertEquals(0.5, Beat.curveAt(3, 0.5, null), 1e-9)
        val drawn = com.spielgrund.glitchr.effects.Curves.lut(com.spielgrund.glitchr.effects.Curves.parse("rgb:0,255 255,0")[0])
        assertTrue(Beat.curveAt(Beat.DRAWN, 0.0, drawn) > 0.99)
        // every other hit the other way
        val alt = Beat.defaultValues(mapOf("moveX" to 20, "scale" to 0, "alternate" to 1))
        assertTrue(!Beat.apply(img, alt, 1L, at(10)).data.contentEquals(Beat.apply(img, alt, 1L, at(20)).data))
    }

    @Test
    fun `morph goes from the picture below to the layer`() {
        val below = Pixels(40, 30, IntArray(40 * 30) { black })
        val layer = Pixels(40, 30, IntArray(40 * 30) { white })
        for (mode in 0..3) {
            fun at(p: Int) = Morph.apply(layer, below, Morph.defaultValues(mapOf("progress" to p, "mode" to mode)), 1L, null)
            assertContentEquals(below.data, at(0).data, "mode $mode at 0")
            assertContentEquals(layer.data, at(1000).data, "mode $mode at 100")
        }
        val half = Morph.apply(layer, below, Morph.defaultValues(mapOf("progress" to 500, "mode" to 1)), 1L, null)
        assertTrue((half[5, 5] and 0xFF) in 120..135)
        // without a picture below: the layer as it is
        assertContentEquals(layer.data, Morph.apply(layer, null, Morph.defaultValues(), 1L, null).data)
    }

    @Test
    fun `uzumaki turns a cut-out sky into a dome to look around in, whirled from the zenith`() {
        // a cut-out sky: the top 40 % a horizontal gradient, below transparent
        val sky = Pixels(100, 100, IntArray(100 * 100) { i -> if (i / 100 >= 40) 0 else { val g = i % 100 * 2; (0xFF shl 24) or (g shl 16) or (g shl 8) or 255 } })
        fun run(vararg c: Pair<String, Int>) = Uzumaki.apply(sky, Uzumaki.defaultValues(mapOf(*c)), 1L)
        // looking up: sky; looking down: empty (for the ground below), or mirrored sky
        assertEquals(255, run("tilt" to 6000)[50, 50] ushr 24)
        assertEquals(255, run()[50, 30] ushr 24)
        assertEquals(0, run("tilt" to -6000)[50, 50] ushr 24)
        assertEquals(255, run("tilt" to -6000, "below" to 1)[50, 50] ushr 24)
        // the camera, the spin, the whirl and the drag all change what is seen
        val base = run()
        for (c in listOf("heading" to 4000, "spin" to 4000, "twist" to 9000, "projection" to 1, "projection" to 2)) {
            assertTrue(!run(c).data.contentEquals(base.data), c.first)
        }
        // holes in the cut-out sky are filled: looking up, nothing is transparent
        val holey = Pixels(100, 100, IntArray(100 * 100) { i -> val x = i % 100; val y = i / 100
            if (y >= 40 || (x in 30..34 && y > 10) || (x + y) % 9 == 0) 0 else 0xFF4080FF.toInt() })
        assertTrue(Uzumaki.apply(holey, Uzumaki.defaultValues(mapOf("tilt" to 6000)), 1L).data.all { it ushr 24 == 255 }, "no holes")
        // the flow: with the time the sky streams on
        val flow = mapOf("tilt" to 5000, "twist" to 9000, "inward" to 1000, "around" to 2000)
        assertTrue(!run(*(flow + ("time" to 0)).toList().toTypedArray()).data.contentEquals(run(*(flow + ("time" to 700)).toList().toTypedArray()).data), "flows")
        // nothing to make a sky of: empty
        assertTrue(Uzumaki.apply(Pixels(20, 20), Uzumaki.defaultValues(), 1L).data.all { it == 0 })
    }

    @Test
    fun `the scanner drags streaks along the scan and lets light in at the sides`() {
        // black top half, white bottom half: dragged down, the white starts later in some columns
        val img = Pixels(80, 80, IntArray(80 * 80) { if (it / 80 < 40) black else white })
        val clean = mapOf("lines" to 0, "slip" to 0, "leak" to 0, "exposure" to 0, "saturation" to 100)
        val s = Scanner.apply(img, Scanner.defaultValues(clean), 1L)
        val justBelow = (0 until 80).map { s[it, 42] and 0xFF }
        assertTrue(justBelow.any { it < 200 } && justBelow.max() - justBelow.min() > 30, "streaks differ per column: $justBelow")
        // light at the sides
        val dark = Pixels(80, 80, IntArray(80 * 80) { black })
        val lit = Scanner.apply(dark, Scanner.defaultValues(clean + ("leak" to 150)), 1L)
        assertTrue((lit[0, 40] and 0xFF) > 100 && (lit[40, 40] and 0xFF) < (lit[0, 40] and 0xFF))
        // left to right: the same, turned
        assertEquals(80, Scanner.apply(Pixels(80, 50), Scanner.defaultValues(mapOf("direction" to 1)), 1L).width)
    }

    @Test
    fun `grain specks the mid tones, sized, gray or colored, and dances with the time`() {
        val gray = Pixels(100, 60, IntArray(100 * 60) { 0xFF808080.toInt() })
        fun run(vararg c: Pair<String, Int>, seed: Long = 1L) = com.spielgrund.glitchr.effects.Grain.apply(gray, com.spielgrund.glitchr.effects.Grain.defaultValues(mapOf(*c)), seed)
        fun spread(p: Pixels) = p.data.map { it and 0xFF }.let { it.max() - it.min() }
        val g = run("amount" to 100)
        assertTrue(spread(g) > 30, "grain")
        assertTrue(spread(run("amount" to 0)) == 0)
        // gray grain keeps r = g = b; colored doesn't
        assertTrue(run("amount" to 100, "color" to 0).data.all { (it shr 16 and 0xFF) == (it and 0xFF) })
        assertTrue(run("amount" to 100, "color" to 100).data.any { (it shr 16 and 0xFF) != (it and 0xFF) })
        // film: black and white stay cleaner than the mid tones
        val black = Pixels(100, 60, IntArray(100 * 60) { 0xFF000000.toInt() })
        val onBlack = com.spielgrund.glitchr.effects.Grain.apply(black, com.spielgrund.glitchr.effects.Grain.defaultValues(mapOf("amount" to 100)), 1L)
        assertTrue(spread(onBlack) < spread(g))
        // the time: a new grain after 1/24 s, the same within it; another seed, another grain
        assertTrue(run("amount" to 100, "time" to 0).data.contentEquals(run("amount" to 100, "time" to 30).data))
        assertTrue(!run("amount" to 100, "time" to 0).data.contentEquals(run("amount" to 100, "time" to 50).data))
        assertTrue(!g.data.contentEquals(run("amount" to 100, seed = 2L).data))
    }
}
