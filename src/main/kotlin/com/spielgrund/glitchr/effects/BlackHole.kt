package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A black hole: the picture behind it is bent around the shadow by gravitational lensing
 * (a point lens: what lies right behind the hole appears as a ring around it, and a second,
 * flipped image inside), and a glowing accretion disk can circle it – seen from the side as
 * in “Interstellar”, where the lens lifts the disk's far side into an arc over and under the
 * shadow; as dots in one color like Jean-Pierre Luminet's drawing of 1978; or as the blurred
 * orange ring of the Event Horizon Telescope's pictures of 2019 and 2024, the latter with
 * swirling magnetic field lines. As a generator it is drawn on black with stars.
 */
object BlackHole : Effect("blackhole", "Black hole", "A black hole that bends the picture around it, with a glowing disk – Interstellar, Luminet 1978, EHT 2019 and 2024") {
    private val styles = listOf("Gargantua (Interstellar)", "Luminet 1978", "Event Horizon (M87, 2019)", "Magnetic swirl (Sgr A*, 2024)", "Lensing only")
    private const val GARGANTUA = 0
    private const val LUMINET = 1
    private const val EHT = 2
    private const val SWIRL = 3

    override val params = listOf(
        Param.Choice("style", "Style", styles, 0),
        Param.Slider("size", "Size", 3, 2000, 90, " px", "Radius of the black shadow", canvasMax = true),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas", decimals = 1),
        Param.Slider("lensing", "Lensing", 0, 300, 100, " %", "How strongly the picture behind is bent around the hole"),
        Param.Choice("background", "Behind", listOf("The picture, bent", "Black"), 0),
        Param.Slider("stars", "Stars", 0, 100, 0, " %", "Adds stars behind the hole (they are bent too)"),

        Param.Heading("diskHeading", "Disk"),
        Param.Slider("tilt", "View angle", 0, 9000, 8200, "°", "0° = looking straight down on the disk, 90° = exactly from the side (Gargantua and Luminet)", decimals = 2),
        Param.Slider("rotation", "Rotation", -18000, 18000, 0, "°", "Turns the disk; for the EHT styles: where the bright side is", decimals = 2),
        Param.Slider("diskSize", "Disk size", 120, 1200, 450, " %", "Outer edge of the disk against the shadow"),
        Param.Slider("brightness", "Brightness", 0, 300, 100, " %"),
        Param.Slider("doppler", "Doppler", 0, 100, 45, " %", "The side turning towards us is brighter"),
        Param.Toggle("reverse", "Turning the other way", false, "Swaps the bright side"),
        Param.Slider("turbulence", "Turbulence", 0, 100, 50, " %", "Streaks and clumps in the glowing gas"),
        Param.Slider("orbit", "Orbit", -360000, 360000, 0, "°", "Turns the gas around the hole – inner parts faster; animate it", decimals = 2),
        Param.Slider("glow", "Glow", 0, 200, 60, " %"),
        Param.Slider("softness", "Softness", 0, 100, 0, " %", "Blurs the light, like a telescope picture"),
        Param.Choice("colors", "Colors", listOf("By style", "Gradient"), 0),
        Param.Ramp("ramp", "Gradient", "0.0000:#000000 0.3500:#8c1a00 0.6500:#ff8a1f 0.8500:#ffd27a 1.0000:#ffffff", "Colors from faint (left) to bright (right)"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val style = v["style"]
        val shadow = v["size"].toDouble()
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        // the Einstein radius: a little beyond the shadow, as the photon ring is
        val einstein = shadow * 1.3 * v["lensing"] / 100.0
        val tilt = Math.toRadians(v["tilt"] / 100.0)
        val cosTilt = max(cos(tilt), 0.015)
        val rot = Math.toRadians(v["rotation"] / 100.0)
        val cosR = cos(rot)
        val sinR = sin(rot)
        val inner = shadow * 1.18
        val outer = shadow * v["diskSize"] / 100.0
        val bright = v["brightness"] / 100.0
        val doppler = v["doppler"] / 100.0
        val spin = if (v.bool("reverse")) -1.0 else 1.0
        val turbulence = v["turbulence"] / 100.0
        val orbit = Math.toRadians(v["orbit"] / 100.0)
        val noise = Noise(seed)
        val ramp = if (v["colors"] == 1) ColorRamp.parse(v.text("ramp")) else null

        // the picture behind, with stars if wanted
        val behind = if (v["stars"] > 0) withStars(src, v["stars"] / 100.0, seed) else src

        // light of the disk or ring per pixel, as brightness and a 0..1 "heat" for the color
        val light = FloatArray(n)
        val heat = FloatArray(n)

        /** The disk at a point (x, y) relative to the center, rotated into the disk's frame; [farOnly]: only its far half. */
        fun disk(px: Double, py: Double, farOnly: Boolean, out: DoubleArray) {
            out[0] = 0.0
            val x = px * cosR + py * sinR
            val yScreen = -px * sinR + py * cosR
            // the far half lies above the middle on screen (the disk is tipped away from us)
            if (farOnly && yScreen > 0) return
            val y = yScreen / cosTilt
            val r = hypot(x, y)
            if (r < inner * 0.9 || r > outer * 1.15) return
            val edgeIn = ((r - inner * 0.9) / (inner * 0.25)).coerceIn(0.0, 1.0)
            val edgeOut = ((outer * 1.15 - r) / (outer * 0.35)).coerceIn(0.0, 1.0)
            var e = edgeIn * edgeIn * (3 - 2 * edgeIn) * edgeOut * edgeOut * (3 - 2 * edgeOut) * (inner / r).pow(1.4)
            // the side coming towards us is brighter (beaming), the other one dimmer
            val towards = -x / r * spin
            e *= (1 + doppler * 0.85 * towards).coerceAtLeast(0.03).pow(3)
            if (turbulence > 0) {
                // gas in orbit: streaks along the circles, the inner ones turning faster
                val turn = orbit * (inner / r).pow(1.5)
                val a = atan2(y, x) + turn
                val m = noise.fbm(ln(r / inner) * 6.0 + 3.1, cos(a) * 2.2 + sin(a) * 0.3, 3) +
                    0.5 * noise.fbm(ln(r / inner) * 18.0, a * 4.0, 2)
                e *= (1 - turbulence * 0.55 + turbulence * 0.9 * (m * 0.5 + 0.5)).coerceAtLeast(0.0)
            }
            out[0] = e
            out[1] = (inner / r).coerceIn(0.0, 1.0)
        }

        parallelRows(h) { yy ->
            val d = DoubleArray(2)
            for (xx in 0 until w) {
                val i = yy * w + xx
                val px = xx + 0.5 - cx
                val py = yy + 0.5 - cy
                val rho = hypot(px, py)
                when (style) {
                    GARGANTUA, LUMINET -> {
                        var e = 0.0
                        var t = 0.0
                        // the disk as it is: its near half in front of the shadow, the far half only beside it
                        val x = px * cosR + py * sinR
                        val near = -px * sinR + py * cosR > 0
                        if (near || rho > shadow) {
                            disk(px, py, false, d)
                            if (d[0] > e) { e = d[0]; t = d[1] }
                        }
                        // the far half once more, lifted by the lens into an arc around the shadow (and a thin one inside it)
                        if (rho > 1e-6 && einstein > 0) {
                            val k = 1 - einstein * einstein / (rho * rho)
                            disk(px * k, py * k, true, d)
                            if (d[0] > 0 && rho > shadow * 0.98) { e += d[0] * 0.9; t = max(t, d[1]) }
                        }
                        // the photon ring hugging the shadow
                        val ring = exp(-((rho - shadow * 1.03) / (shadow * 0.025)).pow(2)) * 0.9
                        val ringBeam = (1 + doppler * 0.6 * (-x / max(rho, 1e-6)) * spin).coerceAtLeast(0.1)
                        e += ring * ringBeam
                        light[i] = (e * bright).toFloat()
                        heat[i] = max(t, ring).toFloat()
                    }
                    EHT, SWIRL -> {
                        // a thick, glowing ring, brighter on one side
                        val ringRadius = shadow * 1.3
                        var e = exp(-((rho - ringRadius) / (shadow * 0.32)).pow(2))
                        val a = atan2(py, px) - rot - PI / 2
                        e *= 0.45 + 0.55 * (1 + doppler * cos(a) * spin).coerceAtLeast(0.0) / (1 + doppler)
                        if (turbulence > 0) e *= 1 - turbulence * 0.35 + turbulence * 0.35 * (noise.fbm(px / shadow * 2.5, py / shadow * 2.5, 3) + 0.5)
                        light[i] = (e * bright).toFloat()
                        heat[i] = 1f
                    }
                    else -> {}
                }
            }
        }

        // telescope softness: the EHT pictures are blurred by nature
        val baseSoft = if (style == EHT || style == SWIRL) shadow * 0.2 else 0.0
        val soft = baseSoft + v["softness"] / 100.0 * shadow * 0.4
        var glowing = if (soft > 0.5) Blur.gauss(light, w, h, soft) else light
        if (style == SWIRL) {
            // magnetic field lines winding round the ring, crisp on top of the soft glow
            glowing = FloatArray(n) { i ->
                val px = i % w + 0.5 - cx
                val py = i / w + 0.5 - cy
                val swirl = sin(atan2(py, px) * 9 - ln(max(hypot(px, py), 1.0) / shadow) * 14 + orbit)
                glowing[i] * (0.45 + 0.85 * (0.5 + 0.5 * swirl).pow(2.5)).toFloat()
            }
        }
        if (style == LUMINET) {
            // drawn in dots: the brighter, the denser
            val s = seed.toInt()
            glowing = FloatArray(n) { i ->
                val hsh = (i * 374761393 + s * 668265263).let { (it xor (it ushr 13)) * 1274126177 }.let { it xor (it ushr 16) }
                if ((hsh and 0xFFFF) / 65535f < glowing[i] * 0.75f) min(1.2f, 0.55f + glowing[i] * 0.5f) else 0f
            }
        }
        val glow = v["glow"] / 100.0
        val halo = if (glow > 0) Blur.gauss(glowing, w, h, max(1.0, shadow * 0.35 * glow)) else null
        val blackBehind = v["background"] == 1

        val out = Pixels(w, h)
        parallelRows(h) { yy ->
            for (xx in 0 until w) {
                val i = yy * w + xx
                val px = xx + 0.5 - cx
                val py = yy + 0.5 - cy
                val rho = hypot(px, py)
                // behind: the picture bent around the hole, black inside the shadow
                var r = 0.0; var g = 0.0; var b = 0.0; var a = 255
                if (!blackBehind && rho > shadow) {
                    val k = if (einstein > 0) 1 - einstein * einstein / (rho * rho) else 1.0
                    val c = sampleBilinear(behind, cx + px * k, cy + py * k, Edge.MIRROR)
                    // light grazing the shadow is dimmed a little
                    val dim = ((rho - shadow) / (shadow * 0.15)).coerceIn(0.0, 1.0)
                    r = (c shr 16 and 0xFF) * dim; g = (c shr 8 and 0xFF) * dim; b = (c and 0xFF) * dim
                    a = c ushr 24
                }
                if (blackBehind || rho <= shadow) a = 255
                // the light, colored by heat (disk) or by its own brightness (EHT)
                val e = glowing[i] + (halo?.get(i) ?: 0f) * glow.toFloat() * 0.8f
                if (e > 0.002f) {
                    val col = colorOf(style, ramp, e.toDouble(), heat[i].toDouble())
                    r += col[0]; g += col[1]; b += col[2]
                }
                out.data[i] = argb(a, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
            }
        }
        return out
    }

    /** Color (0..255 per channel, may exceed and is clipped) of light [e] with [heat] 0..1 in a [style]. */
    private fun colorOf(style: Int, ramp: ColorRamp?, e: Double, heat: Double): DoubleArray {
        if (ramp != null) {
            val c = ramp.colorAt(min(1.0, e))
            val boost = max(1.0, e)
            return doubleArrayOf((c shr 16 and 0xFF) * boost, (c shr 8 and 0xFF) * boost, (c and 0xFF) * boost)
        }
        return when (style) {
            LUMINET -> doubleArrayOf(e * 245, e * 245, e * 250)
            EHT, SWIRL -> {
                // afmhot, like the telescope pictures: black, red, orange, yellow, white
                val t = min(1.2, e)
                doubleArrayOf(min(1.0, t * 2.0) * 255, (t * 2 - 0.55).coerceIn(0.0, 1.0) * 235, (t * 2 - 1.3).coerceIn(0.0, 1.0) * 200)
            }
            else -> {
                // hot inside (white-yellow), cooler outside (orange)
                val warm = 1 - heat
                doubleArrayOf(e * 255, e * (235 - 90 * warm), e * (200 - 170 * warm))
            }
        }
    }

    /** [src] with white and slightly colored stars of different brightness on top. */
    private fun withStars(src: Pixels, amount: Double, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val out = src.copy()
        val random = kotlin.random.Random(seed + 4242)
        val count = (w.toLong() * h * 0.0012 * amount).toInt()
        repeat(count) {
            val x = random.nextInt(w)
            val y = random.nextInt(h)
            val l = random.nextDouble().pow(3)
            val tint = random.nextInt(3)
            val base = out.data[y * w + x]
            fun add(c: Int, extra: Double) = min(255, c + (255 * l * extra).toInt())
            out.data[y * w + x] = argb(
                255, add(base shr 16 and 0xFF, if (tint == 1) 0.8 else 1.0),
                add(base shr 8 and 0xFF, 0.95), add(base and 0xFF, if (tint == 2) 0.8 else 1.0),
            )
            // a few bright ones with a little glow
            if (l > 0.6) for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    val c = out.data[yy * w + xx]
                    out.data[yy * w + xx] = argb(255, min(255, (c shr 16 and 0xFF) + (90 * l).toInt()), min(255, (c shr 8 and 0xFF) + (90 * l).toInt()), min(255, (c and 0xFF) + (100 * l).toInt()))
                }
            }
        }
        return out
    }
}
