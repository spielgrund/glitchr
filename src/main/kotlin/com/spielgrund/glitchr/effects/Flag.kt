package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Flag: the picture flutters like a flag on a pole. Waves run from the pole to the free end, growing
 * as they go (at the pole the cloth is held still), crossed by a slower wave and gusts of noise;
 * the free end sags a little, and the folds catch the light and fall into shadow. Animate the time.
 *
 * The cloth's height off its plane is worked out for every point; the picture is bent by its slopes
 * (folds pull the cloth in and push it up and down) and shaded by them.
 */
object Flag : Effect("flag", "Flag", "The picture flutters like a flag on a pole: waves run out to the free end, gusts, sag, light and shadow in the folds – animate the time") {
    private val poles = listOf("Left", "Right", "Top", "Bottom")

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it and the flag flutters", decimals = 3),
        Param.Slider("wind", "Wind speed", 0, 500, 100, " %", "How fast the waves run out"),
        Param.Choice("pole", "Pole", poles, 0, "Where the flag is held"),
        Param.Heading("wavesHeading", "Waves"),
        Param.Slider("amplitude", "Flutter", 0, 200, 60, " %", "How strongly it waves"),
        Param.Slider("waves", "Waves", 10, 600, 200, "", "How many waves fit across the flag", decimals = 2),
        Param.Slider("cross", "Cross wave", 0, 100, 35, " %", "A slower wave across, so the folds run at a slant and change"),
        Param.Slider("gusts", "Gusts", 0, 100, 30, " %", "Uneven wind: the waves grow and shrink here and there"),
        Param.Slider("sag", "Sag", 0, 100, 15, " %", "The free end hangs down a little"),
        Param.Slider("pull", "Folds pull in", 0, 100, 40, " %", "The folds draw the cloth in, so the free end comes closer to the pole"),
        Param.Heading("tattersHeading", "Tatters"),
        Param.Slider("tatters", "Tatters", 0, 100, 0, " %", "The free end frays out into strips: how deep the tears reach into the flag"),
        Param.Slider("strips", "Strips", 2, 80, 14, tip = "How many strips the end is torn into"),
        Param.Slider("ragged", "Raggedness", 0, 100, 60, " %", "How unevenly long the strips are and how frayed their ends"),
        Param.Slider("stripFlutter", "Strips flutter", 0, 200, 80, " %", "The strips flap on their own"),
        Param.Heading("lightHeading", "Light"),
        Param.Slider("shading", "Shading", 0, 200, 70, " %", "How much the folds fall into shadow and catch the light"),
        Param.Slider("highlight", "Sheen", 0, 100, 25, " %", "A shine on the folds facing the light, like satin"),
        Param.Slider("lightAngle", "Light from", 0, 35900, 31500, "°", "Where the light comes from: 0° = right, 270° = from above", decimals = 2),
        Param.Choice("edge", "Edge", listOf("Transparent", "Stretch edge"), 0, "What shows where the cloth has moved away"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val time = v["time"] / 1000.0 * v["wind"] / 100.0
        val pole = v["pole"]
        val vertical = pole >= 2
        // along: from the pole to the free end; across: along the pole
        val len = if (vertical) h else w
        val wid = if (vertical) w else h
        val amp = v["amplitude"] / 100.0 * wid * 0.06
        val k = 2 * PI * v["waves"] / 100.0
        val cross = v["cross"] / 100.0
        val gusts = v["gusts"] / 100.0
        val sag = v["sag"] / 100.0 * wid * 0.12
        val pull = v["pull"] / 100.0
        val shading = v["shading"] / 100.0
        val sheen = v["highlight"] / 100.0
        val la = Math.toRadians(v["lightAngle"] / 100.0)
        val lx = cos(la); val ly = sin(la)
        val transparent = v["edge"] == 0
        val tatters = v["tatters"] / 100.0
        val strips = v["strips"]
        val ragged = v["ragged"] / 100.0
        val stripFlutter = v["stripFlutter"] / 100.0
        val s32 = seed.toInt() xor (seed ushr 32).toInt()
        fun rnd(a: Int, b: Int): Double {
            var hh = a * 374761393 + b * 668265263 + s32 * 1274126177
            hh = (hh xor (hh ushr 13)) * 1274126177
            hh = hh xor (hh ushr 16)
            return (hh and 0xFFFFFF) / 16777216.0
        }
        /** Where the strip at [q] is torn from (0..1 along, 1 = not torn), and its index. */
        fun tearStart(q: Double): Double {
            val i = floor(q * strips).toInt()
            return 1 - tatters * (1 - ragged * 0.75 * rnd(i, 1))
        }

        /** The cloth's height at [u] (0 at the pole … 1 free end) and [q] (0 … 1 along the pole). */
        fun z(u: Double, q: Double): Double {
            val grow = u.coerceAtLeast(0.0).pow(1.1)
            val phase = k * u - time * 2 * PI * 1.2 + cross * 2.2 * sin(q * PI * 1.3 + time * 1.1)
            var zz = sin(phase) + 0.35 * sin(phase * 2.1 + q * 3.0 - time * 1.7)
            if (gusts > 0) zz *= 1 + gusts * 1.2 * noise.perlin(u * 2.0 - time * 0.8, q * 1.5 + time * 0.3)
            return zz * grow * amp
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                // in the flag's own frame
                val a = if (vertical) y + 0.5 else x + 0.5
                val c = if (vertical) x + 0.5 else y + 0.5
                val u = (if (pole == 1 || pole == 3) len - a else a) / len
                val q = c / wid
                val e = 0.002
                val zu = (z(u + e, q) - z(u - e, q)) / (2 * e) / len
                val zq = (z(u, q + e) - z(u, q - e)) / (2 * e) / wid
                // folds pull the cloth in towards the pole and push it across; the free end sags
                val inward = pull * u * u * len * 0.08 * (1 + 0.5 * sin(k * u - time * 2 * PI * 1.2))
                val su = u + inward / len
                val sAlong = (if (pole == 1 || pole == 3) len - su * len else su * len)
                var sAcross = c - z(u, q) * 0.6 - sag * u * u
                // the torn strips flap on their own, more towards their ends
                if (tatters > 0) {
                    val start = tearStart(q)
                    if (u > start) {
                        val i = floor(q * strips).toInt()
                        val k = (u - start) / (1 - start).coerceAtLeast(1e-6)
                        sAcross += stripFlutter * amp * 0.9 * k * k * sin(time * 2 * PI * (1.4 + rnd(i, 2)) + rnd(i, 3) * 6.283 + u * 9)
                    }
                }
                val sx = if (vertical) sAcross else sAlong
                val sy = if (vertical) sAlong else sAcross
                val outside = sx < 0 || sy < 0 || sx >= w || sy >= h
                if (outside && transparent) continue
                // torn away: the gaps between the strips, and their frayed ends
                if (tatters > 0) {
                    val sq = (if (vertical) sx else sy) / wid
                    val sUu = su
                    val start = tearStart(sq)
                    if (sUu > start) {
                        val i = floor(sq * strips).toInt()
                        val within = sq * strips - i
                        val k = (sUu - start) / (1 - start).coerceAtLeast(1e-6)
                        // the strips narrow towards their ends, with frayed sides
                        val gap = 0.06 + 0.55 * k.pow(1.4)
                        val fray = ragged * 0.15 * noise.perlin(sq * strips * 3.0, sUu * 25.0)
                        if (within < gap / 2 + fray || within > 1 - gap / 2 + fray) continue
                        // each strip ends at its own length, its end ragged
                        val end = 1 - ragged * 0.7 * rnd(i, 4) + ragged * 0.12 * noise.perlin(within * 5.0, i * 1.7)
                        if (k > end) continue
                    }
                }
                var col = sampleBilinear(src, sx, sy, Edge.CLAMP)
                // light: the slope towards or away from the light
                val slopeX = if (vertical) zq else zu * (if (pole == 1) -1 else 1)
                val slopeY = if (vertical) zu * (if (pole == 3) -1 else 1) else zq
                val facing = -(slopeX * lx + slopeY * ly) * 1.8
                val shade = (1 + shading * facing).coerceIn(0.15, 2.0)
                val shine = sheen * (facing.coerceAtLeast(0.0)).pow(2) * 70
                fun ch(sh: Int) = ((col shr sh and 0xFF) * shade + shine).roundToInt().coerceIn(0, 255)
                col = (col and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                out.data[y * w + x] = col
            }
        }
        return out
    }
}
