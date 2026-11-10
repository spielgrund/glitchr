package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Light streaks out of the bright parts of the picture: a star of rays like a star filter, a long
 * horizontal anamorphic flare or a motion streak in one direction. The streaks fade exponentially
 * over their length; red may reach further than blue for colored ends. Rays out of a point are
 * [LightRays].
 */
object LightStreaks : Effect("lightstreaks", "Light streaks", "Streaks out of the bright parts: star filter rays, anamorphic flare or motion streaks") {
    private val modes = listOf("Star", "Anamorphic", "One direction")
    private const val STAR = 0
    private const val ANAMORPHIC = 1
    private const val ONE_WAY = 2

    override val params = listOf(
        Param.Choice("mode", "Streaks", modes, STAR, "Star: rays like a star filter · Anamorphic: a long horizontal flare both ways · One direction: a motion streak"),
        Param.Slider("rays", "Rays", 2, 16, 6, "", "How many rays every light gets").shownWhen("mode", STAR),
        Param.Slider("angle", "Angle", 0, 35999, 1500, "°", decimals = 2),
        Param.Slider("length", "Length", 5, 3000, 250, " px", canvasMax = true),
        Param.Slider("strength", "Strength", 0, 400, 100, " %"),
        Param.Heading("sourceHeading", "What streaks"),
        Param.Slider("threshold", "Threshold", 0, 100, 70, " %", "From which brightness on the picture streaks"),
        Param.Slider("knee", "Softness", 0, 100, 30, " %", "How gently it sets in around the threshold"),
        Param.Slider("thickness", "Thickness", 0, 100, 0, " px", "Softens the lights first: wider, smoother streaks"),
        Param.Heading("colorHeading", "Color"),
        Param.Choice("color", "Color", listOf("From picture", "White", "Custom"), tip = "From picture: every light streaks in its own color · Custom: e.g. blue for the anamorphic look"),
        Param.Color("streakColor", "Streak color", 0x5A9BFF, "For “Custom”"),
        Param.Slider("saturation", "Saturation", 0, 300, 100, " %"),
        Param.Slider("fringe", "Color fringe", 0, 100, 20, " %", "Red reaches further than blue: colored ends"),
        Param.Choice("blend", "Blend", listOf("Screen", "Add"), tip = "Screen: soft, never above white · Add: stronger, burns out sooner"),
        Param.Toggle("streaksOnly", "Streaks only", false, "Shows only the streaks on black, e.g. to blend them yourself in the layer"),
    )

    override val random = false

