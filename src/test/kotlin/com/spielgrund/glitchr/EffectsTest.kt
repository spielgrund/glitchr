package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val bases = java.util.IdentityHashMap<Pixels, com.spielgrund.glitchr.model.ImageLayer>()

/** The same image layer for the same picture, so the renderer's cache works across calls. */
fun baseLayer(src: Pixels): com.spielgrund.glitchr.model.ImageLayer =
    bases.getOrPut(src) { com.spielgrund.glitchr.model.ImageLayer(src) }

/** Renders [layers] above [src] as the bottom image layer. */
fun com.spielgrund.glitchr.model.Renderer.render(src: Pixels, layers: List<com.spielgrund.glitchr.model.LayerState>): Pixels =
    render(src.width, src.height, listOf(baseLayer(src).state()) + layers)

/** A document with [src] as bottom image layer and [layers] above. */
fun docState(src: Pixels, name: String, layers: List<com.spielgrund.glitchr.model.LayerMemento>) =
    com.spielgrund.glitchr.model.DocState(src.width, src.height, name, listOf(baseLayer(src).memento()) + layers)

/** A colorful test picture: hue sweep left to right, brightness waves top to bottom. */
fun testImage(w: Int = 240, h: Int = 160) = Pixels(w, h).also { p ->
    for (y in 0 until h) for (x in 0 until w) {
        val v = (0.5 + 0.5 * sin(y / 9.0 + x / 31.0))
        p.data[y * w + x] = argb(
            255,
            (255 * v * x / w).toInt(),
            (255 * v * (1 - x.toDouble() / w)).toInt(),
            (255 * y.toDouble() / h).toInt(),
        )
    }
}

class EffectsTest {
    private val src = testImage()
    private val outDir = File("target/test-output").apply { mkdirs() }

    @Test
    fun `every effect keeps the size, changes the image and is deterministic`() {
        for (effect in Effects.all) {
            val values = Values(effect.defaults())
            val a = effect.apply(src, values, 42L)
            val b = effect.apply(src, values, 42L)
            assertEquals(src.width, a.width, effect.name)
            assertEquals(src.height, a.height, effect.name)
            assertContentEquals(a.data, b.data, "${effect.name} ist nicht deterministisch")
            // Flow starts at 0 % offset on purpose: first the direction is drawn, then the picture moves
            if (effect.id != "flow") assertFalse(a.data.contentEquals(src.data), "${effect.name} ändert nichts")
            ImageIO.write(a.toImage(), "png", File(outDir, "${effect.id}.png"))
        }
    }

    @Test
    fun `effects leave their input untouched`() {
        val before = src.data.copyOf()
        for (effect in Effects.all) effect.apply(src, Values(effect.defaults()), 7L)
        assertContentEquals(before, src.data)
    }

    @Test
    fun `jpeg corruption still decodes`() {
        val effect = Effects.byId("jpeg")
        val values = effect.defaults().apply { put("corrupt", 60); put("quality", 30) }
        val out = effect.apply(src, Values(values), 3L)
        assertEquals(src.data.size, out.data.size)
        ImageIO.write(out.toImage(), "png", File(outDir, "jpeg-corrupt.png"))
    }
}
