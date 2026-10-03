package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hydraulic erosion on the picture as a landscape. Its brightness is the height; with a
 * flow angle the whole landscape is tilted that way. In every generation raindrops fall,
 * run straight downhill, wash out material where they are fast and lay it down
 * again where they slow, and smudge the picture's colors along their way. The paths they
 * share deepen into channels, so later drops follow them even more.
 * See [ErosionFast] for a river network version that is much quicker on large pictures.
 */
object Erosion : Effect("erosion", "Erosion", "The picture as a landscape: raindrops run downhill, carry the colors along and dig channels") {
    override val params = listOf(
        Param.Flow("strokes", "Regions", "Dragging in the picture draws arrows: only the area around them is eroded; without arrows the whole picture"),
        Param.Slider("areaWidth", "Region width", 5, 1000, 80, " px", "How far around the arrows it erodes"),
        Param.Choice(
            "flow", "Flow direction", listOf("Picture height (bright = high)", "Picture height (dark = high)", "Angle", "Arrow direction", "Random"),
            tip = "Picture height: the water flows from bright to dark spots (or the other way round) · Angle: the landscape is tilted in this direction, the picture height only deflects the water · " +
                "Arrow direction: the water flows along the drawn arrows · Random: a random hilly landscape steers the water in all directions (“Reroll” for a different one)",
        ),
        Param.Slider("angle", "Angle", 0, 359, 90, "°", "For “Angle”: 90° = downwards"),
        Param.Slider("relief", "Relief", 0, 100, 20, " %", "For “Angle”, “Arrow direction” and “Random”: how strongly the picture height deflects the water"),
        Param.Slider("generations", "Generations", 1, 100, 24, "", "How often it rains; every generation deepens the channels of the previous one"),
        Param.Slider("strength", "Strength", 0, 100, 100, " %", "How much material and color the drops wear away and carry along"),
        Param.Slider("rain", "Rain", 1, 100, 71, " %", "How many drops fall per generation"),
        Param.Slider("path", "Path length", 10, 1000, 120, " px", "The furthest a drop flows"),
        Param.Slider("terrain", "Smooth terrain", 0, 20, 14, " px", "Smooths the picture height first: larger, calmer channels"),
        Param.Slider("channels", "Darken channels", 0, 100, 30, " %", "Darkens the washed-out channels, deposits get a little brighter"),
        Param.Choice(
            "precision", "Precision", listOf("1 px (full)", "2 px", "4 px"), 1,
            tip = "Computes on a coarser grid: 2 px about four times, 4 px about sixteen times as fast",
        ),
        Param.Slider(
            "keepDetail", "Original details", 0, 100, 0, " %",
            "For 2 and 4 px: how much of the original's fine detail is kept in the eroded spots; " +
                "where nothing is eroded the picture always stays sharp",
        ),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val eroded = erode(src, v, seed)
        // only inside the drawn areas, fading out at their edge
        val strokes = FlowStrokes.parse(v.text("strokes"))
        if (strokes.isEmpty()) return eroded
        val area = ArrowArea(strokes, src.width, src.height, v["areaWidth"].toDouble())
        val out = Pixels(src.width, src.height)
        com.spielgrund.glitchr.image.parallelRows(src.height) { y ->
            for (x in 0 until src.width) {
                val i = y * src.width + x
                out.data[i] = com.spielgrund.glitchr.image.lerpArgb(src.data[i], eroded.data[i], area.mask(x + 0.5, y + 0.5).toFloat())
            }
        }
        return out
    }

