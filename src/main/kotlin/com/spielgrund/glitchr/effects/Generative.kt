package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.red
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generative line drawings in the style of creative coding: many hairlines drawn by
 * simple rules, whose overlaps create moiré. Colors can come from the picture.
 *
 * - Bänder: a wobbly closed shape wanders along a noise field and is drawn at every
 *   step, giving twisted ribbons of fine lines.
 * - Fliesslinien: lines following a flow field – noise, the picture's brightness contours
 *   or both. "Gleichmässig füllen" packs evenly spaced streamlines over the whole picture
 *   (each line grows both ways until it comes too close to another), optionally denser
 *   where the picture is dark.
 * - Moiré-Ringe: several centers with dense, slightly wavy rings that interfere.
 * - Spiegelkacheln: flow lines drawn mirrored in one tile, repeated over the picture.
 */
object Generative : Effect("generative", "Generativ", "Generative Linienmuster: Bänder, Fliesslinien, Moiré, Spiegelkacheln") {
    private val patterns = listOf("Bänder", "Fliesslinien", "Moiré-Ringe", "Spiegelkacheln")
    private val colorModes = listOf("Aus dem Bild", "Bildpalette", "Regenbogen (pastell)", "Eine Farbe")

    override val params = listOf(
        Param.Choice("pattern", "Muster", patterns),
        Param.Slider("count", "Anzahl", 1, 3000, 16, tip = "Bänder, Linien oder Ring-Zentren (Fliesslinien: nur ohne „Gleichmässig füllen“)"),
        Param.Slider("steps", "Länge", 10, 3000, 400, tip = "Wie viele Schritte jedes Band oder jede Linie gezeichnet wird"),
        Param.Choice(
            "field", "Strömung", listOf("Noise", "Aus dem Bild", "Bild + Noise"),
            tip = "Aus dem Bild: die Linien folgen den Helligkeitskonturen des Bilds (Bänder und Fliesslinien)",
        ),
        Param.Toggle("fill", "Gleichmässig füllen", true, "Nur Fliesslinien: füllt das ganze Bild mit Linien im festen Abstand"),
        Param.Slider("spacing", "Linienabstand", 2, 100, 8, " px", "Nur „Gleichmässig füllen“"),
        Param.Toggle("darkDense", "Dunkle Stellen dichter", false, "Nur „Gleichmässig füllen“: der Abstand folgt der Helligkeit – das Motiv entsteht aus der Liniendichte"),
        Param.Slider("size", "Grösse", 2, 600, 90, " px", "Bänder: Durchmesser der Form. Ringe: Abstand der Ringe × 10. Kacheln: Kachelgrösse"),
        Param.Slider("turbulence", "Turbulenz", 1, 100, 30, " %", "Wie stark die Wege sich winden"),
        Param.Slider("wobble", "Verformung", 0, 100, 35, " %", "Wie stark Bänder und Ringe verbeult werden"),
        Param.Slider("lineWidth", "Linienstärke", 1, 50, 5, tip = "In Zehntelpixeln: 5 = 0,5 px Haarlinie"),
        Param.Slider("lineOpacity", "Deckkraft der Linien", 1, 100, 45, " %"),
        Param.Choice("colorMode", "Farbe", colorModes),
        Param.Color("color", "Linienfarbe", 0x1BA34A, "Für „Eine Farbe“"),
        Param.Choice("background", "Hintergrund", listOf("Originalbild", "Weiss", "Schwarz", "Transparent"), default = 1),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        when (v["background"]) {
            0 -> src.data.copyInto(data)
            1 -> data.fill(-1)
            2 -> data.fill(0xFF000000.toInt())
        }
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.stroke = BasicStroke(v["lineWidth"] / 10f)

        val ctx = Context(src, v, seed)
        when (v["pattern"]) {
            1 -> if (v.bool("fill")) evenLines(g, ctx)
            else flowLines(g, ctx, w.toDouble(), h.toDouble(), ctx.random.nextDouble() * 1000, fromImage = true)
            2 -> rings(g, ctx)
            3 -> mirrorTiles(g, ctx)
            else -> ribbons(g, ctx)
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /** Everything the drawing routines share. */
    private class Context(val src: Pixels, val v: Values, seed: Long) {
        val random = Random(seed)
        val noise = Noise(seed)
        val w = src.width
        val h = src.height
        val opacity = v["lineOpacity"] / 100f
        val turbulence = v["turbulence"] / 100.0
        val wobble = v["wobble"] / 100.0

        /** Noise field scale: smaller turbulence = broader, calmer curves. */
        val fieldScale = min(w, h) / (0.5 + 6 * turbulence)
        val palette: IntArray by lazy { dominantColors(src, 6, seed) }

        /** Direction of the noise field at ([x], [y]) in radians; [scale] = size of its structures. */
        fun flow(x: Double, y: Double, t: Double = 0.0, scale: Double = fieldScale) =
            noise.fbm(x / scale + t, y / scale - t, 3) * PI * 2.5

        private val field = v["field"]

        /** Per pixel: direction along the picture's brightness contours, and how pronounced they are (0..1). */
        private val contours: Pair<FloatArray, FloatArray> by lazy { contours(src) }

        /**
         * Direction of the flow at ([x], [y]): the noise field, or with [fromImage] and a
         * picture field, along the contours – where the picture is flat, noise takes over.
         */
        fun direction(x: Double, y: Double, t: Double = 0.0, scale: Double = fieldScale, fromImage: Boolean = true): Double {
            val n = flow(x, y, t, scale)
            if (!fromImage || field == 0) return n
            val i = y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)
            val a = contours.first[i].toDouble()
            val strength = contours.second[i].toDouble()
            val noiseWeight = if (field == 1) 0.15 * (1 - strength) else 1 - strength * 0.5
            val vx = cos(a) * strength + cos(n) * noiseWeight
            val vy = sin(a) * strength + sin(n) * noiseWeight
            return kotlin.math.atan2(vy, vx)
        }

        /** Line color for agent [agent] of [agents] at step [step] of [steps], standing at ([x], [y]). */
        fun color(agent: Int, agents: Int, step: Int, steps: Int, x: Double, y: Double): Color {
            val rgb = when (v["colorMode"]) {
                0 -> {
                    val px = x.toInt().coerceIn(0, w - 1)
                    val py = y.toInt().coerceIn(0, h - 1)
                    src[px, py].takeIf { alpha(it) > 0 } ?: argb(255, 128, 128, 128)
                }
                1 -> palette[agent % palette.size]
                2 -> {
                    // soft pastel hues that drift along the path
                    val hue = (agent.toFloat() / max(1, agents) + step.toFloat() / max(1, steps) * 0.35f) % 1f
                    Color.HSBtoRGB(hue, 0.45f, 0.85f)
                }
                else -> v["color"] or 0xFF000000.toInt()
            }
            return Color(red(rgb), green(rgb), blue(rgb), (opacity * 255).toInt().coerceIn(1, 255))
        }
    }

    /**
     * Direction along the brightness contours (perpendicular to the gradient of the
     * slightly blurred brightness) and the contour strength 0..1 for every pixel.
     */
    private fun contours(src: Pixels): Pair<FloatArray, FloatArray> {
        val w = src.width
        val h = src.height
        val r = max(2, min(w, h) / 120)
        // box-blurred brightness, horizontally then vertically
        val l = FloatArray(w * h) { luma(src.data[it]).toFloat() }
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
        val angle = FloatArray(w * h)
        val strength = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = l[y * w + min(w - 1, x + 1)] - l[y * w + max(0, x - 1)]
            val gy = l[min(h - 1, y + 1) * w + x] - l[max(0, y - 1) * w + x]
            angle[y * w + x] = (kotlin.math.atan2(gy, gx) + PI / 2).toFloat()
            strength[y * w + x] = min(1f, hypot(gx, gy) / 6f)
        }
        return angle to strength
    }

