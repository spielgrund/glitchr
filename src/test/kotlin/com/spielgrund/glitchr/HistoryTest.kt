package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.PixelSort
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.History
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.Renderer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HistoryTest {
    private val src = testImage(64, 48)

    private fun state(layers: List<Layer>) = docState(src, "test", layers.map { it.memento() })

    @Test
    fun `unchanged states are not recorded twice`() {
        val layer = EffectLayer(Effects.byId("rgb"))
        val history = History()
        assertTrue(history.commit(state(listOf(layer))))
        assertFalse(history.commit(state(listOf(layer))), "gleicher Zustand, auch mit gemalter Maske nicht doppelt")
        layer.mask.mode = MaskMode.BRUSH
        layer.mask.ensurePainted(64, 48)
        assertTrue(history.commit(state(listOf(layer))))
        assertFalse(history.commit(state(listOf(layer))))
    }

    @Test
    fun `undo brings back painted mask and settings, redo reapplies`() {
        val layer = EffectLayer(Effects.byId("rgb"))
        layer.mask.mode = MaskMode.BRUSH
        layer.mask.ensurePainted(64, 48)
        val history = History()
        history.commit(state(listOf(layer)))

        layer.mask.dab(10.0, 10.0, 8.0, 0.5f, 1f, erase = false)
        layer.values["rx"] = 40
        history.commit(state(listOf(layer)))
        val painted = layer.mask.painted!!.copyOf()

        val before = history.undo()!!.layers.last().toLayer() as EffectLayer
        assertEquals(layer.id, before.id)
        assertEquals(12, before.values["rx"])
        assertTrue(before.mask.painted!!.all { it == 0.toByte() })

        val after = history.redo()!!.layers.last().toLayer() as EffectLayer
        assertEquals(40, after.values["rx"])
        assertContentEquals(painted, after.mask.painted)
        assertNull(history.redo())
    }

    @Test
    fun `painting on a restored layer does not change the history`() {
        val layer = EffectLayer(Effects.byId("rgb"))
        layer.mask.mode = MaskMode.BRUSH
        layer.mask.ensurePainted(64, 48)
        val history = History()
        history.commit(state(listOf(layer)))
        val restored = history.undo() ?: docState(src, "test", listOf(layer.memento()))
        val copy = restored.layers.last().toLayer()
        copy.mask.fill(255)
        assertTrue(restored.layers.last().mask.painted!!.all { it == 0.toByte() })
    }

    @Test
    fun `restored layer gets a new mask version so the renderer recomposites`() {
        val layer = EffectLayer(Effects.byId("rgb"))
        layer.mask.mode = MaskMode.LINEAR
        val memento = layer.memento()
        val renderer = Renderer()
        val first = renderer.render(src, listOf(layer.state()))
        layer.mask.invert = true
        renderer.render(src, listOf(layer.state()))
        val restored = memento.toLayer()
        assertNotEquals(layer.mask.version, restored.mask.version)
        assertContentEquals(first.data, renderer.render(src, listOf(restored.state())).data)
    }

    @Test
    fun `pixelsort at any angle only rearranges pixels`() {
        for (angle in listOf(0, 17, 45, 90, 133, 180, 225, 270, 301, 359)) {
            val values = PixelSort.defaults().apply { put("angle", angle); put("lower", 0); put("upper", 255) }
            val out = PixelSort.apply(src, Values(values), 1L)
            assertContentEquals(src.data.sortedArray(), out.data.sortedArray(), "Winkel $angle")
            assertNotEquals(src.data.toList(), out.data.toList(), "Winkel $angle ändert nichts")
        }
    }

    @Test
    fun `datamosh without moving blocks keeps the image`() {
        val effect = Effects.byId("datamosh")
        val values = effect.defaults().apply { put("moving", 0) }
        assertContentEquals(src.data, effect.apply(src, Values(values), 5L).data)
        assertSame(effect, Effects.all.first { it.id == "datamosh" })
    }
}
