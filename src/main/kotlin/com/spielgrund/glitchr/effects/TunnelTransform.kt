package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns the picture like the view down a tunnel: the picture is taken as the tunnel seen from
 * inside, its middle the far end, its edge right in front of us. Turned left, right, up or down
 * (X and Y: where the far end swings to, dragged with the handle), the far end swings over
 * furthest, the nearer rings less and less, and from the fixed radius on nothing moves – rings
 * around the middle slide apart on one side and together on the other.
 * Each point moves by the turn times (1 − its distance from the middle / the radius) to the power
 * of the depth curve; the picture is found by working that back (a few fixed-point steps).
 * Square, the tunnel is a square shaft: its rings are squares, the distance the larger of the two.
 *
 * Copies: the tunnel is built from copies instead, like Feedback – the picture again and again,
 * each smaller than the one before and cut out as a circle or the canvas's rectangle, nested into
 * each other. Turned, the deeper copies move over further and further: the outermost stays, the
 * innermost goes all the way to X/Y. A copy shows the whole picture made small or only its own
 * part of the picture, shifted; their edges can be blended softly into the copy around them.
 */
object TunnelTransform : Effect("tunneltransform", "Tunnel Transform", "Turns the picture like the view down a tunnel – left, right, up or down: the middle swings furthest, the edge stays") {
    private val shapes = listOf("Round", "Square")
    private const val BEND = 0
    private const val COPIES = 1

    override val params = listOf(
        Param.Choice("mode", "Mode", listOf("Bend", "Copies"), BEND, "Bend: the picture bends like rubber, the rings slide together and apart · Copies: the picture copied smaller and smaller into itself, every copy moved over a bit further, like Feedback"),
        Param.Choice("shape", "Shape", shapes, 0, "Round: a round tunnel, its rings circles · Square: a square shaft, its rings squares – copied: the canvas's rectangle"),
        Param.Slider("turnX", "X", -10000, 10000, 0, " %", "Where the far end swings: to the right, or to the left with minus – 100 % = the canvas edge; can also be dragged with the handle in the picture", decimals = 2),
        Param.Slider("turnY", "Y", -10000, 10000, 0, " %", "Down, or up with minus – 100 % = the canvas edge", decimals = 2),
        Param.Slider("radius", "Radius", 500, 30000, 10000, " %", "From the middle out to where nothing moves any more – round of half the canvas diagonal, square of half the longer side", decimals = 2).shownWhen("mode", BEND),
        Param.Slider("copies", "Copies", 2, 60, 10, tip = "How many copies lie inside each other, the picture itself the outermost").shownWhen("mode", COPIES),
        Param.Slider("scale", "Scale per copy", 30, 98, 82, " %", "Each copy this much the size of the one around it").shownWhen("mode", COPIES),
        Param.Choice("content", "Copies show", listOf("The whole picture", "Their part of the picture"), 0, "The whole picture made small, like Feedback, or only the part of the picture where the copy lies, moved along").shownWhen("mode", COPIES),
        Param.Slider("soft", "Soft edges", 0, 200, 0, " px", "Every copy's edge blends this far into the copy around it – 0: hard edges").shownWhen("mode", COPIES),
        Param.Slider("curve", "Depth curve", 30, 400, 120, " %", "How the turn grows towards the middle: 100 % evenly, more = mostly the deep middle swings, less = the near rings (or copies) go along more"),
        Param.Choice("edge", "Edge", Edge.entries.map { it.label }, Edge.MIRROR.ordinal, "What comes in from beyond the picture's edge"),
    )

    override val random = false

