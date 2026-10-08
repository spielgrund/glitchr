package com.spielgrund.glitchr.export

import com.spielgrund.glitchr.image.Pixels
import org.jcodec.api.awt.AWTSequenceEncoder
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.roundToInt

enum class AnimFormat(val label: String, val extension: String) {
    GIF("GIF", "gif"),
    MP4("MP4 (H.264)", "mp4"),
    PNG("PNG sequence", "png");

    override fun toString() = label
}

/**
 * How an animation is written: [scale] of the canvas size, [background] (0xRRGGBB) under
 * transparent parts for GIF and MP4, GIF with [dither] and endless [loop].
 */
data class ExportSettings(
    val format: AnimFormat,
    val scale: Double = 1.0,
    val background: Int = 0x000000,
    val dither: Boolean = true,
    val loop: Boolean = true,
)

/** Writes rendered frames as GIF, MP4 or numbered PNGs. */
object AnimationExport {
    /** Frames the GIF palette is built from, spread over the animation. */
    private const val PALETTE_SAMPLES = 8

    /**
     * Renders frames 0 until [frames] with [render] (on the calling thread) and writes them to
     * [file]; for a PNG sequence [file] is the first name, the others are numbered alike.
     * [progress] gets the number of finished frames and returns false to cancel.
     * Returns the files written.
     */
    fun export(
        frames: Int, fps: Int, settings: ExportSettings, file: File,
        render: (Int) -> Pixels, progress: (Int) -> Boolean,
    ): List<File> = when (settings.format) {
        AnimFormat.GIF -> listOf(writeGif(frames, fps, settings, file, render, progress))
        AnimFormat.MP4 -> listOf(writeMp4(frames, fps, settings, file, render, progress))
        AnimFormat.PNG -> writePngs(frames, settings, file, render, progress)
    }

