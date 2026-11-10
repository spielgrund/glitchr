package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thin-film interference, the colors of soap bubbles, oil slicks and lens coatings: light
 * reflected from the top and the bottom of a film a few hundred nanometres thick adds up or
 * cancels out depending on its wavelength. The colors are worked out physically: the film's
 * reflectance (Fresnel for both polarizations, the Airy sum over its inner reflections) for
 * every wavelength of visible light, through the eye's color matching functions to RGB – from
 * the black of a film thinner than light, through the silvery and golden first order to the
 * vivid magentas, blues and greens of higher orders and their paler repeats. How thick the film
 * is where comes from swirling, flowing noise and/or the picture's brightness, and it can run
 * down, thin at the top like a draining bubble. The film is then laid over the picture.
 */
object ThinFilm : Effect("thinfilm", "Thin film", "Iridescent thin-film interference like soap bubbles, oil on water or lens coatings – physically computed colors on swirling, flowing film; animate the time") {
    /** Materials: refractive index of the film and of what lies beneath it (in air above). */
    private val materials = listOf(
        "Soap bubble" to doubleArrayOf(1.33, 1.0),
        "Oil on water" to doubleArrayOf(1.47, 1.33),
        "Oil on asphalt" to doubleArrayOf(1.47, 1.65),
        "Coating on glass" to doubleArrayOf(1.38, 1.52),
        "Titanium oxide" to doubleArrayOf(2.4, 1.52),
    )
    private val blends = listOf("Screen", "Overlay", "Color", "Multiply", "Film only")

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Lets the film swirl and flow – animate it", decimals = 3),
        Param.Heading("filmHeading", "Film"),
        Param.Choice("material", "Material", materials.map { it.first }, 0, "What the film is and lies on: changes how strong the colors are and which ones come – a soap bubble goes black where it is thinnest, oil on water doesn't"),
        Param.Slider("thin", "Thinnest", 0, 3000, 80, " nm", "Thickness where the film is thinnest: under about 100 nm it reflects almost nothing (black or silvery), then gold, magenta, blue …"),
        Param.Slider("thick", "Thickest", 0, 3000, 900, " nm", "Thickness where it is thickest: the more between thinnest and thickest, the more rings of color; far beyond 1000 nm they pale to pink and green"),
        Param.Slider("angle", "Viewing angle", 0, 85, 20, "°", "Seen at a slant the film acts thinner: the colors shift towards the thinner ones"),
        Param.Slider("drain", "Draining", 0, 100, 30, " %", "The film runs down: thin at the top, thick at the bottom, like a soap bubble"),
        Param.Slider("picture", "From the picture", 0, 100, 30, " %", "How much the picture's brightness sets the thickness: bright = thick – 0 % only the swirls"),
        Param.Heading("swirlHeading", "Swirls"),
        Param.Slider("swirlSize", "Size", 2, 300, 40, " %", "Of the shorter side of the picture"),
        Param.Slider("swirl", "Swirl", 0, 100, 60, " %", "How much the flow curls and folds into itself"),
        Param.Slider("detail", "Detail", 1, 6, 4, tip = "Finer and finer swirls on the large ones"),
        Param.Heading("mixHeading", "Mix"),
        Param.Choice("blend", "Mix", blends, 0, "Screen: the colors shine over the picture · Overlay: stronger, the darks stay · Color: the picture takes on the film's colors, keeps its brightness · Multiply: darkens through the film · Film only: just the film"),
        Param.Slider("strength", "Strength", 0, 100, 75, " %"),
        Param.Slider("brightness", "Brightness", 10, 300, 100, " %", "Of the film's colors"),
        Param.Slider("saturation", "Saturation", 0, 200, 100, " %"),
    )

    /** One lobe of the color matching fits (Wyman, Sloan & Shirley 2013): a Gaussian with different widths either side. */
    private fun lobe(l: Double, mu: Double, s1: Double, s2: Double): Double {
        val t = (l - mu) / (if (l < mu) s1 else s2)
        return exp(-0.5 * t * t)
    }

    /** Reflectance of a film of index [n2] and [d] nm on index [n3] under air, at wavelength [l] nm, seen at [cos1]; both polarizations averaged. */
    private fun reflectance(d: Double, l: Double, n2: Double, n3: Double, cos1: Double, sin1: Double): Double {
        val cos2 = sqrt(max(0.0, 1 - (sin1 / n2).pow(2)))
        val s3 = sin1 / n3
        // beyond the critical angle everything goes back up
        if (s3 >= 1) return 1.0
        val cos3 = sqrt(1 - s3 * s3)
        val delta = 4 * PI * n2 * d * cos2 / l
        val cd = cos(delta)
        fun airy(r12: Double, r23: Double): Double {
            val m = 2 * r12 * r23 * cd
            return (r12 * r12 + r23 * r23 + m) / (1 + r12 * r12 * r23 * r23 + m)
        }
        val s = airy((cos1 - n2 * cos2) / (cos1 + n2 * cos2), (n2 * cos2 - n3 * cos3) / (n2 * cos2 + n3 * cos3))
        val p = airy((n2 * cos1 - cos2) / (n2 * cos1 + cos2), (n3 * cos2 - n2 * cos3) / (n3 * cos2 + n2 * cos3))
        return (s + p) / 2
    }

    /**
     * The film's color for every thickness from 0 to [maxD] nm in steps of [step], linear RGB: the
     * reflected spectrum under even white light through the color matching functions to sRGB,
     * balanced so a mirror would be white, then all scaled so the brightest color is 1.
     */
    private fun table(maxD: Double, step: Double, n2: Double, n3: Double, angle: Double): Array<DoubleArray> {
        val cos1 = cos(angle)
        val sin1 = sin(angle)
        val waves = (380..780 step 5).map { it.toDouble() }
        val cmf = waves.map { l ->
            doubleArrayOf(
                1.056 * lobe(l, 599.8, 37.9, 31.0) + 0.362 * lobe(l, 442.0, 16.0, 26.7) - 0.065 * lobe(l, 501.1, 20.4, 26.2),
                0.821 * lobe(l, 568.8, 46.9, 40.5) + 0.286 * lobe(l, 530.9, 16.3, 31.1),
                1.217 * lobe(l, 437.0, 11.8, 36.0) + 0.681 * lobe(l, 459.0, 26.0, 13.8),
            )
        }
        fun rgb(x: Double, y: Double, z: Double) = doubleArrayOf(
            3.2406 * x - 1.5372 * y - 0.4986 * z,
            -0.9689 * x + 1.8758 * y + 0.0415 * z,
            0.0557 * x - 0.2040 * y + 1.0570 * z,
        )
        val white = rgb(cmf.sumOf { it[0] }, cmf.sumOf { it[1] }, cmf.sumOf { it[2] })
        val n = (maxD / step).toInt() + 2
        val out = Array(n) { k ->
            val d = k * step
            var x = 0.0; var y = 0.0; var z = 0.0
            for ((i, l) in waves.withIndex()) {
                val r = reflectance(d, l, n2, n3, cos1, sin1)
                x += r * cmf[i][0]; y += r * cmf[i][1]; z += r * cmf[i][2]
            }
            val c = rgb(x, y, z)
            doubleArrayOf(max(0.0, c[0] / white[0]), max(0.0, c[1] / white[1]), max(0.0, c[2] / white[2]))
        }
        val brightest = out.maxOf { 0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2] }.coerceAtLeast(1e-9)
        for (c in out) for (i in 0..2) c[i] /= brightest
        return out
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val material = materials[v["material"].coerceIn(0, materials.size - 1)].second
        val thin = v["thin"].toDouble()
        val thick = v["thick"].toDouble()
        val step = 2.0
        val lut = table(max(thin, thick), step, material[0], material[1], Math.toRadians(v["angle"].toDouble()))
        val noise = Noise(seed)
        val time = v["time"] / 1000.0 * 0.15
        val scale = max(1.0, v["swirlSize"] / 100.0 * min(w, h))
        val swirl = v["swirl"] / 100.0
        val octaves = v["detail"]
        val drain = v["drain"] / 100.0
        val picture = v["picture"] / 100.0
        val blend = v["blend"]
        val strength = v["strength"] / 100.0
        val gain = v["brightness"] / 100.0
        val saturation = v["saturation"] / 100.0

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                val br = (c shr 16 and 0xFF) / 255.0
                val bg = (c shr 8 and 0xFF) / 255.0
                val bb = (c and 0xFF) / 255.0
                // the swirls: noise folded into itself (domain warping), drifting with the time
                val px = x / scale
                val py = y / scale
                val qx = noise.fbm(px + time, py - time * 0.6, 3)
                val qy = noise.fbm(px + 5.2 - time * 0.7, py + 1.3 + time * 0.4, 3)
                val f = noise.fbm(px + swirl * 3 * qx + time * 0.3, py + swirl * 3 * qy, octaves)
                var t = (f * 0.85 + 0.5).coerceIn(0.0, 1.0)
                t += ((0.299 * br + 0.587 * bg + 0.114 * bb) - t) * picture
                // drained: thinner towards the top
                t += ((y + 0.5) / h - t) * drain
                val d = thin + (thick - thin) * t.coerceIn(0.0, 1.0)
                // the film's color, between two table steps
                val k = d / step
                val k0 = k.toInt().coerceIn(0, lut.size - 2)
                val a = (k - k0).coerceIn(0.0, 1.0)
                val l0 = lut[k0]
                val l1 = lut[k0 + 1]
                var fr = (l0[0] + (l1[0] - l0[0]) * a) * gain
                var fg = (l0[1] + (l1[1] - l0[1]) * a) * gain
                var fb = (l0[2] + (l1[2] - l0[2]) * a) * gain
                val fl = 0.2126 * fr + 0.7152 * fg + 0.0722 * fb
                fr = max(0.0, fl + (fr - fl) * saturation)
                fg = max(0.0, fl + (fg - fl) * saturation)
                fb = max(0.0, fl + (fb - fl) * saturation)
                // to screen values
                fun enc(q: Double) = q.coerceIn(0.0, 1.0).pow(1 / 2.2)
                val sr = enc(fr); val sg = enc(fg); val sb = enc(fb)
                val baseLuma = 0.299 * br + 0.587 * bg + 0.114 * bb
                val filmLuma = 0.299 * sr + 0.587 * sg + 0.114 * sb
                fun mix(b: Double, s: Double): Double {
                    val m = when (blend) {
                        0 -> 1 - (1 - b) * (1 - s)
                        1 -> if (b < 0.5) 2 * b * s else 1 - 2 * (1 - b) * (1 - s)
                        2 -> s + baseLuma - filmLuma
                        3 -> b * s
                        else -> s
                    }
                    return b + (m.coerceIn(0.0, 1.0) - b) * strength
                }
                fun ch(q: Double) = (q * 255 + 0.5).toInt().coerceIn(0, 255)
                val alpha = if (blend == 4) max(c ushr 24, (strength * 255 + 0.5).toInt()) else c ushr 24
                out.data[i] = (alpha shl 24) or (ch(mix(br, sr)) shl 16) or (ch(mix(bg, sg)) shl 8) or ch(mix(bb, sb))
            }
        }
        return out
    }
}
