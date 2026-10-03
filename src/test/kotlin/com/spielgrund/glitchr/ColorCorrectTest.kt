package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.ColorCorrect
import com.spielgrund.glitchr.effects.Curves
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorCorrectTest {
    private val src = testImage(80, 60)

    private fun neutral(changes: Map<String, Int> = emptyMap(), curves: String = "") =
        ColorCorrect.defaultValues(changes, mapOf("curves" to curves))

    private fun one(c: Int, changes: Map<String, Int>, curves: String = "") =
        ColorCorrect.apply(Pixels(1, 1, intArrayOf(c)), neutral(changes, curves), 0)[0, 0]

    private val gray = argb(255, 128, 128, 128)

    @Test
    fun `neutral settings leave the picture alone`() {
        assertContentEquals(src.data, ColorCorrect.apply(src, neutral(), 0).data)
    }

    @Test
    fun `exposure brightens, tonemapping keeps overexposed highlights below white`() {
        assertTrue(red(one(gray, mapOf("exposure" to 100))) > 160)
        val hot = argb(255, 250, 240, 230)
        // +1.5 EV: without tonemapping clipped to white, with ACES still shaded
        assertEquals(255, red(one(hot, mapOf("exposure" to 150))))
        val aces = one(hot, mapOf("exposure" to 150, "tonemap" to 2))
        assertTrue(red(aces) in 200..254 && blue(aces) < red(aces))
    }

    @Test
    fun `levels, contrast and hsl`() {
        // black point 128: middle gray becomes black
        assertEquals(0, red(one(gray, mapOf("blackPoint" to 128))))
        // saturation 0: gray
        val c = one(argb(255, 200, 40, 40), mapOf("saturation" to 0))
        assertTrue(abs(red(c) - green(c)) <= 1 && abs(green(c) - blue(c)) <= 1)
        // hue 120°: red turns green
        val g = one(argb(255, 200, 40, 40), mapOf("hue" to 120))
        assertTrue(green(g) > red(g) && green(g) > blue(g))
    }

    @Test
    fun `curves pass through their points without overshooting`() {
        val lut = Curves.lut(listOf(0 to 0, 64 to 128, 128 to 128, 255 to 255))
        assertEquals(128 / 255.0, lut[64], 0.01)
        assertEquals(128 / 255.0, lut[128], 0.01)
        // between two equal points it stays flat: no bump above
        assertTrue((64..128).all { lut[it] <= 128 / 255.0 + 1e-9 })
        // an inverted RGB curve inverts the picture; a channel curve only its channel
        assertEquals(argb(255, 55, 155, 225), one(argb(255, 200, 100, 30), emptyMap(), "rgb:0,255 255,0"))
        val redOnly = one(argb(255, 200, 100, 30), emptyMap(), "r:0,0 255,0")
        assertEquals(argb(255, 0, 100, 30), redOnly)
        // text round trip; straight curves are left out
        val parsed = Curves.parse("rgb:0,10 128,140 255,250;g:0,0 255,255")
        assertEquals("rgb:0,10 128,140 255,250", Curves.format(parsed))
    }

    @Test
    fun `saturation oversteers like old Photoshop, overflow and mirror work on the saturation`() {
        val pale = argb(255, 150, 120, 110)
        // 1000 %: the channels are pushed far apart and slam into the ends
        val hard = one(pale, mapOf("saturation" to 1000))
        assertEquals(255, red(hard))
        assertEquals(0, blue(hard))
        // for this color red reaches white at a push of about 5.3 (≈ 532 %); 665 % is 1.25 times that:
        // overflow starts again from gray (0.25 of the way), mirror runs back (0.75 of the way)
        val wrapped = one(pale, mapOf("saturation" to 665, "saturationEdge" to 1))
        val mirrored = one(pale, mapOf("saturation" to 665, "saturationEdge" to 2))
        assertTrue(abs(red(wrapped) - 158) <= 3, "overflow ${red(wrapped)}")
        assertTrue(abs(red(mirrored) - 223) <= 3, "mirror ${red(mirrored)}")
        // the hue stays: red strongest, blue weakest
        for (c in listOf(wrapped, mirrored)) assertTrue(red(c) > green(c) && green(c) > blue(c))
        // below the full saturation all three behave the same
        assertEquals(one(pale, mapOf("saturation" to 300)), one(pale, mapOf("saturation" to 300, "saturationEdge" to 1)))
        // 0 %: gray
        val gray = one(pale, mapOf("saturation" to 0))
        assertTrue(abs(red(gray) - blue(gray)) <= 1)
    }

    @Test
    fun `lightness beyond its range clips, overflows or mirrors`() {
        val light = argb(255, 200, 200, 200)
        assertEquals(255, red(one(light, mapOf("lightness" to 80))))
        val wrappedL = red(one(light, mapOf("lightness" to 80, "lightnessEdge" to 1)))
        val mirroredL = red(one(light, mapOf("lightness" to 80, "lightnessEdge" to 2)))
        assertTrue(wrappedL < 150, "overflow $wrappedL")
        assertTrue(mirroredL in 100..240 && mirroredL != wrappedL, "mirrored $mirroredL")
    }

    @Test
    fun `highlight compression rolls highlights off, negative stretches them`() {
        // the curve itself: below the knee nothing, above it lower (compressed) or higher (stretched)
        assertEquals(0.3, ColorCorrect.compressHighlight(0.3, 0.5, 1.0), 1e-9)
        assertTrue(ColorCorrect.compressHighlight(1.0, 0.5, 1.0) < 0.7)
        assertTrue(ColorCorrect.compressHighlight(1.0, 0.5, -1.0) > 2.0)
        val bright = argb(255, 235, 225, 200)
        val dark = argb(255, 60, 50, 40)
        val down = one(bright, mapOf("highlightCompression" to 80))
        // just above the knee a little brighter, well above it blown out
        val slight = one(argb(255, 170, 160, 140), mapOf("highlightCompression" to -80))
        val up = one(argb(255, 210, 200, 180), mapOf("highlightCompression" to -80))
        assertTrue(red(down) < 200, "pulled down ${red(down)}")
        assertTrue(red(slight) > 185, "a little brighter ${red(slight)}")
        assertTrue(red(up) >= 254, "burnt out ${red(up)}")
        // the dark pixel below the knee stays
        assertEquals(dark, one(dark, mapOf("highlightCompression" to -80)))
        // per channel, so the hues tip: compressed, the channels move closer together (paler);
        // stretched, a channel above the knee blows out while one below it stays
        assertTrue(red(down) - blue(down) < 235 - 200)
        val tipped = one(argb(255, 230, 200, 90), mapOf("highlightCompression" to -80))
        assertEquals(255, red(tipped))
        assertEquals(90, blue(tipped))
    }
}
