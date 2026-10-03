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
object PixelBleed : Effect("pixelbleed", "Pixelbleed", "Bright pixels bleed out in randomly long streaks") {
    private val directions = listOf("Down", "Up", "Right", "Left")

    override val params = listOf(
        Param.Choice("direction", "Direction", directions),
        Param.Choice("thresholdMode", "Threshold by", thresholdModes, THRESHOLD_MEAN, THRESHOLD_TIP),
        Param.Slider("threshold", "Threshold", 0, 255, 100, tip = "Value from which a pixel bleeds"),
        Param.Toggle("darker", "Select pixels below the threshold", false, "Off: pixels above the threshold bleed. On: pixels below it"),
        Param.Slider("length", "Max. length", 1, 3000, 300, " px", "Every streak is randomly 1 to this long; at most the longer side of the canvas", canvasMax = true),
        Param.Slider("jitter", "Length variation", 0, 100, 100, " %", "100 %: every streak is randomly 1 to max. length long. 0 %: all exactly max. length"),
        Param.Color("endColor", "Target color", 0xFF0033, "Color every streak fades to"),
        Param.Slider("colorMix", "Color strength", 0, 100, 100, " %", "How much target color is mixed in at the end of the streak: 0 % = none, 100 % = fully"),
        Param.Slider("step", "Step", 1, 100, 5, " px", "Every how many pixels the color changes; larger = steppier gradient"),
        Param.Choice("block", "Block size", blockOptions, tip = "The bleeding pixels become n×n in size"),
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
