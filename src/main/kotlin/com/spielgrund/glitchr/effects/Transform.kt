package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Moves, scales (also in X and Y alone), turns and mirrors the picture around a center.
 * What comes in from beyond the picture's edge is transparent, repeated, mirrored or the
 * stretched edge; sampling is smooth (bilinear) or hard (nearest pixel).
 */
object Transform : Effect("transform", "Transform", "Shift, scale, rotate and mirror around a chosen center") {
    /** Edge choices, shared with the generators' position. */
    internal val edgeOptions = listOf("Transparent", "Repeat", "Mirror", "Stretch edge")

    internal fun edgeOf(choice: Int) = when (choice) { 1 -> Edge.WRAP; 2 -> Edge.MIRROR; 3 -> Edge.CLAMP; else -> null }

    override val params = listOf(
        Param.Slider("offsetX", "Shift X", -50000, 50000, 0, " px", decimals = 1),
        Param.Slider("offsetY", "Shift Y", -50000, 50000, 0, " px", decimals = 1),
        Param.Slider("scale", "Scale", 10, 10000, 1000, " %", decimals = 1),
        Param.Slider("stretchX", "Stretch X", 10, 10000, 1000, " %", decimals = 1, tip = "Additionally scale only in X"),
        Param.Slider("stretchY", "Stretch Y", 10, 10000, 1000, " %", decimals = 1, tip = "Additionally scale only in Y"),
        Param.Slider("rotation", "Rotation", -1800, 1800, 150, "°", decimals = 1),
        Param.Slider("centerX", "Center X", -1000, 2000, 500, " %", decimals = 1, tip = "Scaling and rotation happen around this point"),
        Param.Slider("centerY", "Center Y", -1000, 2000, 500, " %", decimals = 1),
        Param.Toggle("mirrorX", "Mirror X", false, "Swap left and right"),
        Param.Toggle("mirrorY", "Mirror Y", false, "Swap top and bottom"),
        Param.Choice("edge", "Edge", edgeOptions, tip = "What comes in from outside the picture"),
        Param.Toggle("smooth", "Smooth", true, "Off: hard pixels (nearest neighbor) instead of smooth interpolation"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels = transformPixels(
        src,
        offsetX = v["offsetX"] / 10.0, offsetY = v["offsetY"] / 10.0,
        scaleX = v["scale"] / 1000.0 * v["stretchX"] / 1000.0, scaleY = v["scale"] / 1000.0 * v["stretchY"] / 1000.0,
        rotation = v["rotation"] / 10.0,
        centerX = src.width * v["centerX"] / 1000.0, centerY = src.height * v["centerY"] / 1000.0,
        mirrorX = v.bool("mirrorX"), mirrorY = v.bool("mirrorY"),
        edge = edgeOf(v["edge"]), smooth = v.bool("smooth"),
    )
}

/**
 * [src] moved by ([offsetX], [offsetY]), scaled, turned by [rotation] degrees and mirrored
 * around ([centerX], [centerY]); outside the picture [edge] decides (null = transparent).
 * Returns [src] itself when nothing changes.
 */
internal fun transformPixels(
    src: Pixels, offsetX: Double = 0.0, offsetY: Double = 0.0, scaleX: Double = 1.0, scaleY: Double = 1.0,
    rotation: Double = 0.0, centerX: Double = src.width / 2.0, centerY: Double = src.height / 2.0,
    mirrorX: Boolean = false, mirrorY: Boolean = false, edge: Edge? = null, smooth: Boolean = true,
): Pixels {
    if (offsetX == 0.0 && offsetY == 0.0 && scaleX == 1.0 && scaleY == 1.0 && rotation == 0.0 && !mirrorX && !mirrorY) return src
    val w = src.width
    val h = src.height
    val a = Math.toRadians(rotation)
    val ca = cos(a)
    val sa = sin(a)
    // back from the output to the picture: undo the shift, the turn, the scale and the mirror
    val sx = (if (mirrorX) -1 else 1) * scaleX.coerceAtLeast(1e-6)
    val sy = (if (mirrorY) -1 else 1) * scaleY.coerceAtLeast(1e-6)
    val out = Pixels(w, h)
    parallelRows(h) { y ->
        for (x in 0 until w) {
            val dx = x + 0.5 - centerX - offsetX
            val dy = y + 0.5 - centerY - offsetY
            val qx = centerX + (dx * ca + dy * sa) / sx
            val qy = centerY + (-dx * sa + dy * ca) / sy
            out.data[y * w + x] = when {
                smooth -> if (edge == null) sampleTransparent(src, qx, qy) else sampleBilinear(src, qx, qy, edge)
                else -> sampleNearest(src, qx, qy, edge)
            }
        }
    }
    return out
}

/** Nearest pixel at canvas position ([x], [y]); outside [edge] decides (null = transparent). */
internal fun sampleNearest(src: Pixels, x: Double, y: Double, edge: Edge?): Int {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    if (edge == null) return if (ix in 0 until src.width && iy in 0 until src.height) src.data[iy * src.width + ix] else 0
    return src.data[edge.resolve(iy, src.height) * src.width + edge.resolve(ix, src.width)]
}

/** Bilinear sample, transparent outside the picture – without darkening the edge. */
internal fun sampleTransparent(src: Pixels, x: Double, y: Double): Int {
    val fx = x - 0.5
    val fy = y - 0.5
    val x0 = floor(fx).toInt()
    val y0 = floor(fy).toInt()
    if (x0 < -1 || y0 < -1 || x0 >= src.width || y0 >= src.height) return 0
    val tx = fx - x0
    val ty = fy - y0
    fun inside(ix: Int, iy: Int) = if (ix in 0 until src.width && iy in 0 until src.height) 1.0 else 0.0
    // how much of the sample lies on the picture
    val coverage = (inside(x0, y0) * (1 - tx) + inside(x0 + 1, y0) * tx) * (1 - ty) +
        (inside(x0, y0 + 1) * (1 - tx) + inside(x0 + 1, y0 + 1) * tx) * ty
    val c = sampleBilinear(src, x, y, Edge.CLAMP)
    return ((alpha(c) * coverage).roundToInt() shl 24) or (c and 0xFFFFFF)
}
