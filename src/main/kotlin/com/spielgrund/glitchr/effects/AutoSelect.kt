package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Simple picture analysis for quick mask selections – no learned model, but cues that work
 * for most photos. The picture is split into areas (as for Particles). Subject and
 * background: the background spreads from the picture's border into neighbouring areas of
 * similar color (so a wall stays background where it lies in shadow), and on into pieces of
 * practically the same color that the subject cuts off (a wall between legs); the other areas
 * are judged by how much their color differs from the border, how detailed they are, how
 * near they seem (the estimated depth of Z-Map) and how much of the border they touch.
 * Sky: bright, bluish or grey, calm, far areas connected to the top edge. Depth: the Z-map
 * itself. Every kind has its [params], so the result can be steered; every result is a mask
 * 0..1 per pixel.
 */
object AutoSelect {
    enum class Kind(val label: String) {
        SUBJECT("Subject"), BACKGROUND("Background"), SKY("Sky"), DEPTH("Depth (near = full)");

        override fun toString() = label
    }

    private val subjectParams = listOf(
        Param.Slider("coarse", "Area size", 0, 100, 35, " %", "How coarsely the picture is split into areas: finer follows small shapes, coarser is calmer"),
        Param.Slider("spread", "Background spread", 0, 100, 35, " %", "How different a neighbouring area may be to still count as the same background, starting from the picture's border – raise it for walls in light and shadow"),
        Param.Slider("colorWeight", "Color against border", 0, 100, 40, " %", "Areas whose color differs from the border's colors count as subject"),
        Param.Slider("detailWeight", "Detail", 0, 100, 20, " %", "Detailed, sharp areas count as subject (backgrounds are often smooth or blurred)"),
        Param.Slider("depthWeight", "Nearness", 0, 100, 20, " %", "Areas that seem near (estimated depth) count as subject"),
        Param.Slider("borderWeight", "Off the border", 0, 100, 20, " %", "Areas that touch much of the picture's border count as background"),
        Param.Slider("threshold", "Threshold", -100, 100, 0, " %", "Moves the line between subject and background; 0 = found automatically"),
        Param.Slider("edges", "Exact edges", 0, 100, 100, " %", "Lays the edge pixel by pixel onto the picture's real edge, from the colors on both sides; 0 = the rounder edge of the areas"),
    )

    private val skyParams = listOf(
        Param.Slider("coarse", "Area size", 0, 100, 30, " %", "How coarsely the picture is split into areas"),
        Param.Slider("light", "Min. brightness", 0, 100, 30, " %"),
        Param.Slider("blue", "Blue tolerance", 0, 60, 8, tip = "How far blue may fall behind red and green and still count as sky blue"),
        Param.Slider("grey", "Grey up to", 0, 100, 18, " %", "Clouds: colors this pale (and bright) count as sky too"),
        Param.Slider("calm", "Max. detail", 0, 100, 18, tip = "Busier areas are not sky"),
        Param.Slider("far", "Max. nearness", 0, 100, 60, " %", "Nearer areas (estimated depth) are not sky"),
        Param.Slider("edges", "Exact edges", 0, 100, 100, " %", "Lays the edge pixel by pixel onto the picture's real edge; 0 = the rounder edge of the areas"),
    )

    private val depthParams = Depth.params.filter { it !is Param.Heading && it.key != "depthShow" }

    /** The settings of each kind of selection. */
    fun params(kind: Kind): List<Param> = when (kind) {
        Kind.SUBJECT, Kind.BACKGROUND -> subjectParams
        Kind.SKY -> skyParams
        Kind.DEPTH -> depthParams
    }

    fun defaults(kind: Kind): MutableMap<String, Int> = params(kind).associateTo(LinkedHashMap()) { it.key to it.default }

    fun select(src: Pixels, kind: Kind, values: Map<String, Int> = defaults(kind)): FloatArray {
        val v = Values(defaults(kind).apply { putAll(values) })
        return when (kind) {
            Kind.SUBJECT -> subject(src, v)
            Kind.BACKGROUND -> subject(src, v).let { m -> FloatArray(m.size) { 1 - m[it] } }
            Kind.SKY -> sky(src, v)
            Kind.DEPTH -> Depth.estimate(src, ZMap.defaultValues(values))
        }
    }

