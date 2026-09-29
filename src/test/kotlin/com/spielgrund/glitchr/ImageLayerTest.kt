package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.ImageMemento
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.model.groupRange
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Makes every pixel pure blue and counts its runs. */
private class Blue : Effect("blue", "Blau", "") {
    var calls = 0
    override val params = listOf(Param.Slider("dummy", "Dummy", 0, 10, 0))
    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        calls++
        return Pixels(src.width, src.height, IntArray(src.data.size) { (src.data[it] and 0xFF000000.toInt()) or 0x0000FF })
    }
}

private fun solid(w: Int, h: Int, color: Int) = Pixels(w, h, IntArray(w * h) { color })

class ImageLayerTest {
    private val red = argb(255, 255, 0, 0)
    private val green = argb(255, 0, 255, 0)
    private val w = 100
    private val h = 80

    private fun render(renderer: Renderer, vararg layers: Layer) = renderer.render(w, h, layers.map { it.state() })

    @Test
    fun `effects work only on their own image layer`() {
        val bottom = ImageLayer(solid(w, h, red))
        val top = ImageLayer(solid(20, 10, green)).apply { x = 10.0; y = 20.0; scale = 2.0 }
        val blue = EffectLayer(Blue())
        val out = render(Renderer(), bottom, top, blue)

        // top picture covers x 10..49, y 20..39 and was turned blue; the rest is the untouched red bottom
        assertEquals(argb(255, 0, 255, 0) and 0xFF000000.toInt() or 0x0000FF, out[30, 30])
        assertEquals(red, out[5, 5])
        assertEquals(red, out[60, 30])
        assertEquals(red, out[30, 50])
    }

    @Test
    fun `effects of the bottom group stay below upper image layers`() {
        val bottom = ImageLayer(solid(w, h, red))
        val blue = EffectLayer(Blue())
        val top = ImageLayer(solid(10, 10, green)).apply { x = 0.0; y = 0.0 }
        val out = render(Renderer(), bottom, blue, top)
        assertEquals(green, out[5, 5], "obere Bildebene bleibt unverändert")
        assertEquals(argb(255, 0, 0, 255), out[50, 50], "untere Ebene mit ihrem Effekt")
    }

    @Test
    fun `transparent canvas outside all pictures and opacity mixes`() {
        val small = ImageLayer(solid(10, 10, red)).apply { x = 0.0; y = 0.0 }
        val out = render(Renderer(), small)
        assertEquals(0, alpha(out[50, 50]), "ausserhalb der Bilder ist die Leinwand durchsichtig")

        val bottom = ImageLayer(solid(w, h, green))
        val half = ImageLayer(solid(w, h, red)).apply { opacity = 50 }
        val mixed = render(Renderer(), bottom, half)
        assertEquals(255, alpha(mixed[10, 10]))
        assertTrue(red(mixed[10, 10]) in 120..135, "halbe Deckkraft: ${red(mixed[10, 10])}")
    }

    @Test
    fun `changing an upper group leaves the lower group's effects cached`() {
        val lowerEffect = Blue()
        val upperEffect = Blue()
        val bottom = ImageLayer(solid(w, h, red))
        val lowerFx = EffectLayer(lowerEffect)
        val top = ImageLayer(solid(20, 20, green))
        val upperFx = EffectLayer(upperEffect)
        val renderer = Renderer()
        render(renderer, bottom, lowerFx, top, upperFx)
        top.x = 30.0
        render(renderer, bottom, lowerFx, top, upperFx)
        assertEquals(1, lowerEffect.calls, "untere Gruppe unverändert")
        assertEquals(2, upperEffect.calls, "verschobenes Bild wird neu bearbeitet")
        top.opacity = 40
        render(renderer, bottom, lowerFx, top, upperFx)
        assertEquals(2, upperEffect.calls, "Deckkraft braucht den Effekt nicht neu")
    }

    @Test
    fun `a picture that fills the canvas is used directly`() {
        val img = solid(w, h, red)
        assertSame(img, render(Renderer(), ImageLayer(img)))
    }

    @Test
    fun `groups are an image layer and the effects above it`() {
        val layers = listOf(
            ImageLayer(solid(1, 1, red)), EffectLayer(Blue()), EffectLayer(Blue()),
            ImageLayer(solid(1, 1, red)), EffectLayer(Blue()),
        )
        assertEquals(0..2, groupRange(layers, 0))
        assertEquals(0..2, groupRange(layers, 2))
        assertEquals(3..4, groupRange(layers, 3))
        assertEquals(3..4, groupRange(layers, 4))
    }

    @Test
    fun `fit and cover scale pictures to the canvas`() {
        val layer = ImageLayer(solid(200, 100, red))
        layer.fitInto(100, 80)
        assertEquals(0.5, layer.scale)
        assertEquals(15.0, layer.y, "zentriert")
        layer.fitInto(100, 80, cover = true)
        assertEquals(0.8, layer.scale)
        layer.fitInto(1000, 800, onlyShrink = true)
        assertEquals(1.0, layer.scale, "kleine Bilder werden nicht vergrössert")
    }

    @Test
    fun `projects store several image layers and share identical pictures`() {
        val picture = solid(30, 20, green)
        val bottom = ImageLayer(solid(w, h, red)).apply { name = "Hintergrund" }
        val a = ImageLayer(picture).apply { x = 5.0; y = 6.0; scale = 1.5; smooth = false }
        val b = a.duplicate()
        val state = DocState(w, h, "stapel", listOf(bottom, a, EffectLayer(com.spielgrund.glitchr.effects.Effects.byId("rgb")), b).map { it.memento() })
        val file = File("target/test-output/stapel.glitchr")
        ProjectFile.save(state, file)
        val images = ZipFile(file).use { zip -> zip.entries().toList().count { it.name.startsWith("images/") } }
        assertEquals(2, images, "gleiches Bild wird nur einmal gespeichert")

        val loaded = ProjectFile.load(file)
        assertEquals(w, loaded.width)
        assertEquals(4, loaded.layers.size)
        val la = loaded.layers[1] as ImageMemento
        assertEquals(5.0, la.x)
        assertEquals(1.5, la.scale)
        assertEquals(false, la.smooth)
        assertSame(la.image, (loaded.layers[3] as ImageMemento).image)
        assertEquals("Hintergrund", loaded.layers[0].name)
    }
}
