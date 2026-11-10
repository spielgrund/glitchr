package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Smoke
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest {
    private val src = Pixels(200, 150, IntArray(200 * 150) { 0xFF000000.toInt() })

    private fun run(m: Map<String, Int>) = Smoke.apply(src, Smoke.defaultValues(m), 4)
    private fun changed(p: Pixels, x0: Int, y0: Int, x1: Int, y1: Int) =
        (y0 until y1).sumOf { y -> (x0 until x1).count { x -> p[x, y] != src[x, y] } }.toDouble() / ((x1 - x0) * (y1 - y0))

    @Test
    fun `the plume rises from its source`() {
        val out = run(mapOf("centerY" to 500))
        assertTrue(changed(out, 90, 40, 110, 110) > 0.8, "above the source")
        assertEquals(0.0, changed(out, 0, 135, 30, 150), "not below and aside")
    }

    @Test
    fun `ground fog lies low`() {
        val out = run(mapOf("kind" to 1))
        assertTrue(changed(out, 0, 0, 200, 10) < 0.2, "mostly clear at the top")
        assertTrue(changed(out, 0, 135, 200, 150) > 0.9, "thick at the bottom")
    }

    @Test
    fun `every kind gives smoke and the time moves it`() {
        for (kind in 0..3) assertTrue(changed(run(mapOf("kind" to kind, "centerY" to 0)), 0, 0, 200, 150) > 0.1, "kind $kind")
        assertTrue(!run(mapOf("time" to 0)).data.contentEquals(run(mapOf("time" to 500)).data), "moves")
    }

    @Test
    fun `old smokelets move on smoothly from frame to frame instead of jumping`() {
        val big = Pixels(240, 180, IntArray(240 * 180) { 0xFF000000.toInt() })
        val m = mapOf("centerY" to 600, "turbulence" to 200, "life" to 100)
        val frames = (0..6).map { Smoke.apply(big, Smoke.defaultValues(m + ("time" to it * 100)), 4) }
        for ((a, b) in frames.zipWithNext()) {
            val diff = a.data.indices.sumOf { kotlin.math.abs(com.spielgrund.glitchr.image.green(a.data[it]) - com.spielgrund.glitchr.image.green(b.data[it])) }.toDouble() / a.data.size
            // with the steps growing with the age it was about 10
            assertTrue(diff < 7.5, "change between frames $diff")
        }
    }

    @Test
    fun `taming holds strays back towards the flow`() {
        val wild = mapOf("centerY" to 500, "turbulence" to 200, "spread" to 0)
        // how far the smoke of a plume rising straight up reaches out to the sides, on average
        fun aside(p: Pixels): Double {
            var sum = 0.0
            var weight = 0.0
            for (y in 0 until 150) for (x in 0 until 200) {
                val g = com.spielgrund.glitchr.image.green(p[x, y]).toDouble()
                sum += g * kotlin.math.abs(x - 100)
                weight += g
            }
            return sum / weight
        }
        val tamed = aside(run(wild + ("tame" to 100)))
        val free = aside(run(wild + ("tame" to 0)))
        assertTrue(tamed < free * 0.95, "$tamed / $free")
    }

    @Test
    fun `shapes, mirroring, the second light and the motion blur all change the smoke`() {
        val base = run(emptyMap()).data
        for (m in listOf(mapOf("shapes" to 1), mapOf("mirror" to 0), mapOf("light2" to 150), mapOf("light2Back" to 150), mapOf("motionBlur" to 300)))
            assertTrue(!base.contentEquals(run(m).data), "$m")
    }

    @Test
    fun `turning the light only changes the light, not the shapes`() {
        val shapeAt = { angle: Int -> run(mapOf("transparent" to 1, "lightAngle" to angle)).data.map { alpha(it) } }
        val above = shapeAt(90)
        for (angle in listOf(0, 45, 200)) kotlin.test.assertEquals(above, shapeAt(angle), "light from $angle°")
    }

    @Test
    fun `the density levels cut the faint smoke and make the thick opaque`() {
        val plain = run(mapOf("transparent" to 1)).data
        val cut = run(mapOf("transparent" to 1, "densityLow" to 40)).data
        assertTrue(cut.count { alpha(it) > 0 } < plain.count { alpha(it) > 0 }, "less faint smoke")
        val full = run(mapOf("transparent" to 1, "densityHigh" to 20)).data
        assertTrue(full.count { alpha(it) == 255 } > plain.count { alpha(it) == 255 }, "more opaque smoke")
    }

    @Test
    fun `the backlights brighten the smoke`() {
        fun light(p: Pixels) = p.data.sumOf { com.spielgrund.glitchr.image.blue(it).toLong() }
        val plain = light(run(emptyMap()))
        assertTrue(light(run(mapOf("backlight" to 150))) > plain * 1.05, "backlight")
        assertTrue(light(run(mapOf("light2Back" to 150, "light2Color" to 0x4080FF))) > plain * 1.02, "second backlight")
    }

    @Test
    fun `the drop shadow darkens the ground beside the smoke, the shoulder softens the highlights`() {
        val grey = Pixels(200, 150, IntArray(200 * 150) { 0xFF909090.toInt() })
        val m = mapOf("centerY" to 500, "dropAngle" to 0, "dropDistance" to 60)
        val plain = Smoke.apply(grey, Smoke.defaultValues(m), 4)
        val shadowed = Smoke.apply(grey, Smoke.defaultValues(m + ("dropShadow" to 100)), 4)
        fun light(p: Pixels) = p.data.sumOf { com.spielgrund.glitchr.image.green(it).toLong() }
        assertTrue(light(shadowed) < light(plain) * 0.97, "darker with a drop shadow")
        val bright = mapOf("backlight" to 200, "backlightColor" to 0xFFFFFF, "transparent" to 1)
        val clipped = run(bright + ("shoulder" to 0)).data.count { com.spielgrund.glitchr.image.green(it) == 255 }
        val soft = run(bright + ("shoulder" to 100)).data.count { com.spielgrund.glitchr.image.green(it) == 255 }
        assertTrue(soft < clipped, "$soft / $clipped burnt out")
    }

    @Test
    fun `the time runs in milliseconds, so every frame of an animation moves on`() {
        // 25 frames per second: 40 ms apart – with tenths of a second, frames repeated and then jumped
        val frames = (0..4).map { run(mapOf("time" to it * 40)).data }
        for ((a, b) in frames.zipWithNext()) assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `a stronger backlight keeps brightening the thin edges, the second backlight has its own color`() {
        // the faint edge of a plume: where the smoke is barely there
        fun edge(m: Map<String, Int>): Long {
            val plain = run(mapOf("centerY" to 500))
            val lit = run(mapOf("centerY" to 500) + m)
            return plain.data.indices.filter { alpha(plain.data[it]) == 255 && com.spielgrund.glitchr.image.green(plain.data[it]) in 1..40 }
                .sumOf { com.spielgrund.glitchr.image.green(lit.data[it]).toLong() }
        }
        val white = mapOf("backlightColor" to 0xFFFFFF)
        assertTrue(edge(white + ("backlight" to 200)) > edge(white + ("backlight" to 100)) * 1.2, "brighter edges")
        val red = run(mapOf("light2Back" to 200, "light2BackColor" to 0xFF0000, "light2Color" to 0x0000FF)).data
        assertTrue(red.sumOf { com.spielgrund.glitchr.image.red(it).toLong() } > red.sumOf { com.spielgrund.glitchr.image.blue(it).toLong() }, "in the backlight's own color")
    }

    @Test
    fun `random rotation turns the smokelets`() {
        assertTrue(!run(mapOf("randomTurn" to 0)).data.contentEquals(run(mapOf("randomTurn" to 180)).data))
    }

    @Test
    fun `transparent leaves only the smoke`() {
        val out = run(mapOf("transparent" to 1, "centerY" to 500))
        assertEquals(0, alpha(out[5, 5]), "clear away from it")
        assertTrue(alpha(out[100, 100]) > 60, "the plume")
    }
}
