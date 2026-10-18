package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Glow: the bright parts of the picture (above a soft threshold) shine into their
 * surroundings. The light is blurred in three sizes for a soft, deep falloff; red can reach
 * further than blue (color fringe), long streaks can run through the brightest spots like
 * with an anamorphic lens, and a filmic tone mapping keeps it from burning out.
 */
object Glow : Effect("glow", "Glow", "The bright parts shine into their surroundings: soft bloom with color fringe, light streaks and filmic tone mapping") {
    override val params = listOf(
        Param.Slider("threshold", "Threshold", 0, 100, 60, " %", "From which brightness on the picture glows"),
        Param.Slider("knee", "Softness", 0, 100, 30, " %", "How gently the glow sets in around the threshold"),
        Param.Slider("strength", "Strength", 0, 300, 100, " %"),
        Param.Slider("radius", "Radius", 1, 300, 20, " px", "How far the glow reaches; it falls off in three sizes for a soft, deep halo"),
        Param.Choice("color", "Color", listOf("From picture", "White", "Custom"), tip = "From picture: every light glows in its own color"),
        Param.Color("glowColor", "Glow color", 0xFFB060, "For “Custom”"),
        Param.Slider("saturation", "Saturation", 0, 300, 100, " %", "Makes the glow's colors stronger or paler"),
        Param.Slider("fringe", "Color fringe", 0, 100, 20, " %", "Red reaches further than blue: colored rims around the lights"),
        Param.Choice("blend", "Blend", listOf("Screen", "Add"), tip = "Screen: soft, never above white · Add: stronger, burns out sooner"),
        Param.Toggle("glowOnly", "Glow only", false, "Shows only the glow on black, e.g. to blend it yourself in the layer"),
        Param.Heading("streakHeading", "Streaks"),
        Param.Slider("streak", "Streaks", 0, 100, 0, " %", "Long light streaks through the brightest spots, like with an anamorphic lens"),
        Param.Slider("streakLength", "Streak length", 10, 2000, 300, " px", canvasMax = true),
        Param.Slider("streakAngle", "Streak angle", 0, 17900, 0, "°", "0° = horizontal", decimals = 2),
        Param.Heading("toneHeading", "Tone mapping"),
        Param.Choice("tonemap", "Tone mapping", listOf("Off", "Filmic"), tip = "Filmic: bright parts roll off softly instead of burning out"),
        Param.Slider("exposure", "Exposure", -200, 200, 0, " %", "Hundredths of a stop: −100 % halves the light, +100 % doubles it"),
        Param.Slider("highlights", "Highlights", 0, 300, 60, " %", "Filmic shoulder up to 100 %; above, more contrast in the lights"),
        Param.Slider("shadows", "Shadows", -100, 100, 0, " %", "Negative: deeper shadows · positive: lifted shadows"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val threshold = v["threshold"] / 100.0
        val knee = v["knee"] / 100.0 / 2
        val colorMode = v["color"]
        val custom = v["glowColor"]
        val saturation = v["saturation"] / 100.0

        // what glows: the bright parts, softly cut at the threshold, in their own or a chosen color
        val lr = FloatArray(n)
        val lg = FloatArray(n)
        val lb = FloatArray(n)
        parallelRows(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = src.data[i]
                val a = (c ushr 24) / 255.0
                val r = (c shr 16 and 0xFF) / 255.0
                val g = (c shr 8 and 0xFF) / 255.0
                val b = (c and 0xFF) / 255.0
                val light = 0.299 * r + 0.587 * g + 0.114 * b
                val t = if (knee <= 0) (if (light >= threshold) 1.0 else 0.0)
                else ((light - threshold + knee) / (2 * knee)).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
                val amount = t * a
                if (amount <= 0) continue
                var gr: Double
                var gg: Double
                var gb: Double
                when (colorMode) {
                    1 -> { gr = light; gg = light; gb = light }
                    2 -> { gr = (custom shr 16 and 0xFF) / 255.0 * light; gg = (custom shr 8 and 0xFF) / 255.0 * light; gb = (custom and 0xFF) / 255.0 * light }
                    else -> { gr = r; gg = g; gb = b }
                }
                if (saturation != 1.0) {
                    val m = 0.299 * gr + 0.587 * gg + 0.114 * gb
                    gr = (m + (gr - m) * saturation).coerceAtLeast(0.0)
                    gg = (m + (gg - m) * saturation).coerceAtLeast(0.0)
                    gb = (m + (gb - m) * saturation).coerceAtLeast(0.0)
                }
                lr[i] = (gr * amount).toFloat()
                lg[i] = (gg * amount).toFloat()
                lb[i] = (gb * amount).toFloat()
            }
        }

        // the glow in three sizes for a soft, deep falloff; red reaches further than blue
        val radius = v["radius"].toDouble()
        val fringe = v["fringe"] / 100.0
        fun bloom(plane: FloatArray, stretch: Double): FloatArray {
            val a = Blur.gauss(plane, w, h, radius * stretch)
            val b = Blur.gauss(plane, w, h, radius * stretch * 2.5)
            val c = Blur.gauss(plane, w, h, radius * stretch * 6)
            return FloatArray(n) { 0.55f * a[it] + 0.3f * b[it] + 0.25f * c[it] }
        }
        val gr = bloom(lr, 1 + fringe * 0.8)
        val gg = bloom(lg, 1.0)
        val gb = bloom(lb, 1 - fringe * 0.45)
        val streak = v["streak"] / 100.0
        val streakPlanes = if (streak <= 0) null else {
            val angle = Math.toRadians((v["streakAngle"] / 100.0).toDouble())
            val length = v["streakLength"].toDouble()
            arrayOf(
                Hologram.streaks(lr, w, h, cos(angle), sin(angle), length * (1 + fringe * 0.3)),
                Hologram.streaks(lg, w, h, cos(angle), sin(angle), length),
                Hologram.streaks(lb, w, h, cos(angle), sin(angle), length * (1 - fringe * 0.2)),
            )
        }

        // blurring spreads a light thin: small lights need a strong gain to show a halo
        val k = v["strength"] / 100.0 * 3.5
        val ks = streak * 3.0
        val add = v["blend"] == 1
        val glowOnly = v.bool("glowOnly")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = src.data[i]
                fun channel(shift: Int, glow: FloatArray, s: Int): Int {
                    val base = if (glowOnly) 0.0 else (c shr shift and 0xFF) / 255.0
                    val light = glow[i] * k + (streakPlanes?.get(s)?.get(i) ?: 0f) * ks
                    val value = if (add) base + light else 1 - (1 - base) * (1 - min(1.0, light))
                    return (value.coerceIn(0.0, 1.0) * 255).roundToInt()
                }
                out.data[i] = argb(if (glowOnly) 255 else c ushr 24, channel(16, gr, 0), channel(8, gg, 1), channel(0, gb, 2))
            }
        }
        if (v["tonemap"] == 1) Hologram.tonemap(out, v["exposure"] / 100.0, v["highlights"] / 100.0, v["shadows"] / 100.0)
        return out
    }
}
