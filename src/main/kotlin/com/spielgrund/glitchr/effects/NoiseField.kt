package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.channel
import com.spielgrund.glitchr.image.clamp255
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A directed noise mixed into the picture. The noise runs in a direction (0–360°): along
 * it, it can be stretched into streaks, and its size, stretch, detail and contrast change
 * smoothly from a start value to an end value across the picture. The noise is then
 * mixed with the picture in one of many ways – plain, through a threshold, by shifting
 * hue, saturation or lightness, or by glitching the colors. The noise only ever changes
 * pixel values, never moves pixels (that is what Displace is for).
 *
 * Where the mixing happens is decided by the picture itself (its brightness, darkness or
 * saturation against the threshold, with the noise roughening that edge) – or, if
 * wanted, by the noise alone.
 */
object NoiseField : Effect("noisefield", "Noise", "Directional noise with many ways to mix it into the picture") {
    private val types = listOf("Perlin", "Fractal (fBm)", "Ridged", "Cells (Worley)", "Value noise", "White noise", "Voronoi")
    private val mixes = listOf(
        "Blend", "Threshold", "Threshold cut-out", "Hue (HSL)", "Saturation (HSL)", "Lightness (HSL)",
        "Overlay", "Difference", "Swap channels", "Random values", "RGB values", "Invert", "Color steps",
    )

