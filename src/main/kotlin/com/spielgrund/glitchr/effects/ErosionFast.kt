package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Erosion of the picture as a landscape, its brightness the height (or tilted towards
 * an angle). On a reduced grid every point drains to a lower neighbour, and the water
 * of everything above it adds up: a branching river network, from fine creeks to broad
 * rivers. Each generation the rivers cut deeper where much water runs steeply (stream
 * power). The result is shown on the picture in full resolution: the colors are pulled
 * downstream, the further the more water flows, the eroded relief is lit from the side,
 * and the rivers can be drawn in.
 */
object ErosionFast : Effect("erosionfast", "Erosion Fast", "The picture as a landscape: a river network digs valleys, drags the colors along and is lit in relief") {
    private val riverModes = listOf("Off", "Dark", "Bright", "Color")

    override val params = listOf(
        Param.Flow("strokes", "Regions", "Dragging in the picture draws arrows: only the area around them is eroded; without arrows the whole picture"),
        Param.Slider("areaWidth", "Region width", 5, 1000, 80, " px", "How far around the arrows it erodes"),
        Param.Choice(
            "flow", "Flow direction", listOf("Picture height (bright = high)", "Picture height (dark = high)", "Angle", "Arrow direction", "Random"),
            tip = "Picture height: the water flows from bright to dark spots (or the other way round) · Angle: the landscape is tilted in this direction, the picture height only deflects the water · " +
                "Arrow direction: the water flows along the drawn arrows · Random: a random hilly landscape steers the water in all directions (“Reroll” for a different one)",
        ),
        Param.Slider("angle", "Angle", 0, 359, 90, "°", "For “Angle”: 90° = downwards"),
        Param.Slider("relief", "Relief", 0, 100, 25, " %", "For “Angle”, “Arrow direction” and “Random”: how strongly the picture height deflects the water"),
        Param.Slider("generations", "Generations", 1, 50, 13, "", "How often the rivers dig in; more gives deeper valleys"),
        Param.Slider("strength", "Strength", 0, 100, 100, " %", "How deep the rivers dig in per generation"),
        Param.Slider("terrain", "Smooth terrain", 0, 30, 7, " px", "Smooths the picture height first: larger, calmer river systems"),
        Param.Slider("density", "River density", 0, 100, 80, " %", "From how much water a river becomes visible: more also shows the fine streams"),
        Param.Slider("streak", "Streak length", 0, 500, 130, " px", "How far the colors are dragged downstream; large rivers drag furthest"),
        Param.Slider("light", "Relief light", 0, 100, 0, " %", "Lights the eroded landscape from the side"),
        Param.Slider("lightAngle", "Light direction", 0, 359, 225, "°", "225° = light from the top left"),
        Param.Choice("rivers", "Rivers", riverModes, 0, "Draw the rivers themselves"),
        Param.Color("riverColor", "River color", 0x2A6FDB, "For “Color”"),
        Param.Slider("riverStrength", "River opacity", 0, 100, 50, " %"),
        Param.Slider("detail", "Detail", 128, 1024, 512, " px", "Resolution the river network is computed at (longer side of the picture); higher is finer but slower"),
    )


    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // the river network lives on a reduced grid
        val scale = min(1.0, v["detail"].toDouble() / max(w, h))
        val gw = max(2, (w * scale).roundToInt())
        val gh = max(2, (h * scale).roundToInt())
        val sx = w.toDouble() / gw
        val sy = h.toDouble() / gh
        val n = gw * gh