    /** The picture scaled by [scale], on [background] if given (else with its transparency). */
    fun prepare(p: Pixels, scale: Double, background: Int?, evenSize: Boolean = false): BufferedImage {
        var w = (p.width * scale).roundToInt().coerceAtLeast(1)
        var h = (p.height * scale).roundToInt().coerceAtLeast(1)
        if (evenSize) {
            // H.264 needs even sides: one more pixel of background at most
            w += w % 2
            h += h % 2
        }
        val img = BufferedImage(w, h, if (background == null) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        if (background != null) {
            g.color = java.awt.Color(background)
            g.fillRect(0, 0, w, h)
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(p.toImage(), 0, 0, (p.width * scale).roundToInt().coerceAtLeast(1), (p.height * scale).roundToInt().coerceAtLeast(1), null)
        g.dispose()
        return img
    }

    private fun withExtension(file: File, ext: String) =
        if (file.extension.equals(ext, ignoreCase = true)) file else File(file.parentFile, "${file.name}.$ext")

    // ------------------------------------------------------------------ PNG

    private fun writePngs(frames: Int, s: ExportSettings, file: File, render: (Int) -> Pixels, progress: (Int) -> Boolean): List<File> {
        val base = withExtension(file, "png")
        val stem = base.nameWithoutExtension
        val digits = maxOf(4, frames.toString().length)
        val written = mutableListOf<File>()
        for (f in 0 until frames) {
            val out = File(base.parentFile, "${stem}_${(f + 1).toString().padStart(digits, '0')}.png")
            ImageIO.write(prepare(render(f), s.scale, null), "png", out)
            written += out
            if (!progress(f + 1)) break
        }
        return written
    }

    // ------------------------------------------------------------------ MP4

    private fun writeMp4(frames: Int, fps: Int, s: ExportSettings, file: File, render: (Int) -> Pixels, progress: (Int) -> Boolean): File {
        val out = withExtension(file, "mp4")
        val encoder = AWTSequenceEncoder.createSequenceEncoder(out, fps)
        try {
            for (f in 0 until frames) {
                encoder.encodeImage(prepare(render(f), s.scale, s.background, evenSize = true))
                if (!progress(f + 1)) break
            }
        } finally {
            encoder.finish()
        }
        return out
    }

    // ------------------------------------------------------------------ GIF

    private fun writeGif(frames: Int, fps: Int, s: ExportSettings, file: File, render: (Int) -> Pixels, progress: (Int) -> Boolean): File {
        val out = withExtension(file, "gif")
        // one palette for the whole animation, from frames spread over it, so colors don't flicker
        val samples = (0 until PALETTE_SAMPLES).map { (it * (frames - 1).toDouble() / (PALETTE_SAMPLES - 1)).roundToInt() }.distinct()
        val kept = HashMap<Int, BufferedImage>()
        for (f in samples) kept[f] = prepare(render(f), s.scale, s.background)
        val palette = Palette.of(kept.values.toList())

        val writer = ImageIO.getImageWritersBySuffix("gif").next()
        out.delete()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            for (f in 0 until frames) {
                val rgb = kept.remove(f) ?: prepare(render(f), s.scale, s.background)
                val indexed = palette.map(rgb, s.dither)
                // GIF counts in hundredths of a second: spread the rounding so the total stays right
                val delay = (100.0 * (f + 1) / fps).roundToInt() - (100.0 * f / fps).roundToInt()
                writer.writeToSequence(IIOImage(indexed, null, gifMetadata(writer, indexed, delay, s.loop && f == 0)), writer.defaultWriteParam)
                if (!progress(f + 1)) break
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out
    }

    private fun gifMetadata(writer: javax.imageio.ImageWriter, img: BufferedImage, delay: Int, loop: Boolean): javax.imageio.metadata.IIOMetadata {
        val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), writer.defaultWriteParam)
        val format = meta.nativeMetadataFormatName
        val root = meta.getAsTree(format) as IIOMetadataNode
        node(root, "GraphicControlExtension").apply {
            setAttribute("disposalMethod", "none")
            setAttribute("userInputFlag", "FALSE")
            setAttribute("transparentColorFlag", "FALSE")
            setAttribute("delayTime", delay.coerceAtLeast(1).toString())
            setAttribute("transparentColorIndex", "0")
        }
        if (loop) {
            val ext = IIOMetadataNode("ApplicationExtension").apply {
                setAttribute("applicationID", "NETSCAPE")
                setAttribute("authenticationCode", "2.0")
                // sub-block 1, loop count 0 = forever
                userObject = byteArrayOf(1, 0, 0)
            }
            node(root, "ApplicationExtensions").appendChild(ext)
        }
        meta.setFromTree(format, root)
        return meta
    }

    private fun node(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) if (root.item(i).nodeName == name) return root.item(i) as IIOMetadataNode
        return IIOMetadataNode(name).also(root::appendChild)
    }
}

/**
 * Up to 256 colors found by median cut, and a lookup from 6 bits per channel to the
 * nearest of them. [map] turns a picture into an indexed one, with Floyd–Steinberg
 * dithering if asked.
 */
class Palette private constructor(private val colors: IntArray) {
    private val lut = ByteArray(64 * 64 * 64)

    init {
        for (r in 0 until 64) for (g in 0 until 64) for (b in 0 until 64) {
            // the middle of the cell
            val cr = r * 4 + 2
            val cg = g * 4 + 2
            val cb = b * 4 + 2
            var best = 0
            var bestD = Int.MAX_VALUE
            for ((i, c) in colors.withIndex()) {
                val dr = cr - (c shr 16 and 0xFF)
                val dg = cg - (c shr 8 and 0xFF)
                val db = cb - (c and 0xFF)
                val d = dr * dr * 2 + dg * dg * 3 + db * db
                if (d < bestD) { bestD = d; best = i }
            }
            lut[(r shl 12) or (g shl 6) or b] = best.toByte()
        }
    }

    private fun index(r: Int, g: Int, b: Int) = lut[((r.coerceIn(0, 255) shr 2) shl 12) or ((g.coerceIn(0, 255) shr 2) shl 6) or (b.coerceIn(0, 255) shr 2)].toInt() and 0xFF