    private fun erode(src: Pixels, v: Values, seed: Long): Pixels {
        val unit = 1 shl v["precision"]
        if (unit == 1) return simulate(src, v, seed, 1)
        // erode a smaller copy, then add only what the erosion changed back onto the full picture
        val small = shrink(src, unit)
        val eroded = simulate(small, v, seed, unit)
        val sw = small.width
        val sh = small.height
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        val keep = v["keepDetail"] / 100.0
        com.spielgrund.glitchr.image.parallelRows(h) { y ->
            val erodedUp = DoubleArray(4)
            val plainUp = DoubleArray(4)
            val fy = ((y + 0.5) / unit - 0.5).coerceIn(0.0, sh - 1.0)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5) / unit - 0.5).coerceIn(0.0, sw - 1.0)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val c = src.data[y * w + x]
                // per channel (b, g, r, a): the eroded and the plain reduced picture, scaled up smoothly
                var change = 0.0
                for (k in 0..3) {
                    val shift = k * 8
                    fun up(p: Pixels): Double {
                        fun at(i: Int) = (p.data[i] shr shift and 0xFF).toDouble()
                        val top = at(y0 * sw + x0) * (1 - tx) + at(y0 * sw + x1) * tx
                        val bottom = at(y1 * sw + x0) * (1 - tx) + at(y1 * sw + x1) * tx
                        return top * (1 - ty) + bottom * ty
                    }
                    erodedUp[k] = up(eroded)
                    plainUp[k] = up(small)
                    change = max(change, kotlin.math.abs(erodedUp[k] - plainUp[k]))
                }
                // the fine detail of the original shows only where the erosion changed little
                val eroding = (change / 24).coerceIn(0.0, 1.0)
                val detail = 1 - eroding * (1 - keep)
                var result = 0
                for (k in 0..3) {
                    val fine = (c shr (k * 8) and 0xFF) - plainUp[k]
                    val value = (erodedUp[k] + fine * detail).roundToInt().coerceIn(0, 255)
                    result = result or (value shl (k * 8))
                }
                out.data[y * w + x] = result
            }
        }
        return out
    }

    /** The picture reduced by [unit]: every block of unit × unit pixels averaged into one. */
    private fun shrink(src: Pixels, unit: Int): Pixels {
        val sw = max(2, (src.width + unit - 1) / unit)
        val sh = max(2, (src.height + unit - 1) / unit)
        val out = Pixels(sw, sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val sums = LongArray(4)
            var count = 0
            for (yy in y * unit until min(src.height, (y + 1) * unit)) for (xx in x * unit until min(src.width, (x + 1) * unit)) {
                val c = src.data[yy * src.width + xx]
                for (k in 0..3) sums[k] += (c shr (k * 8) and 0xFF).toLong()
                count++
            }
            if (count == 0) {
                out.data[y * sw + x] = src.data[min(src.height - 1, y * unit) * src.width + min(src.width - 1, x * unit)]
                continue
            }
            var c = 0
            for (k in 0..3) c = c or ((sums[k] / count).toInt() shl (k * 8))
            out.data[y * sw + x] = c
        }
        return out
    }

    /** The erosion itself on [src]; lengths in the settings are divided by [unit]. */
    private fun simulate(src: Pixels, v: Values, seed: Long, unit: Int): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val flow = v["flow"]
        val strokes = FlowStrokes.parse(v.text("strokes"))
        val area = if (strokes.isEmpty()) null else ArrowArea(strokes, w, h, v["areaWidth"].toDouble() / unit)
        // along the arrows: without arrows that is just the angle
        val byArrows = flow == 3 && area != null
        val byAngle = flow == 2 || (flow == 3 && area == null)
        val angle = Math.toRadians(v["angle"].toDouble())
        val generations = v["generations"]
        val strength = v["strength"] / 100.0
        val drops = max(1, (n * v["rain"] / 100.0 / 60).toInt())
        val life = max(1, v["path"] / unit)
        val shading = v["channels"] / 100.0

        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val a = FloatArray(n)
        var light = FloatArray(n)
        for (i in 0 until n) {
            val c = src.data[i]
            a[i] = (c ushr 24).toFloat()
            r[i] = (c shr 16 and 0xFF).toFloat()
            g[i] = (c shr 8 and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
            val l = (0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]) / 255f
            light[i] = if (flow == 1) 1 - l else l
        }
        if (v["terrain"] > 0) light = Blur.gauss(light, w, h, v["terrain"].toDouble() / unit)
        // the landscape: the brightness, with an angle tilted so the water runs that way
        val byChance = flow == 4
        val relief = if (byAngle || byArrows || byChance) (v["relief"] / 100.0).toFloat() else 1f
        // random hills and valleys for "Random"
        val hills = com.spielgrund.glitchr.image.Noise(seed xor 0x51ED)
        val hillSize = max(w, h) / 6.0
        val ax = cos(angle)
        val ay = sin(angle)
        val size = max(w, h).toDouble()
        val height = FloatArray(n) { i ->
            val tilt = when {
                byAngle -> (-6 * ((i % w) * ax + (i / w) * ay) / size).toFloat()
                byChance -> hills.fbm(i % w / hillSize, i / w / hillSize, 4).toFloat()
                else -> 0f
            }
            // coarser cells are further apart: the height shrinks with them, so every slope
            // (and with it speed and pickup of the drops) stays as on the full picture
            (light[i] * relief + tilt) / unit
        }
        val start = height.copyOf()
        // along the arrows the water is pushed as hard as the tilt of "Angle" pulls it
        val push = 6 / size / unit
        val arrow = DoubleArray(2)

        val rnd = Random(seed)
        val erodeRate = 0.3 * strength
        val depositRate = 0.25
        val capacityFactor = 4.0
        val evaporate = 0.02
        val gravity = 4.0
        val hv = DoubleArray(3)

        repeat(generations) {
            repeat(drops) {
                var x = rnd.nextDouble() * (w - 1)
                var y = rnd.nextDouble() * (h - 1)
                // outside the drawn areas no rain falls (drawn first, so the chance doesn't shift the rest)
                val chance = rnd.nextDouble()
                if (area != null && chance >= area.mask(x + 0.5, y + 0.5)) return@repeat
                var dx = 0.0
                var dy = 0.0
                var speed = 1.0
                var water = 1.0
                var sediment = 0.0
                // the color the drop carries: it starts with the color where it lands
                val first = y.toInt() * w + x.toInt()
                var cr = r[first].toDouble()
                var cg = g[first].toDouble()
                var cb = b[first].toDouble()
                for (step in 0 until life) {
                    heightAndSlope(height, w, h, x, y, hv)
                    // no inertia: the drops follow every unevenness, into branching channels
                    dx = -hv[1]
                    dy = -hv[2]
                    if (byArrows) {
                        area!!.direction(x + 0.5, y + 0.5, arrow)
                        dx += arrow[0] * push
                        dy += arrow[1] * push
                    }
                    var len = hypot(dx, dy)
                    if (len < 1e-12) {
                        val turn = rnd.nextDouble() * 2 * Math.PI
                        dx = cos(turn); dy = sin(turn); len = 1.0
                    }
                    dx /= len
                    dy /= len
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w - 1 || ny >= h - 1) break
                    val diff = heightAt(height, w, nx, ny) - hv[0]
                    val capacity = max(-diff, 0.002) * speed * water * capacityFactor
                    val cell = y.toInt() * w + x.toInt()
                    if (sediment > capacity || diff > 0) {
                        // slowing down or climbing: lay material down
                        val drop = if (diff > 0) min(diff, sediment) else (sediment - capacity) * depositRate
                        sediment -= drop
                        spread(height, w, x, y, drop.toFloat())
                    } else {
                        // fast and downhill: wash material out
                        val take = min((capacity - sediment) * erodeRate, -diff)
                        spread(height, w, x, y, -take.toFloat())
                        sediment += take
                    }
                    // like a finger smudging paint: the drop lays down what it carries and picks up
                    // what it runs over, faster water taking more along
                    val smear = (strength * 0.7 * water * (area?.mask(x + 0.5, y + 0.5) ?: 1.0)).toFloat()
                    val pick = min(1.0, 0.03 + speed * 0.04)
                    val pr = r[cell].toDouble()
                    val pg = g[cell].toDouble()
                    val pb = b[cell].toDouble()
                    blend(r, g, b, w, x, y, cr, cg, cb, smear)
                    cr += (pr - cr) * pick
                    cg += (pg - cg) * pick
                    cb += (pb - cb) * pick
                    speed = sqrt(max(0.0, speed * speed - diff * gravity))
                    water *= 1 - evaporate
                    x = nx
                    y = ny
                }
            }
        }

        val out = Pixels(w, h)
        for (i in 0 until n) {
            // washed out deeper than before: darker; built up: a little lighter
            val change = (height[i] - start[i]) * 12 * unit
            val shade = (1 + shading * change.coerceIn(-1f, 0.3f)).toDouble()
            out.data[i] = argb(
                a[i].roundToInt().coerceIn(0, 255),
                (r[i] * shade).roundToInt().coerceIn(0, 255),
                (g[i] * shade).roundToInt().coerceIn(0, 255),
                (b[i] * shade).roundToInt().coerceIn(0, 255),
            )
        }
        return out
    }

    /** Height at ([x], [y]) (pixel indices, bilinear) and its slope: writes h, dh/dx, dh/dy into [out]. */
    private fun heightAndSlope(p: FloatArray, w: Int, h: Int, x: Double, y: Double, out: DoubleArray) {
        val x0 = x.toInt().coerceIn(0, w - 2)
        val y0 = y.toInt().coerceIn(0, h - 2)
        val u = x - x0
        val t = y - y0
        val i = y0 * w + x0
        val nw = p[i]
        val ne = p[i + 1]
        val sw = p[i + w]
        val se = p[i + w + 1]
        out[0] = nw * (1 - u) * (1 - t) + ne * u * (1 - t) + sw * (1 - u) * t + se * u * t
        out[1] = (ne - nw) * (1 - t) + (se - sw) * t
        out[2] = (sw - nw) * (1 - u) + (se - ne) * u
    }

    private fun heightAt(p: FloatArray, w: Int, x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val u = x - x0
        val t = y - y0
        val i = y0 * w + x0
        return p[i] * (1 - u) * (1 - t) + p[i + 1] * u * (1 - t) + p[i + w] * (1 - u) * t + p[i + w + 1] * u * t
    }

    /** Adds [amount] to the four pixels around ([x], [y]), split by closeness. */
    private fun spread(p: FloatArray, w: Int, x: Double, y: Double, amount: Float) {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val u = (x - x0).toFloat()
        val t = (y - y0).toFloat()
        val i = y0 * w + x0
        p[i] += amount * (1 - u) * (1 - t)
        p[i + 1] += amount * u * (1 - t)
        p[i + w] += amount * (1 - u) * t
        p[i + w + 1] += amount * u * t
    }

    /** Blends the four pixels around ([x], [y]) towards the color ([cr], [cg], [cb]). */
    private fun blend(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, x: Double, y: Double, cr: Double, cg: Double, cb: Double, k: Float) {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val u = (x - x0).toFloat()
        val t = (y - y0).toFloat()
        val i = y0 * w + x0
        for ((j, weight) in arrayOf(i to (1 - u) * (1 - t), i + 1 to u * (1 - t), i + w to (1 - u) * t, i + w + 1 to u * t)) {
            val m = k * weight
            r[j] += ((cr - r[j]) * m).toFloat()
            g[j] += ((cg - g[j]) * m).toFloat()
            b[j] += ((cb - b[j]) * m).toFloat()
        }
    }
}
