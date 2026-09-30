package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fog by depth: the further away (after the guessed depth), the more the picture fades
 * into the fog color – linearly between a start and an end depth or exponentially, thicker
 * near the ground if asked, with drifting wisps. Far away the picture also gets soft
 * (haze) and loses its colors (aerial perspective).
 */
object Fog : Effect("fog", "Nebel", "Nebel nach der geschätzten Tiefe: je weiter weg, desto mehr verschwindet das Bild im Dunst") {
    override val params = listOf(
        Param.Heading("fogHeading", "Nebel"),
        Param.Color("fogColor", "Nebelfarbe", 0xC9D3DC),
        Param.Slider("density", "Dichte", 0, 100, 70, " %"),
        Param.Choice("falloff", "Verlauf", listOf("Linear", "Exponentiell"), 1, "Exponentiell: wie echter Nebel, zuerst schnell, dann langsam dichter"),
        Param.Slider("fogStart", "Beginn", 0, 100, 20, " %", "Ab dieser Ferne beginnt der Nebel (0 % = ganz nah)"),
        Param.Slider("fogEnd", "Ende", 0, 100, 100, " %", "Hier ist er am dichtesten"),
        Param.Slider("ground", "Bodennebel", -100, 100, 0, " %", "Positiv: unten dichter, wie Nebel über dem Boden · negativ: oben dichter"),
        Param.Slider("wisps", "Schwaden", 0, 100, 25, " %", "Der Nebel ist ungleich dicht"),
        Param.Slider("wispSize", "Schwadengrösse", 4, 1000, 160, " px", canvasMax = true),
        Param.Slider("haze", "Dunst-Unschärfe", 0, 100, 20, " px", "Im Nebel wird das Bild weich"),
        Param.Slider("aerial", "Luftperspektive", 0, 100, 40, " %", "Ferne Dinge verlieren ihre Farbe"),
    ) + Depth.params

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val depth = Depth.estimate(src, v)
        if (v.bool("depthShow")) return Depth.show(src, depth)
        val color = v["fogColor"]
        val fr = (color shr 16 and 0xFF).toDouble()
        val fg = (color shr 8 and 0xFF).toDouble()
        val fb = (color and 0xFF).toDouble()
        val density = v["density"] / 100.0
        val exponential = v["falloff"] == 1
        val start = v["fogStart"] / 100.0
        val end = max(start + 0.01, v["fogEnd"] / 100.0)
        val ground = v["ground"] / 100.0
        val wisps = v["wisps"] / 100.0
        val wispSize = v["wispSize"].toDouble()
        val aerial = v["aerial"] / 100.0
        val noise = Noise(seed)
        // the soft (hazy) picture, per channel
        val hazeRadius = v["haze"] / 2
        val soft = if (hazeRadius < 1) null else Array(3) { ch ->
            Grow.blur(FloatArray(w * h) { val c = src.data[it]; (when (ch) { 0 -> red(c); 1 -> green(c); else -> blue(c) }).toFloat() }, w, h, hazeRadius)
        }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                val far = 1 - depth[i]
                // how far into the fog, 0..1
                val t = ((far - start) / (end - start)).coerceIn(0.0, 1.0)
                var fog = if (exponential) (1 - exp(-3.5 * t)) / (1 - exp(-3.5)) else t
                if (ground != 0.0) fog *= (1 + ground * ((y + 0.5) / h - 0.5) * 2).coerceAtLeast(0.0)
                if (wisps > 0) fog *= (1 + wisps * noise.fbm(x / wispSize, y / wispSize, 4)).coerceAtLeast(0.0)
                fog = (fog * density).coerceIn(0.0, 1.0)
                var r = red(c).toDouble()
                var g = green(c).toDouble()
                var b = blue(c).toDouble()
                if (soft != null) {
                    r += (soft[0][i] - r) * fog
                    g += (soft[1][i] - g) * fog
                    b += (soft[2][i] - b) * fog
                }
                if (aerial > 0) {
                    val l = 0.299 * r + 0.587 * g + 0.114 * b
                    val k = aerial * fog
                    r += (l - r) * k; g += (l - g) * k; b += (l - b) * k
                }
                r += (fr - r) * fog
                g += (fg - g) * fog
                b += (fb - b) * fog
                out.data[i] = argb(alpha(c), r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
            }
        }
        return out
    }
}
