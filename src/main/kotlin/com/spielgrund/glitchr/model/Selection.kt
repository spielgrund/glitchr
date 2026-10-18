package com.spielgrund.glitchr.model

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Turns selections (shapes, similar colors) into [MaskPatch]es for a painted mask. */
object Selection {

    /** [shape] in image pixels, anti-aliased, with an optional soft edge of [feather] px. */
    fun shape(shape: Shape, width: Int, height: Int, feather: Int): MaskPatch? {
        val bounds = shape.bounds.apply { grow(feather + 1, feather + 1) }.intersection(Rectangle(0, 0, width, height))
        if (bounds.isEmpty) return null
        val img = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_BYTE_GRAY)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            translate(-bounds.x, -bounds.y)
            color = Color.WHITE
            fill(shape)
            dispose()
        }
        val data = (img.raster.dataBuffer as DataBufferByte).data.copyOf()
        return MaskPatch(bounds, feathered(data, bounds.width, bounds.height, feather))
    }

    /**
     * Magic wand: selects the pixels whose color differs from the one at ([x], [y]) by at
     * most [tolerance] in every channel; only those connected to the start pixel when
     * [contiguous], otherwise everywhere in the image. With [gradients] (connected only) the
     * selection also follows soft gradients, such as light falling across a wall: it grows from
     * pixel to pixel as long as each step is small; the brightness may drift up to three times
     * the tolerance from the start color, the hue only half of it, so it stops where another
     * thing begins even at a soft edge.
     */
    fun similarColor(
        image: Pixels, x: Int, y: Int, tolerance: Int, contiguous: Boolean, feather: Int, gradients: Boolean = false,
    ): MaskPatch? {
        val w = image.width
        val h = image.height
        if (x !in 0 until w || y !in 0 until h) return null
        val seed = image[x, y]
        val sr = red(seed)
        val sg = green(seed)
        val sb = blue(seed)
        fun similar(c: Int) =
            abs(red(c) - sr) <= tolerance && abs(green(c) - sg) <= tolerance && abs(blue(c) - sb) <= tolerance

        val selected = BooleanArray(w * h)
        if (contiguous && gradients) {
            // from neighbour to neighbour: small steps, within a wider reach of the start color
            // light falling across a surface changes the brightness, a different thing changes the hue:
            // brightness may drift far from the start color, the hue only a little; each step stays small
            val step = (2 + tolerance / 16).coerceAtMost(6)
            val brightnessReach = tolerance * 3
            val hueReach = max(6, tolerance / 2)
            fun lum(c: Int) = (red(c) * 3 + green(c) * 6 + blue(c)) / 10
            fun hueDiff(a: Int, b: Int) = max(
                abs((red(a) - green(a)) - (red(b) - green(b))),
                abs((blue(a) - green(a)) - (blue(b) - green(b))),
            )
            fun close(n: Int, c: Int) = abs(lum(n) - lum(c)) <= step && hueDiff(n, c) <= step &&
                abs(lum(n) - lum(seed)) <= brightnessReach && hueDiff(n, seed) <= hueReach
            val queue = IntArray(w * h)
            var head = 0
            var tail = 0
            queue[tail++] = y * w + x
            selected[y * w + x] = true
            while (head < tail) {
                val i = queue[head++]
                val c = image.data[i]
                val px = i % w
                val py = i / w
                fun visit(j: Int) {
                    if (selected[j]) return
                    val n = image.data[j]
                    if (close(n, c)) {
                        selected[j] = true
                        queue[tail++] = j
                    }
                }
                if (px > 0) visit(i - 1)
                if (px < w - 1) visit(i + 1)
                if (py > 0) visit(i - w)
                if (py < h - 1) visit(i + w)
            }
        } else if (contiguous) {
            // scanline fill: fill a whole run of the row, queue the rows above and below
            val stack = ArrayDeque<Int>()
            stack.addLast(y * w + x)
            while (stack.isNotEmpty()) {
                val start = stack.removeLast()
                if (selected[start]) continue
                val row = start / w
                var left = start % w
                var right = left
                while (left > 0 && !selected[row * w + left - 1] && similar(image.data[row * w + left - 1])) left--
                while (right < w - 1 && !selected[row * w + right + 1] && similar(image.data[row * w + right + 1])) right++
                for (px in left..right) selected[row * w + px] = true
                for (ny in intArrayOf(row - 1, row + 1)) {
                    if (ny !in 0 until h) continue
                    var px = left
                    while (px <= right) {
                        val i = ny * w + px
                        if (!selected[i] && similar(image.data[i])) {
                            stack.addLast(i)
                            // skip the rest of this run; the fill handles it
                            while (px <= right && similar(image.data[ny * w + px])) px++
                        } else {
                            px++
                        }
                    }
                }
            }
        } else {
            parallelRows(h) { row -> for (px in 0 until w) selected[row * w + px] = similar(image.data[row * w + px]) }
        }

        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (row in 0 until h) for (px in 0 until w) if (selected[row * w + px]) {
            x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, row); y1 = max(y1, row)
        }
        if (x1 < 0) return null
        val bounds = Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1).apply { grow(feather, feather) }
            .intersection(Rectangle(0, 0, w, h))
        val data = ByteArray(bounds.width * bounds.height)
        for (row in 0 until bounds.height) for (px in 0 until bounds.width) {
            if (selected[(bounds.y + row) * w + bounds.x + px]) data[row * bounds.width + px] = 0xFF.toByte()
        }
        return MaskPatch(bounds, feathered(data, bounds.width, bounds.height, feather))
    }

    /** Soft edge: three box blurs approximate a gaussian with about [feather] px spread. */
    private fun feathered(data: ByteArray, w: Int, h: Int, feather: Int): ByteArray {
        if (feather <= 0) return data
        val r = max(1, feather / 3)
        var values = FloatArray(data.size) { (data[it].toInt() and 0xFF).toFloat() }
        repeat(3) {
            values = boxBlur(values, w, h, r, horizontal = true)
            values = boxBlur(values, w, h, r, horizontal = false)
        }
        return ByteArray(data.size) { (values[it] + 0.5f).toInt().coerceIn(0, 255).toByte() }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean): FloatArray {
        val out = FloatArray(src.size)
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        parallelRows(lines) { l ->
            fun at(i: Int) = src[if (horizontal) l * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + l]
            var sum = 0f
            for (i in -r..r) sum += at(i)
            for (i in 0 until len) {
                out[if (horizontal) l * w + i else i * w + l] = sum / (2 * r + 1)
                sum += at(i + r + 1) - at(i - r)
            }
        }
        return out
    }
}
