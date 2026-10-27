package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.model.BlendMode
import kotlin.math.roundToInt

/**
 * A finishing stack in one: the picture blurred large, its shadows lifted and, if set, its lights
 * lowered, then laid over the sharp picture with soft light – soft glow, open shadows, more depth
 * in the colors. Strength mixes it with the picture; beyond 100 % it is laid over again and again, on
 * the result each time, as with the effect stacked on several layers.
 */
object SoftLightStack : Effect("softlightstack", "Soft light stack", "Finishing in one: a large blur with lifted shadows and lowered lights laid over the picture with soft light") {
    private val modes = listOf(BlendMode.SOFT_LIGHT, BlendMode.OVERLAY, BlendMode.SCREEN, BlendMode.MULTIPLY, BlendMode.LINEAR_LIGHT)

    override val params = listOf(
        Param.Slider("radius", "Blur", 0, 500, 40, " px", "How far the blur reaches – as with the blur effect"),
        Param.Slider("lift", "Lift shadows", 0, 100, 25, " %", "The blurred copy's black raised this far: its shadows open up, the lights stay"),
        Param.Slider("lower", "Lower lights", 0, 100, 0, " %", "The blurred copy's white lowered this far: its lights calm down, the shadows stay"),
        Param.Choice("blend", "Laid over with", modes.map { it.label }, 0, "How the blurred copy is laid over the picture"),
        Param.Slider(
            "amount", "Strength", 0, 500, 100, " %",
            "Up to 100 % mixed with the picture; beyond, the stack is laid over again and again, as with layers stacked: 200 % = twice, 250 % = twice and once half",
        ),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        // above 100 %: one pass on the result of the one before, as with the effect stacked on layers; the last one partly
        val total = v["amount"] / 100f
        if (total <= 0f) return src.copy()
        var out = src
        var done = 0f
        while (done < total - 1e-4f) {
            out = pass(out, v, minOf(1f, total - done))
            done += 1f
        }
        return out
    }

    /** One stack laid over [src], mixed with it by [amount] (0 … 1). */
    private fun pass(src: Pixels, v: Values, amount: Float): Pixels {
        val w = src.width
        val h = src.height
        // the blurred copy, premultiplied so transparent parts don't bleed dark into it
        val planes = Array(4) { FloatArray(w * h) }
        for (i in src.data.indices) {
            val c = src.data[i]
            val a = (c ushr 24) / 255f
            planes[0][i] = (c shr 16 and 0xFF) * a
            planes[1][i] = (c shr 8 and 0xFF) * a
            planes[2][i] = (c and 0xFF) * a
            planes[3][i] = a
        }
        val sigma = v["radius"] / 2.0
        for (p in 0..3) planes[p] = Blur.gauss(planes[p], w, h, sigma)
        val lift = v["lift"] / 100.0
        val lower = v["lower"] / 100.0
        val blend = modes[v["blend"].coerceIn(0, modes.size - 1)]
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                val a = planes[3][i]
                if ((c ushr 24) == 0 || a <= 1e-6f) { out.data[i] = c; continue }
                fun tone(p: Int): Int {
                    // black becomes the lift, white comes down by the lowering, in between in a straight line
                    val t = lift + (1 - lower - lift) * (planes[p][i] / a / 255.0)
                    return (t * 255).roundToInt().coerceIn(0, 255)
                }
                val top = (c and 0xFF000000.toInt()) or (tone(0) shl 16) or (tone(1) shl 8) or tone(2)
                val mixed = blend.apply(c, top)
                fun ch(s: Int) = ((c shr s and 0xFF) + ((mixed shr s and 0xFF) - (c shr s and 0xFF)) * amount).roundToInt().coerceIn(0, 255)
                out.data[i] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }
}
