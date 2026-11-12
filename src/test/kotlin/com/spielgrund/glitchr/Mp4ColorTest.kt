package com.spielgrund.glitchr

import com.spielgrund.glitchr.export.AnimationExport
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp4ColorTest {
    @Test
    fun `frames go into the video as Rec 709 in the TV range`() {
        val img = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB)
        // white, black, then a pure red block
        for (y in 0..1) { img.setRGB(0, y, 0xFFFFFF); img.setRGB(1, y, 0xFFFFFF); img.setRGB(2, y, 0xFF0000); img.setRGB(3, y, 0xFF0000) }
        val p = AnimationExport.toRec709(img)
        fun luma(x: Int) = p.getPlaneData(0)[x] + 128
        assertEquals(235, luma(0))
        assertEquals(63, luma(2)) // 16 + 0.2126 · 219
        // the white block has no color; the red one its Rec. 709 color difference
        assertEquals(128, p.getPlaneData(1)[0] + 128)
        assertEquals(128, p.getPlaneData(2)[0] + 128)
        assertEquals(240, p.getPlaneData(2)[1] + 128)
        assertEquals(102, p.getPlaneData(1)[1] + 128)
        val black = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        assertEquals(16, AnimationExport.toRec709(black).getPlaneData(0)[0] + 128)
    }

    @Test
    fun `the MP4 preview shows the picture close to how the export comes out`() {
        val img = testImage(64, 48)
        val fx = com.spielgrund.glitchr.effects.Mp4Preview
        fun meanDiff(a: com.spielgrund.glitchr.image.Pixels, b: com.spielgrund.glitchr.image.Pixels) =
            a.data.indices.sumOf { i -> (0..2).sumOf { k -> kotlin.math.abs((a.data[i] shr (k * 8) and 0xFF) - (b.data[i] shr (k * 8) and 0xFF)) } } / (a.data.size * 3.0)
        val exported = fx.apply(img, fx.defaultValues(), 1L)
        val colorsOnly = fx.apply(img, fx.defaultValues(mapOf("show" to 1)), 1L)
        // close to the picture, but not the same (4:2:0, rounding, compression)
        assertTrue(meanDiff(exported, img) in 0.2..12.0, "${meanDiff(exported, img)}")
        assertTrue(meanDiff(colorsOnly, img) < 6.0)
        // odd sizes work; transparency lies on the background
        val odd = com.spielgrund.glitchr.image.Pixels(33, 17)
        val onWhite = fx.apply(odd, fx.defaultValues(mapOf("background" to 1, "show" to 1)), 1L)
        assertTrue((onWhite[5, 5] and 0xFF) > 245)
        // the difference shows dark where nothing changed
        val flat = com.spielgrund.glitchr.image.Pixels(32, 32, IntArray(32 * 32) { 0xFF808080.toInt() })
        assertTrue((fx.apply(flat, fx.defaultValues(mapOf("show" to 2)), 1L)[10, 10] and 0xFF) < 30)
    }

    @Test
    fun `full range spans all of 0 to 255`() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (y in 0..1) img.setRGB(0, y, 0xFFFFFF)
        val p = AnimationExport.toRec709(img, fullRange = true)
        assertEquals(255, p.getPlaneData(0)[0] + 128)
        assertEquals(0, p.getPlaneData(0)[1] + 128)
        // the preview reads it back the same way: white stays white
        val white = com.spielgrund.glitchr.image.Pixels(16, 16, IntArray(256) { 0xFFFFFFFF.toInt() })
        val fx = com.spielgrund.glitchr.effects.Mp4Preview
        assertTrue((fx.apply(white, fx.defaultValues(mapOf("levels" to 1)), 1L)[5, 5] and 0xFF) > 250)
    }

    @Test
    fun `through FFmpeg the colors stay as they are`() {
        // only where FFmpeg with x264 is installed
        if (com.spielgrund.glitchr.export.Ffmpeg.path == null) return
        val img = testImage(96, 64)
        val back = com.spielgrund.glitchr.export.Ffmpeg.roundTrip(com.spielgrund.glitchr.export.AnimationExport.prepare(img, 1.0, 0), false)!!
        val rgb = back.getRGB(0, 0, 96, 64, null, 0, 96)
        // no color cast: the mean of every channel stays within a step or two
        for (k in 0..2) {
            val before = img.data.sumOf { (it shr (8 * k) and 0xFF).toLong() } / img.data.size.toDouble()
            val after = rgb.sumOf { (it shr (8 * k) and 0xFF).toLong() } / rgb.size.toDouble()
            assertTrue(kotlin.math.abs(after - before) < 2.0, "channel $k: $before -> $after")
        }
    }
}
