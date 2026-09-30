package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import com.spielgrund.glitchr.model.BlendMode
import java.awt.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Video feedback: copies laid over each other again and again, like a camera filming
 * its own monitor. Every step scales, turns and shifts the previous copy around a center
 * – so the shifts are scaled and turned along and copies spiral inwards or outwards –
 * and moves its hue, saturation and lightness a bit further.
 *
 * What is copied ("Quelle"):
 * - Bild: the picture itself.
 * - Kanten: its edges (Sobel), colored by the picture, one color or their direction.
 * - Blobs: the picture is first cut into color areas (as for Hologramm and Partikel);
 *   every blob gets its own feedback around its own middle, as outline, flat area or
 *   picture content – with scaling above 100 % the copies grow out of the blobs, below
 *   they run into them.
 * - Alphakante: only a stroke along the picture's edge – its transparent parts and the
 *   canvas border – outside, centered or inside, in a width and color. The picture
 *   layer's mask is part of it, because the renderer cuts the picture out by its mask
 *   before any effect runs.
 *
 * The same stroke can frame the picture (and its edges) in the other modes too, so every
 * copy gets its frame.
 *
 * The copies go over the picture (the newest on top) or under it (the picture or the
 * edges/blobs themselves on top), mixed by a blend mode and fading with every step.
 * At the end the first copy or the original can be laid over everything once more, so
 * the motif stays visible as the core of its echoes.
 */
object Feedback : Effect("feedback", "Feedback", "Kopien des Bilds, seiner Kanten oder Blobs immer wieder übereinander – grösser, kleiner, gedreht, versetzt, mit Farbverschiebung") {
    private val edges = listOf("Transparent", "Wiederholen", "Spiegeln", "Rand strecken")

    private const val SOURCE_IMAGE = 0
    private const val SOURCE_EDGES = 1
    private const val SOURCE_BLOBS = 2
    private const val SOURCE_ALPHA = 3

