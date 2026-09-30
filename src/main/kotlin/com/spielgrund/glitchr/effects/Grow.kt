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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Propagation growth, after the point based growth solver by Entagma: every pixel has an
 * infection value 0..1; the start mask is fully infected. In every step each pixel takes
 * the average infection of its neighbours within a radius (weighted by distance and,
 * optionally, by a direction) times its susceptibility – lowered by a random immunity per
 * pixel, by noise and by the picture (brightness, saturation … speed it up or slow it
 * down). The infection builds up step by step with the share of infected neighbours; for
 * branching growth crowded pixels are held back so only the tips grow on. At 1 the pixel
 * is infected and passes it on. The front thus creeps outwards in
 * organic, crystalline shapes, round or branching.
 *
 * Every pixel remembers when it was infected, by whom and from which start pixel its
 * lineage comes. Shown are the picture smudged along the growth (the growth pulls the
 * whole picture in its direction, spread softly over the canvas like a smudge brush),
 * the picture stretched onto the new area (every grown pixel is
 * displaced back towards its start pixel, smoothed, so the start mask's picture is drawn
 * out along the growth paths – or, with less stretch, just pushed along), the
 * transported pixels (each takes the content of its infector), the growth time through
 * a color ramp (optionally as rings), or the picture revealed along the front.
 */
object Grow : Effect("grow", "Grow", "Ausbreitung wie eine Infektion: die Startpixel wachsen organisch und kristallin ins Bild hinein") {
    private val contents = listOf("Verschmieren", "Bild aufdehnen", "Transportierte Pixel", "Wachstumszeit als Verlauf", "Bild enthüllen")
    private const val SMUDGE = 0
    private const val STRETCH = 1
    private const val TRANSPORT = 2
    private const val TIME = 3

