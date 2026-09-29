package com.spielgrund.glitchr.image

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Seeded 2D noise functions; all return values in about -1..1. */
class Noise(seed: Long) {
    private val perm = IntArray(512)
    private val salt = seed * 0x9E3779B97F4A7C15uL.toLong()

    init {
        val p = IntArray(256) { it }
        p.shuffle(Random(seed))
        for (i in perm.indices) perm[i] = p[i and 255]
    }

    private fun hash(x: Int, y: Int) = perm[perm[x and 255] + (y and 255)]

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun grad(h: Int, x: Double, y: Double) = when (h and 7) {
        0 -> x + y
        1 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x
        5 -> -x
        6 -> y
        else -> -y
    }

    /** Classic gradient (Perlin) noise. */
    fun perlin(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = fade(xf)
        val v = fade(yf)
        val a = lerp(grad(hash(xi, yi), xf, yf), grad(hash(xi + 1, yi), xf - 1, yf), u)
        val b = lerp(grad(hash(xi, yi + 1), xf, yf - 1), grad(hash(xi + 1, yi + 1), xf - 1, yf - 1), u)
        return lerp(a, b, v).coerceIn(-1.0, 1.0)
    }

    /** Smoothly interpolated random values on a grid. */
    fun value(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val u = fade(x - xi)
        val v = fade(y - yi)
        fun at(i: Int, j: Int) = hash(i, j) / 127.5 - 1
        return lerp(lerp(at(xi, yi), at(xi + 1, yi), u), lerp(at(xi, yi + 1), at(xi + 1, yi + 1), u), v)
    }

    /** Several octaves of Perlin noise (fractal Brownian motion). */
    fun fbm(x: Double, y: Double, octaves: Int): Double {
        var sum = 0.0
        var amp = 1.0
        var freq = 1.0
        var norm = 0.0
        repeat(octaves) { o ->
            sum += perlin(x * freq + o * 17.3, y * freq + o * 31.7) * amp
            norm += amp
            amp *= 0.5
            freq *= 2.0
        }
        return sum / norm
    }

    /** Ridged multifractal: sharp crests where the noise crosses zero. */
    fun ridged(x: Double, y: Double, octaves: Int): Double {
        var sum = 0.0
        var amp = 1.0
        var freq = 1.0
        var norm = 0.0
        repeat(octaves) { o ->
            val r = 1 - abs(perlin(x * freq + o * 17.3, y * freq + o * 31.7))
            sum += r * r * amp
            norm += amp
            amp *= 0.5
            freq *= 2.0
        }
        return sum / norm * 2 - 1
    }

    /** Cellular (Worley) noise: distance to the nearest random feature point. */
    fun worley(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        var best = 9.0
        for (j in -1..1) for (i in -1..1) {
            val cx = xi + i
            val cy = yi + j
            val px = cx + hash(cx, cy) / 255.0
            val py = cy + hash(cx + 101, cy + 57) / 255.0
            best = min(best, hypot(x - px, y - py))
        }
        return (min(best, 1.0) / (sqrt(2.0) / 2)).coerceAtMost(1.0) * 2 - 1
    }

    /**
     * Voronoi cells: the same random feature points as [worley], but every point's
     * whole cell gets one flat random value – a pattern of irregular, flat cells.
     */
    fun voronoi(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        var best = 9.0
        var value = 0.0
        for (j in -1..1) for (i in -1..1) {
            val cx = xi + i
            val cy = yi + j
            val px = cx + hash(cx, cy) / 255.0
            val py = cy + hash(cx + 101, cy + 57) / 255.0
            val d = hypot(x - px, y - py)
            if (d < best) {
                best = d
                value = white(cx, cy)
            }
        }
        return value
    }

    /** Independent random value per integer cell (white noise), without the 256-cell period. */
    fun white(x: Int, y: Int): Double {
        var h = x.toLong() * 0x632BE59BD9B4E019L + y.toLong() * 0x7F4A7C15L + salt
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53) * 2 - 1
    }
}
