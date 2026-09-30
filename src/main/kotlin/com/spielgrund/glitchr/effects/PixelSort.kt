package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Sorts runs of pixels along rows or columns. A run is a stretch of pixels whose
 * brightness lies between the two thresholds (or outside, when inverted).
 */
object PixelSort : Effect("pixelsort", "Pixelsort", "Sortiert Pixelstrecken innerhalb eines Helligkeitsbereichs") {
    private val keys = listOf("Helligkeit", "Farbton", "Sättigung", "Rot", "Grün", "Blau")

    override val params = listOf(
        Param.Slider("angle", "Winkel", 0, 359, 0, "°", "Sortierrichtung: 0° nach rechts, 90° nach unten, 180° nach links, 270° nach oben"),
        Param.Choice("key", "Sortieren nach", keys),
        Param.Slider("lower", "Untere Schwelle", 0, 255, 60, tip = "Pixel dunkler als das beenden eine Strecke"),
        Param.Slider("upper", "Obere Schwelle", 0, 255, 220, tip = "Pixel heller als das beenden eine Strecke"),
        Param.Toggle("invert", "Bereich umkehren", false, "Sortiert die Pixel ausserhalb der Schwellen"),
        Param.Slider("maxLength", "Max. Länge", 0, 3000, 0, " px", "0 = unbegrenzt; höchstens die längere Seite der Leinwand", canvasMax = true),
        Param.Slider("jitter", "Längen-Zufall", 0, 100, 0, " %", "Verkürzt die Strecken zufällig"),
        Param.Choice("block", "Blockgrösse", blockOptions, tip = "Sortiert grosse Pixel: die sortierten Strecken werden aus n×n-Blöcken gebaut"),
        Param.Slider("overhang", "Überstand", 0, 3000, 200, " px", "Wie weit eine Strecke, die an den Rand des Bilds stösst, in den leeren Bereich hinaus gezogen wird; höchstens die längere Seite der Leinwand", canvasMax = true),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val key = v["key"]
        val lo = min(v["lower"], v["upper"])
        val hi = max(v["lower"], v["upper"])
        val invert = v.bool("invert")
        val jitter = v["jitter"] / 100f
        val angle = v["angle"]
        val a = Math.toRadians(angle.toDouble())

        return withBlocks(src, v["block"], seed, horizontalBands = abs(cos(a)) >= abs(sin(a))) { img, n ->
            val maxLength = if (v["maxLength"] == 0) 0 else max(1, v["maxLength"] / n)
            val overhang = v["overhang"] / n
            val w = img.width
            val out = Pixels(w, img.height)
            val changed = BooleanArray(w * img.height)
            val lines = Lines(w, img.height, angle)
            parallelRows(lines.count) { k ->
                val indices = lines.indices(k)
                if (indices.isEmpty()) return@parallelRows
                val line = IntArray(indices.size) { img.data[indices[it]] }
                val sorted = BooleanArray(line.size)
                sortLine(line, sorted, key, lo, hi, invert, maxLength, jitter, overhang, Random(seed + k * 7919L))
                for (i in indices.indices) {
                    out.data[indices[i]] = line[i]
                    changed[indices[i]] = sorted[i]
                }
            }
            Changed(out, changed)
        }
    }

    /**
     * Splits a [w]×[h] image into parallel straight lines at [angleDeg]. Lines step one
     * pixel along the main axis and follow the slope on the other axis, so every pixel
     * lies on exactly one line. [indices] lists a line's pixels in sorting direction.
     */
    internal class Lines(private val w: Int, private val h: Int, angleDeg: Int) {
        private val a = Math.toRadians(angleDeg.toDouble())
        private val alongX = abs(cos(a)) >= abs(sin(a))
        private val forward = if (alongX) cos(a) > 0 else sin(a) > 0
        private val mainSize = if (alongX) w else h
        private val crossSize = if (alongX) h else w

