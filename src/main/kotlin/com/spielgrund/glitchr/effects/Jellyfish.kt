package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.Color
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Sea jellies: a translucent bell with a glowing, scalloped rim seen a little from below, radial
 * canals, gonads and a mottled skin; wide oral arms hanging from its middle like curtains – their
 * margins ruffled and lighter, folds down them, spiralling around each other as set – and long,
 * fine tentacles from the rim. A light in the complementary color can catch its outline, upper
 * bell and tentacles. Each jelly hangs on a spine: from a center point (the bell there), turned by
 * the rotation, or along a drawn arrow – the bell at its first point (or as far along as set), the
 * rest following the drawn, smoothed path to its end. The time drives it: the bell pulses (quick
 * contraction, slow release), waves run down tentacles and arms, the arms turn as they swirl, a
 * current pulls them aside, it rocks and can swim forward in bursts. The glow is one bloom over
 * the finished jellies, so overlapping ones don't add up.
 */
object Jellyfish : Effect("jellyfish", "Jellyfish", "Sea jellies with a pulsing, glowing bell, ruffled swirling oral arms and long tentacles, lit in the complementary color – from a center or along drawn arrows; animate the time") {
    private val modes = listOf("From the center", "Along arrows")

    /** Color schemes: bell, rim and glow, oral arms, their ruffled margins, tentacles. */
    private val schemes = listOf(
        "Lion's mane" to intArrayOf(0xF2A07A, 0xFFC08A, 0xE8501E, 0xFFD9A0, 0xFFE6D6),
        "Sunset" to intArrayOf(0xF4BCD4, 0xFFAE78, 0xFF8E6E, 0xFFE0CC, 0xFFE2DA),
        "Moon jelly" to intArrayOf(0xCFE6FF, 0xFFFFFF, 0xB4D4FF, 0xF0F8FF, 0xE6F2FF),
        "Electric blue" to intArrayOf(0x5AB4FF, 0x9AF2FF, 0x3A6CFF, 0xBFF0FF, 0xA8E4FF),
        "Purple" to intArrayOf(0xC49CFF, 0xFF8AEE, 0x9A5AFF, 0xFFD0F6, 0xEAD6FF),
        "Toxic" to intArrayOf(0xB4FF6A, 0xF2FF9A, 0x56DC46, 0xF0FFC0, 0xDEFFC6),
        "Fire" to intArrayOf(0xFF9A5A, 0xFFE07A, 0xFF4A2A, 0xFFE8A0, 0xFFD8B0),
        "Custom" to intArrayOf(0, 0, 0, 0, 0),
    )
    private val CUSTOM = schemes.size - 1

