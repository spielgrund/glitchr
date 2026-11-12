package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimum / maximum, as in After Effects: every pixel takes the brightest (maximum – white areas
 * grow) or darkest (minimum – black areas grow) value around it. Around means within a blob:
 * a square, circle or diamond, or a line or ellipse turned to any angle – then the areas grow
 * in that direction only, smearing into streaks. One after the other, a maximum then a minimum
 * closes small dark gaps, a minimum then a maximum removes small bright specks. Each channel on
 * its own (colors shift where they meet), the brightest or darkest pixel kept whole (colors stay
 * as they are), or only the transparency.
 *
 * Any convex blob is a stack of rows, each a run of pixels: the result is the extreme over the
 * rows of a sliding extreme along each row (worked out once for every run length, van Herk /
 * Gil–Werman), so even large blobs are quick.
 */
object MinMax : Effect("minmax", "Minimum / Maximum", "Grows the white or the black areas, as in After Effects – within a square, circle, diamond or a line or ellipse turned in any direction") {
    private val operations = listOf("Maximum (white grows)", "Minimum (black grows)", "Maximum, then minimum", "Minimum, then maximum")
    private val shapes = listOf("Square", "Circle", "Diamond", "Line", "Ellipse")
    private const val LINE = 3
    private const val ELLIPSE = 4
    private val channels = listOf("Each color channel", "Brightness (keeps the colors)", "Transparency only", "Colors and transparency")

    override val params = listOf(
        Param.Choice(
            "operation", "Operation", operations, 0,
            "Maximum: the brightest around – white areas grow · Minimum: the darkest – black areas grow · Maximum, then minimum: closes small dark gaps · Minimum, then maximum: removes small bright specks",
        ),
        Param.Slider("radius", "Radius", 0, 3000, 60, " px", "How far the areas grow", decimals = 1),
        Param.Choice("shape", "Blob", shapes, 1, "The shape it grows in: square, circle, diamond – or a line or ellipse turned by the angle, so it grows in that direction only, into streaks"),
        Param.Slider("angle", "Angle", -18000, 18000, 9000, "°", "Of the line or ellipse: 90° grows up and down", decimals = 2).shownWhen("shape", LINE, ELLIPSE),
        Param.Slider("aspect", "Width", 1, 100, 30, " %", "Of the ellipse across its direction").shownWhen("shape", ELLIPSE),
        Param.Slider("balance", "Balance", -100, 100, 0, " %", "Grows more one way than the other along the line – 100 %: only forwards, like a smear").shownWhen("shape", LINE, ELLIPSE),
        Param.Choice("channels", "On", channels, 0, "Each color channel on its own (colors shift where they meet) · Brightness: the brightest or darkest pixel taken whole · Only the transparency · Colors and transparency"),
    )

    override val random = false

    /** The blob: for every row offset dy (−reach..reach) the run of x offsets from [x0] to [x1]; an empty run has x1 < x0. */
    private class Blob(val reach: Int, val x0: IntArray, val x1: IntArray)

    private fun blob(v: Values): Blob {
        val r = v["radius"] / 10.0
        val shape = v["shape"]
        val a = Math.toRadians(v["angle"] / 100.0)
        val ca = cos(a)
        val sa = sin(a)
        val balance = v["balance"] / 100.0
        // the blob's points along its direction run from −r·(1 − b) to r·(1 + b) (both ends r at 0 %)
        val back = r * (1 - balance).coerceIn(0.0, 1.0).let { if (balance < 0) 1.0 else it }
        val fwd = r * (1 + balance).coerceIn(0.0, 2.0).let { if (balance > 0) 1.0 else it }
        val minor = if (shape == ELLIPSE) max(0.5, r * v["aspect"] / 100.0) else 0.5
        val reach = ceil(r).toInt()
        val x0 = IntArray(2 * reach + 1) { 1 }
        val x1 = IntArray(2 * reach + 1) { 0 }
        for (j in 0..2 * reach) {
            val dy = (j - reach).toDouble()
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (dxi in -reach..reach) {
                val dx = dxi.toDouble()
                val inside = when (shape) {
                    0 -> true
                    1 -> dx * dx + dy * dy <= r * r + 0.25
                    2 -> abs(dx) + abs(dy) <= r + 0.5
                    else -> {
                        // along the direction (u) and across it (w) – mirrored: a pixel takes from the
                        // blob behind it, so the areas grow forwards
                        val u = -(dx * ca + dy * sa)
                        val w = dx * sa - dy * ca
                        val half = if (u >= 0) fwd else back
                        if (half <= 0) u == 0.0 && abs(w) <= minor
                        else (u / half) * (u / half) + (w / minor) * (w / minor) <= 1.0 + 1e-9 || (abs(u) < 0.5 && abs(w) <= minor)
                    }
                }
                if (inside) { lo = min(lo, dxi); hi = max(hi, dxi) }
            }
            if (lo <= hi) { x0[j] = lo; x1[j] = hi }
        }
        return Blob(reach, x0, x1)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        if (v["radius"] < 5) return src.copy()
        val b = blob(v)
        val mode = v["channels"]
        return when (v["operation"]) {
            0 -> pass(src, b, true, mode)
            1 -> pass(src, b, false, mode)
            2 -> pass(pass(src, b, true, mode), b, false, mode)
            else -> pass(pass(src, b, false, mode), b, true, mode)
        }
    }

