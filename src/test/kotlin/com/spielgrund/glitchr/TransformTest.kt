package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Generator
import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.effects.Transform
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.ImageMemento
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.MaskSpace
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransformTest {
    private val red = argb(255, 255, 0, 0)
    private val blue = argb(255, 0, 0, 255)

    /** 40 × 20: left half red, right half blue. */
    private val halves = Pixels(40, 20, IntArray(800) { if (it % 40 < 20) red else blue })

    private fun transform(src: Pixels, changes: Map<String, Int>) =
        Transform.apply(src, Transform.defaultValues(mapOf("rotation" to 0, "smooth" to 0) + changes), 0)

    @Test
    fun `nothing set leaves the picture alone`() {
        assertContentEquals(halves.data, transform(halves, emptyMap()).data)
    }

    @Test
    fun `moving, turning and mirroring`() {
        // 10 px to the right: transparent on the left, the red half reaches to x = 29
        val moved = transform(halves, mapOf("offsetX" to 100))
        assertEquals(0, moved[5, 10])
        assertEquals(red, moved[25, 10])
        assertEquals(blue, moved[35, 10])
        // turned by 180°: blue on the left
        val turned = transform(halves, mapOf("rotation" to 1800))
        assertEquals(blue, turned[5, 10])
        assertEquals(red, turned[35, 10])
        // mirrored in X: the same
        assertContentEquals(turned.data, transform(halves, mapOf("mirrorX" to 1)).data)
        // halved: the picture sits in the middle, repeated around it with "Repeat"
        val small = transform(halves, mapOf("scale" to 500, "edge" to 1))
        assertEquals(red, small[12, 10])
        assertEquals(blue, small[25, 10])
        assertEquals(blue, small[5, 10])
    }

    @Test
    fun `generators can be moved and repeat at the edge`() {
        val g = Generators.byId("pattern")!!
        val plain = g.generate(60, 40, g.defaultValues(), 0)
        // transparent by default
        assertEquals(0, g.generate(60, 40, g.defaultValues(mapOf(Generator.POS_X to 100)), 0)[5, 5])
        val moved = g.generate(60, 40, g.defaultValues(mapOf(Generator.POS_X to 100, Generator.POS_EDGE to 1)), 0)
        // moved by 10 px, what leaves on the right comes back on the left
        for (y in 0 until 40) for (x in 0 until 60) assertEquals(plain[(x - 10 + 60) % 60, y], moved[x, y])
    }

    @Test
    fun `the mask of a generator moves with its position`() {
        val gen = com.spielgrund.glitchr.model.GeneratorLayer(Generators.byId("color")!!).apply {
            values[Generator.BASE] = 0xFF0000
            mask.mode = MaskMode.LINEAR
            // full on the left of the picture, nothing on the right
            mask.linearStart = RelPoint(0.0, 0.5)
            mask.linearEnd = RelPoint(1.0, 0.5)
        }
        val still = Renderer().render(100, 50, listOf(gen.state()))
        // moved 50 px to the right: the mask's full end moves along
        gen.values[Generator.POS_X] = 500
        val moved = Renderer().render(100, 50, listOf(gen.state()))
        assertTrue(still[5, 25] ushr 24 > 230)
        assertEquals(0, moved[5, 25] ushr 24) // nothing there any more (transparent edge)
        assertTrue(moved[55, 25] ushr 24 > 230) // the full end now sits here
        // at x = 95 the unmoved mask has almost run out, the moved one is only half way
        assertTrue(still[95, 25] ushr 24 < 30)
        assertTrue((moved[95, 25] ushr 24) in 110..170)
    }

    @Test
    fun `a turned picture layer is drawn turned around its middle`() {
        val layer = ImageLayer(halves).apply { x = 30.0; y = 40.0; rotation = 90.0 }
        val out = Renderer().render(100, 100, listOf(layer.state()))
        // the middle stays at (50, 50); turned clockwise by 90°, the red (left) half is now on top
        assertEquals(red, out[50, 40])
        assertEquals(blue, out[50, 60])
        // the unturned area left and right of the middle is empty now
        assertEquals(0, out[33, 50])
    }

    @Test
    fun `masks turn with their picture`() {
        // a painted mask covering only the picture's left (red) half, on a turned picture
        val layer = ImageLayer(halves).apply {
            x = 30.0; y = 40.0; rotation = 90.0
            mask.mode = MaskMode.BRUSH
            mask.ensurePainted(40, 20)
            mask.dab(10.0, 10.0, 14.0, 1f, 1f, erase = false)
        }
        val out = Renderer().render(100, 100, listOf(layer.state()))
        // the kept part is the red half, now above the middle; the blue half below is cut away
        assertEquals(red, out[50, 40])
        assertEquals(0, out[50, 60] ushr 24)

        // gradients too: a linear mask from the picture's left (full) to its right (none)
        val space = MaskSpace(30.0, 40.0, 40.0, 20.0, Math.toRadians(90.0))
        val linear = ImageLayer(halves).apply {
            x = 30.0; y = 40.0; rotation = 90.0
            mask.mode = MaskMode.LINEAR
            mask.linearStart = RelPoint(0.0, 0.5)
            mask.linearEnd = RelPoint(1.0, 0.5)
        }
        val row = FloatArray(100)
        linear.mask.shape().row(35, 100, 100, row, space)
        val top = row[50]
        linear.mask.shape().row(65, 100, 100, row, space)
        val bottom = row[50]
        assertTrue(top > 0.8f && bottom < 0.2f, "top $top, bottom $bottom")
        // and effect masks of the group lie on the turned picture as well
        val fx = EffectLayer(Transform).apply {
            values.putAll(mapOf("rotation" to 0, "mirrorX" to 1))
            mask.mode = MaskMode.LINEAR
            mask.linearStart = RelPoint(0.0, 0.5)
            mask.linearEnd = RelPoint(1.0, 0.5)
        }
        assertEquals(space, Renderer.spaceOf(layer.state()))
        Renderer().render(100, 100, listOf(layer.state(), fx.state()))
    }

    @Test
    fun `the turn is saved in the project`() {
        val layer = ImageLayer(halves).apply { rotation = 33.5; scale = 1.5 }
        val file = File("target/test-output/turned.glitchr").apply { parentFile.mkdirs() }
        ProjectFile.save(DocState(100, 100, "gedreht", listOf(layer.memento())), file)
        val loaded = ProjectFile.load(file).layers.single() as ImageMemento
        assertEquals(33.5, loaded.rotation)
        assertEquals(1.5, loaded.scale)
    }
}
