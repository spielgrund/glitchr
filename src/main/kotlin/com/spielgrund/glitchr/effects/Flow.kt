package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import java.awt.geom.Point2D
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flow strokes drawn on the canvas, stored as text in a [Param.Flow]: strokes separated
 * by ";", points "x,y" relative to the canvas (0..1).
 */
object FlowStrokes {
    fun parse(text: String): List<List<Point2D.Double>> = text.split(';').mapNotNull { stroke ->
        stroke.trim().split(' ').mapNotNull { point ->
            val parts = point.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val y = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Point2D.Double(x, y)
        }.takeIf { it.size >= 2 }
    }

    fun format(strokes: List<List<Point2D.Double>>): String = strokes.joinToString(";") { stroke ->
        stroke.joinToString(" ") { String.format(Locale.ROOT, "%.4f,%.4f", it.x, it.y) }
    }
}

/**
 * Moves the picture along a drawn flow, like a painted vector map driving a UV offset.
 * Every stroke points the way near it; between and beyond the strokes the direction is
 * blended (or the picture stands still). The picture then slides along the flow by a share
 * of one full repetition (100 %: once across the picture, or once through a section) – as a whole, repeating at the edges, or in sections that start over every
 * few pixels, with hard seams or cross-faded. Every
 * section moves forward as a whole and jumps back after one section length.
 */
object Flow : Effect("flow", "Flow", "Verschiebt das Bild entlang einer mit der Maus gezeichneten Flussrichtung") {
    override val params = listOf(
        Param.Flow("strokes", "Flussrichtung", "Im Bild ziehen zeichnet einen Pfeil: das Bild fliesst in Zeichenrichtung, ohne Pfeile alles in die Grundrichtung"),
        Param.Slider("shift", "Versatz", -400, 400, 0, " %", "Wie weit das Bild entlang der Richtung wandert: 100 % ist eine komplette Wiederholung (einmal über das Bild, bei Abschnitten einmal durch den Abschnitt); negativ gegen die Richtung"),
        Param.Choice("mode", "Verschieben", listOf("Ganz (Wiederholung)", "In Abschnitten", "Schleife entlang der Pfeile", "Schleife in Abschnitten"), tip = "Ganz: alles wandert, am Rand wiederholt sich das Bild · In Abschnitten: der Versatz beginnt alle paar Pixel von vorn · Schleife: das Bild läuft in einem Band (so breit wie die Reichweite) die Pfeile entlang, was am Pfeilende ankommt, springt an den Anfang; 100 % = einmal ganz herum · Schleife in Abschnitten: dasselbe, aber jeder Abschnitt des Pfeils ist eine eigene kleine Schleife"),
        Param.Slider("section", "Abschnittslänge", 4, 500, 120, " px", "Für „In Abschnitten“: Länge eines Abschnitts entlang der Richtung"),
        Param.Slider("spread", "Abschnitte versetzt", 0, 100, 100, " %", "Für „In Abschnitten“: wie unterschiedlich weit die Abschnitte schon gewandert sind"),
        Param.Choice("edge", "Kanten", listOf("Hart", "Weich"), 1, "Übergänge zwischen den Abschnitten und am Rand des Flussbereichs"),
        Param.Choice("method", "Methode", listOf("Strömung folgen", "Direkt (UV-Offset)"), tip = "Strömung folgen: das Bild wandert die Kurven entlang · Direkt: jeder Punkt verschiebt sich gerade in seine Richtung, wie ein UV-Offset"),
        Param.Slider("reach", "Reichweite", 5, 1000, 120, " px", "Wie weit ein Strich um sich herum wirkt"),
        Param.Choice("outside", "Ausserhalb", listOf("Richtung fortsetzen", "Stillstand"), tip = "Was abseits der Striche passiert"),
        Param.Slider("baseAngle", "Grundrichtung", 0, 359, 0, "°", "Richtung ohne gezeichnete Striche; 0° = nach rechts"),
    )

    override val random = false

