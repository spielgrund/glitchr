package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.red
import kotlin.math.max
import kotlin.math.min

/** What a threshold compares; every mode gives a value 0..255 (see [thresholdValue]). */
internal val thresholdModes = listOf(
    "Helligkeit (Luma)", "Mittelwert RGB", "Hellster Kanal (HSV)", "Dunkelster Kanal", "Sättigung", "Buntheit (Chroma)",
    "Farbton", "Rot", "Grün", "Blau",
)

internal const val THRESHOLD_LUMA = 0
internal const val THRESHOLD_MEAN = 1
internal const val THRESHOLD_HUE = 6

internal const val THRESHOLD_TIP =
    "Farbton: 0 Rot, 43 Gelb, 85 Grün, 128 Cyan, 170 Blau, 213 Magenta, 255 wieder Rot; fast graue Pixel haben keinen Farbton " +
        "und zählen nie. Sättigung: wie rein die Farbe ist, Buntheit: wie weit hellster und dunkelster Kanal auseinanderliegen"

/** Grays below this chroma have no meaningful hue. */
private const val MIN_CHROMA_FOR_HUE = 12

/** The value 0..255 of [c] for threshold mode [mode]; -1 where the mode has no value (the hue of grays). */
internal fun thresholdValue(c: Int, mode: Int): Int {
    val r = red(c)
    val g = green(c)
    val b = blue(c)
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    return when (mode) {
        THRESHOLD_MEAN -> (r + g + b) / 3
        2 -> mx
        3 -> mn
        4 -> if (mx == 0) 0 else (mx - mn) * 255 / mx
        5 -> mx - mn
        THRESHOLD_HUE -> {
            val d = mx - mn
            if (d < MIN_CHROMA_FOR_HUE) return -1
            // six sectors of 256, then scaled to 0..255
            val h = when (mx) {
                r -> Math.floorMod((g - b) * 256 / d, 1536)
                g -> 512 + (b - r) * 256 / d
                else -> 1024 + (r - g) * 256 / d
            }
            h * 256 / 1536
        }
        7 -> r
        8 -> g
        9 -> b
        else -> luma(c)
    }
}

/**
 * Whether [c] is selected by the range [lower]..[upper] (inclusive), or with [invert] by
 * everything outside it. For the hue a range with lower > upper wraps around red (e.g.
 * 230..20); for the others the two bounds are simply ordered. Pixels without a value
 * (grays, by hue) are never selected.
 */
internal fun thresholdSelects(c: Int, mode: Int, lower: Int, upper: Int, invert: Boolean): Boolean {
    val v = thresholdValue(c, mode)
    if (v < 0) return false
    val inside = if (mode == THRESHOLD_HUE && lower > upper) v >= lower || v <= upper
    else v in min(lower, upper)..max(lower, upper)
    return inside != invert
}
