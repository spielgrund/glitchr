package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.AutoSelect
import com.spielgrund.glitchr.effects.H264Prep
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.Mask
import com.spielgrund.glitchr.model.MaskClipboard
import com.spielgrund.glitchr.model.MaskMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskToolsTest {
    /** A blue sky over a grey, textured ground with a red, detailed object in the middle of the ground. */
    private val scene = Pixels(200, 150).also { p ->
        for (y in 0 until 150) for (x in 0 until 200) {
            p.data[y * 200 + x] = when {
                x in 80 until 120 && y in 80 until 130 -> if ((x + y) % 4 < 2) 0xFFE02020.toInt() else 0xFFA01010.toInt()
                y < 60 -> 0xFF7FB2F0.toInt()
                else -> 0xFF606060.toInt() + ((x * 7 + y * 3) % 9) * 0x010101
            }
        }
    }

    private fun mean(m: FloatArray, x0: Int, x1: Int, y0: Int, y1: Int) =
        (y0 until y1).sumOf { y -> (x0 until x1).sumOf { x -> m[y * 200 + x].toDouble() } } / ((x1 - x0) * (y1 - y0))

    @Test
    fun `the sky is found at the top`() {
        val sky = AutoSelect.select(scene, AutoSelect.Kind.SKY)
        assertTrue(mean(sky, 10, 190, 5, 50) > 0.9, "sky selected")
        assertTrue(mean(sky, 10, 190, 70, 145) < 0.1, "ground not")
    }

    @Test
    fun `the subject stands out, the background is the rest`() {
        val subject = AutoSelect.select(scene, AutoSelect.Kind.SUBJECT)
        assertTrue(mean(subject, 85, 115, 85, 125) > 0.8, "object selected")
        assertTrue(mean(subject, 5, 60, 70, 145) < 0.2, "ground not")
        val background = AutoSelect.select(scene, AutoSelect.Kind.BACKGROUND)
        assertTrue(mean(background, 85, 115, 85, 125) < 0.2)
    }

    @Test
    fun `selections replace, add to or take from the painted mask`() {
        val mask = Mask().apply { mode = MaskMode.BRUSH }
        val left = FloatArray(100) { if (it % 10 < 5) 1f else 0f }
        val top = FloatArray(100) { if (it / 10 < 5) 1f else 0f }
        mask.fromValues(left, 10, 10)
        mask.fromValues(top, 10, 10, 1)
        assertEquals(75, mask.painted!!.count { it.toInt() and 0xFF == 255 }, "added")
        mask.fromValues(left, 10, 10, 2)
        assertEquals(25, mask.painted!!.count { it.toInt() and 0xFF == 255 }, "subtracted: only top right is left")
    }

    @Test
    fun `a copied mask pastes onto another layer`() {
        val a = Mask().apply { mode = MaskMode.RADIAL; invert = true; ensurePainted(4, 4); fill(200) }
        MaskClipboard.content = a.memento()
        val b = Mask()
        b.restore(MaskClipboard.content!!)
        assertEquals(MaskMode.RADIAL, b.mode)
        assertTrue(b.invert)
        assertContentEquals(a.painted, b.painted)
    }

    @Test
    fun `h264 prep without changes keeps the picture, and its colors sit on the 2 x 2 grid`() {
        val src = testImage(64, 48)
        val neutral = H264Prep.apply(src, H264Prep.defaultValues(mapOf("sharpen" to 0, "denoise" to 0, "chroma" to 0)), 0)
        for (i in src.data.indices) for (s in listOf(16, 8, 0)) {
            assertTrue(kotlin.math.abs((src.data[i] shr s and 0xFF) - (neutral.data[i] shr s and 0xFF)) <= 1)
        }
        // strong quantizing: within a 4 × 4 block the brightness has far fewer levels
        val coarse = H264Prep.apply(src, H264Prep.defaultValues(mapOf("qp" to 45)), 0)
        assertTrue(!coarse.data.contentEquals(src.data))
        val sharp = H264Prep.apply(src, H264Prep.defaultValues(mapOf("sharpen" to 200, "denoise" to 0, "chroma" to 0)), 0)
        assertTrue(!sharp.data.contentEquals(neutral.data))
    }

    @Test
    fun `the wand follows a soft gradient but stops where the hue changes`() {
        // a wall getting darker from left to right, with a warm patch in it whose edge is soft too
        val wall = Pixels(200, 100).also { p ->
            for (y in 0 until 100) for (x in 0 until 200) {
                val l = 230 - x / 4
                val warm = (((x - 150) * (x - 150) + (y - 50) * (y - 50)) < 400)
                p.data[y * 200 + x] = if (warm) com.spielgrund.glitchr.image.argb(255, l, l - 45, l - 70)
                else com.spielgrund.glitchr.image.argb(255, l, l - 5, l - 15)
            }
        }
        val plain = com.spielgrund.glitchr.model.Selection.similarColor(wall, 5, 5, 20, true, 0)!!
        assertTrue(plain.rect.width < 100, "plain wand stops part way along the gradient: ${plain.rect.width}")
        val follow = com.spielgrund.glitchr.model.Selection.similarColor(wall, 5, 5, 20, true, 0, gradients = true)!!
        assertEquals(200, follow.rect.width, "follows the gradient to the right edge")
        // the warm patch stays out
        val r = follow.rect
        assertEquals(0, follow.data[(50 - r.y) * r.width + (150 - r.x)].toInt(), "warm patch not selected")
    }

    @Test
    fun `a selection can be refined afterwards and keeps what was there before`() {
        val mask = Mask().apply { mode = MaskMode.BRUSH; ensurePainted(100, 100) }
        // something painted before: the top left corner
        mask.fromValues(FloatArray(10000) { if (it % 100 < 10 && it / 100 < 10) 1f else 0f }, 100, 100)
        val square = FloatArray(10000) { val x = it % 100; val y = it / 100; if (x in 40 until 60 && y in 40 until 60) 1f else 0f }
        val op = com.spielgrund.glitchr.ui.SelectionOp(mask, mask.painted!!.copyOf(), 100, 100, 1, false, 0, 0) { square }
        op.apply()
        fun selected() = mask.painted!!.count { (it.toInt() and 0xFF) > 127 }
        val base = selected()
        assertEquals(100 + 400, base)
        op.amount = 80
        op.apply()
        assertTrue(selected() > base + 100, "more: ${selected()}")
        op.amount = -80
        op.apply()
        assertTrue(selected() < base - 100, "less: ${selected()}")
        // the corner painted before is still there
        assertEquals(255, mask.painted!![5 * 100 + 5].toInt() and 0xFF)
    }

    @Test
    fun `a noise mask starts white and the noise appears on it`() {
        val mask = Mask().apply { mode = MaskMode.NOISE }
        fun rows() = (0 until 50).flatMap { y -> FloatArray(200).also { mask.shape().row(y, 200, 100, it) }.toList() }
        assertTrue(rows().all { it == 1f }, "without noise: white")
        fun specks(noise: Int): Int {
            mask.breakup = com.spielgrund.glitchr.model.MaskBreakup(noise = noise)
            return rows().count { it < 1f }
        }
        val few = specks(20)
        val many = specks(80)
        assertTrue(few in 1 until many, "more noise, more specks: $few < $many")
        // streaks smear the noise itself along the rows: long runs of equal values
        mask.breakup = com.spielgrund.glitchr.model.MaskBreakup(noise = 50, style = com.spielgrund.glitchr.model.ScatterStyle.STREAKS_X, runLength = 30)
        val row = FloatArray(200).also { mask.shape().row(10, 200, 100, it) }
        val equalNeighbours = (0 until 199).count { row[it] == row[it + 1] }
        assertTrue(equalNeighbours > 150, "streaks: $equalNeighbours equal neighbours")
    }
}
