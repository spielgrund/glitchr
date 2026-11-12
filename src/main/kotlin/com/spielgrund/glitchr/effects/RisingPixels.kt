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
 * Rising pixels: single pixels come loose from an area of the picture – its visible part (a
 * cut-out), a rectangle, an ellipse or a brightness range – and fly up, like Fireflies but plain
 * pixels without light. **Endless**: they keep coming, each starting again from another spot once
 * gone (the picture stays, or leaves a hole where one is missing). **Dissolve**: every pixel of the
 * area leaves once, at random or in order from the top, the bottom or the edge, until the area is
 * empty. Animate the time.
 */
object RisingPixels : Effect("risingpixels", "Rising pixels", "Single pixels come loose from an area of the picture and fly up – like Fireflies, but plain pixels without light – animate the time") {
    private val areas = listOf("Visible pixels", "Rectangle", "Ellipse", "Brightness range")
    private val orders = listOf("Random", "From the top", "From the bottom", "From the edge")
    private const val RECT = 1
    private const val ELLIPSE = 2
    private const val BRIGHTNESS = 3

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Moves them: animate it and they rise", decimals = 3),
        Param.Choice("mode", "Mode", listOf("Endless", "Dissolve"), 0, "Endless: they keep coming, each starting again elsewhere once gone · Dissolve: every pixel of the area leaves once, until the area is empty"),
        Param.Slider("count", "Pixels", 1, 50000, 2000, tip = "How many fly at once").shownWhen("mode", 0),
        Param.Toggle("holes", "Leave holes", false, "Where a pixel is in flight, the picture is transparent – off: copies fly off and the picture stays").shownWhen("mode", 0),
        Param.Slider("dissolveTime", "Dissolve time", 1, 6000, 30, " s", "How long until the last pixel has come loose", decimals = 1).shownWhen("mode", 1),
        Param.Choice("order", "Order", orders, 0, "Which pixels leave first").shownWhen("mode", 1),
        Param.Slider("ragged", "Raggedness", 0, 100, 30, " %", "How unevenly they come loose along the order").shownWhen("mode", 1),
        Param.Slider("share", "Flying", 1, 100, 100, " %", "How many of the leaving pixels fly visibly – the rest simply vanish").shownWhen("mode", 1),
        Param.Heading("areaHeading", "Area"),
        Param.Choice("area", "From", areas, 0, "Visible pixels: wherever the layer is not transparent, e.g. a cut-out · Rectangle or Ellipse: a part of the picture, draggable with the handle · Brightness range: where the picture is that bright"),
        Param.Slider("centerX", "Area X", -20000, 20000, 0, " %", "From the middle of the canvas: 100 % = the right edge – can also be dragged with the handle in the picture", decimals = 2).shownWhen("area", RECT, ELLIPSE),
        Param.Slider("centerY", "Area Y", -20000, 20000, 0, " %", "100 % = the bottom edge", decimals = 2).shownWhen("area", RECT, ELLIPSE),
        Param.Slider("areaWidth", "Area width", 1, 20000, 5000, " %", "Of the canvas width", decimals = 2).shownWhen("area", RECT, ELLIPSE),
        Param.Slider("areaHeight", "Area height", 1, 20000, 5000, " %", "Of the canvas height", decimals = 2).shownWhen("area", RECT, ELLIPSE),
        Param.Slider("low", "From brightness", 0, 100, 60, " %").shownWhen("area", BRIGHTNESS),
        Param.Slider("high", "To brightness", 0, 100, 100, " %").shownWhen("area", BRIGHTNESS),
        Param.Toggle("edgeOnly", "Only from the edge", false, "They come loose only near the area's edge, like a crumbling outline").shownWhen("mode", 0),
        Param.Slider("edgeWidth", "Edge width", 1, 500, 4, " px").shownWhen("edgeOnly", 1),
        Param.Heading("motionHeading", "Motion"),
        Param.Slider("flightTime", "Flight time", 1, 600, 20, " s", "How long one flies until it is gone", decimals = 1),
        Param.Slider("rise", "Distance", 100, 20000, 3000, " %", "How far one flies in its flight time, of the canvas height", decimals = 2),
        Param.Slider("direction", "Direction", 0, 35900, 27000, "°", "Where they fly: 0° = right, 90° = down, 180° = left, 270° = up", decimals = 2),
        Param.Slider("spread", "Spread", 0, 36000, 1500, "°", "How far each one strays from the direction: 360° = in all directions", decimals = 2),
        Param.Slider("accelerate", "Accelerate", 0, 100, 50, " %", "0 %: steady · 100 %: they start slowly and speed up"),
        Param.Slider("wander", "Wander", 0, 100, 15, " %", "How much they sway from side to side"),
        Param.Slider("variation", "Variation", 0, 100, 40, " %", "How different their speeds and flight times are"),
        Param.Heading("lookHeading", "Look"),
        Param.Slider("size", "Size", 50, 2000, 100, " px", "Also the pieces they come loose as: the picture is cut into squares this size, so the holes are as big as the flying pixels", decimals = 2),
        Param.Slider("sizeVariation", "Size variation", 0, 100, 0, " %"),
        Param.Toggle("crisp", "Crisp pixels", true, "On the pixel grid with hard edges – off: smooth, sub-pixel exact"),
        Param.Slider("shrink", "Shrink", 0, 100, 0, " %", "How much smaller they get on the way"),
        Param.Slider("fade", "Fade", 0, 100, 50, " %", "How early on the way they fade out: 0 % they vanish only at the end"),
        Param.Slider("trailCopies", "Trail copies", 0, 60, 0, tip = "Fainter copies behind each one, along its way: 0 = no trail"),
        Param.Slider("trailLength", "Trail length", 1, 100, 25, " %", "How far back the trail reaches, of the flight time"),
        Param.Slider("opacity", "Opacity", 1, 100, 100, " %"),
        Param.Choice("colorMode", "Color", listOf("From the picture", "Own color"), 0, "From the picture: each one in the color of the pixel it came from"),
        Param.Color("color", "Own color", 0xFFFFFF).shownWhen("colorMode", 1),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY", percent = true)

