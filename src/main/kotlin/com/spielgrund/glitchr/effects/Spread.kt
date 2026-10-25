package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.luma
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What counts as a shape, for effects working on the picture's shapes (see [shapeMask]). */
internal val shapeSources = listOf("Alpha (else brightness)", "Bright areas", "Dark areas")

/**
 * How much every pixel belongs to a shape, 0..1: by [source] (see [shapeSources]) its alpha –
 * the bright areas if the picture has no transparency – or the areas brighter or darker than
 * [threshold] (0..255).
 */
internal fun shapeMask(src: Pixels, source: Int, threshold: Double): FloatArray {
    val hasAlpha = src.data.any { alpha(it) < 250 }
    val s = if (source == 0 && !hasAlpha) 1 else source
    return FloatArray(src.data.size) {
        val c = src.data[it]
        when (s) {
            0 -> alpha(c) / 255f
            1 -> if (luma(c) >= threshold) alpha(c) / 255f else 0f
            else -> if (luma(c) < threshold) alpha(c) / 255f else 0f
        }
    }
}

/** A min-heap of pixel indices by time, for [spreadTimes]; entries are never updated, only added again. */
private class TimeHeap(capacity: Int) {
    private var keys = FloatArray(max(16, capacity))
    private var items = IntArray(max(16, capacity))
    var size = 0
        private set

    /** The time of the item [pop] returned last. */
    var lastKey = 0f
        private set

    fun push(key: Float, item: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            items = items.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val parent = (i - 1) / 2
            if (keys[parent] <= key) break
            keys[i] = keys[parent]; items[i] = items[parent]
            i = parent
        }
        keys[i] = key; items[i] = item
    }

    fun pop(): Int {
        val top = items[0]
        lastKey = keys[0]
        size--
        if (size > 0) {
            val key = keys[size]
            val item = items[size]
            var i = 0
            while (true) {
                var child = i * 2 + 1
                if (child >= size) break
                if (child + 1 < size && keys[child + 1] < keys[child]) child++
                if (keys[child] >= key) break
                keys[i] = keys[child]; items[i] = items[child]
                i = child
            }
            keys[i] = key; items[i] = item
        }
        return top
    }
}

// the 16 neighbours: straight, diagonal and knight's moves – a front spreading over them is nearly round
private val NX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 2, 2, -2, -2)
private val NY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1, 2, -2, 2, -2, 1, -1, 1, -1)
private val NLEN = FloatArray(16) { sqrt((NX[it] * NX[it] + NY[it] * NY[it]).toFloat()) }

/**
 * When a front spreading from [seeds] (pixel indices) reaches every pixel of a w×h grid, where
 * passing a pixel takes [cost] per pixel of way (infinite: a wall). Dijkstra over 16 neighbours;
 * diagonal and knight's moves also pay for the pixels they cut across, so they don't slip through
 * walls. Unreached pixels stay infinite.
 */
internal fun spreadTimes(cost: FloatArray, w: Int, h: Int, seeds: IntArray): FloatArray {
    val time = FloatArray(w * h) { Float.POSITIVE_INFINITY }
    val done = BooleanArray(w * h)
    val heap = TimeHeap(w * h / 4)
    for (s in seeds) if (s in time.indices && time[s] > 0f) { time[s] = 0f; heap.push(0f, s) }
    while (heap.size > 0) {
        val i = heap.pop()
        if (done[i]) continue
        done[i] = true
        val t = heap.lastKey
        val x = i % w
        val y = i / w
        for (k in 0 until 16) {
            val nx = x + NX[k]
            val ny = y + NY[k]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val j = ny * w + nx
            if (done[j]) continue
            var c = cost[j]
            if (k >= 4) {
                // what the move cuts across: for a diagonal the easier of the two pixels beside it, for a knight's move both it passes
                c = if (k < 8) (c * 2 + min(cost[y * w + nx], cost[ny * w + x])) / 3
                else {
                    val mx = x + NX[k] / 2
                    val my = y + NY[k] / 2
                    val ox = if (NX[k] / 2 == 0) nx else mx
                    val oy = if (NY[k] / 2 == 0) ny else my
                    (c * 2 + cost[my * w + mx] + cost[oy * w + ox]) / 4
                }
            }
            if (c.isInfinite()) continue
            val nt = t + NLEN[k] * c
            if (nt < time[j]) {
                time[j] = nt
                heap.push(nt, j)
            }
        }
    }
    return time
}

