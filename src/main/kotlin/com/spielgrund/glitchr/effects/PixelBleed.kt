package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.clamp255
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.max
import kotlin.random.Random

/**
 * Port of pixelbleed_06.py: every pixel above (or below) the threshold – by the mean of
 * R, G, B as in the script, or by brightness, saturation, hue or a channel – smears its color over a random number of following pixels, getting a little
 * brighter every few steps. Smeared pixels are skipped, so streaks don't restart.
 */
object PixelBleed : Effect("pixelbleed", "Pixelbleed", "Helle Pixel laufen in zufällig langen Streifen aus") {
    private val directions = listOf("Nach unten", "Nach oben", "Nach rechts", "Nach links")

    override val params = listOf(
        Param.Choice("direction", "Richtung", directions),
        Param.Choice("thresholdMode", "Schwelle nach", thresholdModes, THRESHOLD_MEAN, THRESHOLD_TIP),
        Param.Slider("threshold", "Schwelle", 0, 255, 100, tip = "Wert, ab dem ein Pixel ausläuft"),
        Param.Toggle("darker", "Pixel unter der Schwelle auswählen", false, "Aus: Pixel über der Schwelle laufen aus. An: Pixel darunter"),
        Param.Slider("length", "Max. Länge", 1, 3000, 300, " px", "Jeder Streifen ist zufällig 1 bis so lang; höchstens die längere Seite der Leinwand", canvasMax = true),
        Param.Slider("jitter", "Längen-Zufall", 0, 100, 100, " %", "100 %: jeder Streifen ist zufällig 1 bis Max. Länge lang. 0 %: alle genau Max. Länge"),
        Param.Color("endColor", "Zielfarbe", 0xFF0033, "Farbe, zu der jeder Streifen verläuft"),
        Param.Slider("colorMix", "Farbstärke", 0, 100, 100, " %", "Wie viel Zielfarbe am Streifenende beigemischt ist: 0 % = keine, 100 % = ganz"),
        Param.Slider("step", "Schritt", 1, 100, 5, " px", "Alle wie viele Pixel die Farbe wechselt; grösser = treppiger Verlauf"),
        Param.Choice("block", "Blockgrösse", blockOptions, tip = "Die auslaufenden Pixel werden n×n gross"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val dir = v["direction"]
        val vertical = dir <= 1
        val reverse = dir == 1 || dir == 3
        val mode = v["thresholdMode"]
        val t = v["threshold"]
        val darker = v.bool("darker")
        fun selected(c: Int): Boolean {
            // the mean is compared as a sum, exactly like the original script
            if (mode == THRESHOLD_MEAN) {
                val sum = red(c) + green(c) + blue(c)
                return if (darker) sum < t * 3 else sum > t * 3
            }
            val value = thresholdValue(c, mode)
            return value >= 0 && if (darker) value < t else value > t
        }
        return withBlocks(src, v["block"], seed, horizontalBands = !vertical) { img, n ->
            val mix = v["colorMix"] / 100f
            bleed(
                img, vertical, reverse, ::selected, max(1, v["length"] / n), v["jitter"] / 100f,
                max(1, v["step"] / n), v["endColor"].takeIf { mix > 0f }, mix, seed,
            )
        }
    }

    private fun bleed(
        src: Pixels, vertical: Boolean, reverse: Boolean, selected: (Int) -> Boolean,
        maxLength: Int, jitter: Float, step: Int, endColor: Int?, mix: Float, seed: Long,
    ): Changed {
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        val changed = BooleanArray(w * h)
        val lines = if (vertical) w else h
        val length = if (vertical) h else w
        parallelRows(lines) { l ->
            fun index(i: Int): Int {
                val p = if (reverse) length - 1 - i else i
                return if (vertical) p * w + l else l * w + p
            }
            val random = Random(seed + l * 104729L)
            var j = 0
            while (j < length) {
                val c = src.data[index(j)]
                if (selected(c)) {
                    // jitter 1: anything from 1 to maxLength; 0: always maxLength
                    val shortest = max(1, (maxLength * (1f - jitter)).toInt())
                    val streak = random.nextInt(shortest, maxLength + 1)
                    var k = 0
                    var color = c
                    while (k < streak && j + k < length) {
                        // the color changes every [step] pixels and reaches the end color on the last pixel
                        if (endColor != null && (k % step == 0 || k == streak - 1)) {
                            val t = if (streak <= 1) 1f else if (k == streak - 1) 1f else k.toFloat() / (streak - 1)
                            color = lerpArgb(c, (c and 0xFF000000.toInt()) or endColor, t * mix)
                        }
                        out.data[index(j + k)] = color
                        changed[index(j + k)] = k > 0 || endColor != null
                        k++
                    }
                    j += k
                    // like the original script, the pixel after a streak stays untouched
                    if (j < length) out.data[index(j)] = src.data[index(j)]
                } else {
                    out.data[index(j)] = c
                }
                j++
            }
        }
        return Changed(out, changed)
    }
}
