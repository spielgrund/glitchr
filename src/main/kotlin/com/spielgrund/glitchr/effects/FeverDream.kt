package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Eyes in the picture, like the bubbles: on an area (a threshold range, its gaps closed) they all sit
 * from the start, at the roomiest places, and grow with "Growth" – from nothing at 0 % to their size
 * at 100 % and on to four times that. Growing, they push each other aside, the bigger ones harder, and
 * stop where they are pressed by a neighbour or the area's edge; where the area is too narrow they
 * stay smaller. The time only moves them: they look around – wandering in quick jumps, at a point
 * that can be dragged, or at the viewer – and blink now and then. They sit in the picture itself: its
 * colors around them become the lids, darkened into a socket; nothing of them reaches beyond the area.
 */
object FeverDream : Effect("feverdream", "Fever dream", "Eyes in the picture that grow, push each other aside, look around and blink – animate growth and time") {
    private val gazes = listOf("Wandering", "At a point", "At the viewer")
    private val irises = listOf("Natural", "From the picture", "Psychedelic", "One color", "Milky", "Black with stars")
    private const val MILKY = 4
    private const val STARS = 5

    override val params = listOf(
        Param.Slider("time", "Time", 0, 10000, 0, "", "Animate it: the eyes look around and blink", decimals = 1),
        Param.Slider("growth", "Growth", 0, 400, 100, " %", "How big the eyes are: 0 % nothing, 100 % their size, up to four times that – animate it to let them grow and push each other aside"),
        Param.Heading("areaHeading", "Where"),
        Param.Choice("source", "Value", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Lower threshold", 0, 255, 20, tip = "For hue it may lie above the upper one (a range across red)"),
        Param.Slider("upper", "Upper threshold", 0, 255, 255),
        Param.Toggle("invert", "Invert range", false),
        Param.Slider("merge", "Merge", 0, 300, 0, " px", "Gaps in the range up to this width close into one area", canvasMax = true),
        Param.Toggle("showMask", "Show area", false, "The range white, the merged area grey – the eyes over it"),
        Param.Heading("eyesHeading", "Eyes"),
        Param.Slider("count", "Eyes", 1, 300, 30, "", "How many – Reroll for other places"),
        Param.Slider("size", "Size", 1, 100, 12, " %", "Width of an eye at 100 % growth, of the shorter side of the canvas"),
        Param.Slider("sizeVariation", "Size variation", 0, 100, 60, " %"),
        Param.Slider("push", "Pushing", 0, 150, 100, " %", "How much room each eye claims: growing, the eyes push each other aside, the bigger ones harder · 0: they pile up on each other"),
        Param.Slider("tilt", "Tilt", 0, 180, 15, "°", "How far each eye is turned at most, either way"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("gaze", "Looking", gazes, 0, "Wandering: each eye on its own, in quick jumps · At a point: all at the handle in the picture · At the viewer: straight out, with small twitches"),
        Param.Slider("lookX", "Point X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true).shownWhen("gaze", 1),
        Param.Slider("lookY", "Point Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true).shownWhen("gaze", 1),
        Param.Slider("restless", "Restlessness", 0, 100, 50, " %", "How often they look somewhere else"),
        Param.Slider("blink", "Blinking", 0, 100, 40, " %", "How often they blink"),
        Param.Heading("styleHeading", "Style"),
        Param.Choice(
            "iris", "Iris", irises, 1,
            "Natural: brown, hazel, green, blue, gray · From the picture: the color where the eye sits, grey stays grey · Psychedelic: any hue · One color: the color below · Milky: clouded white, like a blind eye · Black with stars: the whole eye black, a starry sky inside",
        ),
        Param.Slider("colorStrength", "Color strength", 0, 100, 100, " %", "How strongly the iris takes on the picture's color: 0 % grey, 100 % as in the picture").shownWhen("iris", 1),
        Param.Color("irisColor", "Iris color", 0x3FA0FF),
        Param.Slider("pupil", "Pupils", 10, 90, 63, " %", "Of the iris"),
        Param.Slider("bloodshot", "Bloodshot", 0, 100, 100, " %", "Red veins in the white"),
        Param.Slider("socket", "Socket", 0, 100, 80, " %", "How deep the picture darkens around the eye"),
    )

    override val canvasHandle = CanvasHandle("lookX", "lookY")

    private val natural = intArrayOf(0x5A351A, 0x7A5226, 0x8C7334, 0x4F7A3C, 0x3F6FA8, 0x6A8090, 0x2E2018, 0x9A6A22)

    /** One eye: its size at 100 %, turned, its own randomness – and where it is now, how wide, its iris. */
    private class Eye(val start: Double, val tilt: Double, val seed: Long, val phase: Double, val pick: Double) {
        var x = 0.0
        var y = 0.0
        var width = 0.0
        var iris = 0
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val t = v["time"] / 10.0
        val random = Random(seed)
        val base = min(w, h) * v["size"] / 100.0
        val variation = v["sizeVariation"] / 100.0
        val tiltMax = Math.toRadians(v["tilt"].toDouble())

        // the area: the threshold range on a coarser grid, its gaps closed (as with the bubbles)
        val n = max(1, min(w, h) / 500)
        val small = Grow.reduce(src, n)
        val gw = small.width
        val gh = small.height
        val start = BooleanArray(small.data.size) {
            (small.data[it] ushr 24) > 0 && thresholdSelects(small.data[it], v["source"], v["lower"], v["upper"], v.bool("invert"))
        }
        val merge = (v["merge"] / 2.0 / n).toInt()
        val area = if (merge < 1) start
        else Grow.blur(FloatArray(start.size) { if (start[it]) 1f else 0f }, gw, gh, merge).let { soft -> BooleanArray(start.size) { start[it] || soft[it] > 0.25f } }
        val ground = if (!v.bool("showMask")) src else Pixels(w, h).also { m ->
            for (y in 0 until h) for (x in 0 until w) {
                val c = min(y / n, gh - 1) * gw + min(x / n, gw - 1)
                m.data[y * w + x] = if (start[c]) -1 else if (area[c]) 0xFF606060.toInt() else 0xFF000000.toInt()
            }
        }
        val cells = area.indices.filter { area[it] }
        if (cells.isEmpty()) return ground.copy()

        val eyes = List(v["count"]) {
            Eye(base * (1 - variation * random.nextDouble().pow(1.5)), (random.nextDouble() * 2 - 1) * tiltMax, random.nextLong(), random.nextDouble(), random.nextDouble())
        }
        simulate(eyes, v["growth"] / 100.0, cells, area, gw, gh, n, src, v, seed)

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, ground.data, 0, w)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val point = w / 2.0 + v["lookX"] / 10.0 to h / 2.0 + v["lookY"] / 10.0
        // the small ones under the big ones
        for (e in eyes.sortedBy { it.width }) {
            if (e.width < 2) continue
            val open = blinkOpen(e, t, v["blink"] / 100.0)
            val (gx, gy) = gaze(e, t, v["gaze"], v["restless"] / 100.0, point)
            drawEye(g, e, open, gx, gy, v, ground)
        }
        g.dispose()
        // nothing of them – socket shadows included – beyond the area
        val out = Pixels.of(img)
        for (i in out.data.indices) {
            val c = min(i / w / n, gh - 1) * gw + min(i % w / n, gw - 1)
            if (!area[c]) out.data[i] = ground.data[i]
        }
        return out
    }

    /**
     * Where the eyes sit and how big they are at [growth], like the bubbles: first each in turn takes the
     * roomiest of a few places on the area (judged by its size at 100 %), then all grow from nothing in
     * small, fixed steps up to [growth] – so a larger growth goes on from where a smaller one ended, and
     * animating it moves them smoothly. An eye grows only while it is free: not pressed by a neighbour
     * and not at the area's edge. Overlapping eyes push each other apart, the bigger of two moving less;
     * an eye at the edge moves back inwards, or shrinks where the area is too narrow.
     */
    private fun simulate(eyes: List<Eye>, growth: Double, cells: List<Int>, area: BooleanArray, gw: Int, gh: Int, n: Int, src: Pixels, v: Values, seed: Long) {
        val w = src.width
        val h = src.height
        val place = Random(seed xor 0x91ACEL)
        val push = v["push"] / 100.0
        val irisMode = v["iris"]
        // how deep in the area each grid cell lies (positive) or how far off it (negative), in pixels
        val deep = distanceTransform(BooleanArray(area.size) { !area[it] }, gw, gh).first
        val off = distanceTransform(area, gw, gh).first
        fun signed(gx: Int, gy: Int): Double {
            val c = gy.coerceIn(0, gh - 1) * gw + gx.coerceIn(0, gw - 1)
            return if (area[c]) deep[c] * n else -off[c] * n
        }
        // how deep a point lies, the canvas edge counting as an edge too
        fun depthAt(x: Double, y: Double) = min(signed((x / n).toInt(), (y / n).toInt()), min(min(x, w - x), min(y, h - y)))
        // the room an eye claims: about a circle as wide as the eye; its middle must lie this deep (of its width)
        val margin = 0.5

        val placed = ArrayList<Eye>()
        for (e in eyes) {
            var best = -Double.MAX_VALUE
            repeat(20) {
                val c = cells[place.nextInt(cells.size)]
                val x = (c % gw + place.nextDouble()) * n
                val y = (c / gw + place.nextDouble()) * n
                var room = depthAt(x, y) / margin / e.start
                if (push > 0) for (o in placed) room = min(room, (hypot(x - o.x, y - o.y) - o.start * 0.5 * push) / (0.5 * push) / e.start)
                if (room > best) { best = room; e.x = x; e.y = y }
            }
            val px = e.x.toInt().coerceIn(0, w - 1)
            val py = e.y.toInt().coerceIn(0, h - 1)
            e.iris = when (irisMode) {
                1 -> fromPicture(src.data[py * w + px], v["colorStrength"] / 100.0)
                2 -> Color.HSBtoRGB(e.pick.toFloat(), 0.8f, 0.9f) and 0xFFFFFF
                3 -> v["irisColor"]
                // the stars' nebula in the picture's color, the milky white tinted by it a little
                MILKY, STARS -> fromPicture(src.data[py * w + px], 1.5)
                else -> natural[(e.pick * natural.size).toInt().coerceIn(0, natural.size - 1)]
            }
            placed += e
        }

        val stepSize = 0.02
        val pressed = HashSet<Eye>()
        for (k in 1..kotlin.math.ceil(growth / stepSize).toInt()) {
            val scale = min(growth, k * stepSize)
            // growing towards the size of this step, but only while free
            for (e in eyes) {
                if (e in pressed || depthAt(e.x, e.y) < e.width * margin * 1.02) continue
                e.width = min(e.start * scale, e.width + e.start * stepSize * 1.5)
            }
            pressed.clear()
            if (push > 0) repeat(3) {
                for (i in eyes.indices) for (j in i + 1 until eyes.size) {
                    val a = eyes[i]
                    val b = eyes[j]
                    var dx = b.x - a.x
                    var dy = b.y - a.y
                    var d = hypot(dx, dy)
                    val need = (a.width + b.width) * 0.5 * push
                    if (d >= need || need <= 0) continue
                    if (need - d > need * 0.01) { pressed += a; pressed += b }
                    if (d < 1e-6) {
                        // right on top of each other: apart in a direction of their own
                        val angle = (a.phase + b.phase) * 2 * PI
                        dx = cos(angle); dy = sin(angle); d = 1.0
                    }
                    // the whole overlap, the bigger one moving less
                    val over = need - d
                    val wa = a.width * a.width + 1e-9
                    val wb = b.width * b.width + 1e-9
                    a.x -= dx / d * over * wb / (wa + wb); a.y -= dy / d * over * wb / (wa + wb)
                    b.x += dx / d * over * wa / (wa + wb); b.y += dy / d * over * wa / (wa + wb)
                }
            }
            for (e in eyes) {
                // too close to the edge: inwards, up the slope of the depth; where it is too narrow, smaller
                val need = e.width * margin
                var d = depthAt(e.x, e.y)
                if (d >= need) continue
                pressed += e
                val gx = (e.x / n).toInt()
                val gy = (e.y / n).toInt()
                var sx = signed(gx + 1, gy) - signed(gx - 1, gy)
                var sy = signed(gx, gy + 1) - signed(gx, gy - 1)
                // the canvas edge pushes inwards too
                if (e.x < need) sx += 1.0 else if (e.x > w - need) sx -= 1.0
                if (e.y < need) sy += 1.0 else if (e.y > h - need) sy -= 1.0
                val sl = hypot(sx, sy)
                if (sl > 1e-9) {
                    val step = min(need - d, e.width * 0.15)
                    e.x += sx / sl * step; e.y += sy / sl * step
                }
                e.x = e.x.coerceIn(0.0, w - 1.0)
                e.y = e.y.coerceIn(0.0, h - 1.0)
                d = depthAt(e.x, e.y)
                if (d < need) e.width = max(0.0, e.width - (need - max(0.0, d)) / margin * 0.5)
            }
        }
    }

    /**
     * The picture's color [c] as an iris: its hue and saturation as they are – grey stays grey – the
     * saturation times [strength], the brightness kept off black and white so the fibres show.
     */
    private fun fromPicture(c: Int, strength: Double): Int {
        val hsb = Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, null)
        val sat = (hsb[1] * strength).toFloat().coerceIn(0f, 1f)
        return Color.HSBtoRGB(hsb[0], sat, (0.18f + hsb[2] * 0.72f)) and 0xFFFFFF
    }

    /** How open the lids are at [t] (1 open, 0 shut): blinks at random moments, shutting fast and opening slower. */
    private fun blinkOpen(e: Eye, t: Double, rate: Double): Double {
        if (rate <= 0) return 1.0
        val r = Random(e.seed xor 0x5EED)
        val mean = 4 + 60 * (1 - rate).pow(2)
        var at = r.nextDouble() * mean + 2
        while (at < t + 3) {
            val d = t - at
            if (d >= 0 && d < 2.5) return if (d < 0.8) 1 - smoothstep(0.0, 0.8, d) else smoothstep(0.8, 2.5, d)
            // now and then a double blink
            at += if (r.nextDouble() < 0.15) 3.0 else mean * (0.4 + 1.2 * r.nextDouble())
        }
        return 1.0
    }

    /** Where the eye looks at [t]: x, y from −1 to 1 (left/up to right/down), in the picture's directions. */
    private fun gaze(e: Eye, t: Double, mode: Int, restless: Double, point: Pair<Double, Double>): Pair<Double, Double> {
        val r = Random(e.seed xor 0x6A2E)
        // small twitches, always
        val tw = 0.06 * sin(t * 0.9 + e.phase * 9) to 0.05 * cos(t * 0.7 + e.phase * 13)
        when (mode) {
            1 -> {
                val dx = point.first - e.x; val dy = point.second - e.y
                val d = hypot(dx, dy)
                val reach = min(1.0, d / (e.width * 1.5))
                return (if (d > 1e-9) dx / d * reach else 0.0) + tw.first to (if (d > 1e-9) dy / d * reach else 0.0) + tw.second
            }
            2 -> return tw.first * 1.5 to tw.second * 1.5
        }
        // wandering: holding a direction, then a quick jump to the next
        val hold = 3 + 30 * (1 - restless).pow(1.5)
        var px = r.nextDouble() * 2 - 1; var py = (r.nextDouble() * 2 - 1) * 0.7
        var at = -r.nextDouble() * hold
        while (true) {
            val next = at + hold * (0.4 + 1.2 * r.nextDouble())
            val nx = r.nextDouble() * 2 - 1; val ny = (r.nextDouble() * 2 - 1) * 0.7
            if (t < next) return px + tw.first to py + tw.second
            if (t < next + 0.6) {
                val s = smoothstep(0.0, 0.6, t - next)
                return px + (nx - px) * s + tw.first to py + (ny - py) * s + tw.second
            }
            px = nx; py = ny; at = next
        }
    }

    private fun col(c: Int, a: Double) = Color(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, (a * 255).toInt().coerceIn(0, 255))

    private fun mixColor(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Smooth value noise, 0 … 1, in two directions; [wrap] repeats it along x (for angles around the iris). */
    private fun noise2(x: Double, y: Double, seed: Int, wrap: Int): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        fun hsh(i: Int, j: Int): Double {
            var k = Math.floorMod(i, wrap) * 374761393 + j * 668265263 + seed * 1442695041
            k = (k xor (k ushr 13)) * 1274126177
            return ((k xor (k ushr 16)) and 0xFFFF) / 65535.0
        }
        val a = hsh(ix, iy) + (hsh(ix + 1, iy) - hsh(ix, iy)) * sx
        val b = hsh(ix, iy + 1) + (hsh(ix + 1, iy + 1) - hsh(ix, iy + 1)) * sx
        return a + (b - a) * sy
    }

    /**
     * The iris as a picture [2·ir] wide: radial fibres in its color, lighter and warmer around the
     * pupil with a ragged collarette, darker crypts, a dark rim towards the white; the pupil black with
     * a soft edge; the outline anti-aliased. [milky]: clouded over like a blind eye – a pale, misty
     * white barely tinted by the color, the fibres faint, the rim light, the pupil a grey haze.
     */
    private fun irisImage(ir: Double, color: Int, pupil: Double, seed: Long, milky: Boolean): BufferedImage {
        val size = (ir * 2).toInt().coerceAtLeast(2) + 2
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val px = (img.raster.dataBuffer as DataBufferInt).data
        val ns = seed.toInt()
        val c = size / 2.0
        var cr = (color shr 16 and 0xFF) / 255.0; var cg = (color shr 8 and 0xFF) / 255.0; var cb = (color and 0xFF) / 255.0
        if (milky) { cr = 0.84 + 0.14 * cr; cg = 0.86 + 0.12 * cg; cb = 0.9 + 0.1 * cb }
        // the inner ring: lighter, and warmer – a little golden – the more colorful the iris (grey stays grey; milky: whiter)
        val warm = Color.RGBtoHSB(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, null)[1].toDouble()
        val wr = if (milky) 0.97 else cr * 0.7 + 0.24 + 0.04 * warm
        val wg = if (milky) 0.97 else cg * 0.7 + 0.24 - 0.04 * warm
        val wb = if (milky) 0.98 else cb * 0.7 + 0.24 - 0.19 * warm
        val fibres = if (milky) 0.3 else 1.0
        val rim = if (milky) 0.22 else 0.75
        val pupilColor = if (milky) doubleArrayOf(0.6, 0.63, 0.68) else doubleArrayOf(0.02, 0.015, 0.02)
        val pupilSoft = if (milky) 0.12 else 0.025
        val collar = pupil + (1 - pupil) * (0.3 + 0.08 * noise2(0.0, 0.0, ns, 1))
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + 0.5 - c; val dy = y + 0.5 - c
            val rho = hypot(dx, dy) / ir
            if (rho > 1 + 1 / ir) continue
            val ang = (atan2(dy, dx) / (2 * PI) + 0.5)
            // fibres: fine radial streaks, coarser bundles, crypts
            val fine = noise2(ang * 180, rho * 3, ns, 180)
            val bundle = noise2(ang * 40, rho * 1.5, ns + 1, 40)
            val crypt = noise2(ang * 24, rho * 6, ns + 2, 24)
            // the collarette: a ragged ring
            val ragged = collar + (noise2(ang * 30, 0.5, ns + 3, 30) - 0.5) * 0.08
            var l = (if (milky) 0.95 else 0.62) + fibres * (0.55 * (fine - 0.5) + 0.35 * (bundle - 0.5))
            if (rho > ragged && crypt > 0.68) l *= 1 - fibres * (0.45 - (0.75 - crypt).coerceAtLeast(0.0) * 3)
            val inner = 1 - smoothstep(ragged - 0.04, ragged + 0.04, rho)
            l += fibres * 0.18 * (1 - smoothstep(0.0, 0.05, abs(rho - ragged)))
            // the dark rim towards the white
            l *= 1 - rim * smoothstep(0.78, 0.98, rho)
            var r = (cr + (wr - cr) * inner) * l
            var g = (cg + (wg - cg) * inner) * l
            var b = (cb + (wb - cb) * inner) * l
            // the pupil, its edge soft
            val p = smoothstep(pupil - pupilSoft, pupil + pupilSoft, rho)
            r = pupilColor[0] + (r - pupilColor[0]) * p; g = pupilColor[1] + (g - pupilColor[1]) * p; b = pupilColor[2] + (b - pupilColor[2]) * p
            val a = ((1 - rho) * ir + 0.5).coerceIn(0.0, 1.0)
            fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            px[y * size + x] = ((a * 255 + 0.5).toInt() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        return img
    }

    private fun abs(x: Double) = kotlin.math.abs(x)

    /**
     * A black eye full of stars, filling the opening (clipped to it): deep space, a faint nebula in
     * the eye's color, stars small and large, the big ones glowing. Looking around, the sky shifts a
     * little – near stars more than far ones.
     */
    private fun stars(g: java.awt.Graphics2D, e: Eye, ew: Double, lx: Double, ly: Double) {
        val half = ew * 0.6
        g.paint = RadialGradientPaint(
            Point2D.Double(0.0, 0.0), half.toFloat(), floatArrayOf(0f, 1f), arrayOf(Color(14, 10, 26), Color(0, 0, 2)),
        )
        g.fill(java.awt.geom.Rectangle2D.Double(-half, -half, half * 2, half * 2))
        val nebula = e.iris
        g.paint = RadialGradientPaint(
            Point2D.Double(lx * ew * 0.05 + (e.pick - 0.5) * ew * 0.4, ly * ew * 0.04), (ew * 0.35).toFloat(), floatArrayOf(0f, 1f),
            arrayOf(col(nebula, 0.4), col(nebula, 0.0)),
        )
        g.fill(java.awt.geom.Rectangle2D.Double(-half, -half, half * 2, half * 2))
        val r = Random(e.seed xor 0x57A25L)
        val unit = max(0.9, ew / 110)
        repeat((ew * ew * 0.018).toInt().coerceIn(30, 1200)) {
            val depth = r.nextDouble()
            // near stars are bigger and shift more with the gaze
            val x = (r.nextDouble() * 2 - 1) * half - lx * ew * 0.08 * depth
            val y = (r.nextDouble() * 2 - 1) * half - ly * ew * 0.06 * depth
            val size = unit * (0.35 + 1.1 * depth.pow(3) + if (r.nextDouble() < 0.03) 1.5 else 0.0)
            val tint = when (r.nextInt(6)) {
                0 -> Color(255, 222, 180)
                1 -> Color(190, 210, 255)
                else -> Color(255, 255, 255)
            }
            val bright = 0.6 + 0.4 * r.nextDouble()
            if (size > unit * 1.2) {
                // a glow around the big ones
                val glow = size * 2.5
                g.paint = RadialGradientPaint(Point2D.Double(x, y), glow.toFloat(), floatArrayOf(0f, 0.3f, 1f), arrayOf(Color(tint.red, tint.green, tint.blue, (120 * bright).toInt()), Color(tint.red, tint.green, tint.blue, (30 * bright).toInt()), Color(tint.red, tint.green, tint.blue, 0)))
                g.fill(Ellipse2D.Double(x - glow, y - glow, glow * 2, glow * 2))
            }
            g.color = Color(tint.red, tint.green, tint.blue, (255 * bright).toInt())
            g.fill(Ellipse2D.Double(x - size / 2, y - size / 2, size, size))
        }
    }

    /** One eye drawn into its own layer, then laid into the picture. */
    private fun drawEye(g0: java.awt.Graphics2D, e: Eye, open: Double, gx: Double, gy: Double, v: Values, src: Pixels) {
        val ew = e.width
        val side = (ew * 2.6).toInt().coerceAtLeast(8)
        val layer = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val g = layer.createGraphics()
        g.setRenderingHints(g0.renderingHints)
        g.translate(side / 2.0, side / 2.0)
        g.rotate(e.tilt)
        // the skin: the picture's color where the eye sits
        val sx = e.x.toInt().coerceIn(0, src.width - 1); val sy = (e.y - ew * 0.5).toInt().coerceIn(0, src.height - 1)
        val skin = src.data[sy * src.width + sx] and 0xFFFFFF
        val socket = v["socket"] / 100.0

        // an oval only a little wider than the iris; the lids cover the iris a little above and below
        val halfW = ew / 2
        val ir = ew * 0.4
        val lowRy = ew * 0.3
        val upRy = -lowRy * 0.96 + (ew * 0.36 + lowRy * 0.96) * open
        val top = -upRy
        /** Half an oval from one end to the other, [ry] above the middle (negative: below), [wide] times as wide. */
        fun arc(path: GeneralPath, ry: Double, wide: Double, leftToRight: Boolean) {
            val steps = 40
            for (i in 0..steps) {
                val a = PI * (if (leftToRight) steps - i else i) / steps
                val x = cos(a) * halfW * wide
                val y = -sin(a) * ry
                if (i == 0 && path.currentPoint == null) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        val opening = GeneralPath().also { arc(it, upRy, 1.0, true); arc(it, -lowRy, 1.0, false); it.closePath() }

        // the socket: the picture darkened around the eye, fading out
        if (socket > 0) {
            val save = g.transform
            g.scale(1.0, 0.85)
            g.paint = RadialGradientPaint(
                Point2D.Double(0.0, -ew * 0.04), (ew * 0.95).toFloat(), floatArrayOf(0f, 0.45f, 1f),
                arrayOf(col(0x0A0405, 0.7 * socket), col(0x0A0405, 0.3 * socket), col(0x0A0405, 0.0)),
            )
            g.fill(Ellipse2D.Double(-ew * 0.95, -ew * 0.99, ew * 1.9, ew * 1.9))
            g.transform = save
        }
        // the lids: skin rounding over the eyeball, lit from above, the upper one with a soft fold
        val lidTop = top * 0.92 - ew * 0.1
        val lid = GeneralPath().also { arc(it, -lidTop, 1.05, true); arc(it, upRy, 1.0, false); it.closePath() }
        g.paint = java.awt.GradientPaint(0f, lidTop.toFloat(), col(skin, 0.0), 0f, top.toFloat(), col(mixColor(skin, 0xFFF0E8, 0.1), 0.35))
        g.fill(lid)
        val lowerLid = GeneralPath().also { arc(it, -lowRy, 1.0, true); arc(it, -lowRy - ew * 0.07, 1.04, false); it.closePath() }
        g.paint = java.awt.GradientPaint(0f, lowRy.toFloat(), col(mixColor(skin, 0xFFE8E0, 0.1), 0.3), 0f, (lowRy + ew * 0.07).toFloat(), col(skin, 0.0))
        g.fill(lowerLid)

        if (open > 0.02) {
            val clip = g.clip
            g.clip(opening)
            val c = cos(-e.tilt); val s = sin(-e.tilt)
            val lx = (gx * c - gy * s).coerceIn(-1.0, 1.0); val ly = (gx * s + gy * c).coerceIn(-1.0, 1.0)
            val starry = v["iris"] == STARS
            if (starry) stars(g, e, ew, lx, ly)
            // the white: a ball, lighter where it faces us, greyer and pinker towards the corners
            else g.paint = RadialGradientPaint(
                Point2D.Double(gx * ew * 0.1, -ew * 0.02), (halfW * 1.05).toFloat(), floatArrayOf(0f, 0.55f, 0.85f, 1f),
                arrayOf(Color(244, 239, 232), Color(226, 216, 210), Color(196, 168, 162), Color(150, 104, 100)),
            )
            if (!starry) g.fill(opening)
            // veins from the corners, branching, fading towards the iris
            val bloodshot = v["bloodshot"] / 100.0
            if (bloodshot > 0 && !starry) {
                val vr = Random(e.seed xor 0xB100D)
                fun vein(x0: Double, y0: Double, a0: Double, len: Int, width: Double, alpha: Double, depth: Int) {
                    var x = x0; var y = y0; var a = a0
                    val p = GeneralPath().apply { moveTo(x, y) }
                    for (s in 0 until len) {
                        a += (vr.nextDouble() - 0.5) * 0.9
                        x += cos(a) * ew * 0.025; y += sin(a) * ew * 0.025
                        p.lineTo(x, y)
                        if (depth < 2 && vr.nextDouble() < 0.18) vein(x, y, a + (if (vr.nextBoolean()) 0.7 else -0.7), len / 2, width * 0.6, alpha * 0.8, depth + 1)
                    }
                    g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = Color(170, 30, 38, (alpha * 255).toInt().coerceIn(0, 255))
                    g.draw(p)
                }
                repeat((3 + 14 * bloodshot).toInt()) {
                    val left = vr.nextBoolean()
                    val y = (vr.nextDouble() - 0.5) * lowRy * 1.4
                    vein((if (left) -halfW else halfW), y, (if (left) 0.0 else PI) + (vr.nextDouble() - 0.5) * 1.2,
                        6 + vr.nextInt(6), ew * (0.004 + 0.006 * vr.nextDouble()), 0.25 + 0.5 * bloodshot * vr.nextDouble(), 0)
                }
            }
            // the iris, where it looks – the gaze turned into the eye's own frame
            val ix = lx * (halfW - ir * 0.7)
            val iy = ly * ew * 0.09
            if (!starry) {
                // looking aside, the iris is seen a little from the side
                val squeeze = 1 - 0.12 * abs(lx)
                val pupil = v["pupil"] / 100.0 * (1 + 0.1 * (1 - open))
                val iris = irisImage(ir, e.iris, pupil.coerceAtMost(0.95), e.seed, v["iris"] == MILKY)
                val save = g.transform
                g.translate(ix, iy)
                g.scale(squeeze, 1.0)
                g.drawImage(iris, (-iris.width / 2.0).toInt(), (-iris.height / 2.0).toInt(), null)
                g.transform = save
            }
            // shading: the upper lid's shadow on the ball, darker along all edges of the opening
            g.paint = java.awt.GradientPaint(0f, top.toFloat(), Color(30, 10, 10, 190), 0f, (top + ew * 0.3).toFloat(), Color(30, 10, 10, 0))
            g.fill(opening)
            g.stroke = BasicStroke((ew * 0.09).toFloat())
            g.color = Color(40, 12, 12, 60)
            g.draw(opening)
            // the pink corner by the nose
            val inner = if (e.phase < 0.5) -1.0 else 1.0
            if (!starry) g.paint = RadialGradientPaint(
                Point2D.Double(inner * halfW * 0.93, lowRy * 0.1), (ew * 0.1).toFloat(), floatArrayOf(0f, 1f),
                arrayOf(Color(205, 110, 110, 200), Color(205, 110, 110, 0)),
            )
            if (!starry) g.fill(Ellipse2D.Double(inner * halfW * 0.93 - ew * 0.1, lowRy * 0.1 - ew * 0.1, ew * 0.2, ew * 0.2))
            // the wet glints on the cornea: a soft window and a small spark
            val hx = ix - ir * 0.32; val hy = iy - ir * 0.34
            g.paint = RadialGradientPaint(Point2D.Double(hx, hy), (ir * 0.13).toFloat(), floatArrayOf(0f, 0.6f, 1f), arrayOf(Color(255, 255, 255, 250), Color(255, 255, 255, 220), Color(255, 255, 255, 0)))
            g.fill(Ellipse2D.Double(hx - ir * 0.13, hy - ir * 0.13, ir * 0.26, ir * 0.26))
            g.color = Color(255, 255, 255, 150)
            g.fill(Ellipse2D.Double(ix + ir * 0.28, iy + ir * 0.22, ir * 0.08, ir * 0.06))
            g.clip = clip
        }
        // the lower lid's rim, soft and pinkish; closed, the lashes' line where the lids meet
        g.stroke = BasicStroke((ew * 0.025).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = col(mixColor(skin, 0xC08078, 0.35), 0.4)
        g.draw(GeneralPath().also { arc(it, -lowRy - ew * 0.01, 1.0, true) })
        if (open < 0.3) {
            g.stroke = BasicStroke((ew * 0.012).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = col(mixColor(skin, 0x2A1410, 0.6), 0.5 * (1 - open / 0.3))
            g.draw(GeneralPath().also { arc(it, upRy, 1.0, true) })
        }
        g.dispose()
        g0.drawImage(layer, (e.x - side / 2.0).toInt(), (e.y - side / 2.0).toInt(), null)
    }
}
