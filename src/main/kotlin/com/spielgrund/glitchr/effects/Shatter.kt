package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The picture falls apart: it is cut into pieces – glass-like shards, long splinters, square blocks or
 * fine dust – which come loose one after the other over the progress, from a side, from a point, at
 * random or in clouds, and fly off: carried along a direction, scattered, falling or rising, swirled,
 * spinning, shrinking and fading. Where they have gone, the layers below show (or a color, or the
 * picture darkened). A glow can light up the pieces as they come loose, like embers. Assemble plays
 * it backwards: the pieces fly in and put the picture together.
 */
object Shatter : Effect("shatter", "Shatter & dissolve", "The picture breaks into shards, blocks or dust that come loose one after another and fly away – animate the progress") {
    private val shapes = listOf("Shards", "Splinters", "Blocks", "Dust")
    private val starts = listOf("From a side", "From a point", "Random", "Clouds")
    private val behinds = listOf("Transparent (layers below)", "Color", "Darkened original")
    private const val SPLINTERS = 1
    private const val BLOCKS = 2
    private const val DUST = 3

    override val params = listOf(
        Param.Slider("progress", "Progress", 0, 10000, 4000, " %", "0 % the whole picture, 100 % everything gone – animate it", decimals = 2),
        Param.Toggle("assemble", "Assemble", false, "Backwards: the pieces fly in and put the picture together as the progress rises"),
        Param.Heading("piecesHeading", "Pieces"),
        Param.Choice("shape", "Pieces", shapes, 0, "Shards: irregular like broken glass · Splinters: long, along the flying direction · Blocks: squares · Dust: tiny grains, like ash blown away"),
        Param.Slider("size", "Size", 1, 400, 24, " px", "Size of a piece – dust is a tenth of it"),
        Param.Slider("irregular", "Irregularity", 0, 100, 80, " %", "How uneven the shards and splinters are"),
        Param.Heading("orderHeading", "Order"),
        Param.Choice("start", "Comes loose", starts, 0, "From a side: a front running across · From a point: outwards from the handle · Random: anywhere · Clouds: in soft patches"),
        Param.Slider("frontAngle", "Front direction", 0, 359, 180, "°", "Where the front runs to: 0° to the right, 90° upwards").shownWhen("start", 0),
        Param.Slider("centerX", "Point X", -500000, 500000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 2, transform = true).shownWhen("start", 1),
        Param.Slider("centerY", "Point Y", -500000, 500000, 0, " px", decimals = 2, transform = true).shownWhen("start", 1),
        Param.Slider("cloudSize", "Cloud size", 1, 100, 25, " %", "Of the shorter side").shownWhen("start", 3),
        Param.Slider("ragged", "Raggedness", 0, 100, 30, " %", "How unevenly the pieces come loose along the front"),
        Param.Heading("flightHeading", "Flight"),
        Param.Slider("duration", "Flight time", 5, 100, 35, " %", "How long a piece flies until it is gone, of the whole progress"),
        Param.Slider("distance", "Distance", 0, 300, 60, " %", "How far the pieces fly, of the shorter side"),
        Param.Slider("direction", "Direction", 0, 359, 20, "°", "Where they fly: 0° to the right, 90° upwards"),
        Param.Slider("scatter", "Scatter", 0, 100, 40, " %", "Every piece also flies off in its own direction"),
        Param.Slider("gravity", "Gravity", -100, 100, 0, " %", "Falling (+) or rising (−) more and more"),
        Param.Slider("turbulence", "Turbulence", 0, 100, 30, " %", "Swirling on the way"),
        Param.Slider("spin", "Spin", 0, 100, 30, " %", "How much the pieces turn"),
        Param.Slider("shrink", "Shrink", 0, 100, 40, " %", "How much smaller they get on the way"),
        Param.Slider("fade", "Fade", 0, 100, 70, " %", "How early on the way they fade out: 0 % they vanish only at the end"),
        Param.Heading("lookHeading", "Look"),
        Param.Slider("glow", "Glow", 0, 100, 0, " %", "The pieces light up as they come loose, like embers"),
        Param.Color("glowColor", "Glow color", 0xFF7A20),
        Param.Choice("behind", "Behind", behinds, 0, "What shows where the pieces have gone"),
        Param.Color("behindColor", "Color behind", 0x000000).shownWhen("behind", 1),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /** 0..1, the same for the same piece, salt and seed. */
    private fun rnd(i: Int, salt: Int, seed: Long): Double {
        var z = seed * 0x9E3779B97F4A7C15uL.toLong() + i * 0xBF58476D1CE4E5B9uL.toLong() + salt * 0x94D049BB133111EBuL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        z = z xor (z ushr 31)
        return (z ushr 11) * (1.0 / (1L shl 53))
    }

    /** Smooth value noise 0..1 on a grid of [cell] pixels. */
    private fun noise(x: Double, y: Double, cell: Double, seed: Long): Double {
        val gx = x / cell
        val gy = y / cell
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        val fx = gx - ix
        val fy = gy - iy
        fun at(a: Int, b: Int) = rnd(a * 73856093 xor b * 19349663, 7, seed)
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val top = at(ix, iy) + (at(ix + 1, iy) - at(ix, iy)) * sx
        val bottom = at(ix, iy + 1) + (at(ix + 1, iy + 1) - at(ix, iy + 1)) * sx
        return top + (bottom - top) * sy
    }

    /**
     * Cuts the picture into pieces: the piece of every pixel and their number. Shards and splinters are
     * the cells around jittered points (splinters measured stretched across the direction [angle]),
     * blocks and dust squares.
     */
    internal fun pieces(w: Int, h: Int, shape: Int, size: Int, irregular: Double, angle: Double, seed: Long): Pair<IntArray, Int> {
        val label = IntArray(w * h)
        if (shape == BLOCKS || shape == DUST) {
            val s = if (shape == DUST) max(1, (size / 10.0).roundToInt()) else max(1, size)
            val cols = (w + s - 1) / s
            for (y in 0 until h) for (x in 0 until w) label[y * w + x] = y / s * cols + x / s
            return label to cols * ((h + s - 1) / s)
        }
        // in the cells' own frame: along the direction (u) and across it (v); splinters are long along u
        val ca = cos(angle)
        val sa = -sin(angle)
        val stretch = if (shape == SPLINTERS) 4.0 else 1.0
        val s = max(2.0, size.toDouble())
        // the frame's extent: the picture's corners
        var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
        for ((cx, cy) in listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())) {
            val u = (cx * ca + cy * sa) / stretch
            val vv = -cx * sa + cy * ca
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, vv); v1 = max(v1, vv)
        }
        val cols = ceil((u1 - u0) / s).toInt() + 1
        val rows = ceil((v1 - v0) / s).toInt() + 1
        val px = DoubleArray(cols * rows)
        val py = DoubleArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            px[k] = u0 + (i + 0.5 + (rnd(k, 1, seed) - 0.5) * irregular) * s
            py[k] = v0 + (j + 0.5 + (rnd(k, 2, seed) - 0.5) * irregular) * s
        }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val u = (x * ca + y * sa) / stretch
                val vv = -x * sa + y * ca
                val ci = ((u - u0) / s).toInt()
                val cj = ((vv - v0) / s).toInt()
                var best = Double.MAX_VALUE
                var pick = 0
                for (j in max(0, cj - 1)..min(rows - 1, cj + 1)) for (i in max(0, ci - 1)..min(cols - 1, ci + 1)) {
                    val k = j * cols + i
                    val d = (px[k] - u) * (px[k] - u) + (py[k] - vv) * (py[k] - vv)
                    if (d < best) { best = d; pick = k }
                }
                label[y * w + x] = pick
            }
        }
        return label to cols * rows
    }


    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val p = (v["progress"] / 10000.0).let { if (v.bool("assemble")) 1 - it else it }
        if (p <= 0) return src.copy()
        val short = min(w, h).toDouble()
        val flyAngle = Math.toRadians(v["direction"].toDouble())
        val (label, count) = pieces(w, h, v["shape"], v["size"], v["irregular"] / 100.0, flyAngle, seed)

        // every piece: its box and middle
        val minX = IntArray(count) { Int.MAX_VALUE }
        val minY = IntArray(count) { Int.MAX_VALUE }
        val maxX = IntArray(count) { -1 }
        val maxY = IntArray(count) { -1 }
        val sumX = DoubleArray(count)
        val sumY = DoubleArray(count)
        val n = IntArray(count)
        for (y in 0 until h) for (x in 0 until w) {
            val k = label[y * w + x]
            if (x < minX[k]) minX[k] = x
            if (x > maxX[k]) maxX[k] = x
            if (y < minY[k]) minY[k] = y
            if (y > maxY[k]) maxY[k] = y
            sumX[k] += x + 0.5
            sumY[k] += y + 0.5
            n[k]++
        }

        // when every piece comes loose, 0..1
        val start = v["start"]
        val frontAngle = Math.toRadians(v["frontAngle"].toDouble())
        val fx = cos(frontAngle)
        val fy = -sin(frontAngle)
        val corners = listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())
        val along = corners.map { (x, y) -> x * fx + y * fy }
        val alongMin = along.min()
        val alongMax = along.max()
        val pointX = w / 2.0 + v["centerX"] / 100.0
        val pointY = h / 2.0 + v["centerY"] / 100.0
        val farthest = max(1.0, corners.maxOf { (x, y) -> hypot(x - pointX, y - pointY) })
        val cloud = max(2.0, short * v["cloudSize"] / 100.0)
        val ragged = v["ragged"] / 100.0
        val release = DoubleArray(count) { k ->
            if (n[k] == 0) return@DoubleArray 2.0
            val cx = sumX[k] / n[k]
            val cy = sumY[k] / n[k]
            val base = when (start) {
                0 -> (cx * fx + cy * fy - alongMin) / max(1e-9, alongMax - alongMin)
                1 -> hypot(cx - pointX, cy - pointY) / farthest
                2 -> rnd(k, 3, seed)
                else -> ((noise(cx, cy, cloud, seed) * 0.65 + noise(cx, cy, cloud / 2.7, seed + 1) * 0.35) - 0.2) / 0.6
            }.coerceIn(0.0, 1.0)
            ((base + (rnd(k, 4, seed) - 0.5) * ragged * 0.5) * (1 - ragged * 0.1)).coerceIn(0.0, 1.0)
        }
        val travel = v["duration"] / 100.0
        val front = p * (1 + travel)
        val age = DoubleArray(count) { (front - release[it]) / travel }

        // what shows where pieces have gone
        val behind = v["behind"]
        val behindColor = v["behindColor"] or (0xFF shl 24)
        val out = Pixels(w, h)
        for (i in out.data.indices) {
            val c = src.data[i]
            out.data[i] = if (age[label[i]] < 0) c else when (behind) {
                0 -> 0
                1 -> behindColor
                else -> argb(alpha(c), red(c) * 35 / 100, green(c) * 35 / 100, blue(c) * 35 / 100)
            }
        }

        val glow = v["glow"] / 100.0
        val glowColor = v["glowColor"]
        // pieces about to come loose already smoulder
        if (glow > 0) for (i in out.data.indices) {
            val a = age[label[i]]
            if (a < 0 && a > -0.3) out.data[i] = tint(out.data[i], glowColor, glow * (1 + a / 0.3) * 0.6)
        }

        val distance = short * v["distance"] / 100.0
        val dirX = cos(flyAngle)
        val dirY = -sin(flyAngle)
        val scatter = v["scatter"] / 100.0
        val gravity = v["gravity"] / 100.0
        val turbulence = v["turbulence"] / 100.0
        val spin = v["spin"] / 100.0
        val shrink = v["shrink"] / 100.0
        val fadeFrom = 1 - v["fade"] / 100.0

        // the farthest flown first, the ones just loose on top
        val flying = (0 until count).filter { age[it] in 0.0..<1.0 && n[it] > 0 }.sortedByDescending { age[it] }
        for (k in flying) {
            val a = age[k]
            val alphaPiece = if (a <= fadeFrom) 1.0 else 1 - smooth((a - fadeFrom) / max(1e-9, 1 - fadeFrom))
            if (alphaPiece <= 0.003) continue
            val ease = a * a
            val sa = rnd(k, 5, seed) * 2 * PI
            val sd = rnd(k, 6, seed)
            val reach = distance * (0.6 + 0.8 * rnd(k, 7, seed))
            var dx = (dirX + cos(sa) * scatter * 1.2 * sd) * reach * ease
            var dy = (dirY + sin(sa) * scatter * 1.2 * sd) * reach * ease + gravity * distance * ease * a * 1.5
            // swirling across the way
            val wob = sin(a * 9 + rnd(k, 8, seed) * 2 * PI) * turbulence * distance * 0.12 * a
            dx += -dirY * wob
            dy += dirX * wob
            val turn = (rnd(k, 9, seed) * 2 - 1) * spin * 2 * PI * a * 1.5
            val scale = max(0.02, 1 - shrink * a)
            val heat = if (glow > 0) glow * max(0.0, 1 - a / 0.45) else 0.0
            drawPiece(src, label, out, k, sumX[k] / n[k], sumY[k] / n[k], minX[k], minY[k], maxX[k], maxY[k], dx, dy, turn, scale, alphaPiece, heat, glowColor)
        }
        return out
    }

    private fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** [c] lit toward [glow] by [amount] (screen-like, keeps the alpha). */
    private fun tint(c: Int, glow: Int, amount: Double): Int {
        val k = amount.coerceIn(0.0, 1.0)
        fun ch(a: Int, b: Int) = (a + (255 - a) * (b / 255.0) * k).roundToInt().coerceIn(0, 255)
        return argb(alpha(c), ch(red(c), red(glow)), ch(green(c), green(glow)), ch(blue(c), blue(glow)))
    }

    /**
     * Draws piece [k] (middle [cx], [cy], box [x0]..[x1], [y0]..[y1]) moved by [dx], [dy], turned by [turn]
     * and scaled by [scale] around its middle, at [opacity], lit by [heat]: every target pixel looks back
     * to where it comes from in the picture and takes it if that belongs to the piece.
     */
    private fun drawPiece(
        src: Pixels, label: IntArray, out: Pixels, k: Int, cx: Double, cy: Double, x0: Int, y0: Int, x1: Int, y1: Int,
        dx: Double, dy: Double, turn: Double, scale: Double, opacity: Double, heat: Double, glowColor: Int,
    ) {
        val w = src.width
        val h = src.height
        val c = cos(turn)
        val s = sin(turn)
        // the box around the moved piece
        val r = hypot(max(cx - x0, x1 + 1 - cx), max(cy - y0, y1 + 1 - cy)) * scale
        val tx = cx + dx
        val ty = cy + dy
        val bx0 = max(0, floor(tx - r).toInt())
        val bx1 = min(w - 1, ceil(tx + r).toInt())
        val by0 = max(0, floor(ty - r).toInt())
        val by1 = min(h - 1, ceil(ty + r).toInt())
        if (bx0 > bx1 || by0 > by1) return
        val inv = 1 / scale
        for (y in by0..by1) for (x in bx0..bx1) {
            val ox = x + 0.5 - tx
            val oy = y + 0.5 - ty
            val sx = floor(cx + (c * ox + s * oy) * inv).toInt()
            val sy = floor(cy + (-s * ox + c * oy) * inv).toInt()
            if (sx < x0 || sx > x1 || sy < y0 || sy > y1) continue
            val i = sy * w + sx
            if (label[i] != k) continue
            var col = src.data[i]
            if (heat > 0) col = tint(col, glowColor, heat)
            val o = y * w + x
            out.data[o] = over(col, out.data[o], opacity)
        }
    }

    /** [top] at [opacity] over [base], both straight ARGB. */
    private fun over(top: Int, base: Int, opacity: Double): Int {
        val ta = alpha(top) / 255.0 * opacity
        if (ta >= 0.999) return top
        val ba = alpha(base) / 255.0
        val oa = ta + ba * (1 - ta)
        if (oa <= 0) return 0
        fun ch(t: Int, b: Int) = ((t * ta + b * ba * (1 - ta)) / oa).roundToInt().coerceIn(0, 255)
        return argb((oa * 255).roundToInt(), ch(red(top), red(base)), ch(green(top), green(base)), ch(blue(top), blue(base)))
    }
}
