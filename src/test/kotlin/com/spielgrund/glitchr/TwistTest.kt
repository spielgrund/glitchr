package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Twist
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwistTest {
    private val src = testImage(200, 160)

    private fun run(vararg c: Pair<String, Int>) = Twist.apply(src, Twist.defaultValues(mapOf("antialias" to 0, *c)), 0L)

    @Test
    fun `without a twist the picture stays`() = assertContentEquals(src.data, run("angle" to 0).data)

    @Test
    fun `the middle turns, beyond the radius nothing changes`() {
        val out = run("angle" to 9000, "radius" to 50)
        assertTrue(!out.data.contentEquals(src.data))
        for (y in 0 until 160) for (x in 0 until 200) {
            if (kotlin.math.hypot(x + 0.5 - 100, y + 0.5 - 80) > 51) assertEquals(src[x, y], out[x, y], "outside at $x, $y")
        }
    }

    @Test
    fun `turning the whole picture by half a turn turns it upside down`() {
        val out = run("angle" to 18000, "profile" to 3)
        // around the middle (100, 80): pixel (60, 50) comes from (140, 110)
        assertEquals(src[139, 109], out[60, 50])
    }

    @Test
    fun `every profile and ring style twists differently`() {
        val outs = (0..4).map { run("profile" to it, "radius" to 70).data } + (1..2).map { run("rings" to it, "radius" to 70).data }
        for (a in outs.indices) for (b in a + 1 until outs.size) assertTrue(!outs[a].contentEquals(outs[b]), "$a and $b")
    }
}
