package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Dolly
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class DollyTest {
    /** Stripes across, so how much a place grows shows in how far its stripe moves. */
    private val src = Pixels(120, 120, IntArray(120 * 120) { i -> if (i / 120 / 6 % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() })

    /** Only "low is near": the bottom is near, the top far. */
    private fun values(vararg changes: Pair<String, Int>) = Dolly.defaultValues(
        mapOf("depthSharp" to 0, "depthLow" to 100, "depthLight" to 0, "depthSat" to 0, "depthCenter" to 0, *changes),
    )

    @Test
    fun `nothing moves without a move`() {
        assertContentEquals(src.data, Dolly.apply(src, values("move" to 0), 0L).data)
    }

    @Test
    fun `near things grow more than far ones`() {
        val out = Dolly.apply(src, values("move" to 6000), 0L)
        // magnified more, fewer stripes fit: below (near) fewer than above (far)
        fun light(y: Int) = (out.data[y * 120 + 60] and 0xFF) > 128
        fun stripes(rows: IntRange) = rows.count { y -> light(y) != light(y + 1) }
        val far = stripes(0 until 59)
        val near = stripes(60 until 119)
        assertTrue(far < 10, "fewer stripes than before above too: $far")
        assertTrue(near < far, "near $near vs far $far")
    }

    @Test
    fun `dolly zoom keeps the focal plane`() {
        val plain = Dolly.apply(src, values("move" to 6000), 0L)
        val vertigo = Dolly.apply(src, values("move" to 6000, "vertigo" to 1, "focus" to 50), 0L)
        fun diff(p: Pixels) = (50 until 70).sumOf { y -> (0 until 120).count { x -> p.data[y * 120 + x] != src.data[y * 120 + x] } }
        // around the middle depth the dolly zoom changes less than driving in alone
        assertTrue(diff(vertigo) < diff(plain), "vertigo ${diff(vertigo)} vs ${diff(plain)}")
    }

    @Test
    fun `pulling back with a transparent edge shows nothing beyond the picture`() {
        val out = Dolly.apply(src, values("move" to -10000, "edge" to 0), 0L)
        assertTrue((out.data[0] ushr 24) < 10, "the corner comes from beyond the picture")
        assertTrue((out.data[60 * 120 + 60] ushr 24) == 255)
    }

    @Test
    fun `handheld shakes over time`() {
        fun at(t: Int) = Dolly.apply(src, values("move" to 0, "handheld" to 100, "time" to t, "shakeRoll" to 0), 0L)
        val a = at(1000)
        val b = at(1400)
        assertTrue(!a.data.contentEquals(b.data), "it moves with the time")
        assertContentEquals(a.data, at(1000).data)
        // a still camera at time 0 is not guaranteed, but no handheld is still
        assertContentEquals(src.data, Dolly.apply(src, values("move" to 0, "handheld" to 0, "time" to 1400), 0L).data)
    }

    @Test
    fun `the steady depth stays still while shaking`() {
        // "low is near": the top rows are far, the bottom rows near
        fun at(t: Int, steady: Int) = Dolly.apply(src, values("move" to 0, "handheld" to 100, "time" to t, "shakeRoll" to 0, "steady" to steady), 0L)
        fun rows(p: Pixels, r: IntRange) = r.flatMap { y -> (0 until 120).map { x -> p.data[y * 120 + x] } }
        // background steady: the top barely changes, the bottom does
        val a = at(1000, 0); val b = at(1700, 0)
        val farMoves = rows(a, 2..10).zip(rows(b, 2..10)).count { it.first != it.second }
        val nearMoves = rows(a, 108..117).zip(rows(b, 108..117)).count { it.first != it.second }
        assertTrue(nearMoves > farMoves, "background steady: near $nearMoves, far $farMoves")
        // front steady: the other way round
        val c = at(1000, 100); val d = at(1700, 100)
        val farMoves2 = rows(c, 2..10).zip(rows(d, 2..10)).count { it.first != it.second }
        val nearMoves2 = rows(c, 108..117).zip(rows(d, 108..117)).count { it.first != it.second }
        assertTrue(farMoves2 > nearMoves2, "front steady: near $nearMoves2, far $farMoves2")
    }
}
