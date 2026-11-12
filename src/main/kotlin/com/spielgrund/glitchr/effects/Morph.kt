package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear

/**
 * Morph: from the picture below this layer to the layer itself, as the progress goes from 0 to
 * 100 %. Flowing: where things in the two pictures correspond is estimated (block matching, as
 * in video codecs), and both pictures are pushed towards each other along the way while they
 * cross-fade – shapes shift into one another instead of just fading. Or plainly cross-faded,
 * dissolved grain by grain, or wiped in by the layer's brightness.
 */
object Morph : Effect("morph", "Morph", "Morphs from the picture below to this layer – flowing (shapes shift into each other), cross-faded, dissolving or wiped in by brightness; animate the progress"), UsesBelow {
    private val modes = listOf("Flowing", "Cross-fade", "Dissolve", "Brightness wipe")

    override val params = listOf(
        Param.Slider("progress", "Progress", 0, 1000, 500, " %", "0 %: the picture below · 100 %: this layer – animate it", decimals = 1),
        Param.Choice(
            "mode", "Mode", modes, 0,
            "Flowing: shapes shift into one another along their estimated movement while they fade · Cross-fade: simply blended · Dissolve: grain by grain · Brightness wipe: the layer's bright parts first",
        ),
        Param.Slider("block", "Detail", 8, 64, 16, " px", "Flowing: the size of the blocks matched between the two pictures – smaller follows finer shapes, larger is calmer").shownWhen("mode", 0),
        Param.Slider("range", "Reach", 4, 256, 48, " px", "Flowing: how far a shape may travel to find its counterpart").shownWhen("mode", 0),
        Param.Slider("warp", "Warp", 0, 300, 100, " %", "Flowing: how far the pictures are pushed along the way").shownWhen("mode", 0),
        Param.Slider("grain", "Grain", 1, 200, 6, " px", "Dissolve: the size of the grains").shownWhen("mode", 2),
        Param.Slider("softness", "Softness", 0, 100, 20, " %", "Dissolve and wipe: how soft the edge between the two is").shownWhen("mode", 2, 3),
        Param.Toggle("reverse", "Reverse", false, "Wipe: the dark parts first"),
    )

    override val random = false

    /** Without a picture below there is nothing to morph from. */
    override fun apply(src: Pixels, v: Values, seed: Long): Pixels = src.copy()

    override fun apply(src: Pixels, below: Pixels?, v: Values, seed: Long, frames: FrameInput?): Pixels {
        if (below == null || below.width != src.width || below.height != src.height) return src.copy()
        val t = (v["progress"] / 1000.0).coerceIn(0.0, 1.0)
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        when (v["mode"]) {
            0 -> {
                if (t <= 0.0) return below.copy()
                if (t >= 1.0) return src.copy()
                // where each block of this layer came from in the picture below
                val field = MotionField.estimate(below, src, v["block"], v["range"])
                val warp = v["warp"] / 100.0
                parallelRows(h) { y ->
                    val d = FloatArray(2)
                    for (x in 0 until w) {
                        val px = x + 0.5
                        val py = y + 0.5
                        field.smoothAt(px, py, d)
                        // below: pushed forwards along the way; this layer: still back by the part not yet gone
                        val a = sampleBilinear(below, px + d[0] * t * warp, py + d[1] * t * warp, Edge.CLAMP)
                        val b = sampleBilinear(src, px - d[0] * (1 - t) * warp, py - d[1] * (1 - t) * warp, Edge.CLAMP)
                        out.data[y * w + x] = lerpArgb(a, b, t.toFloat())
                    }
                }
            }
            1 -> parallelRows(h) { y ->
                for (x in 0 until w) out.data[y * w + x] = lerpArgb(below.data[y * w + x], src.data[y * w + x], t.toFloat())
            }
            else -> {
                val dissolve = v["mode"] == 2
                val noise = Noise(seed)
                val grain = v["grain"].toDouble()
                val soft = 0.005 + v["softness"] / 100.0 * 0.5
                val reverse = v["reverse"] == 1
                parallelRows(h) { y ->
                    for (x in 0 until w) {
                        val i = y * w + x
                        // when this pixel turns: 0 = first, 1 = last
                        val order = if (dissolve) (noise.fbm(x / grain, y / grain, 2) * 0.5 + 0.5).coerceIn(0.0, 1.0)
                        else luma(src.data[i]) / 255.0
                        val turn = if (dissolve || reverse) order else 1 - order
                        // spread the edge so 0 % is all below and 100 % all this layer
                        val k = ((t * (1 + soft) - turn * (1 - soft) - soft / 2) / soft + 0.5).coerceIn(0.0, 1.0)
                        out.data[i] = lerpArgb(below.data[i], src.data[i], (k * k * (3 - 2 * k)).toFloat())
                    }
                }
            }
        }
        return out
    }
}