    /** A random number 0..1 from the numbers given. */
    private fun rnd(a: Int, b: Int, c: Int): Double {
        var h = a * 374761393 + b * 668265263 + c * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216.0
    }

    /** Which pixels belong to the area. */
    private fun areaMask(src: Pixels, v: Values): BooleanArray {
        val w = src.width
        val h = src.height
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val cy = h / 2.0 * (1 + v["centerY"] / 10000.0)
        val hw = max(0.5, w * v["areaWidth"] / 20000.0)
        val hh = max(0.5, h * v["areaHeight"] / 20000.0)
        val low = v["low"] / 100.0 * 255
        val high = v["high"] / 100.0 * 255
        val area = v["area"]
        return BooleanArray(w * h) { i ->
            val c = src.data[i]
            val x = i % w + 0.5 - cx
            val y = i / w + 0.5 - cy
            when (area) {
                RECT -> alpha(c) > 8 && abs(x) <= hw && abs(y) <= hh
                ELLIPSE -> alpha(c) > 8 && (x / hw) * (x / hw) + (y / hh) * (y / hh) <= 1
                BRIGHTNESS -> alpha(c) > 128 && luma(c).toDouble() in low..high
                else -> alpha(c) > 128
            }
        }
    }

    /** Distance of every area pixel to the nearest pixel outside it (chamfer 3-4, in px); 0 outside. */
    internal fun edgeDistance(inside: BooleanArray, w: Int, h: Int): FloatArray {
        val big = 1e9f
        val d = FloatArray(w * h) { if (inside[it]) big else 0f }
        fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) 0f else d[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            d[i] = minOf(d[i], at(x - 1, y) + 1f, at(x, y - 1) + 1f, min(at(x - 1, y - 1), at(x + 1, y - 1)) + 1.4142f)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0f) continue
            d[i] = minOf(d[i], at(x + 1, y) + 1f, at(x, y + 1) + 1f, min(at(x + 1, y + 1), at(x - 1, y + 1)) + 1.4142f)
        }
        return d
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val dissolve = v["mode"] == 1
        val inside = areaMask(src, v)
        val order = v["order"]
        val needEdge = (!dissolve && v.bool("edgeOnly")) || (dissolve && order == 3)
        val dist = if (needEdge) edgeDistance(inside, w, h) else null
        val edgeWidth = v["edgeWidth"].toFloat()
        // the picture cut into squares the size of the flying pixels: each flies off as one
        val cell = max(1, (v["size"] / 100.0).roundToInt())
        val cw = (w + cell - 1) / cell
        val chh = (h + cell - 1) / cell
        val cellDist = FloatArray(cw * chh) { Float.MAX_VALUE }
        val cellIn = BooleanArray(cw * chh)
        for (i in 0 until w * h) {
            if (!inside[i]) continue
            val k = (i / w / cell) * cw + (i % w / cell)
            cellIn[k] = true
            if (dist != null) cellDist[k] = min(cellDist[k], dist[i])
        }
        val starts = cellIn.indices.filter { cellIn[it] && (dissolve || dist == null || cellDist[it] <= edgeWidth) }
        if (starts.isEmpty()) return src.copy()
        /** The pixels of the area in square [k]. */
        fun pixelsOf(k: Int): List<Int> {
            val x0 = k % cw * cell; val y0 = k / cw * cell
            val list = ArrayList<Int>(cell * cell)
            for (y in y0 until min(h, y0 + cell)) for (x in x0 until min(w, x0 + cell)) if (inside[y * w + x]) list.add(y * w + x)
            return list
        }
        /** The color of square [k]: its area pixels averaged. */
        fun colorOf(k: Int): Int {
            if (cell == 1) return src.data[(k / cw) * w + k % cw]
            var a = 0L; var rr = 0L; var gg = 0L; var bb = 0L; var n = 0
            for (i in pixelsOf(k)) {
                val c = src.data[i]; val ca = alpha(c)
                a += ca; rr += (c shr 16 and 0xFF) * ca; gg += (c shr 8 and 0xFF) * ca; bb += (c and 0xFF) * ca; n++
            }
            if (a == 0L) return 0
            return ((a / n).toInt() shl 24) or ((rr / a).toInt() shl 16) or ((gg / a).toInt() shl 8) or (bb / a).toInt()
        }

        val time = v["time"] / 1000.0
        val flight = v["flightTime"] / 10.0
        val rise = h * v["rise"] / 10000.0
        val a = Math.toRadians(v["direction"] / 100.0)
        val spread = Math.toRadians(v["spread"] / 100.0)
        val accelerate = v["accelerate"] / 100.0
        val wander = v["wander"] / 100.0
        val variation = v["variation"] / 100.0
        val size = v["size"] / 100.0
        val sizeVariation = v["sizeVariation"] / 100.0
        val crisp = v.bool("crisp")
        val shrink = v["shrink"] / 100.0
        val fadeStart = 1 - v["fade"] / 100.0
        val trailCopies = v["trailCopies"]
        val trailLength = v["trailLength"] / 100.0
        val opacity = v["opacity"] / 100.0
        val own = v["colorMode"] == 1
        val color = v["color"]

        // the picture as premultiplied floats, the pixels are laid over it
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h); val al = FloatArray(w * h)
        for (i in 0 until w * h) {
            val c = src.data[i]
            val ca = alpha(c) / 255f
            al[i] = ca; r[i] = (c shr 16 and 0xFF) / 255f * ca; g[i] = (c shr 8 and 0xFF) / 255f * ca; b[i] = (c and 0xFF) / 255f * ca
        }
        val touched = BooleanArray(w * h)
        fun clearPixel(i: Int) { r[i] = 0f; g[i] = 0f; b[i] = 0f; al[i] = 0f; touched[i] = true }
        fun clear(k: Int) { if (cell == 1) clearPixel((k / cw) * w + k % cw) else for (i in pixelsOf(k)) clearPixel(i) }

        /** A square of side [side] centered on ([x], [y]) in color [c], [amount] opaque. */
        fun square(x: Double, y: Double, side: Double, c: Int, amount: Double) {
            if (amount <= 0.003) return
            val half = max(0.05, side / 2)
            val x0 = x - half; val x1 = x + half; val y0 = y - half; val y1 = y + half
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
                    r[i] = (cr * k + r[i] * (1 - k)).toFloat()
                    g[i] = (cg * k + g[i] * (1 - k)).toFloat()
                    b[i] = (cb * k + b[i] * (1 - k)).toFloat()
                    al[i] = k + al[i] * (1 - k)
                    touched[i] = true
                }
            }
        }

        /** One pixel [n] from the square [from], [age] 0..1 into its flight. */
        fun fly(n: Int, round: Int, from: Int, age: Double) {
            val x0 = from % cw * cell + min(cell, w - from % cw * cell) / 2.0
            val y0 = from / cw * cell + min(cell, h - from / cw * cell) / 2.0
            val dir = a + (rnd(n, round, s + 23) - 0.5) * spread
            val dx = cos(dir); val dy = sin(dir)
            val far = rise * (1 + (rnd(n, round, s + 11) - 0.5) * variation)
            val swayFreq = 0.6 + rnd(n, round, s + 13) * 1.4
            val swayPhase = rnd(n, round, s + 17) * 2 * PI
            fun at(t: Double): Pair<Double, Double> {
                val along = far * (t * (1 - accelerate) + t * t * accelerate)
                val sway = sin(t * 2 * PI * swayFreq + swayPhase) * wander * rise * 0.1 * t
                return Pair(x0 + dx * along + dy * sway, y0 + dy * along - dx * sway)
            }
            val fade = min(1.0, age / 0.04) * if (age > fadeStart) ((1 - age) / max(1e-6, 1 - fadeStart)) else 1.0
            val side = size * (1 + (rnd(n, round, s + 29) - 0.5) * 2 * sizeVariation).coerceAtLeast(0.1) * (1 - shrink * age)
            val picture = colorOf(from)
            val c = if (own) color or (alpha(picture) shl 24) else picture
            val amount = fade * opacity
            // the streak: fainter copies a little back along the way
            for (k in trailCopies downTo 1) {
                val t = age - trailLength * k / trailCopies
                if (t <= 0) continue
                val (tx, ty) = at(t)
                place(tx, ty, side, c, amount * (1 - k / (trailCopies + 1.0)) * 0.7, crisp, ::square)
            }
            val (x, y) = at(age)
            place(x, y, side, c, amount, crisp, ::square)
        }

        if (dissolve) {
            val duration = v["dissolveTime"] / 10.0
            val ragged = v["ragged"] / 100.0
            val share = v["share"] / 100.0
            val far = if (dist != null) starts.maxOf { cellDist[it] } else 1f
            // top and bottom of the area itself
            val top = starts.first() / cw
            val tall = max(1.0, (starts.last() / cw - top).toDouble())
            for (i in starts) {
                val y = i / cw
                val key = when (order) {
                    1 -> (y - top) / tall
                    2 -> 1 - (y - top) / tall
                    3 -> cellDist[i] / max(1f, far).toDouble()
                    else -> rnd(i, 5, s)
                }
                val start = (key * (1 - ragged) + rnd(i, 6, s) * ragged) * duration
                if (time < start) continue
                clear(i)
                if (rnd(i, 7, s) >= share) continue
                val life = flight * (1 + (rnd(i, 8, s) - 0.5) * variation)
                val age = (time - start) / life
                if (age < 1) fly(i, 0, i, age)
            }
        } else {
            val count = v["count"]
            val holes = v.bool("holes")
            // where they are now: first the holes, then the flying pixels over them
            val flying = ArrayList<IntArray>(count)
            for (n in 0 until count) {
                val life = flight * (1 + (rnd(n, 1, s) - 0.5) * variation)
                val clock = time / life + rnd(n, 2, s)
                val round = floor(clock).toInt()
                val from = starts[(rnd(n, round, s + 7) * starts.size).toInt().coerceIn(0, starts.size - 1)]
                if (holes) clear(from)
                flying.add(intArrayOf(n, round, from))
            }
            for ((n, round, from) in flying) {
                val life = flight * (1 + (rnd(n, 1, s) - 0.5) * variation)
                val clock = time / life + rnd(n, 2, s)
                fly(n, round, from, clock - floor(clock))
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

    /** Lays one pixel: on the pixel grid when [crisp], else exactly where it is. */
    private inline fun place(x: Double, y: Double, side: Double, c: Int, amount: Double, crisp: Boolean, draw: (Double, Double, Double, Int, Double) -> Unit) {
        if (crisp) {
            val sd = max(1.0, side.roundToInt().toDouble())
            val half = sd / 2
            draw(floor(x - half + 0.5) + half, floor(y - half + 0.5) + half, sd, c, amount)
        } else draw(x, y, side, c, amount)
    }
}
