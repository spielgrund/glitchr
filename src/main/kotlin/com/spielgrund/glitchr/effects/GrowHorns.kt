package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Horns for [Grow]: strands grow out of the rim of the start mask, pointing outwards, and
 * curl up towards their tip into spirals (the curvature rises along the strand). Each
 * strand carries the picture's cross-section at its root and drags it along the curve as
 * streaks, like a loaded brush; it tapers and is shaded round. The strands are drawn one
 * after another, each over the ones before, with soft, anti-aliased edges (every stamp is
 * spread bilinearly over the pixels).
 */
internal object GrowHorns {
    private const val STEP = 0.35
    private const val ACROSS = 0.5

    fun apply(src: Pixels, start: BooleanArray, gw: Int, gh: Int, n: Int, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // roots: start cells with a free neighbour
        val rim = (0 until gw * gh).filter { i ->
            if (!start[i]) return@filter false
            val x = i % gw
            val y = i / gw
            (x > 0 && !start[i - 1]) || (x < gw - 1 && !start[i + 1]) || (y > 0 && !start[i - gw]) || (y < gh - 1 && !start[i + gw])
        }
        if (rim.isEmpty()) return src
        val width = v["hornWidth"].toDouble()
        // the outward direction: down the slope of the softened mask
        val soft = Grow.blur(FloatArray(start.size) { if (start[it]) 1f else 0f }, gw, gh, max(1, (width / 2 / n).roundToInt()))
        val rnd = java.util.Random(seed * 7 + 11)
        val dirAngle = Math.toRadians(v["direction"].toDouble())
        val dirStrength = v["directionStrength"] / 100.0 * 2
        val length = v["hornLength"].toDouble()
        val curl = v["hornCurl"] / 100.0 * 3 * 2 * Math.PI
        val power = v["hornSpiral"] / 100.0
        val variation = v["hornVariation"] / 100.0
        val turnMode = v["hornTurn"]
        val out = Pixels(w, h, src.data.copyOf())
        val canvas = Canvas(w, h)
        repeat(v["horns"]) {
            val cell = rim[rnd.nextInt(rim.size)]
            val cx = cell % gw
            val cy = cell / gw
            var nx = -(soft[cy * gw + min(gw - 1, cx + 1)] - soft[cy * gw + max(0, cx - 1)]).toDouble()
            var ny = -(soft[min(gh - 1, cy + 1) * gw + cx] - soft[max(0, cy - 1) * gw + cx]).toDouble()
            val len = hypot(nx, ny)
            if (len < 1e-6) {
                val a = rnd.nextDouble() * 2 * Math.PI
                nx = cos(a); ny = sin(a)
            } else { nx /= len; ny /= len }
            nx += cos(dirAngle) * dirStrength
            ny += sin(dirAngle) * dirStrength
            fun vary() = 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.6
            val sign = when (turnMode) { 1 -> -1.0; 2 -> 1.0; else -> if (rnd.nextBoolean()) 1.0 else -1.0 }
            val horn = Horn(
                x = (cx + rnd.nextDouble()) * n, y = (cy + rnd.nextDouble()) * n, angle = kotlin.math.atan2(ny, nx),
                length = length * vary(), width = width * vary(), turn = curl * vary() * sign,
            )
            draw(src, out, canvas, horn, power, v)
        }
        return out
    }

    private class Horn(val x: Double, val y: Double, val angle: Double, val length: Double, val width: Double, val turn: Double)

