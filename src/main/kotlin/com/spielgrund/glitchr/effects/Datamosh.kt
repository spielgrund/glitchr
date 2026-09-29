package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Imitates a video stream whose keyframes are missing: every macroblock gets a motion
 * vector, and over several "frames" each block keeps pulling in pixels from where its
 * vector points, so content smears along in blocky trails.
 */
object Datamosh : Effect("datamosh", "Datamosh", "Makroblöcke werden wie in einem Video ohne Keyframes verschleppt") {
    private val motions = listOf("Fluss", "Zufällig", "Entlang der Helligkeit", "Eine Richtung")

    override val params = listOf(
        Param.Choice("motion", "Bewegung", motions, tip = "Woher die Bewegungsvektoren der Blöcke kommen"),
        Param.Slider("block", "Blockgrösse", 4, 64, 16, " px"),
        Param.Slider("frames", "Frames", 1, 60, 12, tip = "Wie oft die Bewegung angewendet wird; mehr = längere Spuren"),
        Param.Slider("speed", "Geschwindigkeit", 0, 40, 5, " px", "Verschiebung pro Frame"),
        Param.Slider("angle", "Richtung", 0, 359, 0, "°", "Nur für „Eine Richtung“: 0° nach rechts, 90° nach unten"),
        Param.Slider("moving", "Bewegte Blöcke", 0, 100, 70, " %"),
        Param.Slider("residual", "Restbild", 0, 100, 0, " %", "Mischt in jedem Frame etwas Original zurück, wie Korrekturdaten"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val random = Random(seed)
        val block = v["block"]
        val speed = v["speed"].toDouble()
        val w = src.width
        val h = src.height
        val bw = ceil(w / block.toDouble()).toInt()
        val bh = ceil(h / block.toDouble()).toInt()

        val vectors = motionField(src, v["motion"], bw, bh, block, speed, Math.toRadians(v["angle"].toDouble()), random)
        val moving = v["moving"] / 100f
        val dx = IntArray(bw * bh)
        val dy = IntArray(bw * bh)
        for (b in 0 until bw * bh) {
            if (random.nextFloat() >= moving) continue
            dx[b] = vectors[2 * b].roundToInt()
            dy[b] = vectors[2 * b + 1].roundToInt()
        }

        val residual = v["residual"] / 100f
        val current = src.copy()
        repeat(v["frames"]) {
            val prev = current.data.copyOf()
            parallelRows(h) { y ->
                val row = (y / block) * bw
                for (x in 0 until w) {
                    val b = row + x / block
                    val i = y * w + x
                    var c = prev[i]
                    if (dx[b] != 0 || dy[b] != 0) {
                        val sx = (x - dx[b]).coerceIn(0, w - 1)
                        val sy = (y - dy[b]).coerceIn(0, h - 1)
                        c = prev[sy * w + sx]
                    }
                    current.data[i] = if (residual > 0f) lerpArgb(c, src.data[i], residual) else c
                }
            }
        }
        return current
    }

    /** Motion vector (x, y interleaved) for every block. */
    private fun motionField(
        src: Pixels, mode: Int, bw: Int, bh: Int, block: Int, speed: Double, angle: Double, random: Random,
    ): DoubleArray {
        val field = DoubleArray(bw * bh * 2)
        when (mode) {
            0 -> {
                // smooth flow: random directions on a coarse grid, interpolated per block
                val g = 5
                val ga = DoubleArray(g * g) { random.nextDouble(0.0, 2 * PI) }
                for (by in 0 until bh) for (bx in 0 until bw) {
                    val fx = bx.toDouble() / maxOf(1, bw - 1) * (g - 1)
                    val fy = by.toDouble() / maxOf(1, bh - 1) * (g - 1)
                    val x0 = min(fx.toInt(), g - 2)
                    val y0 = min(fy.toInt(), g - 2)
                    val tx = fx - x0
                    val ty = fy - y0
                    var vx = 0.0
                    var vy = 0.0
                    for ((cx, cy, wgt) in listOf(
                        Triple(x0, y0, (1 - tx) * (1 - ty)), Triple(x0 + 1, y0, tx * (1 - ty)),
                        Triple(x0, y0 + 1, (1 - tx) * ty), Triple(x0 + 1, y0 + 1, tx * ty),
                    )) {
                        vx += cos(ga[cy * g + cx]) * wgt
                        vy += sin(ga[cy * g + cx]) * wgt
                    }
                    set(field, by * bw + bx, vx, vy, speed)
                }
            }
            1 -> for (b in 0 until bw * bh) {
                val a = random.nextDouble(0.0, 2 * PI)
                val s = random.nextDouble(0.3, 1.0)
                field[2 * b] = cos(a) * speed * s
                field[2 * b + 1] = sin(a) * speed * s
            }
            2 -> {
                // move along the brightness contours (perpendicular to the gradient of block means)
                val mean = DoubleArray(bw * bh)
                for (by in 0 until bh) for (bx in 0 until bw) {
                    var sum = 0L
                    var n = 0
                    for (y in by * block until min(src.height, (by + 1) * block) step 2)
                        for (x in bx * block until min(src.width, (bx + 1) * block) step 2) {
                            sum += luma(src.data[y * src.width + x])
                            n++
                        }
                    mean[by * bw + bx] = if (n == 0) 0.0 else sum.toDouble() / n
                }
                fun m(x: Int, y: Int) = mean[y.coerceIn(0, bh - 1) * bw + x.coerceIn(0, bw - 1)]
                for (by in 0 until bh) for (bx in 0 until bw) {
                    val gx = m(bx + 1, by) - m(bx - 1, by)
                    val gy = m(bx, by + 1) - m(bx, by - 1)
                    set(field, by * bw + bx, -gy, gx, speed)
                }
            }
            else -> for (b in 0 until bw * bh) {
                field[2 * b] = cos(angle) * speed
                field[2 * b + 1] = sin(angle) * speed
            }
        }
        return field
    }

    /** Stores (vx, vy) scaled to length [speed]; zero vectors stay zero. */
    private fun set(field: DoubleArray, b: Int, vx: Double, vy: Double, speed: Double) {
        val len = hypot(vx, vy)
        if (len < 1e-9) return
        field[2 * b] = vx / len * speed
        field[2 * b + 1] = vy / len * speed
    }
}
