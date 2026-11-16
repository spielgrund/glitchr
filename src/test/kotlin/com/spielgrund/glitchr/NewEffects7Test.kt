package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Flag
import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.effects.MonsterWave
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewEffects7Test {
    private val stripes = Pixels(120, 80, IntArray(9600) { i -> if (((i % 120) / 10 + (i / 120) / 10) % 2 == 0) 0xFFFF4020.toInt() else 0xFF2040FF.toInt() })

    @Test
    fun `the nebula glows in the middle and is dark at the edge`() {
        val g = Generators.all.first { it.id == "nebula" }
        val base = mapOf("stars" to 0, "bigStars" to 0, "quality" to 0)
        val p = g.generate(120, 80, g.defaultValues(base), 3L)
        fun lum(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
        val middle = (30 until 50).sumOf { y -> (45 until 75).sumOf { x -> lum(p.data[y * 120 + x]) } } / 600
        val corner = (0 until 10).sumOf { y -> (0 until 10).sumOf { x -> lum(p.data[y * 120 + x]) } } / 100
        assertTrue(middle > corner + 30, "middle $middle, corner $corner")
        // the time lets it drift, the camera turns it
        assertFalse(p.data.contentEquals(g.generate(120, 80, g.defaultValues(base + mapOf("time" to 20000)), 3L).data))
        assertFalse(p.data.contentEquals(g.generate(120, 80, g.defaultValues(base + mapOf("yaw" to 4000)), 3L).data))
    }

    @Test
    fun `the flag is held at the pole and waves at the free end`() {
        fun at(t: Int) = Flag.apply(stripes, Flag.defaultValues(mapOf("time" to t, "shading" to 0, "highlight" to 0, "sag" to 0, "pull" to 0)), 1L)
        val a = at(0); val b = at(700)
        // at the pole (left) the picture stays; at the free end it moves with the time
        for (y in listOf(25, 35, 45, 55)) assertEquals(stripes.data[y * 120 + 4], a.data[y * 120 + 4])
        val freeChanged = (0 until 80).count { y -> a.data[y * 120 + 115] != b.data[y * 120 + 115] }
        assertTrue(freeChanged > 5, "free end moves: $freeChanged")
    }

    @Test
    fun `the monster wave rises above the line and leaves the rest`() {
        val out = MonsterWave.apply(stripes, MonsterWave.defaultValues(mapOf("lineY" to 2500)), 0L)
        // below the line (row 50) nothing changes
        for (y in 51 until 80) for (x in 0 until 120) assertEquals(stripes.data[y * 120 + x], out.data[y * 120 + x])
        // above it the wave: most of it changed
        val above = (0 until 45).sumOf { y -> (0 until 120).count { x -> out.data[y * 120 + x] != stripes.data[y * 120 + x] } }
        assertTrue(above > 45 * 120 / 2, "the wave above the line: $above")
        // the flow moves the picture up the wave
        assertFalse(out.data.contentEquals(MonsterWave.apply(stripes, MonsterWave.defaultValues(mapOf("lineY" to 2500, "flow" to 300)), 0L).data))
    }

    @Test
    fun `the flag frays into strips at its free end`() {
        val whole = Flag.apply(stripes, Flag.defaultValues(mapOf("time" to 500, "edge" to 0)), 1L)
        val torn = Flag.apply(stripes, Flag.defaultValues(mapOf("time" to 500, "edge" to 0, "tatters" to 60)), 1L)
        fun holes(p: Pixels, x0: Int, x1: Int) = (10 until 70).sumOf { y -> (x0 until x1).count { x -> p.data[y * 120 + x] ushr 24 == 0 } }
        assertTrue(holes(torn, 85, 112) > holes(whole, 85, 112) + 100, "torn towards the end: ${holes(torn, 85, 112)} vs ${holes(whole, 85, 112)}")
        assertEquals(holes(whole, 0, 40), holes(torn, 0, 40), "whole near the pole")
    }

    @Test
    fun `the flat sphere swells the picture inside its outline only`() {
        val v = com.spielgrund.glitchr.effects.FlatSphere.defaultValues(mapOf("size" to 60, "reach" to 30, "rimShade" to 0))
        val out = com.spielgrund.glitchr.effects.FlatSphere.apply(stripes, v, 0L)
        // the ball: 72 px wide at the top middle, reaching 24 px down – below it and beside it nothing changes
        for (y in 30 until 80) for (x in 0 until 120) assertEquals(stripes.data[y * 120 + x], out.data[y * 120 + x])
        val inside = (0 until 20).sumOf { y -> (45 until 75).count { x -> out.data[y * 120 + x] != stripes.data[y * 120 + x] } }
        assertTrue(inside > 50, "swollen inside: $inside")
    }

    @Test
    fun `the nebula sky turns with the camera`() {
        // only the stars: the gas far away, out of the picture
        val g = Generators.all.first { it.id == "nebula" }
        val base = mapOf("size" to 10, "quality" to 0, "haze" to 0, "innerStars" to 0, "twinkle" to 0)
        val a = g.generate(160, 100, g.defaultValues(base), 2L)
        val b = g.generate(160, 100, g.defaultValues(base + mapOf("yaw" to 1500)), 2L)
        fun lit(p: Pixels) = p.data.count { (it shr 8 and 0xFF) > 120 }
        assertTrue(lit(a) > 5 && lit(b) > 5, "stars: ${lit(a)}, ${lit(b)}")
        assertFalse(a.data.contentEquals(b.data), "the sky moved")
    }

    @Test
    fun `the flat sphere can end in a straight line and bulge the other way`() {
        val fs = com.spielgrund.glitchr.effects.FlatSphere
        val flat = fs.apply(stripes, fs.defaultValues(mapOf("flatten" to 100, "reach" to 30, "rimShade" to 0)), 0L)
        // a straight edge at 24 px across the whole width: below unchanged, above changed right out to the sides
        for (y in 26 until 80) for (x in 0 until 120) assertEquals(stripes.data[y * 120 + x], flat.data[y * 120 + x])
        val sides = (0 until 20).sumOf { y -> (0 until 15).count { x -> flat.data[y * 120 + x] != stripes.data[y * 120 + x] } }
        assertTrue(sides > 30, "changed at the side too: $sides")
        // the other way round gives another picture
        val reverse = fs.apply(stripes, fs.defaultValues(mapOf("flatten" to 100, "reach" to 30, "rimShade" to 0, "bulge" to -150)), 0L)
        assertFalse(reverse.data.contentEquals(flat.data))
    }

    @Test
    fun `the nebula can be seen from straight above and below`() {
        val g = Generators.all.first { it.id == "nebula" }
        fun lum(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
        val level = g.generate(120, 80, g.defaultValues(mapOf("stars" to 0, "bigStars" to 0, "quality" to 0, "pitch" to 0)), 3L)
        for (pitch in listOf(9000, -9000, 8999)) {
            val p = g.generate(120, 80, g.defaultValues(mapOf("stars" to 0, "bigStars" to 0, "quality" to 0, "pitch" to pitch)), 3L)
            // the nebula still in the middle, about as bright as seen from the side
            val middle = (30 until 50).sumOf { y -> (45 until 75).sumOf { x -> lum(p.data[y * 120 + x]) } } / 600
            val levelMiddle = (30 until 50).sumOf { y -> (45 until 75).sumOf { x -> lum(level.data[y * 120 + x]) } } / 600
            assertTrue(middle > levelMiddle / 3, "tilt $pitch: middle $middle vs level $levelMiddle")
        }
        // 90° and just under it look almost the same – no jump
        val a = g.generate(120, 80, g.defaultValues(mapOf("stars" to 0, "bigStars" to 0, "quality" to 0, "pitch" to 9000)), 3L)
        val b = g.generate(120, 80, g.defaultValues(mapOf("stars" to 0, "bigStars" to 0, "quality" to 0, "pitch" to 8990)), 3L)
        val diff = a.data.indices.sumOf { kotlin.math.abs(lum(a.data[it]) - lum(b.data[it])).toLong() } / a.data.size
        assertTrue(diff < 20, "no jump at 90°: $diff")
    }
}
