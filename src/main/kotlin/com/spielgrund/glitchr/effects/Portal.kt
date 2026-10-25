package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A portal: an ellipse (size, width, tilt; the center draggable) with a ragged, glowing rim,
 * filled with a swirling vortex, a rippling surface like a Stargate, or ringed by spinning sparks
 * like a sorcerer's gate. Sparks fly off the rim; the inside can also be a hole, showing
 * the layers below. Time turns everything – animate it.
 */
object Portal : Effect("portal", "Portal", "A swirling, sparking or rippling portal with a glowing rim – its inside can be a hole") {
    private val styles = listOf("Vortex", "Spark ring", "Ripple")
    private val insides = listOf("Pattern", "Dark", "Transparent")

    /** Color schemes: core, color, rim and glow. Rainbow turns the hue around the portal, Custom takes the three colors. */
    private val schemes = listOf(
        "Slime green" to intArrayOf(0xF2FFC8, 0x2EC23A, 0xB8FF4A),
        "Sorcerer orange" to intArrayOf(0xFFF0B0, 0xE8650E, 0xFFA020),
        "Stargate blue" to intArrayOf(0xDDEEFF, 0x2A6CFF, 0x8FC8FF),
        "Void purple" to intArrayOf(0xF4D8FF, 0x6A1FD0, 0xC070FF),
        "Hellfire" to intArrayOf(0xFFE2A0, 0xB01408, 0xFF4A12),
        "Ice" to intArrayOf(0xFFFFFF, 0x3FB8D8, 0xB8F4FF),
        "Toxic pink" to intArrayOf(0xFFE0F4, 0xE0208C, 0xFF7AD0),
        "Gold" to intArrayOf(0xFFF6D8, 0xB8860B, 0xFFD24A),
        "Rainbow" to intArrayOf(0xFFFFFF, 0, 0),
        "Custom" to intArrayOf(0, 0, 0),
    )
    private val RAINBOW = schemes.size - 2
    private val CUSTOM = schemes.size - 1

