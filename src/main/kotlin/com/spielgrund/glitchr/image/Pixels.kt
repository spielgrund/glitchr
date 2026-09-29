package com.spielgrund.glitchr.image

import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.awt.image.Raster
import java.util.stream.IntStream

/**
 * An ARGB image as a plain pixel array. Pixels produced by the effects and the
 * renderer are cached and shared, so [data] must never be modified once handed on.
 */
class Pixels(val width: Int, val height: Int, val data: IntArray = IntArray(width * height)) {
    init {
        require(data.size == width * height) { "${data.size} Pixel passen nicht zu ${width}×$height" }
    }

    operator fun get(x: Int, y: Int) = data[y * width + x]

    fun copy() = Pixels(width, height, data.copyOf())

    /** An image backed by [data] itself (no copy); it must not be drawn onto. */
    fun toImage(): BufferedImage {
        val cm = ColorModel.getRGBdefault() as DirectColorModel
        val raster = Raster.createPackedRaster(DataBufferInt(data, data.size), width, height, width, cm.masks, null)
        return BufferedImage(cm, raster, false, null)
    }

    /** Same pixels without transparency, for encoders that can't store alpha (JPEG). */
    fun toRgbImage(): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, width, height, data, 0, width)
        return img
    }

    companion object {
        fun of(img: BufferedImage): Pixels {
            val p = Pixels(img.width, img.height)
            img.getRGB(0, 0, img.width, img.height, p.data, 0, img.width)
            return p
        }
    }
}

/** Runs [body] for every row index in parallel. */
fun parallelRows(height: Int, body: (Int) -> Unit) = IntStream.range(0, height).parallel().forEach { body(it) }

fun alpha(c: Int) = c ushr 24
fun red(c: Int) = (c shr 16) and 0xFF
fun green(c: Int) = (c shr 8) and 0xFF
fun blue(c: Int) = c and 0xFF

fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

fun clamp255(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

/** Perceived brightness 0..255. */
fun luma(c: Int) = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000

/** Channel [ch] (0 = red, 1 = green, 2 = blue) of [c]. */
fun channel(c: Int, ch: Int) = (c shr (16 - 8 * ch)) and 0xFF

/** [c] with channel [ch] replaced by [v]. */
fun withChannel(c: Int, ch: Int, v: Int): Int {
    val shift = 16 - 8 * ch
    return (c and (0xFF shl shift).inv()) or (v shl shift)
}

/** Blend between two ARGB colors, t = 0 gives [a], t = 1 gives [b]. */
fun lerpArgb(a: Int, b: Int, t: Float): Int {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun mix(x: Int, y: Int) = (x + (y - x) * t + 0.5f).toInt()
    return argb(mix(alpha(a), alpha(b)), mix(red(a), red(b)), mix(green(a), green(b)), mix(blue(a), blue(b)))
}

/** How out-of-image coordinates are resolved. */
enum class Edge(val label: String) {
    WRAP("Wiederholen"), CLAMP("Rand strecken"), MIRROR("Spiegeln");

    fun resolve(v: Int, size: Int): Int = when (this) {
        WRAP -> Math.floorMod(v, size)
        CLAMP -> v.coerceIn(0, size - 1)
        MIRROR -> Math.floorMod(v, 2 * size).let { if (it >= size) 2 * size - 1 - it else it }
    }

    companion object {
        val labels = entries.map { it.label }
    }
}

/** Bilinear sample of [src] at canvas position ([x], [y]); pixel centers lie at +0.5, outside is resolved by [edge]. */
fun sampleBilinear(src: Pixels, x: Double, y: Double, edge: Edge): Int {
    val fx = x - 0.5
    val fy = y - 0.5
    val x0 = kotlin.math.floor(fx).toInt()
    val y0 = kotlin.math.floor(fy).toInt()
    val tx = (fx - x0).toFloat()
    val ty = (fy - y0).toFloat()
    val w = src.width
    val h = src.height
    val xa = edge.resolve(x0, w)
    val xb = edge.resolve(x0 + 1, w)
    val ya = edge.resolve(y0, h) * w
    val yb = edge.resolve(y0 + 1, h) * w
    return lerpArgb(
        lerpArgb(src.data[ya + xa], src.data[ya + xb], tx),
        lerpArgb(src.data[yb + xa], src.data[yb + xb], tx),
        ty,
    )
}
