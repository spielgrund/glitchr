package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.export.AnimFormat
import com.spielgrund.glitchr.export.AnimationExport
import com.spielgrund.glitchr.export.ExportSettings
import com.spielgrund.glitchr.export.Palette
import com.spielgrund.glitchr.model.Animator
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Renderer
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimationExportTest {
    private val src = testImage(97, 61)
    private val outDir = File("target/test-output/animation").apply { mkdirs() }

    /** An RGB shift that grows over 10 frames, rendered like playback does. */
    private fun frames(): (Int) -> com.spielgrund.glitchr.image.Pixels {
        val picture = ImageLayer(src)
        val fx = EffectLayer(Effects.byId("rgb"))
        fx.values["rx"] = 0
        Animator.addKey(fx, "rx", 0)
        fx.values["rx"] = 40
        Animator.captureEdits(listOf(fx), 9)
        val snapshots = listOf(picture.animated(), fx.animated())
        val renderer = Renderer()
        return { f -> renderer.render(src.width, src.height, snapshots.map { it.at(f) }) }
    }

    @Test
    fun `a GIF has every frame, the right delays and loops`() {
        val file = File(outDir, "shift.gif")
        var reported = 0
        AnimationExport.export(10, 25, ExportSettings(AnimFormat.GIF), file, frames()) { reported = it; true }
        assertEquals(10, reported)
        val reader = ImageIO.getImageReadersBySuffix("gif").next()
        ImageIO.createImageInputStream(file).use { stream ->
            reader.input = stream
            assertEquals(10, reader.getNumImages(true))
            val first = reader.read(0)
            assertEquals(97, first.width)
            val root = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            val gce = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
            assertEquals("4", gce.getAttribute("delayTime"), "25 fps = 4/100 s")
            assertTrue(root.getElementsByTagName("ApplicationExtension").length > 0, "loops")
            // later frames store only what changed: the shift grows, so they are not empty
            assertTrue(reader.read(9).width in 1..97)
        }
        reader.dispose()
    }

    @Test
    fun `fewer colors and storing only changes make smaller GIFs that look the same`() {
        // a mostly still picture with a small moving square
        val still = { f: Int ->
            com.spielgrund.glitchr.image.Pixels(120, 90, src.copy().let { s0 -> IntArray(120 * 90) { i -> s0.data[(i / 120 % 61) * 97 + (i % 120 % 97)] } }).also { p ->
                for (y in 10 until 20) for (x in 10 + f * 3 until 20 + f * 3) p.data[y * 120 + x] = -1
            }
        }
        fun size(settings: ExportSettings): Long {
            val file = File(outDir, "size.gif")
            AnimationExport.export(10, 25, settings, file, still) { true }
            return file.length()
        }
        val full = size(ExportSettings(AnimFormat.GIF, onlyChanges = false, dither = com.spielgrund.glitchr.export.Dither.NONE))
        val changes = size(ExportSettings(AnimFormat.GIF, onlyChanges = true, dither = com.spielgrund.glitchr.export.Dither.NONE))
        val few = size(ExportSettings(AnimFormat.GIF, onlyChanges = true, colors = 16, dither = com.spielgrund.glitchr.export.Dither.NONE))
        assertTrue(changes * 3 < full, "only changes: $changes against $full")
        assertTrue(few < changes, "16 colors: $few against $changes")

        // composed, the last frame looks the same either way
        fun lastFrame(onlyChanges: Boolean): IntArray {
            val file = File(outDir, "last.gif")
            // storing changes keeps one palette entry for "unchanged": the same 255 colors either way
            val colors = if (onlyChanges) 256 else 255
            AnimationExport.export(10, 25, ExportSettings(AnimFormat.GIF, onlyChanges = onlyChanges, colors = colors, dither = com.spielgrund.glitchr.export.Dither.NONE), file, still) { true }
            val reader = ImageIO.getImageReadersBySuffix("gif").next()
            val canvas = java.awt.image.BufferedImage(120, 90, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            ImageIO.createImageInputStream(file).use { stream ->
                reader.input = stream
                for (i in 0 until reader.getNumImages(true)) {
                    val root = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                    val d = root.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                    canvas.createGraphics().drawImage(reader.read(i), d.getAttribute("imageLeftPosition").toInt(), d.getAttribute("imageTopPosition").toInt(), null)
                }
            }
            reader.dispose()
            return canvas.getRGB(0, 0, 120, 90, null, 0, 120)
        }
        kotlin.test.assertContentEquals(lastFrame(false), lastFrame(true))
    }

    @Test
    fun `the GIF palette keeps the colors at least as close as Java's own GIF writer`() {
        val img = AnimationExport.prepare(src, 1.0, 0)
        fun error(other: java.awt.image.BufferedImage): Double {
            var sum = 0L
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val a = img.getRGB(x, y)
                val b = other.getRGB(x, y)
                sum += listOf(16, 8, 0).sumOf { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) }
            }
            return sum.toDouble() / (img.width * img.height)
        }
        val ours = error(Palette.of(listOf(img)).map(img, com.spielgrund.glitchr.export.Dither.NONE))
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "gif", it) }.toByteArray()
        val java = error(ImageIO.read(bytes.inputStream()))
        assertTrue(ours <= java, "ours $ours, Java $java")
    }

    @Test
    fun `an MP4 is written with even sides`() {
        val file = File(outDir, "shift.mp4")
        file.delete()
        AnimationExport.export(10, 25, ExportSettings(AnimFormat.MP4), file, frames()) { true }
        assertTrue(file.length() > 1000, "${file.length()} bytes")
        assertEquals(98, AnimationExport.prepare(src, 1.0, 0, evenSize = true).width)
    }

    @Test
    fun `a PNG sequence keeps transparency, scaled, and stops when cancelled`() {
        val file = File(outDir, "seq.png")
        val written = AnimationExport.export(10, 25, ExportSettings(AnimFormat.PNG, scale = 0.5), file, frames()) { it < 4 }
        assertEquals(4, written.size)
        assertEquals("seq_0001.png", written.first().name)
        val img = ImageIO.read(written.first())
        assertEquals(49, img.width)
        assertTrue(img.colorModel.hasAlpha())
    }
}