    /** Per-strand accumulation buffers over the whole canvas, cleared after each strand within its box. */
    private class Canvas(val w: Int, val h: Int) {
        val a = FloatArray(w * h)
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val weight = FloatArray(w * h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1

        fun splat(px: Double, py: Double, c: Int, light: Double, wgt: Double) {
            val fx = px - 0.5
            val fy = py - 0.5
            val ix = floor(fx).toInt()
            val iy = floor(fy).toInt()
            val tx = fx - ix
            val ty = fy - iy
            for (k in 0..3) {
                val x = ix + (k and 1)
                val y = iy + (k shr 1)
                if (x !in 0 until w || y !in 0 until h) continue
                val f = ((if (k and 1 == 0) 1 - tx else tx) * (if (k shr 1 == 0) 1 - ty else ty) * wgt).toFloat()
                if (f <= 0f) continue
                val i = y * w + x
                a[i] += alpha(c) * f
                r[i] += (red(c) * light).toFloat() * f
                g[i] += (green(c) * light).toFloat() * f
                b[i] += (blue(c) * light).toFloat() * f
                weight[i] += f
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
    }

    /**
     * Walks the strand in small steps; its curvature grows with (s / length)^power, so all
     * of [Horn.turn] is turned, most of it near the tip. At every step the cross-section
     * of the root (scaled to the tapered width, shifted inwards by the texture share) is
     * stamped across the strand.
     */
    private fun draw(src: Pixels, out: Pixels, canvas: Canvas, horn: Horn, power: Double, v: Values) {
        val taper = v["hornTaper"] / 100.0
        val texture = v["hornTexture"] / 100.0
        val shade = v["hornShade"] / 100.0
        val softEdge = max(0.02, v["hornSoft"] / 100.0)
        // the root fades in over this share of the length
        val baseSoft = v["hornBaseSoft"] / 100.0
        val amount = v["amount"] / 100.0
        val steps = max(1, (horn.length / STEP).toInt())
        val t0x = cos(horn.angle)
        val t0y = sin(horn.angle)
        // the root's cross-section runs along its normal
        val n0x = -t0y
        val n0y = t0x
        var x = horn.x
        var y = horn.y
        var angle = horn.angle
        val norm = horn.turn * (power + 1) / horn.length
        for (step in 0..steps) {
            val s = step * STEP
            val f = s / horn.length
            val half = max(0.5, horn.width * (1 - taper * f) / 2)
            val tx = cos(angle)
            val ty = sin(angle)
            val nx = -ty
            val ny = tx
            val across = max(1, (2 * half / ACROSS).toInt())
            val fadeIn = if (baseSoft <= 0 || f >= baseSoft) 1.0 else (f / baseSoft).let { it * it * (3 - 2 * it) }
            for (k in 0..across) {
                val u = k.toDouble() / across * 2 - 1
                val edge = min(1.0, (1 - abs(u)) / softEdge) * fadeIn
                if (edge <= 0) continue
                val o = u * half
                // the root's picture: its cross-section, dragged in from inside by the texture share
                val o0 = u * horn.width / 2
                val sx = horn.x + n0x * o0 - t0x * s * texture
                val sy = horn.y + n0y * o0 - t0y * s * texture
                val c = sampleBilinear(src, sx, sy, Edge.CLAMP)
                val light = 1 - shade * u * u
                // each stamp covers its share of the area, so the weights add up to about 1 per pixel
                canvas.splat(x + nx * o, y + ny * o, c, light, edge * STEP * (2 * half / across))
            }
            angle += norm * f.pow(power) * STEP
            x += tx * STEP
            y += ty * STEP
        }
        // lay the strand over what is there: covered pixels take its (averaged) color
        if (canvas.x1 < 0) return
        val w = canvas.w
        for (py in canvas.y0..canvas.y1) for (px in canvas.x0..canvas.x1) {
            val i = py * w + px
            val wt = canvas.weight[i]
            if (wt <= 0f) continue
            // a little more than 1, so the thinned outer side of tight curls still covers
            val cover = min(1.0, wt * 1.3) * amount
            val sa = canvas.a[i] / wt
            val base = out.data[i]
            val a = cover * sa / 255.0
            fun mix(o: Int, m: Float) = (o + (m / wt - o) * a).roundToInt().coerceIn(0, 255)
            out.data[i] = argb(
                max(alpha(base), (sa * cover).roundToInt()).coerceIn(0, 255),
                mix(red(base), canvas.r[i]), mix(green(base), canvas.g[i]), mix(blue(base), canvas.b[i]),
            )
            canvas.a[i] = 0f; canvas.r[i] = 0f; canvas.g[i] = 0f; canvas.b[i] = 0f; canvas.weight[i] = 0f
        }
        canvas.x0 = w; canvas.y0 = canvas.h; canvas.x1 = -1; canvas.y1 = -1
    }
}