        /** Offset on the cross axis for each step on the main axis. */
        private val offset = IntArray(mainSize).also { o ->
            val slope = if (alongX) tan(a) else 1 / tan(a)
            for (i in o.indices) o[i] = (i * slope).roundToInt()
        }
        private val minOffset = offset.min()
        private val maxOffset = offset.max()

        /** Line k starts at cross position k + firstLine. */
        private val firstLine = -maxOffset
        val count = crossSize + maxOffset - minOffset

        fun indices(k: Int): IntArray {
            val base = k + firstLine
            val result = IntArray(mainSize)
            var n = 0
            for (i in 0 until mainSize) {
                val c = base + offset[i]
                if (c < 0 || c >= crossSize) continue
                result[n++] = if (alongX) c * w + i else i * w + c
            }
            val line = result.copyOf(n)
            if (!forward) line.reverse()
            return line
        }
    }

    /** Sorts the runs of [line] in place and marks their positions in [sorted]. */
    private fun sortLine(
        line: IntArray, sorted: BooleanArray, key: Int, lo: Int, hi: Int, invert: Boolean,
        maxLength: Int, jitter: Float, overhang: Int, random: Random,
    ) {
        // fully transparent pixels (outside the picture) never start or continue a run
        fun selected(c: Int) = alpha(c) != 0 && (luma(c) in lo..hi) != invert
        var i = 0
        while (i < line.size) {
            if (!selected(line[i])) {
                i++
                continue
            }
            val limit = if (maxLength == 0) Int.MAX_VALUE
            else max(1, (maxLength * (1f - random.nextFloat() * jitter)).toInt())
            var end = i
            while (end < line.size && end - i < limit && selected(line[end])) end++
            sortRun(line, i, end, key)
            if (end - i > 1) sorted.fill(true, i, end)
            i = end + stretchIntoEmpty(line, sorted, i, end, overhang, random)
        }
    }

    /**
     * If the run line[from until to] is followed by transparent pixels (the edge of the
     * picture), stretches it over up to [overhang] of them. Returns how many were filled.
     */
    private fun stretchIntoEmpty(line: IntArray, sorted: BooleanArray, from: Int, to: Int, overhang: Int, random: Random): Int {
        if (overhang <= 0 || to >= line.size || alpha(line[to]) != 0) return 0
        val limit = max(1, (overhang * (0.5f + 0.5f * random.nextFloat())).toInt())
        var extra = 0
        while (extra < limit && to + extra < line.size && alpha(line[to + extra]) == 0) extra++
        val run = line.copyOfRange(from, to)
        val total = run.size + extra
        for (j in 0 until total) line[from + j] = run[(j.toLong() * run.size / total).toInt()]
        sorted.fill(true, from, to + extra)
        return extra
    }

    /** Sorts line[from until to] ascending by [key]; the pixel value rides along in the low 32 bits. */
    private fun sortRun(line: IntArray, from: Int, to: Int, key: Int) {
        if (to - from < 2) return
        val packed = LongArray(to - from) { k ->
            val c = line[from + k]
            (sortKey(c, key).toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
        }
        packed.sort()
        for (k in packed.indices) line[from + k] = packed[k].toInt()
    }

    private fun sortKey(c: Int, key: Int): Int = when (key) {
        1 -> hue(c)
        2 -> saturation(c)
        3 -> red(c)
        4 -> green(c)
        5 -> blue(c)
        else -> luma(c)
    }

    /** Hue in 0..1535 (six sectors of 256). */
    private fun hue(c: Int): Int {
        val r = red(c)
        val g = green(c)
        val b = blue(c)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        if (d == 0) return 0
        return when (mx) {
            r -> Math.floorMod((g - b) * 256 / d, 1536)
            g -> 512 + (b - r) * 256 / d
            else -> 1024 + (r - g) * 256 / d
        }
    }

    private fun saturation(c: Int): Int {
        val mx = max(red(c), max(green(c), blue(c)))
        val mn = min(red(c), min(green(c), blue(c)))
        return if (mx == 0) 0 else (mx - mn) * 255 / mx
    }
}
