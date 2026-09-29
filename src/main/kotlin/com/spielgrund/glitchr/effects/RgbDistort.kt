package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.channel
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shifts the red, green and blue channels independently, with an optional per-row wave, and can reorder them. */
object RgbDistort : Effect("rgb", "RGB-Distort", "Verschiebt und verbiegt die Farbkanäle gegeneinander") {
    private val orders = listOf("RGB", "RBG", "GRB", "GBR", "BRG", "BGR")

    override val params = listOf(
        Param.Slider("rx", "Rot X", -300, 300, 12, " px"),
        Param.Slider("ry", "Rot Y", -300, 300, 0, " px"),
        Param.Slider("gx", "Grün X", -300, 300, 0, " px"),
        Param.Slider("gy", "Grün Y", -300, 300, 0, " px"),
        Param.Slider("bx", "Blau X", -300, 300, -12, " px"),
        Param.Slider("by", "Blau Y", -300, 300, 0, " px"),
        Param.Slider("waveAmp", "Welle Stärke", 0, 300, 0, " px", "Verschiebt jede Zeile sinusförmig zur Seite"),
        Param.Slider("waveLength", "Welle Länge", 2, 2000, 120, " px"),
        Param.Toggle("wavePhase", "Welle je Kanal versetzt", true),
        Param.Choice("order", "Kanalreihenfolge", orders),
        Param.Choice("edge", "Rand", Edge.labels),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val dx = intArrayOf(v["rx"], v["gx"], v["bx"])
        val dy = intArrayOf(v["ry"], v["gy"], v["by"])
        val amp = v["waveAmp"].toDouble()
        val waveLength = v["waveLength"].toDouble()
        val phased = v.bool("wavePhase")
        val order = orders[v["order"]].map { "RGB".indexOf(it) }
        val edge = Edge.entries[v["edge"]]

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val wave = IntArray(3) { c ->
                if (amp == 0.0) 0
                else (amp * sin(2 * PI * y / waveLength + if (phased) c * 2 * PI / 3 else 0.0)).roundToInt()
            }
            val rows = IntArray(3) { c -> edge.resolve(y - dy[c], h) * w }
            val ch = IntArray(3)
            for (x in 0 until w) {
                // the most opaque of the three samples, so shifted channels show outside the picture too
                var a = 0
                for (c in 0..2) {
                    val sample = src.data[rows[c] + edge.resolve(x - dx[c] - wave[c], w)]
                    ch[c] = channel(sample, c)
                    a = kotlin.math.max(a, alpha(sample))
                }
                out.data[y * w + x] = argb(a, ch[order[0]], ch[order[1]], ch[order[2]])
            }
        }
        return out
    }
}
