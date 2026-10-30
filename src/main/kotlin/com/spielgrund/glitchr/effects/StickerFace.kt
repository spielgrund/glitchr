package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A drawn face stuck onto the photo, like a doodle on a cup: eyes, cheeks, nose and mouth around a
 * point that the handle moves, and two thin arms from its sides to hands that are dragged in the
 * picture. All in one line color, as if drawn with a marker. The time animates it: the hands wave,
 * the eyes blink and look around, the mouth talks and the whole face bounces.
 */
object StickerFace : Effect("stickerface", "Sticker face", "A drawn face with eyes, cheeks, nose, mouth and arms on the photo – drag it and the hands, animate the time") {
    private val eyeStyles = listOf("Happy arcs", "Dots", "Cartoon", "Wink", "Sleepy", "Hearts", "Crosses", "None")
    private val browStyles = listOf("None", "Friendly", "Angry", "Worried")
    private val cheekStyles = listOf("None", "Round", "Soft blush", "Hatched")
    private val noseStyles = listOf("None", "Dot", "Hook", "Button", "Triangle")
    private val mouthStyles = listOf("Smile", "Grin", "Open", "Cat", "Flat", "Sad", "Tongue out", "One fang", "Two fangs", "None")
    private val handStyles = listOf("Knob", "Ball", "Mitten", "Fingers", "None", "Cat paw")
    private val whiskerStyles = listOf("None", "Straight", "Curved up", "Drooping")
    private val earStyles = listOf("None", "Cat", "Cat, pink inside", "Round", "Round, pink inside")

    // later options were added at the end, so stored settings keep their meaning; None comes first in the menus
    private const val EYES_NONE = 7
    private const val ONE_FANG = 7
    private const val TWO_FANGS = 8
    private const val MOUTH_NONE = 9
    private const val HANDS_NONE = 4
    private const val CAT_PAW = 5

