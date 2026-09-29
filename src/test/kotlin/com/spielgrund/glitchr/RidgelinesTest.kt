package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Ridgelines
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertTrue

class RidgelinesTest {
    @Test
    fun `lines rise where the picture is bright`() {
        // dark left half, bright right half: only the right half gets tall mountains
        val img = Pixels(200, 200)
        for (y in 0 until 200) for (x in 0 until 200) img.data[y * 200 + x] = if (x < 100) 0xFF000000.toInt() else -1
        val out = Ridgelines.apply(img, Ridgelines.defaultValues(mapOf("jag" to 0, "height" to 40, "spacing" to 30, "smooth" to 0)), 1L)
        fun white(x: Int, y: Int) = out[x, y] and 0xFF > 128
        // left: the flat lines lie on their baselines 15, 45, 75 …
        assertTrue(white(50, 45) && white(50, 75) && !white(50, 60))
        // right: every line is lifted by 40 px (baseline 75 → 35), the lines behind are hidden
        assertTrue(white(150, 35) && !white(150, 45) && !white(150, 75), "rechts angehoben")
    }

    @Test
    fun `front lines hide the ones behind`() {
        val img = Pixels(200, 200).also { it.data.fill(-1) }
        fun white(occlude: Int) = Ridgelines.apply(img, Ridgelines.defaultValues(mapOf("source" to 2, "height" to 60, "spacing" to 10, "occlude" to occlude)), 1L)
            .data.count { it and 0xFF > 128 }
        assertTrue(white(1) < white(0), "verdeckt: weniger Linien sichtbar")
    }

    @Test
    fun `the angle turns the lines`() {
        val img = Pixels(200, 160).also { it.data.fill(0xFF808080.toInt()) }
        fun out(angle: Int) = Ridgelines.apply(img, Ridgelines.defaultValues(mapOf("jag" to 0, "angle" to angle, "spacing" to 16)), 1L)
        fun fullRows(p: Pixels) = (0 until p.height).count { y -> (0 until p.width).count { x -> p[x, y] and 0xFF > 128 } > p.width * 0.9 }
        fun fullColumns(p: Pixels) = (0 until p.width).count { x -> (0 until p.height).count { y -> p[x, y] and 0xFF > 128 } > p.height * 0.9 }
        val flat = out(0)
        assertTrue(fullRows(flat) > 0 && fullColumns(flat) == 0, "0°: waagrecht")
        val turned = out(90)
        assertTrue(fullColumns(turned) > 0 && fullRows(turned) == 0, "90°: senkrecht")
    }
}
