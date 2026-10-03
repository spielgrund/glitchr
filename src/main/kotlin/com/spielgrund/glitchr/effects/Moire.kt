package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two line patterns laid over each other – lines, rings, rays, spirals, grids, dots,
 * checkerboards, zone plates. Where their lines drift in and out of step, moiré bands
 * appear: slightly turned lines give broad stripes, shifted rings give hyperbolas,
 * slightly different spacings give beats.
 *
 * Each pattern has its own spacing (to a tenth of a pixel), line width, angle (to a
 * hundredth of a degree), center, stretch along its own X and Y axes (rings become
 * ellipses) and an optional wave that bends it. The two are
 * combined like printed foils (overprint), opaque, as XOR, as light or only where both
 * lines meet. Blurring the result wipes out the fine lines and leaves only the moiré.
 * Only used as a generator; the input only gives the size.
 */
object Moire : Effect("moire", "Moiré", "Two line patterns over each other that combine into moiré patterns") {
    private val types = listOf("Lines", "Rings", "Rays", "Spiral", "Grid", "Dots", "Checkerboard", "Zone plate")
    private val mixes = listOf("Overprint", "B covers A", "Difference (XOR)", "Light (add)", "Intersection only")

    private fun pattern(p: String, name: String, angle: Int) = listOf(
        Param.Heading("${p}Heading", name),
        Param.Choice("${p}Type", "Pattern", types),
        Param.Slider(
            "${p}Spacing", "Spacing", 10, 2000, 80, " px", decimals = 1,
            tip = "Spacing of the lines; rays: spacing at the edge of a circle over half the canvas. Zone plate: spacing there",
        ),
        Param.Slider("${p}Width", "Line width", 3, 97, 50, " %", "Share of the line in the spacing; dots: area of the dots"),
        Param.Slider("${p}Angle", "Angle", 0, 36000, angle, "°", decimals = 2, tip = "Even fractions of a degree change the moiré a lot"),
        Param.Slider("${p}CenterX", "Center X", -1000, 2000, 500, " %", decimals = 1, tip = "Center for rings, rays, spiral; shifts the other patterns"),
        Param.Slider("${p}CenterY", "Center Y", -1000, 2000, 500, " %", decimals = 1),
        Param.Slider(
            "${p}StretchX", "Stretch X", 10, 1000, 100, " %",
            "Pulls the pattern apart in its own X direction (rotates with the angle); rings become ellipses",
        ),
        Param.Slider("${p}StretchY", "Stretch Y", 10, 1000, 100, " %", "Pulls the pattern apart in its own Y direction"),
        Param.Slider("${p}WaveAmp", "Wave", 0, 2000, 0, " px", decimals = 1, tip = "Bends the pattern into waves across its direction"),
        Param.Slider("${p}WaveLen", "Wavelength", 10, 3000, 300, " px"),
    )

    override val params = pattern("a", "Pattern A", 0) + pattern("b", "Pattern B", 500) + listOf(
        Param.Heading("mixHeading", "Compositing"),
        Param.Choice(
            "mix", "Mix", mixes,
            tip = "Overprint: the lines lie on the background, where they cross their colors mix like printing inks. B covers A: B lies opaquely on top. Difference: where the lines meet " +
                "they vanish. Light: the lines glow and add up (on a dark background). Intersection only: only where both lines lie",
        ),
        Param.Color("colorA", "Color A", 0x000000),
        Param.Color("colorB", "Color B", 0x000000),
        Param.Color("background", "Background", 0xFFFFFF),
        Param.Slider("blur", "Moiré only", 0, 100, 0, " px", "Blurs until the fine lines vanish and only the moiré bands remain"),
        Param.Toggle("autoContrast", "Stretch contrast", true, "Only with “Moiré only”: pulls the flat bands to full contrast"),
        Param.Choice("smoothing", "Anti-aliasing", listOf("Off (hard aliasing)", "Normal (3×3)", "High (5×5)"), 1, "Several samples per pixel"),
    )

    override val random = false

    /** One of the two patterns, with its settings read once. */
    private class Pattern(v: Values, p: String, w: Int, h: Int) {
        val type = v["${p}Type"]
        val spacing = v["${p}Spacing"] / 10.0
        val width = v["${p}Width"] / 100.0
        val cx = w * v["${p}CenterX"] / 1000.0
        val cy = h * v["${p}CenterY"] / 1000.0
        private val a = v["${p}Angle"] / 100.0 * PI / 180
        val ca = cos(a)
        val sa = sin(a)
        val stretchX = v["${p}StretchX"] / 100.0
        val stretchY = v["${p}StretchY"] / 100.0
        val waveAmp = v["${p}WaveAmp"] / 10.0
        val waveK = 2 * PI / v["${p}WaveLen"]

        /** Radius of reference: half the shorter canvas side. */
        val r0 = max(1.0, min(w, h) / 2.0)
        val rays = max(2, (2 * PI * r0 / spacing).roundToInt())
        val dotRadius = spacing * sqrt(width / PI)