    override val params = listOf(
        Param.Slider("time", "Time", 0, 10000, 0, "", "Animate it: the hands wave, the eyes blink and look around, the mouth talks", decimals = 1),
        Param.Heading("faceHeading", "Face"),
        Param.Slider("centerX", "Position X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Position Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("size", "Size", 1, 200, 40, " %", "Width of the face, of the shorter side of the canvas", transform = true),
        Param.Slider("tilt", "Tilt", -180, 180, 0, "°"),
        Param.Slider("squash", "Height", 30, 200, 100, " %", "Squeezes or stretches the face upright"),
        Param.Color("lineColor", "Line color", 0x1C1C1E),
        Param.Slider("lineWidth", "Line width", 5, 200, 35, " %", "Of the face width, in tenths of a percent: 35 is 3.5 %", decimals = 1),
        Param.Slider("opacity", "Opacity", 0, 100, 92, " %"),
        Param.Heading("eyesHeading", "Eyes"),
        Param.Choice("eyes", "Eyes", eyeStyles, 0, "Happy arcs: closed, smiling · Dots · Cartoon: white with pupils that look around · Wink · Sleepy: hanging lids · Hearts · Crosses", order = listOf(EYES_NONE) + (0 until EYES_NONE)),
        Param.Slider("eyeSize", "Eye size", 20, 300, 100, " %"),
        Param.Slider("eyeSpacing", "Eye spacing", 10, 120, 46, " %", "Of the face width"),
        Param.Slider("eyeHeight", "Eye height", -50, 50, -12, " %", "Above (−) or below the middle, of the face width"),
        Param.Choice("brows", "Eyebrows", browStyles, 0),
        Param.Heading("earsHeading", "Ears"),
        Param.Choice("ears", "Ears", earStyles, 0, "Cat: pointed · Round: like a bear or mouse · pink inside: in the cheek color"),
        Param.Slider("earSize", "Ear size", 20, 300, 100, " %"),
        Param.Slider("earSpacing", "Ear spacing", 10, 200, 80, " %", "Of the face width"),
        Param.Slider("earHeight", "Ear height", -100, 50, -40, " %", "Above (−) or below the middle, of the face width"),
        Param.Slider("earTilt", "Ear tilt", -45, 45, 12, "°", "How far the ears lean outwards"),
        Param.Heading("cheeksHeading", "Cheeks & nose"),
        Param.Choice("cheeks", "Cheeks", cheekStyles, 1),
        Param.Color("cheekColor", "Cheek color", 0xEC3D6E),
        Param.Slider("cheekSize", "Cheek size", 20, 300, 100, " %"),
        Param.Choice("nose", "Nose", noseStyles, 0),
        Param.Choice("whiskers", "Whiskers", whiskerStyles, 0, "Three on each side of the nose, like a cat"),
        Param.Slider("whiskerLength", "Whisker length", 20, 300, 100, " %"),
        Param.Heading("mouthHeading", "Mouth"),
        Param.Choice("mouth", "Mouth", mouthStyles, 0, "One fang, two fangs: a smile with pointed teeth hanging over the lip", order = listOf(MOUTH_NONE) + (0 until MOUTH_NONE)),
        Param.Slider("mouthWidth", "Mouth width", 20, 300, 100, " %"),
        Param.Slider("mouthHeight", "Mouth height", -50, 80, 20, " %", "Below (+) the middle, of the face width"),
        Param.Slider("mouthOpen", "Mouth open", 0, 100, 0, " %", "How far the mouth stands open – talking opens it further from there"),
        Param.Heading("armsHeading", "Arms"),
        Param.Choice("hands", "Hands", handStyles, 0, "Knob: a flat disc like a feeler · Ball · Mitten · Fingers · None: just the arms · Cat paw: with pink toe beans in the cheek color", order = listOf(HANDS_NONE, 0, 1, 2, 3, CAT_PAW)),
        Param.Points("handPoints", "Hands", "0.1000,0.1200;0.9000,0.1600", "Where the hands are – drag them in the picture; every point is a hand, the left ones on the face's left side"),
        Param.Slider("shoulders", "Shoulders", 0, 300, 110, " %", "How far apart the arms start, of the face width"),
        Param.Slider("shoulderHeight", "Shoulder height", -100, 100, 15, " %", "Below (+) the middle, of the face width"),
        Param.Slider("bend", "Elbow", -100, 100, 30, " %", "How far the arms bow out on the way to the hands"),
        Param.Slider("armWidth", "Arm width", 20, 300, 100, " %", "Of the line width"),
        Param.Slider("handSize", "Hand size", 20, 300, 100, " %"),
        Param.Heading("motionHeading", "Animation"),
        Param.Slider("wave", "Waving", 0, 100, 30, " %", "How far the hands wave with the time"),
        Param.Slider("armWave", "Arm wave", 0, 100, 0, " %", "A wave runs along the arms with the time, like a shaken rope"),
        Param.Slider("armWaves", "Wave crests", 1, 8, 2, "", "How many crests run along an arm at once"),
        Param.Slider("fidget", "Fidgeting", 0, 100, 0, " %", "Every part – eyes, brows, cheeks, nose, mouth, arms, hands and the whole face – twitches a little on its own, like a hand-drawn animation"),
        Param.Slider("fidgetRate", "Fidget rate", 1, 30, 8, " /s", "How many times a second the parts jump to a new spot"),
        Param.Slider("speed", "Speed", 1, 400, 100, " %"),
        Param.Slider("blink", "Blinking", 0, 100, 40, " %", "How often the dots and cartoon eyes blink"),
        Param.Slider("talk", "Talking", 0, 100, 0, " %", "The mouth opens and closes with the time"),
        Param.Slider("bounce", "Bouncing", 0, 100, 0, " %", "The face hops up and down with the time"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    private const val MOUTH_DARK = 0x4A0E1A
    private const val TONGUE = 0xE8566E

    /**
     * Everything about the face that the parts need: size, line width, colors, time – and the
     * fidgeting: how far the parts twitch ([jitter], pixels) and the current twitch [step].
     */
    private class Face(val s: Double, val line: Double, val ink: Color, val t: Double, val v: Values, val seed: Long, val jitter: Double, val step: Long)

    /** A part's twitch – shift across, down and a turn – the same throughout a step, new in the next. */
    private fun jitter(f: Face, part: Int): DoubleArray {
        if (f.jitter <= 0) return DoubleArray(3)
        val r = Random(f.seed * 131 + f.step * 7919 + part * 104729L)
        return doubleArrayOf((r.nextDouble() * 2 - 1) * f.jitter, (r.nextDouble() * 2 - 1) * f.jitter, (r.nextDouble() * 2 - 1) * f.jitter / f.s * 3)
    }

    /** Draws a part around ([x], [y]) twitched (see [jitter]). */
    private inline fun fidget(g: Graphics2D, f: Face, part: Int, x: Double, y: Double, draw: () -> Unit) {
        if (f.jitter <= 0) return draw()
        val (jx, jy, turn) = jitter(f, part)
        val old = g.transform
        g.translate(x + jx, y + jy)
        g.rotate(turn)
        g.translate(-x, -y)
        draw()
        g.transform = old
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val speed = v["speed"] / 100.0
        val t = v["time"] / 10.0 * speed
        val s = min(w, h) * v["size"] / 100.0
        if (s < 1) return src.copy()
        val line = max(0.6, s * v["lineWidth"] / 1000.0)
        val ink = Color(v["lineColor"] or (0xFF shl 24), true)
        val face = Face(s, line, ink, t, v, seed, s * 0.025 * v["fidget"] / 100.0, floor(t * v["fidgetRate"]).toLong())

        // the face's frame on the canvas: its middle, bouncing, twitching, tilted, squashed
        val hop = -abs(sin(t * PI * 1.6)) * s * 0.12 * v["bounce"] / 100.0
        val (jx, jy, turn) = jitter(face, 0)
        val frame = AffineTransform().apply {
            translate(w / 2.0 + v["centerX"] / 10.0 + jx * 0.6, h / 2.0 + v["centerY"] / 10.0 + hop + jy * 0.6)
            rotate(Math.toRadians(v["tilt"].toDouble()) + turn * 0.5)
            scale(1.0, v["squash"] / 100.0)
        }

        val layer = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = layer.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        drawArms(g, frame, face, w, h)
        g.transform(frame)
        drawEars(g, face)
        drawCheeks(g, face)
        drawEyes(g, face)
        drawBrows(g, face)
        drawWhiskers(g, face)
        fidget(g, face, 40, 0.0, s * (v["eyeHeight"] / 100.0 + 0.17)) { drawNose(g, face) }
        fidget(g, face, 50, 0.0, s * (v["eyeHeight"] / 100.0 + v["mouthHeight"] / 100.0 + 0.2)) { drawMouth(g, face) }
        g.dispose()

        // the whole drawing at once at its opacity, so crossing lines don't get darker
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, src.data, 0, w)
        val out = img.createGraphics()
        out.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, v["opacity"] / 100f)
        out.drawImage(layer, 0, 0, null)
        out.dispose()
        return Pixels.of(img)
    }

    private fun stroke(width: Double) = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    // ---- eyes ----

    /** 1 open, 0 shut: a quick blink now and then, at its own moments for every [salt]. */
    internal fun blinkOpen(t: Double, amount: Double, seed: Long, salt: Int): Double {
        if (amount <= 0) return 1.0
        val period = 6.5 - 5.0 * amount
        val k = floor(t / period).toLong()
        val r = Random(seed * 31 + k * 7919 + salt)
        val at = r.nextDouble() * (period - 0.4)
        val local = t - k * period - at
        val dur = 0.22
        return if (local in 0.0..dur) abs(local / dur * 2 - 1) else 1.0
    }

    /** Where the pupils look, −1..1 both ways: a new spot every second or so, reached quickly. */
    private fun look(t: Double, seed: Long): Pair<Double, Double> {
        val k = floor(t * 0.8).toLong()
        fun spot(i: Long): Pair<Double, Double> {
            val r = Random(seed * 17 + i * 104729)
            val a = r.nextDouble() * 2 * PI
            val d = if (r.nextDouble() < 0.3) 0.0 else 0.4 + 0.6 * r.nextDouble()
            return cos(a) * d to sin(a) * d * 0.7
        }
        val (ax, ay) = spot(k - 1)
        val (bx, by) = spot(k)
        val f = min(1.0, (t * 0.8 - k) * 6)
        val e = f * f * (3 - 2 * f)
        return ax + (bx - ax) * e to ay + (by - ay) * e
    }

    private fun drawEyes(g: Graphics2D, f: Face) {
        val v = f.v
        val r = f.s * 0.075 * v["eyeSize"] / 100.0
        val dx = f.s * v["eyeSpacing"] / 200.0
        val y = f.s * v["eyeHeight"] / 100.0
        val style = v["eyes"]
        for ((i, side) in listOf(-1, 1).withIndex()) {
            val x = side * dx
            val open = blinkOpen(f.t, v["blink"] / 100.0, f.seed, 0)
            g.color = f.ink
            g.stroke = stroke(f.line)
            fidget(g, f, 10 + i, x, y) {
                when (style) {
                    0 -> happyArc(g, x, y, r)
                    1 -> dot(g, x, y, r, open)
                    2 -> cartoon(g, f, x, y, r, open)
                    3 -> if (i == 0) happyArc(g, x, y, r) else dot(g, x, y, r, open)
                    4 -> sleepy(g, f, x, y, r)
                    5 -> heart(g, x, y, r)
                    EYES_NONE -> {}
                    else -> {
                        val c = r * 0.6
                        g.draw(Line2D.Double(x - c, y - c, x + c, y + c))
                        g.draw(Line2D.Double(x - c, y + c, x + c, y - c))
                    }
                }
            }
        }
    }

    /** An upward arc ∩, the eye closed in a smile. */
    private fun happyArc(g: Graphics2D, x: Double, y: Double, r: Double) =
        g.draw(Arc2D.Double(x - r, y - r * 0.55, 2 * r, 2 * r * 1.1, 20.0, 140.0, Arc2D.OPEN))

    private fun dot(g: Graphics2D, x: Double, y: Double, r: Double, open: Double) {
        val rx = r * 0.48
        val ry = r * 0.62 * max(0.1, open)
        if (open < 0.15) g.draw(Line2D.Double(x - rx, y, x + rx, y))
        else g.fill(Ellipse2D.Double(x - rx, y - ry, 2 * rx, 2 * ry))
    }

    private fun cartoon(g: Graphics2D, f: Face, x: Double, y: Double, r: Double, open: Double) {
        val rx = r * 0.85
        val ry = r * 1.05
        if (open < 0.12) {
            g.draw(Arc2D.Double(x - rx, y - ry * 0.4, 2 * rx, 2 * ry * 0.4, 200.0, 140.0, Arc2D.OPEN))
            return
        }
        val eye = Ellipse2D.Double(x - rx, y - ry * open, 2 * rx, 2 * ry * open)
        g.color = Color.WHITE
        g.fill(eye)
        val (lx, ly) = look(f.t, f.seed)
        val pr = r * 0.48
        val px = x + lx * (rx - pr) * 0.9
        val py = y + ly * (ry - pr) * 0.9
        val old = g.clip
        g.clip(eye)
        g.color = f.ink
        g.fill(Ellipse2D.Double(px - pr, py - pr, 2 * pr, 2 * pr))
        g.color = Color.WHITE
        val gl = pr * 0.35
        g.fill(Ellipse2D.Double(px - pr * 0.45 - gl, py - pr * 0.45 - gl, 2 * gl, 2 * gl))
        g.clip = old
        g.color = f.ink
        g.draw(eye)
    }

    /** Half-shut: a lid line across, the dot peeking out below. */
    private fun sleepy(g: Graphics2D, f: Face, x: Double, y: Double, r: Double) {
        val rx = r * 0.85
        g.draw(Arc2D.Double(x - rx, y - r * 0.7, 2 * rx, 2 * r * 1.2, 200.0, 140.0, Arc2D.OPEN))
        g.draw(Line2D.Double(x - rx * 1.05, y - r * 0.05, x + rx * 1.05, y - r * 0.05))
        g.fill(Arc2D.Double(x - r * 0.42, y - r * 0.42, r * 0.84, r * 0.84, 180.0, 180.0, Arc2D.CHORD))
    }

    private fun heart(g: Graphics2D, x: Double, y: Double, r: Double) {
        val p = GeneralPath()
        p.moveTo(x, y + r * 0.85)
        p.curveTo(x - r * 1.3, y - r * 0.05, x - r * 0.75, y - r * 1.05, x, y - r * 0.4)
        p.curveTo(x + r * 0.75, y - r * 1.05, x + r * 1.3, y - r * 0.05, x, y + r * 0.85)
        p.closePath()
        g.fill(p)
    }

    private fun drawBrows(g: Graphics2D, f: Face) {
        val v = f.v
        val style = v["brows"]
        if (style == 0) return
        val r = f.s * 0.075 * v["eyeSize"] / 100.0
        val dx = f.s * v["eyeSpacing"] / 200.0
        val y = f.s * v["eyeHeight"] / 100.0 - r * 1.75
        g.color = f.ink
        g.stroke = stroke(f.line)
        for (side in listOf(-1, 1)) {
            val x = side * dx
            val inner = x - side * r * 0.9
            val outer = x + side * r * 0.9
            val p = GeneralPath()
            when (style) {
                1 -> { p.moveTo(inner, y + r * 0.1); p.quadTo(x, y - r * 0.45, outer, y + r * 0.15) }
                // inner ends low: angry, high: worried
                2 -> { p.moveTo(inner, y + r * 0.45); p.lineTo(outer, y - r * 0.2) }
                else -> { p.moveTo(inner, y - r * 0.35); p.quadTo(x, y - r * 0.1, outer, y + r * 0.3) }
            }
            fidget(g, f, 20 + side, x, y) { g.draw(p) }
        }
    }

    // ---- cheeks and nose ----

    private fun drawCheeks(g: Graphics2D, f: Face) {
        val v = f.v
        val style = v["cheeks"]
        if (style == 0) return
        val rx = f.s * 0.075 * v["cheekSize"] / 100.0
        val ry = rx * 0.82
        val dx = f.s * v["eyeSpacing"] / 200.0
        val y = f.s * (v["eyeHeight"] / 100.0 + 0.2)
        val c = v["cheekColor"]
        val solid = Color(c or (0xFF shl 24), true)
        for (side in listOf(-1, 1)) fidget(g, f, 30 + side, side * dx, y) {
            val x = side * dx
            when (style) {
                1 -> { g.color = solid; g.fill(Ellipse2D.Double(x - rx, y - ry, 2 * rx, 2 * ry)) }
                2 -> {
                    val old = g.transform
                    g.translate(x, y)
                    g.scale(1.0, ry / rx)
                    val r = rx * 1.5
                    g.paint = RadialGradientPaint(
                        Point2D.Double(0.0, 0.0), r.toFloat(), floatArrayOf(0f, 0.5f, 1f),
                        arrayOf(Color(c or (0xB0 shl 24), true), Color(c or (0x60 shl 24), true), Color(c, true)),
                    )
                    g.fill(Ellipse2D.Double(-r, -r, 2 * r, 2 * r))
                    g.transform = old
                }
                else -> {
                    g.color = solid
                    g.stroke = stroke(f.line * 0.7)
                    for (k in -1..1) {
                        val hx = x + k * rx * 0.55
                        g.draw(Line2D.Double(hx - rx * 0.2, y + ry * 0.45, hx + rx * 0.2, y - ry * 0.45))
                    }
                }
            }
        }
    }

    private fun drawNose(g: Graphics2D, f: Face) {
        val style = f.v["nose"]
        if (style == 0) return
        val n = f.s * 0.045
        val y = f.s * (f.v["eyeHeight"] / 100.0 + 0.17)
        g.color = f.ink
        g.stroke = stroke(f.line * 0.85)
        when (style) {
            1 -> g.fill(Ellipse2D.Double(-n * 0.4, y - n * 0.3, n * 0.8, n * 0.6))
            2 -> g.draw(GeneralPath().apply { moveTo(n * 0.1, y - n * 1.1); quadTo(-n * 0.9, y + n * 0.5, n * 0.5, y + n * 0.55) })
            3 -> {
                g.fill(Ellipse2D.Double(-n * 0.85, y - n * 0.6, n * 1.7, n * 1.2))
                g.color = Color(255, 255, 255, 170)
                g.fill(Ellipse2D.Double(-n * 0.45, y - n * 0.4, n * 0.45, n * 0.3))
            }
            else -> g.draw(GeneralPath().apply { moveTo(0.0, y - n * 0.8); lineTo(-n * 0.55, y + n * 0.45); lineTo(n * 0.55, y + n * 0.45); closePath() })
        }
    }

    // ---- mouth ----

    /** How far the mouth is open from talking: 0..1, chattering. */
    private fun talking(t: Double, amount: Double) =
        if (amount <= 0) 0.0 else amount * max(0.0, abs(sin(t * 9.0)) * (0.6 + 0.4 * sin(t * 2.3)))

    private fun drawMouth(g: Graphics2D, f: Face) {
        val v = f.v
        val a = f.s * 0.2 * v["mouthWidth"] / 100.0
        val y = f.s * (v["eyeHeight"] / 100.0 + v["mouthHeight"] / 100.0 + 0.12)
        // open by the slider, talking opens it further
        val open = v["mouthOpen"] / 100.0
        val talk = open + (1 - open) * talking(f.t, v["talk"] / 100.0)
        g.color = f.ink
        g.stroke = stroke(f.line)
        when (v["mouth"]) {
            0 -> openSmile(g, f, y, a, a * 0.55, talk)
            1 -> openSmile(g, f, y, a, a * 0.45, 0.55 + 0.45 * talk)
            2 -> {
                val rx = a * 0.42
                val ry = a * (0.35 + 0.35 * talk)
                val o = Ellipse2D.Double(-rx, y - ry * 0.5, 2 * rx, 2 * ry)
                g.color = Color(MOUTH_DARK)
                g.fill(o)
                g.color = f.ink
                g.draw(o)
            }
            3 -> g.draw(GeneralPath().apply {
                moveTo(-a * 0.7, y - a * 0.08)
                quadTo(-a * 0.35, y + a * 0.55, 0.0, y)
                quadTo(a * 0.35, y + a * 0.55, a * 0.7, y - a * 0.08)
            })
            4 -> g.draw(GeneralPath().apply { moveTo(-a * 0.8, y + a * 0.15); quadTo(0.0, y + a * 0.05, a * 0.8, y + a * 0.15) })
            5 -> {
                if (talk < 0.05) g.draw(GeneralPath().apply { moveTo(-a, y + a * 0.4); quadTo(0.0, y - a * 0.2, a, y + a * 0.4) })
                else openShape(g, f, GeneralPath().apply {
                    moveTo(-a, y + a * 0.4)
                    quadTo(0.0, y - a * 0.2 - a * talk * 0.6, a, y + a * 0.4)
                    quadTo(0.0, y - a * 0.2 + a * 0.3, -a, y + a * 0.4)
                    closePath()
                }, null)
            }
            ONE_FANG, TWO_FANGS -> fangSmile(g, f, y, a, talk, if (v["mouth"] == ONE_FANG) listOf(0.68) else listOf(0.3, 0.7))
            MOUTH_NONE -> {}
            else -> {
                // the tongue hangs out under the smile, a little to one side
                val tw = a * 0.36
                val tx = a * 0.18
                val ty = y + a * 0.38
                val tongue = GeneralPath().apply {
                    moveTo(tx - tw, ty - a * 0.1)
                    lineTo(tx - tw, ty + a * 0.25)
                    curveTo(tx - tw, ty + a * 0.75, tx + tw, ty + a * 0.75, tx + tw, ty + a * 0.25)
                    lineTo(tx + tw, ty - a * 0.1)
                    closePath()
                }
                g.color = Color(TONGUE)
                g.fill(tongue)
                g.color = f.ink
                g.draw(tongue)
                g.stroke = stroke(f.line * 0.6)
                g.draw(Line2D.Double(tx, ty + a * 0.05, tx, ty + a * 0.35))
                g.stroke = stroke(f.line)
                g.color = f.ink
                g.draw(GeneralPath().apply { moveTo(-a, y); quadTo(0.0, y + a * 0.9, a, y) })
            }
        }
    }

    /**
     * A smile with pointed white teeth hanging from its upper edge at [at] (0..1 from left to right);
     * talking opens it like the plain smile, the fangs staying on top.
     */
    private fun fangSmile(g: Graphics2D, f: Face, y: Double, a: Double, talk: Double, at: List<Double>) {
        val dip = a * 0.5
        if (talk >= 0.05) openSmile(g, f, y, a, dip, talk)
        // the upper edge: the smile's line, or the top of the opened mouth
        val cy = if (talk >= 0.05) y + dip * 0.35 * (1 - talk) else y + dip * 2
        val fw = a * 0.17
        val fh = a * 0.42
        for (t in at) {
            val u = 1 - t
            val bx = -a * u * u + a * t * t
            val by = y * u * u + 2 * u * t * cy + y * t * t
            // the slope of the line there, so the fang sits square on it
            val sx = 2 * a
            val sy = 2 * (1 - 2 * t) * (cy - y)
            val l = hypot(sx, sy)
            val tx = sx / l
            val ty = sy / l
            val fang = GeneralPath().apply {
                moveTo(bx - tx * fw, by - ty * fw)
                quadTo(bx - tx * fw * 0.6 - ty * fh * 0.6, by - ty * fw * 0.6 + tx * fh * 0.6, bx - ty * fh, by + tx * fh)
                quadTo(bx + tx * fw * 0.6 - ty * fh * 0.6, by + ty * fw * 0.6 + tx * fh * 0.6, bx + tx * fw, by + ty * fw)
                closePath()
            }
            g.color = Color.WHITE
            g.fill(fang)
            g.color = f.ink
            g.stroke = stroke(f.line * 0.6)
            g.draw(fang)
        }
        g.stroke = stroke(f.line)
        if (talk < 0.05) g.draw(GeneralPath().apply { moveTo(-a, y); quadTo(0.0, cy, a, y) })
    }

    // ---- ears ----

    /** Two ears on top: pointed like a cat's or round, leaning outwards, maybe pink inside. */
    private fun drawEars(g: Graphics2D, f: Face) {
        val style = f.v["ears"]
        if (style == 0) return
        val size = f.s * 0.17 * f.v["earSize"] / 100.0
        val dx = f.s * f.v["earSpacing"] / 200.0
        val y = f.s * f.v["earHeight"] / 100.0
        val pink = style == 2 || style == 4
        val round = style >= 3
        for (side in listOf(-1, 1)) fidget(g, f, 55 + side, side * dx, y) {
            val old = g.transform
            g.translate(side * dx, y)
            g.rotate(side * Math.toRadians(f.v["earTilt"].toDouble()))
            // round ones cut off flat at the bottom, where they meet the head
            val outer = if (round) Arc2D.Double(-size * 0.85, -size * 1.25, size * 1.7, size * 1.7, -35.0, 250.0, Arc2D.CHORD) else catEar(size)
            g.color = Color.WHITE
            g.fill(outer)
            if (pink) {
                g.color = Color(f.v["cheekColor"])
                g.fill(if (round) Arc2D.Double(-size * 0.48, -size * 0.85, size * 0.96, size * 0.96, -35.0, 250.0, Arc2D.CHORD) else catEar(size * 0.55, size * 0.12))
            }
            g.color = f.ink
            g.stroke = stroke(f.line)
            g.draw(outer)
            g.transform = old
        }
    }

    /** A pointed ear [size] high over its base, the tip rounded, the sides bowing out a little; raised by [lift]. */
    private fun catEar(size: Double, lift: Double = 0.0) = GeneralPath().apply {
        val b = size * 0.62
        val top = -size * 1.25 + lift
        val base = size * 0.35 + lift
        moveTo(-b, base)
        quadTo(-b * 0.85, top * 0.35, -b * 0.12, top + size * 0.08)
        quadTo(0.0, top - size * 0.04, b * 0.12, top + size * 0.08)
        quadTo(b * 0.85, top * 0.35, b, base)
        quadTo(0.0, base - size * 0.18, -b, base)
        closePath()
    }

    // ---- whiskers ----

    /** Three whiskers on each side, fanning out from beside the nose. */
    private fun drawWhiskers(g: Graphics2D, f: Face) {
        val style = f.v["whiskers"]
        if (style == 0) return
        val y = f.s * (f.v["eyeHeight"] / 100.0 + 0.2)
        val len = f.s * 0.3 * f.v["whiskerLength"] / 100.0
        val bend = when (style) { 2 -> -0.18; 3 -> 0.2; else -> 0.0 }
        g.color = f.ink
        g.stroke = stroke(f.line * 0.6)
        for (side in listOf(-1, 1)) fidget(g, f, 45 + side, side * f.s * 0.1, y) {
            for (k in -1..1) {
                val angle = k * 0.22 + 0.04
                val x0 = side * f.s * 0.1
                val y0 = y + k * f.s * 0.025
                val x1 = x0 + side * cos(angle) * len
                val y1 = y0 + sin(angle) * len
                g.draw(GeneralPath().apply {
                    moveTo(x0, y0)
                    quadTo((x0 + x1) / 2, (y0 + y1) / 2 + bend * len, x1, y1 + bend * len * 0.6)
                })
            }
        }
    }

    /** A smile [a] wide each way, its line dipping by [dip]; [open] 0..1 opens it into a D with a tongue. */
    private fun openSmile(g: Graphics2D, f: Face, y: Double, a: Double, dip: Double, open: Double) {
        if (open < 0.05) {
            g.draw(GeneralPath().apply { moveTo(-a, y); quadTo(0.0, y + dip * 2, a, y) })
            return
        }
        val top = y + dip * 0.35 * (1 - open)
        val bottom = y + dip * 2 + a * 0.9 * open
        val shape = GeneralPath().apply {
            moveTo(-a, y)
            quadTo(0.0, top, a, y)
            quadTo(0.0, bottom, -a, y)
            closePath()
        }
        openShape(g, f, shape, Ellipse2D.Double(-a * 0.45, (y + bottom) / 2 + a * 0.05, a * 0.9, a * 0.7))
    }

    /** An open mouth: dark inside, a [tongue] at the bottom, outlined. */
    private fun openShape(g: Graphics2D, f: Face, shape: Shape, tongue: Shape?) {
        g.color = Color(MOUTH_DARK)
        g.fill(shape)
        if (tongue != null) {
            val old = g.clip
            g.clip(shape)
            g.color = Color(TONGUE)
            g.fill(tongue)
            g.clip = old
        }
        g.color = f.ink
        g.draw(shape)
    }

    // ---- arms ----

    private fun drawArms(g: Graphics2D, frame: AffineTransform, f: Face, w: Int, h: Int) {
        val v = f.v
        val hands = PointList.parse(v.text("handPoints"))
        if (hands.isEmpty()) return
        val mid = frame.transform(Point2D.Double(0.0, 0.0), null)
        val armLine = f.line * v["armWidth"] / 100.0
        val handR = f.line * 2.4 * v["handSize"] / 100.0
        g.color = f.ink
        for ((i, p) in hands.withIndex()) {
            // a paw before may have left its bean color
            g.color = f.ink
            var hx = p.x * w
            var hy = p.y * h
            // left of the face: the left shoulder (in the face's own frame)
            val local = frame.inverseTransform(Point2D.Double(hx, hy), null)
            val side = if (local.x < 0) -1.0 else 1.0
            val shoulder = frame.transform(Point2D.Double(side * f.s * v["shoulders"] / 200.0, f.s * v["shoulderHeight"] / 100.0), null)
            // waving: the hand swings to and fro around its spot, each a little after the other
            val reach = hypot(hx - shoulder.x, hy - shoulder.y)
            if (reach < 1) continue
            val wave = v["wave"] / 100.0
            if (wave > 0) {
                val phase = f.t * 2 * PI * 0.9 + i * 1.7 + Random(f.seed + i).nextDouble() * 0.6
                val swing = sin(phase) * reach * 0.22 * wave
                val lift = (1 - cos(2 * phase)) * reach * 0.03 * wave
                // across the arm
                val nx = -(hy - shoulder.y) / reach
                val ny = (hx - shoulder.x) / reach
                hx += nx * swing
                hy += ny * swing - lift
            }
            // fidgeting: the hand twitches, and the elbow on its own
            val (fx, fy, turn) = jitter(f, 60 + i)
            hx += fx * 1.5
            hy += fy * 1.5
            val (ex, ey) = jitter(f, 70 + i)
            val outward = frame.deltaTransform(Point2D.Double(side, 0.0), null).let { d -> val l = hypot(d.x, d.y); Point2D.Double(d.x / l, d.y / l) }
            val dx = hx - shoulder.x
            val dy = hy - shoulder.y
            val len = hypot(dx, dy)
            // the elbow bows away from the face
            var px = -dy / len
            var py = dx / len
            if (px * (shoulder.x - mid.x) + py * (shoulder.y - mid.y) < 0) { px = -px; py = -py }
            val bend = v["bend"] / 100.0
            val c1 = Point2D.Double(shoulder.x + outward.x * len * 0.35 + px * len * bend * 0.25 + ex * 2, shoulder.y + outward.y * len * 0.35 + py * len * bend * 0.25 + ey * 2)
            val c2 = Point2D.Double(hx + px * len * bend * 0.35 - dx * 0.2 - ey * 2, hy + py * len * bend * 0.35 - dy * 0.2 + ex * 2)
            // the arm wave: crests running from the shoulder to the hand
            val ripple = Ripple(f.s * 0.09 * v["armWave"] / 100.0, v["armWaves"].toDouble(), f.t * 2 * PI * 1.3 + i * 2.1)
            val end = taperedCurve(g, shoulder, c1, c2, Point2D.Double(hx, hy), armLine * 1.25, armLine * 0.85, ripple)
            drawHand(g, f, hx, hy, end + turn * 2, handR, armLine)
        }
    }

    /** A wave along an arm: [height] in pixels, [crests] along it, at [phase]; the ends stay put. */
    private class Ripple(val height: Double, val crests: Double, val phase: Double)

    /**
     * A cubic from [p0] to [p3], [w0] wide at the start and [w1] at the end, rippled across by [ripple];
     * returns its direction at the end.
     */
    private fun taperedCurve(g: Graphics2D, p0: Point2D, c1: Point2D, c2: Point2D, p3: Point2D, w0: Double, w1: Double, ripple: Ripple): Double {
        val n = 96
        val xs = DoubleArray(n + 1)
        val ys = DoubleArray(n + 1)
        for (k in 0..n) {
            val t = k / n.toDouble()
            val u = 1 - t
            xs[k] = u * u * u * p0.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * p3.x
            ys[k] = u * u * u * p0.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * p3.y
        }
        if (ripple.height > 0) {
            val bx = xs.copyOf()
            val by = ys.copyOf()
            for (k in 0..n) {
                val t = k / n.toDouble()
                val tx = bx[min(n, k + 1)] - bx[max(0, k - 1)]
                val ty = by[min(n, k + 1)] - by[max(0, k - 1)]
                val l = max(1e-9, hypot(tx, ty))
                // strongest in the middle, nothing at shoulder and hand
                val off = ripple.height * sin(PI * t) * sin(2 * PI * ripple.crests * t - ripple.phase)
                xs[k] = bx[k] - ty / l * off
                ys[k] = by[k] + tx / l * off
            }
        }
        val left = GeneralPath()
        val right = ArrayList<Point2D.Double>()
        for (k in 0..n) {
            val a = max(0, k - 1)
            val b = min(n, k + 1)
            val tx = xs[b] - xs[a]
            val ty = ys[b] - ys[a]
            val l = max(1e-9, hypot(tx, ty))
            val half = (w0 + (w1 - w0) * k / n) / 2
            val nx = -ty / l * half
            val ny = tx / l * half
            if (k == 0) left.moveTo(xs[k] + nx, ys[k] + ny) else left.lineTo(xs[k] + nx, ys[k] + ny)
            right += Point2D.Double(xs[k] - nx, ys[k] - ny)
        }
        for (p in right.asReversed()) left.lineTo(p.x, p.y)
        left.closePath()
        g.fill(left)
        g.fill(Ellipse2D.Double(p0.x - w0 / 2, p0.y - w0 / 2, w0, w0))
        g.fill(Ellipse2D.Double(p3.x - w1 / 2, p3.y - w1 / 2, w1, w1))
        return atan2(ys[n] - ys[n - 3], xs[n] - xs[n - 3])
    }

    private fun drawHand(g: Graphics2D, f: Face, x: Double, y: Double, angle: Double, r: Double, armLine: Double) {
        val style = f.v["hands"]
        if (style == HANDS_NONE) return
        val old = g.transform
        g.translate(x, y)
        g.rotate(angle)
        g.color = f.ink
        when (style) {
            // a flat disc across the arm's end, like a feeler
            0 -> g.fill(Ellipse2D.Double(-r * 0.55, -r, r * 1.3, r * 2))
            1 -> g.fill(Ellipse2D.Double(-r * 0.6, -r * 0.8, r * 1.6, r * 1.6))
            2 -> {
                g.fill(Ellipse2D.Double(-r * 0.3, -r * 0.9, r * 1.9, r * 1.8))
                // the thumb sticks out to one side
                val thumb = AffineTransform().apply { translate(r * 0.35, -r * 0.75); rotate(-0.8) }.createTransformedShape(Ellipse2D.Double(-r * 0.5, -r * 0.28, r, r * 0.56))
                g.fill(thumb)
            }
            CAT_PAW -> {
                // a round palm with four toes, white, outlined, with pink beans
                val k = r * 1.15
                val paw = Area(Ellipse2D.Double(-k * 0.05, -k * 0.95, k * 1.9, k * 1.9))
                val toes = listOf(-1.05, -0.35, 0.35, 1.05).map { a -> Ellipse2D.Double(k * 0.9 + cos(a) * k * 1.05 - k * 0.45, sin(a) * k * 1.05 - k * 0.45, k * 0.9, k * 0.9) }
                for (t in toes) paw.add(Area(t))
                g.color = Color.WHITE
                g.fill(paw)
                g.color = f.ink
                g.stroke = stroke(armLine * 0.8)
                g.draw(paw)
                g.color = Color(f.v["cheekColor"])
                g.fill(Ellipse2D.Double(k * 0.35, -k * 0.5, k * 0.95, k * 1.0))
                for (t in toes) g.fill(Ellipse2D.Double(t.centerX - k * 0.22, t.centerY - k * 0.22, k * 0.44, k * 0.44))
            }
            else -> {
                g.stroke = stroke(armLine * 0.8)
                for (k in -2..2) {
                    val a = k * 0.42
                    g.draw(Line2D.Double(0.0, 0.0, cos(a) * r * 1.5, sin(a) * r * 1.5))
                }
            }
        }
        g.transform = old
    }
}
