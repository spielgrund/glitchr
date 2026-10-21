package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fireflies: single glowing pixels lift off the bright areas and drift upwards, swaying and
 * blinking, fading in and out – each one, once gone, starts again from another bright spot.
 * Animate the time and they keep rising.
 */
object Fireflies : Effect("fireflies", "Fireflies", "Single glowing pixels rising from the bright areas like fireflies or embers – animate the time") {
    override val params = listOf(
        Param.Slider("time", "Time", 0, 100000, 0, "", "Moves them: animate it and they rise", decimals = 2),
        Param.Slider("count", "Fireflies", 1, 5000, 400),
        Param.Slider("threshold", "From brightness", 0, 100, 65, " %", "They start only where the picture is at least this bright"),
        Param.Heading("motionHeading", "Motion"),
        Param.Slider("rise", "Rise", 100, 10000, 2500, " %", "How far one flies in its life, of the canvas height", decimals = 2, transform = true),
        Param.Slider("direction", "Direction", -18000, 18000, 0, "°", "Where they fly: 0° = up", decimals = 2),
        Param.Slider("speed", "Speed", 0, 400, 100, " %", "How quickly they live with the time"),
        Param.Slider("wander", "Wander", 0, 100, 35, " %", "How much they sway from side to side"),
        Param.Heading("lookHeading", "Look"),
        Param.Slider("size", "Size", 50, 1000, 100, " px", decimals = 2),
        Param.Choice("colorMode", "Color", listOf("From the picture", "Own color"), 0, "From the picture: each one in the color where it started"),
        Param.Color("color", "Own color", 0xD8FF6A),
        Param.Slider("brightness", "Brightness", 10, 400, 150, " %"),
        Param.Slider("blink", "Blinking", 0, 100, 50, " %", "How much they pulse on and off"),
        Param.Slider("glow", "Glow", 0, 300, 80, " %", "A soft halo around each one"),
    )

    /** A random number 0..1 from the numbers given. */
    private fun rnd(a: Int, b: Int, c: Int): Double {
        var h = a * 374761393 + b * 668265263 + c * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216.0
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val threshold = v["threshold"] / 100.0 * 255
        // the bright places they can start from
        val starts = src.data.indices.filter { val c = src.data[it]; alpha(c) > 128 && luma(c) >= threshold }
        if (starts.isEmpty()) return src.copy()
        val time = v["time"] / 100.0 * v["speed"] / 100.0
        val rise = h * v["rise"] / 10000.0
        val a = Math.toRadians(v["direction"] / 100.0)
        val ux = sin(a); val uy = -cos(a)
        val px = cos(a); val py = sin(a)
        val wander = v["wander"] / 100.0
        val size = v["size"] / 100.0
        val own = v["colorMode"] == 1
        val color = v["color"]
        val bright = v["brightness"] / 100.0
        val blink = v["blink"] / 100.0

        // their light, added up per channel
        val light = Array(3) { FloatArray(w * h) }
        fun splat(x: Double, y: Double, c: Int, amount: Double) {
            // a soft dot, sub-pixel exact
            val r = max(0.5, size / 2)
            val x0 = floor(x - r).toInt(); val x1 = floor(x + r).toInt()
            val y0 = floor(y - r).toInt(); val y1 = floor(y + r).toInt()
            for (yy in y0..y1) {
                if (yy < 0 || yy >= h) continue
                for (xx in x0..x1) {
                    if (xx < 0 || xx >= w) continue
                    val k = ((1 - kotlin.math.abs(xx + 0.5 - x) / (r + 0.5)) * (1 - kotlin.math.abs(yy + 0.5 - y) / (r + 0.5))).coerceIn(0.0, 1.0)
                    if (k <= 0) continue
                    val i = yy * w + xx
                    light[0][i] += ((c shr 16 and 0xFF) / 255.0 * amount * k).toFloat()
                    light[1][i] += ((c shr 8 and 0xFF) / 255.0 * amount * k).toFloat()
                    light[2][i] += ((c and 0xFF) / 255.0 * amount * k).toFloat()
                }
            }
        }
        for (n in 0 until v["count"]) {
            // each lives a while, then starts again elsewhere; their lives are spread out over the time
            val life = 0.6 + rnd(n, 1, s) * 0.8
            val clock = time / life + rnd(n, 2, s)
            val round = floor(clock).toInt()
            val age = clock - round
            val from = starts[(rnd(n, round, s + 7) * starts.size).toInt().coerceIn(0, starts.size - 1)]
            val x0 = from % w + 0.5; val y0 = from / w + 0.5
            val far = age * rise * (0.6 + rnd(n, round, s + 11) * 0.8)
            val sway = sin(age * 2 * PI * (0.8 + rnd(n, round, s + 13) * 1.6) + rnd(n, round, s + 17) * 2 * PI) * wander * rise * 0.12 * (0.3 + age)
            val x = x0 + ux * far + px * sway
            val y = y0 + uy * far + py * sway
            // fading in and out, blinking
            val fade = min(1.0, age / 0.12) * min(1.0, (1 - age) / 0.3)
            val pulse = 1 - blink + blink * (0.5 + 0.5 * sin(time * 2 * PI * (1.5 + rnd(n, 3, s) * 2.5) + rnd(n, 4, s) * 2 * PI)).let { it * it }
            val c = if (own) color else src.data[from]
            splat(x, y, c, fade * pulse * bright)
        }

        val glow = v["glow"] / 100.0
        val halos = if (glow > 0) Array(3) { Blur.gauss(light[it], w, h, max(1.5, size * 2.5)) } else null
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                var most = 0.0
                fun ch(k: Int, sh: Int): Int {
                    val l = light[k][i] + (halos?.get(k)?.get(i) ?: 0f) * glow * 3
                    // own colors get a white-hot core
                    val hot = light[0][i] + light[1][i] + light[2][i]
                    val add = (l + max(0.0, hot - 1.2) * 0.3) * 255
                    most = max(most, add)
                    return ((base shr sh and 0xFF) + add).toInt().coerceIn(0, 255)
                }
                val r = ch(0, 16); val g = ch(1, 8); val b = ch(2, 0)
                val a = max(alpha(base), min(255.0, most).toInt())
                out.data[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