    override val params = listOf(
        Param.Choice(
            "source", "Quelle", listOf("Bild", "Kanten", "Blobs", "Alphakante"),
            tip = "Kanten: die Kanten des Bilds werden kopiert. Blobs: das Bild wird in Farbflächen zerlegt, " +
                "jede bekommt ihr eigenes Feedback um ihre Mitte – über 100 % wächst es heraus, darunter läuft es hinein. " +
                "Alphakante: eine Kontur um die durchsichtigen Stellen des Bilds, samt der Maske der Bildebene",
        ),
        Param.Slider("steps", "Schritte", 1, 100, 12, tip = "Wie viele Kopien übereinander gelegt werden"),
        Param.Heading("transformHeading", "Je Schritt"),
        Param.Slider("scale", "Skalierung", 500, 2000, 920, " %", decimals = 1, tip = "Unter 100 %: jede Kopie kleiner (Tunnel, in die Blobs hinein), darüber grösser (aus ihnen heraus)"),
        Param.Slider("rotation", "Drehung", -1800, 1800, 0, "°", decimals = 1),
        Param.Slider("offsetX", "Versatz X", -5000, 5000, 0, " px", decimals = 1, tip = "Wird mit skaliert und gedreht – zusammen mit einer Drehung entstehen Spiralen"),
        Param.Slider("offsetY", "Versatz Y", -5000, 5000, 0, " px", decimals = 1),
        Param.Slider("centerX", "Mitte X", 0, 100, 50, " %", "Um diesen Punkt wird skaliert und gedreht (Blobs: jeder um seine eigene Mitte)"),
        Param.Slider("centerY", "Mitte Y", 0, 100, 50, " %"),
        Param.Choice("edge", "Rand", edges, tip = "Was ausserhalb einer verkleinerten oder verschobenen Kopie liegt (nicht bei Blobs)"),
        Param.Heading("hslHeading", "Farbe je Schritt (HSL)"),
        Param.Slider("hue", "Farbton", -180, 180, 0, "°"),
        Param.Slider("saturation", "Sättigung", -50, 50, 0, " %"),
        Param.Slider("lightness", "Helligkeit", -50, 50, 0, " %"),
        Param.Heading("linesHeading", "Kanten und Blobs"),
        Param.Choice(
            "ground", "Untergrund", listOf("Bild", "Schwarz", "Weiss", "Transparent"),
            tip = "Worauf Kanten oder Blobs und ihre Kopien liegen",
        ),
        Param.Choice(
            "lineColor", "Färbung", listOf("Bildfarbe", "Eine Farbe", "Farbton aus Richtung"),
            tip = "Farbe der Kanten und Blob-Konturen: aus dem Bild (bei Blobs: die Blobfarbe), eine Farbe, " +
                "oder der Farbton aus der Kantenrichtung (bei Blobs: aus der Richtung zur Blobmitte)",
        ),
        Param.Color("color", "Farbe", 0x00FFCC, "Für „Eine Farbe“"),
        Param.Slider("lineWidth", "Linienbreite", 1, 12, 2, " px"),
        Param.Slider("edgeThreshold", "Kantenschwelle", 0, 100, 12, " %", "Nur Kanten: schwächere Kanten werden weggelassen"),
        Param.Slider("edgeGain", "Kantenstärke", 10, 1000, 300, " %", "Nur Kanten: wie deckend die Kanten werden"),
        Param.Slider("abstraction", "Abstraktion", 0, 100, 50, " %", "Nur Blobs: wie grob das Bild in Flächen zerlegt wird"),
        Param.Slider("minBlob", "Min. Blobgrösse", 0, 20000, 400, " px", "Nur Blobs: kleinere Flächen bekommen kein Feedback"),
        Param.Choice("blobDraw", "Blob-Darstellung", listOf("Kontur", "Fläche", "Bildinhalt"), tip = "Nur Blobs: was von jedem Blob kopiert wird"),
        Param.Heading("alphaHeading", "Alphakante"),
        Param.Toggle(
            "alphaFrame", "Alphakante zeichnen", false,
            "Rahmt das Bild (bei Kanten: die Kanten) entlang seiner durchsichtigen Stellen und des Bildrands – jede Kopie bekommt ihn mit. " +
                "Bei Quelle „Alphakante“ immer an; bei Blobs nur am Original",
        ),
        Param.Slider("alphaWidth", "Strichstärke", 5, 2000, 40, " px", decimals = 1),
        Param.Choice(
            "alphaPosition", "Lage", listOf("Aussen", "Mitte", "Innen"), 2,
            "Ob die Kontur ausserhalb, auf oder innerhalb der Kante liegt – am Bildrand ist nur der innere Teil zu sehen",
        ),
        Param.Choice("alphaColorMode", "Färbung", listOf("Eine Farbe", "Bildfarbe", "Farbton aus Richtung"), tip = "Bildfarbe: die Farbe des Bilds an der nächsten Stelle der Kante"),
        Param.Color("alphaColor", "Farbe", 0xFFFFFF),
        Param.Heading("mixHeading", "Überlagerung"),
        Param.Choice("order", "Reihenfolge", listOf("Kopien über dem Bild", "Bild über den Kopien"), tip = "Oben liegt die neueste Kopie – oder das Bild (die Kanten, die Blobs) selbst"),
        Param.Choice("blend", "Mischmodus", BlendMode.entries.map { it.label }),
        Param.Slider("opacity", "Deckkraft der Kopien", 0, 100, 100, " %"),
        Param.Slider("fade", "Abklingen", 0, 50, 0, " %", "Jede weitere Kopie wird um so viel durchsichtiger"),
        Param.Choice(
            "finish", "Zum Schluss darüber", listOf("Nichts", "Erste Kopie", "Original (Bild, Kanten oder Blobs)"),
            tip = "Legt zuletzt noch einmal die erste Kopie oder das Original über alles, im Mischmodus der Kopien – " +
                "etwa damit wachsende Kopien das Motiv nicht zudecken",
        ),
        Param.Slider("finishOpacity", "Deckkraft zum Schluss", 0, 100, 100, " %"),
    )

