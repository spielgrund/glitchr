package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.BlackHole
import com.spielgrund.glitchr.effects.Generators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlackHoleTest {
    private val src = testImage(200, 160)

    private fun run(vararg c: Pair<String, Int>) = BlackHole.apply(src, BlackHole.defaultValues(mapOf("size" to 30, *c)), 1L)

    @Test
    fun `the shadow is black and the picture is bent around it`() {
        val out = run("style" to 4)
        assertEquals(0xFF000000.toInt(), out[100, 80], "black in the middle")
        // close outside the shadow the picture comes from elsewhere (lensed), far away it is as it was
        assertTrue(out[100, 80 - 45] != src[100, 80 - 45], "bent near the hole")
    }

    @Test
    fun `every style looks different`() {
        val outs = (0..4).map { run("style" to it).data }
        for (a in outs.indices) for (b in a + 1 until outs.size) assertTrue(!outs[a].contentEquals(outs[b]), "style $a and $b")
    }

    @Test
    fun `the disk lights up around the hole and turns with the orbit`() {
        val dark = run("style" to 0, "background" to 1, "brightness" to 0, "glow" to 0)
        val lit = run("style" to 0, "background" to 1)
        val brighter = lit.data.indices.count { (lit.data[it] and 0xFF) > (dark.data[it] and 0xFF) + 20 }
        assertTrue(brighter > 500, "disk light: $brighter")
        assertTrue(!run("style" to 0, "orbit" to 0).data.contentEquals(run("style" to 0, "orbit" to 9000).data))
    }

    @Test
    fun `the generator draws it among stars`() {
        val g = Generators.byId("blackhole")!!
        val out = g.generate(200, 150, g.defaultValues(mapOf("size" to 25)), 2L)
        // the middle stays dark (only a breath of the glow reaches into it)
        assertTrue((out[100, 75] shr 16 and 0xFF) < 40, "dark in the middle")
        assertTrue(out.data.toSet().size > 50)
    }
}
