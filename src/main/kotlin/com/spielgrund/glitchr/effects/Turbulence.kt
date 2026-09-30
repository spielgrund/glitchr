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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turbulence (marbling): the colors flow like paint on water or a soap film. The flow is
 * divergence-free curl noise (so it swirls and folds instead of piling up) that slowly
 * changes over the passes, plus an optional drift in one direction; it moves the whole
 * picture or only a threshold range (spread softly).
 *
 * Instead of pushing the picture around again and again (which would blur it), every pass
 * only traces back where each pixel's paint came from (semi-Lagrangian advection of the
 * coordinate map); the picture is read once at the end, so even very fine streaks stay
 * sharp. Where the flow squeezes the picture into streaks finer than a pixel, the reading
 * is supersampled (anti-aliasing). Optional thin-film colors run along the brightness
 * lines of the swirled picture.
 */
object Turbulence : Effect("turbulence", "Verwirbelung", "Farben fliessen wie auf Wasser oder einem Seifenfilm und falten sich zu feinen Schlieren") {
    override val params = listOf(
        Param.Heading("regionHeading", "Bereich"),
        Param.Choice("region", "Fliesst", listOf("Ganzes Bild", "Schwellenbereich"), tip = "Schwellenbereich: nur wo der Wert zwischen den Schwellen liegt, weich auslaufend"),
        Param.Choice("source", "Wert", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Untere Schwelle", 0, 255, 140, tip = "Beim Farbton darf sie über der oberen liegen (Bereich über Rot hinweg)"),
        Param.Slider("upper", "Obere Schwelle", 0, 255, 255),
        Param.Toggle("invert", "Bereich umkehren", false),
        Param.Slider("soft", "Weiche", 0, 1000, 80, " px", "Wie weit die Strömung über den Bereich hinaus ausläuft", canvasMax = true),
        Param.Toggle("showMask", "Bereich zeigen", false, "Zeigt schwarzweiss, wie stark es wo fliesst"),
        Param.Heading("flowHeading", "Strömung"),
        Param.Slider("swirl", "Wirbelstärke", 0, 200, 30, " px", "So weit fliesst die Farbe je Durchlauf", decimals = 1),
        Param.Slider("swirlSize", "Wirbelgrösse", 4, 1000, 120, " px", "Grösse der grössten Wirbel", canvasMax = true),
        Param.Slider("swirlDetail", "Feinheit", 1, 6, 4, tip = "Wie viele immer kleinere Wirbel dazukommen"),
        Param.Slider("swirlSteps", "Durchläufe", 1, 300, 80, tip = "Je mehr, desto feiner werden die Schlieren ausgezogen und gefaltet"),
        Param.Slider("swirlEvolve", "Wandel", 0, 100, 30, " %", "Wie schnell sich die Strömung ändert – 0 %: stehende Wirbel, mehr: verschlungene Falten"),
        Param.Slider("direction", "Richtung", 0, 359, 0, "°", "Die Farbe treibt zusätzlich in diese Richtung – lange, gezogene Bahnen"),
        Param.Slider("directionStrength", "Richtungsstärke", 0, 200, 0, " %"),
        Param.Heading("filmHeading", "Film"),
        Param.Slider("film", "Filmfarben", 0, 100, 25, " %", "Regenbogenschlieren wie ein Seifen- oder Ölfilm, entlang der Helligkeitslinien"),
        Param.Ramp("filmRamp", "Filmverlauf", Bubbles.SOAP_FILM, "Die Farben des Films, läuft mehrmals durch"),
        Param.Slider("filmBands", "Filmbänder", 1, 40, 8, tip = "Wie oft der Filmverlauf über die Helligkeit durchläuft"),
        Param.Heading("outHeading", "Ausgabe"),
        Param.Choice(
            "antialias", "Kantenglättung", listOf("Aus", "2 × 2", "4 × 4"), 2,
            "Wo die Strömung das Bild zu Schlieren feiner als ein Pixel zusammenzieht, wird mehrfach gelesen und gemittelt",
        ),
        Param.Slider("amount", "Stärke", 0, 100, 100, " %"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val region = regionWeight(src, v)
        if (v.bool("showMask")) {
            return Pixels(w, h, IntArray(w * h) { val g = (region?.get(it)?.times(255f) ?: 255f).roundToInt().coerceIn(0, 255); argb(255, g, g, g) })
        }
        if (region != null && region.all { it <= 0f }) return src
        val speed = v["swirl"] / 10.0
        val size = max(2.0, v["swirlSize"].toDouble())
        val octaves = v["swirlDetail"]
        val steps = v["swirlSteps"]
        val evolve = v["swirlEvolve"] / 100.0 * 0.03
        val angle = Math.toRadians(v["direction"].toDouble())
        val drift = v["directionStrength"] / 100.0
        val driftX = (cos(angle) * drift).toFloat()
        val driftY = (sin(angle) * drift).toFloat()
        val noise = Noise(seed * 13 + 5)

        // the flow lives on a coarser grid (spacing sp), read bilinearly
        val sp = (size / 8).roundToInt().coerceIn(2, 8)
        val fw = w / sp + 2
        val fh = h / sp + 2
        val weight = FloatArray(fw * fh) { k ->
            if (region == null) 1f
            else region[min(h - 1, (k / fw) * sp) * w + min(w - 1, (k % fw) * sp)]
        }
        val psi = FloatArray(fw * fh)
        val vx = FloatArray(fw * fh)
        val vy = FloatArray(fw * fh)
        var mapX = FloatArray(w * h) { (it % w) + 0.5f }
        var mapY = FloatArray(w * h) { (it / w) + 0.5f }
        var nextX = FloatArray(w * h)
        var nextY = FloatArray(w * h)
        val scale = (size / sp * 0.7).toFloat()
        for (step in 0 until steps) {
            val t = step * evolve
            parallelRows(fh) { gy ->
                for (gx in 0 until fw) psi[gy * fw + gx] = noise.fbm(gx * sp / size + t, gy * sp / size - t * 0.6, octaves).toFloat()
            }
            // velocity = curl of the stream function (divergence-free), about [speed] px per pass
            parallelRows(fh) { gy ->
                for (gx in 0 until fw) {
                    val k = gy * fw + gx
                    val wk = weight[k]
                    if (wk <= 0f) { vx[k] = 0f; vy[k] = 0f; continue }
                    val dpx = (psi[gy * fw + min(fw - 1, gx + 1)] - psi[gy * fw + max(0, gx - 1)]) / 2f
                    val dpy = (psi[min(fh - 1, gy + 1) * fw + gx] - psi[max(0, gy - 1) * fw + gx]) / 2f
                    vx[k] = (dpy * scale + driftX) * speed.toFloat() * wk
                    vy[k] = (-dpx * scale + driftY) * speed.toFloat() * wk
                }
            }
            val mx = mapX
            val my = mapY
            val ox = nextX
            val oy = nextY
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val px = x + 0.5
                    val py = y + 0.5
                    val ux = grid(vx, fw, fh, px / sp, py / sp)
                    val uy = grid(vy, fw, fh, px / sp, py / sp)
                    if (ux * ux + uy * uy < 1e-8) {
                        ox[i] = mx[i]; oy[i] = my[i]
                        continue
                    }
                    // the paint here came from upstream: take the coordinate map from there
                    ox[i] = grid(mx, w, h, px - ux - 0.5, py - uy - 0.5).toFloat()
                    oy[i] = grid(my, w, h, px - ux - 0.5, py - uy - 0.5).toFloat()
                }
            }
            mapX = ox; mapY = oy; nextX = mx; nextY = my
        }
        return read(src, mapX, mapY, region, v)
    }