    /**
     * Evenly spaced streamlines: candidate seeds in random order; a seed far enough from
     * all lines grows a line in both directions until it leaves the picture, runs out of
     * steps or comes closer than half the spacing to another line. The spacing can
     * follow the brightness (dark = dense).
     */
    private fun evenLines(g: Graphics2D, c: Context) {
        val w = c.w
        val h = c.h
        val base = c.v["spacing"].toDouble()
        val byBrightness = c.v.bool("darkDense")
        val maxSteps = c.v["steps"]
        fun spacing(x: Double, y: Double): Double {
            if (!byBrightness) return base
            val l = luma(c.src[x.toInt().coerceIn(0, w - 1), y.toInt().coerceIn(0, h - 1)]) / 255.0
            return base * (0.3 + 1.4 * l)
        }
        fun visible(x: Double, y: Double) = x >= 0 && y >= 0 && x < w && y < h && alpha(c.src[x.toInt(), y.toInt()]) > 0

        // points of all lines in a grid of buckets for fast distance checks
        val cell = max(1.0, base * (if (byBrightness) 0.3 else 1.0) * 0.5)
        val gw = (w / cell).toInt() + 1
        val gh = (h / cell).toInt() + 1
        val head = IntArray(gw * gh) { -1 }
        var xs = FloatArray(1 shl 16)
        var ys = FloatArray(1 shl 16)
        var line = IntArray(1 shl 16)
        var index = IntArray(1 shl 16)
        var next = IntArray(1 shl 16)
        var count = 0
        fun add(x: Double, y: Double, id: Int, step: Int) {
            if (count == xs.size) {
                xs = xs.copyOf(count * 2); ys = ys.copyOf(count * 2); line = line.copyOf(count * 2)
                index = index.copyOf(count * 2); next = next.copyOf(count * 2)
            }
            val b = (y / cell).toInt() * gw + (x / cell).toInt()
            xs[count] = x.toFloat(); ys[count] = y.toFloat(); line[count] = id; index[count] = step
            next[count] = head[b]; head[b] = count++
        }
        /** Whether a point of another line (or of this line, far back) lies closer than [d]. */
        fun near(x: Double, y: Double, d: Double, id: Int, step: Int, skip: Int): Boolean {
            val r = (d / cell).toInt() + 1
            val bx = (x / cell).toInt()
            val by = (y / cell).toInt()
            for (j in max(0, by - r)..min(gh - 1, by + r)) for (i in max(0, bx - r)..min(gw - 1, bx + r)) {
                var p = head[j * gw + i]
                while (p >= 0) {
                    if (!(line[p] == id && kotlin.math.abs(index[p] - step) < skip)) {
                        val dx = xs[p] - x
                        val dy = ys[p] - y
                        if (dx * dx + dy * dy < d * d) return true
                    }
                    p = next[p]
                }
            }
            return false
        }

        val candidateStep = max(1.0, base * (if (byBrightness) 0.3 else 0.5))
        val candidates = ArrayList<Pair<Double, Double>>()
        var cy = candidateStep / 2
        while (cy < h) {
            var cx = candidateStep / 2
            while (cx < w) {
                candidates += (cx + (c.random.nextDouble() - 0.5) * candidateStep) to (cy + (c.random.nextDouble() - 0.5) * candidateStep)
                cx += candidateStep
            }
            cy += candidateStep
        }
        candidates.shuffle(c.random)

        val stepLength = 1.0
        var lines = 0
        for ((sx, sy) in candidates) {
            if (!visible(sx, sy) || near(sx, sy, spacing(sx, sy), -1, 0, 0)) continue
            val id = lines++
            val skip = (spacing(sx, sy) * 3 / stepLength).toInt() + 2
            add(sx, sy, id, 0)
            val halves = Array(2) { ArrayList<Pair<Double, Double>>() }
            for ((half, dir) in listOf(0 to 1.0, 1 to -1.0)) {
                var x = sx
                var y = sy
                for (s in 1..maxSteps) {
                    val a = c.direction(x, y)
                    x += cos(a) * stepLength * dir
                    y += sin(a) * stepLength * dir
                    if (!visible(x, y)) break
                    val step = if (dir > 0) s else -s
                    if (near(x, y, spacing(x, y) * 0.5, id, step, skip)) break
                    add(x, y, id, step)
                    halves[half] += x to y
                }
            }
            val points = halves[1].asReversed() + listOf(sx to sy) + halves[0]
            if (points.size < 3) continue
            val path = Path2D.Double()
            path.moveTo(points[0].first, points[0].second)
            for (k in 1 until points.size) {
                path.lineTo(points[k].first, points[k].second)
                // draw in pieces so the color can follow the line
                if (k % 40 == 0 || k == points.size - 1) {
                    val (px, py) = points[k]
                    g.color = c.color(id, 60, k, points.size, px, py)
                    g.draw(path)
                    path.reset()
                    path.moveTo(px, py)
                }
            }
        }
    }