    /** Per area: its pixel count, mean color, how many border pixels and how much detail. */
    private class Areas(src: Pixels, abstraction: Double) {
        val w = src.width
        val h = src.height
        val blobs = Particles.blobify(src, abstraction, 7L)
        val label = blobs.label
        val count = blobs.area.size
        val size = IntArray(count)
        val r = DoubleArray(count)
        val g = DoubleArray(count)
        val b = DoubleArray(count)
        val border = IntArray(count)
        val top = IntArray(count)
        val detail = DoubleArray(count)
        val depth = DoubleArray(count)

        init {
            val near = Depth.estimate(src, ZMap.defaultValues())
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val a = label[i]
                if (a < 0) continue
                val c = src.data[i]
                size[a]++
                r[a] += red(c).toDouble(); g[a] += green(c).toDouble(); b[a] += blue(c).toDouble()
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border[a]++
                if (y == 0) top[a]++
                val right = src.data[y * w + min(w - 1, x + 1)]
                val below = src.data[min(h - 1, y + 1) * w + x]
                detail[a] += (abs(luma(c) - luma(right)) + abs(luma(c) - luma(below))).toDouble()
                depth[a] += near[i].toDouble()
            }
            for (a in 0 until count) if (size[a] > 0) {
                r[a] /= size[a]; g[a] /= size[a]; b[a] /= size[a]
                detail[a] /= size[a]
                depth[a] /= size[a]
            }
        }

        /** Areas that touch each other. */
        fun neighbours(): Array<HashSet<Int>> {
            val n = Array(count) { HashSet<Int>() }
            for (y in 0 until h) for (x in 0 until w) {
                val a = label[y * w + x]
                if (a < 0) continue
                if (x + 1 < w) label[y * w + x + 1].let { if (it >= 0 && it != a) { n[a] += it; n[it] += a } }
                if (y + 1 < h) label[(y + 1) * w + x].let { if (it >= 0 && it != a) { n[a] += it; n[it] += a } }
            }
            return n
        }

        /**
         * How different two colors are, 0..1 – the hue counts more than the brightness, so
         * light and shadow on the same thing stay close while another thing stands apart.
         */
        fun difference(r1: Double, g1: Double, b1: Double, r2: Double, g2: Double, b2: Double): Double {
            val l1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1
            val l2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2
            val hue = abs((r1 - g1) - (r2 - g2)) + abs((b1 - g1) - (b2 - g2))
            return (hue * 1.5 + abs(l1 - l2) * 0.5) / 255
        }

        fun difference(a: Int, c: Int) = difference(r[a], g[a], b[a], red(c).toDouble(), green(c).toDouble(), blue(c).toDouble())
        fun between(a: Int, o: Int) = difference(r[a], g[a], b[a], r[o], g[o], b[o])

