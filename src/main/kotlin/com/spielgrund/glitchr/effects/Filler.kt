package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds areas in the picture and fills each one differently. Areas are connected pixels
 * that belong together: of about the same color (so every tile of a generated pattern is
 * one area), on the same brightness step, in the same hue band, inside a threshold range
 * (blobs), or between edges. Areas smaller than a minimum join their neighbours or stay as
 * they are. Each area is filled with a random grey, a random color, a random color from a
 * ramp, a ramp color by its size or brightness, or its own average color – replacing,
 * multiplying or only coloring the picture, optionally with outlines.
 */
object Filler : Effect("filler", "Flächen füllen", "Findet Flächen – Kacheln, Farbflächen, Helligkeitsstufen, Blobs – und füllt jede anders") {
    private val finders = listOf("Gleiche Farbe", "Helligkeitsstufen", "Farbton (HSL)", "Blobs (Schwelle)", "Kanten")
    private const val SAME = 0
    private const val LEVELS = 1
    private const val HUE = 2
    private const val BLOBS = 3
    private const val EDGES = 4

    private val fills = listOf("Zufällige Grauwerte", "Zufällige Farben", "Zufällig aus Verlauf", "Verlauf nach Grösse", "Verlauf nach Helligkeit", "Mittelwert der Fläche")
    private const val GREY = 0
    private const val COLOR = 1
    private const val RAMP = 2
    private const val BY_SIZE = 3
    private const val BY_LIGHT = 4
    private const val MEAN = 5

    private val cutouts = listOf("Keine", "Grösste Fläche", "Flächen am Bildrand", "Nach Farbe", "Hellste Fläche", "Dunkelste Fläche", "Zufällig")
    private const val CUT_LARGEST = 1
    private const val CUT_BORDER = 2
    private const val CUT_COLOR = 3
    private const val CUT_BRIGHTEST = 4
    private const val CUT_DARKEST = 5
    private const val CUT_RANDOM = 6

    /** Not an area: stays as it is. */
    private const val OUTSIDE = -1

    /** Too small, a wall or a stray pixel: joins a neighbouring area if asked. */
    private const val LOOSE = -2

