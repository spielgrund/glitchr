package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Lens
import com.spielgrund.glitchr.image.Pixels
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensTest {
    private val src = testImage(240, 160)

    /** Everything off. */
    private val off = mapOf("distortion" to 0, "aberration" to 0, "edgeBlur" to 0, "vignette" to 0, "glitch" to 0)

    private fun run(img: Pixels = src, vararg changes: Pair<String, Int>) = Lens.apply(img, Lens.defaultValues(off + changes), 5L)

    @Test
    fun `switched off the picture stays`() {
        val out = run()
        for (i in src.data.indices) for (s in 0..24 step 8) {
            assertTrue(abs((src.data[i] shr s and 0xFF) - (out.data[i] shr s and 0xFF)) <= 1, "Pixel $i")
        }
    }

    @Test
    fun `the center stays, filled distortion leaves no empty border`() {
        for (d in listOf(-100, 60, 100)) {
            val out = run(src, "distortion" to d)
            assertTrue(out.data.all { it ushr 24 == 255 }, "Wölbung $d: keine leeren Ränder")
        }
        val open = run(src, "distortion" to 100, "fit" to 0)
        assertTrue(open.data.any { it ushr 24 == 0 }, "ohne Füllen entstehen leere Ränder")
    }

    @Test
    fun `fringes grow towards the edge`() {
        // grey picture with a white frame line near the right edge and one in the middle
        val img = Pixels(200, 100).also { p ->
            p.data.fill(0xFF000000.toInt())
            for (y in 0 until 100) { p.data[y * 200 + 180] = -1; p.data[y * 200 + 100] = -1 }
        }
        val out = run(img, "aberration" to 20)
        fun colored(x0: Int, x1: Int) = (x0..x1).count { x -> out[x, 50].let { abs((it shr 16 and 0xFF) - (it and 0xFF)) > 12 } }
        assertTrue(colored(170, 199) > 0, "am Rand Farbsaum")
        assertEquals(0, colored(95, 105), "in der Mitte keiner")
    }

    @Test
    fun `the vignette darkens the corners`() {
        val white = Pixels(100, 100).also { it.data.fill(-1) }
        val out = run(white, "vignette" to 100, "vignetteSize" to 30, "vignetteSoft" to 50)
        assertEquals(255, out[50, 50] and 0xFF)
        assertTrue((out[1, 1] and 0xFF) < 60)
    }

    @Test
    fun `every glitch breaks the lens`() {
        val plain = run(src, "distortion" to 20)
        for (g in 1..5) {
            val out = run(src, "distortion" to 20, "glitch" to g, "glitchAmount" to 80, "glitchSize" to 40)
            assertTrue(!out.data.contentEquals(plain.data), "Glitch $g ändert nichts")
        }
    }

    @Test
    fun `break lines are bright`() {
        val black = Pixels(200, 200).also { it.data.fill(0xFF000000.toInt()) }
        for (g in listOf(1, 2, 3, 5)) {
            val out = run(black, "glitch" to g, "glitchSize" to 50, "glitchLines" to 100)
            assertTrue(out.data.count { (it shr 8 and 0xFF) > 200 } > 50, "Glitch $g: helle Linien")
            val dark = run(black, "glitch" to g, "glitchSize" to 50, "glitchLines" to 0)
            assertTrue(dark.data.all { (it shr 8 and 0xFF) < 5 }, "Glitch $g: ohne Linien bleibt es schwarz")
        }
    }

    @Test
    fun `thicker break lines, and cracks from the top or out of the impact`() {
        val black = Pixels(300, 200).also { it.data.fill(0xFF000000.toInt()) }
        fun bright(vararg c: Pair<String, Int>) = run(black, "glitch" to 5, "glitchLines" to 100, *c).data.count { (it shr 8 and 0xFF) > 200 }
        assertTrue(bright("lineWidth" to 60) > 2 * bright("lineWidth" to 10), "dicker")
        // vertical: the cracks start along the top edge; radial: out of the middle
        val down = run(black, "glitch" to 5, "glitchLines" to 100, "crackDirection" to 0, "crackBranch" to 0, "glitchSize" to 60)
        assertTrue((0 until 300).count { x -> (down[x, 1] shr 8 and 0xFF) > 150 } >= 3, "oben beginnen Risse")
        val out = run(black, "glitch" to 5, "glitchLines" to 100, "crackDirection" to 100, "crackBranch" to 0, "glitchSize" to 60)
        assertTrue((145..155).any { x -> (95..105).any { y -> (out[x, y] shr 8 and 0xFF) > 150 } }, "Risse im Einschlag")
    }

    @Test
    fun `dark lines, gradients and tilted pieces`() {
        val white = Pixels(200, 200).also { it.data.fill(-1) }
        for (g in listOf(1, 2, 3, 5)) {
            val dark = run(white, "glitch" to g, "glitchSize" to 50, "glitchLines" to -100, "glitchFringe" to 0, "glitchAmount" to 0)
            assertTrue(dark.data.count { (it shr 8 and 0xFF) < 50 } > 50, "Glitch $g: dunkle Linien")
            val shaded = run(white, "glitch" to g, "glitchSize" to 50, "glitchLines" to 0, "elementGradient" to -100, "glitchAmount" to 0)
            assertTrue(shaded.data.count { (it shr 8 and 0xFF) < 128 } > 500, "Glitch $g: Verlauf zu den Kanten")
            val lit = run(testImage(200, 200), "glitch" to g, "glitchSize" to 50, "tilt" to 100, "light" to 100, "refraction" to 0, "glitchLines" to 0)
            val flat = run(testImage(200, 200), "glitch" to g, "glitchSize" to 50, "tilt" to 0, "glitchLines" to 0)
            assertTrue(!lit.data.contentEquals(flat.data), "Glitch $g: Neigung wirkt")
        }
    }

    @Test
    fun `the cracks stay where they are when the line width changes`() {
        val black = Pixels(300, 200).also { it.data.fill(0xFF000000.toInt()) }
        val thin = run(black, "glitch" to 5, "glitchLines" to 100, "glitchFringe" to 0, "lineWidth" to 10)
        val thick = run(black, "glitch" to 5, "glitchLines" to 100, "glitchFringe" to 0, "lineWidth" to 60)
        val thinLines = thin.data.indices.filter { (thin.data[it] shr 8 and 0xFF) > 200 }
        assertTrue(thinLines.size > 100)
        // every pixel of a thin line lies on the thick line too
        assertTrue(thinLines.all { (thick.data[it] shr 8 and 0xFF) > 150 }, "gleiche Risse")
    }
}
