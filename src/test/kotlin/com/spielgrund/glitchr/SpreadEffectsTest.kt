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
import kotlin.test.assertEquals
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

    @Test
    fun `curves bend the colors, or only the brightness`() {
        val e = com.spielgrund.glitchr.effects.CurvesEffect
        val src = Pixels(3, 1, intArrayOf(0xFF402010.toInt(), 0xFF808080.toInt(), 0xFF3060C0.toInt()))
        // straight curves: nothing changes
        assertContentEquals(src.data, e.apply(src, e.defaultValues(), 1L).data)
        // an S-curve: darks darker, mid gray stays
        val s = mapOf("curves" to "rgb:0,0 64,30 128,128 192,225 255,255")
        val bent = e.apply(src, e.defaultValues(texts = s), 1L)
        assertTrue(ch(bent[0, 0], 16) < 0x40, "darker")
        assertTrue(kotlin.math.abs(ch(bent[1, 0], 0) - 0x80) <= 1, "middle stays")
        // on the brightness only, the hue stays: the channels keep their ratio
        val lifted = e.apply(src, e.defaultValues(mapOf("applyTo" to 1), mapOf("curves" to "rgb:0,0 128,160 255,255")), 1L)[2, 0]
        val ratio = ch(lifted, 0).toDouble() / ch(lifted, 8)
        assertTrue(kotlin.math.abs(ratio - 0xC0 / 0x60.toDouble()) < 0.12, "hue kept: $ratio")
        assertTrue(ch(lifted, 8) > 0x60, "brighter")
        // half strength: half way
        val half = e.apply(src, e.defaultValues(mapOf("amount" to 50), s), 1L)
        assertTrue(ch(half[0, 0], 16) in ch(bent[0, 0], 16)..0x40)
    }

    @Test
    fun `fever dream eyes grow with the growth, push each other, look around and blink with the time`() {
        val fx = com.spielgrund.glitchr.effects.FeverDream
        val src = Pixels(200, 150).apply { data.fill(0xFF6688AA.toInt()) }
        fun run(values: Map<String, Int>, seed: Long = 1L, picture: Pixels = src) = fx.apply(picture, fx.defaultValues(values), seed)
        fun changed(p: Pixels, picture: Pixels = src) = p.data.indices.count { p.data[it] != picture.data[it] }
        val calm = mapOf("count" to 12, "size" to 15, "blink" to 0)
        // there from the start; growth 0 hides them, more makes them bigger
        assertEquals(0, changed(run(calm + ("growth" to 0))), "nothing at 0 %")
        val sizes = listOf(50, 100, 200).map { changed(run(calm + ("growth" to it))) }
        assertTrue(sizes[0] < sizes[1] && sizes[1] < sizes[2], "they grow: $sizes")
        // the time only moves them: they look elsewhere, the eyes stay where they are
        val one = mapOf("count" to 1, "size" to 50, "blink" to 0, "tilt" to 0)
        assertTrue(!run(one + ("time" to 0)).data.contentEquals(run(one + ("time" to 600)).data), "they look around")
        assertTrue(abs(changed(run(one + ("time" to 0))) - changed(run(one + ("time" to 600)))) < 400, "same place and size")
        // blinking shuts them now and then
        fun white(p: Pixels) = p.data.count { (it shr 16 and 0xFF) > 200 && (it shr 8 and 0xFF) > 185 }
        val times = (0..1200 step 10).map { white(run(mapOf("count" to 1, "size" to 50, "blink" to 100, "time" to it))) }
        assertTrue(times.minOrNull()!! < times.maxOrNull()!! / 3, "a blink")
        // looking at a point: the pupil follows it
        fun darkX(p: Pixels): Double {
            var sx = 0.0; var n = 0
            for (y in 0 until 150) for (x in 0 until 200) { val c = p[x, y]; if ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF) < 40) { sx += x; n++ } }
            return sx / n
        }
        val look = one + ("gaze" to 1)
        assertTrue(darkX(run(look + ("lookX" to 3000))) > darkX(run(look + ("lookX" to -3000))) + 5, "the pupil follows the point")
        assertTrue(!run(calm).data.contentEquals(run(calm, 2L).data), "rerolled")
        // only on the area, nothing beyond it, not even the sockets
        val halves = Pixels(200, 150).apply { for (y in 0 until 150) for (x in 0 until 200) data[y * 200 + x] = if (x < 100) 0xFFDDDDDD.toInt() else 0xFF222222.toInt() }
        val onArea = run(calm + mapOf("lower" to 128, "growth" to 200), picture = halves)
        assertTrue((0 until 100).sumOf { x -> (0 until 150).count { y -> onArea[x, y] != halves[x, y] } } > 2000, "on the bright half")
        assertTrue((100 until 200).all { x -> (0 until 150).all { y -> onArea[x, y] == halves[x, y] } }, "nothing on the dark half")
        // pushing: grown big on a small patch they stop where they press, piled up they don't
        val patch = Pixels(200, 150).apply { for (y in 0 until 150) for (x in 0 until 200) data[y * 200 + x] = if (x in 70 until 130 && y in 45 until 105) -1 else 0xFF222222.toInt() }
        fun crowd(push: Int) = changed(run(mapOf("count" to 8, "size" to 20, "blink" to 0, "lower" to 128, "growth" to 300, "push" to push), picture = patch), patch)
        assertTrue(crowd(0) > crowd(100), "piled up they grow on: ${crowd(0)} / ${crowd(100)}")
        // one big eye, looking straight out; where it sits: the middle of what changed
        val big = mapOf("count" to 1, "size" to 60, "blink" to 0, "tilt" to 0, "gaze" to 2, "bloodshot" to 0, "socket" to 0)
        fun middle(p: Pixels, picture: Pixels): Pair<Int, Int> {
            var sx = 0L; var sy = 0L; var n = 0
            for (y in 0 until 150) for (x in 0 until 200) if (p[x, y] != picture[x, y]) { sx += x; sy += y; n++ }
            return (sx / n).toInt() to (sy / n).toInt()
        }
        fun chroma(c: Int) = maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) - minOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
        fun light(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
        // the iris's ring, between pupil and rim
        fun ring(p: Pixels, picture: Pixels): List<Int> {
            val (cx, cy) = middle(p, picture)
            return (-34..34).filter { kotlin.math.abs(it) in 28..32 }.map { p[(cx + it).coerceIn(0, 199), cy] }
        }
        // from a grey picture, grey irises; from a red one, red ones
        val grey = Pixels(200, 150).apply { data.fill(0xFF808080.toInt()) }
        val red = Pixels(200, 150).apply { data.fill(0xFFC04040.toInt()) }
        val greyRing = ring(run(big, picture = grey), grey).sumOf { chroma(it) }
        val redRing = ring(run(big, picture = red), red).sumOf { chroma(it) }
        assertTrue(greyRing * 4 < redRing, "grey iris: $greyRing / $redRing")
        // the color strength makes a colored picture's iris more colorful
        val tinted = Pixels(200, 150).apply { data.fill(0xFF907070.toInt()) }
        assertTrue(ring(run(big + ("colorStrength" to 100), picture = tinted), tinted).sumOf { chroma(it) } > ring(run(big + ("colorStrength" to 30), picture = tinted), tinted).sumOf { chroma(it) }, "stronger color")
        // milky: light where the pupil would be; black with stars: dark in the middle, with bright stars
        val milky = run(big + ("iris" to 4))
        val (mx, my) = middle(milky, src)
        assertTrue(light(milky[mx, my]) > 300, "milky pupil: ${light(milky[mx, my])}")
        val starry = run(big + ("iris" to 5))
        val (sx, sy) = middle(starry, src)
        val inside = (-30..30).flatMap { dx -> (-10..10).map { dy -> light(starry[(sx + dx).coerceIn(0, 199), (sy + dy).coerceIn(0, 149)]) } }
        assertTrue(inside.average() < 200, "black: ${inside.average()}")
        assertTrue(inside.any { it > 300 }, "with stars: ${inside.maxOrNull()}")
    }


    @Test
    fun `the color compressor stretches red, green, blue, hue, saturation and luma`() {
        val fx = com.spielgrund.glitchr.effects.ColorCompressor
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val none = mapOf("red" to 0, "green" to 0, "blue" to 0, "blackClip" to 0, "whiteClip" to 0)
        fun run(p: Pixels, values: Map<String, Int>) = fx.apply(p, fx.defaultValues(none + values), 0L)
        // a dull picture from 60 to 180, a little warm
        val dull = Pixels(100, 1, IntArray(100) { val v = 60 + it * 120 / 99; rgb(minOf(255, v + 20), v, v) })
        // red, green, blue: each to the full range – the cast goes; only red: the others stay
        val rgbAll = run(dull, mapOf("red" to 100, "green" to 100, "blue" to 100))
        assertEquals(rgb(0, 0, 0), rgbAll.data[0])
        assertEquals(rgb(255, 255, 255), rgbAll.data[99])
        val redOnly = run(dull, mapOf("red" to 100))
        assertEquals(0, redOnly.data[0] shr 16 and 0xFF)
        assertEquals(60, redOnly.data[0] and 0xFF, "blue untouched")
        assertEquals(40, run(dull, mapOf("red" to 50))[0, 0] shr 16 and 0xFF, "half strength: 80 halfway to 0")
        // luma: black and white at the ends, the warmth kept
        val luma = run(dull, mapOf("luma" to 100))
        assertTrue((luma.data[0] shr 8 and 0xFF) < 10 && (luma.data[99] shr 8 and 0xFF) > 245, "luma stretched")
        assertTrue((luma.data[50] shr 16 and 0xFF) > (luma.data[50] and 0xFF) + 10, "the warmth stays")
        // saturation: the dullest to grey, the most colourful to full
        val sats = Pixels(3, 1, intArrayOf(rgb(140, 120, 120), rgb(160, 120, 120), rgb(180, 120, 120)))
        val sat = run(sats, mapOf("saturation" to 100))
        assertEquals(sat.data[0] shr 16 and 0xFF, sat.data[0] and 0xFF, "dullest grey")
        assertEquals(0, sat.data[2] and 0xFF, "most colourful fully saturated")
        // hue: a narrow arc (orange to yellow) spread round the wheel – its ends far from its middle
        val hues = Pixels(3, 1, intArrayOf(rgb(255, 128, 0), rgb(255, 176, 0), rgb(255, 224, 0)))
        fun hueOf(c: Int) = java.awt.Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, null)[0] * 360
        fun apart(a: Int, b: Int) = kotlin.math.abs(hueOf(a) - hueOf(b)).let { minOf(it, 360 - it) }
        val spread = run(hues, mapOf("hue" to 100))
        assertTrue(apart(spread.data[0], spread.data[1]) > 120 && apart(spread.data[2], spread.data[1]) > 120, "hues spread: ${apart(spread.data[0], spread.data[1])}")
        assertTrue(apart(run(hues, mapOf("hue" to 30)).data[0], run(hues, mapOf("hue" to 30)).data[1]) < apart(spread.data[0], spread.data[1]), "less at 30 %")
        // clipping: a single stray white pixel holds red back, unless clipped
        val stray = Pixels(1000, 1, IntArray(1000) { if (it == 0) rgb(255, 255, 255) else rgb(100 + it % 50, 100 + it % 50, 100 + it % 50) })
        assertTrue((run(stray, mapOf("red" to 100))[49, 0] shr 16 and 0xFF) < 200)
        assertEquals(255, run(stray, mapOf("red" to 100, "whiteClip" to 20))[49, 0] shr 16 and 0xFF, "0.2 % clipped")
        // gamma: the middle towards low (darker) or high (brighter), the ends stay; clipping goes up to half
        val mid = run(dull, mapOf("red" to 100))[50, 0] shr 16 and 0xFF
        val darker = run(dull, mapOf("red" to 100, "gamma" to -100))
        val brighter = run(dull, mapOf("red" to 100, "gamma" to 100))
        assertTrue((darker[50, 0] shr 16 and 0xFF) < mid - 40 && (brighter[50, 0] shr 16 and 0xFF) > mid + 40, "gamma: ${darker[50, 0] shr 16 and 0xFF} < $mid < ${brighter[50, 0] shr 16 and 0xFF}")
        assertEquals(0, darker[0, 0] shr 16 and 0xFF)
        assertEquals(255, brighter[99, 0] shr 16 and 0xFF)
        assertEquals(5000, fx.params.filterIsInstance<com.spielgrund.glitchr.effects.Param.Slider>().first { it.key == "whiteClip" }.max)
        val clippedHalf = run(dull, mapOf("red" to 100, "blackClip" to 4000, "whiteClip" to 4000))
        assertEquals(0, clippedHalf[30, 0] shr 16 and 0xFF, "40 % low clipped: black")
        assertEquals(255, clippedHalf[70, 0] shr 16 and 0xFF, "40 % high clipped: white")
        // all off: unchanged; one flat colour: unchanged
        assertContentEquals(dull.data, run(dull, emptyMap()).data)
        val flat = Pixels(4, 4).apply { data.fill(0x80406080.toInt()) }
        assertContentEquals(flat.data, fx.apply(flat, fx.defaultValues(), 0L).data)
    }

    @Test
    fun `the soft light stack lays a blurred, lifted copy over the picture`() {
        val fx = com.spielgrund.glitchr.effects.SoftLightStack
        val src = Pixels(80, 60).apply { for (y in 0 until 60) for (x in 0 until 80) data[y * 80 + x] = if ((x / 10 + y / 10) % 2 == 0) 0xFF202020.toInt() else 0xFFC0C0C0.toInt() }
        val out = fx.apply(src, fx.defaultValues(mapOf("radius" to 40)), 0L)
        fun l(c: Int) = c and 0xFF
        // over a blurred, lifted copy (mid grey and lighter), soft light opens the darks and keeps the lights near
        assertTrue(l(out[5, 5]) > l(src[5, 5]), "darks lifted: ${l(out[5, 5])}")
        assertTrue(kotlin.math.abs(l(out[15, 5]) - l(src[15, 5])) < 40, "lights near: ${l(out[15, 5])}")
        // no lift, no blur and a mid grey copy: soft light with the picture itself still adds contrast; strength 0 changes nothing
        assertContentEquals(src.data, fx.apply(src, fx.defaultValues(mapOf("amount" to 0)), 0L).data)
        // more lift: lighter
        assertTrue(l(fx.apply(src, fx.defaultValues(mapOf("lift" to 80)), 0L)[5, 5]) > l(fx.apply(src, fx.defaultValues(mapOf("lift" to 0)), 0L)[5, 5]))
        // 200 %: exactly the effect twice, as on two layers; 150 %: once and then half
        val once = fx.apply(src, fx.defaultValues(mapOf("radius" to 40)), 0L)
        assertContentEquals(fx.apply(once, fx.defaultValues(mapOf("radius" to 40)), 0L).data, fx.apply(src, fx.defaultValues(mapOf("radius" to 40, "amount" to 200)), 0L).data)
        assertContentEquals(fx.apply(once, fx.defaultValues(mapOf("radius" to 40, "amount" to 50)), 0L).data, fx.apply(src, fx.defaultValues(mapOf("radius" to 40, "amount" to 150)), 0L).data)
        // lowered lights: the light squares come out darker, the dark ones barely change
        val lowered = fx.apply(src, fx.defaultValues(mapOf("radius" to 40, "lower" to 50)), 0L)
        assertTrue(l(lowered[15, 5]) < l(out[15, 5]), "lights lowered: ${l(lowered[15, 5])} / ${l(out[15, 5])}")
    }
}
