package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.effects.PixelRain
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.red
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixelRainTest {
    private val black = Pixels(160, 120, IntArray(160 * 120) { 0xFF000000.toInt() })
    private val white = Pixels(160, 120, IntArray(160 * 120) { 0xFFFFFFFF.toInt() })

    private fun run(m: Map<String, Int>, base: Pixels = black) = PixelRain.apply(base, PixelRain.defaultValues(m), 5)
    private fun lit(p: Pixels, base: Pixels = black) = p.data.indices.filter { p.data[it] != base.data[it] }

    @Test
    fun `the lines move down as the time goes on, every frame a little`() {
        // one line, falling straight down: its middle moves down
        val one = mapOf("count" to 1, "depth" to 0, "speedVariation" to 0, "speed" to 50, "lifetime" to 3000, "fadeOut" to 0, "fadeIn" to 0)
        fun middle(time: Int) = lit(run(one + ("time" to time))).map { it / 160 }.average()
        val frames = (0..3).map { middle(it * 40) }
        for ((a, b) in frames.zipWithNext()) assertTrue(b > a, "$a then $b")
    }

    @Test
    fun `gravity pulls them faster and faster`() {
        // slow at the top, fast at the bottom: they crowd at the top, steadily falling ones are spread evenly
        val lines = mapOf("count" to 400, "depth" to 0, "speedVariation" to 0, "lifetime" to 3000, "fadeOut" to 0)
        fun topToBottom(m: Map<String, Int>): Double {
            val rows = lit(run(lines + m)).map { it / 160 }
            return rows.count { it < 40 }.toDouble() / rows.count { it >= 80 }.coerceAtLeast(1)
        }
        val falling = topToBottom(mapOf("motion" to 1, "speed" to 0, "gravity" to 100))
        val steady = topToBottom(mapOf("speed" to 50))
        assertTrue(falling > steady * 1.5, "$falling / $steady")
    }

    @Test
    fun `a short life keeps them near the top`() {
        // half a second at a fifth of the height per second: they die before they get far
        val rows = lit(run(mapOf("lifetime" to 50, "lifeVariation" to 0, "speed" to 20, "speedVariation" to 0, "depth" to 0))).map { it / 160 }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.max() < 40, "lowest ${rows.max()}")
    }

    @Test
    fun `the oscillators change the speed, the slants the ends`() {
        for (osc in listOf("sine", "saw", "pulse"))
            assertTrue(!run(mapOf("time" to 1500)).data.contentEquals(run(mapOf(osc to 50, "time" to 1500)).data), osc)
        // the oscillators only go along the fall: straight down they never move a line to the side
        val one = mapOf("count" to 1, "depth" to 0, "lifetime" to 3000, "time" to 400, "width" to 30)
        fun columns(m: Map<String, Int>) = lit(run(one + m)).map { it % 160 }.toSet()
        assertEquals(columns(emptyMap()), columns(mapOf("sine" to 20, "saw" to 20, "pulse" to 20)))
        val slanted = run(mapOf("width" to 80, "frontSlant" to 60, "backSlant" to -60))
        val square = run(mapOf("width" to 80, "frontSlant" to 0, "backSlant" to 0))
        assertTrue(!slanted.data.contentEquals(square.data))
    }

    @Test
    fun `the drop shadow darkens the background beside the lines`() {
        val m = mapOf("width" to 80, "count" to 60, "color1" to 0xFF0000, "color2" to 0xFF0000)
        val plain = run(m, white)
        val shadowed = run(m + mapOf("dropShadow" to 100, "dropDistance" to 300), white)
        // grey pixels: neither the white background nor the red lines
        fun dark(p: Pixels) = p.data.count { red(it) < 200 && blue(it) < 200 }
        assertTrue(dark(shadowed) > dark(plain) + 50, "${dark(plain)} / ${dark(shadowed)}")
    }

    @Test
    fun `on the pixel grid every cell is one color, transparent leaves the background clear`() {
        val out = run(mapOf("pixel" to 8))
        for (cy in 0 until 15) for (cx in 0 until 20) {
            val c = out[cx * 8, cy * 8]
            for (y in 0 until 8) for (x in 0 until 8) assertEquals(c, out[cx * 8 + x, cy * 8 + y])
        }
        val clear = Generators.byId("pixelrain")!!.let { it.generate(160, 120, it.defaultValues(mapOf("transparent" to 1, "fadeOut" to 0, "fadeIn" to 0, "depth" to 0, "width" to 40, "count" to 40)), 5) }
        assertTrue(clear.data.count { alpha(it) == 0 } > clear.data.size / 2, "mostly clear")
        assertTrue(clear.data.any { alpha(it) == 255 }, "with lines")
    }
}
