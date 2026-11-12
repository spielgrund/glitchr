package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Generators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolarEclipseTest {
    private val g = Generators.byId("eclipse")!!
    private fun run(vararg v: Pair<String, Int>) = g.generate(200, 200, g.defaultValues(mapOf("stars" to 0, *v)), 1L)
    private fun lum(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3

    @Test
    fun `totality - a dark moon in a bright corona that fades out`() {
        val p = run()
        // the sun's radius is 16 % of 200 = 32 px: the moon dark, the corona just outside it bright, far out dark
        assertTrue(lum(p[100, 100]) < 20, "dark moon")
        assertTrue(lum(p[100 + 36, 100]) > 150 || lum(p[100, 100 - 36]) > 150, "bright inner corona")
        assertTrue(lum(p[3, 3]) < lum(p[100 + 40, 100]), "fades out")
        // no corona: only the moon on black
        assertTrue(lum(run("corona" to 0, "prominences" to 0, "glare" to 0)[100 + 40, 100]) < 10)
    }

    @Test
    fun `the moments - diamond ring flares, ring of fire, partial drowns the corona`() {
        val total = run()
        val diamond = run("moment" to 1)
        // the sliver lies up and to the right: brighter there than in totality
        assertTrue(lum(diamond[100 + 22, 100 - 22]) > lum(total[100 + 22, 100 - 22]))
        // ring of fire: a bright ring at the sun's edge, the moon inside dark
        val ring = run("moment" to 2)
        assertTrue((ring[100 + 31, 100] shr 16 and 0xFF) > 220 && lum(ring[100, 100]) < 80, "ring")
        // partial: the bare sun is bright, the corona drowned
        val partial = run("moment" to 3)
        assertTrue(lum(partial[100 - 25, 100]) > 230, "bare sun")
        // custom: the moon moved far away shows the whole sun
        val away = run("moment" to 5, "moonX" to 3000)
        assertTrue(lum(away[100, 100]) > 230)
        // transparent: only the faint glow far out, the moon opaque
        val clear = run("transparent" to 1)
        assertTrue(clear[2, 2] ushr 24 < 10 && clear[100, 100] ushr 24 == 255)
    }

    @Test
    fun `the diamond sits on the sun's edge`() {
        // the moon moved down-left: the flare at the sun's edge up-right (32 px radius, 45 degrees: 22.6 px each way)
        val p = run("moment" to 1, "corona" to 0, "prominences" to 0)
        val onEdge = lum(p[100 + 23, 100 - 23])
        assertTrue(onEdge > 240, "flare on the edge: $onEdge")
        assertTrue(lum(p[100 + 12, 100 - 12]) < onEdge)
    }

    @Test
    fun `the dark sun burns in fire and drops a beam to the ground`() {
        val p = run("moment" to 4, "corona" to 0)
        fun red(c: Int) = c shr 16 and 0xFF
        fun blue(c: Int) = c and 0xFF
        // a fiery ring hugging the moon
        val ring = p[100 + 33, 100]
        assertTrue(red(ring) > 200 && red(ring) > blue(ring) + 40, "fiery ring")
        // the beam below the moon, down to the ground at 78 % (y = 156); beside it dark
        for (y in listOf(140, 150)) assertTrue(lum(p[100, y]) > 150 && lum(p[100, y]) > lum(p[80, y]) * 2, "beam at $y")
        assertTrue(lum(p[100, 190]) < lum(p[100, 150]), "ends at the ground")
        // half fallen: the beam reaches halfway down (from the moon's edge at 133 to the ground at 156), below it dark
        val half = run("moment" to 4, "corona" to 0, "beamReach" to 50)
        assertTrue(lum(half[100, 138]) > 150 && lum(half[100, 172]) < 40, "half fallen: ${lum(half[100, 138])}, ${lum(half[100, 172])}")
        // no beam: dark below
        assertTrue(lum(run("moment" to 4, "corona" to 0, "beam" to 0, "beamGlow" to 0)[100, 150]) < 30)
    }

    @Test
    fun `haze glows around the edge, the color fringe splits red and blue`() {
        val plain = run("haze" to 0)
        val hazy = run("haze" to 200)
        // the haze brightens the dark sky around the sun, the moon's middle stays dark
        assertTrue(lum(hazy[100 + 55, 100]) > lum(plain[100 + 55, 100]) + 10, "haze")
        assertTrue(lum(hazy[100, 100]) < 20, "moon stays dark")
        // the fringe: at the moon's edge red and blue no longer go together
        val fringed = run("haze" to 0, "fringe" to 1500)
        val c = fringed[100 + 33, 100]
        assertTrue(kotlin.math.abs((c shr 16 and 0xFF) - (c and 0xFF)) > 40, "fringe: ${Integer.toHexString(c)}")
        val p = plain[100 + 33, 100]
        assertTrue(kotlin.math.abs((p shr 16 and 0xFF) - (p and 0xFF)) < 30, "no fringe")
    }

    @Test
    fun `the ring of fire burns orange, with flames licking out and the sky glowing red`() {
        val ring = run("moment" to 2)
        fun red(c: Int) = c shr 16 and 0xFF
        fun blue(c: Int) = c and 0xFF
        // the ring (the sun between the moon at 0.93 and its edge at 1, 30-32 px out) glows golden-orange
        // white-hot at the ring, orange in the flames just outside it
        assertTrue(red(ring[100 + 31, 100]) > 220, "bright ring")
        val c = (34..40).map { ring[100 + it, 100] }.maxBy { red(it) - blue(it) }
        assertTrue(red(c) > 180 && red(c) > blue(c) + 60, "orange flames: ${Integer.toHexString(c)}")
        // flames: much more is lit just outside the ring than without them
        fun lit(p: com.spielgrund.glitchr.image.Pixels) = (0 until 200).sumOf { y -> (0 until 200).count { x -> val d = Math.hypot(x + 0.5 - 100, y + 0.5 - 100); d > 34 && d < 42 && red(p[x, y]) > 200 } }
        assertTrue(lit(ring) > lit(run("moment" to 2, "flames" to 0)) + 50, "flames")
        // the sky glows red far out, the moon a dark red
        assertTrue(red(ring[180, 100]) > 30 && red(ring[180, 100]) > blue(ring[180, 100]) + 20, "red sky: ${Integer.toHexString(ring[180, 100])}")
        assertTrue(lum(ring[100, 100]) < 40 && red(ring[100, 100]) > blue(ring[100, 100]), "dark red moon")
    }

    @Test
    fun `antialiasing smooths the moon's edge, prominences go up to 300 %`() {
        val hard = run("antialias" to 0, "corona" to 0, "haze" to 0, "prominences" to 0, "glare" to 0, "earthshine" to 0, "transparent" to 1)
        val smooth = run("corona" to 0, "haze" to 0, "prominences" to 0, "glare" to 0, "earthshine" to 0, "transparent" to 1)
        // along the moon's edge more pixels lie in between (partly covered) when rendered finer
        fun between(p: com.spielgrund.glitchr.image.Pixels) = p.data.count { (it ushr 24) in 20..235 }
        assertTrue(between(smooth) > between(hard), "${between(smooth)} vs ${between(hard)}")
        // more prominences: more lit just outside the moon
        fun lit(p: com.spielgrund.glitchr.image.Pixels) = (0 until 200).sumOf { y -> (0 until 200).count { x -> val d = Math.hypot(x + 0.5 - 100, y + 0.5 - 100); d > 33.5 && d < 40 && lum(p[x, y]) > 200 } }
        val base = mapOf("corona" to 0, "haze" to 0)
        assertTrue(lit(run(*(base + ("prominences" to 300)).toList().toTypedArray())) > lit(run(*(base + ("prominences" to 50)).toList().toTypedArray())), "more prominences")
    }

    @Test
    fun `stars stay with antialiasing, the arches wiggle with the time`() {
        // counted away from the sun (its edge changes with the antialiasing)
        fun stars(aa: Int): Int {
            val p = g.generate(200, 200, g.defaultValues(mapOf("stars" to 100, "antialias" to aa, "corona" to 0, "haze" to 0, "glare" to 0)), 1L)
            return (0 until 200).sumOf { y -> (0 until 200).count { x -> Math.hypot(x - 100.0, y - 100.0) > 50 && lum(p[x, y]) > 60 } }
        }
        val plain = stars(0)
        assertTrue(plain > 5 && stars(3) == plain, "stars ${stars(3)} vs $plain")
        // the arches (over 100 % prominences) move with the time
        fun at(t: Int) = run("prominences" to 300, "corona" to 0, "time" to t, "antialias" to 0)
        assertTrue(!at(0).data.contentEquals(at(2000).data))
    }
}