    /** How strongly it flows per pixel (0..1), or null for the whole picture. */
    private fun regionWeight(src: Pixels, v: Values): FloatArray? {
        if (v["region"] == 0) return null
        val mode = v["source"]
        val lower = v["lower"]
        val upper = v["upper"]
        val invert = v.bool("invert")
        val mask = FloatArray(src.data.size) { val c = src.data[it]; if (alpha(c) > 0 && thresholdSelects(c, mode, lower, upper, invert)) 1f else 0f }
        val r = (v["soft"] / 2.0).roundToInt()
        if (r < 1) return mask
        val soft = Grow.blur(mask, src.width, src.height, r)
        return FloatArray(soft.size) { max(mask[it], min(1f, soft[it] * 2f)) }
    }

    /**
     * Reads the picture through the coordinate map, with film colors. Where neighbouring
     * pixels come from far apart (the flow squeezed the picture), several points between
     * them are read and averaged.
     */
    private fun read(src: Pixels, mapX: FloatArray, mapY: FloatArray, region: FloatArray?, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val amount = v["amount"] / 100.0
        val film = v["film"] / 100.0
        val filmBands = v["filmBands"].toDouble()
        val filmRamp = ColorRamp.parse(v.text("filmRamp").ifBlank { Bubbles.SOAP_FILM })
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }

