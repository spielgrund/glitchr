package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.max
import kotlin.math.sin

/**
 * A water reflection: below the water line the picture above it is mirrored, seen in
 * perspective – the reflection narrows towards a vanishing point, the waves get finer
 * towards the horizon and stronger towards us – rippled and shifted by the waves, blurred,
 * darkened and tinted with the water's color the further it lies from the line.
 */
object WaterReflection : Effect("waterreflection", "Water reflection", "Mirrors the picture in water below a line: perspective, waves, ripples, blur and water color") {
    override val params = listOf(
        Param.Slider("line", "Water line", 0, 1000, 600, " %", "Where the water begins, from the top", decimals = 1),
        Param.Slider("vanish", "Perspective X", -500, 1500, 500, " %", "Where the reflection's lines run to, across the picture", decimals = 1),
        Param.Slider("perspective", "Perspective", 0, 100, 30, " %", "How strongly the reflection narrows towards the vanishing point with depth"),
        Param.Slider("squash", "Squash", 20, 200, 100, " %", "Height of the reflection against the picture"),
        Param.Heading("wavesHeading", "Waves"),
        Param.Slider("waves", "Waves", 0, 200, 40, " px", "How far the waves shift the reflection (nearest to us)"),
        Param.Slider("waveSize", "Wave size", 2, 400, 40, " px", "Size of the waves close to us; towards the horizon they get finer"),
        Param.Slider("stretch", "Wave stretch", 100, 1000, 400, " %", "Waves are long sideways and short in depth"),
        Param.Slider("ripple", "Ripples", 0, 100, 40, " %", "Finer, choppier ripples on top"),
        Param.Slider("phase", "Phase", -360000, 360000, 0, "°", "Moves the waves – animate it", decimals = 2),
        Param.Slider("vertical", "Up and down", 0, 100, 30, " %", "Waves also shift the reflection up and down, not only sideways"),
        Param.Heading("waterHeading", "Water"),
        Param.Slider("blur", "Blur", 0, 100, 25, " %", "Blurs the reflection more the further it lies from the line"),
        Param.Slider("fade", "Fade", 0, 100, 35, " %", "Fades into the water color with distance"),
        Param.Slider("darken", "Darken", 0, 100, 20, " %"),
        Param.Color("water", "Water color", 0x1C3A4A),
        Param.Slider("edge", "Soft line", 0, 100, 2, " px", "Blends the water line softly"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val line = h * v["line"] / 1000.0
        val vanish = w * v["vanish"] / 1000.0
        val perspective = v["perspective"] / 100.0
        val squash = v["squash"] / 100.0
        val amplitude = v["waves"].toDouble()
        val waveSize = v["waveSize"].toDouble()
        val stretch = v["stretch"] / 100.0
        val ripple = v["ripple"] / 100.0
        val phase = Math.toRadians(v["phase"] / 100.0)
        val vertical = v["vertical"] / 100.0
        val blur = v["blur"] / 100.0
        val fade = v["fade"] / 100.0
        val darken = v["darken"] / 100.0
        val water = v["water"] or 0xFF000000.toInt()
        val soft = v["edge"].toDouble()
        val noise = Noise(seed)
        val depthMax = max(1.0, h - line)
        val out = src.copy()
        val start = max(0, line.toInt())
        parallelRows(h - start) { row ->
            val y = start + row
            // how far into the water, 0 at the line, 1 at the bottom
            val d = y + 0.5 - line
            if (d <= 0) return@parallelRows
            val t = d / depthMax
            // perspective: close to the line things are far away and the waves are small
            val near = 0.08 + 0.92 * t
            for (x in 0 until w) {
                // waves: long sideways, short in depth, finer towards the horizon, moving with the phase
                val scale = waveSize * near
                val u = x / (scale * stretch)
                val z = (1 / near) * depthMax / waveSize
                var wave = noise.fbm(u, z + phase / (2 * Math.PI) * 3, 3) + sin(z * 0.9 + phase + u * 0.3) * 0.35
                if (ripple > 0) wave += ripple * noise.fbm(u * 4.3, z * 3.7 - phase / Math.PI * 2, 2)
                val shift = wave * amplitude * near
                // the mirrored point above the line, narrowed towards the vanishing point with depth
                var sx = vanish + (x + 0.5 - vanish) * (1 - perspective * t * 0.6) + shift
                var sy = line - d * squash + shift * vertical
                sx = sx.coerceIn(0.0, w - 0.001)
                sy = sy.coerceIn(0.0, max(0.0, line - 0.5))
                // blur: a few samples up and down, more the deeper
                val spread = blur * t * waveSize * 0.4
                var c = if (spread < 0.5) sampleBilinear(src, sx, sy, Edge.CLAMP) else {
                    val taps = IntArray(5) { k -> sampleBilinear(src, sx, (sy + (k - 2) * spread / 2).coerceIn(0.0, max(0.0, line - 0.5)), Edge.CLAMP) }
                    averageArgb(taps)
                }
                c = lerpArgb(c, water, (fade * t).toFloat())
                if (darken > 0) c = lerpArgb(c, 0xFF000000.toInt(), (darken * (0.4 + 0.6 * t)).toFloat())
                // the water line: a soft blend over the first pixels
                val i = y * w + x
                out.data[i] = if (soft > 0 && d < soft) lerpArgb(src.data[i], c, (d / soft).toFloat()) else c
            }
        }
        return out
    }
}
