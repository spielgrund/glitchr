package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.DepthBlur
import com.spielgrund.glitchr.effects.PixelRain
import com.spielgrund.glitchr.effects.SphereTransform
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewEffects6Test {
    private val black = Pixels(100, 100, IntArray(10000) { 0xFF000000.toInt() })

    private fun bright(p: Pixels, rows: IntRange) = rows.sumOf { y -> (0 until 100).count { x -> (p.data[y * 100 + x] and 0xFF) > 100 } }

    @Test
    fun `pixel rain falls and lands on the floor`() {
        // own white color, a floor at 80 %, no staying – only the falling pixels and the splashes
        val v = PixelRain.defaultValues(mapOf("time" to 3000, "count" to 400, "coloring" to 1, "color1" to 0xFFFFFF, "hit" to 1, "floor" to 80, "stay" to 0, "splash" to 0))
        val out = PixelRain.apply(black, v, 1L)
        assertTrue(bright(out, 0 until 79) > 20, "falling pixels above the floor")
        assertEquals(0, bright(out, 82 until 100), "nothing below the floor")
    }

    @Test
    fun `landed pixels stay on the floor and splash`() {
        val stay = PixelRain.defaultValues(mapOf("time" to 3000, "count" to 400, "coloring" to 1, "color1" to 0xFFFFFF, "hit" to 1, "floor" to 80, "stay" to 50, "splash" to 0, "trailCopies" to 0))
        assertTrue(bright(PixelRain.apply(black, stay, 1L), 78 until 80) > 10, "lying on the floor")
        val splash = PixelRain.defaultValues(mapOf("time" to 3000, "count" to 400, "coloring" to 1, "color1" to 0xFFFFFF, "hit" to 1, "floor" to 80, "stay" to 0, "splash" to 8))
        assertTrue(bright(PixelRain.apply(black, splash, 1L), 0 until 80) > bright(PixelRain.apply(black, stay.let { PixelRain.defaultValues(mapOf("time" to 3000, "count" to 400, "coloring" to 1, "color1" to 0xFFFFFF, "hit" to 1, "floor" to 80, "stay" to 0, "splash" to 0)) }, 1L), 0 until 80))
    }

    @Test
    fun `pixel rain lands on a bright edge`() {
        // a white bar in the middle: the rain stops on its top edge, nothing falls behind it
        val src = Pixels(100, 100, IntArray(10000) { i -> if (i / 100 in 50..60) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })
        val v = PixelRain.defaultValues(mapOf("time" to 3000, "count" to 400, "coloring" to 1, "color1" to 0xFF0000, "hit" to 2, "stay" to 0, "splash" to 0, "wind" to 0))
        val out = PixelRain.apply(src, v, 1L)
        val redBelow = (65 until 100).sumOf { y -> (0 until 100).count { x -> out.data[y * 100 + x] == 0xFFFF0000.toInt() } }
        assertEquals(0, redBelow)
    }

    @Test
    fun `sphere transform runs the lines into the zenith, each half on its own side`() {
        // a red column 5 px wide around x = 100 on 120 × 60, the horizon in the middle (row 30)
        val src = Pixels(120, 60, IntArray(7200) { i -> if (i % 120 in 98..102) 0xFFFF0000.toInt() else 0xFF3080FF.toInt() })
        assertContentEquals(src.data, SphereTransform.apply(src, SphereTransform.defaultValues(), 0L).data)
        val out = SphereTransform.apply(src, SphereTransform.defaultValues(mapOf("top" to 100, "beyond" to 0, "antialias" to 0)), 0L)
        // the middle of the red run in a row
        fun redAt(p: Pixels, y: Int): Int {
            val reds = (0 until 120).filter { x -> p.data[y * 120 + x] ushr 24 == 255 && (p.data[y * 120 + x] shr 16 and 0xFF) > 0x90 }
            return if (reds.isEmpty()) -1 else (reds.first() + reds.last()) / 2
        }
        // straight towards the zenith on the top edge above the middle: halfway up, halfway in
        assertTrue(redAt(out, 15) in 78..82, "halfway: ${redAt(out, 15)}")
        assertTrue(redAt(out, 9) in 70..75, "nearer the zenith: ${redAt(out, 9)}")
        // the horizon and everything below it stay as they were
        for (y in 31 until 60) assertEquals(100, redAt(out, y), "row $y")
        // the pull stretches what lies low up to the top: a red row just above the horizon reaches far up
        val low = Pixels(120, 60, IntArray(7200) { i -> if (i / 120 in 25..27) 0xFFFF0000.toInt() else 0xFF3080FF.toInt() })
        val pulled = SphereTransform.apply(low, SphereTransform.defaultValues(mapOf("pullTop" to 100)), 0L)
        assertTrue((pulled.data[5 * 120 + 60] shr 16 and 0xFF) > 150, "pulled up into a streak")
        assertEquals(low.data[50 * 120 + 60], pulled.data[50 * 120 + 60], "below the horizon untouched")
    }

    @Test
    fun `depth blur keeps the focus sharp and blurs the rest`() {
        // fine stripes; "low is near": focus on the bottom
        val src = Pixels(100, 100, IntArray(10000) { i -> if ((i % 100) % 4 < 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })
        val v = DepthBlur.defaultValues(mapOf("depthSharp" to 0, "depthLow" to 100, "depthLight" to 0, "depthSat" to 0, "depthCenter" to 0, "focus" to 100, "blur" to 20, "fstop" to 14, "boost" to 0))
        val out = DepthBlur.apply(src, v, 0L)
        fun contrast(y: Int) = (0 until 99).sumOf { x -> abs((out.data[y * 100 + x] and 0xFF) - (out.data[y * 100 + x + 1] and 0xFF)) }
        assertTrue(contrast(97) > 4 * contrast(5), "bottom sharp ${contrast(97)}, top blurred ${contrast(5)}")
        // f/22 hardly blurs
        val closed = DepthBlur.apply(src, v.let { DepthBlur.defaultValues(mapOf("depthSharp" to 0, "depthLow" to 100, "depthLight" to 0, "depthSat" to 0, "depthCenter" to 0, "focus" to 100, "blur" to 1, "fstop" to 220, "boost" to 0)) }, 0L)
        assertContentEquals(src.data, closed.data)
    }

    private fun abs(x: Int) = kotlin.math.abs(x)
}
