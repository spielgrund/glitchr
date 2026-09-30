package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Texture placement by UV coordinates: two channels of the picture (red and green by
 * default, freely chosen) are read as the texture coordinates U and V (0..1). A pattern –
 * checkerboard, stripes, dots, grid, rings, waves, bricks, hexagons, noise, a gradient or
 * the picture itself – is laid out in that UV space, so it follows whatever the channels
 * describe (a UV pass from 3D, gradients, a generated field …). The UV fields can be
 * smoothed first (against 8-bit steps), the pattern moved, turned and scaled. Every pixel
 * is sampled several times with the UVs interpolated between the pixels (anti-aliasing).
 */
object UvTexture : Effect("uvtexture", "UV-Textur", "Legt ein Muster über UV-Koordinaten aus zwei Farbkanälen – wie eine Textur auf einem 3D-UV-Pass") {
    private val channels = listOf("Rot", "Grün", "Blau", "Alpha", "Helligkeit", "Farbton", "Sättigung")
    private val patterns = listOf(
        "Schachbrett", "Streifen", "Punkte", "Gitter", "Ringe", "Wellen", "Ziegel", "Sechsecke", "Noise", "Verlauf", "Bild",
    )
    private const val CHECKER = 0
    private const val STRIPES = 1
    private const val DOTS = 2
    private const val GRID = 3
    private const val RINGS = 4
    private const val WAVES = 5
    private const val BRICKS = 6
    private const val HEXAGONS = 7
    private const val NOISE = 8
    private const val GRADIENT = 9
    private const val PICTURE = 10

