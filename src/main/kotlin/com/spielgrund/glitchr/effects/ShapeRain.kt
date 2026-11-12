package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Shape rain: lines in two colors, their front and back cut at a slant, emitted along the top edge
 * and falling down, at a steady speed or pulled by gravity, slanted by a direction. Every line lives
 * for a while and fades away. Oscillators – sine, saw and pulse – add to their speed, all together,
 * each line its own way or as a wave across. Every line can cast a soft drop shadow on the lines
 * behind it. Everything is worked out in closed form from the time, so the animation runs smoothly
 * and can be scrubbed. Optionally drawn on a coarse pixel grid for a retro look.
 */
object ShapeRain : Effect("shaperain", "Shape rain", "Slanted lines in two colors raining down from the top – falling steadily or with gravity, living for a while, with sine, saw and pulse oscillators on their speed and drop shadows") {
    private val motions = listOf("Steady speed", "Gravity")
    private const val GRAVITY = 1
    private val phases = listOf("All together", "Each its own", "Wave across")
    private const val TOGETHER = 0
    private const val WAVE = 2
    private const val SHADOW_BANDS = 4

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: the lines fall", decimals = 3),
        Param.Heading("emitHeading", "Emitter"),
        Param.Slider("count", "Lines", 1, 5000, 300, "", "About how many are on the picture at once"),
        Param.Slider("emitHeight", "Emitter height", 0, 100, 0, " %", "0: they come in at the top edge · higher: they appear anywhere in this top part of the picture"),
        Param.Slider("direction", "Direction", -7000, 7000, 0, "°", "Where they fall: 0° straight down, positive to the right", decimals = 2),
        Param.Slider("depth", "Depth", 0, 100, 40, " %", "Small lines are farther away: slower and fainter"),
        Param.Heading("lifeHeading", "Lifetime"),
        Param.Slider("lifetime", "Lifetime", 5, 3000, 300, " s", "How long a line lives", decimals = 2),
        Param.Slider("lifeVariation", "Lifetime variation", 0, 100, 30, " %", "Some live longer, some shorter"),
        Param.Slider("fadeIn", "Fade in", 0, 100, 5, " %", "Of its life"),
        Param.Slider("fadeOut", "Fade out", 0, 100, 30, " %", "Of its life"),
        Param.Heading("motionHeading", "Motion"),
        Param.Choice("motion", "Motion", motions, 0, "Steady speed: they keep their speed · Gravity: they start slowly and fall faster and faster"),
        Param.Slider("speed", "Speed", 0, 500, 40, " %", "Of the picture's height per second – with gravity the speed they start with"),
        Param.Slider("gravity", "Gravity", 1, 500, 100, " %", "Of the picture's height per second, every second").shownWhen("motion", GRAVITY),
        Param.Slider("speedVariation", "Speed variation", 0, 100, 40, " %", "Some faster, some slower"),
        Param.Heading("lineHeading", "Lines"),
        Param.Slider("width", "Width", 1, 300, 6, " %", "Of a hundredth of the shorter side", decimals = 1),
        Param.Slider("length", "Length", 1, 200, 10, " %", "Of the shorter side"),
        Param.Slider("sizeVariation", "Size variation", 0, 100, 50, " %", "Width and length together"),
        Param.Slider("lengthVariation", "Length variation", 0, 100, 40, " %"),
        Param.Slider("stretch", "Motion stretch", 0, 300, 50, " %", "They get longer the faster they fall"),
        Param.Slider("frontSlant", "Front slant", -75, 75, 30, "°", "Cuts the front at a slant – 0°: square"),
        Param.Slider("backSlant", "Back slant", -75, 75, 30, "°", "Cuts the back at a slant – the same as the front: a parallelogram"),
        Param.Heading("colorHeading", "Colors"),
        Param.Color("color1", "Color 1", 0x00E5FF),
        Param.Color("color2", "Color 2", 0xFF2D95),
        Param.Slider("mix", "Color 2 share", 0, 100, 35, " %"),
        Param.Slider("opacity", "Opacity", 1, 100, 100, " %"),
        Param.Heading("oscHeading", "Oscillators"),
        Param.Choice("phase", "Phase", phases, 1, "All together: every line swings the same · Each its own · Wave across: the swing travels across the picture"),
        Param.Slider("waves", "Waves", 1, 20, 2, "", "How many waves across the picture").shownWhen("phase", WAVE),
        Param.Slider("sine", "Sine", 0, 200, 0, " %", "Faster and slower, smoothly – of the picture's height per second"),
        Param.Slider("sineRate", "Sine rate", 1, 500, 80, " /s", "Cycles per second", decimals = 2),
        Param.Slider("saw", "Saw", 0, 200, 0, " %", "Speeds up slowly, then snaps back to slow"),
        Param.Slider("sawRate", "Saw rate", 1, 500, 100, " /s", decimals = 2),
        Param.Slider("pulse", "Pulse", 0, 200, 0, " %", "Bursts of speed, switched hard on and off"),
        Param.Slider("pulseRate", "Pulse rate", 1, 2000, 200, " /s", decimals = 2),
        Param.Slider("pulseWidth", "Pulse width", 1, 99, 50, " %", "How long of every cycle the burst lasts"),
        Param.Heading("dropHeading", "Drop shadow"),
        Param.Slider("dropShadow", "Drop shadow", 0, 100, 0, " %", "Every line casts a shadow on the lines behind it and on the background"),
        Param.Slider("dropAngle", "Falls to", 0, 359, 315, "°", "Where the shadows fall: 0° right, 270° down"),
        Param.Slider("dropDistance", "Distance", 0, 1000, 150, " %", "Of a line's width"),
        Param.Slider("dropSoftness", "Softness", 0, 500, 50, " %", "Of a line's width"),
        Param.Color("dropColor", "Shadow color", 0x000000),
        Param.Heading("lookHeading", "Look"),
        Param.Slider("pixel", "Pixel grid", 1, 32, 1, " px", "Draws it on a coarse grid of pixels – 1: smooth"),
        Param.Toggle("transparent", "Transparent background", false, "Only the lines (and their shadows), to lay over a picture"),
    )

    /** 0..1 for line [i] and [salt]. */
    private fun rnd(i: Long, salt: Int, seed: Long): Double {
        var z = seed * -0x61c8864680b583ebL + i * 0x5851F42D4C957F2DL + salt * 0xC2B2AE3DL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return ((z xor (z ushr 31)) ushr 11) * (1.0 / (1L shl 53))
    }

    private fun frac(u: Double) = u - floor(u)

    /** Sine, saw and pulse of one cycle per unit at [u], and their integrals – so the way they add up is exact. */
    private fun wave(kind: Int, u: Double, width: Double): Double = when (kind) {
        0 -> sin(2 * PI * u)
        1 -> 2 * frac(u) - 1
        else -> if (frac(u) < width) 1.0 else 0.0
    }

    private fun waveIntegral(kind: Int, u: Double, width: Double): Double = when (kind) {
        0 -> -cos(2 * PI * u) / (2 * PI)
        1 -> frac(u) * frac(u) - frac(u)
        else -> floor(u) * width + min(frac(u), width)
    }

    private class Drop(val z: Double, val shape: Shape, val color: Color, val alpha: Float, val width: Double)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val grid = v["pixel"].coerceAtLeast(1)
        // drawn on the coarse grid, then blown up
        val w = ceil(src.width / grid.toDouble()).toInt()
        val h = ceil(src.height / grid.toDouble()).toInt()
        val short = min(w, h).toDouble()
        val time = v["time"] / 1000.0
        val gravityOn = v["motion"] == GRAVITY
        val speed = h * v["speed"] / 100.0
        val gravity = h * v["gravity"] / 100.0
        val variation = v["speedVariation"] / 100.0
        val depth = v["depth"] / 100.0
        val dir = Math.toRadians(v["direction"] / 100.0)
        val dx = sin(dir)
        val dy = cos(dir)
        val width = short * v["width"] / 10.0 / 100.0
        val length = short * v["length"] / 100.0
        val sizeVariation = v["sizeVariation"] / 100.0
        val lengthVariation = v["lengthVariation"] / 100.0
        val lifetime = v["lifetime"] / 100.0
        val lifeVariation = v["lifeVariation"] / 100.0
        val emitHeight = v["emitHeight"] / 100.0
        // fading in and out together never take more than the whole life
        val fadeSum = (v["fadeIn"] + v["fadeOut"]) / 100.0
        val fadeIn = v["fadeIn"] / 100.0 / max(1.0, fadeSum)
        val fadeOut = v["fadeOut"] / 100.0 / max(1.0, fadeSum)

        // how long the slowest one takes to cross the picture – no line is seen for longer than that or its life
        val slowest = max(speed * (1 - variation) * (1 - depth * 0.6), h * 0.05)
        val reach = h * 1.25 + length * 3
        val cross = if (gravityOn) (-slowest + sqrt(slowest * slowest + 2 * gravity * (1 - depth * 0.6) * reach)) / (gravity * (1 - depth * 0.6))
        else reach / slowest
        val window = min(cross, lifetime * (1 + lifeVariation * 0.9))
        val typical = if (gravityOn) (-speed + sqrt(speed * speed + 2 * gravity * h)) / gravity else h / max(speed, h * 0.05)
        val rate = v["count"] / max(0.05, min(typical, lifetime))
        // warmed up: at time 0 the picture is already full
        val now = time + window
        val first = floor((now - window) * rate).toLong()
        val last = floor(now * rate).toLong()

        val phaseMode = v["phase"]
        val waves = v["waves"]
        val pulseWidth = v["pulseWidth"] / 100.0
        val oscillators = listOf(
            doubleArrayOf(v["sine"] / 100.0 * h, v["sineRate"] / 100.0),
            doubleArrayOf(v["saw"] / 100.0 * h, v["sawRate"] / 100.0),
            doubleArrayOf(v["pulse"] / 100.0 * h, v["pulseRate"] / 100.0),
        )

        val c1 = Color(v["color1"])
        val c2 = Color(v["color2"])
        val mix = v["mix"] / 100.0
        val opacity = v["opacity"] / 100.0
        val stretch = v["stretch"] / 100.0
        val tanFront = tan(Math.toRadians(v["frontSlant"].toDouble()))
        val tanBack = tan(Math.toRadians(v["backSlant"].toDouble()))
        val thinnest = if (grid > 1) 1.0 else 0.6

        val drops = ArrayList<Drop>()
        for (i in first..last) {
            val born = (i + rnd(i, 1, seed) * 0.95) / rate
            val age = now - born
            val life = max(0.02, lifetime * (1 + (rnd(i, 12, seed) - 0.5) * 2 * lifeVariation * 0.9))
            if (age < 0 || age > life || age > window) continue
            // farther away: smaller, slower, fainter
            val z = rnd(i, 2, seed)
            val near = 1 - depth * 0.6 * (1 - z)
            val sized = near * (1 + (rnd(i, 3, seed) - 0.5) * 2 * sizeVariation * 0.8)
            val lw = max(thinnest, width * sized)
            val sp = speed * near * (1 + (rnd(i, 4, seed) - 0.5) * 2 * variation)
            val gr = gravity * near
            val x0 = -0.15 * w + rnd(i, 5, seed) * w * 1.3 - dx * h * 0.5
            val y0 = -lw * (2 + abs(tanFront)) + rnd(i, 6, seed) * h * emitHeight
            // the fall itself
            var fall = sp * age + (if (gravityOn) 0.5 * gr * age * age else 0.0)
            var fallSpeed = sp + (if (gravityOn) gr * age else 0.0)
            // the oscillators on the speed – what they add up to, in closed form on the time of the picture
            val t0 = born - window
            val t1 = time
            val phase = when (phaseMode) {
                TOGETHER -> 0.0
                WAVE -> x0 / w * waves
                else -> rnd(i, 7, seed + 1)
            }
            for ((k, osc) in oscillators.withIndex()) {
                val amp = osc[0] * near
                val f = osc[1]
                if (amp <= 0) continue
                fall += amp / f * (waveIntegral(k, f * t1 + phase, pulseWidth) - waveIntegral(k, f * t0 + phase, pulseWidth))
                fallSpeed += amp * wave(k, f * t1 + phase, pulseWidth)
            }
            val x = x0 + dx * fall
            val y = y0 + dy * fall
            if (y < -reach * 0.5 || y > h + reach * 0.2 || x < -w * 0.3 || x > w * 1.3) continue

            val fade = min(1.0, if (fadeIn > 0) age / (fadeIn * life) else 1.0) *
                min(1.0, if (fadeOut > 0) (life - age) / (fadeOut * life) else 1.0)
            val alpha = opacity * (1 - depth * 0.5 * (1 - z)) * fade
            if (alpha <= 0.002) continue
            // the streak behind its head, its ends cut at a slant – the back never passes the front
            val hw = lw / 2
            val len = max(length * sized * (1 + (rnd(i, 8, seed) - 0.5) * 2 * lengthVariation * 0.8) + abs(fallSpeed) * stretch / 24,
                hw * abs(tanFront - tanBack) + thinnest)
            val outline = Path2D.Double().apply {
                moveTo(-hw, -hw * tanFront)
                lineTo(hw, hw * tanFront)
                lineTo(hw, -len + hw * tanBack)
                lineTo(-hw, -len - hw * tanBack)
                closePath()
            }
            // along the way it moves, turned around when an oscillator drives it backwards
            val at = AffineTransform.getTranslateInstance(x, y)
            at.rotate(atan2(dy, dx) - PI / 2 + (if (fallSpeed < 0) PI else 0.0))
            val color = if (rnd(i, 9, seed) < mix) c2 else c1
            drops += Drop(z, at.createTransformedShape(outline), color, alpha.toFloat(), lw)
        }
        // far ones first, the near ones over them
        drops.sortBy { it.z }

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        if (!v.bool("transparent")) {
            // the base, at the grid's size
            g.drawImage(src.toImage(), 0, 0, w, h, null)
        }
        fun smooth(gr: java.awt.Graphics2D) {
            if (grid > 1) return
            gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gr.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        }
        smooth(g)

        val dropShadow = v["dropShadow"] / 100.0
        val bands = if (dropShadow > 0) SHADOW_BANDS else 1
        val dropAngle = Math.toRadians(v["dropAngle"].toDouble())
        val dropDistance = v["dropDistance"] / 100.0
        val dropSoftness = v["dropSoftness"] / 100.0
        val dropColor = v["dropColor"]
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        var from = 0
        for (b in 0 until bands) {
            // the lines of one slice of depth: their shadows fall on everything behind them
            var to = from
            while (to < drops.size && (b == bands - 1 || drops[to].z < (b + 1.0) / bands)) to++
            val band = drops.subList(from, to)
            from = to
            if (band.isEmpty()) continue
            if (dropShadow > 0) {
                val mask = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val mg = mask.createGraphics()
                smooth(mg)
                mg.color = Color.BLACK
                for (d in band) {
                    mg.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, d.alpha)
                    val off = d.width * dropDistance
                    mg.fill(AffineTransform.getTranslateInstance(cos(dropAngle) * off, -sin(dropAngle) * off).createTransformedShape(d.shape))
                }
                mg.dispose()
                val m = (mask.raster.dataBuffer as DataBufferInt).data
                var plane = FloatArray(w * h) { (m[it] ushr 24) / 255f }
                val radius = (band.sumOf { it.width } / band.size * dropSoftness * 0.6).roundToInt()
                if (radius > 0) plane = Grow.blur(plane, w, h, radius)
                for (p in pixels.indices) {
                    val s = (plane[p] * dropShadow).coerceIn(0.0, 1.0)
                    if (s <= 0) continue
                    val c = pixels[p]
                    val a = (c ushr 24) / 255.0
                    val outA = s + a * (1 - s)
                    fun ch(sh: Int) = (((dropColor shr sh and 0xFF) * s + (c shr sh and 0xFF) * a * (1 - s)) / outA).roundToInt().coerceIn(0, 255)
                    pixels[p] = ((outA * 255).roundToInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
            for (d in band) {
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, d.alpha)
                g.color = d.color
                g.fill(d.shape)
            }
        }
        g.dispose()

        if (grid == 1) return Pixels.of(img)
        // blown up to the canvas, every grid cell one hard pixel
        val small = Pixels.of(img)
        val out = Pixels(src.width, src.height)
        for (y in 0 until src.height) {
            val sy = min(h - 1, y / grid)
            for (x in 0 until src.width) out.data[y * src.width + x] = small.data[sy * w + min(w - 1, x / grid)]
        }
        return out
    }
}
