package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.nearestColor
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rebuilds the picture from geometric particles. First the picture is "blobified":
 * blurred, reduced to a few main colors and split into connected color areas (blobs);
 * the abstraction sets how coarse. Then particles spawn on it: all particles of a blob
 * share one base size, the bigger the blob, the bigger the particles. Each blob also
 * collects a sample of its original colors, which particles can pick from at random.
 * Big shapes are drawn first, small ones on top, so small blobs stay readable.
 */
// id stays "shapes" so projects from when this effect was called "Formen" still load
object Particles : Effect("shapes", "Partikel", "Setzt das Bild aus geometrischen Partikeln neu zusammen") {
    private val forms = listOf("Kreise", "Quadrate", "Dreiecke", "Sechsecke", "Striche", "Gemischt")

    override val params = listOf(
        Param.Choice("form", "Form", forms),
        Param.Slider("density", "Dichte", 10, 400, 100, " %", "Wie dicht die Partikel liegen: bei 100 % schliessen sie lückenlos an, egal wie gross sie sind"),
        Param.Slider("abstraction", "Abstraktion", 0, 100, 40, " %", "Wie stark das Bild zuerst zu Farbflächen (Blobs) vereinfacht wird. Grosse Blobs geben grosse Partikel"),
        Param.Slider("particleSize", "Partikelgrösse", 10, 400, 100, " %", "Skaliert alle Partikel; ihre Grundgrösse folgt der Grösse ihrer Farbfläche"),
        Param.Slider("sizeJitter", "Grössen-Zufall", 0, 100, 30, " %", "0 %: alle Partikel einer Fläche gleich gross. 100 %: stark gestreut"),
        Param.Slider("colorRange", "Farbvielfalt", 0, 100, 50, " %", "0 %: Mittelfarbe der Fläche. 100 %: jedes Partikel nimmt eine zufällige Originalfarbe aus seiner Fläche"),
        Param.Slider("detail", "Detail an Kanten", 0, 100, 0, " %", "Macht Partikel an den Rändern der Farbflächen kleiner"),
        Param.Choice("spread", "Verteilung", listOf("Zufällig", "Raster", "An Kanten dichter")),
        Param.Choice("orient", "Ausrichtung", listOf("Zufällig", "Entlang der Kanten", "Gerade")),
        Param.Slider("opacity", "Deckkraft der Formen", 5, 100, 90, " %"),
        Param.Choice("background", "Hintergrund", listOf("Originalbild", "Mittlere Farbe", "Transparent", "Blob-Bild")),
    )

    private class Particle(val x: Double, val y: Double, val size: Double, val angle: Double, val color: Int, val form: Int)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val blobs = blobify(src, v["abstraction"] / 100.0, seed)
        val (edge, direction) = edges(blobs.image)
        val sizeFactor = v["particleSize"] / 100.0
        val largest = min(w, h) / 2.0
        val detail = v["detail"] / 100.0
        val opacity = v["opacity"] / 100f
        val density = v["density"] / 100.0
        val jitter = v["sizeJitter"] / 100.0
        val colorRange = v["colorRange"] / 100f

        /** Base particle radius of blob [b]: from its area (half the radius of a circle as big as the blob). */
        fun blobRadius(b: Int) = max(0.75, min(largest, sqrt(blobs.area[b] / PI) * 0.5 * sizeFactor))

        /** Base particle radius at pixel [i]: the same for the whole blob, smaller at edges with "Detail an Kanten". */
        fun base(i: Int): Double {
            val blob = blobs.label[i]
            return if (blob < 0) 1.0 else max(0.75, blobRadius(blob) * (1 - detail * edge[i] * 0.85))
        }