        // height: the brightness averaged over each grid cell
        var height = FloatArray(n)
        val invert = v["flow"] == 1
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = (gx * sx).toInt()
            val x1 = max(x0 + 1, ((gx + 1) * sx).toInt().coerceAtMost(w))
            val y0 = (gy * sy).toInt()
            val y1 = max(y0 + 1, ((gy + 1) * sy).toInt().coerceAtMost(h))
            var sum = 0.0
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = src.data[y * w + x]
                sum += (0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)) / 255
            }
            val l = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
            height[gy * gw + gx] = if (invert) 1 - l else l
        }
        val smooth = v["terrain"] * scale
        if (smooth >= 0.5) height = Blur.gauss(height, gw, gh, smooth)
        val strokes = FlowStrokes.parse(v.text("strokes"))
        val area = if (strokes.isEmpty()) null else ArrowArea(strokes, w, h, v["areaWidth"].toDouble())
        if (v["flow"] == 3 && area != null) {
            // downhill along the arrows: the further along its arrow, the lower
            val relief = v["relief"] / 100f
            val size = max(gw, gh).toDouble()
            for (i in 0 until n) {
                val along = area.along((i % gw + 0.5) * sx, (i / gw + 0.5) * sy) / sx
                height[i] = height[i] * relief - (2 * along / size).toFloat()
            }
        } else if (v["flow"] == 4) {
            // random hills and valleys; the picture's relief only bends the water
            val hills = com.spielgrund.glitchr.image.Noise(seed xor 0x51ED)
            val relief = v["relief"] / 100f
            val cell = 90.0 * max(gw, gh) / 512
            for (i in 0 until n) height[i] = height[i] * relief + (0.6 * hills.fbm(i % gw / cell, i / gw / cell, 4)).toFloat()
        } else if (v["flow"] >= 2) {
            // tilted towards the angle; the picture's relief only bends the water
            val a = Math.toRadians(v["angle"].toDouble())
            val relief = v["relief"] / 100f
            val size = max(gw, gh).toDouble()
            for (i in 0 until n) height[i] = height[i] * relief - (2 * ((i % gw) * cos(a) + (i / gw) * sin(a)) / size).toFloat()
        }
        if (v["flow"] >= 2) {
            // a slightly uneven slope: on a perfectly even one every line would run down on its own,
            // with bumps the water gathers into channels
            val bumps = com.spielgrund.glitchr.image.Noise(seed)
            val cell = 16.0 * max(gw, gh) / 512
            for (i in 0 until n) height[i] += (0.012 * bumps.fbm(i % gw / cell, i / gw / cell, 3)).toFloat()
        }

        // generations: route the water, add it up, let the rivers cut in
        val recv = IntArray(n)
        val order = IntArray(n)
        val water = FloatArray(n)
        val k = v["strength"] / 100.0 * 0.004 / scale.coerceAtLeast(0.05)
        fun route() {
            flood(height, gw, gh, recv, order)
            steepest(height, gw, gh, recv)
            water.fill(1f)
            for (i in n - 1 downTo 0) {
                val c = order[i]
                if (recv[c] >= 0) water[recv[c]] += water[c]
            }
        }
        repeat(v["generations"]) {
            route()
            // implicit stream power erosion, from the outlets upwards: stable at any strength
            for (i in 0 until n) {
                val c = order[i]
                val r = recv[c]
                if (r < 0) continue
                val dist = if (c % gw != r % gw && c / gw != r / gw) 1.4142 else 1.0
                val f = (k * sqrt(water[c].toDouble()) / dist).toFloat()
                height[c] = (height[c] + f * height[r]) / (1 + f)
            }
        }
        route()

        // fields for the full picture: river strength 0..1, downstream direction, light
        val maxWater = ln(water.max().toDouble().coerceAtLeast(2.0))
        val threshold = 1 - v["density"] / 100.0
        val river = FloatArray(n) { i ->
            val t = ((ln(water[i].toDouble()) / maxWater - threshold) / 0.25).coerceIn(0.0, 1.0)
            (t * t * (3 - 2 * t)).toFloat()
        }
        var dirX = FloatArray(n)
        var dirY = FloatArray(n)
        for (c in 0 until n) {
            val r = recv[c]
            if (r < 0) continue
            val dx = (r % gw - c % gw).toFloat()
            val dy = (r / gw - c / gw).toFloat()
            val len = hypot(dx, dy)
            dirX[c] = dx / len
            dirY[c] = dy / len
        }
        // the eight neighbour directions smoothed into a flowing field
        dirX = Blur.gauss(dirX, gw, gh, 2.5)
        dirY = Blur.gauss(dirY, gw, gh, 2.5)
        val lightAngle = Math.toRadians(v["lightAngle"].toDouble())
        val lx = cos(lightAngle) * 0.7071
        val ly = sin(lightAngle) * 0.7071
        val lz = 0.7071
        val relief = 60.0
        val shade = FloatArray(n) { i ->
            val x = i % gw
            val y = i / gw
            val gx = (height[y * gw + min(gw - 1, x + 1)] - height[y * gw + max(0, x - 1)]) / 2.0 * relief
            val gy = (height[min(gh - 1, y + 1) * gw + x] - height[max(0, y - 1) * gw + x]) / 2.0 * relief
            val len = sqrt(gx * gx + gy * gy + 1)
            // facing the light brighter, turned away darker; flat ground stays as it is
            (((-gx * lx - gy * ly + lz) / len - lz) * 2).toFloat()
        }

        val streak = v["streak"].toDouble()
        val light = v["light"] / 100.0
        val rivers = v["rivers"]
        val riverStrength = v["riverStrength"] / 100.0
        val riverColor = v["riverColor"]
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val gx = (x + 0.5) / sx
                val gy = (y + 0.5) / sy
                val rv = field(river, gw, gh, gx, gy)
                // colors pulled downstream: the pixel collects what flows in from upstream
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var a = 0.0
                var weights = 0.0
                val len = streak * (0.15 + 0.85 * rv)
                val taps = if (len < 1) 1 else 8
                var px = x + 0.5
                var py = y + 0.5
                for (t in 0 until taps) {
                    val c = sampleBilinear(src, px, py, Edge.CLAMP)
                    val weight = 1.0 - t * 0.09
                    val ca = (c ushr 24) / 255.0 * weight
                    r += (c shr 16 and 0xFF) * ca
                    g += (c shr 8 and 0xFF) * ca
                    b += (c and 0xFF) * ca
                    a += ca
                    weights += weight
                    if (taps > 1) {
                        val qx = px / sx
                        val qy = py / sy
                        px -= field(dirX, gw, gh, qx, qy) * len / taps
                        py -= field(dirY, gw, gh, qx, qy) * len / taps
                    }
                }
                if (a > 1e-9) { r /= a; g /= a; b /= a }
                val alpha = a / weights
                // relief light
                val lit = 1 + light * field(shade, gw, gh, gx, gy)
                r *= lit; g *= lit; b *= lit
                // the rivers drawn in
                val m = rv * riverStrength
                if (rivers != 0 && m > 0) {
                    val (tr, tg, tb) = when (rivers) {
                        1 -> Triple(r * 0.25, g * 0.25, b * 0.25)
                        2 -> Triple(r + (255 - r) * 0.75, g + (255 - g) * 0.75, b + (255 - b) * 0.75)
                        else -> Triple((riverColor shr 16 and 0xFF).toDouble(), (riverColor shr 8 and 0xFF).toDouble(), (riverColor and 0xFF).toDouble())
                    }
                    r += (tr - r) * m; g += (tg - g) * m; b += (tb - b) * m
                }
                val eroded = argb(
                    (alpha * 255).roundToInt().coerceIn(0, 255),
                    r.roundToInt().coerceIn(0, 255),
                    g.roundToInt().coerceIn(0, 255),
                    b.roundToInt().coerceIn(0, 255),
                )
                // only inside the drawn areas, fading out at their edge
                out.data[y * w + x] = if (area == null) eroded
                else com.spielgrund.glitchr.image.lerpArgb(src.data[y * w + x], eroded, area.mask(x + 0.5, y + 0.5).toFloat())
            }
        }
        return out
    }

    /** Bilinear lookup in a grid field at grid position ([x], [y]) (cell centers at +0.5). */
    private fun field(f: FloatArray, gw: Int, gh: Int, x: Double, y: Double): Double {
        val fx = (x - 0.5).coerceIn(0.0, gw - 1.0)
        val fy = (y - 0.5).coerceIn(0.0, gh - 1.0)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, gw - 1)
        val y1 = min(y0 + 1, gh - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = f[y0 * gw + x0] * (1 - tx) + f[y0 * gw + x1] * tx
        val bottom = f[y1 * gw + x0] * (1 - tx) + f[y1 * gw + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    /**
     * Priority flood from the border: fills every hollow up to its spill point (raising
     * [height] there) and lets each cell drain to the neighbour it was reached from
     * ([recv], -1 at the border). [order] gets the cells from low to high.
     */
    private fun flood(height: FloatArray, gw: Int, gh: Int, recv: IntArray, order: IntArray) {
        val n = gw * gh
        val seen = BooleanArray(n)
        val heap = Heap(n, height)
        for (i in 0 until n) {
            val x = i % gw
            val y = i / gw
            if (x == 0 || y == 0 || x == gw - 1 || y == gh - 1) {
                seen[i] = true
                recv[i] = -1
                heap.push(i)
            }
        }
        var count = 0
        while (heap.size > 0) {
            val c = heap.pop()
            order[count++] = c
            val cx = c % gw
            val cy = c / gw
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || y < 0 || x >= gw || y >= gh) continue
                val nb = y * gw + x
                if (seen[nb]) continue
                seen[nb] = true
                // a hollow is filled just above its outlet, so the water still runs through it
                if (height[nb] <= height[c]) height[nb] = height[c] + 1e-6f
                recv[nb] = c
                heap.push(nb)
            }
        }
    }

    /**
     * Lets every cell drain to its steepest lower neighbour (on the filled landscape), so
     * the water runs straight down a slope instead of along the order the flood reached it.
     * Cells without a lower neighbour keep the way the flood found out of their hollow.
     */
    private fun steepest(height: FloatArray, gw: Int, gh: Int, recv: IntArray) {
        parallelRows(gh) { y ->
            for (x in 0 until gw) {
                val c = y * gw + x
                if (recv[c] < 0) continue
                var best = 0.0
                var target = -1
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val drop = (height[c] - height[ny * gw + nx]) / (if (dx != 0 && dy != 0) 1.4142 else 1.0)
                    if (drop > best) { best = drop; target = ny * gw + nx }
                }
                if (target >= 0) recv[c] = target
            }
        }
    }

    /** Min-heap of cell indices ordered by their height. */
    private class Heap(capacity: Int, private val key: FloatArray) {
        private val data = IntArray(capacity)
        var size = 0
            private set

        fun push(i: Int) {
            var pos = size++
            data[pos] = i
            while (pos > 0) {
                val parent = (pos - 1) / 2
                if (key[data[parent]] <= key[data[pos]]) break
                val t = data[parent]; data[parent] = data[pos]; data[pos] = t
                pos = parent
            }
        }

        fun pop(): Int {
            val top = data[0]
            data[0] = data[--size]
            var pos = 0
            while (true) {
                val l = pos * 2 + 1
                if (l >= size) break
                val r = l + 1
                val child = if (r < size && key[data[r]] < key[data[l]]) r else l
                if (key[data[pos]] <= key[data[child]]) break
                val t = data[child]; data[child] = data[pos]; data[pos] = t
                pos = child
            }
            return top
        }
    }
}
