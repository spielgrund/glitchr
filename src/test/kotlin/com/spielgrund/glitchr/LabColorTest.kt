package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.LabColor
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabColorTest {
    private val src = testImage(80, 60)
    private fun neutral(changes: Map<String, Int> = emptyMap()) = LabColor.defaultValues(mapOf("chroma" to 100) + changes)
    private fun one(c: Int, changes: Map<String, Int>) = LabColor.apply(Pixels(1, 1, intArrayOf(c)), neutral(changes), 0)[0, 0]

    @Test
    fun `without changes the conversion there and back keeps the colors`() {
        val out = LabColor.apply(src, neutral(), 0)
        for (i in src.data.indices) {
            val a = src.data[i]
            val b = out.data[i]
            assertTrue(abs(red(a) - red(b)) <= 1 && abs(green(a) - green(b)) <= 1 && abs(blue(a) - blue(b)) <= 1, "Pixel $i")
            assertEquals(a ushr 24, b ushr 24)
        }
    }

    @Test
    fun `chroma 0 gives grays of the same lightness`() {
        val c = one(argb(255, 220, 40, 60), mapOf("chroma" to 0))
        assertTrue(abs(red(c) - green(c)) <= 1 && abs(green(c) - blue(c)) <= 1)
    }

    @Test
    fun `a gray picture can be tinted, like sepia`() {
        // chroma 0 first, then yellow (b) and a little magenta (a)
        val c = one(argb(255, 60, 120, 200), mapOf("chroma" to 0, "bShift" to 30, "aShift" to 8))
        assertTrue(red(c) > green(c) && green(c) > blue(c), "warm getönt")
    }

    @Test
    fun `hue turns in LCh, lightness inverts, a flips green and magenta`() {
        val red = argb(255, 220, 40, 60)
        val turned = one(red, mapOf("hue" to 90))
        val diff = (LabColor.hueOf(turned) - LabColor.hueOf(red) + 360) % 360
        assertTrue(abs(diff - 90) < 8, "Farbton $diff")
        val inverted = one(argb(255, 30, 30, 30), mapOf("lInvert" to 1))
        assertTrue(red(inverted) > 180)
        // green (a < 0) turns magenta-ish with a negative a gain
        val flipped = one(argb(255, 40, 180, 60), mapOf("aGain" to -100))
        assertTrue(red(flipped) > green(flipped) && blue(flipped) > green(flipped) - 40)
    }

    @Test
    fun `swapping a and b and overflowing work`() {
        val c = argb(255, 40, 90, 220)
        val swapped = one(c, mapOf("swap" to 1))
        assertTrue(swapped != c)
        // an extreme push leaves the screen colors: clipped stays at the edge, overflow jumps
        val clipped = one(c, mapOf("bShift" to 128, "aShift" to 128))
        val wrapped = one(c, mapOf("bShift" to 128, "aShift" to 128, "gamut" to 1))
        assertTrue(clipped != wrapped)
    }
}
