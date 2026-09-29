package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Hologram
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class HologramTest {
    private val src = testImage(200, 140)

    /** Nothing of the foil: the picture must come through unchanged. */
    private val none = arrayOf("strength" to 0, "gloss" to 0, "edges" to 0, "parallax" to 0, "smooth" to 0, "glow" to 0, "tonemap" to 0, "sparkle" to 0)

    private fun run(vararg c: Pair<String, Int>) = Hologram.apply(src, Hologram.defaultValues(mapOf(*c)), 7L)

    @Test
    fun `without foil, lights and parallax the picture stays`() {
        assertContentEquals(src.data, run(*none).data)
    }

    @Test
    fun `turning the card changes the foil`() {
        assertTrue(!run("rotation" to -30).data.contentEquals(run("rotation" to 30).data))
    }

    @Test
    fun `every style gives its own colors`() {
        val outs = (0..3).map { run("style" to it).data }
        for (a in 0..3) for (b in a + 1..3) assertTrue(!outs[a].contentEquals(outs[b]), "Stil $a und $b")
    }

    @Test
    fun `parallax moves the areas against each other when turned`() {
        val moved = run(*none, "parallax" to 100, "rotation" to 60)
        assertTrue(!moved.data.contentEquals(src.data), "Parallaxe")
        // not turned, nothing moves
        assertContentEquals(src.data, run(*none, "parallax" to 100, "rotation" to 0).data)
    }

    @Test
    fun `parallax never pulls in other areas`() {
        // two flat areas: red left, blue right
        val halves = com.spielgrund.glitchr.image.Pixels(200, 100).also { p ->
            for (y in 0 until 100) for (x in 0 until 200) p.data[y * 200 + x] = if (x < 100) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
        for (edge in 0..1) {
            val out = Hologram.apply(halves, Hologram.defaultValues(mapOf(*none, "parallax" to 200, "rotation" to 90, "parallaxEdge" to edge)), 7L)
            assertTrue((0 until 100).all { y -> (0 until 95).all { x -> out[x, y] == 0xFFFF0000.toInt() } }, "Rand $edge: links nur Rot")
            assertTrue((0 until 100).all { y -> (105 until 200).all { x -> out[x, y] == 0xFF0000FF.toInt() } }, "Rand $edge: rechts nur Blau")
        }
    }

    @Test
    fun `smoothing rounds the areas and keeps the picture sharp`() {
        // without foil the smoothed areas leave the picture exactly as it was, nothing is blurred
        assertContentEquals(src.data, run(*none, "smooth" to 30).data)
        // with bright edges drawn, rounder areas have fewer edge pixels
        val rough = run(*none, "edges" to 100, "smooth" to 0)
        val round = run(*none, "edges" to 100, "smooth" to 20)
        fun changed(p: com.spielgrund.glitchr.image.Pixels) = p.data.indices.count { p.data[it] != src.data[it] }
        assertTrue(changed(round) < changed(rough), "glatter: ${changed(round)} < ${changed(rough)}")
    }

    @Test
    fun `filmic tonemapping keeps the lights off white and steers the shadows`() {
        val white = com.spielgrund.glitchr.image.Pixels(40, 40).also { it.data.fill(-1) }
        fun tone(vararg c: Pair<String, Int>) = Hologram.apply(white, Hologram.defaultValues(mapOf(*none, "tonemap" to 1, *c)), 7L)
        assertTrue((tone("highlights" to 100)[20, 20] and 0xFF) < 250, "Lichter laufen weich aus")
        assertTrue((tone("highlights" to 0)[20, 20] and 0xFF) == 255, "ohne Schulter bleibt Weiss")
        // above 100 % the lights get more contrast: bright gets brighter, the upper midtones darker
        fun level(c: Int, h: Int) = Hologram.apply(
            com.spielgrund.glitchr.image.Pixels(20, 20).also { it.data.fill(c) },
            Hologram.defaultValues(mapOf(*none, "tonemap" to 1, "highlights" to h)), 7L,
        )[10, 10] and 0xFF
        val bright = 0xFFE0E0E0.toInt()
        val mid = 0xFF9A9A9A.toInt()
        assertTrue(level(bright, 300) > level(bright, 100), "hell heller: ${level(bright, 300)} > ${level(bright, 100)}")
        assertTrue(level(bright, 300) - level(mid, 300) > level(bright, 100) - level(mid, 100) + 20, "mehr Kontrast im Licht")
        val grey = com.spielgrund.glitchr.image.Pixels(40, 40).also { it.data.fill(0xFF303030.toInt()) }
        fun shade(s: Int) = Hologram.apply(grey, Hologram.defaultValues(mapOf(*none, "tonemap" to 1, "highlights" to 0, "shadows" to s)), 7L)[20, 20] and 0xFF
        assertTrue(shade(-100) < shade(0) && shade(0) < shade(100), "Schatten: ${shade(-100)} < ${shade(0)} < ${shade(100)}")
        fun exposed(e: Int) = Hologram.apply(grey, Hologram.defaultValues(mapOf(*none, "tonemap" to 1, "exposure" to e)), 7L)[20, 20] and 0xFF
        assertTrue(exposed(100) > exposed(0) && exposed(-100) < exposed(0))
    }

    @Test
    fun `diamond sparkles flash with the turn and have a size`() {
        fun sparkles(vararg c: Pair<String, Int>) = run(*none, "style" to 2, "sparkle" to 100, *c)
        val a = sparkles("rotation" to 10)
        val b = sparkles("rotation" to 40)
        assertTrue(!a.data.contentEquals(src.data), "Funken sichtbar")
        assertTrue(!a.data.contentEquals(b.data), "beim Drehen andere Funken")
        fun lit(p: com.spielgrund.glitchr.image.Pixels) = p.data.indices.count { p.data[it] != src.data[it] }
        assertTrue(lit(sparkles("sparkleSize" to 20)) > lit(sparkles("sparkleSize" to 3)), "grössere Funken")
    }

    @Test
    fun `every band shape gives its own foil`() {
        val outs = (0..7).map { run("bandShape" to it).data }
        for (a in 0..7) for (b in a + 1..7) assertTrue(!outs[a].contentEquals(outs[b]), "Streifenform $a und $b")
    }

    @Test
    fun `band shapes can be repeated as tiles`() {
        // tiny square tiles: neighbouring tiles look the same, so the foil repeats every tile
        val grey = com.spielgrund.glitchr.image.Pixels(120, 120).also { it.data.fill(0xFF808080.toInt()) }
        // without tilt, so the tiles line up with the picture's rows
        val out = Hologram.apply(grey, Hologram.defaultValues(mapOf(*none, "strength" to 100, "bandShape" to 3, "tile" to 10, "tilt" to 0)), 7L)
        val whole = Hologram.apply(grey, Hologram.defaultValues(mapOf(*none, "strength" to 100, "bandShape" to 3, "tile" to 0, "tonemap" to 0)), 7L)
        assertTrue(!out.data.contentEquals(whole.data))
        // one tile further the foil looks much more alike than half a tile further
        // (the bands also wave slowly, so the tiles are not exactly alike)
        fun difference(step: Int) = (0 until 100).sumOf { x ->
            (0..16 step 8).sumOf { s -> kotlin.math.abs((out[x, 60] shr s and 0xFF) - (out[x + step, 60] shr s and 0xFF)) }
        }
        assertTrue(difference(10) * 2 < difference(5), "Kacheln wiederholen sich: ${difference(10)} gegen ${difference(5)}")
    }

    @Test
    fun `tilted areas turn their stripes`() {
        // with tilt the stripes of different areas run in different directions
        assertTrue(!run(*none, "strength" to 100, "tilt" to 0).data.contentEquals(run(*none, "strength" to 100, "tilt" to 100).data))
        // no sparkles unless asked for
        assertContentEquals(run(*none, "strength" to 100).data, run(*none, "strength" to 100, "sparkle" to 0).data)
    }

    @Test
    fun `sparkles work in every style`() {
        for (style in 0..3) {
            val plain = run(*none, "style" to style, "sparkle" to 0)
            val sparkling = run(*none, "style" to style, "sparkle" to 100)
            assertTrue(!sparkling.data.contentEquals(plain.data), "Stil $style funkelt")
        }
    }

    @Test
    fun `every pattern is printed into the areas`() {
        val plain = run(*none, "glow" to 0)
        for (p in 1..6) {
            val out = run(*none, "glow" to 0, "pattern" to p, "patternDensity" to 100, "patternSize" to 30)
            val changed = out.data.indices.count { out.data[it] != plain.data[it] }
            assertTrue(changed in 300 until out.data.size / 2, "Muster $p: $changed")
        }
    }

    @Test
    fun `the lights glow into their surroundings`() {
        val sharp = run("glow" to 0, "edges" to 100)
        val glowing = run("glow" to 100, "edges" to 100)
        val brighter = sharp.data.indices.count { (glowing.data[it] shr 8 and 0xFF) > (sharp.data[it] shr 8 and 0xFF) + 5 }
        assertTrue(brighter > 1000, "Glow: $brighter")
    }
}
