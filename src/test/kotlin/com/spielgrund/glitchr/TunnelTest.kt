package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Tunnel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class TunnelTest {
    private val src = testImage(160, 120)

    private fun run(vararg c: Pair<String, Int>) = Tunnel.apply(src, Tunnel.defaultValues(mapOf("antialias" to 0, *c)), 0L)

    @Test
    fun `flying one picture further looks the same again`() {
        for (shape in 0..1) {
            assertContentEquals(run("shape" to shape, "fly" to 0).data, run("shape" to shape, "fly" to 10000).data, "shape $shape")
            assertTrue(!run("shape" to shape, "fly" to 0).data.contentEquals(run("shape" to shape, "fly" to 3000).data), "it moves in between")
        }
    }

    @Test
    fun `round and every polygon give their own tunnel`() {
        val outs = listOf(run("shape" to 0).data) + (3..6).map { run("shape" to 1, "corners" to it).data }
        for (a in outs.indices) for (b in a + 1 until outs.size) assertTrue(!outs[a].contentEquals(outs[b]), "$a and $b")
    }

    @Test
    fun `rotation, offset, twist and repeats change it`() {
        val plain = run().data
        for (c in listOf("rotation" to 2000, "offset" to 2500, "twist" to 9000, "repeatAround" to 3, "repeatDepth" to 2, "depth" to 200)) {
            assertTrue(!run(c).data.contentEquals(plain), c.first)
        }
    }

    @Test
    fun `the far end fades into the fog color`() {
        val out = run("fade" to 100, "fogColor" to 0xFF0000)
        assertTrue((out[80, 60] shr 16 and 0xFF) > 200 && (out[80, 60] and 0xFF) < 60, "red in the middle")
    }
}