    override val params = listOf(
        Param.Choice(
            "style", "Style", styles, 0,
            "Vortex: swirling arms pulled into the middle · Spark ring: a ring of spinning sparks · Ripple: a rippling surface like water standing upright",
        ),
        Param.Choice("inside", "Inside", insides, 0, "What fills the portal: its pattern, darkness, or a hole showing the layers below"),
        Param.Slider("time", "Time", 0, 100000, 0, "", "Turns the vortex, spins the sparks and moves the ripples – animate it", decimals = 2),
        Param.Slider("spin", "Spin", -300, 300, 100, " %", "How fast and which way it turns with time"),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("size", "Size", 100, 10000, 3000, " %", "Radius, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("aspect", "Width", 10, 300, 70, " %", "Of the height: below 100 % an upright oval like a door, above a lying one"),
        Param.Slider("tilt", "Tilt", -18000, 18000, 0, "°", decimals = 2),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("arms", "Arms", 1, 16, 4, tip = "Swirling arms of the vortex, waves of the ripple"),
        Param.Slider("twist", "Twist", -100, 100, 55, " %", "How tightly the arms wind into the middle"),
        Param.Slider("turbulence", "Turbulence", 0, 100, 45, " %", "Noise churning the pattern"),
        Param.Slider("ragged", "Ragged edge", 0, 100, 30, " %", "How much the rim frays and flickers"),
        Param.Slider("rim", "Rim width", 1, 60, 10, " %", "Of the radius"),
        Param.Slider("sparks", "Sparks", 0, 100, 40, " %", "Sparks flying off the rim – the ring itself in the spark style"),
        Param.Slider("sparkReach", "Spark reach", 0, 100, 35, " %", "How far the sparks fly, of the radius"),
        Param.Heading("lightHeading", "Light"),
        Param.Choice("scheme", "Colors", schemes.map { it.first }, 0, "Rainbow: the hue turns around the portal · Custom: the three colors below"),
        Param.Color("core", "Core", 0xF2FFC8, "Custom: the bright middle and the sparks' cores"),
        Param.Color("color", "Color", 0x2EC23A, "Custom: the vortex and the ripples"),
        Param.Color("rimColor", "Rim and glow", 0xB8FF4A, "Custom"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %"),
        Param.Slider("glowSize", "Glow size", 1, 200, 35, " %", "Of the radius"),
        Param.Toggle("transparent", "Transparent background", false, "Only the portal and its glow, without the base color"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val random = Random(seed)
        val style = v["style"]
        val inside = v["inside"]
        val time = v["time"] / 100.0
        val turn = time * v["spin"] / 100.0
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        val radius = max(1.0, v["size"] / 10000.0 * min(w, h))
        val aspect = v["aspect"] / 100.0
        val tilt = Math.toRadians(v["tilt"] / 100.0)
        val ct = cos(tilt)
        val st = sin(tilt)
        val arms = v["arms"]
        val twist = v["twist"] / 100.0 * 3
        val turb = v["turbulence"] / 100.0
        val ragged = v["ragged"] / 100.0
        val rimW = v["rim"] / 100.0
        val glow = v["glow"] / 100.0
        val glowSize = v["glowSize"] / 100.0
        // one pixel in portal units (the shorter axis), for a soft, anti-aliased edge
        val px = 1.0 / (radius * min(1.0, aspect))

        /** Where the rim is at angle [a]: around 1, frayed by noise that crawls with time. */
        fun edge(a: Double): Double {
            if (ragged <= 0) return 1.0
            val n = noise.fbm(cos(a) * 2.2 + 11.3, sin(a) * 2.2 + time * 0.35, 3)
            val fine = noise.perlin(cos(a) * 9 + 40.1, sin(a) * 9 - time * 0.9)
            return 1 + ragged * (0.12 * n + 0.04 * fine)
        }

        /** Portal units → canvas pixels. */
        fun toCanvas(u: Double, vv: Double): DoubleArray {
            val dx = u * radius * aspect
            val dy = vv * radius
            return doubleArrayOf(cx + dx * ct - dy * st, cy + dx * st + dy * ct)
        }

        // ---- sparks: drawn as white streaks, their brightness the core, blurred the glow ----
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        val lineW = max(0.6, radius * 0.008)

        /** A streak through [pts] (portal units), thinning towards its end. */
        fun streak(pts: List<DoubleArray>, width: Double, alpha: Double) {
            g.color = Color(1f, 1f, 1f, alpha.coerceIn(0.0, 1.0).toFloat())
            for (i in 1 until pts.size) {
                val a = toCanvas(pts[i - 1][0], pts[i - 1][1])
                val b = toCanvas(pts[i][0], pts[i][1])
                val wd = max(0.35, width * (1 - 0.8 * i / pts.size))
                g.stroke = BasicStroke(wd.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(b[0], b[1], a[0], a[1]))
            }
        }

        val sparks = v["sparks"] / 100.0
        val reach = v["sparkReach"] / 100.0
        val dirSign = if (v["spin"] < 0) -1.0 else 1.0
        if (style == 1) {
            // the ring: many short arcs around the rim, each spinning at its own speed
            val n = (300 + 1500 * sparks).toInt()
            repeat(n) {
                val a0 = random.nextDouble() * 2 * PI
                val speed = 0.6 + random.nextDouble() * 0.8
                val a = a0 + turn * 2 * PI * 0.25 * speed
                val r = 1 + (random.nextDouble() - 0.5) * rimW * 1.4
                val len = 0.08 + random.nextDouble() * 0.35
                val pts = (0..6).map { k ->
                    val b = a - dirSign * len * k / 6
                    val rr = r * edge(b) * (1 + (random.nextDouble() - 0.5) * 0.004)
                    doubleArrayOf(cos(b) * rr, sin(b) * rr)
                }
                streak(pts, lineW * (0.5 + random.nextDouble()), 0.35 + random.nextDouble() * 0.65)
            }
        }
        if (sparks > 0 && reach > 0) {
            // sparks flying off: from the rim, along it and outwards, looping with time
            val n = (if (style == 1) 1200 else 600) * sparks
            repeat(n.toInt()) {
                val a0 = random.nextDouble() * 2 * PI
                val phase = random.nextDouble()
                val speed = 0.5 + random.nextDouble()
                val life = (phase + time * 0.3 * speed) % 1.0
                val a = a0 + turn * 2 * PI * 0.25
                val far = reach * (0.4 + random.nextDouble() * 0.9)
                val curl = 0.5 + random.nextDouble()
                fun at(t: Double): DoubleArray {
                    // tangentially off the rim, curving outwards, sinking a little
                    val b = a + dirSign * t * curl * 0.8
                    val r = edge(a) * (1 + t * far)
                    return doubleArrayOf(cos(b) * r, sin(b) * r + t * t * far * 0.4)
                }
                val tail = 0.15 + random.nextDouble() * 0.2
                val pts = (0..5).map { k -> at(max(0.0, life - tail * k / 5)) }
                streak(pts, lineW * (0.6 + random.nextDouble() * 0.8) * (1 - life * 0.6), 1 - life * life)
            }
        }
        g.dispose()
        val sparkCore = FloatArray(w * h) { ((img.raster.dataBuffer as DataBufferInt).data[it] ushr 24) / 255f }
        val sparkGlow = if (glow > 0) Blur.gauss(sparkCore, w, h, max(1.0, radius * 0.04)) else null

        val scheme = v["scheme"].coerceIn(0, CUSTOM)
        val colors = if (scheme == CUSTOM) intArrayOf(v["core"], v["color"], v["rimColor"]) else schemes[scheme].second
        val core = colors[0]
        val rainbow = scheme == RAINBOW
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        val k = twist
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                // into portal units: untilted, the width undone
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val u = (dx * ct + dy * st) / (radius * aspect)
                val vv = (-dx * st + dy * ct) / radius
                val r = hypot(u, vv)
                val a = atan2(vv, u)
                val e = edge(a)
                val rn = r / e
                // inside: 1 within, 0 beyond the rim, soft over a pixel
                val cover = ((1 - rn) / px + 0.5).coerceIn(0.0, 1.0)
                // rainbow: the hue runs around the portal, turning with the time, shifting towards the middle
                val hue = (a / (2 * PI) + turn * 0.05 + rn * 0.25).toFloat()
                val main = if (rainbow) Color.HSBtoRGB(hue, 0.9f, 0.9f) and 0xFFFFFF else colors[1]
                val rimC = if (rainbow) Color.HSBtoRGB(hue, 0.55f, 1f) and 0xFFFFFF else colors[2]

                // the fill
                var fr = 0.0; var fg = 0.0; var fb = 0.0
                if (cover > 0 && inside != 2) {
                    if (inside == 1) {
                        fr = 0.01; fg = 0.01; fb = 0.02
                    } else {
                        val lr = ln(max(rn, 0.02))
                        val t: Double
                        if (style == 2) {
                            // ripple: rings running outwards, wobbling with noise, lit from the top left
                            val wob = turb * 1.5 * noise.fbm(u * 2.5 + 3.7, vv * 2.5 - time * 0.2, 3)
                            val ph = rn * arms * 2.2 * PI - turn * 2 * PI * 0.5 + wob * 2 + sin(a * 3 + rn * 4) * 0.3 * turb
                            val slope = cos(ph)
                            val light = (u * -0.5 + vv * -0.6) / max(0.15, rn) * slope
                            t = (0.45 + 0.25 * sin(ph) + 0.35 * light * rn).coerceIn(0.0, 1.0) * (0.75 + 0.25 * (1 - rn)) + (1 - rn) * (1 - rn) * 0.35
                        } else {
                            // vortex: log-spiral arms, the noise swirled along with them
                            val sa = a + k * lr - turn * 2 * PI * 0.25
                            val nx = cos(sa) * rn * 2.5
                            val ny = sin(sa) * rn * 2.5
                            val n = noise.fbm(nx + 7.1, ny - 3.3, 4)
                            val band = 0.5 + 0.5 * sin(arms * sa + turb * 6 * n + lr * 0.5)
                            t = band * (0.55 + 0.25 * rn) + (1 - rn) * (1 - rn) * 0.7 + n * 0.2 * turb
                        }
                        val tc = (if (style == 1) t * 0.35 else t).coerceIn(0.0, 1.2)
                        // dark color → color → core
                        fun mix(sh: Int): Double {
                            val m = ch(main, sh)
                            return if (tc < 0.5) m * (0.15 + 1.7 * tc) else m + (ch(core, sh) - m) * min(1.0, (tc - 0.5) * 1.6)
                        }
                        fr = mix(16); fg = mix(8); fb = mix(0)
                    }
                }

                // light: the rim, the glow around it, the sparks
                val dRim = (rn - 1) / max(0.005, rimW)
                val rimLight = exp(-dRim * dRim * 2.0) * (0.75 + 0.25 * noise.perlin(cos(a) * 14, sin(a) * 14 + time * 1.3)) *
                    (if (style == 1) 0.45 else 1.0)
                val halo = if (rn > 1 && glow > 0) exp(-(rn - 1) / max(0.01, glowSize)) * 0.55 * glow else 0.0
                val inner = if (rn < 1 && glow > 0) exp(-(1 - rn) / max(0.01, glowSize * 0.5)) * 0.35 * glow else 0.0
                val sc = sparkCore[i].toDouble()
                val sg = if (sparkGlow == null) 0.0 else min(1.0, sparkGlow[i] * 3.0) * glow * 0.7
                val rimSum = rimLight + halo + inner + sg
                var lr = ch(rimC, 16) * rimSum + ch(core, 16) * (sc + rimLight * 0.35)
                var lg = ch(rimC, 8) * rimSum + ch(core, 8) * (sc + rimLight * 0.35)
                var lb = ch(rimC, 0) * rimSum + ch(core, 0) * (sc + rimLight * 0.35)

                // compose, premultiplied: the background, the fill (or the hole), the light added on top
                val base = src.data[i]
                var ba = if (transparent) 0.0 else (base ushr 24) / 255.0
                var pr = ch(base, 16) * ba
                var pg = ch(base, 8) * ba
                var pb = ch(base, 0) * ba
                if (inside == 2) {
                    pr *= 1 - cover; pg *= 1 - cover; pb *= 1 - cover; ba *= 1 - cover
                } else {
                    pr += (fr - pr) * cover; pg += (fg - pg) * cover; pb += (fb - pb) * cover; ba += (1 - ba) * cover
                }
                lr = min(1.0, lr); lg = min(1.0, lg); lb = min(1.0, lb)
                pr = min(1.0, pr + lr); pg = min(1.0, pg + lg); pb = min(1.0, pb + lb)
                val oa = min(1.0, max(ba, max(lr, max(lg, lb))))
                if (oa <= 0.002) continue
                fun c8(p: Double) = (min(1.0, p / oa) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(pr) shl 16) or (c8(pg) shl 8) or c8(pb)
            }
        }
        return out
    }
}
