package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.DiffGrowth
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffGrowthTest {
    private val dark = argb(255, 20, 30, 40)

    /** A bright disk of radius 60 on dark, 160 × 160. */
    private val disk = Pixels(160, 160, IntArray(160 * 160) { i ->
        if (hypot(i % 160 + 0.5 - 80, i / 160 + 0.5 - 80) < 60) argb(255, 230, 200, 170) else dark
    })

    private val settings = mapOf("seeds" to 3, "steps" to 150, "fold" to 16, "merge" to 0)

    @Test
    fun `curves grow into lobes on the area, reproducibly`() {
        val v = DiffGrowth.defaultValues(settings)
        val out = DiffGrowth.apply(disk, v, 3L)
        assertContentEquals(out.data, DiffGrowth.apply(disk, v, 3L).data)
        val changed = (0 until 160 * 160).count { out.data[it] != disk.data[it] }
        assertTrue(changed > 3000, "changed $changed")
        // well away from the area nothing happens
        assertEquals(dark, out[2, 2])
    }

    @Test
    fun `the curves stay on the area and grow with the steps`() {
        fun red(steps: Int) = DiffGrowth.apply(disk, DiffGrowth.defaultValues(settings + mapOf("steps" to steps, "showMask" to 1)), 3L)
        val short = red(3)
        val long = red(150)
        fun count(p: Pixels) = p.data.count { it == 0xFFFF0033.toInt() }
        assertTrue(count(long) > count(short) * 2, "short ${count(short)}, long ${count(long)}")
        // no curve far outside the disk
        for (i in 0 until 160 * 160) {
            if (long.data[i] == 0xFFFF0033.toInt()) assertTrue(hypot(i % 160 + 0.5 - 80, i / 160 + 0.5 - 80) < 64, "curve outside at $i")
        }
    }

    @Test
    fun `the point limit bounds the growth`() {
        val limited = DiffGrowth.apply(disk, DiffGrowth.defaultValues(settings + mapOf("fold" to 5, "maxNodes" to 500, "showMask" to 1)), 3L)
        val free = DiffGrowth.apply(disk, DiffGrowth.defaultValues(settings + mapOf("fold" to 5, "showMask" to 1)), 3L)
        fun count(p: Pixels) = p.data.count { it == 0xFFFF0033.toInt() }
        assertTrue(count(limited) < count(free), "limited ${count(limited)}, free ${count(free)}")
    }
}
