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
object Displace : Effect("noise", "Displace", "Verschiebt die Bildpixel entlang eines Noise-Musters; die Effektmaske steuert die Stärke") {
    private val types = listOf("Perlin", "Fraktal (fBm)", "Ridged", "Zellen (Worley)", "Wert-Noise", "Weisses Rauschen")

    override val params = listOf(
        Param.Choice("type", "Noise", types),
        Param.Slider("scale", "Grösse", 2, 1000, 80, " px", "Grösse der Noise-Strukturen"),
        Param.Slider("octaves", "Detailstufen", 1, 8, 4, tip = "Nur Fraktal und Ridged: wie viele feinere Lagen dazukommen"),
        Param.Slider("strength", "Stärke", 0, 300, 100, " %", "Wie weit verschoben wird, im Verhältnis zur Grösse. Für stufenlose Übergänge die Maske der Ebene nutzen, z. B. einen Verlauf"),
        Param.Choice("edge", "Rand", Edge.labels, default = Edge.MIRROR.ordinal),
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