        // Every blob gets its own grid, spaced for its particle size: circles of radius r on
        // a square grid of spacing about 1.25·r cover the area even with some jitter, so the
        // coverage no longer depends on how big the particles are. Dividing by √density packs them closer or looser. The grid
        // is jittered for "Zufällig", exact for "Raster". A blob smaller than one grid step
        // still gets a particle near its middle, so no area is left out.
        val spread = v["spread"]
        val positions = ArrayList<Pair<Double, Double>>()
        val limit = 400_000
        val jitterGrid = if (spread == 1) 0.0 else 0.5
        for (b in blobs.area.indices) {
            // at most half the blob's extent, so even huge particles are spread over the whole blob
            val step = min(blobRadius(b) * 1.25, sqrt(blobs.area[b].toDouble()) * 0.5)
            val spacing = max(1.0, step / sqrt(density) * (if (spread == 2) 1.6 else 1.0))
            val x0 = blobs.bounds[4 * b]
            val y0 = blobs.bounds[4 * b + 1]
            val x1 = blobs.bounds[4 * b + 2]
            val y1 = blobs.bounds[4 * b + 3]
            var placed = 0
            var gy = y0 + random.nextDouble() * spacing
            while (gy <= y1 + 1) {
                var gx = x0 + random.nextDouble() * spacing
                while (gx <= x1 + 1) {
                    val px = (gx + (random.nextDouble() - 0.5) * spacing * jitterGrid).coerceIn(0.0, w - 0.001)
                    val py = (gy + (random.nextDouble() - 0.5) * spacing * jitterGrid).coerceIn(0.0, h - 0.001)
                    if (blobs.label[py.toInt() * w + px.toInt()] == b && positions.size < limit) {
                        positions += px to py
                        placed++
                    }
                    gx += spacing
                }
                gy += spacing
            }
            if (placed == 0 && positions.size < limit) {
                val c = blobs.center[b]
                positions += (c % w + 0.5) to (c / w + 0.5)
            }
        }
        // "An Kanten dichter": extra particles scattered along the edges
        if (spread == 2) {
            val cell = 3
            for (cy in 0 until h step cell) for (cx in 0 until w step cell) {
                val x = cx + random.nextDouble() * cell
                val y = cy + random.nextDouble() * cell
                if (x >= w || y >= h) continue
                val i = y.toInt() * w + x.toInt()
                if (blobs.label[i] < 0) continue
                val r = base(i)
                if (random.nextDouble() < density * cell * cell / (PI * r * r) * edge[i] && positions.size < limit) positions += x to y
            }
        }

