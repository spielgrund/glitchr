package com.spielgrund.glitchr.image

import kotlin.random.Random

/**
 * The [k] most prominent colors of [src]: k-means on a random sample of its visible
 * pixels, most frequent first. Always returns [k] opaque colors.
 */
fun dominantColors(src: Pixels, k: Int, seed: Long): IntArray {
    val random = Random(seed)
    val sample = ArrayList<Int>()
    val tries = minOf(src.data.size, 60_000)
    repeat(tries) {
        val c = src.data[random.nextInt(src.data.size)]
        if (alpha(c) > 127) sample += c
        if (sample.size >= 6000) return@repeat
    }
    if (sample.isEmpty()) return IntArray(k) { argb(255, 128, 128, 128) }

    fun dist(a: Int, r: Float, g: Float, b: Float): Float {
        val dr = red(a) - r
        val dg = green(a) - g
        val db = blue(a) - b
        return dr * dr + dg * dg + db * db
    }

    // k-means++ start: spread the first centers over the color range
    val cr = FloatArray(k)
    val cg = FloatArray(k)
    val cb = FloatArray(k)
    sample[random.nextInt(sample.size)].let { cr[0] = red(it).toFloat(); cg[0] = green(it).toFloat(); cb[0] = blue(it).toFloat() }
    val nearest = FloatArray(sample.size) { Float.MAX_VALUE }
    for (c in 1 until k) {
        var total = 0.0
        for (i in sample.indices) {
            nearest[i] = minOf(nearest[i], dist(sample[i], cr[c - 1], cg[c - 1], cb[c - 1]))
            total += nearest[i]
        }
        var pick = random.nextDouble() * total
        var chosen = sample.last()
        for (i in sample.indices) {
            pick -= nearest[i]
            if (pick <= 0) { chosen = sample[i]; break }
        }
        cr[c] = red(chosen).toFloat(); cg[c] = green(chosen).toFloat(); cb[c] = blue(chosen).toFloat()
    }

    val assign = IntArray(sample.size)
    val counts = IntArray(k)
    repeat(12) {
        val sr = DoubleArray(k); val sg = DoubleArray(k); val sb = DoubleArray(k)
        counts.fill(0)
        for (i in sample.indices) {
            var best = 0
            var bestD = Float.MAX_VALUE
            for (c in 0 until k) {
                val d = dist(sample[i], cr[c], cg[c], cb[c])
                if (d < bestD) { bestD = d; best = c }
            }
            assign[i] = best
            counts[best]++
            sr[best] += red(sample[i]).toDouble(); sg[best] += green(sample[i]).toDouble(); sb[best] += blue(sample[i]).toDouble()
        }
        for (c in 0 until k) if (counts[c] > 0) {
            cr[c] = (sr[c] / counts[c]).toFloat(); cg[c] = (sg[c] / counts[c]).toFloat(); cb[c] = (sb[c] / counts[c]).toFloat()
        }
    }
    return (0 until k).sortedByDescending { counts[it] }
        .map { argb(255, cr[it].toInt(), cg[it].toInt(), cb[it].toInt()) }
        .toIntArray()
}

/** Index of the color in [palette] closest to [c]. */
fun nearestColor(palette: IntArray, c: Int): Int {
    var best = 0
    var bestD = Int.MAX_VALUE
    for (i in palette.indices) {
        val dr = red(palette[i]) - red(c)
        val dg = green(palette[i]) - green(c)
        val db = blue(palette[i]) - blue(c)
        val d = dr * dr + dg * dg + db * db
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}
