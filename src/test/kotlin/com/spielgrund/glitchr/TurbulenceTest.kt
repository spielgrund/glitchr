package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Turbulence
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurbulenceTest {
    private val bright = argb(255, 250, 220, 190)

    /** Grey stripes with a bright spot in the middle of 160 × 120. */
    private val stripes = Pixels(160, 120, IntArray(160 * 120) { i ->
        val x = i % 160
        when {
            hypot(x + 0.5 - 80, i / 160 + 0.5 - 60) < 8 -> bright
            (x / 4) % 2 == 0 -> argb(255, 60, 60, 60)
            else -> argb(255, 120, 120, 120)
        }
    })

    @Test
    fun `the whole picture swirls, reproducibly`() {
        val v = Turbulence.defaultValues(mapOf("swirlSteps" to 30, "swirlSize" to 30, "film" to 0))
        val out = Turbulence.apply(stripes, v, 3L)
        assertContentEquals(out.data, Turbulence.apply(stripes, v, 3L).data)
        val moved = (0 until 160 * 120).count { out.data[it] != stripes.data[it] }
        assertTrue(moved > 5000, "moved $moved")
    }

    @Test
    fun `with a threshold range only that area flows`() {
        val v = Turbulence.defaultValues(mapOf("region" to 1, "soft" to 20, "swirlSteps" to 30, "swirlSize" to 30, "film" to 0))
        val out = Turbulence.apply(stripes, v, 3L)
        assertTrue((0 until 160 * 120).count { out.data[it] != stripes.data[it] } > 100)
        assertEquals(stripes[2, 2], out[2, 2])
        val mask = Turbulence.apply(stripes, Turbulence.defaultValues(mapOf("region" to 1, "soft" to 20, "showMask" to 1)), 3L)
        assertEquals(-1, mask[80, 60])
        assertEquals(0xFF000000.toInt(), mask[2, 2])
    }

    @Test
    fun `anti-aliasing smooths the fine streaks`() {
        fun roughness(aa: Int): Long {
            val p = Turbulence.apply(stripes, Turbulence.defaultValues(mapOf("antialias" to aa, "swirlSize" to 30)), 3L)
            var sum = 0L
            for (y in 0 until 120) for (x in 0 until 159) sum += kotlin.math.abs(p[x + 1, y] and 0xFF - (p[x, y] and 0xFF))
            return sum
        }
        assertTrue(roughness(2) < roughness(0), "smooth ${roughness(2)}, hard ${roughness(0)}")
    }
}
