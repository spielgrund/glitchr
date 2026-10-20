package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Draws with Java2D onto a copy of [src] (antialiased, in high quality) and returns the
 * result – for the generators that build their picture from shapes and type.
 */
internal fun drawOn(src: Pixels, block: (Graphics2D) -> Unit): Pixels {
    val img = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
    val data = (img.raster.dataBuffer as DataBufferInt).data
    System.arraycopy(src.data, 0, data, 0, data.size)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
    block(g)
    g.dispose()
    return Pixels(src.width, src.height, data.copyOf())
}

/** An opaque Java2D color from 0xRRGGBB, with [alpha] 0..1. */
internal fun color(rgb: Int, alpha: Double = 1.0) = java.awt.Color((rgb and 0xFFFFFF) or ((alpha.coerceIn(0.0, 1.0) * 255 + 0.5).toInt() shl 24), true)

/**
 * A tapered stroke along [xs], [ys]: [width] at each point (0..1 along the stroke gives the
 * share), filled as one smooth shape – thick in the middle, pointed at the ends, like a
 * brush or a tribal blade.
 */
internal fun taperedStroke(xs: DoubleArray, ys: DoubleArray, width: (Double) -> Double): java.awt.geom.Path2D.Double {
    val n = xs.size
    val path = java.awt.geom.Path2D.Double()
    if (n < 2) return path
    val left = DoubleArray(n * 2)
    val right = DoubleArray(n * 2)
    for (i in 0 until n) {
        val a = maxOf(0, i - 1)
        val b = minOf(n - 1, i + 1)
        var dx = xs[b] - xs[a]
        var dy = ys[b] - ys[a]
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-9)
        dx /= len
        dy /= len
        val half = width(i / (n - 1.0)) / 2
        left[2 * i] = xs[i] - dy * half; left[2 * i + 1] = ys[i] + dx * half
        right[2 * i] = xs[i] + dy * half; right[2 * i + 1] = ys[i] - dx * half
    }
    path.moveTo(left[0], left[1])
    for (i in 1 until n) path.lineTo(left[2 * i], left[2 * i + 1])
    for (i in n - 1 downTo 0) path.lineTo(right[2 * i], right[2 * i + 1])
    path.closePath()
    return path
}
