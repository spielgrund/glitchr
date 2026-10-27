package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.roundToInt

/**
 * Tone curves on their own (as in the color correction, see [Curves]): the RGB curve, then the
 * one of each channel. On the colors they bend each channel; on the brightness only, the RGB
 * curve changes how light a pixel is and keeps its hue and saturation (like a luminosity blend),
 * the channel curves still tint it. Mixed with the picture as set.
 */
object CurvesEffect : Effect("curves", "Curves", "Tone curves for RGB and each channel – on the colors or on the brightness only") {
    override val params = listOf(
        Param.Curve("curves", "Curves", "Click sets a point, dragging moves it, right-click removes it"),
        Param.Choice(
            "applyTo", "RGB curve on", listOf("The colors", "The brightness only"), 0,
            "The colors: each channel bent by it – contrast curves saturate too · The brightness only: hue and saturation stay as they are",
        ),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val curves = Curves.luts(v.text("curves")) ?: return src.copy()
        val brightnessOnly = v["applyTo"] == 1
        val amount = v["amount"] / 100.0
        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            val c3 = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                if (alpha(c) == 0) { out.data[i] = c; continue }
                c3[0] = red(c) / 255.0
                c3[1] = green(c) / 255.0
                c3[2] = blue(c) / 255.0
                if (brightnessOnly) {
                    // the RGB curve on the brightness, the colors scaled along (or, near white, lifted towards it)
                    val l = 0.2126 * c3[0] + 0.7152 * c3[1] + 0.0722 * c3[2]
                    val nl = Curves.apply(curves[0], l)
                    if (l > 1e-6) {
                        val k = nl / l
                        for (j in 0..2) c3[j] *= k
                        // a channel pushed over white: the color pales towards the gray of that brightness just enough to fit,
                        // so the brightness is kept instead of clipping away the hue
                        val top = maxOf(c3[0], c3[1], c3[2])
                        if (top > 1) {
                            if (nl >= 1) for (j in 0..2) c3[j] = 1.0
                            else {
                                val t = (top - 1) / (top - nl)
                                for (j in 0..2) c3[j] = c3[j] + (nl - c3[j]) * t
                            }
                        }
                    } else for (j in 0..2) c3[j] = nl
                    for (j in 0..2) c3[j] = Curves.apply(curves[j + 1], c3[j])
                } else {
                    for (j in 0..2) c3[j] = Curves.apply(curves[j + 1], Curves.apply(curves[0], c3[j]))
                }
                fun ch(k: Int, o: Int) = (o + ((c3[k] * 255).coerceIn(0.0, 255.0) - o) * amount).roundToInt().coerceIn(0, 255)
                out.data[i] = (c and 0xFF000000.toInt()) or (ch(0, red(c)) shl 16) or (ch(1, green(c)) shl 8) or ch(2, blue(c))
            }
        }
        return out
    }
}