    override val params = listOf(
        Param.Choice("type", "Noise", types, default = 4), // Wert-Noise
        Param.Slider("direction", "Direction", 0, 359, 0, "°", "Direction of the noise; along this direction the start/end values change"),
        Param.Slider("scaleStart", "Size start", 2, 1000, 2, " px"),
        Param.Slider("scaleEnd", "Size end", 2, 1000, 120, " px"),
        Param.Slider("stretchStart", "Stretch start", 100, 1500, 100, " %", "Pulls the noise into streaks along the direction"),
        Param.Slider("stretchEnd", "Stretch end", 100, 1500, 100, " %"),
        Param.Slider("detailStart", "Detail start", 1, 8, 3, tip = "Finer layers (fractal, ridged)"),
        Param.Slider("detailEnd", "Detail end", 1, 8, 3),
        Param.Slider("contrastStart", "Contrast start", 0, 400, 100, " %"),
        Param.Slider("contrastEnd", "Contrast end", 0, 400, 100, " %"),
        Param.Slider("offset", "Offset", 0, 2000, 0, " px", "Shifts the noise along the direction"),
        Param.Slider(
            "imageShape", "Picture shapes noise", 0, 100, 50, " %",
            "The brightness shapes of the picture bend the noise and flow into it – it grows out of the subject instead of lying on top",
        ),
        Param.Choice(
            "colorMode", "Noise color", listOf("Greyscale", "Two colors", "Picture palette", "Picture color"), 3,
            tip = "Picture color: every pixel keeps its color and is only made lighter or darker by the noise",
        ),
        Param.Color("color1", "Color 1", 0x0B0B2E, "For “Two colors”: color of the dark spots"),
        Param.Color("color2", "Color 2", 0xFF4FA3, "For “Two colors”: color of the bright spots"),
        Param.Choice("mix", "Mix", mixes, default = 1, tip = "How the noise is combined with the picture"), // Schwelle
        Param.Choice(
            "control", "Control", listOf("Picture brightness", "Picture darkness", "Picture saturation", "Noise"),
            tip = "What decides where it is mixed: the picture below (bright, dark or colorful above the threshold) or the noise itself",
        ),
        Param.Slider("noiseInfluence", "Noise influence", 0, 100, 30, " %", "When controlled by the picture: how much the noise roughens the edge of the threshold"),
        Param.Slider("amount", "Mix strength", 0, 100, 100, " %"),
        Param.Slider("threshold", "Threshold", 0, 100, 0, " %", "The control value from which it is mixed"),
        Param.Slider("softness", "Soft edge", 0, 50, 5, " %", "Transition at the threshold"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val type = v["type"]
        val dir = v["direction"] * PI / 180
        val ux = cos(dir)
        val uy = sin(dir)
        // half the picture's extent along the direction, so t runs 0..1 from one side to the other
        val reach = max(1.0, (abs(w * ux) + abs(h * uy)) / 2)
        val logScale0 = ln(v["scaleStart"].toDouble())
        val logScale1 = ln(v["scaleEnd"].toDouble())
        val offset = v["offset"].toDouble()
        val mixMode = v["mix"]
        val amount = v["amount"] / 100.0
        val threshold = v["threshold"] / 100.0
        val softness = max(1e-3, v["softness"] / 100.0)
        val colorMode = v["colorMode"]
        val color1 = v["color1"] or 0xFF000000.toInt()
        val color2 = v["color2"] or 0xFF000000.toInt()
        val palette = if (colorMode == 2) dominantColors(src, 6, seed).sortedBy { luma(it) }.toIntArray() else null
        fun lerp(a: Int, b: Int, t: Double) = a + (b - a) * t

        // Size and stretch change along the direction through a series of undistorted noise
        // levels in stepped sizes that are blended into each other – stretching the
        // coordinates themselves would fan the noise out.
        val logStretch0 = ln(v["stretchStart"] / 100.0)
        val logStretch1 = ln(v["stretchEnd"] / 100.0)
        val range = max(abs(logScale1 - logScale0), abs(logStretch1 - logStretch0))
        val levels = (kotlin.math.ceil(range / ln(1.35)).toInt() + 1).coerceIn(2, 24)

        /** Raw noise -1..1 of level [level] (0..levels-1) at the picture position. */
        fun level(level: Int, along: Double, across: Double, detail: Double): Double {
            val f = level.toDouble() / (levels - 1)
            val scale = exp(logScale0 + (logScale1 - logScale0) * f)
            val stretch = exp(logStretch0 + (logStretch1 - logStretch0) * f)
            val nx = (along + offset) / (scale * stretch)
            val ny = across / scale
            return when (type) {
                1 -> fractal(noise, nx, ny, detail, ridged = false)
                2 -> fractal(noise, nx, ny, detail, ridged = true)
                3 -> noise.worley(nx, ny)
                4 -> noise.value(nx, ny)
                5 -> noise.white(floor(nx).toInt(), floor(ny).toInt())
                6 -> noise.voronoi(nx, ny)
                else -> noise.perlin(nx, ny)
            }
        }

        // the picture's brightness, softly blurred: its shapes bend the noise and flow into it
        val shape = v["imageShape"] / 100.0
        val shapeLuma = if (shape > 0) blurredLuma(src, max(2, min(w, h) / 80)) else null

        /** Position 0..1 along the direction. */
        fun tAt(x: Double, y: Double) = (((x - w / 2.0) * ux + (y - h / 2.0) * uy) / reach / 2 + 0.5).coerceIn(0.0, 1.0)

        /** Noise size (px) at position [t] along the direction. */
        fun scaleAt(t: Double) = exp(logScale0 + (logScale1 - logScale0) * t)

        /** Noise value 0..1 at canvas pixel ([x], [y]). */
        fun valueAt(x: Double, y: Double): Double {
            val dx = x - w / 2.0
            val dy = y - h / 2.0
            var along = dx * ux + dy * uy
            var across = -dx * uy + dy * ux
            val t = (along / reach / 2 + 0.5).coerceIn(0.0, 1.0)
            val light = shapeLuma?.let { it[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)].toDouble() }
            if (light != null) {
                // bend the noise along the picture's forms
                val bend = (light - 0.5) * shape * scaleAt(t) * 2
                along += bend
                across += bend * 0.5
            }
            val detail = lerp(v["detailStart"], v["detailEnd"], t)
            val contrast = lerp(v["contrastStart"], v["contrastEnd"], t) / 100.0
            val n = if (range < 1e-9) level(0, along, across, detail) else {
                // blend the two neighbouring levels
                val pos = t * (levels - 1)
                val i = floor(pos).toInt().coerceAtMost(levels - 2)
                val f = (pos - i).let { it * it * (3 - 2 * it) }
                val a = level(i, along, across, detail)
                if (f <= 0.0) a else a * (1 - f) + level(i + 1, along, across, detail) * f
            }
            val value = n * 0.5 * contrast + 0.5
            // and let the picture's brightness flow into the value
            val mixed = if (light == null) value else value * (1 - 0.6 * shape) + light * 0.6 * shape
            return mixed.coerceIn(0.0, 1.0)
        }

        /** Color of the noise with value [n] on top of the picture color [c]. */
        fun noiseColor(n: Double, c: Int): Int = when (colorMode) {
            3 -> {
                // the pixel's own color, only made lighter or darker by the noise
                val t = (n - 0.5) * 2
                if (t < 0) lerpArgb(c, c and 0xFF000000.toInt(), (-t * 0.75).toFloat())
                else lerpArgb(c, c or 0x00FFFFFF, (t * 0.55).toFloat())
            }
            1 -> lerpArgb(color1, color2, n.toFloat())
            2 -> {
                val p = palette!!
                val pos = n * (p.size - 1)
                val i = floor(pos).toInt().coerceIn(0, p.size - 2)
                lerpArgb(p[i], p[i + 1], (pos - i).toFloat())
            }
            else -> (n * 255).roundToInt().let { argb(255, it, it, it) }
        }

        /** 0..1: how far [n] lies above the threshold, with a soft edge. */
        fun above(n: Double): Double {
            val t = ((n - (threshold - softness / 2)) / softness).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        val control = v["control"]
        val imageControl = control != 3
        val influence = v["noiseInfluence"] / 100.0
        // modes that only ever worked above a threshold
        val thresholdModes = setOf(1, 2, 8, 9, 11)

        /**
         * 0..1: where to mix at this pixel. Controlled by the picture: its brightness,
         * darkness or saturation against the threshold, the edge roughened by the noise.
         * Controlled by the noise: the noise against the threshold (for the threshold
         * modes), everywhere for the others.
         */
        fun maskAt(c: Int, n: Double): Double = when {
            imageControl -> {
                val value = when (control) {
                    0 -> luma(c) / 255.0
                    1 -> 1 - luma(c) / 255.0
                    else -> saturation(c)
                }
                above(value + (n - 0.5) * influence)
            }
            mixMode in thresholdModes -> above(n)
            else -> 1.0
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                val n = valueAt(x + 0.5, y + 0.5)
                val m = if (mixMode == 0) 1.0 else maskAt(c, n)
                val k = (m * amount).toFloat()
                out.data[i] = when (mixMode) {
                    1 -> keepAlpha(c, lerpArgb(c, noiseColor(n, c), k))
                    2 -> {
                        // cut the picture out where the control stays below the threshold
                        val keep = 1 - amount * (1 - m)
                        ((alpha(c) * keep).roundToInt() shl 24) or (c and 0xFFFFFF)
                    }
                    3 -> hsl(c) { hue, sat, light -> Triple(hue + (n - 0.5) * amount * m, sat, light) }
                    4 -> hsl(c) { hue, sat, light -> Triple(hue, sat * (1 + (n - 0.5) * 4 * amount * m), light) }
                    5 -> hsl(c) { hue, sat, light -> Triple(hue, sat, light + (n - 0.5) * amount * m) }
                    6 -> keepAlpha(c, lerpArgb(c, overlay(c, noiseColor(n, c)), k))
                    7 -> keepAlpha(c, lerpArgb(c, difference(c, noiseColor(n, c)), k))
                    8 -> {
                        // rotate the color channels; the noise decides which way round
                        val rotated = if (n < 0.5) argb(alpha(c), green(c), blue(c), red(c)) else argb(alpha(c), blue(c), red(c), green(c))
                        lerpArgb(c, rotated, k)
                    }
                    9 -> {
                        // colored grain on the pixel values
                        fun grain(ch: Int, salt: Int) = clamp255((ch + noise.white(x + salt, y) * 127 * k).roundToInt())
                        argb(alpha(c), grain(red(c), 0), grain(green(c), 7919), grain(blue(c), 15887))
                    }
                    10 -> {
                        // the noise pushes the three channel values by different amounts
                        val push = (n - 0.5) * 2 * 160 * k
                        val third = ((n * 3) % 1.0 - 0.5) * 2 * 160 * k
                        argb(
                            alpha(c), clamp255((red(c) + push).roundToInt()), clamp255((green(c) - push * 0.6).roundToInt()),
                            clamp255((blue(c) + third).roundToInt()),
                        )
                    }
                    11 -> lerpArgb(c, c xor 0x00FFFFFF, k) // invert
                    12 -> {
                        // posterize: the noise sets how many levels each channel keeps (2..16)
                        val levels = 2 + ((1 - n) * 14).roundToInt()
                        fun step(ch: Int) = ((ch * (levels - 1) + 127) / 255) * 255 / (levels - 1)
                        lerpArgb(c, argb(alpha(c), step(red(c)), step(green(c)), step(blue(c))), k)
                    }
                    else -> keepAlpha(c, lerpArgb(c, noiseColor(n, c), amount.toFloat()))
                }
            }
        }
        return out
    }

