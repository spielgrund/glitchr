package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.model.BlendMode
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.EffectMemento
import com.spielgrund.glitchr.model.ImageMemento
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectFileTest {
    private val src = testImage(80, 60)
    private val dir = File("target/test-output").apply { mkdirs() }

    @Test
    fun `saved project loads with identical layers, masks and result`() {
        val sort = EffectLayer(Effects.byId("pixelsort")).apply {
            name = "Stripes"
            values["angle"] = 33
            opacity = 70
            blend = BlendMode.LIGHTEN
            mask.mode = MaskMode.BRUSH
            mask.ensurePainted(src.width, src.height)
            mask.dab(30.0, 20.0, 12.0, 0.3f, 1f, erase = false)
        }
        val mosh = EffectLayer(Effects.byId("datamosh")).apply {
            visible = false
            mask.mode = MaskMode.RADIAL
            mask.invert = true
            mask.radialEdge = RelPoint(0.9, 0.2)
        }
        val state = docState(src, "testbild", listOf(sort.memento(), mosh.memento())).apply { selectedId = sort.id }
        val file = File(dir, "roundtrip.glitchr")
        ProjectFile.save(state, file)

        val loaded = ProjectFile.load(file)
        assertEquals("testbild", loaded.name)
        assertEquals(src.width, loaded.width)
        assertContentEquals(src.data, (loaded.layers[0] as ImageMemento).image.data)
        assertEquals(3, loaded.layers.size)
        assertEquals(loaded.layers[1].id, loaded.selectedId)

        val a = loaded.layers[1] as EffectMemento
        val b = loaded.layers[2] as EffectMemento
        assertEquals("Stripes", a.name)
        assertEquals(sort.values, a.values)
        assertEquals(sort.seed, a.seed)
        assertEquals(70, a.opacity)
        assertEquals(BlendMode.LIGHTEN, a.blend)
        assertContentEquals(sort.mask.painted, a.mask.painted)
        assertEquals(false, b.visible)
        assertEquals(MaskMode.RADIAL, b.mask.mode)
        assertEquals(true, b.mask.invert)
        assertEquals(RelPoint(0.9, 0.2), b.mask.radialEdge)

        val before = Renderer().render(src, listOf(sort.state(), mosh.state()))
        val after = Renderer().render(loaded.width, loaded.height, loaded.layers.map { it.toLayer().state() })
        assertContentEquals(before.data, after.data)
    }

    @Test
    fun `unknown settings are ignored and missing ones take defaults`() {
        val file = File(dir, "old.glitchr")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write("""{"format":"glitchr","version":1,"layers":[{"effect":"rgb","values":{"rx":9999,"gone":5}}]}""".toByteArray())
            zip.putNextEntry(ZipEntry("source.png"))
            javax.imageio.ImageIO.write(src.toImage(), "png", zip)
        }
        val loaded = ProjectFile.load(file)
        assertEquals(2, loaded.layers.size, "version 1: the source image becomes the lowest image layer")
        assertContentEquals(src.data, (loaded.layers[0] as ImageMemento).image.data)
        val layer = loaded.layers[1] as EffectMemento
        assertEquals(300, layer.values["rx"], "limited to the region")
        assertEquals(0, layer.values["ry"], "default value")
        assertEquals(null, layer.values["gone"])
        assertEquals(MaskMode.OFF, layer.mask.mode)
    }

    @Test
    fun `other files are rejected with a message`() {
        val file = File(dir, "kaputt.glitchr").apply { writeText("no zip") }
        assertFailsWith<IllegalStateException> { ProjectFile.load(file) }
    }
}
