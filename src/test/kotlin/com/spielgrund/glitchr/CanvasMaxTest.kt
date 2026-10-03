package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.effects.PixelBleed
import com.spielgrund.glitchr.effects.PixelSort
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasMaxTest {
    private fun slider(effect: com.spielgrund.glitchr.effects.Effect, key: String) =
        effect.params.first { it.key == key } as Param.Slider

    @Test
    fun `length sliders end at the longer canvas side`() {
        assertEquals(768, slider(PixelSort, "maxLength").maxFor(768, 620))
        assertEquals(4000, slider(PixelBleed, "length").maxFor(3000, 4000))
        assertEquals(620, slider(PixelSort, "overhang").maxFor(400, 620))
        assertEquals(255, slider(PixelSort, "lower").maxFor(4000, 4000), "other sliders stay fixed")
    }
}
