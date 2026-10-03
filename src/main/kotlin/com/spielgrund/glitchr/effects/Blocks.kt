package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.min
import kotlin.random.Random

/** Block size choice shared by the streak effects; the last option picks sizes at random. */
internal val blockOptions = listOf("1 px", "2 px", "4 px", "8 px", "16 px", "32 px", "Random")
private val blockSizes = intArrayOf(1, 2, 4, 8, 16, 32)

/** What a blockable effect produced: new pixels, and which pixels it actually changed. */
internal class Changed(val pixels: Pixels, val changed: BooleanArray?)

/**
 * Runs [op] with big pixels: the image is reduced so each n×n block becomes one pixel
 * (its average color), [op] works on that, and every pixel [op] marks as changed is
 * written back as a solid n×n block. Unchanged areas keep their full resolution.
 * [op] gets the reduced image and n, so it can convert pixel lengths.
 *
 * With the random option the image is cut into bands (horizontal when [horizontalBands])
 * of random thickness, each with its own random block size.
 */
internal fun withBlocks(
    src: Pixels, choice: Int, seed: Long, horizontalBands: Boolean,
    op: (Pixels, Int) -> Changed,
): Pixels {
    if (choice < blockSizes.size) return blocky(src, blockSizes[choice], op)

    val random = Random(seed xor 0x5DEECE66DL)
    val results = HashMap<Int, Pixels>()
    fun result(n: Int) = results.getOrPut(n) { blocky(src, n, op) }

    val w = src.width
    val h = src.height
    val across = if (horizontalBands) h else w
    val out = Pixels(w, h)
    var start = 0
    while (start < across) {
        // bands are multiples of 32 px, so no block is cut in half
        val end = min(across, start + 32 * random.nextInt(1, 9))
        val part = result(blockSizes[random.nextInt(blockSizes.size)])
        if (horizontalBands) {
            System.arraycopy(part.data, start * w, out.data, start * w, (end - start) * w)
        } else {
            for (y in 0 until h) System.arraycopy(part.data, y * w + start, out.data, y * w + start, end - start)
        }
        start = end
    }
    return out
}

private fun blocky(src: Pixels, n: Int, op: (Pixels, Int) -> Changed): Pixels {
    if (n == 1) return op(src, 1).pixels
    val w = src.width
    val h = src.height
    val cw = (w + n - 1) / n
    val ch = (h + n - 1) / n
    val coarse = Pixels(cw, ch)
    parallelRows(ch) { cy ->
        for (cx in 0 until cw) {
            var a = 0; var r = 0; var g = 0; var b = 0; var count = 0
            for (y in cy * n until min(h, cy * n + n)) for (x in cx * n until min(w, cx * n + n)) {
                val c = src.data[y * w + x]
                a += alpha(c); r += red(c); g += green(c); b += blue(c)
                count++
            }
            coarse.data[cy * cw + cx] = argb(a / count, r / count, g / count, b / count)
        }
    }
    val result = op(coarse, n)
    val changed = result.changed
    val out = Pixels(w, h)
    parallelRows(h) { y ->
        val row = (y / n) * cw
        for (x in 0 until w) {
            val cell = row + x / n
            out.data[y * w + x] = if (changed == null || changed[cell]) result.pixels.data[cell] else src.data[y * w + x]
        }
    }
    return out
}