    override val random = false

    /**
     * Step k maps a point p to c + L^k (p - c) + D[k] (c: the center of the copied thing);
     * [inv] holds L^-k as (a, b, c, d) and [shift] D[k], for k = 0..steps.
     */
    private class Steps(steps: Int, private val scale: Double, rotation: Double, dx: Double, dy: Double) {
        val inv = Array(steps + 1) { DoubleArray(4) }
        val fwd = Array(steps + 1) { DoubleArray(4) }
        val shift = Array(steps + 1) { DoubleArray(2) }
        val invertible = scale > 1e-9

        init {
            val la = scale * cos(rotation)
            val lb = -scale * sin(rotation)
            val lc = scale * sin(rotation)
            val ld = scale * cos(rotation)
            var m = doubleArrayOf(1.0, 0.0, 0.0, 1.0)
            var sx = 0.0
            var sy = 0.0
            for (k in 0..steps) {
                fwd[k] = m
                shift[k][0] = sx
                shift[k][1] = sy
                val det = m[0] * m[3] - m[1] * m[2]
                inv[k] = doubleArrayOf(m[3] / det, -m[1] / det, -m[2] / det, m[0] / det)
                // D[k+1] = L D[k] + d, L^(k+1) = L L^k
                val nx = la * sx + lb * sy + dx
                val ny = lc * sx + ld * sy + dy
                sx = nx
                sy = ny
                m = doubleArrayOf(la * m[0] + lb * m[2], la * m[1] + lb * m[3], lc * m[0] + ld * m[2], lc * m[1] + ld * m[3])
            }
        }

        /** How much copy [k] is enlarged against the original. */
        fun scaleOf(k: Int) = scale.pow(k)

        /** Where output point ([x], [y]) comes from in copy [k] around center ([cx], [cy]); into [q]. */
        fun back(k: Int, x: Double, y: Double, cx: Double, cy: Double, q: DoubleArray) {
            val m = inv[k]
            val px = x - cx - shift[k][0]
            val py = y - cy - shift[k][1]
            q[0] = cx + m[0] * px + m[1] * py
            q[1] = cy + m[2] * px + m[3] * py
        }

