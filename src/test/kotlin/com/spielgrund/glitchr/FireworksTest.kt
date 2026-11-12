package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Generators
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FireworksTest {
    private val gen = Generators.all.first { it.id == "fireworks" }

    private fun lit(p: com.spielgrund.glitchr.image.Pixels) = p.data.count { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) > 150 }

    @Test
    fun `every kind of burst lights up the sky`() {
        for (type in 0..7) {
            val p = gen.generate(160, 120, gen.defaultValues(mapOf("type" to type, "time" to 1800, "rate" to 300)), 4L)
            assertTrue(lit(p) > 20, "type $type: ${lit(p)}")
        }
    }

    @Test
    fun `it loops seamlessly and moves with the time`() {
        fun at(t: Int) = gen.generate(120, 90, gen.defaultValues(mapOf("time" to t, "loop" to 50)), 2L).data
        assertContentEquals(at(1300), at(6300))
        assertFalse(at(1300).contentEquals(at(1500)))
    }

    @Test
    fun `transparent leaves only the light`() {
        val p = gen.generate(120, 90, gen.defaultValues(mapOf("background" to 1, "time" to 1800, "rate" to 300)), 3L)
        assertTrue(p.data.count { (it ushr 24) < 20 } > p.data.size / 2, "mostly transparent")
        assertTrue(p.data.any { (it ushr 24) > 200 }, "the stars are opaque")
    }
}
