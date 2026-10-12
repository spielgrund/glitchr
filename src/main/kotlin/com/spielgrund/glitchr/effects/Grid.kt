package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Repeats the whole picture in a grid of columns × rows: every cell holds a smaller copy,
 * stretched, fitted or cropped. Cells can have gaps, every second row or column can be
 * offset like bricks, and the copies can be mirrored alternately or at random.
 */
object Grid : Effect("tilegrid", "Grid", "Repeats the picture in a grid of columns and rows") {
    private val fits = listOf("Stretch", "Fit whole picture", "Fill cell")
    private val mirrors = listOf("None", "Every second column", "Every second row", "Both")
    private val gapFills = listOf("Transparent", "Color")

    override val params = listOf(
        Param.Slider("columns", "Columns", 1, 64, 3),
        Param.Slider("rows", "Rows", 1, 64, 3),
        Param.Choice("fit", "Fit", fits, 0, "How the picture goes into a cell: distorted, whole with empty bands, or cut to fill"),
        Param.Slider("gap", "Gap", 0, 500, 0, " px", "Space between the cells"),
        Param.Choice("gapFill", "Gaps", gapFills, 0, "What is in the gaps and empty bands"),
        Param.Color("gapColor", "Gap color", 0x000000),
        Param.Slider("rowOffset", "Row offset", -100, 100, 0, " %", "Every second row shifted by part of a cell, like bricks"),
        Param.Slider("columnOffset", "Column offset", -100, 100, 0, " %", "Every second column shifted by part of a cell"),
        Param.Slider("shiftX", "Shift X", -50000, 50000, 0, " px", "Moves the whole grid; it repeats at the edge", decimals = 1),
        Param.Slider("shiftY", "Shift Y", -50000, 50000, 0, " px", "Moves the whole grid; it repeats at the edge", decimals = 1),
        Param.Choice("mirror", "Mirror", mirrors, 0, "Mirrors every second copy, so neighbours meet seamlessly"),
        Param.Toggle("randomMirror", "Random mirror", false, "Mirrors copies at random, horizontally and vertically"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val cols = v["columns"]
        val rows = v["rows"]
        val cellW = w.toDouble() / cols
        val cellH = h.toDouble() / rows
        val gap = v["gap"].toDouble()
        val cw = cellW - gap
        val ch = cellH - gap
        val empty = if (v["gapFill"] == 1) v["gapColor"] or 0xFF000000.toInt() else 0
        if (cw < 0.5 || ch < 0.5) return Pixels(w, h).apply { data.fill(empty) }

        // source pixels per cell pixel, for each fit
        val stretchX = w / cw
        val stretchY = h / ch
        val (scaleX, scaleY) = when (v["fit"]) {
            1 -> max(stretchX, stretchY).let { it to it }
            2 -> min(stretchX, stretchY).let { it to it }
            else -> stretchX to stretchY
        }
        // shrunk beforehand by whole steps, so small copies are averaged instead of sparkling
        val k = max(1, floor(min(scaleX, scaleY)).toInt())
        val small = if (k > 1) shrink(src, k) else src

        val rowOffset = v["rowOffset"] / 100.0 * cellW
        val columnOffset = v["columnOffset"] / 100.0 * cellH
        val shiftX = v["shiftX"] / 10.0
        val shiftY = v["shiftY"] / 10.0
        val mirror = v["mirror"]
        val randomMirror = v.bool("randomMirror")

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var gx = x + 0.5 - shiftX
                var gy = y + 0.5 - shiftY
                var row = floor(gy / cellH).toInt()
                if (Math.floorMod(row, 2) == 1) gx -= rowOffset
                val col = floor(gx / cellW).toInt()
                if (Math.floorMod(col, 2) == 1) gy -= columnOffset
                row = floor(gy / cellH).toInt()
                // position inside the cell's picture area
                var u = gx - col * cellW - gap / 2
                var t = gy - row * cellH - gap / 2
                if (u < 0 || u >= cw || t < 0 || t >= ch) {
                    out.data[y * w + x] = empty
                    continue
                }
                var flipX = (mirror == 1 || mirror == 3) && Math.floorMod(col, 2) == 1
                var flipY = (mirror == 2 || mirror == 3) && Math.floorMod(row, 2) == 1
                if (randomMirror) {
                    val r = hash(Math.floorMod(col, cols), Math.floorMod(row, rows), seed)
                    flipX = flipX xor (r and 1 == 1)
                    flipY = flipY xor (r and 2 == 2)
                }
                if (flipX) u = cw - u
                if (flipY) t = ch - t
                val sx = (u - cw / 2) * scaleX + w / 2.0
                val sy = (t - ch / 2) * scaleY + h / 2.0
                out.data[y * w + x] = if (sx < 0 || sx >= w || sy < 0 || sy >= h) empty
                else sampleBilinear(small, sx * small.width / w, sy * small.height / h, Edge.CLAMP)
            }
        }
        return out
    }

    /** [src] reduced by the whole factor [k]: each k×k block averaged, colors weighted by their opacity. */
    private fun shrink(src: Pixels, k: Int): Pixels {
        val sw = max(1, src.width / k)
        val sh = max(1, src.height / k)
        val out = Pixels(sw, sh)
        parallelRows(sh) { y ->
            for (x in 0 until sw) {
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (yy in y * k until min(src.height, (y + 1) * k)) for (xx in x * k until min(src.width, (x + 1) * k)) {
                    val c = src.data[yy * src.width + xx]
                    val ca = (c ushr 24).toLong()
                    a += ca
                    r += (c shr 16 and 0xFF) * ca
                    g += (c shr 8 and 0xFF) * ca
                    b += (c and 0xFF) * ca
                    n++
                }
                out.data[y * sw + x] = if (a == 0L) 0 else argb((a / n).toInt(), (r / a).toInt(), (g / a).toInt(), (b / a).toInt())
            }
        }
        return out
    }

    private fun hash(x: Int, y: Int, seed: Long): Int {
        var h = x * 374761393 + y * 668265263 + seed.toInt() * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }
}
