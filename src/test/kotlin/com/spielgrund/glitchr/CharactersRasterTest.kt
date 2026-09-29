package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Characters
import com.spielgrund.glitchr.effects.GridModules
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.EffectMemento
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharactersRasterTest {
    private val src = testImage(240, 160)
    private val dir = File("target/test-output").apply { mkdirs() }

    @Test
    fun `own text changes the picture and every character set draws something`() {
        val a = Characters.apply(src, Characters.defaultValues(mapOf("set" to 7), mapOf("text" to "AAAA")), 0)
        val b = Characters.apply(src, Characters.defaultValues(mapOf("set" to 7), mapOf("text" to "WXYZ")), 0)
        assertFalse(a.data.contentEquals(b.data), "anderer Text, anderes Bild")
        for (set in 0..7) {
            val out = Characters.apply(src, Characters.defaultValues(mapOf("set" to set)), 0)
            assertTrue(out.data.any { it != 0xFF000000.toInt() }, "Zeichensatz $set zeichnet nichts")
            ImageIO.write(out.toImage(), "png", File(dir, "chars-$set.png"))
        }
    }

    @Test
    fun `letter and line spacing spread the characters out`() {
        fun ink(vararg changes: Pair<String, Int>) = Characters.apply(src, Characters.defaultValues(mapOf("set" to 1, *changes)), 0)
            .data.count { it != 0xFF000000.toInt() }
        val normal = ink()
        assertTrue(ink("letterSpacing" to 200) < normal * 0.6, "Zeichenabstand: weniger Zeichen")
        assertTrue(ink("lineSpacing" to 300) < normal * 0.6, "Zeilenabstand: weniger Zeilen")
        assertTrue(ink("letterSpacing" to -50, "lineSpacing" to 50) > normal, "enger: mehr Zeichen")
    }

    @Test
    fun `text is kept by the renderer cache key, undo and projects`() {
        val layer = EffectLayer(Characters).apply { values["set"] = 7; texts["text"] = "HALLO" }
        val renderer = Renderer()
        val first = renderer.render(src, listOf(layer.state()))
        layer.texts["text"] = "WELT"
        assertFalse(first.data.contentEquals(renderer.render(src, listOf(layer.state())).data), "Textänderung wird neu berechnet")

        val file = File(dir, "text.glitchr")
        ProjectFile.save(docState(src, "t", listOf(layer.memento())), file)
        val loaded = ProjectFile.load(file).layers.last() as EffectMemento
        assertEquals("WELT", loaded.texts["text"])
        assertEquals("WELT", (loaded.toLayer() as EffectLayer).texts["text"])
    }

    @Test
    fun `raster without picture influence is a plain pattern`() {
        for (module in 0..4) {
            val out = GridModules.apply(src, GridModules.defaultValues(mapOf("module" to module, "influence" to 0, "cell" to 20)), 0)
            // every full cell looks the same (inside; lines on the border reach into the neighbors)
            for (cy in 0 until 7) for (cx in 0 until 11) for (y in 1 until 19) for (x in 1 until 19) {
                assertEquals(out[x, y], out[cx * 20 + x, cy * 20 + y], "Modul $module, Zelle $cx,$cy")
            }
        }
        for (module in 0..5) {
            ImageIO.write(GridModules.apply(src, GridModules.defaultValues(mapOf("module" to module)), 0).toImage(), "png", File(dir, "grid-$module.png"))
        }
    }
}