    /** Fractal noise with a fractional number of octaves, so the detail can change smoothly. */
    private fun fractal(noise: Noise, x: Double, y: Double, octaves: Double, ridged: Boolean): Double {
        var sum = 0.0
        var norm = 0.0
        var amp = 1.0
        var freq = 1.0
        val full = floor(octaves).toInt()
        for (o in 0..full) {
            val weight = if (o < full) 1.0 else octaves - full
            if (weight <= 0) break
            val p = noise.perlin(x * freq + o * 17.3, y * freq + o * 31.7)
            val n = if (ridged) (1 - abs(p)).let { it * it } * 2 - 1 else p
            sum += n * amp * weight
            norm += amp * weight
            amp *= 0.5
            freq *= 2.0
        }
        return if (norm == 0.0) 0.0 else sum / norm
    }

    private fun keepAlpha(original: Int, mixed: Int) = (original and 0xFF000000.toInt()) or (mixed and 0xFFFFFF)

    /** Brightness 0..1, box-blurred with radius [r]. */
    private fun blurredLuma(src: Pixels, r: Int): FloatArray {
        val w = src.width
        val h = src.height
        val l = FloatArray(w * h) { luma(src.data[it]) / 255f }
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            for (i in -r..r) sum += l[y * w + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / (2 * r + 1)
                sum += l[y * w + (x + r + 1).coerceIn(0, w - 1)] - l[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                l[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return l
    }

    /** Saturation 0..1 (HSV style). */
    private fun saturation(c: Int): Double {
        val mx = max(red(c), max(green(c), blue(c)))
        val mn = min(red(c), min(green(c), blue(c)))
        return if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
    }

    private fun overlay(base: Int, top: Int): Int {
        fun ch(b: Int, t: Int) = if (b < 128) 2 * b * t / 255 else 255 - 2 * (255 - b) * (255 - t) / 255
        return argb(alpha(base), ch(red(base), red(top)), ch(green(base), green(top)), ch(blue(base), blue(top)))
    }

    private fun difference(base: Int, top: Int) =
        argb(alpha(base), abs(red(base) - red(top)), abs(green(base) - green(top)), abs(blue(base) - blue(top)))

    /** Changes [c] in HSL: [change] gets hue (0..1, wraps), saturation and lightness (0..1). */
    private fun hsl(c: Int, change: (Double, Double, Double) -> Triple<Double, Double, Double>): Int {
        val r = red(c) / 255.0
        val g = green(c) / 255.0
        val b = blue(c) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        var hue = 0.0
        var sat = 0.0
        if (d > 1e-9) {
            sat = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            hue = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
        }
        val (h2, s2, l2) = change(hue, sat, l)
        val hh = h2 - floor(h2)
        val ss = s2.coerceIn(0.0, 1.0)
        val ll = l2.coerceIn(0.0, 1.0)
        if (ss < 1e-9) return argb(alpha(c), (ll * 255).roundToInt(), (ll * 255).roundToInt(), (ll * 255).roundToInt())
        val q = if (ll < 0.5) ll * (1 + ss) else ll + ss - ll * ss
        val p = 2 * ll - q
        fun hue2rgb(t0: Double): Int {
            val t = t0 - floor(t0)
            val value = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return clamp255((value * 255).roundToInt())
        }
        return argb(alpha(c), hue2rgb(hh + 1.0 / 3), hue2rgb(hh), hue2rgb(hh - 1.0 / 3))
    }
}
