package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Twist
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.model.Resize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResizeTest {
    private val src = testImage(100, 80)

    private fun render(layers: List<Layer>, w: Int, h: Int) = Renderer().render(w, h, layers.map { it.state() })

    /** A picture, a hue shift on it through a gradient mask, and an adjustment layer with a painted mask on the canvas. */
    private fun document(): List<Layer> {
        val picture = ImageLayer(src)
        val hue = EffectLayer(Effects.byId("colorcorrect")).apply {
            values["hue"] = 9000
            mask.mode = MaskMode.LINEAR
            mask.linearStart = RelPoint(0.2, 0.0)
            mask.linearEnd = RelPoint(0.8, 0.0)
        }
        val adjust = EffectLayer(Effects.byId("colorcorrect")).apply {
            adjustment = true
            values["hue"] = -12000
            mask.ensurePainted(100, 80)
            mask.mode = MaskMode.BRUSH
            mask.dab(30.0, 40.0, 15.0, 1f, 1f, false)
        }
        return listOf(picture, hue, adjust)
    }

    @Test
    fun `cropping keeps exactly that part of the picture`() {
        val layers = document()
        val before = render(layers, 100, 80)
        Resize(100, 80, 50, 40, 1.0, -10.0, -20.0).apply(layers)
        val after = render(layers, 50, 40)
        var differing = 0
        for (y in 0 until 40) for (x in 0 until 50) {
            val a = before[x + 10, y + 20]
            val b = after[x, y]
            if (listOf(16, 8, 0).any { s -> kotlin.math.abs((a shr s and 0xFF) - (b shr s and 0xFF)) > 2 }) differing++
        }
        // the painted mask is resampled: at most its edge may differ by a pixel
        assertTrue(differing < 40, "differing pixels: $differing")
    }

    @Test
    fun `a larger canvas puts the picture at the anchor`() {
        val layers = document()
        Resize(100, 80, 140, 100, 1.0, 20.0, 10.0).apply(layers)
        val picture = layers[0] as ImageLayer
        assertEquals(20.0, picture.x)
        assertEquals(10.0, picture.y)
        val out = render(layers, 140, 100)
        assertEquals(0, out[5, 5] ushr 24, "transparent around")
    }

    @Test
    fun `scaling scales the layers and the pixel sizes of the effects`() {
        val picture = ImageLayer(src)
        val twist = EffectLayer(Twist).apply { values["radius"] = 40; values["centerX"] = 200 }
        val layers = listOf(picture, twist)
        Resize(100, 80, 50, 40, 0.5, 0.0, 0.0).apply(layers)
        assertEquals(0.5, picture.scale)
        assertEquals(20, twist.values["radius"])
        // the handle 20 px right of the middle stays on the same spot of the picture: 10 px now
        assertEquals(100, twist.values["centerX"])
    }
}
