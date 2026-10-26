package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Bleed
import com.spielgrund.glitchr.effects.FreezeOver
import com.spielgrund.glitchr.effects.InkFill
import com.spielgrund.glitchr.effects.Luminescence
import com.spielgrund.glitchr.effects.spreadTimes
import com.spielgrund.glitchr.effects.spreadTimesScaled
import com.spielgrund.glitchr.image.Pixels
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SpreadEffectsTest {
    private fun ch(c: Int, sh: Int) = c shr sh and 0xFF

    @Test
    fun `a front spreads nearly round and doesn't pass walls`() {
        val w = 101
        val h = 101
        val t = spreadTimes(FloatArray(w * h) { 1f }, w, h, intArrayOf(50 * w + 50))
        for ((x, y) in listOf(100 to 50, 50 to 0, 90 to 80, 70 to 95)) {
            val d = hypot(x - 50.0, y - 50.0)
            assertTrue(abs(t[y * w + x] - d) < d * 0.05, "($x, $y): ${t[y * w + x]} vs $d")
        }
        // a wall down the middle: the other side is never reached
        val wall = FloatArray(w * h) { if (it % w == 60) Float.POSITIVE_INFINITY else 1f }
        assertTrue(spreadTimes(wall, w, h, intArrayOf(50 * w + 50))[50 * w + 80].isInfinite())
        // shrunk and scaled back: close to the full one
        val big = 1500
        val scaled = spreadTimesScaled(FloatArray(big * 40) { 1f }, big, 40, intArrayOf(20 * big))
        assertTrue(abs(scaled[20 * big + 1200] - 1200) < 30, "${scaled[20 * big + 1200]}")
    }

    @Test
    fun `ink fill reveals the picture from the points, as an area or a band`() {
        val src = testImage(120, 90)
        val points = mapOf("points" to "0.2000,0.3000")
        fun run(vararg c: Pair<String, Int>) = InkFill.apply(src, InkFill.defaultValues(mapOf(*c), points), 1L)
        // nothing at first
        assertTrue(run("progress" to 0).data.all { it == 0 })
        for (style in 0..3) {
            // at the end exactly the picture, the edge gone
            assertContentEquals(src.data, run("style" to style, "progress" to 10000).data, "style $style ends with the picture")
            // partway: uncovered near the point, still transparent far away
            val half = run("style" to style, "progress" to 3000)
            assertTrue(half[24, 27] ushr 24 == 255 && half[115, 85] == 0, "style $style spreads from the point")
        }
        // the edge looks are on the picture; without them a plain reveal
        assertTrue(!run("progress" to 3000).data.contentEquals(run("progress" to 3000, "edge" to 0).data), "the ink edge shows")
        // a band: passed by at the point, gone everywhere at the end
        val band = run("reveal" to 1, "bandWidth" to 1000, "progress" to 6000)
        assertTrue(band[24, 27] == 0 && band.data.any { it ushr 24 == 255 }, "a band moving on")
        assertTrue(run("reveal" to 1, "progress" to 10000).data.all { it == 0 })
        // without points nothing shows
        assertTrue(InkFill.apply(src, InkFill.defaultValues(mapOf("progress" to 5000), mapOf("points" to "")), 1L).data.all { it == 0 })
    }

    @Test
    fun `the picture freezes over from the edges`() {
        val src = Pixels(100, 80).also { it.data.fill(0xFF805040.toInt()) }
        assertContentEquals(src.data, FreezeOver.apply(src, FreezeOver.defaultValues(mapOf("progress" to 0)), 1L).data)
        val early = FreezeOver.apply(src, FreezeOver.defaultValues(mapOf("progress" to 2500, "sparkle" to 0)), 1L)
        assertTrue(early[1, 1] != src[1, 1] && early[50, 40] == src[50, 40], "the edges first, the middle still untouched")
        // frozen: icy blue instead of brown
        val all = FreezeOver.apply(src, FreezeOver.defaultValues(mapOf("progress" to 10000, "sparkle" to 0)), 1L)
        val blueish = all.data.count { ch(it, 0) > ch(it, 16) }
        assertTrue(blueish > all.data.size * 0.9, "icy: $blueish")
        // seen from the side, the picture under the ice shifts; nothing frozen, nothing shifts
        val picture = testImage(100, 80)
        fun view(progress: Int, angle: Int) = FreezeOver.apply(picture, FreezeOver.defaultValues(mapOf("progress" to progress, "view" to angle, "parallax" to 100, "sparkle" to 0)), 1L)
        assertTrue(!view(10000, 4500).data.contentEquals(view(10000, 0).data), "parallax")
        assertContentEquals(picture.data, view(0, 4500).data)
    }

    @Test
    fun `blood gathers under the shapes and drips in the direction`() {
        // a white bar on transparency
        val src = Pixels(160, 160)
        for (y in 40..60) for (x in 30..130) src.data[y * 160 + x] = -1
        fun red(p: Pixels, x0: Int, y0: Int, x1: Int, y1: Int) =
            (y0..y1).sumOf { y -> (x0..x1).count { x -> val c = p[x, y]; (c ushr 24) > 128 && ch(c, 16) > ch(c, 8) + 40 } }
        assertContentEquals(src.data, Bleed.apply(src, Bleed.defaultValues(mapOf("progress" to 0)), 1L).data)
        val down = Bleed.apply(src, Bleed.defaultValues(mapOf("progress" to 10000, "drips" to 100)), 1L)
        assertTrue(red(down, 30, 61, 130, 159) > 200, "below the bar: ${red(down, 30, 61, 130, 159)}")
        assertTrue(red(down, 0, 0, 159, 38) == 0, "nothing above it")
        assertTrue(red(down, 30, 55, 130, 60) > 300, "gathered on its lower edge")
        // running up instead
        val up = Bleed.apply(src, Bleed.defaultValues(mapOf("progress" to 10000, "drips" to 100, "angle" to 18000)), 1L)
        assertTrue(red(up, 30, 0, 130, 39) > 200 && red(up, 0, 62, 159, 159) == 0, "upwards")
        // bigger drops: more blood below
        fun drops(size: Int) = red(Bleed.apply(src, Bleed.defaultValues(mapOf("progress" to 10000, "drips" to 100, "dropSize" to size)), 1L), 30, 61, 130, 159)
        assertTrue(drops(300) > drops(50) + 50, "drop size: ${drops(300)} vs ${drops(50)}")
        // a white bar on a dark, opaque ground: as white areas it bleeds below itself, as dark areas the ground bleeds onto its top
        val flat = Pixels(160, 160).also { it.data.fill(0xFF141420.toInt()) }
        for (y in 40..60) for (x in 30..130) flat.data[y * 160 + x] = -1
        val whiteAreas = Bleed.apply(flat, Bleed.defaultValues(mapOf("progress" to 10000, "drips" to 100, "source" to 2)), 1L)
        assertTrue(red(whiteAreas, 30, 55, 130, 60) > 300 && red(whiteAreas, 30, 40, 130, 44) < 50, "white areas gather at the bar's bottom")
        val darkAreas = Bleed.apply(flat, Bleed.defaultValues(mapOf("progress" to 10000, "drips" to 100, "source" to 1)), 1L)
        assertTrue(red(darkAreas, 30, 36, 130, 44) > 200, "dark areas: the ground above it bleeds onto it")
        // only the blood: the bar itself gone where there's no blood
        val only = Bleed.apply(src, Bleed.defaultValues(mapOf("progress" to 10000, "onlyBlood" to 1, "coat" to 0)), 1L)
        assertTrue(only[80, 42] ushr 24 == 0)
    }

    @Test
    fun `light gathers in the blobs and a pulse runs through them`() {
        // a bright disc on a grey ground
        val src = Pixels(120, 100).also { p ->
            for (y in 0 until 100) for (x in 0 until 120) p.data[y * 120 + x] = if (hypot(x - 60.0, y - 50.0) < 30) -1 else 0xFF606060.toInt()
        }
        fun lum(c: Int) = ch(c, 16) + ch(c, 8) + ch(c, 0)
        val calm = Luminescence.apply(src, Luminescence.defaultValues(mapOf("sparkles" to 0, "glow" to 0)), 1L)
        assertTrue(lum(calm[60, 50]) > lum(calm[5, 5]) + 100, "the blob glows, the ground goes dark")
        assertTrue(lum(calm[60, 50]) > lum(calm[60, 78]), "brightest in its middle")
        // the pulse: a ring of light partway out
        val pulse = Luminescence.apply(src, Luminescence.defaultValues(mapOf("sparkles" to 0, "glow" to 0, "pulse" to 4000)), 1L)
        assertTrue(lum(pulse[60, 50 + 12]) > lum(calm[60, 50 + 12]) + 60, "the wave passes")
        // black light: a violet ground
        val uv = Luminescence.apply(src, Luminescence.defaultValues(mapOf("mode" to 1, "sparkles" to 0, "glow" to 0)), 1L)
        assertTrue(ch(uv[5, 5], 0) > ch(uv[5, 5], 8) + 10 && ch(uv[5, 5], 16) > ch(uv[5, 5], 8))
    }

    @Test
    fun `water caustics draw a net of light that moves with the time`() {
        val g = com.spielgrund.glitchr.effects.Generators.byId("caustics")!!
        fun run(vararg c: Pair<String, Int>) = g.generate(200, 150, com.spielgrund.glitchr.effects.Values(g.defaults().apply { putAll(mapOf(*c)) }, g.textDefaults()), 1L)
        fun lum(c: Int) = ch(c, 16) + ch(c, 8) + ch(c, 0)
        val net = run()
        val sorted = net.data.map { lum(it) }.sorted()
        // bright lines over darker water
        assertTrue(sorted[sorted.size * 98 / 100] > sorted[sorted.size / 2] + 150, "a net: ${sorted[sorted.size / 2]} .. ${sorted[sorted.size * 98 / 100]}")
        assertTrue(net.data.none { lum(it) < 20 }, "no black holes")
        assertTrue(!run("time" to 300).data.contentEquals(net.data), "the time moves it")
        // a seamless loop: at the loop length exactly the water at 0, in between it moves
        assertContentEquals(run("loop" to 1, "time" to 0).data, run("loop" to 1, "time" to 2000).data)
        assertTrue(!run("loop" to 1, "time" to 700).data.contentEquals(run("loop" to 1, "time" to 0).data))
        // every kind of waves draws its own net
        val kinds = (0..3).map { run("waves" to it).data }
        for (a in 0..3) for (b in a + 1..3) assertTrue(!kinds[a].contentEquals(kinds[b]), "waves $a and $b differ")
        // a focus varying across the picture changes the net
        assertTrue(!run("focusVariation" to 100).data.contentEquals(net.data), "focus variation")
        // dispersion: colored fringes
        fun fringes(p: Pixels) = p.data.count { it ushr 24 > 100 && abs(ch(it, 16) - ch(it, 0)) > 40 }
        assertTrue(fringes(run("dispersion" to 100, "transparent" to 1)) > fringes(run("transparent" to 1)) + 50)
        // only the light: the dark water transparent
        assertTrue(run("transparent" to 1).data.any { it ushr 24 < 60 })
    }

    @Test
    fun `a galaxy skin is fixed in the picture, not on the shapes, and slides against the camera`() {
        val g = com.spielgrund.glitchr.effects.GalaxySkin
        fun square(x0: Int) = Pixels(200, 120).also { p -> for (y in 20..100) for (x in x0 until x0 + 80) p.data[y * 200 + x] = 0xFF808080.toInt() }
        val a = g.apply(square(20), g.defaultValues(mapOf("rim" to 0)), 1L)
        val b = g.apply(square(60), g.defaultValues(mapOf("rim" to 0)), 1L)
        // where both shapes cover the picture, the space is the same: it stays put while the shape moves
        for (y in 30..90) for (x in 70..90) assertTrue(a[x, y] == b[x, y], "screen-fixed at ($x, $y)")
        // outside the shapes nothing changes
        assertTrue(a[150, 60] == 0 && a[5, 5] == 0)
        // the camera moves it
        assertTrue(!g.apply(square(20), g.defaultValues(mapOf("panX" to 800)), 1L).data.contentEquals(g.apply(square(20), g.defaultValues(), 1L).data))
        // the rim glows along the edges
        val rim = g.apply(square(20), g.defaultValues(mapOf("rim" to 300, "nebula" to 0, "stars" to 0)), 1L)
        assertTrue(ch(rim[21, 60], 0) > ch(rim[60, 60], 0) + 40, "rim")
    }
}
