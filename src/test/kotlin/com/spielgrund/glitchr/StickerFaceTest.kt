package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.StickerFace
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerFaceTest {
    private val src = Pixels(200, 200, IntArray(200 * 200) { 0xFFFFFFFF.toInt() })

    private fun changed(p: Pixels, x0: Int, y0: Int, x1: Int, y1: Int) =
        (y0 until y1).sumOf { y -> (x0 until x1).count { x -> p[x, y] != src[x, y] } }

    @Test
    fun `the face sits at the handle, the hands at their points`() {
        val out = StickerFace.apply(src, StickerFace.defaultValues(mapOf("centerX" to 400, "wave" to 0), mapOf("handPoints" to "0.1000,0.1000")), 1)
        assertTrue(changed(out, 110, 70, 170, 140) > 100, "face around (140, 100)")
        assertTrue(changed(out, 10, 10, 30, 30) > 10, "a hand at (20, 20)")
        assertEquals(0, changed(out, 150, 150, 200, 200), "nothing at the far corner")
    }

    @Test
    fun `without hands there are no arms, at opacity 0 nothing`() {
        val bare = StickerFace.apply(src, StickerFace.defaultValues(texts = mapOf("handPoints" to "")), 1)
        assertEquals(0, changed(bare, 0, 0, 200, 50), "nothing above the face")
        val none = StickerFace.apply(src, StickerFace.defaultValues(mapOf("opacity" to 0)), 1)
        assertContentEquals(src.data, none.data)
    }

    @Test
    fun `the time waves the hands and blinks the eyes`() {
        val a = StickerFace.apply(src, StickerFace.defaultValues(mapOf("time" to 0)), 1)
        val b = StickerFace.apply(src, StickerFace.defaultValues(mapOf("time" to 3)), 1)
        assertTrue(changed(a, 0, 0, 200, 60).let { it > 0 } && !a.data.contentEquals(b.data), "the hands moved")
        val still = StickerFace.defaultValues(mapOf("wave" to 0, "time" to 3))
        assertContentEquals(StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0)), 1).data, StickerFace.apply(src, still, 1).data, "without waving the arcs stay")
        val times = (0 until 2000).map { StickerFace.blinkOpen(it / 100.0, 1.0, 5, 0) }
        assertTrue(times.any { it < 0.1 } && times.count { it == 1.0 } > 1500, "now and then shut, mostly open")
        assertTrue((0 until 2000).all { StickerFace.blinkOpen(it / 100.0, 0.0, 5, 0) == 1.0 })
    }

    @Test
    fun `the arm wave ripples the arms only, running with the time`() {
        val still = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0)), 1)
        val rippled = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "armWave" to 100)), 1)
        val later = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "armWave" to 100, "time" to 3)), 1)
        assertTrue(!still.data.contentEquals(rippled.data) && !rippled.data.contentEquals(later.data))
        // the eyes stay where they are
        assertEquals(0, (80 until 100).sumOf { y -> (70 until 130).count { x -> still[x, y] != rippled[x, y] } })
    }

    @Test
    fun `fidgeting twitches the parts in steps`() {
        fun at(time: Int, fidget: Int = 100) = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "fidget" to fidget, "fidgetRate" to 2, "time" to time)), 1).data
        assertContentEquals(at(0, 0), at(10, 0), "without fidgeting nothing moves")
        assertContentEquals(at(1), at(4), "the same within a step (half a second)")
        assertTrue(!at(1).contentEquals(at(6)), "a new spot in the next step")
        assertTrue(!at(1).contentEquals(at(1, 0)))
    }

    @Test
    fun `every part can be left out, None first in the menus`() {
        for (key in listOf("eyes", "brows", "ears", "cheeks", "nose", "whiskers", "mouth", "hands")) {
            val c = StickerFace.params.first { it.key == key } as com.spielgrund.glitchr.effects.Param.Choice
            assertEquals("None", c.options[c.shown[0]], key)
        }
        fun none(key: String) = (StickerFace.params.first { it.key == key } as com.spielgrund.glitchr.effects.Param.Choice).let { it.shown[0] }
        val bare = StickerFace.apply(src, StickerFace.defaultValues(listOf("eyes", "brows", "cheeks", "nose", "whiskers", "mouth").associateWith { none(it) }, mapOf("handPoints" to "")), 1)
        assertContentEquals(src.data, bare.data, "nothing left")
    }

    @Test
    fun `whiskers, fangs and cat paws draw`() {
        val plain = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0)), 1)
        for (m in listOf(mapOf("whiskers" to 1), mapOf("whiskers" to 3), mapOf("mouth" to 7), mapOf("mouth" to 8), mapOf("hands" to 5))) {
            val out = StickerFace.apply(src, StickerFace.defaultValues(m + mapOf("wave" to 0)), 1)
            assertTrue(!out.data.contentEquals(plain.data), "$m")
        }
        // two fangs are white where the plain smile is not
        val fangs = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "mouth" to 8, "lineColor" to 0x000000)), 1)
        val one = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "mouth" to 7, "lineColor" to 0x000000)), 1)
        assertTrue(fangs.data.indices.count { fangs.data[it] != one.data[it] } > 0)
    }

    @Test
    fun `the mouth stands open without talking, ears sit above the eyes`() {
        val shut = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0)), 1)
        val open = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "mouthOpen" to 80)), 1)
        val later = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "mouthOpen" to 80, "time" to 37)), 1)
        assertTrue(!shut.data.contentEquals(open.data), "open")
        assertContentEquals(open.data, later.data, "and stays so without talking")
        for (ears in 1..4) {
            val out = StickerFace.apply(src, StickerFace.defaultValues(mapOf("wave" to 0, "ears" to ears), mapOf("handPoints" to "")), 1)
            // the face is 80 px wide around (100, 100): the ears 32 px above the middle
            assertTrue(changed(out, 50, 40, 150, 80) > 50, "ears $ears")
        }
    }
}