/**
 * [spreadTimes] on a grid shrunk so its longer side is at most [maxSide] (a cell is a wall when
 * most of it is), scaled back up and in full-size pixels: fronts are smooth anyway, and it is
 * many times faster.
 */
internal fun spreadTimesScaled(cost: FloatArray, w: Int, h: Int, seeds: IntArray, maxSide: Int = 640): FloatArray {
    val f = max(1, ceil(max(w, h) / maxSide.toDouble()).toInt())
    if (f == 1) return spreadTimes(cost, w, h, seeds)
    val lw = (w + f - 1) / f
    val lh = (h + f - 1) / f
    val low = FloatArray(lw * lh)
    for (cy in 0 until lh) for (cx in 0 until lw) {
        var sum = 0.0
        var open = 0
        var all = 0
        for (y in cy * f until min(h, cy * f + f)) for (x in cx * f until min(w, cx * f + f)) {
            val c = cost[y * w + x]
            all++
            if (c.isFinite()) { sum += c; open++ }
        }
        low[cy * lw + cx] = if (open * 2 < all) Float.POSITIVE_INFINITY else (sum / open).toFloat()
    }
    val lowSeeds = seeds.filter { it in cost.indices }.map { (it / w / f) * lw + (it % w) / f }.distinct().toIntArray()
    val lt = spreadTimes(low, lw, lh, lowSeeds)
    // bilinear, over the reached cells only
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val fy = ((y + 0.5) / f - 0.5).coerceIn(0.0, lh - 1.0)
        val y0 = fy.toInt()
        val y1 = min(lh - 1, y0 + 1)
        val ty = fy - y0
        for (x in 0 until w) {
            val fx = ((x + 0.5) / f - 0.5).coerceIn(0.0, lw - 1.0)
            val x0 = fx.toInt()
            val x1 = min(lw - 1, x0 + 1)
            val tx = fx - x0
            var sum = 0.0
            var weight = 0.0
            fun add(i: Int, wt: Double) {
                val t = lt[i]
                if (t.isFinite() && wt > 0) { sum += t * wt; weight += wt }
            }
            add(y0 * lw + x0, (1 - tx) * (1 - ty))
            add(y0 * lw + x1, tx * (1 - ty))
            add(y1 * lw + x0, (1 - tx) * ty)
            add(y1 * lw + x1, tx * ty)
            out[y * w + x] = if (weight > 0) (sum / weight * f).toFloat() else Float.POSITIVE_INFINITY
        }
    }
    return out
}

/** The connected areas (4-neighbours) of [inside]: a number for every pixel (−1 outside) and how many there are. */
internal fun labelAreas(inside: BooleanArray, w: Int, h: Int): Pair<IntArray, Int> {
    val label = IntArray(w * h) { -1 }
    val stack = IntArray(w * h)
    var count = 0
    for (start in inside.indices) {
        if (!inside[start] || label[start] >= 0) continue
        var top = 0
        stack[top++] = start
        label[start] = count
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (inside[j] && label[j] < 0) { label[j] = count; stack[top++] = j }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        count++
    }
    return label to count
}

/**
 * Remembers the last result of a slow computation by its key, so animating a setting that
 * comes after it (a progress, a pulse) doesn't repeat it every frame.
 */
internal class LastResult<T> {
    private var lastKey: Any? = null
    private var last: T? = null

    @Synchronized
    fun get(key: Any, make: () -> T): T {
        if (key == lastKey) @Suppress("UNCHECKED_CAST") return last as T
        val value = make()
        lastKey = key
        last = value
        return value
    }
}

internal fun smoothstep(e0: Double, e1: Double, x: Double): Double {
    val k = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
    return k * k * (3 - 2 * k)
}