    /** Wobbly closed shapes wandering along the noise field, drawn at every step (the "tube" look). */
    private fun ribbons(g: Graphics2D, c: Context) {
        val agents = c.v["count"]
        val steps = c.v["steps"]
        val size = c.v["size"] / 2.0
        val vertices = 48
        repeat(agents) { a ->
            var x = c.random.nextDouble() * c.w
            var y = c.random.nextDouble() * c.h
            val phase = c.random.nextDouble() * 100
            val base = size * (0.6 + 0.4 * c.random.nextDouble())
            // a few pixels between the drawn outlines keep the single hairlines visible
            val speed = max(0.5, base * 0.06)
            for (s in 0 until steps) {
                val angle = c.direction(x, y)
                x += cos(angle) * speed
                y += sin(angle) * speed
                // the shape breathes and its outline is bent by noise that changes along the way
                val radius = base * (0.7 + 0.3 * sin(s * 0.02 + phase))
                val t = s * 0.01 + phase
                val path = Path2D.Double()
                for (k in 0..vertices) {
                    val va = 2 * PI * k / vertices
                    val bend = 1 + c.wobble * c.noise.perlin(cos(va) * 1.2 + t, sin(va) * 1.2 - t)
                    val px = x + cos(va) * radius * bend
                    val py = y + sin(va) * radius * bend
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.closePath()
                g.color = c.color(a, agents, s, steps, x, y)
                g.draw(path)
            }
        }
    }

    /**
     * Agents drawing lines along the flow field inside a [width]×[height] area
     * (the whole picture, or one tile); [t] shifts the field.
     */
    private fun flowLines(g: Graphics2D, c: Context, width: Double, height: Double, t: Double, fromImage: Boolean = false) {
        val scale = min(width, height) / (0.5 + 6 * c.turbulence)
        val agents = c.v["count"]
        val steps = c.v["steps"]
        val speed = max(0.6, min(width, height) / 300)
        repeat(agents) { a ->
            var x = c.random.nextDouble() * width
            var y = c.random.nextDouble() * height
            val path = Path2D.Double()
            path.moveTo(x, y)
            var drawn = 0
            for (s in 0 until steps) {
                val angle = c.direction(x, y, t, scale, fromImage)
                x += cos(angle) * speed
                y += sin(angle) * speed
                if (x < 0 || y < 0 || x >= width || y >= height) break
                path.lineTo(x, y)
                drawn++
                // draw in pieces so the color can follow the path
                if (drawn % 40 == 0) {
                    g.color = c.color(a, agents, s, steps, x, y)
                    g.draw(path)
                    path.reset()
                    path.moveTo(x, y)
                }
            }
            g.color = c.color(a, agents, steps, steps, x, y)
            g.draw(path)
        }
    }

    /** Dense wavy rings around a few centers; where they overlap, moiré appears. */
    private fun rings(g: Graphics2D, c: Context) {
        val centers = min(c.v["count"], 24)
        val spacing = max(1.5, c.v["size"] / 10.0)
        val reach = hypot(c.w.toDouble(), c.h.toDouble())
        val vertices = 180
        repeat(centers) { a ->
            val cx = c.random.nextDouble() * c.w
            val cy = c.random.nextDouble() * c.h
            val phase = c.random.nextDouble() * 100
            var r = spacing
            var ring = 0
            val total = (reach / spacing).toInt()
            while (r < reach) {
                val path = Path2D.Double()
                for (k in 0..vertices) {
                    val va = 2 * PI * k / vertices
                    val bend = 1 + c.wobble * 0.15 * c.noise.perlin(cos(va) * 2 + phase, sin(va) * 2 + r / (spacing * 40))
                    val px = cx + cos(va) * r * bend
                    val py = cy + sin(va) * r * bend
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                g.color = c.color(a, centers, ring, total, cx + r * 0.7, cy)
                g.draw(path)
                r += spacing
                ring++
            }
        }
    }

    /** Flow lines drawn once into a tile, mirrored four ways, and the tile repeated (a mirrored brush). */
    private fun mirrorTiles(g: Graphics2D, c: Context) {
        val size = max(8, c.v["size"])
        val half = size / 2.0
        val tile = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val tg = tile.createGraphics()
        tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        tg.stroke = g.stroke
        // draw the lines in one quarter, then the quarter mirrored into the other three
        val quarter = BufferedImage(size / 2 + 1, size / 2 + 1, BufferedImage.TYPE_INT_ARGB)
        val qg = quarter.createGraphics()
        qg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        qg.stroke = g.stroke
        flowLines(qg, c, half + 1, half + 1, c.random.nextDouble() * 1000)
        qg.dispose()
        for ((sx, sy) in listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)) {
            val t = AffineTransform()
            t.translate(if (sx < 0) size.toDouble() else 0.0, if (sy < 0) size.toDouble() else 0.0)
            t.scale(sx.toDouble(), sy.toDouble())
            tg.drawImage(quarter, t, null)
        }
        tg.dispose()
        var y = 0
        while (y < c.h) {
            var x = 0
            while (x < c.w) {
                g.drawImage(tile, x, y, null)
                x += size
            }
            y += size
        }
    }
}
