package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Flicker
import com.spielgrund.glitchr.effects.FrameInput
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlickerTest {
    private val src = Pixels(40, 30, IntArray(40 * 30) { 0xFF336699.toInt() })

    private fun at(frame: Int) = object : FrameInput {
        override val frame = frame
        override val frameCount = 100
        override fun at(f: Int) = src
    }

    private fun only(kind: String, vararg more: Pair<String, Int>) = Flicker.defaultValues(
        mapOf("transparent" to 0, "invert" to 0, "white" to 0, kind to 1, "chance" to 50, "vary" to 0, *more),
    )

    @Test
    fun `some frames flicker, the others stay`() {
        val v = only("invert")
        val results = (0 until 60).map { Flicker.apply(src, v, 7L, at(it)) }
        val flickered = results.count { !it.data.contentEquals(src.data) }
        assertTrue(flickered in 15..45, "about half flicker: $flickered")
        // a flickering frame is the inverted picture; the same seed flickers the same way again
        assertTrue(results.any { it.data.all { c -> c == 0xFFCC9966.toInt() } })
        assertTrue((0 until 60).all { Flicker.apply(src, v, 7L, at(it)).data.contentEquals(results[it].data) })
    }

    @Test
    fun `transparent, held for a few frames, and in pieces`() {
        val v = only("transparent", "chance" to 100, "hold" to 3)
        val p = Flicker.apply(src, v, 1L, at(4))
        assertEquals(0x00336699, p.data[0])
        // stripes: each its own – with half the chance some stay and some go
        val stripes = Flicker.apply(src, only("transparent", "area" to 1, "pieces" to 10), 3L, at(2))
        val alphas = (0 until 30 step 3).map { stripes[0, it] ushr 24 }.toSet()
        assertEquals(setOf(0, 255), alphas)
        // nothing switched on, or no chance: the picture as it is
        assertTrue(Flicker.apply(src, only("transparent", "transparent" to 0), 1L, at(0)).data.contentEquals(src.data))
        assertTrue(Flicker.apply(src, only("invert", "chance" to 0), 1L, at(0)).data.contentEquals(src.data))
    }

    @Test
    fun `flicker now flickers the frame by hand`() {
        val v = only("invert", "chance" to 0)
        assertTrue(Flicker.apply(src, v, 1L, at(3)).data.contentEquals(src.data))
        val now = Flicker.apply(src, only("invert", "chance" to 0, "manual" to 1), 1L, at(3))
        assertTrue(now.data.all { it == 0xFFCC9966.toInt() })
        // in pieces, every piece goes
        val stripes = Flicker.apply(src, only("transparent", "chance" to 0, "manual" to 1, "area" to 1), 1L, at(3))
        assertTrue(stripes.data.all { it ushr 24 == 0 })
    }
}