    override val params = listOf(
        Param.Heading("findHeading", "Flächen finden"),
        Param.Choice(
            "finder", "Verfahren", finders,
            tip = "Gleiche Farbe: zusammenhängende Pixel mit ähnlicher Farbe – erkennt Kacheln und Farbflächen · Helligkeitsstufen: gleich hell nach Stufen · " +
                "Farbton: gleiches Farbband, Graues für sich · Blobs: zusammenhängende Stücke im Schwellenbereich · Kanten: was zwischen den Umrissen liegt",
        ),
        Param.Slider("tolerance", "Toleranz", 0, 255, 16, tip = "Gleiche Farbe: so viel darf ein Kanal abweichen"),
        Param.Choice(
            "compare", "Vergleichen mit", listOf("Nachbarpixel", "Startfarbe"),
            tip = "Gleiche Farbe – Nachbarpixel: Verläufe innerhalb einer Kachel bleiben eine Fläche, nur Sprünge trennen · " +
                "Startfarbe: jeder Pixel muss der ersten Farbe der Fläche ähneln (bei Fotos weniger Auslaufen)",
        ),
        Param.Slider("levels", "Stufen", 2, 64, 6, tip = "Helligkeitsstufen und Farbton: in so viele Bänder wird geteilt"),
        Param.Toggle("hueLight", "Farbton auch nach Helligkeit", false, "Farbton: hell und dunkel desselben Farbtons werden getrennt"),
        Param.Choice("source", "Blobs: Wert", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Blobs: untere Schwelle", 0, 255, 128),
        Param.Slider("upper", "Blobs: obere Schwelle", 0, 255, 255),
        Param.Toggle("invert", "Blobs: umkehren", false),
        Param.Slider("edge", "Kantenschwelle", 1, 255, 40, tip = "Kanten: ab diesem Helligkeitssprung ist eine Grenze"),
        Param.Toggle("diagonal", "Diagonal verbunden", false, "Auch schräg benachbarte Pixel gehören zur selben Fläche"),
        Param.Slider("minArea", "Min. Fläche", 1, 100000, 20, " px", "Kleinere Flächen gelten nicht als eigene Fläche"),
        Param.Choice(
            "small", "Kleine Flächen", listOf("Unverändert", "Zur Nachbarfläche"), 1,
            "Was mit zu kleinen Flächen (und Kantenpixeln) geschieht",
        ),
        Param.Heading("fillHeading", "Füllen"),
        Param.Choice("fill", "Füllung", fills, RAMP),
        Param.Ramp("ramp", "Verlauf", RampPalette.RAINBOW.ramp.format(), "Für die Füllungen aus dem Verlauf"),
        Param.Slider("saturation", "Sättigung", 0, 100, 70, " %", "Zufällige Farben: wie bunt"),
        Param.Slider("lightFrom", "Helligkeit von", 0, 100, 20, " %", "Zufällige Grauwerte und Farben: dunkelste Füllung"),
        Param.Slider("lightTo", "Helligkeit bis", 0, 100, 85, " %", "Zufällige Grauwerte und Farben: hellste Füllung"),
        Param.Choice(
            "blend", "Mischen", listOf("Ersetzen", "Multiplizieren", "Nur Farbe"),
            tip = "Nur Farbe: die Fläche bekommt die Farbe, die Helligkeit des Bilds bleibt",
        ),
        Param.Toggle("outline", "Umrisse zeichnen", false, "Schwarze Linien zwischen den Flächen"),
        Param.Heading("cutHeading", "Freistellen"),
        Param.Choice(
            "cutout", "Transparent", cutouts,
            tip = "Welche Flächen durchsichtig werden – z. B. der Hintergrund: meist die grösste Fläche oder was am Bildrand liegt",
        ),
        Param.Color("cutColor", "Farbe", 0xFFFFFF, "Nach Farbe: Flächen, deren Mittelwert dieser Farbe ähnelt"),
        Param.Slider("cutTolerance", "Farbtoleranz", 0, 255, 40, tip = "Nach Farbe: so viel darf ein Kanal abweichen"),
        Param.Slider("cutShare", "Anteil", 0, 100, 30, " %", "Zufällig: so viele Flächen werden durchsichtig"),
        Param.Toggle("cutInvert", "Auswahl umkehren", false, "Nur die gewählten Flächen bleiben, alles andere wird durchsichtig"),
        Param.Heading("outHeading", "Ausgabe"),
        Param.Choice(
            "antialias", "Kantenglättung", listOf("Aus", "2 × 2", "4 × 4"), 2,
            "An den Grenzen zwischen den Flächen – auch an freigestellten Rändern – wird mehrfach abgetastet und gemittelt",
        ),
        Param.Slider("amount", "Stärke", 0, 100, 100, " %"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val labels = findAreas(src, v)
        var count = 0
        for (l in labels) if (l >= count) count = l + 1
        // area sizes, then the too small ones are let loose
        val size = IntArray(count)
        for (l in labels) if (l >= 0) size[l]++
        val minArea = v["minArea"]
        for (i in labels.indices) if (labels[i] >= 0 && size[labels[i]] < minArea) labels[i] = LOOSE
        if (v["small"] == 1) absorbLoose(labels, w, h)
        // renumber what is left, gather size and average color
        val remap = IntArray(count) { -1 }
        var areas = 0
        for (i in labels.indices) {
            val l = labels[i]
            if (l < 0) continue
            if (remap[l] < 0) remap[l] = areas++
            labels[i] = remap[l]
        }
        if (areas == 0) return src
        val area = IntArray(areas)
        val sumR = LongArray(areas)
        val sumG = LongArray(areas)
        val sumB = LongArray(areas)
        for (i in labels.indices) {
            val l = labels[i]
            if (l < 0) continue
            val c = src.data[i]
            area[l]++
            sumR[l] += red(c).toLong(); sumG[l] += green(c).toLong(); sumB[l] += blue(c).toLong()
        }
        val fills = fillColors(area, sumR, sumG, sumB, v, seed)
        val clear = cutout(labels, area, sumR, sumG, sumB, w, h, v, seed)
        val means = IntArray(areas) { k -> argb(255, (sumR[k] / area[k]).toInt(), (sumG[k] / area[k]).toInt(), (sumB[k] / area[k]).toInt()) }
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        return paint(src, labels, fills, clear, means, sub, v)
    }

    /** Which areas become transparent. */
    private fun cutout(labels: IntArray, area: IntArray, sumR: LongArray, sumG: LongArray, sumB: LongArray, w: Int, h: Int, v: Values, seed: Long): BooleanArray {
        val n = area.size
        val chosen = BooleanArray(n)
        fun light(k: Int) = (0.299 * sumR[k] + 0.587 * sumG[k] + 0.114 * sumB[k]) / area[k]
        when (v["cutout"]) {
            CUT_LARGEST -> chosen[area.indices.maxBy { area[it] }] = true
            CUT_BORDER -> {
                for (x in 0 until w) {
                    labels[x].let { if (it >= 0) chosen[it] = true }
                    labels[(h - 1) * w + x].let { if (it >= 0) chosen[it] = true }
                }
                for (y in 0 until h) {
                    labels[y * w].let { if (it >= 0) chosen[it] = true }
                    labels[y * w + w - 1].let { if (it >= 0) chosen[it] = true }
                }
            }
            CUT_COLOR -> {
                val c = v["cutColor"]
                val tol = v["cutTolerance"]
                for (k in 0 until n) {
                    chosen[k] = abs((sumR[k] / area[k]).toInt() - (c shr 16 and 0xFF)) <= tol &&
                        abs((sumG[k] / area[k]).toInt() - (c shr 8 and 0xFF)) <= tol &&
                        abs((sumB[k] / area[k]).toInt() - (c and 0xFF)) <= tol
                }
            }
            CUT_BRIGHTEST -> chosen[area.indices.maxBy { light(it) }] = true
            CUT_DARKEST -> chosen[area.indices.minBy { light(it) }] = true
            CUT_RANDOM -> {
                val share = v["cutShare"] / 100.0
                for (k in 0 until n) chosen[k] = hash01(k, 3, seed) < share
            }
            else -> return chosen
        }
        if (v.bool("cutInvert")) for (k in 0 until n) chosen[k] = !chosen[k]
        return chosen
    }

    /** One label per pixel: an area number, [OUTSIDE] or [LOOSE]. */
    private fun findAreas(src: Pixels, v: Values): IntArray {
        val w = src.width
        val h = src.height
        val n = w * h
        val diagonal = v.bool("diagonal")
        val labels = IntArray(n) { OUTSIDE }
        when (val finder = v["finder"]) {
            SAME -> {
                // region growing: a pixel joins while it stays close to its neighbour in the area (or to the area's first color)
                val tol = v["tolerance"]
                val chain = v["compare"] == 0
                var next = 0
                val queue = IntArray(n)
                for (start in 0 until n) {
                    if (labels[start] != OUTSIDE) continue
                    val seedColor = src.data[start]
                    labels[start] = next
                    var head = 0
                    var tail = 0
                    queue[tail++] = start
                    while (head < tail) {
                        val i = queue[head++]
                        forNeighbours(i, w, h, diagonal) { j ->
                            if (labels[j] == OUTSIDE && close(src.data[j], if (chain) src.data[i] else seedColor, tol)) {
                                labels[j] = next
                                queue[tail++] = j
                            }
                        }
                    }
                    next++
                }
            }
            else -> {
                // a key per pixel (Int.MIN_VALUE: not part of any area); areas are connected pixels of equal key
                val keys = IntArray(n)
                val levels = v["levels"]
                when (finder) {
                    LEVELS -> for (i in 0 until n) keys[i] = min(levels - 1, (luma(src.data[i]) * levels / 256.0).toInt())
                    HUE -> {
                        val light = v.bool("hueLight")
                        for (i in 0 until n) keys[i] = hueKey(src.data[i], levels, light)
                    }
                    BLOBS -> {
                        val mode = v["source"]
                        val lower = v["lower"]
                        val upper = v["upper"]
                        val invert = v.bool("invert")
                        for (i in 0 until n) {
                            val c = src.data[i]
                            keys[i] = if (alpha(c) > 0 && thresholdSelects(c, mode, lower, upper, invert)) 1 else Int.MIN_VALUE
                        }
                    }
                    EDGES -> {
                        // a brightness jump (Sobel) makes a wall
                        val limit = v["edge"].toDouble()
                        val l = DoubleArray(n) { luma(src.data[it]) }
                        for (y in 0 until h) for (x in 0 until w) {
                            fun at(xx: Int, yy: Int) = l[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
                            val gx = at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)
                            val gy = at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)
                            keys[y * w + x] = if (hypot(gx, gy) / 4 > limit) Int.MIN_VALUE + 1 else 0
                        }
                    }
                }
                var next = 0
                val queue = IntArray(n)
                for (start in 0 until n) {
                    if (labels[start] != OUTSIDE) continue
                    val key = keys[start]
                    if (key == Int.MIN_VALUE) continue
                    if (key == Int.MIN_VALUE + 1) { labels[start] = LOOSE; continue }
                    labels[start] = next
                    var head = 0
                    var tail = 0
                    queue[tail++] = start
                    while (head < tail) {
                        val i = queue[head++]
                        forNeighbours(i, w, h, diagonal) { j ->
                            if (labels[j] == OUTSIDE && keys[j] == key) {
                                labels[j] = next
                                queue[tail++] = j
                            }
                        }
                    }
                    next++
                }
            }
        }
        return labels
    }

    private inline fun forNeighbours(i: Int, w: Int, h: Int, diagonal: Boolean, f: (Int) -> Unit) {
        val x = i % w
        val y = i / w
        if (x > 0) f(i - 1)
        if (x < w - 1) f(i + 1)
        if (y > 0) f(i - w)
        if (y < h - 1) f(i + w)
        if (diagonal) {
            if (x > 0 && y > 0) f(i - w - 1)
            if (x < w - 1 && y > 0) f(i - w + 1)
            if (x > 0 && y < h - 1) f(i + w - 1)
            if (x < w - 1 && y < h - 1) f(i + w + 1)
        }
    }

    private fun close(a: Int, b: Int, tol: Int) =
        abs(red(a) - red(b)) <= tol && abs(green(a) - green(b)) <= tol && abs(blue(a) - blue(b)) <= tol && abs(alpha(a) - alpha(b)) <= tol

    private fun luma(c: Int) = 0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)