        /** Whether a line (ink) lies at canvas point ([x], [y]). */
        fun ink(x: Double, y: Double): Boolean {
            val dx = x - cx
            val dy = y - cy
            // pattern coordinates: turned, then stretched along the pattern's own axes
            val u = (dx * ca + dy * sa) / stretchX
            var v = (-dx * sa + dy * ca) / stretchY
            if (waveAmp > 0) v += waveAmp * sin(u * waveK)
            return when (type) {
                1 -> band(hypot(u, v) / spacing)
                2 -> band((atan2(v, u) / (2 * PI) + 0.5) * rays)
                3 -> band(hypot(u, v) / spacing + atan2(v, u) / (2 * PI))
                4 -> band(u / spacing) || band(v / spacing)
                5 -> {
                    val fu = u / spacing - floor(u / spacing) - 0.5
                    val fv = v / spacing - floor(v / spacing) - 0.5
                    hypot(fu, fv) * spacing < dotRadius
                }
                6 -> (floor(u / spacing).toInt() + floor(v / spacing).toInt()) and 1 == 0
                // zone plate: the rings get closer outwards, [spacing] apart at [r0]
                7 -> band((u * u + v * v) / (2 * spacing * r0))
                else -> band(v / spacing)
            }
        }

        /** Ink of a stripe pattern at phase [t] (one period per unit), the line centered on whole numbers. */
        private fun band(t: Double): Boolean {
            val f = t + width / 2
            return f - floor(f) < width
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val a = Pattern(v, "a", w, h)
        val b = Pattern(v, "b", w, h)
        val colors = colors(v["mix"], v["background"], v["colorA"], v["colorB"])
        val n = when (v["smoothing"]) { 0 -> 1; 1 -> 3; else -> 5 }
        val samples = n * n

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var r = 0; var g = 0; var bl = 0
                for (sy in 0 until n) for (sx in 0 until n) {
                    val px = x + (sx + 0.5) / n
                    val py = y + (sy + 0.5) / n
                    val c = colors[(if (a.ink(px, py)) 2 else 0) + (if (b.ink(px, py)) 1 else 0)]
                    r += red(c); g += green(c); bl += blue(c)
                }
                out.data[y * w + x] = argb(255, (r + samples / 2) / samples, (g + samples / 2) / samples, (bl + samples / 2) / samples)
            }
        }
        val blur = v["blur"]
        if (blur == 0) return out
        val soft = boxBlur(boxBlur(boxBlur(out, blur), blur), blur)
        return if (v.bool("autoContrast")) stretch(soft) else soft
    }

    /** The color for no line, only A, only B and both, by mixing mode. */
    private fun colors(mix: Int, background: Int, colorA: Int, colorB: Int): IntArray {
        fun op(x: Int, y: Int, f: (Int, Int) -> Int) = argb(255, f(red(x), red(y)), f(green(x), green(y)), f(blue(x), blue(y)))
        fun multiply(x: Int, y: Int) = op(x, y) { p, q -> p * q / 255 }
        fun add(x: Int, y: Int) = op(x, y) { p, q -> min(255, p + q) }
        val bg = background or 0xFF000000.toInt()
        val ca = colorA or 0xFF000000.toInt()
        val cb = colorB or 0xFF000000.toInt()
        return when (mix) {
            1 -> intArrayOf(bg, cb, ca, cb)
            2 -> intArrayOf(bg, cb, ca, bg)
            3 -> intArrayOf(bg, add(bg, cb), add(bg, ca), add(add(bg, ca), cb))
            4 -> intArrayOf(bg, bg, bg, ca)
            // the lines lie on the background; where they cross, their inks multiply
            else -> intArrayOf(bg, cb, ca, multiply(ca, cb))
        }
    }

    /** Box blur with radius [r], horizontally and vertically; edges repeat. */
    private fun boxBlur(p: Pixels, r: Int): Pixels {
        val w = p.width
        val h = p.height
        val tmp = Pixels(w, h)
        val out = Pixels(w, h)
        val size = 2 * r + 1
        parallelRows(h) { y ->
            var sr = 0; var sg = 0; var sb = 0
            fun at(x: Int) = p.data[y * w + x.coerceIn(0, w - 1)]
            for (i in -r..r) at(i).let { sr += red(it); sg += green(it); sb += blue(it) }
            for (x in 0 until w) {
                tmp.data[y * w + x] = argb(255, sr / size, sg / size, sb / size)
                at(x + r + 1).let { sr += red(it); sg += green(it); sb += blue(it) }
                at(x - r).let { sr -= red(it); sg -= green(it); sb -= blue(it) }
            }
        }
        parallelRows(w) { x ->
            var sr = 0; var sg = 0; var sb = 0
            fun at(y: Int) = tmp.data[y.coerceIn(0, h - 1) * w + x]
            for (i in -r..r) at(i).let { sr += red(it); sg += green(it); sb += blue(it) }
            for (y in 0 until h) {
                out.data[y * w + x] = argb(255, sr / size, sg / size, sb / size)
                at(y + r + 1).let { sr += red(it); sg += green(it); sb += blue(it) }
                at(y - r).let { sr -= red(it); sg -= green(it); sb -= blue(it) }
            }
        }
        return out
    }

    /** Stretches the brightness range (without the outer 1 %) to the full range, all channels alike. */
    private fun stretch(p: Pixels): Pixels {
        val histogram = IntArray(256)
        for (c in p.data) {
            histogram[red(c)]++; histogram[green(c)]++; histogram[blue(c)]++
        }
        val total = p.data.size * 3
        fun level(fraction: Double): Int {
            var sum = 0
            for (i in 0..255) {
                sum += histogram[i]
                if (sum >= total * fraction) return i
            }
            return 255
        }
        val low = level(0.01)
        val high = level(0.99)
        if (high - low < 2) return p
        val out = Pixels(p.width, p.height)
        fun ch(c: Int) = ((c - low) * 255 / (high - low)).coerceIn(0, 255)
        for (i in p.data.indices) {
            val c = p.data[i]
            out.data[i] = argb(255, ch(red(c)), ch(green(c)), ch(blue(c)))
        }
        return out
    }
}
