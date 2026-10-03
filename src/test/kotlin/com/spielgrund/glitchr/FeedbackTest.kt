package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Feedback
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackTest {
    private val src = testImage(120, 80)

    @Test
    fun `copies without any change leave the picture as it is`() {
        val out = Feedback.apply(src, Feedback.defaultValues(mapOf("scale" to 1000, "steps" to 5)), 0)
        assertContentEquals(src.data, out.data)
    }

    @Test
    fun `a smaller copy on top nests the picture in itself`() {
        // a red picture with a white center: the half-size copy puts a small red frame around a smaller white center
        val w = 100
        val img = Pixels(w, w, IntArray(w * w) { i -> val x = i % w; val y = i / w; if (x in 40..59 && y in 40..59) -1 else argb(255, 255, 0, 0) })
        val out = Feedback.apply(img, Feedback.defaultValues(mapOf("scale" to 500, "steps" to 1)), 0)
        // the copy covers the middle half: at (30, 50) the copy shows the picture's (10, 50): red
        assertEquals(argb(255, 255, 0, 0), out[30, 50])
        // outside the copy the picture stays
        assertEquals(out[5, 5], img[5, 5])
        // the copy's white center is 10 px wide around the middle
        assertEquals(-1, out[50, 50])
        assertEquals(argb(255, 255, 0, 0), out[44, 50])
    }

    @Test
    fun `hue turns further with every step`() {
        val red = Pixels(10, 10, IntArray(100) { argb(255, 255, 0, 0) })
        fun hueAfter(steps: Int) = Feedback.apply(red, Feedback.defaultValues(mapOf("scale" to 1000, "steps" to steps, "hue" to 120)), 0)[5, 5]
        val one = hueAfter(1)
        assertTrue(green(one) > 250 && red(one) < 5, "1 step: green")
        val two = hueAfter(2)
        assertTrue(blue(two) > 250 && green(two) < 5, "2 steps: blue")
    }

    @Test
    fun `the picture on top lets shifted copies peek out only where it is transparent`() {
        val w = 60
        val img = Pixels(w, w, IntArray(w * w) { i -> if (i % w < 20) argb(255, 0, 0, 255) else 0 })
        val out = Feedback.apply(img, Feedback.defaultValues(mapOf("scale" to 1000, "offsetX" to 200, "steps" to 2, "order" to 1)), 0)
        assertEquals(img[10, 30], out[10, 30])
        // shifted by 20 and 40 px: the copies fill up to x = 60
        assertEquals(255, out[50, 30] ushr 24)
    }

    /** A yellow disc (radius 20) in the middle of a dark 120 px square. */
    private val disc = Pixels(120, 120, IntArray(120 * 120) { i ->
        val x = i % 120 - 59.5
        val y = i / 120 - 59.5
        if (kotlin.math.hypot(x, y) < 20) argb(255, 250, 210, 30) else argb(255, 10, 10, 20)
    })

    @Test
    fun `edges are found and colored, the flat parts stay on the ground`() {
        val v = mapOf("source" to 1, "ground" to 1, "steps" to 1, "scale" to 1000, "lineColor" to 1, "color" to 0x00FFCC)
        val out = Feedback.apply(disc, Feedback.defaultValues(v), 0)
        // on the disc's edge: the edge color; in the middle and far outside: the black ground
        assertEquals(0x00FFCC, out[60, 40] and 0xFFFFFF)
        assertEquals(argb(255, 0, 0, 0), out[60, 60])
        assertEquals(argb(255, 0, 0, 0), out[5, 5])
    }

    /** The same disc on a transparent canvas: the only blob. */
    private val lonelyDisc = Pixels(120, 120, IntArray(120 * 120) { i -> if (alpha(disc.data[i]) > 0 && red(disc.data[i]) > 100) disc.data[i] else 0 })

    @Test
    fun `blob outlines grow out of the blob with the same line width`() {
        // one step at 150 %: a ring of radius 30 around the disc's middle, 2 px wide like the original outline
        val v = mapOf("source" to 2, "ground" to 1, "steps" to 1, "scale" to 1500, "lineColor" to 1, "color" to 0xFF00FF, "minBlob" to 500)
        val out = Feedback.apply(lonelyDisc, Feedback.defaultValues(v), 0)
        val column = (0 until 60).map { out[60, it] and 0xFFFFFF == 0xFF00FF }
        // the ring (y ≈ 30) and the blob's own outline (y ≈ 40)
        assertTrue(column[30] || column[31], "ring at 150 %")
        assertTrue(column[40] || column[41], "own outline")
        // nothing between them, and each line at most 3 px thick
        assertTrue(!column[35])
        assertTrue(column.subList(25, 36).count { it } <= 3)
    }

    @Test
    fun `blob copies run into the blob below 100 percent`() {
        val v = mapOf("source" to 2, "ground" to 1, "steps" to 1, "scale" to 500, "lineColor" to 1, "color" to 0xFF00FF, "minBlob" to 500)
        val out = Feedback.apply(lonelyDisc, Feedback.defaultValues(v), 0)
        // half size: an outline at radius 10 inside the disc
        assertTrue((48..52).any { out[60, it] and 0xFFFFFF == 0xFF00FF })
    }

    @Test
    fun `the first copy or the original can go over everything at the end`() {
        // growing copies cover the picture; laid on top again, the original shows through
        val grow = mapOf("scale" to 1500, "steps" to 4, "hue" to 90)
        val covered = Feedback.apply(src, Feedback.defaultValues(grow), 0)
        val original = Feedback.apply(src, Feedback.defaultValues(grow + ("finish" to 2)), 0)
        assertContentEquals(src.data, original.data)
        assertTrue(!covered.data.contentEquals(src.data))
        // the first copy on top: the same as a single step
        val first = Feedback.apply(src, Feedback.defaultValues(grow + ("finish" to 1)), 0)
        val oneStep = Feedback.apply(src, Feedback.defaultValues(grow + ("steps" to 1)), 0)
        assertContentEquals(oneStep.data, first.data)
    }

    @Test
    fun `alpha edge strokes the transparent edge outside, centered or inside`() {
        fun stroke(position: Int, ground: Int = 3) = Feedback.apply(
            lonelyDisc,
            Feedback.defaultValues(mapOf("source" to 3, "ground" to ground, "steps" to 1, "scale" to 1000, "alphaWidth" to 40, "alphaPosition" to position, "alphaColor" to 0x00FF00)),
            0,
        )
        fun green(p: Pixels, y: Int) = p[60, y] and 0xFFFFFF == 0x00FF00 && p[60, y] ushr 24 == 255
        // the disc's edge is at y ≈ 40 on the middle column (radius 20)
        val outside = stroke(0)
        assertTrue(green(outside, 38) && green(outside, 37) && !green(outside, 41) && outside[60, 34] ushr 24 == 0)
        // inside: the stroke covers the disc's rim; on the picture as ground the rest of the disc shows as it is
        val inside = stroke(2, ground = 0)
        assertTrue(green(inside, 41) && green(inside, 42) && !green(inside, 38))
        assertEquals(lonelyDisc[60, 60], inside[60, 60])
        val centered = stroke(1)
        assertTrue(green(centered, 39) && green(centered, 40))
    }

    @Test
    fun `exact distances give round strokes`() {
        // a single visible pixel with a 10 px stroke: a disc of radius ~10.5, not a square or an octagon
        val dot = Pixels(41, 41, IntArray(41 * 41) { if (it == 20 * 41 + 20) -1 else 0 })
        val out = Feedback.apply(dot, Feedback.defaultValues(mapOf("source" to 3, "ground" to 3, "steps" to 1, "scale" to 1000, "alphaWidth" to 100, "alphaPosition" to 0)), 0)
        assertEquals(255, out[20, 10] ushr 24)
        assertEquals(255, out[27, 13] ushr 24) // distance 9.9
        assertEquals(0, out[28, 12] ushr 24) // distance 11.3
    }

    @Test
    fun `the alpha edge follows the mask of the picture layer`() {
        // an opaque picture without transparency, cut to a circle by its layer mask
        val picture = com.spielgrund.glitchr.model.ImageLayer(Pixels(100, 100, IntArray(100 * 100) { argb(255, 200, 40, 40) })).apply {
            mask.mode = com.spielgrund.glitchr.model.MaskMode.RADIAL
            mask.radialCenter = com.spielgrund.glitchr.model.RelPoint(0.5, 0.5)
            mask.radialEdge = com.spielgrund.glitchr.model.RelPoint(0.5, 0.2)
            mask.hardEdge = true
        }
        val stroke = com.spielgrund.glitchr.model.EffectLayer(Feedback).apply {
            values.putAll(mapOf("source" to 3, "steps" to 1, "scale" to 1000, "alphaWidth" to 30, "alphaColor" to 0x00FF00, "alphaPosition" to 0))
        }
        val out = com.spielgrund.glitchr.model.Renderer().render(100, 100, listOf(picture.state(), stroke.state()))
        fun isStroke(x: Int, y: Int) = out[x, y] and 0xFFFFFF == 0x00FF00 && out[x, y] ushr 24 > 200
        // somewhere just outside the masked circle, on the middle row: a green stroke
        val row = (0 until 50).filter { isStroke(it, 50) }
        assertTrue(row.isNotEmpty(), "no outline at the mask edge")
        assertTrue(row.all { it in 5..35 }, "outline at $row")
        // the canvas corner stays empty, the circle's middle keeps the picture
        assertEquals(0, out[2, 2] ushr 24)
        assertEquals(argb(255, 200, 40, 40), out[50, 50])
    }

    @Test
    fun `the alpha edge frames a picture that fills the canvas, also in every copy`() {
        val v = mapOf("alphaFrame" to 1, "alphaWidth" to 30, "alphaColor" to 0x00FF00, "steps" to 1, "scale" to 500)
        val out = Feedback.apply(src, Feedback.defaultValues(v), 0)
        fun isFrame(x: Int, y: Int) = out[x, y] and 0xFFFFFF == 0x00FF00
        // inside along the picture's border (120 × 80)
        assertTrue(isFrame(0, 40) && isFrame(2, 40) && isFrame(119, 40) && isFrame(60, 0) && isFrame(60, 79))
        assertTrue(!isFrame(4, 40))
        // the half size copy in the middle has its own (half as wide) frame: its left border at x = 30
        assertTrue((29..31).any { isFrame(it, 40) })
        assertTrue(!isFrame(35, 40))
        // off: no frame
        val plain = Feedback.apply(src, Feedback.defaultValues(v + ("alphaFrame" to 0)), 0)
        assertTrue(plain[0, 40] and 0xFFFFFF != 0x00FF00)
    }
}
