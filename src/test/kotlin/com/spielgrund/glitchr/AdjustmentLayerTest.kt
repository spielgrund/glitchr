package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.EffectMemento
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.model.groupRange
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inverts the colors. */
private object InvertAll : Effect("invert-test", "InvertAll", "") {
    override val params = emptyList<com.spielgrund.glitchr.effects.Param>()
    override fun apply(src: Pixels, v: Values, seed: Long) =
        Pixels(src.width, src.height, IntArray(src.data.size) { src.data[it] xor 0x00FFFFFF })
}

class AdjustmentLayerTest {
    private val w = 40
    private val h = 20
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** Red on the left half, transparent on the right. */
    private fun left() = ImageLayer(Pixels(w, h).also { p -> for (i in p.data.indices) if (i % w < w / 2) p.data[i] = red })

    /** Blue on the right half, transparent on the left. */
    private fun right() = ImageLayer(Pixels(w, h).also { p -> for (i in p.data.indices) if (i % w >= w / 2) p.data[i] = blue })

    private fun invert(adjustment: Boolean) = EffectLayer(InvertAll).also { it.adjustment = adjustment }

    private fun render(vararg layers: Layer) = Renderer().render(w, h, layers.map { it.state() })

    @Test
    fun `an adjustment layer works on all layers below, a plain effect only on its own image`() {
        val adjusted = render(left(), right(), invert(adjustment = true))
        assertEquals(red xor 0x00FFFFFF, adjusted[5, 5], "left image inverted too")
        assertEquals(blue xor 0x00FFFFFF, adjusted[35, 5])

        val plain = render(left(), right(), invert(adjustment = false))
        assertEquals(red, plain[5, 5], "plain effect leaves the lower image alone")
        assertEquals(blue xor 0x00FFFFFF, plain[35, 5])
    }

    @Test
    fun `layers above an adjustment layer stay untouched`() {
        val out = render(left(), invert(adjustment = true), right())
        assertEquals(red xor 0x00FFFFFF, out[5, 5])
        assertEquals(blue, out[35, 5])
    }

    @Test
    fun `effects above an adjustment layer refine it`() {
        val out = render(left(), right(), invert(adjustment = true), invert(adjustment = false))
        assertEquals(red, out[5, 5])
        assertEquals(blue, out[35, 5])
    }

    @Test
    fun `opacity, visibility and an empty canvas`() {
        val half = invert(adjustment = true).apply { opacity = 50 }
        val mixed = render(left(), half)
        assertTrue((mixed[5, 5] shr 8 and 0xFF) in 120..135, "half way to the inverted color")
        val hidden = invert(adjustment = true).apply { visible = false }
        assertEquals(red, render(left(), hidden)[5, 5])
        // nothing below: the effect gets a transparent canvas and must not fail
        render(invert(adjustment = true))
    }

    @Test
    fun `an adjustment layer starts a group of its own`() {
        val layers = listOf(left(), invert(false), invert(true), invert(false), right())
        assertEquals(0..1, groupRange(layers, 1))
        assertEquals(2..3, groupRange(layers, 3))
        assertEquals(4..4, groupRange(layers, 4))
    }

    @Test
    fun `the adjustment switch is saved and duplicated`() {
        val fx = EffectLayer(Effects.byId("rgb")).also { it.adjustment = true }
        assertTrue((fx.duplicate() as EffectLayer).adjustment)
        val file = File("target/test-output/adjustment.glitchr").apply { parentFile.mkdirs() }
        ProjectFile.save(DocState(w, h, "adj", listOf(left().memento(), fx.memento())), file)
        assertTrue((ProjectFile.load(file).layers[1] as EffectMemento).adjustment)
        assertTrue((ProjectFile.load(file).layers[1].toLayer() as EffectLayer).adjustment)
    }
}