    override val params = listOf(
        Param.Heading("uvHeading", "UV"),
        Param.Choice("uChannel", "X (U) aus", channels, 0),
        Param.Toggle("uInvert", "X umkehren", false),
        Param.Choice("vChannel", "Y (V) aus", channels, 1),
        Param.Toggle("vInvert", "Y umkehren", false),
        Param.Toggle("blackEmpty", "Schwarz ist kein UV", true, "Schwarze (und durchsichtige) Pixel sind Hintergrund: dort kein Muster, und sie verfälschen die UVs daneben nicht"),
        Param.Slider("smooth", "Glätten", 0, 200, 2, " px", "Glättet die UV-Kanäle, bevor das Muster nachgeschlagen wird – weichere Verläufe, keine 8-Bit-Stufen"),
        Param.Heading("patternHeading", "Muster"),
        Param.Choice("pattern", "Muster", patterns, CHECKER, "Bild: das Bild selbst wird über die UVs neu verteilt (UV-Remap)"),
        Param.Slider("repeats", "Wiederholungen", 1, 200, 8, tip = "So oft wiederholt sich das Muster über den UV-Bereich 0 bis 1"),
        Param.Slider("lineWidth", "Linienbreite", 1, 50, 10, " %", "Gitter, Ziegel, Sechsecke: Breite der Linien; Punkte: Grösse"),
        Param.Choice("colors", "Farben", listOf("Zwei Farben", "Verlauf")),
        Param.Color("colorA", "Farbe 1", 0x141414),
        Param.Color("colorB", "Farbe 2", 0xF0F0F0),
        Param.Ramp("ramp", "Verlauf", RampPalette.SUNSET.ramp.format(), "Farben: Verlauf – das Muster wählt seine Farben aus diesem Verlauf"),
        Param.Heading("placeHeading", "Platzierung"),
        Param.Slider("offsetU", "Verschieben X", -1000, 1000, 0, " %", decimals = 1),
        Param.Slider("offsetV", "Verschieben Y", -1000, 1000, 0, " %", decimals = 1),
        Param.Slider("rotation", "Drehung", -180, 180, 0, "°"),
        Param.Slider("scale", "Skalierung", 1, 1000, 100, " %"),
        Param.Heading("outHeading", "Ausgabe"),
        Param.Choice("blend", "Mischen", listOf("Ersetzen", "Multiplizieren", "Weiches Licht")),
        Param.Choice("antialias", "Kantenglättung", listOf("Aus", "2 × 2", "4 × 4"), 2, "Jeder Pixel wird mehrfach abgetastet, die UVs dazwischen interpoliert"),
        Param.Slider("amount", "Stärke", 0, 100, 100, " %"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // where there are UVs at all: not transparent, and (if asked) not black
        val blackEmpty = v.bool("blackEmpty")
        val valid = FloatArray(w * h) {
            val c = src.data[it]
            if (alpha(c) == 0 || (blackEmpty && (c and 0xFFFFFF) == 0)) 0f else 1f
        }
        val r = max(1, (v["smooth"] / 2.0).roundToInt())
        val smoothValid = if (v["smooth"] > 0) Grow.blur(valid, w, h, r) else valid
        // the UV fields, 0..1; smoothed only over valid pixels (a weighted blur)
        fun field(channel: Int, invert: Boolean): FloatArray {
            val f = FloatArray(w * h) { value(src.data[it], channel).toFloat() }
            if (invert) for (i in f.indices) f[i] = 1 - f[i]
            if (v["smooth"] <= 0) return f
            for (i in f.indices) f[i] *= valid[i]
            val b = Grow.blur(f, w, h, r)
            for (i in b.indices) b[i] = if (smoothValid[i] > 1e-4f) b[i] / smoothValid[i] else 0f
            return b
        }
        val u = field(v["uChannel"], v.bool("uInvert"))
        val vv = field(v["vChannel"], v.bool("vInvert"))

        val pattern = v["pattern"]
        val repeats = v["repeats"].toDouble()
        val line = v["lineWidth"] / 100.0
        val useRamp = v["colors"] == 1
        val colorA = v["colorA"] or 0xFF000000.toInt()
        val colorB = v["colorB"] or 0xFF000000.toInt()
        val ramp = ColorRamp.parse(v.text("ramp").ifBlank { RampPalette.SUNSET.ramp.format() })
        val offU = v["offsetU"] / 1000.0
        val offV = v["offsetV"] / 1000.0
        val rot = Math.toRadians(v["rotation"].toDouble())
        val cr = cos(-rot)
        val sr = sin(-rot)
        val scale = max(0.01, v["scale"] / 100.0)
        val blend = v["blend"]
        val amount = v["amount"] / 100.0
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        val noise = Noise(seed)

        /** The texture at UV ([pu], [pv]). */
        fun texture(pu: Double, pv: Double): Int {
            // placement around the middle of the UV square: moved, turned, scaled
            val du = pu - 0.5 - offU
            val dv = pv - 0.5 - offV
            val tu = (du * cr - dv * sr) / scale + 0.5
            val tv = (du * sr + dv * cr) / scale + 0.5
            val x = tu * repeats
            val y = tv * repeats
            fun frac(a: Double) = a - floor(a)
            // a share 0..1 between the two colors (or along the ramp)
            val t: Double = when (pattern) {
                CHECKER -> ((floor(x) + floor(y)).toLong() and 1L).toDouble()
                STRIPES -> if (frac(x) < 0.5) 0.0 else 1.0
                DOTS -> if (hypot(frac(x) - 0.5, frac(y) - 0.5) < 0.1 + line * 0.8) 1.0 else 0.0
                GRID -> if (frac(x) < line || frac(y) < line) 1.0 else 0.0
                RINGS -> if (frac(hypot(x - repeats / 2, y - repeats / 2)) < 0.5) 0.0 else 1.0
                WAVES -> if (frac(y + 0.25 * sin(2 * PI * x)) < 0.5) 0.0 else 1.0
                BRICKS -> {
                    val row = floor(y)
                    val bx = x * 0.5 + if (row.toLong() % 2 == 0L) 0.0 else 0.25
                    if (frac(bx) < line * 0.5 || frac(y) < line) 1.0 else 0.0
                }
                HEXAGONS -> if (hexEdge(x, y) < line * 0.5) 1.0 else 0.0
                NOISE -> (noise.fbm(x, y, 4) * 0.5 + 0.5).coerceIn(0.0, 1.0)
                GRADIENT -> frac(x)
                else -> return sampleBilinear(src, frac(tu) * w, frac(tv) * h, Edge.WRAP)
            }
            return if (useRamp) ramp.lut[(t * 255).roundToInt().coerceIn(0, 255)] or 0xFF000000.toInt()
            else mixColor(colorA, colorB, t)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                for (sy in 0 until sub) for (sx in 0 until sub) {
                    // the UVs between the pixel centres
                    val fx = x + (sx + 0.5) / sub - 0.5
                    val fy = y + (sy + 0.5) / sub - 0.5
                    // only between valid pixels; their share is how much of the sample the texture covers
                    val cover = bilinear(valid, w, h, fx, fy, null)
                    if (cover <= 1e-6) continue
                    val c = texture(bilinear(u, w, h, fx, fy, valid) / cover, bilinear(vv, w, h, fx, fy, valid) / cover)
                    val ca = alpha(c) * cover
                    a += ca; r += red(c) * ca; g += green(c) * ca; b += blue(c) * ca
                }
                val n = (sub * sub).toDouble()
                val tex = if (a <= 0) 0 else argb((a / n).roundToInt(), (r / a).roundToInt(), (g / a).roundToInt(), (b / a).roundToInt())
                out.data[i] = combine(base, tex, blend, amount)
            }
        }
        return out
    }