    fun map(img: BufferedImage, dither: Boolean): BufferedImage {
        val w = img.width
        val h = img.height
        val rgb = img.getRGB(0, 0, w, h, null, 0, w)
        val idx = ByteArray(w * h)
        if (!dither) {
            for (i in rgb.indices) { val c = rgb[i]; idx[i] = index(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF).toByte() }
        } else {
            // errors carried to the right and to the next row
            var cur = IntArray((w + 2) * 3)
            var next = IntArray((w + 2) * 3)
            for (y in 0 until h) {
                java.util.Arrays.fill(next, 0)
                for (x in 0 until w) {
                    val c = rgb[y * w + x]
                    val k = (x + 1) * 3
                    val r = (c shr 16 and 0xFF) + cur[k] / 16
                    val g = (c shr 8 and 0xFF) + cur[k + 1] / 16
                    val b = (c and 0xFF) + cur[k + 2] / 16
                    val i = index(r, g, b)
                    idx[y * w + x] = i.toByte()
                    val p = colors[i]
                    val er = r.coerceIn(0, 255) - (p shr 16 and 0xFF)
                    val eg = g.coerceIn(0, 255) - (p shr 8 and 0xFF)
                    val eb = b.coerceIn(0, 255) - (p and 0xFF)
                    cur[k + 3] += er * 7; cur[k + 4] += eg * 7; cur[k + 5] += eb * 7
                    next[k - 3] += er * 3; next[k - 2] += eg * 3; next[k - 1] += eb * 3
                    next[k] += er * 5; next[k + 1] += eg * 5; next[k + 2] += eb * 5
                    next[k + 3] += er; next[k + 4] += eg; next[k + 5] += eb
                }
                val t = cur; cur = next; next = t
            }
        }
        val n = colors.size
        val model = IndexColorModel(8, n, ByteArray(n) { (colors[it] shr 16).toByte() }, ByteArray(n) { (colors[it] shr 8).toByte() }, ByteArray(n) { colors[it].toByte() })
        val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, model)
        out.raster.setDataElements(0, 0, w, h, idx)
        return out
    }

    companion object {
        /** The palette of [images] (a sample of their pixels). */
        fun of(images: List<BufferedImage>, size: Int = 256): Palette {
            val total = images.sumOf { it.width.toLong() * it.height }
            val step = maxOf(1L, total / 300_000).toInt()
            val pixels = ArrayList<Int>()
            for (img in images) {
                val data = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
                var i = 0
                while (i < data.size) { pixels += data[i] and 0xFFFFFF; i += step }
            }
            return Palette(medianCut(pixels.toIntArray(), size))
        }

        private fun medianCut(pixels: IntArray, size: Int): IntArray {
            if (pixels.isEmpty()) return intArrayOf(0)
            class Box(val px: IntArray) {
                /** Summed squared deviation of a channel: how much splitting along it helps. */
                fun spread(shift: Int): Double {
                    var sum = 0.0; var sq = 0.0
                    for (c in px) { val v = (c shr shift and 0xFF).toDouble(); sum += v; sq += v * v }
                    return sq - sum * sum / px.size
                }
                val spreads by lazy { listOf(16, 8, 0).associateWith(::spread) }
                val widest by lazy { spreads.maxBy { it.value }.key }
                val score by lazy { spreads.values.sum() }
                fun average(): Int {
                    var r = 0L; var g = 0L; var b = 0L
                    for (c in px) { r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF }
                    val n = px.size
                    return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                }
            }
            val boxes = mutableListOf(Box(pixels))
            while (boxes.size < size) {
                val box = boxes.filter { it.px.size > 1 }.maxByOrNull { it.score } ?: break
                if (box.score <= 0.0) break
                val shift = box.widest
                val sorted = box.px.sortedBy { it shr shift and 0xFF }.toIntArray()
                val mid = sorted.size / 2
                boxes.remove(box)
                boxes += Box(sorted.copyOfRange(0, mid))
                boxes += Box(sorted.copyOfRange(mid, sorted.size))
            }
            return boxes.map { it.average() }.distinct().toIntArray()
        }
    }
}
