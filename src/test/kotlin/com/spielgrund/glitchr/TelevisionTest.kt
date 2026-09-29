package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Television
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class TelevisionTest {
    private val src = testImage(240, 160)

    /** Everything switched off: only the tape's color conversion is left. */
    private val off = mapOf(
        "curvature" to 0, "corner" to 0, "vignette" to 0, "scanlines" to 0, "mask" to 0, "aberration" to 0,
        "saturation" to 100, "noise" to 0, "blur" to 0, "chromaSmear" to 0, "jitter" to 0, "wobble" to 0,
        "tracking" to 0, "headSwitch" to 0,
    )

    private fun run(changes: Map<String, Int>) = Television.apply(src, Television.defaultValues(off + changes), 3L)

    @Test
    fun `switched off the picture stays`() {
        val out = run(emptyMap())
        for (i in src.data.indices) for (s in 0..24 step 8) {
            assertTrue(abs((src.data[i] shr s and 0xFF) - (out.data[i] shr s and 0xFF)) <= 2, "Pixel $i")
        }
    }

    @Test
    fun `the bulge leaves black corners`() {
        val out = run(mapOf("curvature" to 100))
        assertTrue(out[0, 0] == 0xFF000000.toInt() && out[239, 159] == 0xFF000000.toInt())
        assertTrue(out[120, 80] != 0xFF000000.toInt())
        val clear = run(mapOf("curvature" to 100, "border" to 1))
        assertTrue(clear[0, 0] == 0)
    }

    @Test
    fun `scanlines darken every few rows`() {
        val out = run(mapOf("scanlines" to 100, "lineSpacing" to 4, "glow" to 0))
        fun rowSum(y: Int) = (0 until 240).sumOf { x -> out[x, y] shr 8 and 0xFF }
        val sums = (40 until 80).map { rowSum(it) }
        assertTrue(sums.max() > 2 * sums.min() + 100, "Zeilen: ${sums.take(8)}")
    }

    @Test
    fun `tracking errors shift rows`() {
        val out = run(mapOf("tracking" to 6, "trackingStrength" to 200))
        val changed = (0 until 160).count { y -> (0 until 240).any { x -> abs((out[x, y] shr 8 and 0xFF) - (src[x, y] shr 8 and 0xFF)) > 20 } }
        assertTrue(changed in 5 until 160, "gestörte Zeilen: $changed")
    }
}