    /** [plane] at (x, y), bilinear, 0 outside. */
    private fun sample(plane: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        fun at(xx: Int, yy: Int) = if (xx < 0 || yy < 0 || xx >= w || yy >= h) 0f else plane[yy * w + xx]
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /**
     * Sum over n of decay^n · [plane] at n pixels back along the streak – an exponential streak of
     * about [length] pixels, built in passes of doubling steps. [back] gives the unit step back at a
     * pixel for a step of that many pixels (null: none, the streak ends before).
     */
    internal fun streak(plane: FloatArray, w: Int, h: Int, length: Double, back: (Int, Int, Double) -> DoubleArray?): FloatArray {
        val decay = exp(-3.0 / max(1.0, length))
        val passes = ceil(ln(max(2.0, length * 1.3)) / ln(2.0)).toInt()
        var cur = plane
        var step = 1.0
        repeat(passes) {
            val k = decay.pow(step).toFloat()
            val src = cur
            val next = FloatArray(w * h)
            val st = step
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val d = back(x, y, st)
                    next[i] = if (d == null) src[i] else src[i] + k * sample(src, w, h, x - d[0] * st, y - d[1] * st)
                }
            }
            cur = next
            step *= 2
        }
        return cur
    }

    /**
     * The bright parts of [src] as red, green and blue planes, softly cut at [threshold] over
     * [knee], in their own colors ([colorMode] 0), white (1) or [custom] (2).
     */
    internal fun brightParts(src: Pixels, threshold: Double, knee: Double, colorMode: Int, custom: Int, saturation: Double): Array<FloatArray> {
        val w = src.width
        val planes = Array(3) { FloatArray(w * src.height) }
        parallelRows(src.height) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = src.data[i]
                val r = red(c) / 255.0
                val g = green(c) / 255.0
                val b = blue(c) / 255.0
                val light = 0.299 * r + 0.587 * g + 0.114 * b
                val t = if (knee <= 0) (if (light >= threshold) 1.0 else 0.0)
                else ((light - threshold + knee) / (2 * knee)).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
                val amount = t * alpha(c) / 255.0
                if (amount <= 0) continue
                var rgb = when (colorMode) {
                    1 -> doubleArrayOf(light, light, light)
                    2 -> doubleArrayOf(red(custom) / 255.0 * light, green(custom) / 255.0 * light, blue(custom) / 255.0 * light)
                    else -> doubleArrayOf(r, g, b)
                }
                if (saturation != 1.0) {
                    val m = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]
                    rgb = DoubleArray(3) { (m + (rgb[it] - m) * saturation).coerceAtLeast(0.0) }
                }
                for (ch in 0..2) planes[ch][i] = (rgb[ch] * amount).toFloat()
            }
        }
        return planes
    }

    /** [light] planes laid over [src]: screened, added or alone on black ([only]). */
    internal fun lay(src: Pixels, light: Array<FloatArray>, add: Boolean, only: Boolean): Pixels {
        val out = Pixels(src.width, src.height)
        parallelRows(src.height) { y ->
            for (i in y * src.width until (y + 1) * src.width) {
                val c = src.data[i]
                fun ch(base: Int, l: Float): Int {
                    val lv = l.toDouble()
                    return when {
                        only -> ((1 - exp(-lv)) * 255).roundToInt()
                        add -> (base + lv * 255).roundToInt()
                        else -> (255 - (255 - base) * exp(-lv)).roundToInt()
                    }.coerceIn(0, 255)
                }
                out.data[i] = argb(if (only) 255 else alpha(c), ch(red(c), light[0][i]), ch(green(c), light[1][i]), ch(blue(c), light[2][i]))
            }
        }
        return out
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        // what streaks: the bright parts, softly cut at the threshold, in their own or a chosen color
        var planes = brightParts(src, v["threshold"] / 100.0, v["knee"] / 100.0 / 2, v["color"], v["streakColor"], v["saturation"] / 100.0)
        val thickness = v["thickness"].toDouble()
        if (thickness > 0) planes = Array(3) { Blur.gauss(planes[it], w, h, thickness * 0.5) }

        val mode = v["mode"]
        val length = v["length"].toDouble()
        val fringe = v["fringe"] / 100.0
        val angle = Math.toRadians(v["angle"] / 100.0)
        // the directions the streaks run in, as unit steps back
        val directions = when (mode) {
            STAR -> List(v["rays"]) { k -> (angle + 2 * PI * k / v["rays"]).let { doubleArrayOf(cos(it), -sin(it)) } }
            ANAMORPHIC -> listOf(doubleArrayOf(cos(angle), -sin(angle)), doubleArrayOf(-cos(angle), sin(angle)))
            else -> listOf(doubleArrayOf(cos(angle), -sin(angle)))
        }
        val gain = v["strength"] / 100.0 * when (mode) { ANAMORPHIC -> 0.14; ONE_WAY -> 0.12; else -> 0.09 }
        val light = Array(3) { ch ->
            // red reaches further than blue
            val l = length * (1 + fringe * (when (ch) { 0 -> 0.3; 2 -> -0.2; else -> 0.0 }))
            val plane = planes[ch]
            val sum = FloatArray(n)
            for (d in directions) {
                val s = streak(plane, w, h, l) { _, _, _ -> d }
                for (i in 0 until n) sum[i] += s[i] - plane[i]
            }
            // the lights themselves stay as they are, the streaks come on top
            for (i in 0 until n) sum[i] = (sum[i] * gain).toFloat()
            sum
        }
        return lay(src, light, v["blend"] == 1, v.bool("streaksOnly"))
    }
}
