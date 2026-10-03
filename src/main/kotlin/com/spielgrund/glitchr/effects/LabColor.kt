package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Color changes in the CIELAB color space (D65): L is the lightness 0..100, a runs from
 * green (negative) to magenta (positive), b from blue to yellow. Every channel can be
 * shifted, strengthened, flipped or inverted; the colorfulness (chroma) and the hue can
 * be changed in the round LCh form (before the shifts, so a gray picture can be tinted); channels can be swapped and cut into steps. Colors
 * that end up outside what a screen can show are clipped – or wrap around, which gives
 * hard, glitchy color jumps.
 */
object LabColor : Effect("lab", "LAB colors", "Change colors in LAB color space: lightness, green–magenta, blue–yellow, chroma, hue") {
    private val swaps = listOf("None", "a ↔ b", "L ↔ a", "L ↔ b", "Rotate L → a → b → L")

    override val params = listOf(
        Param.Heading("lHeading", "L – lightness"),
        Param.Slider("lShift", "Displacement", -100, 100, 0, tip = "Raises or lowers the lightness without changing the colors"),
        Param.Slider("lContrast", "Contrast", 0, 400, 100, " %", "Around the middle lightness (L 50)"),
        Param.Toggle("lInvert", "Invert lightness", false, "Light turns dark, the colors stay"),
        Param.Heading("aHeading", "a – green ↔ magenta"),
        Param.Slider("aShift", "Displacement", -128, 128, 0, tip = "Negative: greener, positive: more magenta"),
        Param.Slider("aGain", "Strength", -400, 400, 100, " %", "Negative swaps green and magenta"),
        Param.Heading("bHeading", "b – blue ↔ yellow"),
        Param.Slider("bShift", "Displacement", -128, 128, 0, tip = "Negative: bluer, positive: yellower"),
        Param.Slider("bGain", "Strength", -400, 400, 100, " %", "Negative swaps blue and yellow"),
        Param.Heading("lchHeading", "Chroma and hue (LCh)"),
        Param.Slider("chroma", "Chroma", 0, 400, 150, " %", "0 %: grey, 100 %: unchanged, above ever more colorful – without changing the lightness"),
        Param.Slider("hue", "Rotate hue", -180, 180, 0, "°"),
        Param.Heading("glitchHeading", "Glitch"),
        Param.Choice("swap", "Swap channels", swaps, tip = "L is converted to the range of a and b for this (and back)"),
        Param.Slider("steps", "Steps", 0, 64, 0, tip = "Cuts L, a and b into this many steps; 0 = stepless"),
        Param.Choice(
            "gamut", "Outside the gamut", listOf("Clip", "Overflow"),
            tip = "What happens to colors the screen cannot show: set them to the edge or let them overflow (hard color jumps)",
        ),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    override val random = false

    /** sRGB 0..255 to linear 0..1. */
    private val toLinear = DoubleArray(256) { c ->
        val v = c / 255.0
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    // D65 white point
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val lShift = v["lShift"].toDouble()
        val lContrast = v["lContrast"] / 100.0
        val lInvert = v.bool("lInvert")
        val aShift = v["aShift"].toDouble()
        val aGain = v["aGain"] / 100.0
        val bShift = v["bShift"].toDouble()
        val bGain = v["bGain"] / 100.0
        val chroma = v["chroma"] / 100.0
        val hue = v["hue"] * PI / 180
        val hueCos = cos(hue)
        val hueSin = sin(hue)
        val swap = v["swap"]
        val steps = v["steps"]
        val wrap = v["gamut"] == 1
        val amount = v["amount"] / 100.0
        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            val lab = DoubleArray(3)
            for (x in 0 until w) {
                val c = src.data[y * w + x]
                if (alpha(c) == 0) {
                    out.data[y * w + x] = c
                    continue
                }
                toLab(c, lab)
                var l = lab[0]
                var a = lab[1]
                var b = lab[2]
                // lightness
                if (lInvert) l = 100 - l
                l = (l - 50) * lContrast + 50 + lShift
                // chroma and hue: scale and turn the (a, b) vector
                if (chroma != 1.0 || hue != 0.0) {
                    val na = (a * hueCos - b * hueSin) * chroma
                    val nb = (a * hueSin + b * hueCos) * chroma
                    a = na
                    b = nb
                }
                // then the two color axes – so a shift tints even a gray picture (chroma 0 %)
                a = a * aGain + aShift
                b = b * bGain + bShift
                // swaps; L (0..100) maps onto -128..128 and back
                fun lToAb(v: Double) = (v - 50) * 2.56
                fun abToL(v: Double) = v / 2.56 + 50
                when (swap) {
                    1 -> { val t = a; a = b; b = t }
                    2 -> { val t = l; l = abToL(a); a = lToAb(t) }
                    3 -> { val t = l; l = abToL(b); b = lToAb(t) }
                    4 -> { val t = l; l = abToL(b); b = a; a = lToAb(t) }
                }
                if (steps > 0) {
                    fun step(v: Double, lo: Double, hi: Double): Double {
                        val size = (hi - lo) / steps
                        return lo + (floor((v - lo) / size).coerceIn(0.0, steps - 1.0) + 0.5) * size
                    }
                    l = step(l, 0.0, 100.0)
                    a = step(a, -128.0, 128.0)
                    b = step(b, -128.0, 128.0)
                }
                val changed = fromLab(l, a, b, c, wrap)
                out.data[y * w + x] = if (amount >= 1.0) changed else mix(c, changed, amount)
            }
        }
        return out
    }

    /** [c] in CIELAB into [lab] (L, a, b). */
    private fun toLab(c: Int, lab: DoubleArray) {
        val r = toLinear[red(c)]
        val g = toLinear[green(c)]
        val b = toLinear[blue(c)]
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        lab[0] = 116 * fy - 16
        lab[1] = 500 * (fx - fy)
        lab[2] = 200 * (fy - fz)
    }

    /** Back to sRGB with the alpha of [like]; out-of-range channels clipped or wrapped. */
    private fun fromLab(l: Double, a: Double, b: Double, like: Int, wrap: Boolean): Int {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
        val x = inv(fx) * XN
        val y = inv(fy) * YN
        val z = inv(fz) * ZN
        val rl = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        fun channel(v: Double): Int {
            val s = if (v <= 0.0031308) 12.92 * v else 1.055 * (if (v > 0) v.pow(1 / 2.4) else 0.0) - 0.055
            val c = (s * 255).roundToInt()
            // wrap: the overflow continues from the other end
            return if (wrap) Math.floorMod(c, 256) else c.coerceIn(0, 255)
        }
        return (like and 0xFF000000.toInt()) or (channel(rl) shl 16) or (channel(gl) shl 8) or channel(bl)
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).roundToInt()
        return (a and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Hue angle of [c] in LCh, degrees (for tests). */
    internal fun hueOf(c: Int): Double {
        val lab = DoubleArray(3)
        toLab(c, lab)
        return Math.toDegrees(atan2(lab[2], lab[1]))
    }

    /** Chroma of [c] (for tests). */
    internal fun chromaOf(c: Int): Double {
        val lab = DoubleArray(3)
        toLab(c, lab)
        return hypot(lab[1], lab[2])
    }
}
