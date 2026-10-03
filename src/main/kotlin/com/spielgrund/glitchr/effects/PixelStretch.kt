package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pulls pixels out along a direction. Every run of pixels whose value (brightness,
 * saturation, hue, a channel …) lies between the thresholds keeps its pixels in their
 * order and is stretched by a length:
 *
 * - Overlay: like Pixelbleed, the stretched run lies over the pixels after it; they
 *   are covered and don't start runs of their own.
 * - Push: like Pixelsort, the pixels after the run are pushed along by the stretch;
 *   what is pushed past the end of the line drops out – or moves into the empty canvas.
 */
object PixelStretch : Effect("pixelstretch", "Pixelstretch", "Stretches pixels in a brightness, color or saturation range – over the following pixels or pushing them ahead") {
    override val params = listOf(
        Param.Choice(
            "variant", "Variant", listOf("Overlay", "Push"),
            tip = "Overlay: the stretched pixels lie over the following ones. Push: the following pixels are pushed on by the length",
        ),
        Param.Slider("angle", "Angle", 0, 359, 90, "°", "Direction: 0° to the right, 90° down, 180° to the left, 270° up"),
        Param.Choice("thresholdMode", "Threshold by", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Lower threshold", 0, 255, 160, tip = "Pixels with a smaller value are not stretched; for hue it may lie above the upper one (a range across red)"),
        Param.Slider("upper", "Upper threshold", 0, 255, 255, tip = "Pixels with a larger value are not stretched"),
        Param.Toggle("invert", "Invert range", false, "Stretches the pixels outside the thresholds"),
        Param.Slider("length", "Length", 1, 3000, 80, " px", "Every run gets this many pixels longer; at most the longer side of the canvas", canvasMax = true),
        Param.Slider("jitter", "Length variation", 0, 100, 60, " %", "Shortens the length of every run randomly; 0 % = all equally long"),
        Param.Slider(
            "maxRun", "Max. run", 0, 3000, 1, " px",
            "How many pixels in the range are stretched together at most; 1 = every pixel on its own, 0 = unlimited", canvasMax = true,
        ),
        Param.Choice("block", "Block size", blockOptions, tip = "Stretches big pixels: the stretched runs are built from n×n blocks"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mode = v["thresholdMode"]
        val lower = v["lower"]
        val upper = v["upper"]
        val invert = v.bool("invert")
        val push = v["variant"] == 1
        val jitter = v["jitter"] / 100f
        val angle = v["angle"]
        val a = Math.toRadians(angle.toDouble())
        // fully transparent pixels (outside the picture) are never pulled
        fun selected(c: Int) = alpha(c) != 0 && thresholdSelects(c, mode, lower, upper, invert)

        return withBlocks(src, v["block"], seed, horizontalBands = abs(cos(a)) >= abs(sin(a))) { img, n ->
            val length = max(1, v["length"] / n)
            val maxRun = if (v["maxRun"] == 0) Int.MAX_VALUE else max(1, v["maxRun"] / n)
            val out = Pixels(img.width, img.height)
            val changed = BooleanArray(img.data.size)
            val lines = PixelSort.Lines(img.width, img.height, angle)
            parallelRows(lines.count) { k ->
                val indices = lines.indices(k)
                if (indices.isEmpty()) return@parallelRows
                val line = IntArray(indices.size) { img.data[indices[it]] }
                val result = line.copyOf()
                val moved = BooleanArray(line.size)
                val random = Random(seed + k * 7919L)
                fun extra() = max(0, (length * (1f - random.nextFloat() * jitter)).toInt())
                if (push) pushLine(line, result, moved, ::selected, maxRun, ::extra)
                else coverLine(line, result, moved, ::selected, maxRun, ::extra)
                for (i in indices.indices) {
                    out.data[indices[i]] = result[i]
                    changed[indices[i]] = moved[i]
                }
            }
            Changed(out, changed)
        }
    }

    /** The end of the run starting at [from]: selected pixels, at most [maxRun]. */
    private inline fun runEnd(line: IntArray, from: Int, maxRun: Int, selected: (Int) -> Boolean): Int {
        var end = from
        while (end < line.size && end - from < maxRun && selected(line[end])) end++
        return end
    }

    /** Pixel [j] of the run line[from until to] spread over [total] pixels. */
    private fun stretched(line: IntArray, from: Int, to: Int, total: Int, j: Int) =
        line[from + (j.toLong() * (to - from) / total).toInt()]

    /** Overlay: each run is stretched over the pixels after it; covered pixels start no run. */
    private inline fun coverLine(
        line: IntArray, out: IntArray, moved: BooleanArray, selected: (Int) -> Boolean, maxRun: Int, extra: () -> Int,
    ) {
        var i = 0
        while (i < line.size) {
            if (!selected(line[i])) {
                i++
                continue
            }
            val end = runEnd(line, i, maxRun, selected)
            val total = end - i + extra()
            val stop = min(line.size, i + total)
            for (j in 0 until stop - i) out[i + j] = stretched(line, i, end, total, j)
            moved.fill(true, i, stop)
            i = max(stop, end)
        }
    }

    /** Schieben: each run is stretched and pushes everything after it along. */
    private inline fun pushLine(
        line: IntArray, out: IntArray, moved: BooleanArray, selected: (Int) -> Boolean, maxRun: Int, extra: () -> Int,
    ) {
        var i = 0 // in the original line
        var pos = 0 // in the result
        while (i < line.size && pos < out.size) {
            if (!selected(line[i])) {
                out[pos] = line[i]
                moved[pos] = pos != i
                i++
                pos++
                continue
            }
            val end = runEnd(line, i, maxRun, selected)
            val total = end - i + extra()
            for (j in 0 until total) {
                if (pos >= out.size) break
                out[pos] = stretched(line, i, end, total, j)
                moved[pos] = true
                pos++
            }
            i = end
        }
    }
}
