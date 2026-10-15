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
    val dither: Dither = Dither.ORDERED,
    val loop: Boolean = true,
    /** GIF: colors in the palette, 2..256; fewer make smaller files. */
    val colors: Int = 256,
    /** GIF: every frame stores only what changed since the one before; unchanged frames are merged. */
    val onlyChanges: Boolean = true,
    /** GIF with [onlyChanges]: pixels that moved less than this (per channel, roughly) count as unchanged. */
    val tolerance: Int = 0,
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
        // one palette for the whole animation, from frames spread over it, so colors don't flicker;
        // when only changes are stored, one entry is kept free for "unchanged" (transparent)
        val samples = (0 until PALETTE_SAMPLES).map { (it * (frames - 1).toDouble() / (PALETTE_SAMPLES - 1)).roundToInt() }.distinct()
        val kept = HashMap<Int, BufferedImage>()
        for (f in samples) kept[f] = prepare(render(f), s.scale, s.background)
        val colorCount = s.colors.coerceIn(2, 256)
        val palette = Palette.of(kept.values.toList(), if (s.onlyChanges) colorCount - 1 else colorCount)
        val clear = if (s.onlyChanges) palette.size else -1
        val entries = palette.size + if (s.onlyChanges) 1 else 0
        val tolerance = s.tolerance * s.tolerance * 3

        val writer = ImageIO.getImageWritersBySuffix("gif").next()
        out.delete()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            // what the viewer shows so far, as palette indices
            var shown: ByteArray? = null
            // a frame is written only when the next one is known: an unchanged next frame just lengthens it
            var pendingImage: BufferedImage? = null
            var pendingX = 0
            var pendingY = 0
            var pendingDelay = 0
            var first = true
            fun flush() {
                val img = pendingImage ?: return
                writer.writeToSequence(
                    IIOImage(img, null, gifMetadata(writer, img, pendingDelay, s.loop && first, pendingX, pendingY, clear)),
                    writer.defaultWriteParam,
                )
                first = false
                pendingImage = null
            }
            for (f in 0 until frames) {
                val rgbImage = kept.remove(f) ?: prepare(render(f), s.scale, s.background)
                val w = rgbImage.width
                val h = rgbImage.height
                val rgb = rgbImage.getRGB(0, 0, w, h, null, 0, w)
                val idx = palette.indices(rgb, w, h, s.dither)
                // GIF counts in hundredths of a second: spread the rounding so the total stays right
                val delay = (100.0 * (f + 1) / fps).roundToInt() - (100.0 * f / fps).roundToInt()
                val before = shown
                if (before == null || !s.onlyChanges) {
                    flush()
                    pendingImage = palette.image(idx, w, h, entries)
                    pendingX = 0
                    pendingY = 0
                    pendingDelay = delay
                    shown = idx
                } else {
                    // only the pixels that differ (by more than the tolerance) from what is shown
                    var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
                    val changed = BooleanArray(w * h)
                    for (i in idx.indices) {
                        if (idx[i] == before[i]) continue
                        if (tolerance > 0 && palette.distance(rgb[i], before[i].toInt() and 0xFF) <= tolerance) continue
                        changed[i] = true
                        val x = i % w
                        val y = i / w
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                    if (x1 < 0) {
                        // nothing changed: the frame before simply stays longer
                        pendingDelay += delay
                    } else {
                        flush()
                        val cw = x1 - x0 + 1
                        val ch = y1 - y0 + 1
                        val crop = ByteArray(cw * ch)
                        for (y in 0 until ch) for (x in 0 until cw) {
                            val i = (y0 + y) * w + x0 + x
                            if (changed[i]) {
                                crop[y * cw + x] = idx[i]
                                before[i] = idx[i]
                            } else crop[y * cw + x] = clear.toByte()
                        }
                        pendingImage = palette.image(crop, cw, ch, entries)
                        pendingX = x0
                        pendingY = y0
                        pendingDelay = delay
                    }
                }
                if (!progress(f + 1)) break
            }
            flush()
            writer.endWriteSequence()
        }
        writer.dispose()
        return out
    }

    private fun gifMetadata(
        writer: javax.imageio.ImageWriter, img: BufferedImage, delay: Int, loop: Boolean, x: Int = 0, y: Int = 0, transparent: Int = -1,
    ): javax.imageio.metadata.IIOMetadata {
        val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), writer.defaultWriteParam)
        val format = meta.nativeMetadataFormatName
        val root = meta.getAsTree(format) as IIOMetadataNode
        node(root, "ImageDescriptor").apply {
            setAttribute("imageLeftPosition", x.toString())
            setAttribute("imageTopPosition", y.toString())
        }
        node(root, "GraphicControlExtension").apply {
            // each frame is drawn over the one before; its transparent pixels let that show through
            setAttribute("disposalMethod", "doNotDispose")
            setAttribute("userInputFlag", "FALSE")
            setAttribute("transparentColorFlag", if (transparent >= 0) "TRUE" else "FALSE")
            setAttribute("delayTime", delay.coerceAtLeast(1).toString())
            setAttribute("transparentColorIndex", transparent.coerceAtLeast(0).toString())
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

/** How a picture is spread over the few colors of a GIF. */
enum class Dither(val label: String) {
    NONE("None"),

    /** Error diffusion: finest gradients, but it changes all over when anything changes – larger animated GIFs. */
    FLOYD_STEINBERG("Floyd–Steinberg"),

    /** A fixed pattern: a little coarser, but steady from frame to frame, so unchanged parts stay unchanged. */
    ORDERED("Ordered (steady)");

    override fun toString() = label
}

/**
 * Up to 256 colors found by median cut, and a lookup from 6 bits per channel to the
 * nearest of them. [indices] turns a picture into palette indices, dithered if asked.
 */
class Palette private constructor(private val colors: IntArray) {
    private val lut = ByteArray(64 * 64 * 64)

    /** Number of colors. */
    val size get() = colors.size

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

    /** Squared distance of the RGB color [c] from palette color [i]. */
    fun distance(c: Int, i: Int): Int {
        val p = colors[i]
        val dr = (c shr 16 and 0xFF) - (p shr 16 and 0xFF)
        val dg = (c shr 8 and 0xFF) - (p shr 8 and 0xFF)
        val db = (c and 0xFF) - (p and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** The palette index of every pixel of [rgb] ([w]×[h]). */
    fun indices(rgb: IntArray, w: Int, h: Int, dither: Dither): ByteArray {
        val idx = ByteArray(w * h)
        when (dither) {
            Dither.NONE -> for (i in rgb.indices) { val c = rgb[i]; idx[i] = index(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF).toByte() }
            Dither.ORDERED -> {
                // a Bayer pattern pushes each pixel up or down by up to about one color step
                val step = 255.0 / Math.cbrt(colors.size.toDouble())
                for (y in 0 until h) for (x in 0 until w) {
                    val c = rgb[y * w + x]
                    val o = ((BAYER[(y and 7) * 8 + (x and 7)] + 0.5) / 64 - 0.5) * step
                    val d = o.roundToInt()
                    idx[y * w + x] = index((c shr 16 and 0xFF) + d, (c shr 8 and 0xFF) + d, (c and 0xFF) + d).toByte()
                }
            }
            Dither.FLOYD_STEINBERG -> {
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
        }
        return idx
    }

    /**
     * An indexed picture of [w]×[h] from [idx], with a color table of [entries] (the palette,
     * plus a transparent entry if there is one), using as few bits per pixel as that needs:
     * fewer bits make the GIF's compression start smaller.
     */
    fun image(idx: ByteArray, w: Int, h: Int, entries: Int): BufferedImage {
        val bits = when {
            entries <= 2 -> 1
            entries <= 4 -> 2
            entries <= 16 -> 4
            else -> 8
        }
        val n = 1 shl bits
        fun channel(shift: Int) = ByteArray(n) { if (it < colors.size) (colors[it] shr shift).toByte() else 0 }
        val model = IndexColorModel(bits, n, channel(16), channel(8), channel(0))
        val out = if (bits == 8) BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, model)
        else BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY, model)
        out.raster.setPixels(0, 0, w, h, IntArray(w * h) { idx[it].toInt() and 0xFF })
        return out
    }

    /** [img] as an indexed picture in this palette. */
    fun map(img: BufferedImage, dither: Dither): BufferedImage {
        val w = img.width
        val h = img.height
        return image(indices(img.getRGB(0, 0, w, h, null, 0, w), w, h, dither), w, h, colors.size)
    }

    companion object {
        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
        )

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
