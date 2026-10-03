package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Renderer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** Inverts the image and counts how often it ran. */
private class CountingInvert : Effect("count", "Counter", "") {
    var calls = 0
    override val params = listOf(Param.Slider("dummy", "Dummy", 0, 10, 0))
    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        calls++
        return Pixels(src.width, src.height, IntArray(src.data.size) { src.data[it] xor 0x00FFFFFF })
    }
}

class RendererTest {
    private val src = testImage(64, 48)

    @Test
    fun `empty brush mask shows the image below, full mask the effect`() {
        val effect = CountingInvert()
        val layer = EffectLayer(effect)
        layer.mask.mode = MaskMode.BRUSH
        layer.mask.ensurePainted(src.width, src.height)
        val renderer = Renderer()
        assertContentEquals(src.data, renderer.render(src, listOf(layer.state())).data)

        layer.mask.fill(255)
        val full = renderer.render(src, listOf(layer.state()))
        assertContentEquals(effect.apply(src, Values(emptyMap()), 0).data, full.data)
    }

    @Test
    fun `changing only the mask does not rerun the effect`() {
        val effect = CountingInvert()
        val layer = EffectLayer(effect)
        layer.mask.mode = MaskMode.LINEAR
        val renderer = Renderer()
        renderer.render(src, listOf(layer.state()))
        layer.mask.linearEnd = RelPoint(0.2, 0.9)
        layer.opacity = 50
        renderer.render(src, listOf(layer.state()))
        assertEquals(1, effect.calls)

        layer.values["dummy"] = 3
        renderer.render(src, listOf(layer.state()))
        assertEquals(2, effect.calls)
    }

    @Test
    fun `layers above an unchanged layer reuse its result`() {
        val bottom = CountingInvert()
        val top = CountingInvert()
        val lower = EffectLayer(bottom)
        val upper = EffectLayer(top)
        val renderer = Renderer()
        renderer.render(src, listOf(lower.state(), upper.state()))
        upper.opacity = 40
        renderer.render(src, listOf(lower.state(), upper.state()))
        assertEquals(1, bottom.calls)
        assertEquals(1, top.calls)
        lower.values["dummy"] = 1
        renderer.render(src, listOf(lower.state(), upper.state()))
        assertEquals(2, bottom.calls)
        assertEquals(2, top.calls)
    }

    @Test
    fun `linear gradient fades from full effect to none`() {
        val layer = EffectLayer(CountingInvert())
        layer.mask.mode = MaskMode.LINEAR
        layer.mask.linearStart = RelPoint(0.0, 0.5)
        layer.mask.linearEnd = RelPoint(1.0, 0.5)
        val out = Renderer().render(src, listOf(layer.state()))
        val w = src.width
        assertClose(src.data[5 * w] xor 0x00FFFFFF, out.data[5 * w], "full effect on the left")
        assertClose(src.data[5 * w + w - 1], out.data[5 * w + w - 1], "no effect on the right")
    }

    /** Pixel centers never sit exactly on the gradient ends, so allow a few levels per channel. */
    private fun assertClose(expected: Int, actual: Int, message: String) {
        for (shift in listOf(16, 8, 0)) {
            val d = kotlin.math.abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF))
            kotlin.test.assertTrue(d <= 6, "$message: channel differs by $d")
        }
    }

    @Test
    fun `hidden layers pass the image through`() {
        val layer = EffectLayer(Effects.byId("rgb")).apply { visible = false }
        assertSame(src, Renderer().render(src, listOf(layer.state())))
        layer.visible = true
        assertNotEquals(src, Renderer().render(src, listOf(layer.state())))
    }
}
