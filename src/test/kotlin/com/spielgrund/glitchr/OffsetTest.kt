package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Offset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OffsetTest {
    private val src = testImage(240, 160)

    private fun run(vararg changes: Pair<String, Int>) = Offset.apply(src, Offset.defaultValues(mapOf(*changes)), 0)

    @Test
    fun `without shifting or mirroring the picture stays`() = assertContentEquals(src.data, run("shiftX" to 0).data)

    @Test
    fun `moves and repeats at the edge`() {
        val out = run("shiftX" to 250, "shiftY" to -30)
        for (y in 0 until 160 step 7) for (x in 0 until 240 step 9) {
            assertEquals(src[Math.floorMod(x - 250, 240), Math.floorMod(y + 30, 160)], out[x, y])
        }
    }

    @Test
    fun `mirrors`() {
        val out = run("shiftX" to 0, "flipX" to 1, "flipY" to 1)
        assertEquals(src[239, 159], out[0, 0])
        assertEquals(src[0, 159], out[239, 0])
    }
}