        fun forward(k: Int, x: Double, y: Double, cx: Double, cy: Double): Pair<Double, Double> {
            val m = fwd[k]
            val px = x - cx
            val py = y - cy
            return (cx + m[0] * px + m[1] * py + shift[k][0]) to (cy + m[2] * px + m[3] * py + shift[k][1])
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val steps = v["steps"]
        val source = v["source"]
        val cx = w * v["centerX"] / 100.0
        val cy = h * v["centerY"] / 100.0
        val transform = Steps(steps, v["scale"] / 1000.0, Math.toRadians(v["rotation"] / 10.0), v["offsetX"] / 10.0, v["offsetY"] / 10.0)
        val edge = when (v["edge"]) { 1 -> Edge.WRAP; 2 -> Edge.MIRROR; 3 -> Edge.CLAMP; else -> null }
        val blend = BlendMode.entries[v["blend"]]
        val opacity = v["opacity"] / 100f
        val fade = 1 - v["fade"] / 100f
        val hue = v["hue"] / 360.0
        val saturation = v["saturation"] / 100.0
        val lightness = v["lightness"] / 100.0
        val shiftColors = hue != 0.0 || saturation != 0.0 || lightness != 0.0
        val copiesOnTop = v["order"] == 0
        val finish = v["finish"]
        val finishOpacity = v["finishOpacity"] / 100f

        // the layer that is copied, and for blobs everything to find a blob's copies
        val blobs = if (source == SOURCE_BLOBS) BlobCopies(src, v, transform, steps) else null
        val stroke = if (source == SOURCE_ALPHA || v.bool("alphaFrame")) alphaStroke(src, v) else null
        val layer = when (source) {
            SOURCE_EDGES -> edgeLayer(src, v)
            SOURCE_ALPHA -> stroke!!
            SOURCE_BLOBS -> blobs!!.layer
            else -> src
        }.let { base ->
            // the frame lies on the picture (edges, blobs) and goes into every copy with it
            if (stroke == null || source == SOURCE_ALPHA) base
            else Pixels(w, h, IntArray(w * h) { over(base.data[it], stroke.data[it], 1f, BlendMode.NORMAL) })
        }
        val ground: Pixels? = when (if (source == SOURCE_IMAGE) -1 else v["ground"]) {
            0 -> src
            1 -> Pixels(w, h).apply { data.fill(0xFF000000.toInt()) }
            2 -> Pixels(w, h).apply { data.fill(-1) }
            else -> null
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val q = DoubleArray(2)
            val candidates = blobs?.let { b -> Array(steps + 1) { k -> b.inRow(k, y) } }
            fun copy(k: Int, x: Int): Int {
                if (!transform.invertible) return 0
                val c = if (blobs != null) blobs.at(k, x, y, candidates!![k], q)
                else {
                    transform.back(k, x + 0.5, y + 0.5, cx, cy, q)
                    if (edge == null) sampleTransparent(layer, q[0], q[1]) else sampleBilinear(layer, q[0], q[1], edge)
                }
                return if (shiftColors && alpha(c) > 0) shiftHsl(c, hue * k, saturation * k, lightness * k) else c
            }
            fun strength(k: Int) = opacity * fade.pow(k - 1)
            for (x in 0 until w) {
                val i = y * w + x
                var c: Int
                if (source == SOURCE_IMAGE) {
                    // as before: the picture is the ground of its own copies
                    // (with its frame, if there is one)
                    if (copiesOnTop) {
                        c = layer.data[i]
                        for (k in 1..steps) c = over(c, copy(k, x), strength(k), blend)
                    } else {
                        // the furthest copy at the bottom, the picture itself on top
                        c = 0
                        for (k in steps downTo 1) c = over(c, copy(k, x), strength(k), blend)
                        c = over(c, layer.data[i], 1f, blend)
                    }
                } else {
                    c = ground?.data?.get(i) ?: 0
                    if (copiesOnTop) {
                        c = over(c, layer.data[i], 1f, BlendMode.NORMAL)
                        for (k in 1..steps) c = over(c, copy(k, x), strength(k), blend)
                    } else {
                        for (k in steps downTo 1) c = over(c, copy(k, x), strength(k), blend)
                        c = over(c, layer.data[i], 1f, BlendMode.NORMAL)
                    }
                }
                when (finish) {
                    1 -> c = over(c, copy(1, x), finishOpacity, blend)
                    2 -> c = over(c, layer.data[i], finishOpacity, blend)
                }
                out.data[i] = c
            }
        }
        return out
    }