    /** A channel of [c] as 0..1. */
    private fun value(c: Int, channel: Int): Double {
        val r = red(c) / 255.0
        val g = green(c) / 255.0
        val b = blue(c) / 255.0
        return when (channel) {
            0 -> r
            1 -> g
            2 -> b
            3 -> alpha(c) / 255.0
            4 -> 0.299 * r + 0.587 * g + 0.114 * b
            else -> {
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val d = mx - mn
                if (channel == 6) {
                    val l = (mx + mn) / 2
                    if (d < 1e-9) 0.0 else d / (1 - abs(2 * l - 1))
                } else {
                    if (d < 1e-9) 0.0
                    else {
                        var hue = when (mx) {
                            r -> ((g - b) / d) % 6
                            g -> (b - r) / d + 2
                            else -> (r - g) / d + 4
                        } / 6
                        if (hue < 0) hue += 1
                        hue
                    }
                }
            }
        }
    }

    /** Distance to the nearest edge of a pointy hexagon grid (cells of size 1). */
    private fun hexEdge(x: Double, y: Double): Double {
        val r = 0.5 / (sqrt(3.0) / 2)
        val q = (sqrt(3.0) / 3 * x - y / 3) / r
        val rr = (2.0 / 3 * y) / r
        var cq = Math.round(q).toDouble()
        var crr = Math.round(rr).toDouble()
        val cs = Math.round(-q - rr).toDouble()
        val dq = abs(cq - q)
        val dr = abs(crr - rr)
        val ds = abs(cs - (-q - rr))
        if (dq > dr && dq > ds) cq = -crr - cs else if (dr > ds) crr = -cq - cs
        val dx = x - r * (sqrt(3.0) * cq + sqrt(3.0) / 2 * crr)
        val dy = y - r * 1.5 * crr
        var m = 0.0
        for (k in 0..2) m = max(m, abs(dx * cos(k * PI / 3) + dy * sin(k * PI / 3)))
        return 0.5 - m
    }

    /** Bilinear read of [a], each of the four pixels weighted by [weight] too (not normalized). */
    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Double, y: Double, weight: FloatArray?): Double {
        val cx = x.coerceIn(0.0, w - 1.0)
        val cy = y.coerceIn(0.0, h - 1.0)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = cx - x0
        val fy = cy - y0
        fun at(i: Int) = if (weight == null) a[i].toDouble() else a[i].toDouble() * weight[i]
        return (at(y0 * w + x0) * (1 - fx) + at(y0 * w + x1) * fx) * (1 - fy) + (at(y1 * w + x0) * (1 - fx) + at(y1 * w + x1) * fx) * fy
    }

    private fun mixColor(a: Int, b: Int, t: Double): Int {
        fun m(p: Int, q: Int) = (p + (q - p) * t).roundToInt()
        return argb(m(alpha(a), alpha(b)), m(red(a), red(b)), m(green(a), green(b)), m(blue(a), blue(b)))
    }

    /** The texture over the picture: replaced, multiplied or as soft light; the picture's alpha stays. */
    private fun combine(base: Int, tex: Int, blend: Int, amount: Double): Int {
        fun soft(p: Int, q: Int): Double {
            val a = p / 255.0
            val b = q / 255.0
            return 255 * if (b < 0.5) a - (1 - 2 * b) * a * (1 - a) else a + (2 * b - 1) * ((if (a < 0.25) ((16 * a - 12) * a + 4) * a else sqrt(a)) - a)
        }
        val ta = alpha(tex) / 255.0 * amount
        fun ch(p: Int, q: Int): Int {
            val m = when (blend) {
                1 -> p * q / 255.0
                2 -> soft(p, q)
                else -> q.toDouble()
            }
            return (p + (m - p) * ta).roundToInt().coerceIn(0, 255)
        }
        return argb(alpha(base), ch(red(base), red(tex)), ch(green(base), green(tex)), ch(blue(base), blue(tex)))
    }
}
