package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Generator
import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.GeneratorLayer
import com.spielgrund.glitchr.model.GeneratorMemento
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.model.groupRange
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeneratorTest {
    private val dir = File("target/test-output/generators").apply { mkdirs() }

    @Test
    fun `every generator makes an opaque picture in canvas size, patterns are not flat`() {
        for (g in Generators.all) {
            val out = g.generate(160, 120, g.defaultValues(), 42L)
            assertEquals(160, out.width, g.name)
            assertEquals(120, out.height, g.name)
            ImageIO.write(out.toImage(), "png", File(dir, "${g.id}.png"))
            assertTrue(out.data.all { alpha(it) == 255 }, "${g.name} is not opaque")
            val colors = out.data.toSet().size
            if (g.effect == null) assertEquals(1, colors) else assertTrue(colors > 2, "${g.name} is a single color")
        }
    }

    @Test
    fun `hidden settings keep their fixed values, the base color is a setting`() {
        val noise = Generators.byId("noise")!!
        assertTrue(noise.params.none { it.key == "imageShape" || it.key == "control" })
        assertEquals(Generator.BASE, noise.params.first().key)
        assertEquals(0, noise.defaults()["imageShape"])
        assertEquals(3, noise.defaults()["control"])
        val flat = Generators.byId("color")!!
        val out = flat.generate(4, 4, flat.defaultValues(mapOf(Generator.BASE to 0x123456)), 0)
        assertTrue(out.data.all { it == 0xFF123456.toInt() })
    }

    @Test
    fun `noise starts in black and white`() {
        val noise = Generators.byId("noise")!!
        val out = noise.generate(80, 60, noise.defaultValues(), 3L)
        assertTrue(out.data.all { red(it) == green(it) && green(it) == blue(it) })
    }

    @Test
    fun `gradient runs from color 1 to color 2, has no base color and can be stepped`() {
        val g = Generators.byId("gradient")!!
        assertTrue(g.params.none { it.key == Generator.BASE })
        // top to bottom (90°), black to white
        val out = g.generate(50, 100, g.defaultValues(mapOf("dither" to 0)), 0)
        assertTrue(red(out[25, 0]) < 5)
        assertTrue(red(out[25, 99]) > 250)
        assertTrue((0 until 99).all { red(out[10, it]) <= red(out[10, it + 1]) })
        val stepped = g.generate(50, 100, g.defaultValues(mapOf("steps" to 4)), 0)
        assertEquals(4, stepped.data.toSet().size)
        for (type in 0..5) {
            val shape = g.generate(60, 40, g.defaultValues(mapOf("type" to type, "repeats" to 3)), 0)
            assertTrue(shape.data.toSet().size > 10, "type $type")
        }
    }

    @Test
    fun `moire of two slightly turned line patterns shows broad bands`() {
        val g = Generators.byId("moire")!!
        assertTrue(g.params.none { it.key == Generator.BASE })
        for (type in 0..7) {
            val out = g.generate(80, 60, g.defaultValues(mapOf("aType" to type, "bType" to type)), 0)
            assertTrue(out.data.toSet().size > 2, "pattern $type")
        }
        // blurred until only the moire is left: still clearly light and dark areas
        val bands = g.generate(300, 200, g.defaultValues(mapOf("blur" to 6)), 0)
        val lumas = bands.data.map { red(it) }
        assertTrue(lumas.max() - lumas.min() > 150)
        // the same pattern twice without turn gives no moire: blurred it is flat
        val flat = g.generate(300, 200, g.defaultValues(mapOf("blur" to 6, "bAngle" to 0, "autoContrast" to 0)), 0)
        val inner = (20 until 180).flatMap { y -> (20 until 280).map { x -> red(flat[x, y]) } }
        assertTrue(inner.max() - inner.min() < 20)
    }

    @Test
    fun `moire stretch pulls a pattern apart along its own axes`() {
        val g = Generators.byId("moire")!!
        // B in the background color, so only A shows; no smoothing, so every edge is one step
        val base = mapOf("colorB" to 0xFFFFFF, "smoothing" to 0)
        fun lines(p: com.spielgrund.glitchr.image.Pixels) = (1 until p.height).count { red(p[5, it]) != red(p[5, it - 1]) }
        val normal = g.generate(40, 200, g.defaultValues(base), 0)
        // horizontal lines stretched in Y: half as many edges down the picture
        val stretched = g.generate(40, 200, g.defaultValues(base + ("aStretchY" to 200)), 0)
        assertTrue(lines(stretched) in lines(normal) * 4 / 10..lines(normal) * 6 / 10)
        // stretching along the lines changes nothing
        val along = g.generate(40, 200, g.defaultValues(base + ("aStretchX" to 300)), 0)
        assertContentEquals(normal.data, along.data)
    }

    @Test
    fun `overprinted lines stay visible on a dark background, crossings mix`() {
        val g = Generators.byId("moire")!!
        val v = mapOf("background" to 0x000000, "colorA" to 0xFFFF00, "colorB" to 0x00FFFF, "smoothing" to 0)
        val out = g.generate(200, 150, g.defaultValues(v), 0)
        val colors = out.data.map { it and 0xFFFFFF }.toSet()
        assertEquals(setOf(0x000000, 0xFFFF00, 0x00FFFF, 0x00FF00), colors)
    }

    @Test
    fun `mandala is rotation symmetric, a new seed gives a new one`() {
        val g = Generators.byId("mandala")!!
        for (style in 0..2) for (motif in 0..7) {
            val v = g.defaultValues(mapOf("style" to style, "motif" to motif, "symmetry" to 8, "rotation" to 0))
            val out = g.generate(201, 201, v, 5L)
            assertTrue(out.data.toSet().size > 2, "style $style, motif $motif")
            // 8-fold: turned by 90° it is the same picture (up to antialiasing)
            var diff = 0L
            for (y in 0 until 201) for (x in 0 until 201) diff += kotlin.math.abs(red(out[x, y]) - red(out[200 - y, x]))
            assertTrue(diff / (201 * 201) < 6, "style $style, motif $motif: $diff")
        }
        val a = g.generate(120, 120, g.defaultValues(), 1L)
        val b = g.generate(120, 120, g.defaultValues(), 2L)
        assertTrue(!a.data.contentEquals(b.data))
    }

    @Test
    fun `spirograph draws closed curves inside and outside, with several passes`() {
        val g = Generators.byId("spirograph")!!
        for (mode in 0..1) for (colorMode in 0..4) {
            val out = g.generate(160, 160, g.defaultValues(mapOf("mode" to mode, "colorMode" to colorMode, "passes" to 3)), 0)
            val background = out.data.count { it == 0xFFFAF7F0.toInt() }
            assertTrue(background in 1 until out.data.size * 95 / 100, "type $mode, colors $colorMode")
        }
        // 96 and 63 teeth share 3: the wheel goes round 21 times (63 / 3) until the curve closes;
        // half of it is clearly less ink
        val full = g.generate(200, 200, g.defaultValues(), 0)
        val half = g.generate(200, 200, g.defaultValues(mapOf("portion" to 50)), 0)
        fun ink(p: com.spielgrund.glitchr.image.Pixels) = p.data.count { it != 0xFFFAF7F0.toInt() }
        assertTrue(ink(half) < ink(full) * 0.7)
    }

    @Test
    fun `stroke length changes the Y pattern`() {
        val g = Generators.byId("geometric")!!
        val y = mapOf("pattern" to 9) // Y-Muster
        val normal = g.generate(120, 120, g.defaultValues(y), 0)
        val short = g.generate(120, 120, g.defaultValues(y + ("length" to 40)), 0)
        fun dark(p: com.spielgrund.glitchr.image.Pixels) = p.data.count { red(it) < 128 }
        assertTrue(dark(short) < dark(normal) * 0.7)
    }

    @Test
    fun `same settings and seed give the same picture`() {
        for (g in Generators.all) {
            val a = g.generate(64, 48, g.defaultValues(), 7L)
            val b = g.generate(64, 48, g.defaultValues(), 7L)
            assertContentEquals(a.data, b.data, g.name)
        }
    }

    @Test
    fun `effects above a generator work on its picture`() {
        val gen = GeneratorLayer(Generators.byId("pattern")!!)
        val fx = EffectLayer(Effects.byId("rgb"))
        val layers = listOf(gen, fx)
        assertEquals(0..1, groupRange(layers, 1))

        val renderer = Renderer()
        val plain = renderer.render(90, 70, listOf(gen.state()))
        val generated = assertNotNull(renderer.generated(gen.id))
        assertContentEquals(generated.data, plain.data)

        val withFx = renderer.render(90, 70, layers.map { it.state() })
        // the picture is cached and not generated anew for the effect
        assertSame(generated, renderer.generated(gen.id))
        assertTrue(!withFx.data.contentEquals(plain.data))
    }

    @Test
    fun `generator layers are saved and loaded with their settings`() {
        val gen = GeneratorLayer(Generators.byId("chars")!!).apply {
            name = "Text"
            values["cell"] = 24
            values[Generator.BASE] = 0x102030
            texts["text"] = "HELLO"
            opacity = 80
        }
        val file = File(dir, "generator.glitchr")
        ProjectFile.save(DocState(120, 80, "without image", listOf(gen.memento())), file)
        val loaded = ProjectFile.load(file)
        assertEquals(120, loaded.width)
        val m = loaded.layers.single() as GeneratorMemento
        assertSame(gen.generator, m.generator)
        assertEquals("Text", m.name)
        assertEquals(gen.values, m.values)
        assertEquals("HELLO", m.texts["text"])
        assertEquals(gen.seed, m.seed)
        assertEquals(80, m.opacity)

        val a = Renderer().render(120, 80, listOf(gen.state()))
        val b = Renderer().render(120, 80, listOf(m.toLayer().state()))
        assertContentEquals(a.data, b.data)
    }
}
