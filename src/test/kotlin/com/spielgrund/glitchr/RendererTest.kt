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
import kotlin.test.assertTrue
import com.spielgrund.glitchr.model.BlendMode

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

    @Test
    fun `soft light, overlay, linear light, subtract and divide mix like in Photoshop`() {
        fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        fun mix(mode: BlendMode, base: Int, top: Int) = mode.apply(grey(base), grey(top)) and 0xFF
        // mid grey on top changes nothing with soft light, overlay and linear light
        for (mode in listOf(BlendMode.SOFT_LIGHT, BlendMode.OVERLAY, BlendMode.LINEAR_LIGHT)) for (b in listOf(0, 60, 128, 200, 255)) {
            assertTrue(kotlin.math.abs(mix(mode, b, 128) - b) <= 1, "$mode on $b")
        }
        // overlay: multiply in the darks, screen in the lights
        assertEquals(51, mix(BlendMode.OVERLAY, 64, 102))
        assertEquals(179, mix(BlendMode.OVERLAY, 192, 102), "screen half: 1 − 2·(1 − b)·(1 − t)")
        // soft light: white lightens, black darkens, gently
        assertEquals(160, mix(BlendMode.SOFT_LIGHT, 100, 255), "√b")
        assertEquals(39, mix(BlendMode.SOFT_LIGHT, 100, 0), "b²")
        // linear light: base + 2·top − 1, clamped
        assertEquals(201, mix(BlendMode.LINEAR_LIGHT, 100, 178))
        assertEquals(0, mix(BlendMode.LINEAR_LIGHT, 50, 0))
        assertEquals(255, mix(BlendMode.LINEAR_LIGHT, 200, 255))
        // subtract and divide
        assertEquals(70, mix(BlendMode.SUBTRACT, 200, 130))
        assertEquals(0, mix(BlendMode.SUBTRACT, 100, 130))
        assertEquals(128, mix(BlendMode.DIVIDE, 128, 255), "white changes nothing")
        assertEquals(255, mix(BlendMode.DIVIDE, 100, 64), "darker tops brighten")
        assertEquals(199, mix(BlendMode.DIVIDE, 100, 128))
        assertEquals(255, mix(BlendMode.DIVIDE, 10, 0), "black: white")
        assertEquals(0, mix(BlendMode.DIVIDE, 0, 0))
        // the top's transparency is kept
        assertEquals(0x80, BlendMode.SUBTRACT.apply(grey(200), 0x80404040.toInt()) ushr 24)
    }

    @Test
    fun `the blend mode menu groups lightening, laying over, darkening and comparing, cutting, stored numbers unchanged`() {
        // every mode once
        assertEquals(BlendMode.entries.toSet(), BlendMode.menu.toSet())
        assertEquals(BlendMode.entries.size, BlendMode.menu.size)
        val labels = BlendMode.menu.map { it.label }
        assertEquals(
            listOf("Normal", "Lighten", "Screen", "Add", "Overlay", "Soft light", "Linear light", "Darken", "Multiply", "Divide", "Difference", "Subtract", "Cut black", "Cut white", "Cut grey"),
            labels,
        )
        // effects store a mode by its number: the old ones keep theirs
        assertEquals(listOf("Normal", "Lighten", "Darken", "Screen", "Multiply", "Add", "Difference", "Cut black", "Cut white", "Cut grey"), BlendMode.entries.take(10).map { it.label })
        // a choice in an effect shows the menu order but stores the mode's number
        val choice = com.spielgrund.glitchr.effects.Feedback.params.first { it.key == "blend" } as com.spielgrund.glitchr.effects.Param.Choice
        assertEquals(labels, choice.shown.map { choice.options[it] })
        assertEquals(BlendMode.DIVIDE.ordinal, choice.shown[9])
    }

    @Test
    fun `cut blend modes take away only pure black, white or grey`() {
        val black = com.spielgrund.glitchr.model.BlendMode.CUT_BLACK
        assertEquals(0, black.cut(0xFF000000.toInt()))
        assertEquals(0, black.cut(0xFF020202.toInt()), "within 1 %")
        assertEquals(0xFF040404.toInt(), black.cut(0xFF040404.toInt()), "just beyond stays")
        assertEquals(0xFF808080.toInt(), black.cut(0xFF808080.toInt()))
        assertEquals(0, com.spielgrund.glitchr.model.BlendMode.CUT_WHITE.cut(0xFFFEFFFD.toInt()))
        assertEquals(0, com.spielgrund.glitchr.model.BlendMode.CUT_GREY.cut(0xFF818080.toInt()))
        assertEquals(0xFF707070.toInt(), com.spielgrund.glitchr.model.BlendMode.CUT_GREY.cut(0xFF707070.toInt()))
        // an effect whose result is black on the right: cut black shows the picture there
        val pic = Pixels(2, 1, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val layer = EffectLayer(CountingInvert())
        layer.blend = black
        val out = Renderer().render(pic, listOf(layer.state()))
        assertEquals(0xFFFFFFFF.toInt(), out.data[0], "white result stays")
        assertEquals(0xFFFFFFFF.toInt(), out.data[1], "black result cut away")
    }

    @Test
    fun `luma key and color key make dark parts or the key color transparent`() {
        val pic = Pixels(3, 1, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF00FF00.toInt()))
        val luma = com.spielgrund.glitchr.effects.LumaKey
        val l = luma.apply(pic, luma.defaultValues(emptyMap()), 0)
        assertEquals(0, l.data[0] ushr 24, "dark keyed out")
        assertEquals(255, l.data[1] ushr 24, "bright stays")
        assertEquals(0, luma.apply(pic, luma.defaultValues(mapOf("keyOut" to 1)), 0).data[1] ushr 24, "or the bright")
        val color = com.spielgrund.glitchr.effects.ColorKey
        val c = color.apply(pic, color.defaultValues(emptyMap()), 0)
        assertEquals(0, c.data[2] ushr 24, "green keyed out")
        assertEquals(255, c.data[0] ushr 24)
        assertEquals(255, c.data[1] ushr 24)
        val matte = color.apply(pic, color.defaultValues(mapOf("matte" to 1)), 0)
        assertEquals(0xFF000000.toInt(), matte.data[2], "the matte: black where keyed out")
    }
}
