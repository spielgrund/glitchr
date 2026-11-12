package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Film grain: fine random specks over the picture, as on film or a high-ISO sensor. Grains of a
 * size (crisp or soft), with finer ones among them (roughness), gray or colored, strongest in
 * the mid tones as on film (or evenly, in the shadows, in the highlights). With the time the
 * grain dances – changing so many times a second, like film running through a projector.
 */
object Grain : Effect("grain", "Grain", "Film grain – amount, grain size, softness, roughness, gray or colored, in the mid tones like film; dances with the time") {
    private val responses = listOf("Mid tones (film)", "Evenly", "Shadows", "Highlights")

    override val params = listOf(
        Param.Slider("amount", "Amount", 0, 200, 35, " %"),
        Param.Slider("size", "Grain size", 5, 200, 15, " px", "How big one grain is", decimals = 1),
        Param.Slider("softness", "Softness", 0, 100, 50, " %", "Crisp, square grains (0 %) or soft, round ones"),
        Param.Slider("roughness", "Roughness", 0, 100, 40, " %", "Finer grains among the big ones"),
        Param.Slider("color", "Color", 0, 100, 15, " %", "Gray grain (0 %) or each color channel grained on its own, like color film"),
        Param.Choice("response", "Strongest in", responses, 0, "Mid tones: as on film, the blacks and whites stay cleaner · Evenly · Shadows: like a pushed sensor · Highlights"),
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Lets the grain dance – animate it", decimals = 3),
        Param.Slider("rate", "Changes per second", 1, 60, 24, tip = "How often the grain changes as the time runs: 24 like film"),
    )

    /** A roughly bell-shaped random value −1..1 for the grid point (x, y) of layer [k]. */
    private fun cell(x: Int, y: Int, k: Int, seed: Int): Double {
        var h = x * 374761393 + y * 668265263 + k * 1274126177 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        // three uniform values summed: close to a bell curve
        val a = (h and 0x3FF) / 1023.0
        val b = (h ushr 10 and 0x3FF) / 1023.0
        val c = (h ushr 20 and 0x3FF) / 1023.0
        return (a + b + c - 1.5) / 1.5
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val amount = v["amount"] / 100.0
        if (amount <= 0) return src.copy()
        val size = max(0.5, v["size"] / 10.0)
        val soft = v["softness"] / 100.0
        val rough = v["roughness"] / 100.0
        val colored = v["color"] / 100.0
        val response = v["response"]
        // the grain changes so many times a second: a new pattern for each step of the time
        val step = floor(v["time"] / 1000.0 * v["rate"]).toInt()
        val s0 = seed.toInt() * 31 + step * 7919
        val w = src.width
        val h = src.height

        /** The grain of layer [k] at (x, y) with grains of [sz] px: crisp to soft between the grid points. */
        fun grain(x: Double, y: Double, sz: Double, k: Int): Double {
            val gx = x / sz
            val gy = y / sz
            val x0 = floor(gx).toInt()
            val y0 = floor(gy).toInt()
            if (soft <= 0) return cell(x0, y0, k, s0)
            // soft: smoothly between the four around (from the grain's middle), mixed with the crisp one
            val fx = gx - x0 - 0.5
            val fy = gy - y0 - 0.5
            val ix = if (fx < 0) x0 - 1 else x0
            val iy = if (fy < 0) y0 - 1 else y0
            var tx = if (fx < 0) fx + 1 else fx
            var ty = if (fy < 0) fy + 1 else fy
            tx = tx * tx * (3 - 2 * tx)
            ty = ty * ty * (3 - 2 * ty)
            val top = cell(ix, iy, k, s0) * (1 - tx) + cell(ix + 1, iy, k, s0) * tx
            val bottom = cell(ix, iy + 1, k, s0) * (1 - tx) + cell(ix + 1, iy + 1, k, s0) * tx
            // smooth blending loses contrast: lifted back
            val smooth = (top * (1 - ty) + bottom * ty) * 1.6
            // the crisp share falls away quickly as it gets softer, so soft grains show no square edges
            val crisp = (1 - soft) * (1 - soft)
            return cell(x0, y0, k, s0) * crisp + smooth * (1 - crisp)
        }

        fun layered(x: Double, y: Double, k: Int): Double {
            val big = grain(x, y, size, k)
            if (rough <= 0) return big
            val fine = grain(x, y, max(0.5, size / 2.3), k + 11)
            return (big + fine * rough) / sqrt(1 + rough * rough)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                val r = (c shr 16 and 0xFF) / 255.0
                val g = (c shr 8 and 0xFF) / 255.0
                val b = (c and 0xFF) / 255.0
                val l = 0.3 * r + 0.59 * g + 0.11 * b
                // how strongly this brightness takes the grain
                val weight = when (response) {
                    0 -> 0.25 + 3 * l * (1 - l)
                    1 -> 1.0
                    2 -> 0.15 + 0.85 * (1 - l) * (1 - l)
                    else -> 0.15 + 0.85 * l * l
                }
                val px = x + 0.5
                val py = y + 0.5
                val mono = layered(px, py, 0)
                val k = amount * weight * 0.22
                fun channel(q: Double, ch: Int): Double {
                    val n = if (colored > 0) mono * (1 - colored) + layered(px, py, ch + 1) * colored else mono
                    return q + n * k
                }
                fun c8(q: Double) = (q * 255).roundToInt().coerceIn(0, 255)
                out.data[i] = (c and 0xFF000000.toInt()) or (c8(channel(r, 0)) shl 16) or (c8(channel(g, 1)) shl 8) or c8(channel(b, 2))
            }
        }
        return out
    }
}