    /** One maximum ([max] true) or minimum over [b], in channel [mode]. */
    private fun pass(src: Pixels, b: Blob, max: Boolean, mode: Int): Pixels {
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        when (mode) {
            1 -> {
                // the brightness first, the color behind it: the extreme pixel comes along whole
                val key = IntArray(w * h)
                for (i in key.indices) {
                    val c = src.data[i]
                    val l = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                    // signed order: shifted so a plain comparison works
                    key[i] = ((l shl 24) or (c and 0xFFFFFF)) xor Int.MIN_VALUE
                }
                val r = extreme(key, w, h, b, max)
                for (i in r.indices) {
                    val k = r[i] xor Int.MIN_VALUE
                    out.data[i] = (src.data[i] and 0xFF000000.toInt()) or (k and 0xFFFFFF)
                }
            }
            else -> {
                val shifts = when (mode) {
                    2 -> intArrayOf(24)
                    3 -> intArrayOf(16, 8, 0, 24)
                    else -> intArrayOf(16, 8, 0)
                }
                System.arraycopy(src.data, 0, out.data, 0, src.data.size)
                val ch = IntArray(w * h)
                for (s in shifts) {
                    for (i in ch.indices) ch[i] = src.data[i] ushr s and 0xFF
                    val r = extreme(ch, w, h, b, max)
                    val mask = (0xFF shl s).inv()
                    for (i in r.indices) out.data[i] = (out.data[i] and mask) or (r[i] shl s)
                }
            }
        }
        return out
    }

    /**
     * The maximum (or minimum) of [a] over the blob around every pixel; outside the picture
     * counts as nothing (so the edges don't grow in).
     */
    private fun extreme(a: IntArray, w: Int, h: Int, b: Blob, max: Boolean): IntArray {
        val none = if (max) Int.MIN_VALUE else Int.MAX_VALUE
        val result = IntArray(w * h) { none }
        // the run lengths the blob has, each worked out once over all rows
        val lengths = (0..2 * b.reach).filter { b.x1[it] >= b.x0[it] }.map { b.x1[it] - b.x0[it] + 1 }.distinct()
        val runs = IntArray(w * h)
        // from the left edge to x in every row: for runs that start left of the picture
        val prefix = IntArray(w * h)
        parallelRows(h) { y ->
            var acc = none
            for (x in 0 until w) { acc = pick(acc, a[y * w + x], max); prefix[y * w + x] = acc }
        }
        for (len in lengths) {
            // runs[y·w + x] = extreme of a[x .. x+len−1] in row y (beyond the right edge: nothing)
            parallelRows(h) { y ->
                val row = y * w
                val g = IntArray(w)
                val hh = IntArray(w)
                // van Herk / Gil–Werman: block-wise prefix and suffix extremes
                var start = 0
                while (start < w) {
                    val end = min(w, start + len)
                    var acc = none
                    for (x in start until end) { acc = pick(acc, a[row + x], max); g[x] = acc }
                    acc = none
                    for (x in end - 1 downTo start) { acc = pick(acc, a[row + x], max); hh[x] = acc }
                    start = end
                }
                for (x in 0 until w) {
                    val last = x + len - 1
                    runs[row + x] = if (last < w) pick(hh[x], g[last], max) else hh[x]
                }
            }
            // every row of the blob with this run length
            for (j in 0..2 * b.reach) {
                if (b.x1[j] < b.x0[j] || b.x1[j] - b.x0[j] + 1 != len) continue
                val dy = j - b.reach
                val dx0 = b.x0[j]
                parallelRows(h) { y ->
                    val sy = y + dy
                    if (sy < 0 || sy >= h) return@parallelRows
                    val row = y * w
                    val srow = sy * w
                    for (x in 0 until w) {
                        val sx = x + dx0
                        val value: Int
                        if (sx < 0) {
                            // the run starts left of the picture: only its part inside counts
                            val inside = len + sx
                            if (inside <= 0) continue
                            value = prefix[srow + min(inside, w) - 1]
                        } else if (sx >= w) continue
                        else value = runs[srow + sx]
                        result[row + x] = pick(result[row + x], value, max)
                    }
                }
            }
        }
        // a pixel no run reached (blob all outside): keeps itself
        for (i in result.indices) if (result[i] == none) result[i] = a[i]
        return result
    }

    private fun pick(a: Int, b: Int, max: Boolean) = if (max) (if (a > b) a else b) else (if (a < b) a else b)
}
