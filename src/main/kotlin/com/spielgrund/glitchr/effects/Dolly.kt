package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The camera drives into the picture (or pulls back), in 2.5D by the estimated depth: every
 * place grows by how far it is from the camera, so near things come towards it much faster than
 * far ones and cover what lies behind them. Driving towards a vanishing point that can be dragged.
 * With the dolly zoom (vertigo) the lens widens at the same time, so the focal plane keeps its
 * size while the background rushes away – or towards it. Handheld: the camera shakes a little,
 * swaying with parallax around a steady depth that stays still, and tilting.
 *
 * Found backwards as in [Wiggle]: for an output pixel the place it shows is searched over the
 * depths, from the nearest to the farthest, and the first one whose own growth and sway bring it
 * here wins.
 */
object Dolly : Effect("dolly", "Camera dolly", "2.5D: the camera drives into the picture by the estimated depth – near things come closer faster and cover the rest; with dolly zoom (vertigo)") {
    override val params = listOf(
        Param.Heading("cameraHeading", "Camera"),
        Param.Slider("move", "Drive in", -20000, 9500, 3000, " %", "How far the camera drives in, of the distance to the nearest thing – animate it · negative: it pulls back", decimals = 2),
        Param.Slider("centerX", "Towards X", -20000, 20000, 0, " %", "The point the camera drives towards, from the middle: 100 % = the right edge – can also be dragged with the handle", decimals = 2),
        Param.Slider("centerY", "Towards Y", -20000, 20000, 0, " %", "100 % = the bottom edge", decimals = 2),
        Param.Slider("range", "Depth range", 11, 500, 40, " ×", "How many times farther the farthest thing is than the nearest: more = the background stays behind more", decimals = 1),
        Param.Toggle("vertigo", "Dolly zoom", false, "Vertigo: the lens widens while driving in, so the focal plane keeps its size and the background rushes away"),
        Param.Slider("focus", "Focal plane", 0, 100, 50, " %", "Dolly zoom: this depth keeps its size (0 % far, 100 % near)").shownWhen("vertigo", 1),
        Param.Choice("edge", "Edge", Transform.edgeOptions, 3, "Pulling back or shaking: what comes in from beyond the picture"),
        Param.Heading("handheldHeading", "Handheld"),
        Param.Slider("handheld", "Handheld", 0, 100, 0, " %", "The camera shakes a little, as if held in the hand: it sways and tilts – the steady depth stays still, the rest shifts the more, the farther from it. Animate the time"),
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it and the camera shakes", decimals = 3).shownWhen("handheld", *(1..100).toList().toIntArray()),
        Param.Slider("shakeSpeed", "Shake speed", 10, 400, 100, " %", "How nervous the hands are").shownWhen("handheld", *(1..100).toList().toIntArray()),
        Param.Slider("shakeRoll", "Roll", 0, 200, 50, " %", "How much the camera also tilts around its axis").shownWhen("handheld", *(1..100).toList().toIntArray()),
        Param.Slider("steady", "Steady depth", 0, 100, 0, " %", "Which depth stays still while the camera shakes: 0 % the background stays, the front moves · 100 % the front stays, the background moves · in between: a depth in the middle stays, in front and behind move opposite ways").shownWhen("handheld", *(1..100).toList().toIntArray()),
    ) + Depth.params

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "centerY", percent = true)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val depth = Depth.estimate(src, v)
        if (v.bool("depthShow")) return Depth.show(src, depth)
        val m = v["move"] / 10000.0
        val range = v["range"] / 10.0
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val cy = h / 2.0 * (1 + v["centerY"] / 10000.0)
        // a place's distance from the camera before the move: the nearest 1, the farthest [range]
        fun distance(d: Double) = 1 + (range - 1) * (1 - d)
        // how much it shrinks back (1 / its growth): its distance after the move over before
        val keep = if (v.bool("vertigo")) distance(v["focus"] / 100.0).let { it / (it - m) } else 1.0
        fun shrink(d: Double): Double { val z = distance(d); return (z - m) / z * keep }
        // the hand: the camera sways sideways (with parallax around the steady depth) and tilts
        val hand = v["handheld"] / 100.0
        val short = minOf(w, h).toDouble()
        val time = v["time"] / 1000.0 * v["shakeSpeed"] / 100.0
        fun wobble(seedAt: Int): Double {
            // a few slow and quicker swings over each other, never repeating soon
            var sum = 0.0
            val freqs = doubleArrayOf(0.31, 0.73, 1.57, 3.9)
            val amps = doubleArrayOf(0.45, 0.3, 0.17, 0.08)
            for (k in freqs.indices) {
                val ph = ((seedAt * 7 + k * 13) * 0.6180339887 % 1.0) * 2 * PI
                sum += amps[k] * sin(2 * PI * freqs[k] * (1 + 0.07 * seedAt) * time + ph)
            }
            return sum
        }
        val swayX = wobble(1) * hand * short * 0.025
        val swayY = wobble(2) * hand * short * 0.018
        val roll = Math.toRadians(wobble(5) * hand * 1.2 * v["shakeRoll"] / 100.0)
        if (m == 0.0 && keep == 1.0 && hand == 0.0) return src.copy()
        // how much a place sways: by how near it is after the move, measured from the steady depth, which stays
        // still – the farthest of the two ends from it sways by the whole amount
        fun nearness(d: Double) = 1 / (distance(d) - m)
        val still = nearness(v["steady"] / 100.0)
        val most = maxOf(kotlin.math.abs(nearness(1.0) - still), kotlin.math.abs(nearness(0.0) - still), 1e-9)
        fun sway(d: Double) = (nearness(d) - still) / most
        val edge = Transform.edgeOf(v["edge"])
        fun sample(x: Double, y: Double) = if (edge == null) sampleTransparent(src, x, y) else sampleBilinear(src, x, y, edge)
        val rc = cos(roll); val rs = sin(roll)
        val mx = w / 2.0; val my = h / 2.0

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                // undo the tilt around the middle of the picture
                val ox = x + 0.5 - mx; val oy = y + 0.5 - my
                val qx = mx + ox * rc + oy * rs
                val qy = my - ox * rs + oy * rc
                // where a place of depth d would have to be to land here, and the depth that is really there
                fun from(d: Double): Pair<Double, Double> {
                    val t = shrink(d)
                    val k = sway(d)
                    return Pair(cx + (qx - swayX * k - cx) * t, cy + (qy - swayY * k - cy) * t)
                }
                fun gap(d: Double): Double { val (px, py) = from(d); return Wiggle.depthAt(depth, w, h, px, py) - d }
                // from the nearest depth back: the first place that fits is the surface in front
                val (ax, ay) = from(1.0)
                val (bx, by) = from(0.0)
                val steps = ceil(hypot(ax - bx, ay - by) / 0.5).toInt().coerceIn(1, 400)
                var d = 1.0
                var prev = gap(d)
                var best = d
                var bestGap = abs(prev)
                for (k in 1..steps) {
                    val nd = 1.0 - k.toDouble() / steps
                    val g = gap(nd)
                    if (abs(g) < bestGap) { bestGap = abs(g); best = nd }
                    if ((prev <= 0 && g >= 0) || (prev >= 0 && g <= 0)) {
                        val f = if (abs(g - prev) < 1e-12) 0.0 else prev / (prev - g)
                        best = d + (nd - d) * f
                        break
                    }
                    d = nd
                    prev = g
                }
                val (px, py) = from(best)
                out.data[y * w + x] = sample(px, py)
            }
        }
        return out
    }
}