        /** The value of every area spread onto its pixels, with soft edges. */
        fun paint(value: DoubleArray): FloatArray {
            val m = FloatArray(w * h) { i -> label[i].let { if (it < 0) 0f else value[it].toFloat() } }
            // soften the stair-steps of the areas a little
            return Blur.gauss(m, w, h, max(1.0, min(w, h) / 400.0))
        }
    }

    /** The subject: everything the background doesn't reach from the border, and areas that stand out. */
    private fun subject(src: Pixels, v: Values): FloatArray {
        val areas = Areas(src, v["coarse"] / 100.0)
        val w = src.width
        val h = src.height
        // the colors along the picture's border are taken as the background's colors
        val borderPixels = IntArray(2 * (w + h)) { k ->
            when {
                k < w -> src.data[k]
                k < 2 * w -> src.data[(h - 1) * w + (k - w)]
                k < 2 * w + h -> src.data[(k - 2 * w) * w]
                else -> src.data[(k - 2 * w - h) * w + w - 1]
            }
        }
        val palette = dominantColors(Pixels(borderPixels.size, 1, borderPixels), 5, 3L)
        fun toBorder(a: Int) = palette.minOf { areas.difference(a, it) }

        val perimeter = 2.0 * (w + h)
        val maxDetail = (0 until areas.count).filter { areas.size[it] > 0 }.maxOfOrNull { areas.detail[it] }?.coerceAtLeast(1e-6) ?: 1.0
        val weights = doubleArrayOf(v["colorWeight"].toDouble(), v["detailWeight"].toDouble(), v["depthWeight"].toDouble(), v["borderWeight"].toDouble())
        val total = weights.sum().coerceAtLeast(1e-6)
        val score = DoubleArray(areas.count) { a ->
            if (areas.size[a] == 0) return@DoubleArray 0.0
            // touching a bit of the border is fine (a subject cut by the frame), much of it is not
            val onBorder = (areas.border[a] / perimeter * 6).coerceIn(0.0, 1.0)
            (weights[0] * (toBorder(a) * 2).coerceIn(0.0, 1.0) + weights[1] * areas.detail[a] / maxDetail +
                weights[2] * areas.depth[a] + weights[3] * (1 - onBorder)) / total
        }
        // the line between subject and background, found automatically
        val line = otsu(score, areas.size) + v["threshold"] / 100.0 * 0.5

        // the background spreads from its areas at the border into neighbours of similar color,
        // step by step – into a wall in shadow, but not into something that clearly is subject
        val spread = v["spread"] / 100.0 * 0.4
        val background = BooleanArray(areas.count)
        if (spread > 0) {
            val neighbours = areas.neighbours()
            val queue = ArrayDeque<Int>()
            for (a in 0 until areas.count) if (areas.size[a] > 0 && areas.border[a] > 0 && score[a] < line) {
                background[a] = true
                queue += a
            }
            fun grow() {
                while (queue.isNotEmpty()) {
                    val a = queue.removeFirst()
                    for (n in neighbours[a]) {
                        if (background[n] || areas.size[n] == 0) continue
                        if (areas.between(n, a) < spread && score[n] < line + spread) {
                            background[n] = true
                            queue += n
                        }
                    }
                }
            }
            grow()
            // pieces of the same background cut off by the subject (a wall between the legs):
            // areas of practically the same color as a large background area count too, and spread on
            val found = (0 until areas.count).filter { background[it] && areas.size[it] * 200L > w.toLong() * h }
            for (a in 0 until areas.count) {
                if (background[a] || areas.size[a] == 0) continue
                if (found.any { areas.between(a, it) < spread * 0.6 }) {
                    background[a] = true
                    queue += a
                }
            }
            grow()
        }
        val softness = 0.03
        return exact(src, areas, v["coarse"] / 100.0, v["edges"] / 100.0, DoubleArray(areas.count) { a ->
            if (background[a]) 0.0 else ((score[a] - line) / softness + 0.5).coerceIn(0.0, 1.0)
        })
    }

    /** The sky: bright, bluish or grey, calm areas, connected to the top edge through each other. */
    private fun sky(src: Pixels, v: Values): FloatArray {
        val areas = Areas(src, v["coarse"] / 100.0)
        val minLight = v["light"] / 100.0
        val blueTolerance = v["blue"].toDouble()
        val grey = v["grey"] / 100.0
        val calm = v["calm"].toDouble()
        val far = v["far"] / 100.0
        val likely = BooleanArray(areas.count) { a ->
            if (areas.size[a] == 0) return@BooleanArray false
            val r = areas.r[a]
            val g = areas.g[a]
            val b = areas.b[a]
            val light = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            val sat = (max(r, max(g, b)) - min(r, min(g, b))) / 255
            // blue, or grey/white like clouds; not busy; not near
            val skyColor = (b >= r - blueTolerance && b >= g - blueTolerance * 0.75 && light > minLight) ||
                (sat < grey && light > minLight + 0.15)
            skyColor && areas.detail[a] < calm && areas.depth[a] < far
        }
        // from the areas along the top edge through likely neighbours
        val neighbours = areas.neighbours()
        val sky = BooleanArray(areas.count)
        val queue = ArrayDeque<Int>()
        for (a in 0 until areas.count) if (likely[a] && areas.top[a] > 0) { sky[a] = true; queue += a }
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (n in neighbours[a]) if (!sky[n] && likely[n]) { sky[n] = true; queue += n }
        }
        return exact(src, areas, v["coarse"] / 100.0, v["edges"] / 100.0, DoubleArray(areas.count) { if (sky[it]) 1.0 else 0.0 })
    }

    /**
     * The areas' [value]s as a mask whose edge lies exactly on the picture's edge: in a band
     * along the areas' (blurred, rounded) border, every pixel is compared with the color of the
     * selected and of the unselected side nearby and placed between them by which it is
     * closer to – softly, so real soft edges stay soft. [amount] blends with the plain areas.
     */
    private fun exact(src: Pixels, areas: Areas, coarse: Double, amount: Double, value: DoubleArray): FloatArray {
        val plain = areas.paint(value)
        if (amount <= 0) return plain
        val w = src.width
        val h = src.height
        val n = w * h
        val hard = FloatArray(n) { i -> areas.label[i].let { if (it >= 0 && value[it] >= 0.5) 1f else 0f } }
        // as wide as the blur the areas were found with, plus a little
        val band = max(2.0, coarse.pow(1.3) * min(w, h) / 30 + 2)
        val spread = Blur.gauss(hard, w, h, band * 0.6)
        // the sure parts on both sides, and their colors averaged over the neighbourhood
        val sureIn = FloatArray(n) { if (spread[it] > 0.97f) 1f else 0f }
        val sureOut = FloatArray(n) { if (spread[it] < 0.03f) 1f else 0f }
        val reach = band * 3
        fun local(weight: FloatArray, shift: Int): FloatArray {
            val c = FloatArray(n) { i -> ((src.data[i] shr shift) and 0xFF) * weight[i] }
            return Blur.gauss(c, w, h, reach)
        }
        val inW = Blur.gauss(sureIn, w, h, reach)
        val outW = Blur.gauss(sureOut, w, h, reach)
        val inR = local(sureIn, 16); val inG = local(sureIn, 8); val inB = local(sureIn, 0)
        val outR = local(sureOut, 16); val outG = local(sureOut, 8); val outB = local(sureOut, 0)
        return FloatArray(n) { i ->
            val s = spread[i]
            // far from the border the areas decide
            if (s >= 0.97f || s <= 0.03f || inW[i] < 1e-4f || outW[i] < 1e-4f) return@FloatArray plain[i]
            val fr = inR[i] / inW[i]; val fg = inG[i] / inW[i]; val fb = inB[i] / inW[i]
            val br = outR[i] / outW[i]; val bg = outG[i] / outW[i]; val bb = outB[i] / outW[i]
            // the two sides look alike here: the colors can't tell, keep the areas' answer
            val apart = areas.difference(fr.toDouble(), fg.toDouble(), fb.toDouble(), br.toDouble(), bg.toDouble(), bb.toDouble())
            if (apart < 0.06) return@FloatArray plain[i]
            val c = src.data[i]
            val cr = (c shr 16 and 0xFF).toDouble(); val cg = (c shr 8 and 0xFF).toDouble(); val cb = (c and 0xFF).toDouble()
            // which side the pixel is closer to – by hue more than brightness, so lit skin stays skin before a pale wall
            val toIn = areas.difference(cr, cg, cb, fr.toDouble(), fg.toDouble(), fb.toDouble())
            val toOut = areas.difference(cr, cg, cb, br.toDouble(), bg.toDouble(), bb.toDouble())
            val t = (toOut / (toIn + toOut).coerceAtLeast(1e-6)).toFloat()
            // a soft step around the middle between the two colors
            val k = ((t - 0.5f) * 2.2f + 0.5f).coerceIn(0f, 1f)
            val e = k * k * (3 - 2 * k)
            (plain[i] + (e - plain[i]) * amount.toFloat())
        }
    }

    /** The threshold that splits the scores of areas with [weight] > 0 best into two groups (Otsu). */
    private fun otsu(score: DoubleArray, weight: IntArray): Double {
        val order = score.indices.filter { weight[it] > 0 }.sortedBy { score[it] }
        if (order.size < 2) return 0.5
        val total = order.sumOf { weight[it].toDouble() }
        val mean = order.sumOf { score[it] * weight[it] } / total
        var bestT = mean
        var best = -1.0
        var w0 = 0.0
        var s0 = 0.0
        for (k in 0 until order.size - 1) {
            val a = order[k]
            w0 += weight[a]
            s0 += score[a] * weight[a]
            val w1 = total - w0
            if (w0 <= 0 || w1 <= 0) continue
            val m0 = s0 / w0
            val m1 = (mean * total - s0) / w1
            val between = w0 * w1 * (m0 - m1) * (m0 - m1)
            if (between > best) { best = between; bestT = (score[a] + score[order[k + 1]]) / 2 }
        }
        return bestT
    }
}