    override val params = listOf(
        Param.Heading("startHeading", "Startmaske"),
        Param.Choice("source", "Wert", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Untere Schwelle", 0, 255, 140, tip = "Beim Farbton darf sie über der oberen liegen (Bereich über Rot hinweg)"),
        Param.Slider("upper", "Obere Schwelle", 0, 255, 255),
        Param.Toggle("invert", "Bereich umkehren", false),
        Param.Toggle("showMask", "Startmaske zeigen", false, "Zeigt schwarzweiss, wo die Ausbreitung beginnt"),
        Param.Heading("spreadHeading", "Ausbreitung"),
        Param.Slider("steps", "Schritte", 1, 1000, 200, tip = "Wie lange es wächst"),
        Param.Slider("radius", "Radius", 1, 8, 2, " px", "Wie weit ein Pixel seine Nachbarn spürt – grösser: runder und schneller"),
        Param.Slider("speed", "Tempo", 1, 100, 50, " %", "Wie schnell sich die Ansteckung in einem Pixel ansammelt"),
        Param.Slider(
            "form", "Form", -100, 100, 0, " %",
            "Negativ: rund, Buchten füllen sich zuerst. Positiv: verästelt – wo schon viel angesteckt ist, wird gebremst, nur die Spitzen wachsen weiter",
        ),
        Param.Slider("immunity", "Immunität", 0, 100, 40, " %", "Zufällige Widerstandskraft je Pixel – macht die Front zerklüftet"),
        Param.Slider("noise", "Noise", 0, 200, 60, " %", "Ein Noise-Feld macht Gebiete leichter oder schwerer ansteckbar"),
        Param.Slider("noiseSize", "Noise-Grösse", 2, 500, 40, " px"),
        Param.Choice("imageValue", "Bild wirkt über", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("imageInfluence", "Bildeinfluss", -200, 200, 0, " %", "Positiv: hohe Werte (z. B. helle Stellen) stecken sich leichter an, negativ: schwerer"),
        Param.Slider("direction", "Richtung", 0, 359, 0, "°", "In diese Richtung breitet es sich bevorzugt aus"),
        Param.Slider("directionStrength", "Richtungsstärke", 0, 100, 0, " %"),
        Param.Heading("showHeading", "Darstellung"),
        Param.Choice(
            "content", "Inhalt", contents,
            tip = "Verschmieren: wie mit dem Wischfinger zieht das Wachstum das ganze Bild in seine Richtung, weich auslaufend. " +
                "Bild aufdehnen: das Bild der Startmaske wird entlang der Wachstumswege auf die neue Fläche gezogen. " +
                "Transportierte Pixel: jeder angesteckte Pixel übernimmt den Inhalt dessen, der ihn angesteckt hat",
        ),
        Param.Slider("push", "Verschiebung", 0, 400, 200, " %", "Verschmieren: wie weit das Bild in Wachstumsrichtung gezogen wird"),
        Param.Slider("reach", "Reichweite", 4, 1000, 120, " px", "Verschmieren: wie weit um das Wachstum herum das Bild mitgezogen wird – weich auslaufend", canvasMax = true),
        Param.Slider("smear", "Wischspuren", 0, 100, 20, " %", "Verschmieren: Spuren entlang der Zugrichtung; 0 % bleibt ganz scharf"),
        Param.Slider(
            "stretch", "Dehnung", 0, 100, 100, " %",
            "Bild aufdehnen: 100 % zieht das Bild der Startmaske bis an die Front, weniger verschiebt es nur ein Stück in Wachstumsrichtung",
        ),
        Param.Slider(
            "smooth", "Glätte", 0, 300, 30, " px",
            "Bild aufdehnen: wie weich die Verschiebung ist – wenig: kristalline Facetten entlang der Wachstumswege, viel: fliessend wie Gummi",
        ),
        Param.Slider(
            "texture", "Textur", 0, 100, 0, " %",
            "Transportierte Pixel: 0 % zieht die Startpixel zu Kristallschlieren, mehr lässt ihre Textur mitwandern (innerhalb der Startmaske)",
        ),
        Param.Ramp("ramp", "Verlauf", RampPalette.THERMAL.ramp.format(), "Wachstumszeit: von den Startpixeln (links) bis zur Front (rechts)"),
        Param.Slider("rings", "Ringe", 1, 50, 1, tip = "Wachstumszeit: der Verlauf läuft so oft durch – Wachstumsringe"),
        Param.Choice("background", "Nicht gewachsen", listOf("Bild", "Schwarz", "Transparent")),
        Param.Slider("amount", "Stärke", 0, 100, 100, " %"),
        Param.Choice(
            "precision", "Rechengenauigkeit", listOf("1 px (voll)", "2 px", "4 px"), 1,
            "Gröber ist schneller, wächst in gleich vielen Schritten weiter und gibt dickere Äste",
        ),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mode = v["source"]
        val lower = v["lower"]
        val upper = v["upper"]
        val invert = v.bool("invert")
        fun chosen(c: Int) = alpha(c) > 0 && thresholdSelects(c, mode, lower, upper, invert)
        if (v.bool("showMask")) {
            return Pixels(src.width, src.height, IntArray(src.data.size) { if (chosen(src.data[it])) -1 else 0xFF000000.toInt() })
        }
        val n = 1 shl v["precision"]
        val small = reduce(src, n)
        val start = BooleanArray(small.data.size) { chosen(small.data[it]) }
        if (start.none { it }) return src
        val growth = spread(small, start, v, seed, n)
        return compose(src, growth, n, v)
    }

    /**
     * The result of the spreading on the reduced grid: when each cell was infected (-1 =
     * never) and where its content comes from (full picture pixels).
     */
    private class Growth(val w: Int, val h: Int) {
        val time = IntArray(w * h) { -1 }
        val ox = FloatArray(w * h)
        val oy = FloatArray(w * h)

        /** The start pixel each cell's lineage goes back to (full picture pixels). */
        val rx = FloatArray(w * h)
        val ry = FloatArray(w * h)
    }

    /**
     * The solver. Every not yet infected cell collects infection from its infected
     * neighbours (their weighted share, shaped by "Form", times its susceptibility); at 1
     * it is infected. Each step reads the previous one only, so it runs in
     * parallel and stays reproducible; only the box around the infection is visited.
     */
    private fun spread(small: Pixels, start: BooleanArray, v: Values, seed: Long, n: Int): Growth {
        val w = small.width
        val h = small.height
        val size = w * h
        val steps = v["steps"]
        val radius = max(1, (v["radius"].toDouble() / n).roundToInt())
        val speed = v["speed"] / 100f
        // round: the share of infected neighbours raised to a power favours bays;
        // branching: crowded cells (many infected neighbours) are held back, so only tips grow on
        val form = v["form"] / 100.0
        val bayPower = if (form < 0) 1 - form else 1.0
        val crowding = if (form > 0) 14 * form else 0.0
        // scaled so the most favoured share grows at full speed (a tip when branching)
        val peakShare = if (crowding > 0) bayPower / (bayPower + crowding) else 1.0
        val peakRate = peakShare.pow(bayPower) * (1 - peakShare).pow(crowding)
        val immunity = v["immunity"] / 100.0
        val noiseAmount = v["noise"] / 100.0
        val noiseSize = max(1.0, v["noiseSize"].toDouble() / n)
        val imageValue = v["imageValue"]
        val imageInfluence = v["imageInfluence"] / 100.0
        val dirStrength = v["directionStrength"] / 100.0
        val dirAngle = Math.toRadians(v["direction"].toDouble())
        val dirX = cos(dirAngle)
        val dirY = sin(dirAngle)
        val texture = v["texture"] / 100.0
        val noise = Noise(seed)

        // susceptibility per cell: random immunity, noise field, picture value
        val susceptible = FloatArray(size)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var s = 1.0 - immunity * hash01(i, seed)
                if (noiseAmount > 0) s *= (1 + noiseAmount * noise.fbm((x + 0.5) / noiseSize, (y + 0.5) / noiseSize, 4)).coerceAtLeast(0.0)
                if (imageInfluence != 0.0) {
                    val value = thresholdValue(small.data[i], imageValue).coerceAtLeast(0) / 255.0
                    s *= (1 + imageInfluence * (value - 0.5) * 2).coerceAtLeast(0.0)
                }
                susceptible[i] = s.toFloat()
            }
        }

        // neighbourhood: offsets with their distance weight (and direction weight)
        val offX = ArrayList<Int>()
        val offY = ArrayList<Int>()
        val weights = ArrayList<Float>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx == 0 && dy == 0) continue
            val d = hypot(dx.toDouble(), dy.toDouble())
            if (d > radius + 0.5) continue
            // the neighbour at (dx, dy) passes the infection to us in direction (-dx, -dy)
            val along = (-dx * dirX - dy * dirY) / d
            val weight = (1 - d / (radius + 1)) * (1 + dirStrength * along).coerceAtLeast(0.05)
            offX += dx
            offY += dy
            weights += weight.toFloat()
        }
        val ow = weights.toFloatArray()

        val growth = Growth(w, h)
        // infection 0..1 per cell; the start mask is fully infected
        var infection = FloatArray(size) { if (start[it]) 1f else 0f }
        for (i in 0 until size) if (start[i]) {
            growth.time[i] = 0
            growth.ox[i] = ((i % w + 0.5) * n).toFloat()
            growth.oy[i] = ((i / w + 0.5) * n).toFloat()
            growth.rx[i] = growth.ox[i]
            growth.ry[i] = growth.oy[i]
        }
        // only the area around what is (partly) infected needs to be looked at
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in 0 until size) if (start[i]) {
            x0 = min(x0, i % w); x1 = max(x1, i % w); y0 = min(y0, i / w); y1 = max(y1, i / w)
        }
        for (step in 1..steps) {
            x0 = max(0, x0 - radius); y0 = max(0, y0 - radius); x1 = min(w - 1, x1 + radius); y1 = min(h - 1, y1 + radius)
            val before = infection
            val after = before.copyOf()
            val ox = growth.ox.copyOf()
            val oy = growth.oy.copyOf()
            val rx = growth.rx.copyOf()
            val ry = growth.ry.copyOf()
            val bx0 = x0; val bx1 = x1; val by0 = y0
            parallelRows(y1 - y0 + 1) { row ->
                val y = by0 + row
                for (x in bx0..bx1) {
                    val i = y * w + x
                    if (before[i] >= 1f) continue
                    // the share of infected neighbours, weighted by distance (and direction)
                    var sum = 0f
                    var total = 0f
                    var best = -1
                    var bestScore = 0f
                    for (k in ow.indices) {
                        val nx = x + offX[k]
                        val ny = y + offY[k]
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val j = ny * w + nx
                        val weight = ow[k]
                        total += weight
                        if (before[j] >= 1f) {
                            sum += weight
                            // who passes it on: the strongest infected neighbour, ties broken by a fixed hash
                            val score = weight * (0.75f + 0.5f * hash01(j * 31 + step, seed).toFloat())
                            if (score > bestScore) { bestScore = score; best = j }
                        }
                    }
                    if (best < 0 || total <= 0f) continue
                    // the infection builds up with the share of infected neighbours – held back where it
                    // is crowded when branching, so tips grow and the gaps between the branches stay
                    val share = (sum / total).toDouble()
                    val rate = share.pow(bayPower) * (1 - share).pow(crowding) / peakRate
                    after[i] = before[i] + speed * rate.toFloat() * susceptible[i]
                    if (after[i] < 1f) continue
                    after[i] = 1f
                    growth.time[i] = step
                    // the content: the contributor's, moved along by the texture share – but only
                    // while it stays on the start mask, so the start pixels travel and not their surroundings
                    var qx = ox[best] + ((i % w) - (best % w)) * n * texture.toFloat()
                    var qy = oy[best] + ((i / w) - (best / w)) * n * texture.toFloat()
                    if (texture > 0) {
                        val cell = (qy / n).toInt().coerceIn(0, h - 1) * w + (qx / n).toInt().coerceIn(0, w - 1)
                        if (!start[cell]) { qx = ox[best]; qy = oy[best] }
                    }
                    growth.ox[i] = qx
                    growth.oy[i] = qy
                    growth.rx[i] = rx[best]
                    growth.ry[i] = ry[best]
                }
            }
            infection = after
        }
        return growth
    }

    /** A fixed pseudo-random number 0..1 per [i] and [seed]. */
    private fun hash01(i: Int, seed: Long): Double {
        var x = i.toLong() * -0x61c8864680b583ebL + seed
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** The picture averaged over n×n blocks. */
    private fun reduce(src: Pixels, n: Int): Pixels {
        if (n == 1) return src
        val sw = (src.width + n - 1) / n
        val sh = (src.height + n - 1) / n
        val out = Pixels(sw, sh)
        parallelRows(sh) { y ->
            for (x in 0 until sw) {
                var a = 0; var r = 0; var g = 0; var b = 0; var count = 0
                for (yy in y * n until min(src.height, (y + 1) * n)) for (xx in x * n until min(src.width, (x + 1) * n)) {
                    val c = src.data[yy * src.width + xx]
                    a += alpha(c); r += red(c); g += green(c); b += blue(c); count++
                }
                out.data[y * sw + x] = argb(a / count, r / count, g / count, b / count)
            }
        }
        return out
    }

    /**
     * Smudging: the picture at ([x], [y]) is fetched from against the pull (displacement
     * field times [push]); with [smear], several points along the way are averaged into a
     * streak, weighted towards the far end so the smudged content stays in front.
     */
    private fun smudged(src: Pixels, field: Field, x: Int, y: Int, n: Int, push: Double, smear: Double, amount: Float): Int {
        val base = src.data[y * src.width + x]
        val gx = ((x + 0.5) / n - 0.5).coerceIn(0.0, field.w - 1.0)
        val gy = ((y + 0.5) / n - 0.5).coerceIn(0.0, field.h - 1.0)
        val (dx, dy) = field.at(gx, gy)
        val mx = dx * push
        val my = dy * push
        if (mx * mx + my * my < 0.01) return base
        val c = if (smear <= 0) sampleBilinear(src, x + 0.5 - mx, y + 0.5 - my, Edge.CLAMP)
        else {
            // samples from the full pull back towards the pixel itself, the far ones counting most
            val samples = 8
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
            for (k in 0 until samples) {
                val t = 1 - smear * k / (samples - 1)
                val weight = 1.0 - 0.7 * k / (samples - 1)
                val s = sampleBilinear(src, x + 0.5 - mx * t, y + 0.5 - my * t, Edge.CLAMP)
                a += alpha(s) * weight; r += red(s) * weight; g += green(s) * weight; b += blue(s) * weight; total += weight
            }
            argb((a / total).roundToInt(), (r / total).roundToInt(), (g / total).roundToInt(), (b / total).roundToInt())
        }
        if (amount >= 1f) return c
        fun mix(o: Int, m: Int) = (o + (m - o) * amount).roundToInt()
        return argb(mix(alpha(base), alpha(c)), mix(red(base), red(c)), mix(green(base), green(c)), mix(blue(base), blue(c)))
    }

    /**
     * The pull for smudging: every grown cell pulls in its growth direction as far as it
     * grew (from its start pixel); this is spread over the whole canvas by [reach] pixels
     * and fades out softly, so the motif and its surroundings are dragged along too, like a
     * smudge brush or liquify – not only the grown pixels.
     */
    private fun smudgeField(g: Growth, n: Int, reach: Int): Field {
        val size = g.w * g.h
        val dx = FloatArray(size)
        val dy = FloatArray(size)
        for (i in 0 until size) {
            if (g.time[i] <= 0) continue
            dx[i] = ((i % g.w + 0.5f) * n) - g.rx[i]
            dy[i] = ((i / g.w + 0.5f) * n) - g.ry[i]
        }
        val radius = max(1, (reach / 2.0 / n).roundToInt())
        // a plain blur (not divided by the coverage): strong where much grew, fading out around it
        val bx = blur(dx, g.w, g.h, radius)
        val by = blur(dy, g.w, g.h, radius)
        // the blur thins the pull out; bring the strongest back to the growth's own size
        var grownMax = 0f
        var blurredMax = 0f
        for (i in 0 until size) {
            grownMax = max(grownMax, dx[i] * dx[i] + dy[i] * dy[i])
            blurredMax = max(blurredMax, bx[i] * bx[i] + by[i] * by[i])
        }
        val gain = if (blurredMax > 1e-6f) kotlin.math.sqrt(grownMax / blurredMax) * 0.6f else 1f
        for (i in 0 until size) {
            bx[i] *= gain
            by[i] *= gain
        }
        return Field(g.w, g.h, bx, by)
    }

    /** A smooth vector field on the grid, read bilinearly. */
    private class Field(val w: Int, val h: Int, val dx: FloatArray, val dy: FloatArray) {
        fun at(gx: Double, gy: Double): Pair<Double, Double> {
            val x0 = gx.toInt().coerceIn(0, w - 1)
            val y0 = gy.toInt().coerceIn(0, h - 1)
            val x1 = min(w - 1, x0 + 1)
            val y1 = min(h - 1, y0 + 1)
            val fx = gx - x0
            val fy = gy - y0
            fun lerp(a: FloatArray): Double {
                val top = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
                val bottom = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
                return top * (1 - fy) + bottom * fy
            }
            return lerp(dx) to lerp(dy)
        }
    }

    /**
     * How far each grown cell lies from the start pixel it grew from (full picture pixels).
     * Its direction is smoothed over the grown area by [smooth] pixels (a blur of the field
     * divided by the blur of the grown area); its length stays the cell's own – smoothing
     * the length too would shorten it at the rim, and the rim would show the surroundings
     * instead of the start picture.
     */
    private fun displacement(g: Growth, n: Int, smooth: Int): Field {
        val size = g.w * g.h
        val weight = FloatArray(size)
        val dx = FloatArray(size)
        val dy = FloatArray(size)
        for (i in 0 until size) {
            if (g.time[i] < 0) continue
            weight[i] = 1f
            dx[i] = ((i % g.w + 0.5f) * n) - g.rx[i]
            dy[i] = ((i / g.w + 0.5f) * n) - g.ry[i]
        }
        val radius = (smooth / 2.0 / n).roundToInt()
        if (radius < 1) return Field(g.w, g.h, dx, dy)
        val bw = blur(weight, g.w, g.h, radius)
        val bx = blur(dx, g.w, g.h, radius)
        val by = blur(dy, g.w, g.h, radius)
        for (i in 0 until size) {
            if (bw[i] <= 1e-4f) continue
            val length = kotlin.math.sqrt(dx[i] * dx[i] + dy[i] * dy[i])
            val sx = bx[i] / bw[i]
            val sy = by[i] / bw[i]
            val smoothLength = kotlin.math.sqrt(sx * sx + sy * sy)
            if (smoothLength > 1e-4f) {
                dx[i] = sx / smoothLength * length
                dy[i] = sy / smoothLength * length
            }
        }
        return Field(g.w, g.h, dx, dy)
    }

    /** Box blur with radius [r], three passes (close to a Gaussian), edges repeated. */
    private fun blur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        var cur = a
        repeat(3) {
            val src = cur
            val tmp = FloatArray(a.size)
            val out = FloatArray(a.size)
            val norm = 1f / (2 * r + 1)
            parallelRows(h) { y ->
                val row = y * w
                var sum = 0f
                for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[row + x] = sum * norm
                    sum += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
                }
            }
            parallelRows(w) { x ->
                var sum = 0f
                for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = sum * norm
                    sum += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
                }
            }
            cur = out
        }
        return cur
    }

    /**
     * The grown area at full resolution: coverage smoothed between the grid cells; the
     * content from the nearest grown grid cell (transported pixels), the growth time
     * through the ramp, or the picture itself (revealed).
     */
    private fun compose(src: Pixels, g: Growth, n: Int, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val content = v["content"]
        val background = v["background"]
        val amount = v["amount"] / 100f
        val ramp = ColorRamp.parse(v.text("ramp").ifBlank { RampPalette.THERMAL.ramp.format() })
        val rings = v["rings"]
        val last = max(1, g.time.maxOrNull() ?: 1)
        val stretch = v["stretch"] / 100.0
        // stretching: every grown cell is displaced back towards the start pixel it grew from;
        // the displacement is smoothed over the grown area so the picture stretches instead of
        // breaking into the lineages
        val field = when (content) {
            STRETCH -> displacement(g, n, v["smooth"])
            SMUDGE -> smudgeField(g, n, v["reach"])
            else -> null
        }
        val push = v["push"] / 100.0
        val smear = v["smear"] / 100.0
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                if (content == SMUDGE) {
                    out.data[i] = smudged(src, field!!, x, y, n, push, smear, amount)
                    continue
                }
                val ground = when (background) {
                    1 -> 0xFF000000.toInt()
                    2 -> 0
                    else -> base
                }
                // coverage: bilinear over the grid cells, for smooth edges
                val gx = ((x + 0.5) / n - 0.5).coerceIn(0.0, g.w - 1.0)
                val gy = ((y + 0.5) / n - 0.5).coerceIn(0.0, g.h - 1.0)
                val x0 = gx.toInt()
                val y0 = gy.toInt()
                val x1 = min(g.w - 1, x0 + 1)
                val y1 = min(g.h - 1, y0 + 1)
                val fx = (gx - x0).toFloat()
                val fy = (gy - y0).toFloat()
                val cells = intArrayOf(y0 * g.w + x0, y0 * g.w + x1, y1 * g.w + x0, y1 * g.w + x1)
                val fs = floatArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy)
                var cover = 0f
                var from = -1
                var fromWeight = 0f
                for (k in 0..3) {
                    if (g.time[cells[k]] < 0) continue
                    cover += fs[k]
                    if (fs[k] > fromWeight) { fromWeight = fs[k]; from = cells[k] }
                }
                if (cover <= 0f || from < 0) {
                    out.data[i] = ground
                    continue
                }
                // the own cell's content if it grew, else the closest grown neighbour's
                val own = min(g.h - 1, y / n) * g.w + min(g.w - 1, x / n)
                if (g.time[own] >= 0) from = own
                val grownColor = when (content) {
                    STRETCH -> {
                        val (dx, dy) = field!!.at(gx, gy)
                        sampleBilinear(src, x + 0.5 - dx * stretch, y + 0.5 - dy * stretch, Edge.CLAMP)
                    }
                    TIME -> {
                        // growth time through the ramp, repeated as rings
                        val t = g.time[from].toDouble() / last * rings
                        val f = if (rings == 1) t.coerceIn(0.0, 1.0) else (t - floor(t)).let { if (it == 0.0 && t > 0) 1.0 else it }
                        ramp.lut[(f * 255).roundToInt().coerceIn(0, 255)] or 0xFF000000.toInt()
                    }
                    TRANSPORT -> sampleBilinear(src, g.ox[from].toDouble(), g.oy[from].toDouble(), Edge.CLAMP)
                    else -> base
                }
                val a = cover * amount
                fun mix(b: Int, c: Int) = (b + (c - b) * a).roundToInt()
                val alphaOut = max(alpha(ground), (alpha(grownColor) * a).roundToInt())
                out.data[i] = argb(
                    alphaOut,
                    mix(red(ground), red(grownColor)), mix(green(ground), green(grownColor)), mix(blue(ground), blue(grownColor)),
                )
            }
        }
        return out
    }
}
