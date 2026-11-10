package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Turns the picture like the view down a tunnel: the picture is taken as the tunnel seen from
 * inside, its middle the far end, its edge right in front of us. Turned left, right, up or down
 * (X and Y: where the far end swings to, dragged with the handle), the far end swings over
 * furthest, the nearer rings less and less, and from the fixed radius on nothing moves – rings
 * around the middle slide apart on one side and together on the other.
 * Each point moves by the turn times (1 − its distance from the middle / the radius) to the power
 * of the depth curve; the picture is found by working that back (a few fixed-point steps).
 */
object TunnelTransform : Effect("tunneltransform", "Tunnel Transform", "Turns the picture like the view down a tunnel – left, right, up or down: the middle swings furthest, the edge stays") {
    override val params = listOf(
        Param.Slider("turnX", "X", -10000, 10000, 0, " %", "Where the far end swings: to the right, or to the left with minus – 100 % = the canvas edge; can also be dragged with the handle in the picture", decimals = 2),
        Param.Slider("turnY", "Y", -10000, 10000, 0, " %", "Down, or up with minus – 100 % = the canvas edge", decimals = 2),
        Param.Slider("radius", "Radius", 500, 30000, 10000, " %", "From the middle out to where nothing moves any more, of half the canvas diagonal", decimals = 2),
        Param.Slider("curve", "Depth curve", 30, 400, 120, " %", "How the turn grows towards the middle: 100 % evenly, more = mostly the deep middle swings, less = the near rings go along more"),
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
        // swung further than the radius allows, the rings would fold over each other: then the turn reaches further out
        val radius = max(max(1.0, hypot(w.toDouble(), h.toDouble()) / 2 * v["radius"] / 10000.0), hypot(dx, dy) * max(1.0, curve) / 0.9)
        val edge = Edge.entries[v["edge"].coerceIn(0, Edge.entries.size - 1)]
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
                    val s = share(hypot(qx - cx, qy - cy))
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
}
