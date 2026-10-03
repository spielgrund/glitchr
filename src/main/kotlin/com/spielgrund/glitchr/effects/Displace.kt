package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.floor

/**
 * Displaces the picture along noise: every pixel takes its color from a spot the noise
 * points to, so the picture flows into the noise pattern. How strongly each area is
 * displaced is set by the layer's mask – a gradient mask gives a smooth transition.
 * (The id stays "noise" so projects from when this was called "Noise-Verlauf" still load.)
 */
object Displace : Effect("noise", "Displace", "Shifts the picture's pixels along a noise pattern; the effect mask controls the strength") {
    private val types = listOf("Perlin", "Fractal (fBm)", "Ridged", "Cells (Worley)", "Value noise", "White noise")

    override val params = listOf(
        Param.Choice("type", "Noise", types),
        Param.Slider("scale", "Size", 2, 1000, 80, " px", "Size of the noise structures"),
        Param.Slider("octaves", "Detail levels", 1, 8, 4, tip = "Fractal and Ridged only: how many finer layers are added"),
        Param.Slider("strength", "Strength", 0, 300, 100, " %", "How far it shifts, relative to the size. For seamless transitions use the layer's mask, e.g. a gradient"),
        Param.Choice("edge", "Edge", Edge.labels, default = Edge.MIRROR.ordinal),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val noise = Noise(seed)
        val type = v["type"]
        val octaves = v["octaves"]
        val scale = v["scale"].toDouble()
        val strength = v["strength"] / 100.0
        val edge = Edge.entries[v["edge"]]
        val amount = scale * strength

        fun n(x: Double, y: Double): Double = when (type) {
            1 -> noise.fbm(x, y, octaves)
            2 -> noise.ridged(x, y, octaves)
            3 -> noise.worley(x, y)
            4 -> noise.value(x, y)
            5 -> noise.white(floor(x).toInt(), floor(y).toInt())
            else -> noise.perlin(x, y)
        }

        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val u = x / scale
                val t = y / scale
                val dx = n(u, t) * amount
                val dy = n(u + 19.1, t + 47.7) * amount
                out.data[y * w + x] = sampleBilinear(src, x + 0.5 + dx, y + 0.5 + dy, edge)
            }
        }
        return out
    }
}
