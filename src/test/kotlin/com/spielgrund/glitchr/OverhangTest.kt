package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Mask
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Renderer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inverts the colors. */
private class Invert : Effect("invert", "Invertieren", "") {
    override val params = listOf(Param.Slider("dummy", "Dummy", 0, 1, 0))
    override fun apply(src: Pixels, v: Values, seed: Long) =
        Pixels(src.width, src.height, IntArray(src.data.size) { src.data[it] xor 0x00FFFFFF })
}

class OverhangTest {
    private val gray = argb(255, 200, 200, 200)

    @Test
    fun `painted mask moves and scales with its picture`() {
        // 40×20 picture, left half of its mask painted
        val picture = ImageLayer(Pixels(40, 20, IntArray(800) { gray })).apply { x = 10.0; y = 10.0; scale = 2.0 }
        val fx = EffectLayer(Invert()).apply {
            mask.mode = MaskMode.BRUSH
            mask.ensurePainted(40, 20)
            for (y in 0 until 20) for (x in 0 until 20) mask.painted!![y * 40 + x] = 0xFF.toByte()
        }
        fun render() = Renderer().render(120, 80, listOf(picture.state(), fx.state()))

        var out = render()
        // placed picture covers x 10..89; its left half (10..49) is inverted
        assertEquals(gray xor 0x00FFFFFF, out[20, 20])
        assertEquals(gray, out[70, 20])

        picture.x = 30.0
        picture.scale = 1.0 // now covers x 30..69, left half 30..49
        out = render()
        assertEquals(gray xor 0x00FFFFFF, out[35, 15])
        assertEquals(gray, out[55, 15])
    }

    @Test
    fun `gradients are relative to their picture`() {
        val picture = ImageLayer(Pixels(50, 50, IntArray(2500) { gray })).apply { x = 50.0 }
        val fx = EffectLayer(Invert()).apply {
            mask.mode = MaskMode.LINEAR
            mask.linearStart = RelPoint(0.0, 0.5)
            mask.linearEnd = RelPoint(1.0, 0.5)
        }
        val out = Renderer().render(100, 50, listOf(picture.state(), fx.state()))
        // pixel centers sit half a pixel inside the gradient ends, so allow a few levels
        assertTrue(kotlin.math.abs(com.spielgrund.glitchr.image.red(out[50, 25]) - 55) <= 6, "linker Bildrand: voller Effekt")
        assertTrue(kotlin.math.abs(com.spielgrund.glitchr.image.red(out[99, 25]) - 200) <= 6, "rechter Bildrand: kein Effekt")
        assertTrue(kotlin.math.abs(com.spielgrund.glitchr.image.red(out[75, 25]) - 128) <= 6, "Mitte des Bilds: halber Effekt")
    }

    @Test
    fun `a painted mask of another size is resampled, not cleared`() {
        val mask = Mask().apply { mode = MaskMode.BRUSH; ensurePainted(10, 10); fill(255) }
        mask.ensurePainted(40, 30)
        assertEquals(40 * 30, mask.painted!!.size)
        assertTrue(mask.painted!!.all { it == 0xFF.toByte() })
    }

    @Test
    fun `effects reach beyond the picture into the empty canvas`() {
        // a colorful picture in the middle of a larger transparent canvas
        val w = 160
        val h = 120
        val pic = ImageLayer(testImage(80, 60)).apply { x = 40.0; y = 30.0 }
        val placed = pic.placed(w, h)
        fun opaqueOutside(p: Pixels) = (0 until h).sumOf { y ->
            (0 until w).count { x -> (x !in 40 until 120 || y !in 30 until 90) && alpha(p[x, y]) > 0 }
        }
        assertEquals(0, opaqueOutside(placed))

        val settings = mapOf(
            "pixelsort" to mapOf("lower" to 0, "upper" to 255),
            "pixelbleed" to mapOf("threshold" to 20, "direction" to 2),
            "rgb" to mapOf("rx" to 20),
            "slices" to mapOf("shift" to 60, "count" to 20),
            "blocks" to mapOf("shift" to 60, "count" to 60),
            "datamosh" to mapOf("frames" to 20, "speed" to 4),
            "jpeg" to mapOf("quality" to 5, "blocks" to 4),
        )
        val dir = File("target/test-output").apply { mkdirs() }
        for ((id, values) in settings) {
            val effect = Effects.byId(id)
            val out = effect.apply(placed, Values(effect.defaults().apply { putAll(values) }), 9L)
            assertTrue(opaqueOutside(out) > 0, "$id bleibt im Bild")
            ImageIO.write(out.toImage(), "png", File(dir, "overhang-$id.png"))
        }
    }
}

class CutOutTest {
    @Test
    fun `the image mask cuts the picture before the effects, so they reach past the cut`() {
        val bright = argb(255, 240, 240, 240)
        // picture fills the canvas; its mask keeps only the right half
        val picture = ImageLayer(Pixels(100, 20, IntArray(2000) { bright })).apply {
            mask.mode = MaskMode.BRUSH
            mask.ensurePainted(100, 20)
            for (y in 0 until 20) for (x in 50 until 100) mask.painted!![y * 100 + x] = 0xFF.toByte()
        }
        val bleedLeft = EffectLayer(Effects.byId("pixelbleed")).apply {
            values["direction"] = 3 // to the left
            values["threshold"] = 100
            values["length"] = 30
        }
        val out = Renderer().render(100, 20, listOf(picture.state(), bleedLeft.state()))
        val beyondCut = (0 until 20).count { y -> alpha(out[45, y]) > 0 }
        assertTrue(beyondCut > 0, "Pixelbleed läuft über die Maskenkante hinaus")
        assertEquals(0, alpha(Renderer().render(100, 20, listOf(picture.state()))[45, 5]), "ohne Effekt bleibt links leer")
    }
}
