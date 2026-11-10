package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * An array of laser beams shooting out of one point, like a laser show through stage haze. The
 * beams are lines in space – a flat fan, a cone, a grid or scattered at random – turned around
 * all three axes and seen through a camera, so they come towards the viewer, getting wider and
 * spreading in perspective. Each a bright core in a glow of its color, fading along its length,
 * shown up unevenly by drifting haze; a flare where they come out. The time sweeps and flickers them.
 */
object LaserArray : Effect("laserarray", "Laser array", "Laser beams fanning out of one point, turnable in all three directions – through drifting haze, with glow and flare") {
    private val layouts = listOf("Fan", "Cone", "Grid", "Random")
    private const val FAN = 0
    private const val CONE = 1
    private const val GRID = 2
    private val colorModes = listOf("One color", "Two colors alternating", "Gradient across", "Rainbow")

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: sweep, flicker and drifting haze", decimals = 3),
        Param.Heading("sourceHeading", "Source"),
        Param.Slider("centerX", "Position X", -50000, 50000, 0, " px", "Where the beams come out, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Position Y", -50000, 50000, -2500, " px", "Where the beams come out, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("distance", "Distance", 20, 500, 100, " %", "How far away the source is: near gives strong perspective, far nearly parallel beams"),
        Param.Heading("arrayHeading", "Array"),
        Param.Choice("layout", "Layout", layouts, FAN, "Fan: in one plane · Cone: on the surface of a cone · Grid: rows and columns · Random: scattered in the spread"),
        Param.Slider("count", "Beams", 1, 120, 18),
        Param.Slider("rows", "Rows", 1, 20, 3).shownWhen("layout", GRID),
        Param.Slider("spread", "Spread", 0, 17000, 7000, "°", "How widely the beams fan out", decimals = 2),
        Param.Slider("spread2", "Spread across", 0, 17000, 2500, "°", "The spread in the other direction", decimals = 2).shownWhen("layout", GRID, 3),
        Param.Slider("irregular", "Irregular", 0, 100, 0, " %", "The beams a little off their even places, and some brighter than others"),
        Param.Slider("length", "Length", 5, 1000, 300, " %", "Of the canvas size"),
        Param.Heading("turnHeading", "Direction"),
        Param.Slider("tilt", "Tilt (X)", -18000, 18000, 1500, "°", "Turns the array around the horizontal axis: positive brings the beams towards you", decimals = 2),
        Param.Slider("turn", "Turn (Y)", -18000, 18000, 0, "°", "Turns the array around the vertical axis", decimals = 2),
        Param.Slider("roll", "Roll (Z)", -18000, 18000, 0, "°", "Turns the array in the picture plane: 0° points down, 180° up", decimals = 2),
        Param.Heading("animHeading", "Animation"),
        Param.Slider("sweep", "Sweep", 0, 9000, 0, "°", "Swings the array back and forth around its vertical axis over time", decimals = 2),
        Param.Slider("sweepTilt", "Sweep tilt", 0, 9000, 0, "°", "Swings it towards you and away over time", decimals = 2),
        Param.Slider("sweepSpeed", "Sweep speed", 1, 500, 50, " %", "Swings per second, in hundredths"),
        Param.Slider("flicker", "Flicker", 0, 100, 0, " %", "Beams switching on and off"),
        Param.Slider("flickerRate", "Flicker rate", 1, 60, 10, " /s"),
        Param.Heading("hazeHeading", "Haze"),
        Param.Slider("haze", "Haze", 0, 100, 45, " %", "The beams shown up unevenly by drifting haze"),
        Param.Slider("hazeSize", "Haze size", 5, 400, 100, " %"),
        Param.Slider("fade", "Fade", 0, 100, 55, " %", "How much the beams fade along their length"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("colorMode", "Colors", colorModes, 0),
        Param.Color("color", "Color", 0x5FB4FF),
        Param.Color("color2", "Second color", 0xFF4FD8).shownWhen("colorMode", 1, 2),
        Param.Color("coreColor", "Core", 0xF4FAFF),
        Param.Slider("width", "Width", 2, 400, 25, " px", "Of a beam at the source; nearer the camera it gets wider", decimals = 1),
        Param.Slider("brightness", "Brightness", 0, 300, 100, " %"),
        Param.Slider("glow", "Glow", 0, 300, 70, " %"),
        Param.Slider("glowSize", "Glow size", 10, 30000, 1400, " px", decimals = 2),
        Param.Slider("flare", "Source flare", 0, 300, 80, " %", "The bright spot where the beams come out"),
        Param.Toggle("transparent", "Transparent background", false, "Only the light, to lay over a picture"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /** A beam on screen: from (ax, ay) to (bx, by), with the inverse depths there, in its color. */
    private class Beam(
        val ax: Double, val ay: Double, val iza: Double, val bx: Double, val by: Double, val izb: Double,
        val r: Double, val g: Double, val b: Double, val strength: Double,
    ) {
        val dx = bx - ax
        val dy = by - ay
        val len2 = max(1e-9, dx * dx + dy * dy)
    }

    private fun hsv(h: Double): DoubleArray {
        val f = { n: Double -> val k = (n + h * 6) % 6; 1 - max(0.0, min(min(k, 4 - k), 1.0)) }
        return doubleArrayOf(f(5.0), f(3.0), f(1.0))
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val t = v["time"] / 1000.0
        val random = Random(seed)
        // the camera: looking along +z, the source at the focal distance times "distance"
        val focal = max(w, h) * 0.9
        val sx = w / 2.0 + v["centerX"] / 10.0
        val sy = h / 2.0 + v["centerY"] / 10.0
        val oz = focal * v["distance"] / 100.0
        val ox = (sx - w / 2.0) * oz / focal
        val oy = (sy - h / 2.0) * oz / focal
        val length = max(w, h) * v["length"] / 100.0

        val phase = 2 * PI * t * v["sweepSpeed"] / 100.0
        val tilt = Math.toRadians(v["tilt"] / 100.0 + v["sweepTilt"] / 100.0 * cos(phase))
        val turn = Math.toRadians(v["turn"] / 100.0 + v["sweep"] / 100.0 * sin(phase))
        val roll = Math.toRadians(v["roll"] / 100.0)
        val spread = Math.toRadians(v["spread"] / 100.0)
        val spread2 = Math.toRadians(v["spread2"] / 100.0)
        val irregular = v["irregular"] / 100.0
        val count = v["count"]
        val layout = v["layout"]

        // the beams' directions before turning: around straight down (0, 1, 0)
        val dirs = ArrayList<DoubleArray>()
        fun jitter() = (random.nextDouble() - 0.5) * irregular
        when (layout) {
            FAN -> for (i in 0 until count) {
                val a = (if (count == 1) 0.0 else i / (count - 1.0) - 0.5 + jitter() / max(1, count - 1)) * spread
                dirs += doubleArrayOf(sin(a), cos(a), 0.0)
            }
            CONE -> for (i in 0 until count) {
                val p = 2 * PI * (i + jitter()) / count
                val o = spread / 2 * (1 + jitter() * 0.5)
                dirs += doubleArrayOf(sin(o) * cos(p), cos(o), sin(o) * sin(p))
            }
            GRID -> {
                val rows = v["rows"].coerceAtMost(count)
                val cols = (count + rows - 1) / rows
                for (r in 0 until rows) for (c in 0 until cols) {
                    val a = (if (cols == 1) 0.0 else c / (cols - 1.0) - 0.5 + jitter() / cols) * spread
                    val b = (if (rows == 1) 0.0 else r / (rows - 1.0) - 0.5 + jitter() / rows) * spread2
                    dirs += doubleArrayOf(tan(a.coerceIn(-1.5, 1.5)), 1.0, tan(b.coerceIn(-1.5, 1.5)))
                }
            }
            else -> repeat(count) {
                val a = (random.nextDouble() - 0.5) * spread
                val b = (random.nextDouble() - 0.5) * spread2
                dirs += doubleArrayOf(tan(a.coerceIn(-1.5, 1.5)), 1.0, tan(b.coerceIn(-1.5, 1.5)))
            }
        }

        // turned: tilt around x (positive towards the camera), turn around y, roll in the picture plane
        val ct = cos(tilt); val st = sin(tilt)
        val cu = cos(turn); val su = sin(turn)
        val cr = cos(roll); val sr = sin(roll)
        fun turned(d: DoubleArray): DoubleArray {
            val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            var x = d[0] / l; var y = d[1] / l; var z = d[2] / l
            val y1 = y * ct - z * st; val z1 = y * st + z * ct; y = y1; z = -z1
            val x2 = x * cu + z * su; val z2 = -x * su + z * cu; x = x2; z = z2
            val x3 = x * cr - y * sr; val y3 = x * sr + y * cr
            return doubleArrayOf(x3, y3, z)
        }

        val colorMode = v["colorMode"]
        val c1 = v["color"]
        val c2 = v["color2"]
        val flicker = v["flicker"] / 100.0
        val flickerStep = floor(t * v["flickerRate"]).toInt()
        val near = focal * 0.04
        val beams = ArrayList<Beam>()
        for ((k, d0) in dirs.withIndex()) {
            val d = turned(d0)
            // clipped at the camera's near plane
            var len = length
            if (oz + d[2] * len < near) len = (near - oz) / d[2]
            if (len <= 0) continue
            val bz = oz + d[2] * len
            val col = when (colorMode) {
                1 -> if (k % 2 == 0) c1 else c2
                2 -> {
                    val f = if (dirs.size == 1) 0.0 else k / (dirs.size - 1.0)
                    argb(255, (red(c1) * (1 - f) + red(c2) * f).roundToInt(), (green(c1) * (1 - f) + green(c2) * f).roundToInt(), (blue(c1) * (1 - f) + blue(c2) * f).roundToInt())
                }
                3 -> hsv(k / dirs.size.toDouble()).let { argb(255, (it[0] * 255).roundToInt(), (it[1] * 255).roundToInt(), (it[2] * 255).roundToInt()) }
                else -> c1
            }
            // flicker: now and then a beam goes dark for a step
            val on = if (flicker > 0 && Random(seed * 31 + k * 7919L + flickerStep * 104729L).nextDouble() < flicker * 0.6) 0.0 else 1.0
            if (on == 0.0) continue
            beams += Beam(
                sx, sy, 1 / oz,
                w / 2.0 + (ox + d[0] * len) * focal / bz, h / 2.0 + (oy + d[1] * len) * focal / bz, 1 / bz,
                red(col) / 255.0, green(col) / 255.0, blue(col) / 255.0, 1 - irregular * 0.7 * random.nextDouble(),
            )
        }

        val halfWidth = v["width"] / 20.0
        val brightness = v["brightness"] / 100.0
        val glow = v["glow"] / 100.0
        val glowSize = v["glowSize"] / 100.0
        val fade = v["fade"] / 100.0
        val haze = v["haze"] / 100.0
        val hazeScale = max(w, h) * 0.18 * v["hazeSize"] / 100.0
        val flare = v["flare"] / 100.0
        val core = v["coreColor"]
        val kr = red(core) / 255.0
        val kg = green(core) / 255.0
        val kb = blue(core) / 255.0
        val noise = Noise(seed + 5)
        val transparent = v.bool("transparent")
        val firstColor = if (beams.isEmpty()) doubleArrayOf(red(c1) / 255.0, green(c1) / 255.0, blue(c1) / 255.0)
        else doubleArrayOf(beams[0].r, beams[0].g, beams[0].b)
        val flareR = max(w, h) * 0.035 * (halfWidth / 1.25).coerceIn(0.5, 4.0)

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val py = y + 0.5
            for (x in 0 until w) {
                val px = x + 0.5
                // the haze: drifting, uneven
                val mist = if (haze <= 0) 1.0 else {
                    val n = noise.fbm(px / hazeScale + t * 0.05, py / hazeScale - t * 0.03, 4) * 0.5 + 0.5
                    1 - haze + haze * (n * 1.6).coerceIn(0.0, 1.4)
                }
                var lr = 0.0
                var lg = 0.0
                var lb = 0.0
                for (b in beams) {
                    var s = ((px - b.ax) * b.dx + (py - b.ay) * b.dy) / b.len2
                    if (s < 0) s = 0.0 else if (s > 1) s = 1.0
                    val qx = b.ax + b.dx * s - px
                    val qy = b.ay + b.dy * s - py
                    val dist = sqrt(qx * qx + qy * qy)
                    // the depth there (1/z interpolates on screen) – nearer beams are wider
                    val iz = b.iza + (b.izb - b.iza) * s
                    val widen = iz / b.iza
                    val hw = halfWidth * widen
                    val reach = glowSize * sqrt(widen) + hw * 3
                    if (dist > reach * 3.2 + 2) continue
                    // how far along the beam in space: 0 at the source, 1 at its end
                    val along = s * b.izb / iz
                    val fading = (1 - fade * along).coerceAtLeast(0.0) * (1 - fade * 0.5 * along)
                    // a thin core keeps its light: dimmer instead of narrower than a pixel
                    val hwc = max(hw, 0.6)
                    val c = ((hwc + 0.6 - dist) / 1.2).coerceIn(0.0, 1.0) * min(1.0, hw / 0.6 + 0.2)
                    val gl = exp(-(dist / reach) * (dist / reach)) * 0.35 + exp(-dist / (reach * 0.15)) * 0.35
                    val k = fading * brightness * b.strength
                    val gk = gl * glow * k * mist
                    val ck = c * k * (0.5 + 0.5 * mist)
                    lr += b.r * gk + kr * ck
                    lg += b.g * gk + kg * ck
                    lb += b.b * gk + kb * ck
                }
                if (flare > 0) {
                    val fd = ((px - sx) * (px - sx) + (py - sy) * (py - sy)) / (flareR * flareR)
                    val hot = exp(-fd * 6) * flare
                    val wide = exp(-fd * 0.6) * flare * 0.45
                    lr += kr * hot + firstColor[0] * wide
                    lg += kg * hot + firstColor[1] * wide
                    lb += kb * hot + firstColor[2] * wide
                }
                // soft roll-off, never above white
                val r = 1 - exp(-lr)
                val g = 1 - exp(-lg)
                val bl = 1 - exp(-lb)
                val i = y * w + x
                if (transparent) {
                    val a = max(r, max(g, bl))
                    if (a <= 0.001) { out.data[i] = 0; continue }
                    out.data[i] = argb((a * 255).roundToInt(), (r / a * 255).roundToInt(), (g / a * 255).roundToInt(), (bl / a * 255).roundToInt())
                } else {
                    val c = src.data[i]
                    fun screen(base: Int, l: Double) = (255 - (255 - base) * (1 - l)).roundToInt().coerceIn(0, 255)
                    out.data[i] = argb(alpha(c), screen(red(c), r), screen(green(c), g), screen(blue(c), bl))
                }
            }
        }
        return out
    }
}
