package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.LaserArray
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaserArrayTest {
    private val src = Pixels(200, 150, IntArray(200 * 150) { 0xFF000000.toInt() })
    private val centered = mapOf("centerY" to 0, "tilt" to 0, "haze" to 0)

    private fun run(m: Map<String, Int>) = LaserArray.apply(src, LaserArray.defaultValues(centered + m), 1)
    private fun light(p: Pixels, x0: Int, y0: Int, x1: Int, y1: Int) = (y0 until y1).sumOf { y -> (x0 until x1).sumOf { x -> blue(p[x, y]) } }

    @Test
    fun `the beams fan out downwards from the source`() {
        val out = run(emptyMap())
        val below = light(out, 60, 120, 140, 150)
        assertTrue(below > 20000, "below the source")
        assertTrue(light(out, 0, 0, 200, 40) < below / 20, "hardly anything above it")
    }

    @Test
    fun `rolled half around they point up`() {
        val out = run(mapOf("roll" to 18000))
        val above = light(out, 60, 0, 140, 30)
        assertTrue(above > 20000, "above")
        assertTrue(light(out, 0, 110, 200, 150) < above / 20, "hardly anything below")
    }

    @Test
    fun `tilting, the layouts and the time change the picture`() {
        val base = run(emptyMap()).data
        assertTrue(!base.contentEquals(run(mapOf("tilt" to 6000)).data), "tilt")
        assertTrue(!base.contentEquals(run(mapOf("turn" to 6000, "tilt" to 3000)).data), "turn")
        for (layout in 1..3) assertTrue(light(run(mapOf("layout" to layout, "tilt" to 4000)), 0, 0, 200, 150) > 1000, "layout $layout")
        val swept = mapOf("sweep" to 3000, "flicker" to 50)
        assertTrue(!run(swept + ("time" to 0)).data.contentEquals(run(swept + ("time" to 700)).data), "animated")
    }

    @Test
    fun `transparent shows only the light`() {
        val out = run(mapOf("transparent" to 1))
        assertEquals(0, alpha(out[5, 5]), "clear away from the beams")
        assertTrue(alpha(out[100, 75]) > 200, "the source")
    }
}