        val mixed = v["form"] == 5
        val particles = positions.map { (x, y) ->
            val i = y.toInt() * w + x.toInt()
            val size = base(i) * max(0.1, 1 + (random.nextDouble() * 2 - 1) * jitter)
            val angle = when (v["orient"]) {
                1 -> direction[i] + PI / 2
                2 -> 0.0
                else -> random.nextDouble() * 2 * PI
            }
            // mix the blob's mean color towards one of its sampled original colors
            val samples = blobs.label[i].let { if (it < 0) null else blobs.samples[it] }
            val mean = blobs.image.data[i]
            val c = if (colorRange > 0f && samples != null && samples.isNotEmpty())
                lerpArgb(mean, samples[random.nextInt(samples.size)], colorRange) else mean
            val color = argb((alpha(c) * opacity).toInt(), red(c), green(c), blue(c))
            Particle(x, y, max(0.75, size), angle, color, if (mixed) random.nextInt(5) else v["form"])
        }.sortedByDescending { it.size }

        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        when (v["background"]) {
            0 -> src.data.copyInto(data)
            3 -> blobs.image.data.copyInto(data)
            1 -> {
                val avg = averageColor(src)
                for (i in data.indices) data[i] = (src.data[i] and 0xFF000000.toInt()) or (avg and 0xFFFFFF)
            }
        }
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (p in particles) {
            g.color = Color(p.color, true)
            val t = AffineTransform().apply { translate(p.x, p.y); rotate(p.angle) }
            g.fill(t.createTransformedShape(shape(p.form, p.size)))
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /**
     * Connected color areas: blob index per pixel (-1 = transparent), each blob's area,
     * a random sample of each blob's original colors, and the picture in blob mean colors.
     */
    internal class Blobs(
        val label: IntArray,
        val area: IntArray,
        val samples: Array<IntArray>,
        val image: Pixels,
        /** Bounding box per blob: minX, minY, maxX, maxY. */
        val bounds: IntArray,
        /** A pixel of each blob close to its middle. */
        val center: IntArray,
    )

    /** How many original colors each blob keeps for "Farbvielfalt". */
    private const val SAMPLES = 32

    /**
     * Blur by up to about 1/30 of the shorter side and reduce to 20 down to 4 main colors
     * (more [abstraction] = coarser), then label connected areas of equal color. Areas
     * smaller than a minimum that also grows with the abstraction are merged into their
     * biggest neighbor, so textured parts (fur, noise) don't fall apart into specks.
     * Each blob gets the average original color of its pixels and a random sample of them.
     */
    internal fun blobify(src: Pixels, abstraction: Double, seed: Long): Blobs {
        val w = src.width
        val h = src.height
        val n = w * h
        val radius = (abstraction.pow(1.3) * min(w, h) / 30).toInt()
        val blurred = if (radius > 0) boxBlur(src, radius) else src
        val palette = dominantColors(blurred, (20 - 16 * abstraction).roundToInt().coerceIn(2, 20), seed)
        val quantized = IntArray(n) { if (alpha(src.data[it]) == 0) -1 else nearestColor(palette, blurred.data[it]) }

        // connected areas of equal quantized color
        val label = IntArray(n) { -1 }
        var count = 0
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (label[start] >= 0 || quantized[start] < 0) continue
            val id = count++
            val q = quantized[start]
            var top = 0
            stack[top++] = start
            label[start] = id
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                fun visit(j: Int) {
                    if (label[j] < 0 && quantized[j] == q) {
                        label[j] = id
                        stack[top++] = j
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (i >= w) visit(i - w)
                if (i < n - w) visit(i + w)
            }
        }

        // merge small areas into their biggest neighbor (union-find over the labels)
        val parent = IntArray(count) { it }
        val size = IntArray(count)
        for (l in label) if (l >= 0) size[l]++
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            var c = a
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        val minArea = (1 + abstraction * min(w, h) / 40).pow(2).toInt()
        if (minArea > 1) repeat(4) {
            for (i in 0 until n) {
                if (label[i] < 0) continue
                val a = find(label[i])
                if (size[a] >= minArea) continue
                val x = i % w
                var best = -1
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                    if (j < 0 || j >= n || label[j] < 0) continue
                    val b = find(label[j])
                    if (b != a && (best < 0 || size[b] > size[best])) best = b
                }
                if (best >= 0) {
                    parent[a] = best
                    size[best] += size[a]
                }
            }
        }

        // final blob ids, mean colors and a random sample of each blob's original colors
        val ids = IntArray(count) { -1 }
        var blobs = 0
        for (i in 0 until n) if (label[i] >= 0) {
            val r = find(label[i])
            if (ids[r] < 0) ids[r] = blobs++
            label[i] = ids[r]
        }
        val area = IntArray(blobs)
        val bounds = IntArray(4 * blobs)
        for (b in 0 until blobs) { bounds[4 * b] = w; bounds[4 * b + 1] = h; bounds[4 * b + 2] = -1; bounds[4 * b + 3] = -1 }
        val sumX = LongArray(blobs)
        val sumY = LongArray(blobs)
        val sums = Array(blobs) { LongArray(4) }
        val samples = Array(blobs) { IntArray(SAMPLES) }
        val sampler = Random(seed xor 0x5A5A5A5AL)
        for (i in 0 until n) {
            val b = label[i]
            if (b < 0) continue
            val c = src.data[i]
            val seen = ++area[b]
            val px = i % w
            val py = i / w
            bounds[4 * b] = min(bounds[4 * b], px); bounds[4 * b + 1] = min(bounds[4 * b + 1], py)
            bounds[4 * b + 2] = max(bounds[4 * b + 2], px); bounds[4 * b + 3] = max(bounds[4 * b + 3], py)
            sumX[b] += px.toLong(); sumY[b] += py.toLong()
            val sm = sums[b]
            sm[0] += alpha(c).toLong(); sm[1] += red(c).toLong(); sm[2] += green(c).toLong(); sm[3] += blue(c).toLong()
            // reservoir sampling: every pixel of the blob has the same chance to be kept
            if (seen <= SAMPLES) samples[b][seen - 1] = c
            else sampler.nextInt(seen).let { if (it < SAMPLES) samples[b][it] = c }
        }
        val colors = IntArray(blobs) { b ->
            val k = area[b].toLong()
            val sm = sums[b]
            argb((sm[0] / k).toInt(), (sm[1] / k).toInt(), (sm[2] / k).toInt(), (sm[3] / k).toInt())
        }
        val kept = Array(blobs) { b -> if (area[b] < SAMPLES) samples[b].copyOf(area[b]) else samples[b] }
        val image = Pixels(w, h, IntArray(n) { if (label[it] < 0) 0 else colors[label[it]] })
        // the blob pixel nearest to its centroid (the centroid itself may lie outside, e.g. for rings)
        val center = IntArray(blobs) { -1 }
        val bestDistance = DoubleArray(blobs) { Double.MAX_VALUE }
        for (i in 0 until n) {
            val b = label[i]
            if (b < 0) continue
            val dx = i % w - sumX[b].toDouble() / area[b]
            val dy = i / w - sumY[b].toDouble() / area[b]
            val d = dx * dx + dy * dy
            if (d < bestDistance[b]) { bestDistance[b] = d; center[b] = i }
        }
        return Blobs(label, area, kept, image, bounds, center)
    }

