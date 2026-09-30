package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.parallelRows

/**
 * Exact Euclidean distance of every pixel to the nearest pixel where [feature] is set
 * (0 on them), and that pixel's index (-1 if there is none) – Felzenszwalb &
 * Huttenlocher: a 1D transform down every column, then along every row.
 */
internal fun distanceTransform(feature: BooleanArray, w: Int, h: Int): Pair<DoubleArray, IntArray> {
    val inf = 1e20
    val colDist = DoubleArray(w * h)
    val colNearest = IntArray(w * h)
    parallelRows(w) { x ->
        val f = DoubleArray(h) { if (feature[it * w + x]) 0.0 else inf }
        val d = DoubleArray(h)
        val arg = IntArray(h)
        transform1d(f, h, d, arg)
        for (y in 0 until h) {
            colDist[y * w + x] = d[y]
            colNearest[y * w + x] = arg[y]
        }
    }
    val dist = DoubleArray(w * h)
    val nearest = IntArray(w * h)
    parallelRows(h) { y ->
        val f = DoubleArray(w) { colDist[y * w + it] }
        val d = DoubleArray(w)
        val arg = IntArray(w)
        transform1d(f, w, d, arg)
        for (x in 0 until w) {
            val i = y * w + x
            dist[i] = kotlin.math.sqrt(d[x])
            val ax = arg[x]
            nearest[i] = if (d[x] >= inf / 2) -1 else colNearest[y * w + ax] * w + ax
        }
    }
    return dist to nearest
}

/** 1D squared distance transform of [f] (length [n]) into [d], the minimizing position into [arg]. */
private fun transform1d(f: DoubleArray, n: Int, d: DoubleArray, arg: IntArray) {
    val v = IntArray(n)
    val z = DoubleArray(n + 1)
    var k = 0
    v[0] = 0
    z[0] = Double.NEGATIVE_INFINITY
    z[1] = Double.POSITIVE_INFINITY
    fun intersection(q: Int, p: Int) = ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * q - 2.0 * p)
    for (q in 1 until n) {
        var s = intersection(q, v[k])
        // z[0] is -∞, so k never drops below 0
        while (s <= z[k]) {
            k--
            s = intersection(q, v[k])
        }
        k++
        v[k] = q
        z[k] = s
        z[k + 1] = Double.POSITIVE_INFINITY
    }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val dq = (q - v[k]).toDouble()
        d[q] = dq * dq + f[v[k]]
        arg[q] = v[k]
    }
}
