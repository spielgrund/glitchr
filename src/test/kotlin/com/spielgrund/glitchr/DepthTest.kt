package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Fog
import com.spielgrund.glitchr.effects.Wiggle
import com.spielgrund.glitchr.effects.ZMap
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthTest {
    /** Top half: a flat, pale "sky"; bottom half: detailed, colorful stripes (near). */
    private val scene = Pixels(120, 90, IntArray(120 * 90) { i ->
        val x = i % 120
        val y = i / 120
        if (y < 45) argb(255, 190, 200, 215)
        else if ((x / 3 + y / 3) % 2 == 0) argb(255, 200, 60, 30) else argb(255, 40, 120, 30)
    })

    @Test
    fun `the guessed depth puts the detailed, colorful, low part near`() {
        val z = ZMap.apply(scene, ZMap.defaultValues(), 3L)
        assertTrue(red(z[60, 75]) > red(z[60, 15]) + 60, "nah ${red(z[60, 75])}, fern ${red(z[60, 15])}")
        // grey: all channels the same
        assertEquals(red(z[60, 75]), z[60, 75] and 0xFF)
        val inverted = ZMap.apply(scene, ZMap.defaultValues(mapOf("depthInvert" to 1)), 3L)
        assertTrue(red(inverted[60, 75]) < red(inverted[60, 15]))
    }

    @Test
    fun `fog covers the far part more than the near one`() {
        val fogged = Fog.apply(scene, Fog.defaultValues(mapOf("fogColor" to 0xFFFFFF, "wisps" to 0, "haze" to 0, "aerial" to 0)), 3L)
        fun change(x: Int, y: Int) = kotlin.math.abs(red(fogged[x, y]) - red(scene[x, y])) + kotlin.math.abs((fogged[x, y] and 0xFF) - (scene[x, y] and 0xFF))
        assertTrue(change(60, 15) > 5, "fern ${change(60, 15)}")
        val shown = Fog.apply(scene, Fog.defaultValues(mapOf("depthShow" to 1)), 3L)
        assertContentEquals(ZMap.apply(scene, ZMap.defaultValues(), 3L).data, shown.data)
    }

    @Test
    fun `the camera moves near things more than far ones`() {
        // focus far: the far sky stays, the near stripes move
        val moved = Wiggle.apply(scene, Wiggle.defaultValues(mapOf("mode" to 0, "camX" to 6, "camY" to 0, "focus" to 0)), 3L)
        val skyChanged = (10 until 110).count { moved[it, 10] != scene[it, 10] }
        val groundChanged = (10 until 110).count { moved[it, 80] != scene[it, 80] }
        assertTrue(groundChanged > skyChanged + 20, "Boden $groundChanged, Himmel $skyChanged")
        for (mode in 1..3) {
            val v = Wiggle.defaultValues(mapOf("mode" to mode))
            assertContentEquals(Wiggle.apply(scene, v, 3L).data, Wiggle.apply(scene, v, 3L).data)
        }
    }
}