        fun colorAt(sx: Double, sy: Double, weight: Double): Int {
            val c = sampleBilinear(src, sx, sy, Edge.CLAMP)
            if (film <= 0 || weight <= 0) return c
            // thin film: the ramp runs along the brightness lines, screened over the paint
            val luma = (0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)) / 255.0
            val f = luma * filmBands
            val fc = filmRamp.lut[((f - floor(f)) * 255).roundToInt().coerceIn(0, 255)]
            val a = film * weight
            fun screen(p: Int, q: Int) = (p + (255 - (255 - p) * (255 - q) / 255.0 - p) * a).roundToInt().coerceIn(0, 255)
            return argb(alpha(c), screen(red(c), red(fc)), screen(green(c), green(fc)), screen(blue(c), blue(fc)))
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val weight = region?.get(i)?.toDouble() ?: 1.0
                val right = if (x + 1 < w) i + 1 else i
                val down = if (y + 1 < h) i + w else i
                // how far apart the sources of this pixel and its neighbours lie
                val spread = max(
                    abs(mapX[right] - mapX[i]) + abs(mapY[right] - mapY[i]),
                    abs(mapX[down] - mapX[i]) + abs(mapY[down] - mapY[i]),
                )
                val c = if (sub == 1 || spread < 1.5f) colorAt(mapX[i].toDouble(), mapY[i].toDouble(), weight)
                else {
                    var a = 0; var r = 0; var g = 0; var b = 0
                    for (sy in 0 until sub) for (sx in 0 until sub) {
                        // the map between this pixel and the next ones, centred on the pixel
                        val fx = (sx + 0.5) / sub - 0.5
                        val fy = (sy + 0.5) / sub - 0.5
                        val qx = mapPoint(mapX, w, h, x + fx, y + fy)
                        val qy = mapPoint(mapY, w, h, x + fx, y + fy)
                        val s = colorAt(qx, qy, weight)
                        a += alpha(s); r += red(s); g += green(s); b += blue(s)
                    }
                    val count = sub * sub
                    argb((a + count / 2) / count, (r + count / 2) / count, (g + count / 2) / count, (b + count / 2) / count)
                }
                out.data[i] = if (amount >= 1.0) c else {
                    fun mix(p: Int, q: Int) = (p + (q - p) * amount).roundToInt()
                    argb(mix(alpha(base), alpha(c)), mix(red(base), red(c)), mix(green(base), green(c)), mix(blue(base), blue(c)))
                }
            }
        }
        return out
    }

    /** The coordinate map at a point between pixel centres (x, y in pixel index units). */
    private fun mapPoint(a: FloatArray, w: Int, h: Int, x: Double, y: Double) = grid(a, w, h, x, y)

    /** Bilinear read of a [w]×[h] grid at (gx, gy) in grid units, edges clamped. */
    private fun grid(a: FloatArray, w: Int, h: Int, gx: Double, gy: Double): Double {
        val cx = gx.coerceIn(0.0, w - 1.0)
        val cy = gy.coerceIn(0.0, h - 1.0)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
        val bottom = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