    /**
     * A stroke along the edge between the visible (alpha at least half) and transparent
     * parts of [src], from exact distances to the edge, with soft (antialiased) sides.
     * Beyond the canvas everything counts as transparent, so the picture's border is an
     * edge too.
     */
    private fun alphaStroke(src: Pixels, v: Values): Pixels {
        val w = src.width
        val h = src.height
        // the distances are taken on the canvas with a one pixel transparent border around it
        val pw = w + 2
        val ph = h + 2
        val inside = BooleanArray(pw * ph) { j ->
            val x = j % pw - 1
            val y = j / pw - 1
            x in 0 until w && y in 0 until h && alpha(src.data[y * w + x]) >= 128
        }
        // distance of every outside pixel to the nearest inside pixel, and the other way round
        val (toInside, nearestInside) = distanceTransform(inside, pw, ph)
        val (toOutside, nearestOutside) = distanceTransform(BooleanArray(pw * ph) { !inside[it] }, pw, ph)
        val width = v["alphaWidth"] / 10.0
        // how far the stroke reaches outwards and inwards from the edge
        val (outer, inner) = when (v["alphaPosition"]) {
            1 -> width / 2 to width / 2
            2 -> 0.0 to width
            else -> width to 0.0
        }
        val colorMode = v["alphaColorMode"]
        val single = v["alphaColor"] or 0xFF000000.toInt()
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val j = (y + 1) * pw + x + 1
                // the edge lies halfway between an inside and an outside pixel
                val onInside = inside[j]
                val distance = (if (onInside) toOutside[j] else toInside[j]) - 0.5
                val reach = if (onInside) inner else outer
                val coverage = (reach - distance + 0.5).coerceIn(0.0, 1.0)
                if (coverage <= 0.0) continue
                // the nearest pixel on the other side, for color and direction
                val nearest = if (onInside) nearestOutside[j] else nearestInside[j]
                if (nearest < 0) continue
                // back from the bordered grid (may lie just outside the canvas)
                val ox = nearest % pw - 1
                val oy = nearest / pw - 1
                val other = oy.coerceIn(0, h - 1) * w + ox.coerceIn(0, w - 1)
                val color = when (colorMode) {
                    1 -> {
                        val c = src.data[if (onInside) i else other]
                        c or 0xFF000000.toInt()
                    }
                    2 -> {
                        val dx = (ox - x).toDouble()
                        val dy = (oy - y).toDouble()
                        // the direction pointing out of the picture
                        hueColor(atan2(if (onInside) dy else -dy, if (onInside) dx else -dx) / (2 * PI))
                    }
                    else -> single
                }
                out.data[i] = ((coverage * 255).roundToInt() shl 24) or (color and 0xFFFFFF)
            }
        }
        return out
    }

    /** Hue [t] (turns) as a full, bright color. */
    private fun hueColor(t: Double) = Color.HSBtoRGB((t - floor(t)).toFloat(), 1f, 1f)

    /**
     * The edges of the picture as a transparent layer: Sobel on each color channel (so
     * equally bright but different colors still give an edge), the strongest channel
     * counts. The strength above the threshold, amplified, is the alpha; the lines are
     * widened to the line width.
     */
    private fun edgeLayer(src: Pixels, v: Values): Pixels {
        val w = src.width
        val h = src.height
        // the channels, weighted by opacity so the picture's own edge counts too
        val planes = Array(3) { ch ->
            FloatArray(w * h) { i -> val c = src.data[i]; ((c shr (16 - 8 * ch)) and 0xFF) * alpha(c) / 255f }
        }
        val threshold = v["edgeThreshold"] / 100.0
        val gain = v["edgeGain"] / 100.0
        val mode = v["lineColor"]
        val single = v["color"] or 0xFF000000.toInt()
        val strength = FloatArray(w * h)
        val colors = IntArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var gx = 0f
                var gy = 0f
                var m = 0.0
                for (plane in planes) {
                    fun at(xx: Int, yy: Int) = plane[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
                    val px = (at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)) - (at(x - 1, y - 1) + 2 * at(x - 1, y) + at(x - 1, y + 1))
                    val py = (at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)) - (at(x - 1, y - 1) + 2 * at(x, y - 1) + at(x + 1, y - 1))
                    // 0..1: the strongest possible step is 4 × 255
                    val pm = hypot(px.toDouble(), py.toDouble()) / 1020
                    if (pm > m) { m = pm; gx = px; gy = py }
                }
                strength[y * w + x] = ((m - threshold) * gain / max(1e-6, 1 - threshold)).coerceIn(0.0, 1.0).toFloat()
                colors[y * w + x] = when (mode) {
                    1 -> single
                    2 -> hueColor(atan2(gy.toDouble(), gx.toDouble()) / (2 * PI))
                    else -> src.data[y * w + x]
                }
            }
        }
        // widen: every pixel takes the strongest edge within the line radius
        val r = (v["lineWidth"] - 1) / 2.0
        val reach = r.toInt() + 1
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var best = 0f
                var color = 0
                for (dy in -reach..reach) for (dx in -reach..reach) {
                    if (hypot(dx.toDouble(), dy.toDouble()) > r + 0.5) continue
                    val xx = x + dx
                    val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val s = strength[yy * w + xx]
                    if (s > best) { best = s; color = colors[yy * w + xx] }
                }
                out.data[y * w + x] = ((best * 255).roundToInt() shl 24) or (color and 0xFFFFFF)
            }
        }
        return out
    }

    /**
     * Everything for the blob mode: the blobs (big enough ones only), their centers, the
     * layer they draw themselves as (outline, area or picture content), and per step the
     * area each blob's copy covers, to find the copies that reach a pixel quickly.
     */
    private class BlobCopies(private val src: Pixels, v: Values, private val transform: Steps, steps: Int) {
        private val w = src.width
        private val h = src.height
        private val blobs = Particles.blobify(src, v["abstraction"] / 100.0, 1L)
        private val draw = v["blobDraw"]
        private val colorMode = v["lineColor"]
        private val single = v["color"] or 0xFF000000.toInt()
        private val count = blobs.area.size
        private val big = BooleanArray(count) { blobs.area[it] >= max(1, v["minBlob"]) }
        private val cx = DoubleArray(count) { blobs.center[it] % w + 0.5 }
        private val cy = DoubleArray(count) { blobs.center[it] / w + 0.5 }

        private val lineWidth = v["lineWidth"].toDouble()

        /**
         * Distance of every pixel to the edge of its blob (0 on the edge row), within the
         * blob – two chamfer passes. The canvas edge is no blob edge, only the border to
         * another blob or to transparency. With it an outline of the same width can be
         * drawn in every copy, however much it is scaled.
         */
        private val edgeDistance = FloatArray(w * h) { Float.MAX_VALUE }.also { d ->
            val label = blobs.label
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val b = label[i]
                if (b < 0) continue
                if ((x > 0 && label[i - 1] != b) || (x < w - 1 && label[i + 1] != b) ||
                    (y > 0 && label[i - w] != b) || (y < h - 1 && label[i + w] != b)
                ) d[i] = 0f
            }
            val diagonal = 1.4142f
            fun relax(i: Int, j: Int, cost: Float) {
                if (label[j] == label[i] && d[j] + cost < d[i]) d[i] = d[j] + cost
            }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (label[i] < 0) continue
                if (x > 0) relax(i, i - 1, 1f)
                if (y > 0) {
                    relax(i, i - w, 1f)
                    if (x > 0) relax(i, i - w - 1, diagonal)
                    if (x < w - 1) relax(i, i - w + 1, diagonal)
                }
            }
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (label[i] < 0) continue
                if (x < w - 1) relax(i, i + 1, 1f)
                if (y < h - 1) {
                    relax(i, i + w, 1f)
                    if (x < w - 1) relax(i, i + w + 1, diagonal)
                    if (x > 0) relax(i, i + w - 1, diagonal)
                }
            }
        }

        /** Color of pixel [i] of blob [b] as drawn in a copy enlarged [scale] times (0 = nothing). */
        fun colorAt(i: Int, b: Int, scale: Double = 1.0): Int = when (draw) {
            1 -> blobs.image.data[i]
            2 -> src.data[i]
            // the outline keeps its width on the canvas: thinner in the blob when enlarged
            else -> if (edgeDistance[i] >= max(0.5, lineWidth / scale)) 0 else when (colorMode) {
                1 -> single
                2 -> hueColor(atan2(i / w + 0.5 - cy[b], i % w + 0.5 - cx[b]) / (2 * PI))
                else -> blobs.image.data[i] or 0xFF000000.toInt()
            }
        }

        /** The blobs drawn in place (step 0). */
        val layer = Pixels(w, h, IntArray(w * h) { i -> blobs.label[i].let { b -> if (b >= 0 && big[b]) colorAt(i, b) else 0 } })

        /** Per step and blob, the covered area: minX, minY, maxX, maxY. */
        private val boxes = Array(steps + 1) { k ->
            DoubleArray(count * 4).also { box ->
                for (b in 0 until count) {
                    if (!big[b]) continue
                    var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                    val bx0 = blobs.bounds[b * 4].toDouble(); val by0 = blobs.bounds[b * 4 + 1].toDouble()
                    val bx1 = blobs.bounds[b * 4 + 2] + 1.0; val by1 = blobs.bounds[b * 4 + 3] + 1.0
                    for ((px, py) in listOf(bx0 to by0, bx1 to by0, bx0 to by1, bx1 to by1)) {
                        val (fx, fy) = transform.forward(k, px, py, cx[b], cy[b])
                        x0 = min(x0, fx); y0 = min(y0, fy); x1 = max(x1, fx); y1 = max(y1, fy)
                    }
                    box[b * 4] = x0; box[b * 4 + 1] = y0; box[b * 4 + 2] = x1; box[b * 4 + 3] = y1
                }
            }
        }

        /** The blobs whose copy [k] reaches row [y]. */
        fun inRow(k: Int, y: Int): IntArray {
            val box = boxes[k]
            val yc = y + 0.5
            return (0 until count).filter { big[it] && box[it * 4 + 1] <= yc && box[it * 4 + 3] >= yc }.toIntArray()
        }

        /** Copy [k] at pixel ([x], [y]): the last of the [candidates] whose copy covers it. */
        fun at(k: Int, x: Int, y: Int, candidates: IntArray, q: DoubleArray): Int {
            val box = boxes[k]
            val xc = x + 0.5
            val scale = transform.scaleOf(k)
            var color = 0
            for (b in candidates) {
                if (box[b * 4] > xc || box[b * 4 + 2] < xc) continue
                transform.back(k, xc, y + 0.5, cx[b], cy[b], q)
                val qx = floor(q[0]).toInt()
                val qy = floor(q[1]).toInt()
                if (qx !in 0 until w || qy !in 0 until h) continue
                val i = qy * w + qx
                if (blobs.label[i] != b) continue
                val c = colorAt(i, b, scale)
                if (alpha(c) > 0) color = c
            }
            return color
        }
    }

    /** [top] with [strength] laid over [base] through [blend]; where the base is transparent the top shows as it is. */
    private fun over(base: Int, top: Int, strength: Float, blend: BlendMode): Int {
        val ta = alpha(top) / 255f * strength
        if (ta <= 0f) return base
        val ba = alpha(base) / 255f
        val mixed = if (ba > 0f && blend != BlendMode.NORMAL) lerpArgb(top, blend.apply(base, top), ba) else top
        val oa = ta + ba * (1 - ta)
        fun ch(t: Int, b: Int) = ((t * ta + b * ba * (1 - ta)) / oa + 0.5f).toInt().coerceIn(0, 255)
        return argb((oa * 255 + 0.5f).toInt().coerceIn(0, 255), ch(red(mixed), red(base)), ch(green(mixed), green(base)), ch(blue(mixed), blue(base)))
    }

    /** [c] with hue turned by [dh] (in turns), saturation and lightness moved by [ds] and [dl] (0..1 scale). */
    private fun shiftHsl(c: Int, dh: Double, ds: Double, dl: Double): Int {
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
        val hh = (hue + dh).let { it - floor(it) }
        val ss = (sat + ds).coerceIn(0.0, 1.0)
        val ll = (l + dl).coerceIn(0.0, 1.0)
        val q = if (ll < 0.5) ll * (1 + ss) else ll + ss - ll * ss
        val p = 2 * ll - q
        fun channel(t0: Double): Int {
            val t = t0 - floor(t0)
            val value = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return (value * 255).roundToInt().coerceIn(0, 255)
        }
        return argb(alpha(c), channel(hh + 1.0 / 3), channel(hh), channel(hh - 1.0 / 3))
    }
}
