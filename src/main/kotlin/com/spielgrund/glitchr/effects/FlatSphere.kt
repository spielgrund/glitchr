package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Flat sphere: a big flat 2D ball reaching into the picture from the top or bottom edge (or both),
 * like a lens laid on it – inside its round outline the picture swells out from its middle (or is
 * drawn in), so its lines fan out towards the rim; outside nothing changes. Its rim can be darkened
 * like a sphere's edge and drawn as an outline.
 */
object FlatSphere : Effect("flatsphere", "Flat sphere", "A flat 2D ball reaching into the picture from the top or bottom edge: inside it the picture swells like under a lens, its lines fanning out to the rim") {
    private val places = listOf("Top", "Bottom", "Top and bottom")

    override val params = listOf(
        Param.Choice("place", "Where", places, 0, "Which edge the ball reaches in from"),
        Param.Slider("centerX", "Across", -10000, 10000, 0, " %", "Where the ball sits across, from the middle", decimals = 2),
        Param.Slider("size", "Size", 5, 400, 90, " %", "The ball's width, of the picture width"),
        Param.Slider("reach", "Reach", 1, 100, 35, " %", "How far into the picture it reaches, of the height"),
        Param.Slider("flatten", "Flatten", 0, 100, 0, " %", "Widens and flattens the ball's outline: at 100 % it ends in a straight line across the picture at its reach"),
        Param.Slider("bulge", "Bulge", -1000, 1000, 80, " %", "Positive: the picture swells out from the middle of the ball, squeezed towards the rim · negative: the other way – swelling towards the rim, drawn together in the middle"),
        Param.Slider("fan", "Fan out", 0, 200, 60, " %", "The lines fan out towards the rim: the picture turned around the ball's middle, more further out"),
        Param.Slider("rimShade", "Rim shade", 0, 100, 25, " %", "Darker towards the rim, like the edge of a sphere"),
        Param.Slider("outline", "Outline", 0, 40, 0, " px", "A line around the ball", decimals = 0),
        Param.Color("outlineColor", "Outline color", 0xFF3018),
        Param.Choice("edge", "Edge", listOf("Mirror", "Stretch edge", "Transparent"), 0, "Where the ball needs picture from beyond its edge"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val place = v["place"]
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val radius = (w * v["size"] / 200.0).coerceAtLeast(1.0)
        val reach = h * v["reach"] / 100.0
        val bulge = v["bulge"] / 100.0
        // flattened: the same round lens around the same middle, only its boundary bent from the circle towards
        // the straight line at its reach – at 100 % it runs out exactly on that line, right across the picture
        val flatten = v["flatten"] / 100.0
        val fan = v["fan"] / 100.0
        val rimShade = v["rimShade"] / 100.0
        val outline = v["outline"].toDouble()
        val oc = v["outlineColor"]
        val edge = when (v["edge"]) { 1 -> Edge.CLAMP; 2 -> null; else -> Edge.MIRROR }
        // the balls: their middles beyond the edge, so that they reach this far in
        val centers = buildList {
            if (place == 0 || place == 2) add(doubleArrayOf(reach - radius, 1.0))
            if (place == 1 || place == 2) add(doubleArrayOf(h - reach + radius, -1.0))
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var col = src.data[i]
                for (c in centers) {
                    val cy = c[0]
                    val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
                    val r = hypot(dx, dy)
                    // how far the boundary lies in this direction: the circle, or bent towards the straight line
                    val scale = if (flatten <= 0) radius else {
                        // towards the line (straight down for a top ball, up for a bottom one) it lies where the ray meets it
                        val toward = dy * c[1] / r.coerceAtLeast(1e-9)
                        val line = if (toward > 1e-6) radius / toward else radius * 4
                        radius + (line.coerceAtMost(radius * 4) - radius) * flatten
                    }
                    // how far out towards the boundary, 0 in the middle, 1 on it – the transformation as for the round ball
                    val k = r / scale
                    if (k >= 1 + outline / scale) continue
                    if (k < 1) {
                        // swelling: from nearer the middle, the more the bulge; the rim stays in place
                        val kk = if (bulge >= 0) k.pow(1 + bulge) else 1 - (1 - k).pow(1 - bulge)
                        // fanning: turned around the middle, more further out and away from straight down (or up)
                        val side = dx / r.coerceAtLeast(1e-9)
                        val turn = fan * 0.6 * side * k * k * c[1]
                        val ux = dx / r.coerceAtLeast(1e-9); val uy = dy / r.coerceAtLeast(1e-9)
                        val tx = ux * kotlin.math.cos(turn) - uy * kotlin.math.sin(turn)
                        val ty = ux * kotlin.math.sin(turn) + uy * kotlin.math.cos(turn)
                        // the same direction, nearer or farther: its place on the way to the outline moved from k to kk
                        val sx = cx + tx * r * (kk / k.coerceAtLeast(1e-9))
                        val sy = cy + ty * r * (kk / k.coerceAtLeast(1e-9))
                        col = if (edge == null && (sx < 0 || sy < 0 || sx >= w || sy >= h)) 0 else sampleBilinear(src, sx, sy, edge ?: Edge.CLAMP)
                        // darker towards the rim, like a sphere's edge
                        if (rimShade > 0) {
                            val shade = 1 - rimShade * (1 - sqrt((1 - k * k).coerceAtLeast(0.0))).pow(1.5)
                            fun ch(sh: Int) = ((col shr sh and 0xFF) * shade).roundToInt().coerceIn(0, 255)
                            col = (col and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                        }
                    }
                    // the outline, smooth at its edges
                    if (outline > 0) {
                        val d = abs(k - 1) * scale
                        val a = (outline / 2 + 0.5 - d).coerceIn(0.0, 1.0)
                        if (a > 0) {
                            fun mix(sh: Int) = ((col shr sh and 0xFF) * (1 - a) + (oc shr sh and 0xFF) * a).roundToInt()
                            col = (max(col ushr 24, (a * 255).roundToInt()) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                        }
                    }
                }
                out.data[i] = col
            }
        }
        return out
    }
}
