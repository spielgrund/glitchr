package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Sharpening: a classic sharpen, unsharp mask with radius and threshold, and clarity
 * (local contrast in the midtones). Every mode works on the detail – the picture minus a
 * blurred copy of it. Oversharpening pushes that detail far beyond sense: along the
 * sharpened edges the brightness blows out, colors explode, values overflow or the
 * channels split into colored halos.
 */
object Sharpen : Effect("sharpen", "Scharfzeichnen", "Schärfen, Unscharf maskieren und Clarity – mit Übersteuerung für glitchige Kanten") {
    private val modes = listOf("Schärfen", "Unscharf maskieren", "Clarity")
    private val overTypes = listOf("Helligkeit", "Sättigung", "Überlauf", "Farbkanäle")

    override val params = listOf(
        Param.Choice(
            "mode", "Art", modes, 1,
            "Schärfen: feine Details (fester kleiner Radius) · Unscharf maskieren: klassisch mit Radius und Schwelle · " +
                "Clarity: lokaler Kontrast in den Mitteltönen, grossflächig",
        ),
        Param.Slider("amount", "Stärke", -300, 500, 150, " %", "Negativ: die Details werden abgeschwächt statt verstärkt, unter −100 % kehren sie sich um"),
        Param.Slider("radius", "Radius", 3, 2000, 20, tip = "In Zehntelpixeln: 20 = 2 px; bei Clarity wirkt er achtfach"),
        Param.Slider("threshold", "Schwelle", 0, 128, 0, "", "Kleinere Helligkeitsunterschiede bleiben ungeschärft, z. B. Rauschen"),
        Param.Choice("channels", "Kanäle", listOf("RGB", "Nur Helligkeit"), tip = "Nur Helligkeit schärft ohne Farbsäume; Clarity wirkt immer auf die Helligkeit"),
        Param.Slider("over", "Übersteuern", 0, 100, 0, " %", "Treibt die Schärfung weit über das Sinnvolle hinaus: an den Kanten entstehen Glitches"),
        Param.Choice(
            "overType", "Übersteuerung", overTypes,
            tip = "Helligkeit: die Kanten brennen hell und dunkel aus · Sättigung: an den Kanten explodieren die Farben · " +
                "Überlauf: die Werte laufen über und springen um · Farbkanäle: Rot und Blau schiessen gegenläufig, bunte Säume",
        ),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val mode = v["mode"]
        val amount = v["amount"] / 100.0
        val radius = v["radius"] / 10.0
        val threshold = v["threshold"].toDouble()
        val lumaOnly = mode == 2 || v["channels"] == 1
        val over = v["over"] / 100.0
        val overType = v["overType"]
        val sigma = when (mode) {
            0 -> 0.8
            2 -> radius * 8
            else -> radius
        }

        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val c = src.data[i]
            r[i] = (c shr 16 and 0xFF).toFloat()
            g[i] = (c shr 8 and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
            y[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        // the blurred copies: of the brightness always (for the threshold and the edges), of the colors for RGB
        val blurY = Blur.gauss(y, w, h, sigma)
        val blurR = if (lumaOnly) null else Blur.gauss(r, w, h, sigma)
        val blurG = if (lumaOnly) null else Blur.gauss(g, w, h, sigma)
        val blurB = if (lumaOnly) null else Blur.gauss(b, w, h, sigma)

        val out = Pixels(w, h)
        parallelRows(h) { row ->
            for (i in row * w until (row + 1) * w) {
                val dy = (y[i] - blurY[i]).toDouble()
                // the threshold fades in over a few levels instead of cutting hard
                var gate = if (threshold <= 0) 1.0 else ((abs(dy) - threshold) / 4 + 0.5).coerceIn(0.0, 1.0)
                if (mode == 2) {
                    // clarity: strongest in the midtones, none in the deepest shadows and brightest lights
                    val t = y[i] / 255.0 * 2 - 1
                    gate *= 1 - t * t
                }
                val k = amount * gate
                var dr: Double
                var dg: Double
                var db: Double
                if (lumaOnly) {
                    dr = dy; dg = dy; db = dy
                } else {
                    dr = (r[i] - blurR!![i]).toDouble()
                    dg = (g[i] - blurG!![i]).toDouble()
                    db = (b[i] - blurB!![i]).toDouble()
                }
                var nr = r[i] + k * dr
                var ng = g[i] + k * dg
                var nb = b[i] + k * db
                var wrap = false

                if (over > 0) {
                    // how hard this pixel is being sharpened: 0 on flat areas, 1 on strong edges
                    val edge = min(1.0, abs(dy * k) / 40)
                    val push = over * edge
                    when (overType) {
                        0 -> {
                            val blow = sign(dy) * push * push * 600
                            nr += blow; ng += blow; nb += blow
                        }
                        1 -> {
                            val mean = (nr + ng + nb) / 3
                            val boost = 1 + push * 14
                            nr = mean + (nr - mean) * boost
                            ng = mean + (ng - mean) * boost
                            nb = mean + (nb - mean) * boost
                        }
                        2 -> {
                            // only strong edges get the huge gain, so noise on flat areas doesn't overflow
                            val gain = 1 + push * edge * 12
                            nr = r[i] + k * dr * gain
                            ng = g[i] + k * dg * gain
                            nb = b[i] + k * db * gain
                            wrap = true
                        }
                        else -> {
                            val split = dy * k * push * 8
                            nr += split
                            ng -= split * 0.35
                            nb -= split
                        }
                    }
                }
                fun fit(c: Double) = if (wrap) Math.floorMod(c.roundToInt(), 256) else c.roundToInt().coerceIn(0, 255)
                out.data[i] = argb(src.data[i] ushr 24, fit(nr), fit(ng), fit(nb))
            }
        }
        return out
    }
}