    override val params = listOf(
        Param.Choice("mode", "Jellies", modes, 0, "From the center: one jelly, its bell at the handle, turned by the rotation · Along arrows: a jelly on every drawn arrow, its bell where the arrow starts, the rest following it to its end"),
        Param.Flow("strokes", "Arrows", "Drag in the picture: a jelly hangs along every arrow, its bell at the start; a short drag places one turned by the rotation", activeWhen = "mode" to 1),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "The middle of the bell, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true).shownWhen("mode", 0),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", decimals = 1, transform = true).shownWhen("mode", 0),
        Param.Slider("rotation", "Rotation", -18000, 18000, 0, "°", "Where the bell points: 0° = up", decimals = 2),
        Param.Slider("along", "Along the arrow", 0, 10000, 0, " %", "Where on its arrow the bell is: 0 % at its first point – animate it, the rest follows the path", decimals = 2).shownWhen("mode", 1),
        Param.Slider("smoothing", "Arrow smoothing", 0, 100, 30, " %", "Leaves out the small wiggles of the drawn path and rounds it off – its shape, start and end stay").shownWhen("mode", 1),
        Param.Toggle("fit", "Fit to the arrow", true, "The tentacles reach the arrow's end and the arms most of the way, whatever the bell's size – the lengths below scale that").shownWhen("mode", 1),
        Param.Heading("bodyHeading", "Body"),
        Param.Slider("size", "Size", 100, 10000, 4000, " %", "Width of the bell, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("height", "Bell height", 30, 200, 65, " %", "Of its width: flat like a saucer or tall like a helmet"),
        Param.Slider("lobes", "Rim lobes", 0, 32, 16, tip = "Scallops along the bell's rim"),
        Param.Slider("canals", "Canals", 0, 32, 16, tip = "Radial canals in the bell"),
        Param.Slider("arms", "Oral arms", 0, 8, 4, tip = "Curtains hanging from the bell's middle"),
        Param.Slider("armLength", "Arm length", 20, 600, 170, " %", "Of the bell's width (fitted to an arrow: 170 % reaches most of the way)"),
        Param.Slider("armWidth", "Arm width", 20, 300, 100, " %"),
        Param.Slider("frills", "Ruffles", 0, 100, 70, " %", "How ruffled the arms' margins are"),
        Param.Slider("shadow", "Arm shadow", 0, 100, 50, " %", "The arms cast a soft shadow onto the arms and tentacles behind them"),
        Param.Slider("swirl", "Swirl", 0, 100, 45, " %", "How the arms spiral around each other – and turn with the time"),
        Param.Slider("tentacles", "Tentacles", 0, 80, 26),
        Param.Slider("tentacleLength", "Tentacle length", 20, 1200, 260, " %", "Of the bell's width (fitted to an arrow: 260 % reaches its end)"),
        Param.Slider("thickness", "Tentacle thickness", 10, 500, 100, " %"),
        Param.Heading("motionHeading", "Motion"),
        Param.Slider("time", "Time", 0, 10000, 0, "", "Drives everything – animate it", decimals = 2),
        Param.Slider("pulse", "Pulse", 0, 100, 50, " %", "How strongly the bell contracts and opens again"),
        Param.Slider("rate", "Pulse rate", 10, 400, 50, " %", "Pulses per time unit: 100 % = one every 2"),
        Param.Slider("wave", "Tentacle waves", 0, 100, 45, " %", "Waves running down the tentacles and arms"),
        Param.Slider("current", "Current", -100, 100, 15, " %", "Pulls the tentacles and arms aside – negative to the other side"),
        Param.Slider("turbulence", "Turbulence", 0, 100, 0, " %", "Swings the whole lower body – arms and tentacles together – in waves that drift with the time"),
        Param.Slider("turbulenceSize", "Turbulence size", 20, 600, 150, " %", "Length of its waves, of the bell's width"),
        Param.Slider("rock", "Rocking", 0, 4500, 600, "°", "The jelly rocks to and fro by up to this much", decimals = 2).shownWhen("mode", 0),
        Param.Slider("swim", "Swim", 0, 100, 0, " %", "Moves forward in bursts with every pulse").shownWhen("mode", 0),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("scheme", "Colors", schemes.map { it.first }, 0, "Custom: the five colors below"),
        Param.Color("bellColor", "Bell", 0xF2A07A, "Custom"),
        Param.Color("rimColor", "Rim", 0xFFC08A, "Custom: the rim's glow"),
        Param.Color("armColor", "Oral arms", 0xE8501E, "Custom"),
        Param.Color("frillColor", "Ruffled margins", 0xFFD9A0, "Custom"),
        Param.Color("tentacleColor", "Tentacles", 0xFFE6D6, "Custom"),
        Param.Slider("complement", "Complementary light", 0, 100, 60, " %", "A light in the opposite color of the arms catching the bell's outline and top and the tentacles – blue on an orange jelly"),
        Param.Slider("opacity", "Opacity", 10, 100, 75, " %", "How see-through the jelly is"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %", "One bloom over the finished jellies"),
        Param.Toggle("transparent", "Transparent background", false, "Only the jellies and their glow, without the base color"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /**
     * A path the jelly hangs on, by arc length from the bell's rim down the tentacles; beyond its
     * ends it goes on straight. Its direction turns smoothly between the points – with a sudden turn
     * at every point, everything hanging off to the side would jump there, and lines get jagged.
     */
    private class Spine(points: List<DoubleArray>, val tentacleLen: Double, val armLen: Double) {
        private val xs = points.map { it[0] }.toDoubleArray()
        private val ys = points.map { it[1] }.toDoubleArray()
        private val len = DoubleArray(points.size).also { l -> for (i in 1 until points.size) l[i] = l[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) }

        // the direction at each point: between the stretch before it and the one after
        private val dx = DoubleArray(points.size)
        private val dy = DoubleArray(points.size)

        init {
            val n = points.size
            for (i in 0 until n) {
                var x = 0.0
                var y = 0.0
                for (j in listOf(i - 1, i)) if (j >= 0 && j + 1 < n) {
                    val l = max(1e-9, len[j + 1] - len[j])
                    x += (xs[j + 1] - xs[j]) / l; y += (ys[j + 1] - ys[j]) / l
                }
                val l = max(1e-9, hypot(x, y))
                dx[i] = x / l; dy[i] = y / l
            }
        }

        /** Point and unit direction (down the spine) at arc length [s] into [out]: x, y, tx, ty. */
        fun at(s: Double, out: DoubleArray) {
            val n = xs.size
            var i = if (s <= 0) 0 else if (s >= len[n - 1]) n - 2 else {
                var lo = 0
                var hi = n - 1
                while (hi - lo > 1) { val m = (lo + hi) / 2; if (len[m] <= s) lo = m else hi = m }
                lo
            }
            i = i.coerceIn(0, n - 2)
            val seg = max(1e-9, len[i + 1] - len[i])
            val d = s - len[i]
            out[0] = xs[i] + (xs[i + 1] - xs[i]) / seg * d; out[1] = ys[i] + (ys[i + 1] - ys[i]) / seg * d
            // the direction blended from one point's to the next's
            val k = (d / seg).coerceIn(0.0, 1.0)
            val tx = dx[i] + (dx[i + 1] - dx[i]) * k
            val ty = dy[i] + (dy[i + 1] - dy[i]) * k
            val tl = max(1e-9, hypot(tx, ty))
            out[2] = tx / tl; out[3] = ty / tl
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val size = min(w, h).toDouble()
        val radius = max(2.0, v["size"] / 10000.0 * size / 2)
        val tentacleLen = v["tentacleLength"] / 100.0 * radius * 2
        val armLen = v["armLength"] / 100.0 * radius * 2
        val bellHeight = radius * v["height"] / 100.0 * 1.6
        // (half the slider's units, so the default motion is calm)
        val time = v["time"] / 100.0 * 0.5
        val phase = time * v["rate"] / 100.0
        // a quick contraction, then slowly opening again
        val f = phase - floor(phase)
        val squeeze = v["pulse"] / 100.0 * (if (f < 0.3) smoothstep(0.0, 0.3, f) else 1 - smoothstep(0.3, 1.0, f))
        val rotation = Math.toRadians(v["rotation"] / 100.0)

        // ---- the spines: one from the center, or one per arrow ----
        val spines = ArrayList<Spine>()
        val far = (tentacleLen + armLen + radius) * 4 + max(w, h) * 6
        fun straight(x: Double, y: Double, angle: Double): Spine {
            // down the spine is away from where the bell points
            val tx = -sin(angle)
            val ty = cos(angle)
            return Spine(listOf(doubleArrayOf(x, y), doubleArrayOf(x + tx * far, y + ty * far)), tentacleLen, armLen)
        }
        if (v["mode"] == 0) {
            val rock = Math.toRadians(v["rock"] / 100.0) * sin(phase * 2 * PI * 0.25)
            val angle = rotation + rock
            // the handle marks the middle of the bell, the rest hangs from it; it swims forward in bursts
            val forward = v["swim"] / 100.0 * radius * 2 * (phase + 0.3 * squeeze)
            val down = bellHeight * 0.5 - forward
            spines += straight(w / 2.0 + v["centerX"] / 10.0 - sin(angle) * down, h / 2.0 + v["centerY"] / 10.0 + cos(angle) * down, angle)
        } else {
            val along = v["along"] / 10000.0
            for (stroke in FlowStrokes.parse(v.text("strokes"))) {
                val pts = stroke.map { doubleArrayOf(it.x * w, it.y * h) }
                val total = (1 until pts.size).sumOf { hypot(pts[it][0] - pts[it - 1][0], pts[it][1] - pts[it - 1][1]) }
                if (total < size * 0.04) {
                    spines += straight(pts[0][0], pts[0][1], rotation)
                    continue
                }
                // the drawn path, smoothed so the jelly bends in curves rather than kinks
                val smoothing = v["smoothing"] / 100.0
                val path = smoothPath(pts, max(1.0, radius * 0.1), smoothing * smoothing * max(total * 0.025, radius * 0.35))
                // from the bell's place on it on to its end, then on straight; the bell's middle sits on that place,
                // so its rim – where the spine starts – is half a bell further on
                val at = min(total - 1e-6, total * along + bellHeight * 0.5)
                val rest = max(radius, total - at)
                val spine = ArrayList<DoubleArray>()
                var walked = 0.0
                var i = 1
                while (i < path.size) {
                    val d = hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1])
                    if (walked + d >= at) {
                        val k = (at - walked) / max(1e-9, d)
                        spine += doubleArrayOf(path[i - 1][0] + (path[i][0] - path[i - 1][0]) * k, path[i - 1][1] + (path[i][1] - path[i - 1][1]) * k)
                        break
                    }
                    walked += d
                    i++
                }
                if (spine.isEmpty()) spine += path.last()
                for (j in i until path.size) spine += path[j]
                val n = path.size
                val ex = path[n - 1][0] - path[n - 2][0]
                val ey = path[n - 1][1] - path[n - 2][1]
                val el = max(1e-9, hypot(ex, ey))
                spine += doubleArrayOf(path[n - 1][0] + ex / el * far, path[n - 1][1] + ey / el * far)
                // fitted: as long as the arrow is from the bell on
                spines += if (v.bool("fit")) Spine(spine, rest * v["tentacleLength"] / 260.0, rest * 0.85 * v["armLength"] / 170.0)
                else Spine(spine, tentacleLen, armLen)
            }
        }

        // ---- colors, and the complementary light: the arms' hue turned half way round, bright ----
        val scheme = v["scheme"].coerceIn(0, CUSTOM)
        val colors = if (scheme == CUSTOM) intArrayOf(v["bellColor"], v["rimColor"], v["armColor"], v["frillColor"], v["tentacleColor"]) else schemes[scheme].second
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val comp = colors[2].let { c ->
            val hsb = Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, null)
            Color.HSBtoRGB(hsb[0] + 0.5f, max(0.55f, hsb[1] * 0.9f), 1f) and 0xFFFFFF
        }
        val complement = v["complement"] / 100.0
        fun toward(c: Int, target: Int, t: Double): Int {
            fun one(sh: Int) = ((c shr sh and 0xFF) + ((target shr sh and 0xFF) - (c shr sh and 0xFF)) * t).toInt().coerceIn(0, 255)
            return (one(16) shl 16) or (one(8) shl 8) or one(0)
        }
        val tentacleColor = toward(colors[4], comp, complement * 0.75)
        val opacity = v["opacity"] / 100.0
        val wave = v["wave"] / 100.0
        val current = v["current"] / 100.0
        val turbulence = v["turbulence"] / 100.0
        val turbulenceSize = v["turbulenceSize"] / 100.0 * radius * 2

        // ---- tentacles and oral arms, drawn into their own layer (behind the bell) ----
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val p = DoubleArray(4)
        val random = Random(seed)
        // the bell narrows as it contracts; the tentacles follow
        val rimHalf = radius * (1 - 0.14 * squeeze)
        val swirl = v["swirl"] / 100.0
        val frills = v["frills"] / 100.0
        val armWidth = v["armWidth"] / 100.0
        val shadow = v["shadow"] / 100.0
        for (spine in spines) {
            /** A point [side] across the spine at [s] down it, swaying with the waves and the current. */
            fun point(s: Double, side: Double, phaseShift: Double, length: Double, sway: Double, out: DoubleArray = DoubleArray(4)): DoubleArray {
                spine.at(s, p)
                val k = (s / length).coerceIn(0.0, 1.0)
                val swing = sway * radius * 0.3 * k.pow(0.8) * sin(2 * PI * s / (radius * 1.7) - 2 * PI * phase * 0.8 + phaseShift) +
                    current * radius * 1.3 * k.pow(1.6)
                // turbulence: the same for everything at this depth, so the whole body swings – growing from the bell down
                val body = if (turbulence > 0) turbulence * radius * 0.9 * smoothstep(0.0, radius * 0.8, s) *
                    noise.fbm(s / turbulenceSize - time * 0.35 + 61.0, time * 0.12 + 17.0, 3) else 0.0
                val off = side + swing + body
                // the point, and the direction across the spine there
                out[0] = p[0] + p[3] * off; out[1] = p[1] - p[2] * off; out[2] = p[3]; out[3] = -p[2]
                return out
            }

            // tentacles: from all along the rim, fine and long, a few thicker
            val count = v["tentacles"]
            val thick = v["thickness"] / 100.0
            for (t in 0 until count) {
                val r1 = random.nextDouble(); val r2 = random.nextDouble(); val r3 = random.nextDouble(); val r4 = random.nextDouble()
                val side = ((t + 0.5) / count * 2 - 1) * rimHalf * 0.92
                val len = spine.tentacleLen * (0.55 + 0.6 * r1)
                val width = max(0.5, radius * 0.012 * thick * (if (r2 > 0.8) 2.2 else 0.7 + 0.6 * r2))
                val shift = r3 * 2 * PI
                val bright = 0.45 + 0.55 * r4
                val path = Path2D.Double()
                val step = max(1.5, radius * 0.03)
                // from a little up inside the rim, so they grow out of it under its front skin
                var s = -bellHeight * 0.08
                var first = true
                while (s <= len) {
                    // they gather a little towards the middle as they hang
                    val q = point(s, side * (1 - 0.35 * (s / len).coerceIn(0.0, 1.0)), shift, len, wave * (0.7 + 0.6 * r3))
                    if (first) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1])
                    first = false
                    s += step
                }
                val c = tentacleColor
                g.color = Color(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, (255 * min(1.0, opacity * bright)).toInt())
                g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }

            // oral arms: curtains around the middle, spiralling around each other as set; the ones behind first
            val arms = v["arms"]
            val armData = (0 until arms).map { a -> doubleArrayOf(a * 2 * PI / max(1, arms) + random.nextDouble() * 0.4, spine.armLen * (0.8 + 0.3 * random.nextDouble()), random.nextDouble() * 2 * PI) }
            // where an arm is turned to at [s]: its place around the middle, turning down it and with the time
            fun turn(arm: DoubleArray, s: Double) = arm[0] + swirl * (s / (radius * 0.9)) * PI + swirl * time * 0.6
            // a fixed order front to back, so the arms don't jump over each other as they turn
            val order = armData.sortedBy { cos(it[0]) }
            val spread = radius * 0.32
            for (arm in order) {
                val len = arm[1]
                val shift = arm[2]
                val step = max(1.0, radius * 0.02)
                val left = ArrayList<DoubleArray>()
                val right = ArrayList<DoubleArray>()
                val middle = ArrayList<DoubleArray>()
                // from up inside the bell, between its back and front
                var s = -bellHeight * 0.35
                while (s <= len) {
                    val k = (s / len).coerceAtLeast(0.0)
                    val psi = turn(arm, s)
                    // around the middle: across the picture by sin, towards us by cos; wider apart further down
                    val side = spread * (0.35 + 1.3 * k.pow(0.8)) * sin(psi)
                    val c = point(s, side, shift, len, wave * 0.6)
                    // the curtain's width: swelling in the middle, narrowing at the end, thin seen edge-on
                    val facing = 0.3 + 0.7 * abs(cos(psi))
                    val half = radius * 0.2 * armWidth * (0.45 + 0.9 * sin(PI * k.pow(0.75))) * (1 - 0.35 * k) * facing
                    // the ruffled margins: rounded frills bulging out, of uneven size, with a finer crinkle on them
                    fun ruffle(edge: Double): Double {
                        val size = (0.6 + 0.8 * (noise.perlin(s / (radius * 0.15) + edge * 7.7, shift) + 0.5)).coerceAtLeast(0.3)
                        val bulge = abs(sin(s / (radius * 0.075 * size) + edge * 2.3 + shift)).pow(0.45)
                        val crinkle = 0.05 * sin(s / (radius * 0.022) + edge * 5.1 + shift * 3)
                        return frills * half * (0.6 * bulge * size + crinkle - 0.25)
                    }
                    val l = half + ruffle(1.0)
                    val r = half + ruffle(-1.0)
                    left += doubleArrayOf(c[0] + c[2] * l, c[1] + c[3] * l)
                    right += doubleArrayOf(c[0] - c[2] * r, c[1] - c[3] * r)
                    middle += doubleArrayOf(c[0], c[1], c[2], c[3], half)
                    s += step
                }
                if (middle.size < 2) continue
                val sheet = Path2D.Double()
                sheet.moveTo(left[0][0], left[0][1])
                for (q in left.drop(1)) sheet.lineTo(q[0], q[1])
                for (q in right.asReversed()) sheet.lineTo(q[0], q[1])
                sheet.closePath()
                // its shadow on what lies behind (only there – not on the water): soft, a few steps further out,
                // away from the light at the top left, turned with the jelly
                if (shadow > 0) {
                    spine.at(0.0, p)
                    val sx = (p[3] * 0.45 + p[2] * 0.9) * radius * 0.07
                    val sy = (-p[2] * 0.45 + p[3] * 0.9) * radius * 0.07
                    val old = g.composite
                    g.composite = java.awt.AlphaComposite.SrcAtop
                    for (step in 1..4) {
                        g.color = Color(0, 0, 0, (255 * min(1.0, shadow * 0.22 * (1.1 - step * 0.2))).toInt())
                        g.fill(java.awt.geom.AffineTransform.getTranslateInstance(sx * step * 0.5, sy * step * 0.5).createTransformedShape(sheet))
                    }
                    g.composite = old
                }
                // the curtain: paler at the top, the arms' color further down, one see-through layer
                val top = toward(colors[2], 0xFFFFFF, 0.35)
                val alpha = (255 * min(1.0, opacity * 0.62)).toInt()
                val start = middle.first()
                val end = middle.last()
                if (hypot(end[0] - start[0], end[1] - start[1]) > 1) {
                    g.paint = LinearGradientPaint(
                        Point2D.Double(start[0], start[1]), Point2D.Double(end[0], end[1]), floatArrayOf(0f, 0.45f, 1f),
                        arrayOf(Color(top shr 16, top shr 8 and 0xFF, top and 0xFF, alpha), Color(colors[2] shr 16, colors[2] shr 8 and 0xFF, colors[2] and 0xFF, alpha),
                            Color(colors[2] shr 16, colors[2] shr 8 and 0xFF, colors[2] and 0xFF, alpha * 3 / 4)),
                    )
                } else g.color = Color(colors[2] shr 16, colors[2] shr 8 and 0xFF, colors[2] and 0xFF, alpha)
                g.fill(sheet)
                // folds down it: darker lines wandering across the curtain
                val dark = toward(colors[2], 0x000000, 0.45)
                // soft, wide shadows first, then a fine crease in each
                for ((widthShare, alphaShare) in listOf(0.06 to 0.12, 0.01 to 0.3)) for (fold in 0 until 3) {
                    g.color = Color(dark shr 16, dark shr 8 and 0xFF, dark and 0xFF, (255 * min(1.0, opacity * alphaShare)).toInt())
                    g.stroke = BasicStroke(max(0.5f, (radius * widthShare).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val fp = Path2D.Double()
                    for ((n, q) in middle.withIndex()) {
                        val off = q[4] * ((fold - 1) * 0.5 + 0.25 * sin(n * 0.09 + fold * 2.1 + shift))
                        if (n == 0) fp.moveTo(q[0] + q[2] * off, q[1] + q[3] * off) else fp.lineTo(q[0] + q[2] * off, q[1] + q[3] * off)
                    }
                    g.draw(fp)
                }
                // the margins: crinkled, lighter, catching the light
                val fc = colors[3]
                for ((edge, width) in listOf(left to 0.016, right to 0.016, left to 0.005, right to 0.005)) {
                    val ep = Path2D.Double()
                    ep.moveTo(edge[0][0], edge[0][1])
                    for (q in edge.drop(1)) ep.lineTo(q[0], q[1])
                    val a = if (width > 0.01) 0.4 else 0.75
                    val c = if (width > 0.01) fc else toward(fc, 0xFFFFFF, 0.5)
                    g.color = Color(c shr 16, c shr 8 and 0xFF, c and 0xFF, (255 * min(1.0, opacity * a * (0.4 + 0.6 * frills))).toInt())
                    g.stroke = BasicStroke(max(0.5f, (radius * width * (0.5 + frills)).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(ep)
                }
            }
        }
        g.dispose()
        val hang = (img.raster.dataBuffer as DataBufferInt).data

        // ---- the bells in two layers: their back (the inside wall, the back of the rim, the gonads) behind the arms and
        // tentacles, their front skin over them; premultiplied ----
        val jr = FloatArray(w * h)
        val jg = FloatArray(w * h)
        val jb = FloatArray(w * h)
        val ja = FloatArray(w * h)
        val fr = FloatArray(w * h)
        val fg = FloatArray(w * h)
        val fb = FloatArray(w * h)
        val fa = FloatArray(w * h)
        val lobes = v["lobes"]
        val canals = v["canals"]
        val bell = colors[0]
        val rim = colors[1]
        val height = bellHeight * (1 + 0.12 * squeeze)
        for (spine in spines) {
            // the bell sits on the spine's start, pointing back along its direction there – the same frame the tentacles
            // and arms start from, so they hang right from its rim
            spine.at(0.0, p)
            val tx = p[2]
            val ty = p[3]
            val ox = p[0]
            val oy = p[1]
            val reach = max(rimHalf, height) * 1.2 + 2
            val x0 = max(0, (ox - reach).toInt()); val x1 = min(w - 1, (ox + reach).toInt())
            val y0 = max(0, (oy - reach).toInt()); val y1 = min(h - 1, (oy + reach).toInt())
            if (x0 > x1 || y0 > y1) continue
            val px = 1.0 / rimHalf
            val opening = height * 0.13
            parallelRows(y1 - y0 + 1) { row ->
                val y = y0 + row
                for (x in x0..x1) {
                    val dx = x + 0.5 - ox
                    val dy = y + 0.5 - oy
                    // across the bell, and along its axis (negative: up into the dome)
                    val u = dx * ty - dy * tx
                    val a = dx * tx + dy * ty
                    val nu = u / rimHalf
                    if (abs(nu) > 1.05) continue
                    // the rim seen a little from below: an oval opening, its front edge hanging lower than its back,
                    // with rounded scallops along the front
                    val ell = sqrt(max(0.0, 1 - nu * nu))
                    val front = opening * ell
                    val back = -opening * ell * 0.6
                    val scallop = if (lobes > 0) sqrt(abs(sin(PI * lobes * (nu + 1) / 2))) * height * 0.06 else 0.0
                    val na = -a / height
                    val r = hypot(nu, max(0.0, na))
                    // inside the dome (above the rim) or down to the front edge and its scallops
                    val dome = ((1 - r) / (px * 1.5) + 0.5).coerceIn(0.0, 1.0)
                    val below = ((front + scallop - a) / 1.2 + 0.5).coerceIn(0.0, 1.0)
                    val cover = if (a <= 0) dome else dome * below * (if (abs(nu) < 1) 1.0 else 0.0)
                    if (cover <= 0) continue
                    val i = y * w + x
                    // the hollow under the bell, seen through the opening
                    val hollow = if (a > back) smoothstep(back, back + opening * 0.4, a) * (1 - smoothstep(front - opening * 0.3, front, a)) else 0.0
                    // more opaque towards the outline (seen through more of the skin), bright along the rim's edges
                    val fresnel = r.coerceIn(0.0, 1.0).pow(3)
                    val rimGlow = exp(-((a - front) / (height * 0.05)).pow(2)) + 0.5 * exp(-((a - back) / (height * 0.04)).pow(2))
                    val theta = atan2(nu, max(1e-6, na))
                    val canal = if (canals > 0) cos(theta * canals / 2).pow(2).pow(30) * smoothstep(0.3, 0.55, r) * (1 - smoothstep(0.85, 1.0, r)) * 0.6 else 0.0
                    // four horseshoe gonads around the middle, glowing softly
                    val gonad = exp(-((r - 0.42) / 0.11).pow(2)) * (0.5 + 0.5 * cos(theta * 4 + PI / 4)).pow(2)
                    val mottle = noise.fbm(u / (radius * 0.12) + 7.0, a / (radius * 0.12) - 3.0, 3)
                    // a soft highlight up on the dome, from the top left
                    val shine = exp(-((nu + 0.35).pow(2) + (na - 0.65).pow(2)) / 0.04) * (if (a < 0) 1.0 else 0.0)
                    val backRim = exp(-((a - back) / (height * 0.04)).pow(2))
                    val frontRim = exp(-((a - front) / (height * 0.05)).pow(2))
                    // the back: the inside wall seen through the skin and the opening, the back of the rim, the gonads
                    val backAlpha = (opacity * (0.22 + 0.2 * fresnel + 0.4 * backRim + 0.35 * gonad + 0.15 * hollow)).coerceIn(0.0, 1.0) * cover
                    // the front: the outer skin, thin in the middle and thicker towards the outline, its canals, the front rim
                    val alpha = (opacity * (0.25 + 0.4 * fresnel + 0.15 * canal + 0.6 * frontRim + 0.08 * mottle + 0.3 * shine) * (1 - 0.6 * hollow)).coerceIn(0.0, 1.0) * cover
                    // the bell's color, towards the rim color at the bottom edge, in the hollow and the gonads, paler towards the top
                    val toRim = (frontRim * 0.8 + hollow * 0.4 + (1 - smoothstep(-0.2, 0.4, na)) * 0.4).coerceIn(0.0, 1.0)
                    val pale = (0.2 * smoothstep(0.3, 1.0, na) + 0.2 * canal + 0.6 * shine + 0.06 * mottle).coerceIn(0.0, 1.0)
                    // the complementary light: on the outline and up on the dome
                    val lit = complement * (0.85 * fresnel + 0.55 * smoothstep(0.35, 1.0, na)) * (1 - 0.7 * rimGlow)
                    fun mix(sh: Int): Double {
                        var c = ch(bell, sh) + (ch(rim, sh) - ch(bell, sh)) * toRim
                        c += (ch(comp, sh) - c) * lit.coerceIn(0.0, 1.0)
                        return (c + (1 - c) * pale).coerceIn(0.0, 1.0)
                    }
                    // the back deeper in the rim's color, the gonads glowing in it
                    fun backMix(sh: Int): Double {
                        var c = ch(bell, sh) + (ch(rim, sh) - ch(bell, sh)) * (0.45 + 0.5 * gonad + 0.4 * backRim).coerceIn(0.0, 1.0)
                        c += (ch(comp, sh) - c) * (lit * 0.5).coerceIn(0.0, 1.0)
                        return c.coerceIn(0.0, 1.0)
                    }
                    // each layer over what is there of other bells
                    val keepBack = 1 - backAlpha
                    jr[i] = (backMix(16) * backAlpha + jr[i] * keepBack).toFloat()
                    jg[i] = (backMix(8) * backAlpha + jg[i] * keepBack).toFloat()
                    jb[i] = (backMix(0) * backAlpha + jb[i] * keepBack).toFloat()
                    ja[i] = (backAlpha + ja[i] * keepBack).toFloat()
                    val keep = 1 - alpha
                    fr[i] = (mix(16) * alpha + fr[i] * keep).toFloat()
                    fg[i] = (mix(8) * alpha + fg[i] * keep).toFloat()
                    fb[i] = (mix(0) * alpha + fb[i] * keep).toFloat()
                    fa[i] = (alpha + fa[i] * keep).toFloat()
                }
            }
        }

        // the arms and tentacles over the bells' backs, the fronts over them
        for (i in hang.indices) {
            val c = hang[i]
            val a = (c ushr 24) / 255f
            if (a > 0f) {
                val keep = 1 - a
                jr[i] = (c shr 16 and 0xFF) / 255f * a + jr[i] * keep
                jg[i] = (c shr 8 and 0xFF) / 255f * a + jg[i] * keep
                jb[i] = (c and 0xFF) / 255f * a + jb[i] * keep
                ja[i] = a + ja[i] * keep
            }
            val f = fa[i]
            if (f > 0f) {
                val keep = 1 - f
                jr[i] = fr[i] + jr[i] * keep; jg[i] = fg[i] + jg[i] * keep; jb[i] = fb[i] + jb[i] * keep
                ja[i] = f + ja[i] * keep
            }
        }

        // ---- one bloom over the finished jellies: their light, in their own colors ----
        val glow = v["glow"] / 100.0
        val bloom = if (glow > 0) {
            val sigmaWide = max(1.5, radius * 0.18)
            val sigmaNear = max(1.0, radius * 0.04)
            Array(3) { k ->
                val c = when (k) { 0 -> jr; 1 -> jg; else -> jb }
                val wide = Blur.gauss(c, w, h, sigmaWide)
                val near = Blur.gauss(c, w, h, sigmaNear)
                FloatArray(w * h) { (wide[it] * 0.55f + near[it] * 0.45f) }
            }
        } else null
        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                var ba = if (transparent) 0.0 else (base ushr 24) / 255.0
                val ja0 = ja[i].toDouble()
                var r = ch(base, 16) * ba * (1 - ja0) + jr[i]
                var gg = ch(base, 8) * ba * (1 - ja0) + jg[i]
                var b = ch(base, 0) * ba * (1 - ja0) + jb[i]
                ba += (1 - ba) * ja0
                if (bloom != null) {
                    val k = glow * 0.9
                    r = min(1.0, r + bloom[0][i] * k); gg = min(1.0, gg + bloom[1][i] * k); b = min(1.0, b + bloom[2][i] * k)
                    ba = min(1.0, max(ba, max(r, max(gg, b))))
                }
                if (ba <= 0.002) continue
                fun c8(q: Double) = (min(1.0, q / ba) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((ba * 255 + 0.5).toInt() shl 24) or (c8(r) shl 16) or (c8(gg) shl 8) or c8(b)
            }
        }
        return out
    }

    /**
     * The drawn [points] as a smooth curve that keeps their shape: small wiggles under [tolerance]
     * pixels left out (Ramer–Douglas–Peucker), then a centripetal Catmull–Rom spline through the
     * points that remain, sampled every [step]. It starts and ends where and how it was drawn.
     */
    private fun smoothPath(points: List<DoubleArray>, step: Double, tolerance: Double): ArrayList<DoubleArray> {
        val kept = if (tolerance > 0) simplify(points, tolerance) else points
        val n = kept.size
        val out = ArrayList<DoubleArray>()
        if (n < 2) { out += points.first(); out += points.last(); return out }
        fun at(i: Int): DoubleArray = when {
            i < 0 -> doubleArrayOf(2 * kept[0][0] - kept[1][0], 2 * kept[0][1] - kept[1][1])
            i >= n -> doubleArrayOf(2 * kept[n - 1][0] - kept[n - 2][0], 2 * kept[n - 1][1] - kept[n - 2][1])
            else -> kept[i]
        }
        out += kept[0]
        for (i in 0 until n - 1) {
            val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
            // centripetal knots: no loops or overshoots at sharp turns
            fun gap(a: DoubleArray, b: DoubleArray) = max(1e-6, sqrt(hypot(b[0] - a[0], b[1] - a[1])))
            val t1 = gap(p0, p1); val t2 = t1 + gap(p1, p2); val t3 = t2 + gap(p2, p3)
            val steps = max(1, (hypot(p2[0] - p1[0], p2[1] - p1[1]) / step).toInt())
            for (k in 1..steps) {
                val t = t1 + (t2 - t1) * k / steps
                fun lerp(a: DoubleArray, b: DoubleArray, ta: Double, tb: Double) =
                    doubleArrayOf((tb - t) / (tb - ta) * a[0] + (t - ta) / (tb - ta) * b[0], (tb - t) / (tb - ta) * a[1] + (t - ta) / (tb - ta) * b[1])
                val a1 = lerp(p0, p1, 0.0, t1); val a2 = lerp(p1, p2, t1, t2); val a3 = lerp(p2, p3, t2, t3)
                val b1 = lerp(a1, a2, 0.0, t2); val b2 = lerp(a2, a3, t1, t3)
                out += lerp(b1, b2, t1, t2)
            }
        }
        return out
    }

    /** [points] with the ones closer than [tolerance] to the line between their neighbours left out (Ramer–Douglas–Peucker); both ends stay. */
    private fun simplify(points: List<DoubleArray>, tolerance: Double): List<DoubleArray> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack += 0 to points.size - 1
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val ax = points[a][0]; val ay = points[a][1]
            val bx = points[b][0]; val by = points[b][1]
            val len = max(1e-9, hypot(bx - ax, by - ay))
            var far = -1
            var farthest = tolerance
            for (i in a + 1 until b) {
                val d = abs((bx - ax) * (ay - points[i][1]) - (ax - points[i][0]) * (by - ay)) / len
                if (d > farthest) { farthest = d; far = i }
            }
            if (far >= 0) {
                keep[far] = true
                stack += a to far
                stack += far to b
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