    // the handle is where the far end has swung to
    override val canvasHandle = CanvasHandle("turnX", "turnY", percent = true)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // the tunnel's far end lies in the middle of the canvas and swings by (X, Y), in percent of half the canvas
        val cx = w / 2.0
        val cy = h / 2.0
        val dx = v["turnX"] / 10000.0 * w / 2
        val dy = v["turnY"] / 10000.0 * h / 2
        val curve = v["curve"] / 100.0
        val square = v["shape"] == 1
        // round the distance is the usual one, square the larger of the two sides
        fun dist(x: Double, y: Double) = if (square) max(abs(x), abs(y)) else hypot(x, y)
        val reach = if (square) max(w, h) / 2.0 else hypot(w.toDouble(), h.toDouble()) / 2
        // swung further than the radius allows, the rings would fold over each other: then the turn reaches further out
        // (square, a ring moves the turn's larger side towards its neighbour)
        val step = if (square) max(abs(dx), abs(dy)) else hypot(dx, dy)
        val radius = max(max(1.0, reach * v["radius"] / 10000.0), step * max(1.0, curve) / 0.9)
        val edge = Edge.entries[v["edge"].coerceIn(0, Edge.entries.size - 1)]
        if (v["mode"] == COPIES) return copies(src, v, dx, dy, curve, square, edge)
        if (dx == 0.0 && dy == 0.0) return src.copy()

        /** How much of the turn a point at distance [d] from the middle takes: 1 at the far end, 0 from the radius on. */
        fun share(d: Double) = (1 - d / radius).coerceAtLeast(0.0).pow(curve)

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                // the point q that lands here: q + turn · share(|q − center|) = p, found step by step from p
                var qx = px
                var qy = py
                repeat(12) {
                    val s = share(dist(qx - cx, qy - cy))
                    val nx = px - dx * s
                    val ny = py - dy * s
                    // a little damped, so it settles even where the rings crowd together
                    qx += (nx - qx) * 0.8
                    qy += (ny - qy) * 0.8
                }
                out.data[y * w + x] = sampleBilinear(src, qx, qy, edge)
            }
        }
        return out
    }

    /**
     * The copy tunnel: copy k (0 = the picture itself) is scale^k the size, its middle moved by
     * the turn times (k / (copies − 1))^curve. A point shows the deepest copy it lies in, within
     * [soft edges] of that copy's edge blended into the copy around it; 2 × 2 samples per pixel
     * smooth hard edges.
     */
    private fun copies(src: Pixels, v: Values, dx: Double, dy: Double, curve: Double, square: Boolean, edge: Edge): Pixels {
        val w = src.width
        val h = src.height
        val cx = w / 2.0
        val cy = h / 2.0
        val n = v["copies"]
        val scale = v["scale"] / 100.0
        val whole = v["content"] == 0
        val soft = v["soft"].toDouble()
        val sizes = DoubleArray(n) { scale.pow(it) }
        val ox = DoubleArray(n) { dx * (it.toDouble() / (n - 1)).pow(curve) }
        val oy = DoubleArray(n) { dy * (it.toDouble() / (n - 1)).pow(curve) }
        // half the copy's width and height (square: the canvas's rectangle), or its radius (round)
        val halfW = w / 2.0
        val halfH = h / 2.0
        val radius = min(w, h) / 2.0

        /** The color at (px, py): from the deepest copy up to [deepest] that holds it. */
        fun at(px: Double, py: Double, deepest: Int = n - 1): Int {
            for (k in deepest downTo 1) {
                val rx = px - cx - ox[k]
                val ry = py - cy - oy[k]
                val z = sizes[k]
                // how far inside the copy's edge the point lies
                val inside = if (square) min(halfW * z - abs(rx), halfH * z - abs(ry)) else radius * z - hypot(rx, ry)
                if (inside < 0) continue
                val c = if (whole) sampleBilinear(src, cx + rx / z, cy + ry / z, edge)
                else sampleBilinear(src, px - ox[k], py - oy[k], edge)
                if (inside >= soft) return c
                // near the edge: faded into the copy around it (which may itself fade further out)
                val t = inside / soft
                return lerpArgb(at(px, py, k - 1), c, (t * t * (3 - 2 * t)).toFloat())
            }
            return sampleBilinear(src, px, py, edge)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (sy in 0..1) for (sx in 0..1) {
                    val c = at(x + 0.25 + sx * 0.5, y + 0.25 + sy * 0.5)
                    a += c ushr 24; r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
                }
                out.data[y * w + x] = ((a + 2) / 4 shl 24) or ((r + 2) / 4 shl 16) or ((g + 2) / 4 shl 8) or ((b + 2) / 4)
            }
        }
        return out
    }
}
