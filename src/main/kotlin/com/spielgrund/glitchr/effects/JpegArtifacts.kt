package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.clamp255
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Real JPEG compression: the image is encoded at a low quality and decoded again,
 * optionally several times (generation loss), at a reduced size (bigger blocks)
 * and with random bytes of the compressed data overwritten (data corruption).
 */
object JpegArtifacts : Effect("jpeg", "JPEG artifacts", "Real JPEG compression, with broken data if you like") {
    override val params = listOf(
        Param.Slider("quality", "Quality", 1, 100, 8, " %"),
        Param.Slider("passes", "Passes", 1, 30, 1, tip = "Save several times with slightly changing quality (generation loss)"),
        Param.Slider("blocks", "Block size", 1, 16, 1, "×", "Shrinks before compressing; the 8×8 blocks get bigger"),
        Param.Slider("corrupt", "Data errors", 0, 200, 0, tip = "Number of randomly overwritten bytes in the image data"),
        Param.Choice(
            "scaling", "Scale", listOf("Nearest neighbor (sharp)", "Bilinear (soft)"),
            tip = "How it is shrunk for the block size: nearest takes single pixels and stays hard",
        ),
        Param.Toggle("fullChroma", "Full color resolution (4:4:4)", false, "Otherwise JPEG stores colors at half resolution; that blurs color edges"),
        Param.Slider("sharpen", "Resharpen", 0, 300, 0, " %", "Sharpens the compressed picture before it is enlarged again"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val random = Random(seed)
        val quality = v["quality"]
        val passes = v["passes"]
        val scale = v["blocks"]
        val corrupt = v["corrupt"]
        val smooth = v["scaling"] == 1
        val fullChroma = v.bool("fullChroma")

        val sharpen = v["sharpen"] / 100f
        fun compress(input: BufferedImage, random: Random, corrupt: Int): BufferedImage {
            val w = input.width
            val h = input.height
            var img = input
            if (scale > 1) img = resize(img, max(1, w / scale), max(1, h / scale), smooth)
            repeat(passes) { pass ->
                val q = if (pass == 0) quality else (quality + random.nextInt(-4, 5)).coerceIn(1, 100)
                val bytes = encode(img, q, fullChroma)
                img = if (pass == 0 && corrupt > 0) decodeCorrupted(bytes, corrupt, random) ?: decode(bytes) ?: img
                else decode(bytes) ?: img
            }
            if (sharpen > 0) img = sharpen(img, sharpen)
            if (img.width != w || img.height != h) img = resize(img, w, h, smooth = false)
            return img
        }

        val out = Pixels.of(compress(src.toRgbImage(), random, corrupt))
        if (src.data.all { it ushr 24 == 255 }) {
            for (i in out.data.indices) out.data[i] = out.data[i] or 0xFF000000.toInt()
        } else {
            // transparency goes through the same compression, so the blocks fray beyond the picture's edge
            val alphaImage = Pixels(src.width, src.height, IntArray(src.data.size) { i ->
                val a = src.data[i] ushr 24
                argb(255, a, a, a)
            }).toRgbImage()
            val alpha = Pixels.of(compress(alphaImage, Random(seed + 1), 0))
            for (i in out.data.indices) out.data[i] = (red(alpha.data[i]) shl 24) or (out.data[i] and 0xFFFFFF)
        }
        return out
    }

    private fun encode(img: BufferedImage, quality: Int, fullChroma: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            val metadata = if (fullChroma) fullChromaMetadata(writer, img, param) else null
            writer.write(null, IIOImage(img, null, metadata), param)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    /** Metadata that sets every component's sampling factor to 1 (no chroma subsampling); null if unsupported. */
    private fun fullChromaMetadata(writer: ImageWriter, img: BufferedImage, param: ImageWriteParam): IIOMetadata? = try {
        val format = "javax_imageio_jpeg_image_1.0"
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param)
        val tree = metadata.getAsTree(format) as IIOMetadataNode
        val components = tree.getElementsByTagName("componentSpec")
        for (i in 0 until components.length) {
            val c = components.item(i) as IIOMetadataNode
            c.setAttribute("HsamplingFactor", "1")
            c.setAttribute("VsamplingFactor", "1")
        }
        metadata.setFromTree(format, tree)
        metadata
    } catch (e: Exception) {
        null
    }

    /** Unsharp mask with a 3×3 box blur: c + amount · (c − blur). */
    private fun sharpen(img: BufferedImage, amount: Float): BufferedImage {
        val src = Pixels.of(img)
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val c = src.data[y * w + x]
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val n = src.data[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                    r += red(n); g += green(n); b += blue(n)
                }
                fun sharp(v: Int, sum: Int) = clamp255((v + amount * (v - sum / 9f)).roundToInt())
                out.data[y * w + x] = argb(0xFF, sharp(red(c), r), sharp(green(c), g), sharp(blue(c), b))
            }
        }
        return out.toRgbImage()
    }

    private fun decode(bytes: ByteArray): BufferedImage? = try {
        ImageIO.read(bytes.inputStream())?.let { toRgb(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Overwrites [count] random bytes of the entropy-coded data (after the start-of-scan
     * header), never creating or breaking 0xFF marker bytes. Some corruptions make the
     * decoder give up; then a few other random variants are tried.
     */
    private fun decodeCorrupted(bytes: ByteArray, count: Int, random: Random): BufferedImage? {
        val start = scanDataStart(bytes) ?: return null
        val end = bytes.size - 2
        if (end - start < 16) return null
        repeat(5) {
            val broken = bytes.copyOf()
            repeat(count) {
                val i = random.nextInt(start, end)
                if (broken[i] != 0xFF.toByte() && broken[i - 1] != 0xFF.toByte()) {
                    broken[i] = random.nextInt(0, 255).toByte()
                }
            }
            decode(broken)?.let { return it }
        }
        return null
    }

    private fun scanDataStart(bytes: ByteArray): Int? {
        for (i in 2 until bytes.size - 4) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xDA.toByte()) {
                val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                return i + 2 + len
            }
        }
        return null
    }

    private fun toRgb(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_RGB) return img
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        return rgb
    }

    private fun resize(img: BufferedImage, w: Int, h: Int, smooth: Boolean): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        return out
    }
}