    /** Direction and strength of the flow on a coarse grid; looked up bilinearly. */
    private class Field(val w: Int, val h: Int, val cell: Double, val cols: Int, val rows: Int) {
        val dx = FloatArray(cols * rows)
        val dy = FloatArray(cols * rows)
        val strength = FloatArray(cols * rows)

        /** Writes (dx, dy, strength) at canvas position ([x], [y]) into [out]. */
        fun at(x: Double, y: Double, out: DoubleArray) {
            val gx = (x / cell - 0.5).coerceIn(0.0, cols - 1.0)
            val gy = (y / cell - 0.5).coerceIn(0.0, rows - 1.0)
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val x1 = min(x0 + 1, cols - 1)
            val y1 = min(y0 + 1, rows - 1)
            val tx = gx - x0
            val ty = gy - y0
            fun lerp(a: FloatArray): Double {
                val top = a[y0 * cols + x0] * (1 - tx) + a[y0 * cols + x1] * tx
                val bottom = a[y1 * cols + x0] * (1 - tx) + a[y1 * cols + x1] * tx
                return top * (1 - ty) + bottom * ty
            }
            var vx = lerp(dx)
            var vy = lerp(dy)
            val len = hypot(vx, vy)
            if (len > 1e-9) { vx /= len; vy /= len }
            out[0] = vx
            out[1] = vy
            out[2] = lerp(strength)
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val repeats = v["shift"] / 100.0
        val sections = v["mode"] == 1
        val sectionLength = v["section"].toDouble()
        val spread = v["spread"] / 100.0
        val soft = v["edge"] == 1
        val direct = v["method"] == 1
        if (v["mode"] >= 2) {
            val belts = FlowStrokes.parse(v.text("strokes")).map { Belt(it, w, h) }.filter { it.length > 1 }
            // one section as long as the whole arrow is the plain loop
            val length = if (v["mode"] == 3) sectionLength else Double.MAX_VALUE
            if (belts.isNotEmpty()) return loop(src, belts, repeats, v["reach"].toDouble(), soft, length, if (v["mode"] == 3) spread else 0.0)
        }
        val field = field(src.width, src.height, v)

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val f = DoubleArray(3)
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                if (!sections) {
                    // one repetition is the picture's length along the local direction
                    field.at(px, py, f)
                    val cycle = abs(f[0]) * w + abs(f[1]) * h
                    out.data[y * w + x] = sample(src, field, px, py, repeats * cycle, direct, soft, f)
                    continue
                }
                // the sections lie one after another along the flow; each one moves forward as a whole
                // and starts over after one section length, each at its own point of the cycle
                field.at(px, py, f)
                val along = (px * f[0] + py * f[1]) / sectionLength
                val k = floor(along).toInt()
                fun distance(section: Int) = sectionLength * fract(repeats + spread * phase(section))
                val own = sample(src, field, px, py, distance(k), direct, soft, f)
                out.data[y * w + x] = if (!soft) own else {
                    // near the seams, fade into the neighbouring section: half and half on the seam itself
                    val u = along - k
                    val blend = 0.25
                    when {
                        u < blend -> lerpArgb(own, sample(src, field, px, py, distance(k - 1), direct, soft, f), smooth(0.5 * (1 - u / blend)))
                        u > 1 - blend -> lerpArgb(own, sample(src, field, px, py, distance(k + 1), direct, soft, f), smooth(0.5 * (1 - (1 - u) / blend)))
                        else -> own
                    }
                }
            }
        }
        return out
    }

    private fun fract(v: Double) = v - floor(v)

    /** A stroke as a conveyor belt: its points in canvas pixels, the length up to each point and smoothed normals. */
    private class Belt(stroke: List<Point2D.Double>, w: Int, h: Int) {
        val xs = DoubleArray(stroke.size) { stroke[it].x * w }
        val ys = DoubleArray(stroke.size) { stroke[it].y * h }
        val at = DoubleArray(stroke.size)
        val nx = DoubleArray(stroke.size)
        val ny = DoubleArray(stroke.size)
        val length: Double

        init {
            for (i in 1 until xs.size) at[i] = at[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            length = at.last()
            // normal at each point: from the neighbouring points, pointing to the left of the stroke
            for (i in xs.indices) {
                val a = max(0, i - 1)
                val b = min(xs.size - 1, i + 1)
                val dx = xs[b] - xs[a]
                val dy = ys[b] - ys[a]
                val len = hypot(dx, dy).coerceAtLeast(1e-9)
                nx[i] = dy / len
                ny[i] = -dx / len
            }
        }

        /** Canvas position [across] pixels beside the belt, [along] pixels from its start; writes x, y into [out]. */
        fun point(along: Double, across: Double, out: DoubleArray) {
            var lo = 0
            var hi = at.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (at[mid] <= along) lo = mid else hi = mid
            }
            val seg = (at[hi] - at[lo]).coerceAtLeast(1e-9)
            // not clamped: before the first and after the last point the belt continues straight
            val t = (along - at[lo]) / seg
            val x = xs[lo] + (xs[hi] - xs[lo]) * t
            val y = ys[lo] + (ys[hi] - ys[lo]) * t
            var ox = nx[lo] + (nx[hi] - nx[lo]) * t
            var oy = ny[lo] + (ny[hi] - ny[lo]) * t
            val len = hypot(ox, oy).coerceAtLeast(1e-9)
            ox /= len
            oy /= len
            out[0] = x + ox * across
            out[1] = y + oy * across
        }
    }

    /**
     * The picture runs along the strokes in bands as wide as the reach: what reaches the
     * arrow's tip starts again at its tail. Every pixel belongs to the nearest stroke.
     */
    private fun loop(
        src: Pixels, belts: List<Belt>, repeats: Double, reach: Double, soft: Boolean, sectionLength: Double, spread: Double,
    ): Pixels {
        val w = src.width
        val h = src.height
        // segments sorted into a grid of reach-sized cells, so each pixel only checks the nearby ones
        val cell = max(8.0, reach)
        val cols = ceil(w / cell).toInt() + 1
        val rows = ceil(h / cell).toInt() + 1
        val grid = Array(cols * rows) { ArrayList<IntArray>() }
        for ((b, belt) in belts.withIndex()) for (i in 0 until belt.xs.size - 1) {
            val x0 = floor((min(belt.xs[i], belt.xs[i + 1]) - reach) / cell).toInt().coerceIn(0, cols - 1)
            val x1 = floor((max(belt.xs[i], belt.xs[i + 1]) + reach) / cell).toInt().coerceIn(0, cols - 1)
            val y0 = floor((min(belt.ys[i], belt.ys[i + 1]) - reach) / cell).toInt().coerceIn(0, rows - 1)
            val y1 = floor((max(belt.ys[i], belt.ys[i + 1]) + reach) / cell).toInt().coerceIn(0, rows - 1)
            for (gy in y0..y1) for (gx in x0..x1) grid[gy * cols + gx].add(intArrayOf(b, i))
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val q = DoubleArray(2)
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                val own = src.data[y * w + x]
                val candidates = grid[min(rows - 1, (py / cell).toInt()) * cols + min(cols - 1, (px / cell).toInt())]
                // nearest point on any stroke: distance, which stroke, how far along, which side
                var best = Double.MAX_VALUE
                var belt: Belt? = null
                var along = 0.0
                var across = 0.0
                var beyond = 0.0
                for (c in candidates) {
                    val bt = belts[c[0]]
                    val i = c[1]
                    val ax = bt.xs[i]
                    val ay = bt.ys[i]
                    val dx = bt.xs[i + 1] - ax
                    val dy = bt.ys[i + 1] - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 < 1e-12) continue
                    val tRaw = ((px - ax) * dx + (py - ay) * dy) / len2
                    val t = tRaw.coerceIn(0.0, 1.0)
                    val cx = ax + dx * t
                    val cy = ay + dy * t
                    val d = hypot(px - cx, py - cy)
                    if (d < best) {
                        best = d
                        belt = bt
                        val len = sqrt(len2)
                        // past the ends the belt runs on straight, so nothing is stretched there
                        along = bt.at[i] + when {
                            i == 0 && tRaw < 0 -> tRaw * len
                            i == bt.xs.size - 2 && tRaw > 1 -> tRaw * len
                            else -> t * len
                        }
                        // signed distance, positive on the side the normals point to
                        across = ((px - cx) * dy - (py - cy) * dx) / len
                        // past the tail or the tip of the whole stroke
                        beyond = when {
                            i == 0 && tRaw < 0 -> -tRaw * len
                            i == bt.xs.size - 2 && tRaw > 1 -> (tRaw - 1) * len
                            else -> 0.0
                        }
                    }
                }
                val bt = belt
                if (bt == null || best > reach) {
                    out.data[y * w + x] = own
                    continue
                }
                // hard: the whole band; soft: fading out towards its edge and past the ends
                val edge = max(best, beyond)
                val weight = if (soft) (1 - ((edge - reach * 0.5) / (reach * 0.5)).coerceIn(0.0, 1.0)).let { it * it * (3 - 2 * it) }
                else if (beyond > 0) 0.0 else 1.0
                if (weight <= 0) {
                    out.data[y * w + x] = own
                    continue
                }
                // the belt cut into sections (a single one for the plain loop); each loops on its own
                val sectionLen = min(sectionLength, bt.length)
                val count = max(1, ceil(bt.length / sectionLen - 1e-9).toInt())
                fun moved(k: Int): Int {
                    val section = k.coerceIn(0, count - 1)
                    val start = section * sectionLen
                    val len = min(sectionLen, bt.length - start)
                    // 100 % = once around the section
                    val from = along - start - (repeats + spread * phase(section)) * len
                    // inside the section it wraps around; past the arrow's ends the belt just runs on straight
                    val inside = along - start in 0.0..len
                    bt.point(start + if (inside) from - floor(from / len) * len else from, across, q)
                    return sampleBilinear(src, q[0], q[1], Edge.MIRROR)
                }
                val k = floor(along.coerceIn(0.0, bt.length - 1e-9) / sectionLen).toInt()
                var moved = moved(k)
                if (soft && count > 1) {
                    // fade into the neighbouring section near the seams, half and half on the seam
                    val u = along / sectionLen - k
                    val blend = 0.25
                    if (u < blend && k > 0) moved = lerpArgb(moved, moved(k - 1), smooth(0.5 * (1 - u / blend)))
                    else if (u > 1 - blend && k < count - 1) moved = lerpArgb(moved, moved(k + 1), smooth(0.5 * (1 - (1 - u) / blend)))
                }
                out.data[y * w + x] = if (weight >= 1) moved else lerpArgb(own, moved, weight.toFloat())
            }
        }
        return out
    }

    /** Where in its cycle section [k] starts, 0..1: a fixed scramble of the section number. */
    private fun phase(k: Int): Double {
        var h = k.toLong() * -0x61c8864680b583ebL
        h = (h xor (h ushr 29)) * -0x4b47d5b1b2d1a1c1L
        h = h xor (h ushr 32)
        return (h ushr 11).toDouble() / (1L shl 53)
    }

    /** Smooth 0..0.5 weight: slow at the ends, so the fade has no visible edge. */
    private fun smooth(t: Double): Float {
        val x = (t / 0.5).coerceIn(0.0, 1.0)
        return (0.5 * x * x * (3 - 2 * x)).toFloat()
    }

    /** The picture at the point that flows [distance] pixels onto ([x], [y]). */
    private fun sample(src: Pixels, field: Field, x: Double, y: Double, distance: Double, direct: Boolean, soft: Boolean, f: DoubleArray): Int {
        var qx = x
        var qy = y
        if (distance != 0.0) {
            if (direct) {
                field.at(qx, qy, f)
                val m = strength(f[2], soft)
                qx -= f[0] * m * distance
                qy -= f[1] * m * distance
            } else {
                // walk upstream in small steps, so the picture follows the curves
                val steps = ceil(abs(distance) / max(2.0, field.cell)).toInt().coerceIn(1, 160)
                val step = distance / steps
                repeat(steps) {
                    field.at(qx, qy, f)
                    val m = strength(f[2], soft)
                    qx = wrap(qx - f[0] * m * step, src.width)
                    qy = wrap(qy - f[1] * m * step, src.height)
                }
            }
        }
        return sampleBilinear(src, qx, qy, Edge.WRAP)
    }

    private fun strength(m: Double, soft: Boolean) = if (soft) m.coerceIn(0.0, 1.0) else if (m >= 0.5) 1.0 else 0.0

    private fun wrap(v: Double, size: Int) = v - floor(v / size) * size

    private fun field(w: Int, h: Int, v: Values): Field {
        val cell = max(4.0, ceil(sqrt(w.toDouble() * h / 40_000)))
        val cols = max(1, ceil(w / cell).toInt())
        val rows = max(1, ceil(h / cell).toInt())
        val field = Field(w, h, cell, cols, rows)
        val base = Math.toRadians(v["baseAngle"].toDouble())
        val baseX = cos(base)
        val baseY = sin(base)
        val still = v["outside"] == 1
        val reach = v["reach"].toDouble()

        // the strokes in canvas pixels, cut into segments of about half the reach
        val segments = ArrayList<DoubleArray>()
        for (stroke in FlowStrokes.parse(v.text("strokes"))) {
            val pts = stroke.map { Point2D.Double(it.x * w, it.y * h) }
            val target = max(3.0, reach / 2)
            var start = pts[0]
            var travelled = 0.0
            for (i in 1 until pts.size) {
                travelled += pts[i].distance(pts[i - 1])
                if (travelled >= target || i == pts.size - 1) {
                    val len = pts[i].distance(start)
                    if (len > 1e-6) segments.add(doubleArrayOf(start.x, start.y, pts[i].x, pts[i].y, len))
                    start = pts[i]
                    travelled = 0.0
                }
            }
        }

        parallelRows(rows) { gy ->
            for (gx in 0 until cols) {
                val i = gy * cols + gx
                if (segments.isEmpty()) {
                    field.dx[i] = baseX.toFloat(); field.dy[i] = baseY.toFloat()
                    field.strength[i] = if (still) 0f else 1f
                    continue
                }
                val x = (gx + 0.5) * cell
                val y = (gy + 0.5) * cell
                var nearX = 0.0; var nearY = 0.0; var near = 0.0
                var farX = 0.0; var farY = 0.0
                for (s in segments) {
                    val ax = s[0]; val ay = s[1]; val bx = s[2]; val by = s[3]; val len = s[4]
                    val tx = (bx - ax) / len
                    val ty = (by - ay) / len
                    val t = ((x - ax) * tx + (y - ay) * ty).coerceIn(0.0, len)
                    val d2 = (ax + tx * t - x).let { it * it } + (ay + ty * t - y).let { it * it }
                    // gaussian falloff; the length weight makes a long stroke as strong as one segment of it
                    val g = len * exp(-d2 / (reach * reach)) / (reach * 1.7724538509)
                    nearX += g * tx; nearY += g * ty; near += g
                    val idw = len / (d2 + reach * reach)
                    farX += idw * tx; farY += idw * ty
                }
                if (still) {
                    field.dx[i] = nearX.toFloat(); field.dy[i] = nearY.toFloat()
                    field.strength[i] = min(1.0, near).toFloat()
                } else {
                    // near the strokes their own direction, further away the blend of all of them
                    val k = min(1.0, near)
                    val fl = hypot(farX, farY).coerceAtLeast(1e-12)
                    val nl = hypot(nearX, nearY).coerceAtLeast(1e-12)
                    field.dx[i] = (k * nearX / nl + (1 - k) * farX / fl).toFloat()
                    field.dy[i] = (k * nearY / nl + (1 - k) * farY / fl).toFloat()
                    field.strength[i] = 1f
                }
            }
        }
        return field
    }
}
