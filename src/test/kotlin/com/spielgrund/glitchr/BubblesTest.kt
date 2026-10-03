package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Bubbles
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BubblesTest {
    private val dark = argb(255, 20, 25, 30)

    /** Two bright striped blocks with a small gap, on dark. */
    private val blocks = Pixels(200, 120, IntArray(200 * 120) { i ->
        val x = i % 200
        val y = i / 200
        val inBlock = y in 20 until 100 && (x in 20 until 96 || x in 104 until 180)
        if (inBlock) (if ((x / 3) % 2 == 0) argb(255, 200, 200, 200) else argb(255, 250, 240, 230)) else dark
    })

    private val settings = mapOf("precision" to 0, "bubbles" to 30, "steps" to 100, "bubbleGrowth" to 5)

    @Test
    fun `bubbles bend the picture on the melted area only, reproducibly`() {
        val v = Bubbles.defaultValues(settings)
        val out = Bubbles.apply(blocks, v, 5L)
        assertContentEquals(out.data, Bubbles.apply(blocks, v, 5L).data)
        val changed = (0 until 200 * 120).count { out.data[it] != blocks.data[it] }
        assertTrue(changed > 1000, "changed $changed")
        // far from the area nothing moves
        assertEquals(dark, out[2, 2])
        assertEquals(dark, out[197, 117])
        // the mask view: the gap between the blocks melted into the area, the walls red
        val mask = Bubbles.apply(blocks, Bubbles.defaultValues(settings + ("showMask" to 1)), 5L)
        assertTrue(mask[100, 60] != 0xFF000000.toInt() && mask[100, 60] != -1, "gap not merged")
        assertTrue(mask.data.any { it == 0xFFFF0033.toInt() }, "no bubbles")
    }

    @Test
    fun `anti-aliasing adds in-between colors at the walls`() {
        fun colors(aa: Int) = Bubbles.apply(blocks, Bubbles.defaultValues(settings + ("antialias" to aa)), 5L).data.toSet().size
        assertTrue(colors(2) > colors(0), "smooth ${colors(2)}, hard ${colors(0)}")
    }

    @Test
    fun `light off is the same as all light sliders at zero`() {
        val off = Bubbles.apply(blocks, Bubbles.defaultValues(settings + ("lightOn" to 0)), 5L)
        val zero = Bubbles.apply(blocks, Bubbles.defaultValues(settings + mapOf("shading" to 0, "gloss" to 0, "rim" to 0)), 5L)
        assertContentEquals(zero.data, off.data)
        assertTrue(!off.data.contentEquals(Bubbles.apply(blocks, Bubbles.defaultValues(settings), 5L).data))
    }

    @Test
    fun `nothing chosen, nothing happens`() {
        assertContentEquals(blocks.data, Bubbles.apply(blocks, Bubbles.defaultValues(mapOf("lower" to 255, "upper" to 255)), 3L).data)
    }
}
