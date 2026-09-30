package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.ColorRamp
import com.spielgrund.glitchr.effects.ColorStop
import com.spielgrund.glitchr.effects.Ramp
import com.spielgrund.glitchr.effects.RampPalette
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RampTest {
    private val blueToYellow = ColorRamp(listOf(ColorStop(0.0, 0x0000FF), ColorStop(1.0, 0xFFFF00))).format()

    private fun one(c: Int, changes: Map<String, Int> = emptyMap(), ramp: String = blueToYellow) =
        Ramp.apply(Pixels(1, 1, intArrayOf(c)), Ramp.defaultValues(changes, mapOf("ramp" to ramp)), 0)[0, 0]

    @Test
    fun `the ramp text keeps its stops`() {
        val ramp = RampPalette.TERRAIN.ramp
        val back = ColorRamp.parse(ramp.format())
        assertEquals(ramp.stops.size, back.stops.size)
        for ((a, b) in ramp.stops.zip(back.stops)) {
            assertEquals(a.rgb, b.rgb)
            assertEquals(a.pos, b.pos, 1e-4)
        }
        // reversed: the ends swap
        assertEquals(ramp.stops.last().rgb, ramp.reversed().colorAt(0.0))
    }

    @Test
    fun `black and white take the ends of the ramp`() {
        assertEquals(argb(255, 0, 0, 255), one(argb(255, 0, 0, 0)))
        assertEquals(argb(255, 255, 255, 0), one(argb(255, 255, 255, 255)))
    }

    @Test
    fun `only color keeps the brightness`() {
        val c = one(argb(255, 128, 128, 128), mapOf("mode" to 1))
        val l = 0.2126 * red(c) + 0.7152 * green(c) + 0.0722 * blue(c)
        assertTrue(abs(l - 128) < 6, "Helligkeit $l")
        assertTrue(blue(c) != red(c), "eingefärbt")
    }

    @Test
    fun `repeats make bands, mirrored or with hard jumps`() {
        // brightness 0.75 with 2 repeats: position 1.5 → mirrored back to 0.5, hard jump to 0.5 as well; 0.4 → 0.8
        val mid = one(argb(255, 102, 102, 102), mapOf("repeats" to 2))
        val hard = one(argb(255, 102, 102, 102), mapOf("repeats" to 2, "mirror" to 0))
        assertEquals(mid, hard)
        val high = one(argb(255, 230, 230, 230), mapOf("repeats" to 2))
        val highHard = one(argb(255, 230, 230, 230), mapOf("repeats" to 2, "mirror" to 0))
        // 0.9 × 2 = 1.8: mirrored 0.2 (blue side), hard 0.8 (yellow side)
        assertTrue(blue(high) > blue(highHard) && red(highHard) > red(high))
    }

    @Test
    fun `the ramp can follow the hue instead of the brightness`() {
        val red = argb(255, 255, 0, 0)
        val green = argb(255, 0, 255, 0)
        // by brightness red and green differ a lot, by hue (0 vs 85 of 255) too – but by saturation they are equal
        val bySaturation = mapOf("source" to 4)
        assertEquals(one(red, bySaturation), one(green, bySaturation))
    }
}