    /** The hue band; greys (little saturation) form their own bands by lightness. */
    private fun hueKey(c: Int, levels: Int, light: Boolean): Int {
        val r = red(c) / 255.0
        val g = green(c) / 255.0
        val b = blue(c) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        val s = if (d < 1e-9) 0.0 else d / (1 - abs(2 * l - 1))
        if (s < 0.15) return 100_000 + min(levels - 1, (l * levels).toInt())
        var hue = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6
        if (hue < 0) hue += 1
        val band = min(levels - 1, (hue * levels).toInt())
        return if (light) band * 10 + min(2, (l * 3).toInt()) else band
    }

    /** Loose pixels take the label of the nearest area (grown out from all areas at once). */
    private fun absorbLoose(labels: IntArray, w: Int, h: Int) {
        val queue = IntArray(labels.size)
        var tail = 0
        for (i in labels.indices) if (labels[i] >= 0) queue[tail++] = i
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            forNeighbours(i, w, h, false) { j ->
                if (labels[j] == LOOSE) {
                    labels[j] = labels[i]
                    queue[tail++] = j
                }
            }
        }
    }

    /** The fill color of every area. */
    private fun fillColors(area: IntArray, sumR: LongArray, sumG: LongArray, sumB: LongArray, v: Values, seed: Long): IntArray {
        val n = area.size
        val ramp = ColorRamp.parse(v.text("ramp").ifBlank { RampPalette.RAINBOW.ramp.format() })
        val sat = v["saturation"] / 100.0
        val lo = v["lightFrom"] / 100.0
        val hi = v["lightTo"] / 100.0
        // for the ramp by size: the rank of every area
        val rank = IntArray(n)
        if (v["fill"] == BY_SIZE) {
            val order = (0 until n).sortedBy { area[it] }
            for ((r, k) in order.withIndex()) rank[k] = r
        }
        return IntArray(n) { k ->
            val r1 = hash01(k, 1, seed)
            val r2 = hash01(k, 2, seed)
            when (v["fill"]) {
                GREY -> (255 * (lo + r1 * (hi - lo))).roundToInt().coerceIn(0, 255).let { argb(255, it, it, it) }
                COLOR -> hsl(r1, sat, lo + r2 * (hi - lo))
                RAMP -> ramp.lut[(r1 * 255).roundToInt()] or 0xFF000000.toInt()
                BY_SIZE -> ramp.lut[if (n > 1) rank[k] * 255 / (n - 1) else 128] or 0xFF000000.toInt()
                BY_LIGHT -> {
                    val l = (0.299 * sumR[k] + 0.587 * sumG[k] + 0.114 * sumB[k]) / area[k]
                    ramp.lut[l.roundToInt().coerceIn(0, 255)] or 0xFF000000.toInt()
                }
                else -> argb(255, (sumR[k] / area[k]).toInt(), (sumG[k] / area[k]).toInt(), (sumB[k] / area[k]).toInt())
            }
        }
    }

    private fun hsl(h: Double, s: Double, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = h * 6
        val x = c * (1 - abs(hp % 2 - 1))
        val (r, g, b) = when (floor(hp).toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return argb(255, ch(r), ch(g), ch(b))
    }

    /** A fixed pseudo-random number 0..1 per area, channel and seed. */
    private fun hash01(i: Int, k: Int, seed: Long): Double {
        var x = i.toLong() * -0x61c8864680b583ebL + k * 0x5851F42D4C957F2DL + seed
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** The picture averaged over 3 × 3 pixels. */
    private fun softened(src: Pixels): Pixels {
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        com.spielgrund.glitchr.image.parallelRows(h) { y ->
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val c = src.data[yy * w + xx]
                    a += alpha(c); r += red(c); g += green(c); b += blue(c); n++
                }
                out.data[y * w + x] = argb(a / n, r / n, g / n, b / n)
            }
        }
        return out
    }

    /** The color of area [l] over the picture's color [base] (fill, blend, strength; transparent if cut out). */
    private fun shade(l: Int, base: Int, fills: IntArray, clear: BooleanArray, blend: Int, amount: Double): Int {
        if (clear[l]) return 0
        val f = fills[l]
        val r: Int
        val g: Int
        val b: Int
        when (blend) {
            1 -> { r = red(base) * red(f) / 255; g = green(base) * green(f) / 255; b = blue(base) * blue(f) / 255 }
            2 -> {
                // the fill's color with the picture's brightness
                val shift = luma(base) - luma(f)
                r = (red(f) + shift).roundToInt().coerceIn(0, 255)
                g = (green(f) + shift).roundToInt().coerceIn(0, 255)
                b = (blue(f) + shift).roundToInt().coerceIn(0, 255)
            }
            else -> { r = red(f); g = green(f); b = blue(f) }
        }
        fun mix(o: Int, m: Int) = (o + (m - o) * amount).roundToInt()
        return argb(alpha(base), mix(red(base), r), mix(green(base), g), mix(blue(base), b))
    }

    /**
     * Paints every pixel with its area's color. With [sub] > 1 the pixels on a border
     * between areas are sampled [sub] × [sub] times, as in the glass: each sample reads the
     * picture at its spot and belongs to the neighbouring area (of the 3 × 3 around) whose
     * average color is closest – ties go to the nearer one; the samples are averaged,
     * premultiplied, so cut out edges get soft too. Outlines are laid on top.
     */
    private fun paint(src: Pixels, labels: IntArray, fills: IntArray, clear: BooleanArray, means: IntArray, sub: Int, v: Values): Pixels {
        val w = src.width
        val h = src.height
        val blend = v["blend"]
        val amount = v["amount"] / 100.0
        val outline = v.bool("outline")
        // the samples are sorted by a slightly softened picture: the border then runs as a smooth line
        // through the pixels, even where the picture itself has hard steps
        val soft = if (sub > 1) softened(src) else src
        val out = Pixels(w, h)
        com.spielgrund.glitchr.image.parallelRows(h) { y ->
            val cand = IntArray(9)
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val l = labels[i]
                // the neighbours: a border pixel if any of them belongs elsewhere
                var count = 0
                var border = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    cand[count++] = j
                    if (labels[j] != l) border = true
                }
                if (sub == 1 || !border) {
                    out.data[i] = if (l < 0) base else shade(l, base, fills, clear, blend, amount)
                } else {
                    var sa = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0
                    for (sy in 0 until sub) for (sx in 0 until sub) {
                        val px = x + (sx + 0.5) / sub
                        val py = y + (sy + 0.5) / sub
                        val c = sampleBilinear(src, px, py, com.spielgrund.glitchr.image.Edge.CLAMP)
                        val k0 = sampleBilinear(soft, px, py, com.spielgrund.glitchr.image.Edge.CLAMP)
                        // the area this sample belongs to: closest in color, then in distance
                        var best = i
                        var bestScore = Double.MAX_VALUE
                        for (k in 0 until count) {
                            val j = cand[k]
                            val lj = labels[j]
                            val rep = if (lj >= 0) means[lj] else src.data[j]
                            val colorDist = abs(red(k0) - red(rep)) + abs(green(k0) - green(rep)) + abs(blue(k0) - blue(rep)) + abs(alpha(k0) - alpha(rep))
                            val spatial = hypot(px - (j % w + 0.5), py - (j / w + 0.5))
                            val score = colorDist + 2 * spatial
                            if (score < bestScore) { bestScore = score; best = j }
                        }
                        val lb = labels[best]
                        val s = if (lb >= 0) shade(lb, c, fills, clear, blend, amount) else c
                        val a = alpha(s).toDouble()
                        sa += a; sr += red(s) * a; sg += green(s) * a; sb += blue(s) * a
                    }
                    val n = (sub * sub).toDouble()
                    out.data[i] = if (sa <= 0) 0 else argb((sa / n).roundToInt(), (sr / sa).roundToInt(), (sg / sa).roundToInt(), (sb / sa).roundToInt())
                }
                if (outline && l >= 0 && ((x + 1 < w && labels[i + 1] != l) || (y + 1 < h && labels[i + w] != l))) {
                    out.data[i] = argb(max(alpha(out.data[i]), (255 * amount).roundToInt()), 0, 0, 0)
                }
            }
        }
        return out
    }
}
