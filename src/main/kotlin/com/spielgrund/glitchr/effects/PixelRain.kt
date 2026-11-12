package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.luma
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pixel rain: single pixels rain down – from the sky above the picture, or coming loose out of the
 * picture itself (Rising pixels the other way round) – and land: on a floor, on the edges in the
 * picture (where the brightness jumps), on the picture's visible pixels (a cut-out to rain on), or
 * fall through. Where one lands it bursts into a little splash of droplets and can stay lying a
 * while. Every path is worked out from the time, so it can be scrubbed.
 */
object PixelRain : Effect("pixelrain", "Pixel rain", "Single pixels raining down onto a floor, the edges in the picture or its visible pixels – splashing where they land; or falling out of the picture – animate the time") {
    private val sources = listOf("From the sky", "Out of the picture")
    private val hits = listOf("Nothing (fall through)", "Floor", "Edges in the picture", "Visible pixels", "Floor and edges", "Floor and visible pixels")
    private val colorings = listOf("From the picture", "Own color", "Two colors")
    private const val OUT_OF_PICTURE = 1
    private const val FLOOR = 1
    private const val EDGES = 2
    private const val VISIBLE = 3
    private const val FLOOR_EDGES = 4
    private const val FLOOR_VISIBLE = 5

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it and it rains", decimals = 3),
        Param.Slider("count", "Pixels", 1, 50000, 1500, tip = "How many are falling at once"),
        Param.Choice("source", "From", sources, 0, "From the sky: they come in from beyond the picture · Out of the picture: pixels come loose where the picture is this bright and fall"),
        Param.Slider("low", "From brightness", 0, 100, 60, " %").shownWhen("source", OUT_OF_PICTURE),
        Param.Slider("high", "To brightness", 0, 100, 100, " %").shownWhen("source", OUT_OF_PICTURE),
        Param.Toggle("holes", "Leave holes", false, "Where a pixel has come loose, the picture is missing while it falls").shownWhen("source", OUT_OF_PICTURE),
        Param.Heading("fallHeading", "Fall"),
        Param.Slider("direction", "Direction", 0, 35900, 9000, "°", "Where they fall: 0° = right, 90° = down, 180° = left", decimals = 2),
        Param.Slider("speed", "Speed", 0, 500, 60, " %", "Of the canvas height per second they start with"),
        Param.Slider("gravity", "Gravity", 0, 500, 150, " %", "How much faster they get, of the canvas height per second, every second"),
        Param.Slider("variation", "Variation", 0, 100, 40, " %", "How different their speeds are"),
        Param.Slider("wind", "Wind", 0, 100, 10, " %", "How much they flutter sideways"),
        Param.Heading("landHeading", "Landing"),
        Param.Choice("hit", "Lands on", hits, 4, "Floor: a line across · Edges: where the brightness jumps, like on the top of things · Visible pixels: on whatever the layer shows (a cut-out to rain on)"),
        Param.Slider("floor", "Floor height", 0, 100, 92, " %", "Where the floor is, from the top").shownWhen("hit", FLOOR, FLOOR_EDGES, FLOOR_VISIBLE),
        Param.Slider("edgeStrength", "Edge strength", 1, 100, 25, " %", "How sharp a brightness jump must be to land on").shownWhen("hit", EDGES, FLOOR_EDGES),
        Param.Slider("stay", "Stays", 0, 1000, 5, " s", "How long one lies where it landed, fading out", decimals = 1),
        Param.Slider("splash", "Splash", 0, 16, 4, tip = "How many droplets spring up where one lands"),
        Param.Slider("splashHeight", "Splash height", 1, 300, 40, " px", "How high the droplets jump"),
        Param.Slider("splashWidth", "Splash width", 0, 180, 110, "°", "How wide they spread: small = straight up"),
        Param.Slider("splashTime", "Splash time", 1, 200, 40, " s", "How long the droplets fly", decimals = 2),
        Param.Heading("lookHeading", "Look"),
        Param.Slider("size", "Size", 50, 2000, 100, " px", decimals = 2),
        Param.Slider("sizeVariation", "Size variation", 0, 100, 0, " %"),
        Param.Toggle("crisp", "Crisp pixels", true, "On the pixel grid with hard edges – off: smooth, sub-pixel exact"),
        Param.Slider("trailCopies", "Trail copies", 0, 60, 0, tip = "Fainter copies behind each one: 0 = no trail"),
        Param.Slider("trailLength", "Trail length", 1, 200, 30, " px", "How far back the trail reaches"),
        Param.Slider("opacity", "Opacity", 1, 100, 100, " %"),
        Param.Choice("coloring", "Color", colorings, 0, "From the picture: from the sky, the color of the picture where it falls; out of the picture, the color it came loose with"),
        Param.Color("color1", "Color", 0xBFE6FF).shownWhen("coloring", 1, 2),
        Param.Color("color2", "Color 2", 0xFFFFFF).shownWhen("coloring", 2),
    )

    /** A random number 0..1 from the numbers given. */
    private fun rnd(a: Int, b: Int, c: Int): Double {
        var h = a * 374761393 + b * 668265263 + c * 1274126177
        h = (h xor (h ushr 16)) * -2048144789
        h = (h xor (h ushr 13)) * -1028477387
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216.0
    }

    /** Where they can land: the floor, the edges and/or the visible pixels. */
    internal fun landing(src: Pixels, v: Values): BooleanArray {
        val w = src.width
        val h = src.height
        val hit = v["hit"]
        val floorY = (h * v["floor"] / 100.0).roundToInt()
        val edges = hit == EDGES || hit == FLOOR_EDGES
        val visible = hit == VISIBLE || hit == FLOOR_VISIBLE
        val floor = hit == FLOOR || hit == FLOOR_EDGES || hit == FLOOR_VISIBLE
        val limit = v["edgeStrength"] / 100.0 * 255 * 0.6
        fun lumaAt(x: Int, y: Int): Double {
            val c = src.data[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            return luma(c) * alpha(c) / 255.0
        }
        return BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w
            (floor && y >= floorY) ||
                (visible && alpha(src.data[i]) > 128) ||
                (edges && run {
                    val gx = lumaAt(x + 1, y) - lumaAt(x - 1, y)
                    val gy = lumaAt(x, y + 1) - lumaAt(x, y - 1)
                    sqrt(gx * gx + gy * gy) / 2 > limit
                })
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val fromPicture = v["source"] == OUT_OF_PICTURE
        val low = v["low"] / 100.0 * 255
        val high = v["high"] / 100.0 * 255
        val starts = if (fromPicture) src.data.indices.filter { val c = src.data[it]; alpha(c) > 128 && luma(c).toDouble() in low..high } else emptyList()
        if (fromPicture && starts.isEmpty()) return src.copy()
        val land = if (v["hit"] == 0) null else landing(src, v)

        val time = v["time"] / 1000.0
        val a = Math.toRadians(v["direction"] / 100.0)
        val dx = cos(a); val dy = sin(a)
        val speed = h * v["speed"] / 100.0
        val gravity = h * v["gravity"] / 100.0
        val variation = v["variation"] / 100.0
        val wind = v["wind"] / 100.0
        val stay = v["stay"] / 10.0
        val splashCount = v["splash"]
        val splashHeight = v["splashHeight"].toDouble()
        val splashWidth = Math.toRadians(v["splashWidth"].toDouble())
        val splashTime = v["splashTime"] / 100.0
        val size = v["size"] / 100.0
        val sizeVariation = v["sizeVariation"] / 100.0
        val crisp = v.bool("crisp")
        val trailCopies = v["trailCopies"]
        val trailLength = v["trailLength"].toDouble()
        val opacity = v["opacity"] / 100.0
        val coloring = v["coloring"]
        // the longest way across the canvas, and how long a fall may take at most
        val across = abs(dx) * w + abs(dy) * h + 4 * size
        fun travelled(t: Double, sp: Double) = sp * t + gravity * t * t / 2
        fun timeFor(dist: Double, sp: Double): Double =
            if (gravity < 1e-9) dist / max(1e-6, sp) else (-sp + sqrt(sp * sp + 2 * gravity * dist)) / gravity

        // the picture as premultiplied floats; the pixels are laid over it
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h); val al = FloatArray(w * h)
        for (i in 0 until w * h) {
            val c = src.data[i]
            val ca = alpha(c) / 255f
            al[i] = ca; r[i] = (c shr 16 and 0xFF) / 255f * ca; g[i] = (c shr 8 and 0xFF) / 255f * ca; b[i] = (c and 0xFF) / 255f * ca
        }
        val touched = BooleanArray(w * h)

        fun square(x: Double, y: Double, side: Double, c: Int, amount: Double) {
            if (amount <= 0.003) return
            var cx = x; var cy = y; var sd = side
            if (crisp) {
                sd = max(1.0, side.roundToInt().toDouble())
                cx = floor(x - sd / 2 + 0.5) + sd / 2; cy = floor(y - sd / 2 + 0.5) + sd / 2
            }
            val half = max(0.05, sd / 2)
            val x0 = cx - half; val x1 = cx + half; val y0 = cy - half; val y1 = cy + half
            val ca = alpha(c) / 255.0 * amount
            val cr = (c shr 16 and 0xFF) / 255.0; val cg = (c shr 8 and 0xFF) / 255.0; val cb = (c and 0xFF) / 255.0
            for (yy in max(0, floor(y0).toInt())..min(h - 1, floor(y1 - 1e-9).toInt())) {
                val ky = min(yy + 1.0, y1) - max(yy.toDouble(), y0)
                if (ky <= 0) continue
                for (xx in max(0, floor(x0).toInt())..min(w - 1, floor(x1 - 1e-9).toInt())) {
                    val kx = min(xx + 1.0, x1) - max(xx.toDouble(), x0)
                    if (kx <= 0) continue
                    val k = (ca * kx * ky).toFloat()
                    val i = yy * w + xx
                    r[i] = (cr * k + r[i] * (1 - k)).toFloat(); g[i] = (cg * k + g[i] * (1 - k)).toFloat(); b[i] = (cb * k + b[i] * (1 - k)).toFloat()
                    al[i] = k + al[i] * (1 - k)
                    touched[i] = true
                }
            }
        }
        fun pictureAt(x: Double, y: Double) = src.data[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]
        fun inside(x: Double, y: Double) = x >= 0 && y >= 0 && x < w && y < h

        // the holes first, so the falling pixels are drawn over them
        val drops = ArrayList<DoubleArray>()
        for (n in 0 until v["count"]) {
            val sp = speed * (1 + (rnd(n, 1, s) - 0.5) * variation)
            // one round: falling across, landing, lying and splashing – then it starts again elsewhere
            val round = timeFor(across, sp) + stay + splashTime + 0.2
            val clock = time / round + rnd(n, 2, s)
            val k = floor(clock).toInt()
            val age = (clock - k) * round
            // where it starts: a point of the picture (or a loose pixel), backed off against the fall to beyond the edge
            val x0: Double; val y0: Double; val from: Int
            if (fromPicture) {
                from = starts[(rnd(n, k, s + 3) * starts.size).toInt().coerceIn(0, starts.size - 1)]
                x0 = from % w + 0.5; y0 = from / w + 0.5
            } else {
                from = -1
                // spread along the edge it comes in over: across the canvas, wide enough for slanted rain
                val px = rnd(n, k, s + 3) * w; val py = rnd(n, k, s + 4) * h
                // back to where its line leaves the canvas against the fall
                var back = Double.MAX_VALUE
                if (dx > 1e-9) back = min(back, px / dx) else if (dx < -1e-9) back = min(back, (px - w) / dx)
                if (dy > 1e-9) back = min(back, py / dy) else if (dy < -1e-9) back = min(back, (py - h) / dy)
                if (back == Double.MAX_VALUE) back = 0.0
                x0 = px - dx * (back + size); y0 = py - dy * (back + size)
            }
            drops.add(doubleArrayOf(n.toDouble(), k.toDouble(), age, sp, x0, y0, from.toDouble()))
            if (fromPicture && v.bool("holes") && age < timeFor(across, sp)) {
                touched[from] = true; r[from] = 0f; g[from] = 0f; b[from] = 0f; al[from] = 0f
            }
        }

        for (d in drops) {
            val n = d[0].toInt(); val k = d[1].toInt(); val age = d[2]; val sp = d[3]; val x0 = d[4]; val y0 = d[5]; val from = d[6].toInt()
            val side = size * (1 + (rnd(n, k, s + 5) - 0.5) * 2 * sizeVariation).coerceAtLeast(0.2)
            val flutterF = 0.5 + rnd(n, k, s + 6) * 1.5
            val flutterP = rnd(n, k, s + 7) * 2 * PI
            fun pos(dist: Double, t: Double): Pair<Double, Double> {
                val side2 = sin(t * 2 * PI * flutterF + flutterP) * wind * h * 0.02 * min(1.0, t)
                return Pair(x0 + dx * dist - dy * side2, y0 + dy * dist + dx * side2)
            }
            // where it lands: walked along its way pixel by pixel; out of the picture only once it has been in the open
            var landAt = Double.MAX_VALUE
            if (land != null) {
                var free = !fromPicture
                var dist = 0.0
                while (dist < across + 2 * size) {
                    val (px, py) = pos(dist, timeFor(dist, sp))
                    if (inside(px, py)) {
                        val i = py.toInt() * w + px.toInt()
                        if (land[i]) { if (free) { landAt = dist; break } } else free = true
                    }
                    dist += 0.75
                }
            }
            val c = when (coloring) {
                1 -> v["color1"] or (0xFF shl 24)
                2 -> (if (rnd(n, k, s + 8) < 0.5) v["color1"] else v["color2"]) or (0xFF shl 24)
                else -> if (from >= 0) src.data[from] else 0
            }
            fun colorAt(x: Double, y: Double) = if (c != 0) c else pictureAt(x, y) or (0xFF shl 24)
            val fall = if (landAt < Double.MAX_VALUE) timeFor(landAt, sp) else timeFor(across + 2 * size, sp)
            if (age < fall) {
                // falling, with its trail
                val dist = travelled(age, sp)
                val (x, y) = pos(dist, age)
                for (j in trailCopies downTo 1) {
                    val back = dist - trailLength * j / trailCopies
                    if (back <= 0) continue
                    val (tx, ty) = pos(back, timeFor(back, sp))
                    square(tx, ty, side, colorAt(tx, ty), opacity * (1 - j / (trailCopies + 1.0)) * 0.7)
                }
                square(x, y, side, colorAt(x, y), opacity)
                continue
            }
            if (landAt == Double.MAX_VALUE) continue
            val since = age - fall
            val (hx, hy) = pos(landAt, fall)
            val hc = colorAt(hx, hy)
            // lying where it landed, fading out
            if (stay > 0 && since < stay) square(hx, hy - dy * side / 2, side, hc, opacity * (1 - (since / stay).let { it * it }))
            // the splash: droplets springing back against the fall, spread to the sides, pulled down again
            if (since < splashTime) {
                val u = since / splashTime
                for (m in 0 until splashCount) {
                    val ang = (rnd(n * 31 + m, k, s + 9) - 0.5) * splashWidth
                    val up = splashHeight * (0.5 + rnd(n * 31 + m, k, s + 10) * 0.7)
                    // straight up by the splash height at the top of its jump, half the time; out sideways meanwhile
                    val lift = 4 * up * u * (1 - u)
                    val outward = up * 1.2 * u
                    val ex = -dx * lift * cos(ang) + (-dy) * outward * sin(ang)
                    val ey = -dy * lift * cos(ang) + dx * outward * sin(ang)
                    square(hx + ex, hy + ey - dy * side / 2, max(1.0, side * 0.6), hc, opacity * (1 - u) * 0.9)
                }
            }
        }

        val out = Pixels(w, h)
        for (i in 0 until w * h) {
            val ca = al[i]
            out.data[i] = if (!touched[i]) src.data[i] else if (ca <= 0f) 0 else {
                fun ch(p: Float) = (p / ca * 255).roundToInt().coerceIn(0, 255)
                ((ca * 255).roundToInt().coerceIn(0, 255) shl 24) or (ch(r[i]) shl 16) or (ch(g[i]) shl 8) or ch(b[i])
            }
        }
        return out
    }
}
