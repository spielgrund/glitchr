package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Moves random horizontal (or vertical) bands of the image sideways, optionally only one color channel. */
object SliceShift : Effect("slices", "Zeilenversatz", "Verschiebt zufällige Streifen des Bildes seitlich") {
    override val params = listOf(
        Param.Choice("direction", "Streifen", listOf("Waagrecht", "Senkrecht")),
        Param.Slider("count", "Anzahl", 1, 400, 24),
        Param.Slider("minSize", "Min. Dicke", 1, 500, 2, " px"),
        Param.Slider("maxSize", "Max. Dicke", 1, 500, 40, " px"),
        Param.Slider("shift", "Max. Versatz", 0, 3000, 120, " px"),
        Param.Choice("channels", "Kanäle", channelOptions),
        Param.Choice("edge", "Rand", Edge.labels),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val random = Random(seed)
        val vertical = v["direction"] == 1
        val lo = min(v["minSize"], v["maxSize"])
        val hi = max(v["minSize"], v["maxSize"])
        val maxShift = v["shift"]
        val edge = Edge.entries[v["edge"]]
        val w = src.width
        val h = src.height
        val across = if (vertical) w else h
        val along = if (vertical) h else w

        val out = src.copy()
        repeat(v["count"]) {
            val size = random.nextInt(lo, hi + 1)
            val start = random.nextInt(0, across)
            val shift = if (maxShift == 0) 0 else random.nextInt(-maxShift, maxShift + 1)
            val bits = channelBits(v["channels"], random)
            for (a in start until min(across, start + size)) {
                for (b in 0 until along) {
                    val from = edge.resolve(b - shift, along)
                    val dst = if (vertical) b * w + a else a * w + b
                    val c = if (vertical) src.data[from * w + a] else src.data[a * w + from]
                    out.data[dst] = mergeChannels(out.data[dst], c, bits)
                }
            }
        }
        return out
    }
}
