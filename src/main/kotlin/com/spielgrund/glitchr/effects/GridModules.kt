package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.red
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grid modules in the manner of "Generative Gestaltung" P.2.1: the picture is cut into
 * cells and each cell is drawn as a small line module whose density, size or direction
 * follows the cell's brightness – so the picture reappears as a pattern. The brightness
 * is stretched to the picture's own range first, so dark or pale pictures still show.
 */
object GridModules : Effect("grid", "Raster", "Rastermodule, deren Dichte, Grösse oder Richtung der Bildhelligkeit folgt") {
    private val modules = listOf("Punktraster", "Konzentrische Kreise", "Strahlen", "Schraffur", "Streifen", "Moiré-Gitter")

    override val params = listOf(
        Param.Choice("module", "Modul", modules),
        Param.Slider("cell", "Zellgrösse", 4, 200, 16, " px"),
        Param.Slider("lines", "Linien je Zelle", 1, 40, 8, tip = "Höchstzahl der Linien, Ringe oder Strahlen in einer ganz dunklen Zelle; Moiré: Linienabstand"),
        Param.Slider("influence", "Bildeinfluss", 0, 100, 100, " %", "0 %: alle Zellen gleich, reines Muster. 100 %: die Helligkeit bestimmt jede Zelle"),
        Param.Slider("lineWidth", "Linienstärke", 1, 50, 8, tip = "In Zehntelpixeln"),
        Param.Toggle("invert", "Umkehren", false, "Helle statt dunkle Stellen werden dicht – für dunklen Hintergrund einschalten"),
        Param.Choice("colorMode", "Farbe", listOf("Eine Farbe", "Aus dem Bild")),
        Param.Color("color", "Linienfarbe", 0x111111),
        Param.Choice("background", "Hintergrund", listOf("Weiss", "Schwarz", "Transparent", "Originalbild")),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        when (v["background"]) {
            0 -> data.fill(-1)
            1 -> data.fill(0xFF000000.toInt())
            3 -> src.data.copyInto(data)
        }
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.stroke = BasicStroke(v["lineWidth"] / 10f)

        val cell = v["cell"]
        val maxLines = v["lines"]
        val influence = v["influence"] / 100.0
        val invert = v.bool("invert")
        val single = v["color"] or 0xFF000000.toInt()
        val fromImage = v["colorMode"] == 1

        // cell averages first, to stretch the brightness to the picture's own range
        val cols = (w + cell - 1) / cell
        val rows = (h + cell - 1) / cell
        val averages = arrayOfNulls<Int>(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            averages[r * cols + c] = average(src, c * cell, r * cell, min(w, (c + 1) * cell), min(h, (r + 1) * cell))
        }
        val (low, high) = range(averages.filterNotNull().map { luma(it) })
        fun level(l: Int) = ((l - low) / max(1.0, high - low)).coerceIn(0.0, 1.0)

        if (v["module"] == 5) {
            moire(g, src, maxLines, influence, invert, single, fromImage, ::level)
        } else {
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = c * cell
                val y0 = r * cell
                val avg = averages[r * cols + c] ?: continue
                val bright = level(luma(avg))
                // ink 0..1: how much to draw here (dark cells more), blended with a flat middle value
                val ink = (if (invert) bright else 1 - bright) * influence + 0.5 * (1 - influence)
                val rgb = if (fromImage) avg else single
                g.color = Color(red(rgb), green(rgb), blue(rgb), alpha(avg))
                val cx = x0 + cell / 2.0
                val cy = y0 + cell / 2.0
                when (v["module"]) {
                    0 -> { // halftone dot, its area following the ink
                        val r = cell / 2.0 * sqrt(ink) * 1.15
                        g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
                    }
                    1 -> { // concentric circles
                        val rings = (ink * maxLines).roundToInt()
                        for (k in 1..rings) {
                            val r = cell / 2.0 * k / maxLines
                            g.draw(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
                        }
                    }
                    2 -> { // rays from a point that drifts with the brightness to points on the cell border
                        val rays = max(1, (ink * maxLines * 4).roundToInt())
                        val px = x0 + cell * (0.2 + 0.6 * ink)
                        val py = y0 + cell * (0.8 - 0.6 * ink)
                        for (k in 0 until rays) {
                            val t = k.toDouble() / rays
                            val (bx, by) = borderPoint(x0.toDouble(), y0.toDouble(), cell.toDouble(), t)
                            g.draw(Line2D.Double(px, py, bx, by))
                        }
                    }
                    3 -> { // diagonal hatching, crossed where it is very dark
                        hatch(g, x0, y0, cell, (ink * maxLines).roundToInt(), PI / 4)
                        if (ink > 0.65) hatch(g, x0, y0, cell, ((ink - 0.65) / 0.35 * maxLines).roundToInt(), -PI / 4)
                    }
                    else -> hatch(g, x0, y0, cell, max(2, maxLines), ink * PI) // stripes turned by the brightness
                }
            }
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /** Point on the border of a cell, [t] 0..1 going once around it. */
    private fun borderPoint(x0: Double, y0: Double, s: Double, t: Double): Pair<Double, Double> {
        val d = t * 4
        return when {
            d < 1 -> (x0 + d * s) to y0
            d < 2 -> (x0 + s) to (y0 + (d - 1) * s)
            d < 3 -> (x0 + s - (d - 2) * s) to (y0 + s)
            else -> x0 to (y0 + s - (d - 3) * s)
        }
    }

    /** [n] evenly spaced parallel lines at [angle] across a cell, cut to the cell. */
    private fun hatch(g: Graphics2D, x0: Int, y0: Int, s: Int, n: Int, angle: Double) {
        if (n <= 0) return
        val clip = g.clip
        g.clipRect(x0, y0, s, s)
        val cx = x0 + s / 2.0
        val cy = y0 + s / 2.0
        val dx = cos(angle)
        val dy = sin(angle)
        // the lines span the cell's diagonal, so any angle fills it
        val span = s * sqrt(2.0)
        for (k in 0 until n) {
            val off = ((k + 0.5) / n - 0.5) * span
            val ox = -dy * off
            val oy = dx * off
            g.draw(Line2D.Double(cx + ox - dx * span, cy + oy - dy * span, cx + ox + dx * span, cy + oy + dy * span))
        }
        g.clip = clip
    }

    /** Brightness range of the picture without the extreme 2 % on both ends. */
    private fun range(lumas: List<Int>): Pair<Double, Double> {
        if (lumas.isEmpty()) return 0.0 to 255.0
        val sorted = lumas.sorted()
        val low = sorted[(sorted.size * 0.02).toInt()].toDouble()
        val high = sorted[((sorted.size - 1) * 0.98).toInt()].toDouble()
        return if (high - low < 8) 0.0 to 255.0 else low to high
    }

    /**
     * Two line gratings on top of each other: a straight one and one bent by the
     * brightness. Where they drift apart, moiré bands trace the picture.
     */
    private fun moire(
        g: Graphics2D, src: Pixels, spacing: Int, influence: Double, invert: Boolean, single: Int, fromImage: Boolean,
        level: (Int) -> Double,
    ) {
        val w = src.width
        val h = src.height
        val gap = max(2, spacing)
        // brightness smoothed over a few line gaps, so the bent lines follow shapes, not texture
        val smooth = blurredLuma(src, gap * 2)
        val c = Color(red(single), green(single), blue(single))
        g.color = c
        var y = 0.0
        while (y < h) {
            g.draw(Line2D.Double(0.0, y, w.toDouble(), y))
            y += gap
        }
        var row = 0.0
        while (row < h + gap * 4) {
            val path = Path2D.Double()
            for (x in 0..w step 2) {
                val sy = row.toInt().coerceIn(0, h - 1)
                val px = src[x.coerceAtMost(w - 1), sy]
                val bright = level(smooth[sy * w + x.coerceAtMost(w - 1)])
                val ink = (if (invert) bright else 1 - bright) * influence
                // bend by up to about three line gaps
                val yy = row + ink * gap * 3
                if (x == 0) path.moveTo(x.toDouble(), yy) else path.lineTo(x.toDouble(), yy)
                if (fromImage && x % 40 == 0 && x > 0) {
                    g.color = Color(red(px), green(px), blue(px))
                    g.draw(path)
                    path.reset()
                    path.moveTo(x.toDouble(), yy)
                }
            }
            if (!fromImage) g.color = c
            g.draw(path)
            row += gap
        }
    }

    /** Box-blurred brightness with radius [r]. */
    private fun blurredLuma(src: Pixels, r: Int): IntArray {
        val w = src.width
        val h = src.height
        val l = IntArray(w * h) { luma(src.data[it]) }
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            var sum = 0
            for (i in -r..r) sum += l[y * w + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / (2 * r + 1)
                sum += l[y * w + (x + r + 1).coerceIn(0, w - 1)] - l[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                l[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return l
    }

    /** Average color of the visible pixels in the rectangle; null if all are transparent. */
    private fun average(src: Pixels, x0: Int, y0: Int, x1: Int, y1: Int): Int? {
        var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = src.data[y * src.width + x]
            if (alpha(c) == 0) continue
            a += alpha(c); r += red(c); g += green(c); b += blue(c); n++
        }
        return if (n == 0L) null else argb((a / n).toInt(), (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
