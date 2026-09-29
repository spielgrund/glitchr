package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.parallelRows
import java.awt.geom.Point2D
import kotlin.math.hypot
import kotlin.math.max

/**
 * The areas around the drawn arrows: how much each point belongs to them (1 near an
 * arrow, fading to 0 at [width]) and the arrow direction there. Both are worked out on
 * a coarse grid and looked up bilinearly. [along] tells how far along its arrow a point
 * lies, which makes a landscape that runs downhill in the arrow direction.
 */
internal class ArrowArea(strokes: List<List<Point2D.Double>>, w: Int, h: Int, width: Double) {
    private val cell = max(1.0, width / 6)
    private val cols = (w / cell).toInt() + 2
    private val rows = (h / cell).toInt() + 2
    private val inside = FloatArray(cols * rows)
    private val dirX = FloatArray(cols * rows)
    private val dirY = FloatArray(cols * rows)
    private val alongs = FloatArray(cols * rows)

    init {
        // the strokes in pixels, cut into pieces a few cells long
        val segs = ArrayList<DoubleArray>()
        for (stroke in strokes) {
            val pts = stroke.map { doubleArrayOf(it.x * w, it.y * h) }
            var start = pts[0]
            var travelled = 0.0
            var length = 0.0
            for (i in 1 until pts.size) {
                travelled += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
                if (travelled >= cell * 2 || i == pts.size - 1) {
                    val len = hypot(pts[i][0] - start[0], pts[i][1] - start[1])
                    // the last value: how far along the stroke this piece starts
                    if (len > 1e-9) segs.add(doubleArrayOf(start[0], start[1], pts[i][0], pts[i][1], len, length))
                    length += len
                    start = pts[i]
                    travelled = 0.0
                }
            }
        }
        parallelRows(rows) { gy ->
            for (gx in 0 until cols) {
                val x = gx * cell
                val y = gy * cell
                var best = Double.MAX_VALUE
                for (sg in segs) {
                    val tx = (sg[2] - sg[0]) / sg[4]
                    val ty = (sg[3] - sg[1]) / sg[4]
                    val t = ((x - sg[0]) * tx + (y - sg[1]) * ty).coerceIn(0.0, sg[4])
                    val d = hypot(sg[0] + tx * t - x, sg[1] + ty * t - y)
                    if (d < best) {
                        best = d
                        dirX[gy * cols + gx] = tx.toFloat()
                        dirY[gy * cols + gx] = ty.toFloat()
                        alongs[gy * cols + gx] = (sg[5] + t).toFloat()
                    }
                }
                // full effect up to 60 % of the width, then fading out
                val f = ((width - best) / (width * 0.4)).coerceIn(0.0, 1.0)
                inside[gy * cols + gx] = (f * f * (3 - 2 * f)).toFloat()
            }
        }
    }

    private fun lookup(f: FloatArray, x: Double, y: Double): Double {
        val fx = (x / cell).coerceIn(0.0, cols - 1.001)
        val fy = (y / cell).coerceIn(0.0, rows - 1.001)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val tx = fx - x0
        val ty = fy - y0
        val i = y0 * cols + x0
        return (f[i] * (1 - tx) + f[i + 1] * tx) * (1 - ty) + (f[i + cols] * (1 - tx) + f[i + cols + 1] * tx) * ty
    }

    fun mask(x: Double, y: Double) = lookup(inside, x, y)

    /** How far along the nearest arrow ([x], [y]) lies, in pixels from its tail. */
    fun along(x: Double, y: Double) = lookup(alongs, x, y)

    /** The arrow direction at ([x], [y]) as a unit vector into [out]. */
    fun direction(x: Double, y: Double, out: DoubleArray) {
        val dx = lookup(dirX, x, y)
        val dy = lookup(dirY, x, y)
        val len = hypot(dx, dy).coerceAtLeast(1e-9)
        out[0] = dx / len
        out[1] = dy / len
    }
}