    /** Separable box blur of all four channels. */
    private fun boxBlur(src: Pixels, r: Int): Pixels {
        fun pass(input: Pixels, horizontal: Boolean): Pixels {
            val w = input.width
            val h = input.height
            val out = Pixels(w, h)
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            parallelRows(lines) { l ->
                fun at(i: Int) = input.data[if (horizontal) l * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + l]
                var a = 0; var rr = 0; var g = 0; var b = 0
                for (i in -r..r) { val c = at(i); a += alpha(c); rr += red(c); g += green(c); b += blue(c) }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    out.data[if (horizontal) l * w + i else i * w + l] = argb(a / n, rr / n, g / n, b / n)
                    val add = at(i + r + 1)
                    val remove = at(i - r)
                    a += alpha(add) - alpha(remove); rr += red(add) - red(remove)
                    g += green(add) - green(remove); b += blue(add) - blue(remove)
                }
            }
            return out
        }
        return pass(pass(src, true), false)
    }

    /** A shape of radius [r] centered at the origin. */
    private fun shape(form: Int, r: Double): Shape = when (form) {
        1 -> Rectangle2D.Double(-r * 0.8, -r * 0.8, r * 1.6, r * 1.6)
        2 -> polygon(3, r)
        3 -> polygon(6, r)
        4 -> RoundRectangle2D.Double(-r * 1.4, -r * 0.18, r * 2.8, r * 0.36, r * 0.3, r * 0.3)
        else -> Ellipse2D.Double(-r, -r, 2 * r, 2 * r)
    }

    private fun polygon(corners: Int, r: Double) = Path2D.Double().apply {
        for (i in 0 until corners) {
            val a = -PI / 2 + 2 * PI * i / corners
            if (i == 0) moveTo(r * cos(a), r * sin(a)) else lineTo(r * cos(a), r * sin(a))
        }
        closePath()
    }

    /** Edge strength 0..1 and gradient direction per pixel (Sobel on brightness). */
    private fun edges(src: Pixels): Pair<FloatArray, DoubleArray> {
        val w = src.width
        val h = src.height
        val l = IntArray(src.data.size) { luma(src.data[it]) * alpha(src.data[it]) / 255 }
        val strength = FloatArray(l.size)
        val direction = DoubleArray(l.size)
        fun at(x: Int, y: Int) = l[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val gx = at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)
                val gy = at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)
                strength[y * w + x] = min(1f, sqrt((gx * gx + gy * gy).toFloat()) / 400f)
                direction[y * w + x] = atan2(gy.toDouble(), gx.toDouble())
            }
        }
        return strength to direction
    }

    private fun averageColor(src: Pixels): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (c in src.data) if (alpha(c) > 0) { r += red(c); g += green(c); b += blue(c); n++ }
        return if (n == 0L) 0 else argb(255, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
