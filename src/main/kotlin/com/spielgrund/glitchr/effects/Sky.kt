package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
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
import kotlin.math.tan

/**
 * A sky seen through a virtual camera – turned (heading, tilt, roll) with a field of view, or as
 * a 360° panorama (equirectangular, for an HDRI-like backdrop) or a fisheye looking straight up.
 * The sky as the 3D programs make it:
 * - gradient: zenith, horizon and ground colors with a glow around the sun;
 * - Preetham: the classic analytic daylight model, its haze set by the turbidity;
 * - physical (Nishita): light scattered in the atmosphere, worked out along every ray – air
 *   (Rayleigh: blue skies, red sunsets), dust (Mie: the white haze and glare around the sun),
 *   ozone (the blue of twilight) and the camera's altitude.
 * Over it the sun's disk (in the color the atmosphere leaves it), a layer of procedural clouds
 * lit from the sun with a silver lining, drifting with the time, and stars as the sun sets. Below
 * the horizon: ground fading into the haze, rippled water mirroring the sky, or more sky. The
 * light is worked out in high range, then exposed and tone mapped (filmic or linear).
 */
object Sky : Effect("sky", "Sky", "A sky through a virtual camera – gradient, Preetham or physical atmosphere, sun, clouds, stars, ground or water") {
    private val projections = listOf("Camera", "Panorama (360°)", "Fisheye (looking up)")
    private val models = listOf("Gradient", "Preetham", "Physical (Nishita)")
    private val belows = listOf("Ground", "Water (mirror)", "Sky")

    override val params = listOf(
        Param.Heading("cameraHeading", "Camera"),
        Param.Choice(
            "projection", "Projection", projections, 0,
            "Camera: a view with the field of view below · Panorama: the whole sky around, left to right 360°, top to bottom zenith to nadir – an HDRI-like backdrop · Fisheye: straight up, the horizon around the edge",
        ),
        Param.Slider("heading", "Heading", -18000, 18000, 0, "°", "Where the camera looks around the horizon: 0° = north, 90° = east", decimals = 2),
        Param.Slider("tilt", "Tilt", -9000, 9000, 1200, "°", "Up (positive) or down from the horizon", decimals = 2).shownWhen("projection", 0),
        Param.Slider("roll", "Roll", -18000, 18000, 0, "°", "Turns the view around its axis", decimals = 2).shownWhen("projection", 0, 2),
        Param.Slider("fov", "Field of view", 500, 17000, 7500, "°", "Across the picture: small = telephoto, large = wide angle", decimals = 2).shownWhen("projection", 0),
        Param.Heading("skyHeading", "Sky"),
        Param.Choice(
            "model", "Model", models, 2,
            "Gradient: three colors and a glow · Preetham: the classic analytic daylight sky · Physical: light scattered in the atmosphere – sunsets, twilight, haze",
        ),
        Param.Color("zenith", "Zenith", 0x2A5EB8).shownWhen("model", 0),
        Param.Color("horizon", "Horizon", 0xB8D4EC).shownWhen("model", 0),
        Param.Slider("gradientCurve", "Gradient curve", 10, 400, 100, " %", "How quickly the horizon color gives way to the zenith's").shownWhen("model", 0),
        Param.Slider("turbidity", "Turbidity", 170, 1000, 250, "", "Haze: 2 = clear mountain air, 10 = thick haze", decimals = 2).shownWhen("model", 1),
        Param.Slider("air", "Air", 0, 1000, 100, " %", "Rayleigh scattering: the blue of the sky, the red of sunsets").shownWhen("model", 2),
        Param.Slider("dust", "Dust", 0, 1000, 100, " %", "Mie scattering: white haze, glare around the sun").shownWhen("model", 2),
        Param.Slider("ozone", "Ozone", 0, 1000, 100, " %", "The deep blue of the sky at twilight").shownWhen("model", 2),
        Param.Slider("altitude", "Altitude", 0, 60000, 0, " m", "Of the camera: high up the sky darkens and the haze sinks below").shownWhen("model", 2),
        Param.Heading("sunHeading", "Sun"),
        Param.Slider("sunElevation", "Elevation", -2000, 9000, 1200, "°", "Above the horizon – below it: dusk and night", decimals = 2),
        Param.Slider("sunAzimuth", "Direction", -18000, 18000, 2000, "°", "Around the horizon, as the heading", decimals = 2),
        Param.Slider("sunSize", "Disk size", 0, 2000, 53, "°", "Its apparent diameter: the real sun is 0.53°", decimals = 2),
        Param.Slider("sunStrength", "Strength", 0, 500, 100, " %", "Of the sunlight – the disk, the sky it lights, the clouds"),
        Param.Heading("cloudHeading", "Clouds"),
        Param.Slider("clouds", "Coverage", 0, 100, 35, " %"),
        Param.Slider("cloudDensity", "Density", 0, 100, 65, " %", "Thin veils to thick, dark-bottomed clouds"),
        Param.Slider("cloudSize", "Size", 10, 1000, 100, " %"),
        Param.Slider("cloudHeight", "Height", 30, 1200, 200, " km", "Of the cloud layer's base: low clouds rush past, high ones stay put", decimals = 2),
        Param.Slider("cloudThickness", "Thickness", 0, 1200, 150, " km", "How tall the clouds tower: 0 = a flat layer · thick: billowing towers, narrowing upwards, bright tops", decimals = 2),
        Param.Slider("darkBases", "Dark bases", 0, 100, 35, " %", "How dark the clouds' undersides are – high for a storm"),
        Param.Heading("lightningHeading", "Lightning"),
        Param.Slider("lightning", "Lightning", 0, 100, 0, " %", "Bolts from the clouds' base to the ground, lighting up the clouds"),
        Param.Slider("bolts", "Bolts", 1, 40, 3),
        Param.Slider("boltNear", "Distance from", 50, 5000, 250, " km", "The nearest the bolts strike: near ones reach high up in the view and are thicker", decimals = 2),
        Param.Slider("boltFar", "Distance to", 50, 5000, 1500, " km", "The farthest: far ones are small, thin and low over the horizon", decimals = 2),
        Param.Slider("branching", "Branching", 0, 100, 50, " %"),
        Param.Color("boltColor", "Color", 0xD8E0FF),
        Param.Toggle("flashes", "Flashing", false, "The bolts strike now and then as the time goes on, flickering – animate the time; off: all of them at once"),
        Param.Slider("wind", "Wind direction", -18000, 18000, 0, "°", decimals = 2),
        Param.Slider("time", "Time", 0, 10000, 0, "", "Drifts the clouds with the wind – animate it", decimals = 2),
        Param.Heading("groundHeading", "Ground and look"),
        Param.Choice("below", "Below the horizon", belows, 0, "Ground fading into the haze · Water mirroring the sky, with ripples · More sky"),
        Param.Color("groundColor", "Ground", 0x2E2A26),
        Param.Slider("ripples", "Ripples", 0, 100, 40, " %", "On the water").shownWhen("below", 1),
        Param.Slider("stars", "Stars", 0, 100, 60, " %", "Coming out as the sun goes down"),
        Param.Slider("exposure", "Exposure", -600, 600, 0, " EV", "Brighter or darker, in stops", decimals = 2),
        Param.Choice("tonemap", "Tone mapping", listOf("Filmic", "Linear"), 0, "Filmic: bright light rolls off softly, like film · Linear: clipped"),
    )

    override val random = true

    /** Cells of the star grid per unit of direction: about a third of a degree each. */
    private const val STAR_GRID = 160.0

    /** A random number 0..1 for a grid cell and a [salt], well mixed (no rows or patterns). */
    private fun hash3(x: Long, y: Long, z: Long, salt: Long): Double {
        var h = x * -0x61c8864680b583ebL + y * -0x3d4d51c2d82b14b1L + z * 0x165667B19E3779F9L + salt * 0x27D4EB2F165667C5L
        h = (h xor (h ushr 31)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 29)
        return (h ushr 11).toDouble() / (1L shl 53)
    }

    /**
     * Where the direction [d] lands on the picture for the view [ctx] (projection, heading, roll, field of view, size,
     * and the camera's frame), or null when it is out of view.
     */
    private fun project3(d: V3, ctx: View): DoubleArray? {
        val w = ctx.w
        val h = ctx.h
        return when (ctx.projection) {
            1 -> {
                var lon = atan2(d.x, d.z) - ctx.heading
                while (lon > PI) lon -= 2 * PI
                while (lon < -PI) lon += 2 * PI
                val lat = kotlin.math.asin(d.y.coerceIn(-1.0, 1.0))
                doubleArrayOf((lon / PI + 1) / 2 * w, (0.5 - lat / PI) * h)
            }
            2 -> {
                val s = min(w, h) / 2.0
                val zen = acos(d.y.coerceIn(-1.0, 1.0))
                val az = atan2(d.x, d.z) - ctx.heading - ctx.roll
                val rr = zen / (PI / 2)
                doubleArrayOf(w / 2.0 + sin(az) * rr * s, h / 2.0 - cos(az) * rr * s)
            }
            else -> {
                val dz = d.dot(ctx.fwd)
                if (dz <= 1e-4) return null
                val sx = d.dot(ctx.right) / dz
                val sy = d.dot(ctx.up) / dz
                doubleArrayOf((sx / ctx.half + 1) / 2 * w, (1 - sy / (ctx.half * h / w)) / 2 * h)
            }
        }
    }

    /** What [project3] needs to know about the view. */
    private class View(val w: Int, val h: Int, val projection: Int, val heading: Double, val roll: Double, val half: Double, val fwd: V3, val right: V3, val up: V3)

    /**
     * Lightning bolts, drawn as a brightness per pixel (0..1): each from a point of the clouds' base [base] meters above the
     * camera, between [near] and [far] away in a direction in view – only where there is a cloud ([cloudAt] at that place
     * of the layer; a few tries, else none) – zigzagging down to the ground [ground] meters below it, forking into
     * thinner branches. Further bolts are thinner. Flashing, each strikes now and then with the [time], flickering;
     * otherwise all show.
     */
    private fun lightning(
        seed: Long, time: Double, count: Int, near: Double, far: Double, branching: Double, flashing: Boolean,
        base: Double, ground: Double, view: View, cloudAt: ((Double, Double) -> Double)?,
    ): FloatArray? {
        val w = view.w
        val h = view.h
        val random = kotlin.random.Random(seed + 991)
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE)
        var drawn = false
        // the camera's horizontal field: the bolts strike where it looks (all around for panorama and fisheye)
        val spread = if (view.projection == 0) kotlin.math.atan(view.half) * 0.85 else PI
        for (b in 0 until count) {
            // the same random draws whatever the time, so every bolt keeps its place
            val rAz = random.nextDouble(); val rDist = random.nextDouble(); val rLeanX = random.nextDouble(); val rLeanZ = random.nextDouble()
            val rPhase = random.nextDouble(); val rSeed = random.nextLong()
            var strength = 1.0
            if (flashing) {
                // a strike every few time units, at its own moment: a quick flicker, then darkness
                val f = (time / 2.5 + rPhase) % 1.0
                strength = if (f < 0.07) 0.55 + 0.45 * sin(f * 900 + rPhase * 40) else 0.0
            }
            if (strength <= 0.02) continue
            // a place under a cloud: tried at random until one is found
            val place = kotlin.random.Random(rSeed + 17)
            var az = view.heading + (rAz * 2 - 1) * spread
            var dist = near + (far - near) * rDist
            var found = cloudAt == null || cloudAt(sin(az) * dist, cos(az) * dist) > 0.35
            var tries = 0
            while (!found && tries++ < 30) {
                az = view.heading + (place.nextDouble() * 2 - 1) * spread
                dist = near + (far - near) * place.nextDouble()
                found = cloudAt!!(sin(az) * dist, cos(az) * dist) > 0.35
            }
            if (!found) continue
            val top = V3(sin(az) * dist, base, cos(az) * dist)
            val bottom = V3(top.x + (rLeanX * 2 - 1) * base * 0.45, -ground, top.z + (rLeanZ * 2 - 1) * base * 0.45)
            val rnd = kotlin.random.Random(rSeed)
            /** A zigzag from [a] to [b]: the middle pushed aside, again and again, down to short pieces. */
            fun zig(a: V3, b: V3, out: MutableList<V3>) {
                val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
                val len = sqrt(dx * dx + dy * dy + dz * dz)
                // down to short pieces, so they zigzag finely
                if (len < base * 0.005) { out += b; return }
                val off = len * 0.22
                val mid = V3((a.x + b.x) / 2 + (rnd.nextDouble() * 2 - 1) * off, (a.y + b.y) / 2 + (rnd.nextDouble() * 2 - 1) * off * 0.3, (a.z + b.z) / 2 + (rnd.nextDouble() * 2 - 1) * off)
                zig(a, mid, out)
                zig(mid, b, out)
            }
            /**
             * Draws a bolt from [a] to [b], [width] wide at its start and thinning towards its end, as the Lightning
             * generator's: it forks into thinner, fainter branches – most often near its start, where it is strong – which
             * head off aside and on down, and may fork again.
             */
            fun bolt(a: V3, b: V3, width: Double, depth: Int, alpha: Double) {
                val path = ArrayList<V3>()
                path += a
                zig(a, b, path)
                g.color = java.awt.Color(1f, 1f, 1f, alpha.toFloat().coerceIn(0f, 1f))
                var prev: DoubleArray? = null
                for ((n, q) in path.withIndex()) {
                    val t = n / max(1.0, path.size - 1.0)
                    val pt = project3(q.norm(), view)
                    if (pt != null && prev != null && abs(pt[0] - prev[0]) < w / 2.0) {
                        val wd = max(0.35, width * (1 - 0.75 * t))
                        g.stroke = java.awt.BasicStroke(wd.toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                        g.draw(java.awt.geom.Line2D.Double(prev[0], prev[1], pt[0], pt[1]))
                        drawn = true
                    }
                    prev = pt
                    if (depth >= 3 || branching <= 0 || n !in 1 until path.size - 2) continue
                    // the chance per piece, so a bolt forks about as often however finely it zigzags
                    if (rnd.nextDouble() > branching * 4.0 / path.size * (1.2 - t)) continue
                    // the way the bolt runs here, turned aside (and a little more downwards)
                    val nxt = path[n + 1]
                    var dx = nxt.x - path[n - 1].x; var dy = nxt.y - path[n - 1].y; var dz = nxt.z - path[n - 1].z
                    val dl = max(1e-9, sqrt(dx * dx + dy * dy + dz * dz))
                    dx /= dl; dy /= dl; dz /= dl
                    val side = (if (rnd.nextBoolean()) 1 else -1) * (0.4 + rnd.nextDouble() * 0.7)
                    val ang = rnd.nextDouble() * 2 * PI
                    val bx = dx + side * cos(ang); val by = dy - 0.2 * abs(side); val bz = dz + side * sin(ang)
                    val bl = max(1e-9, sqrt(bx * bx + by * by + bz * bz))
                    val remaining = (1 - t) * sqrt((b.x - a.x).pow(2) + (b.y - a.y).pow(2) + (b.z - a.z).pow(2))
                    val reach = remaining * (0.25 + 0.4 * rnd.nextDouble())
                    if (reach < base * 0.03) continue
                    val end = V3(q.x + bx / bl * reach, q.y + by / bl * reach, q.z + bz / bl * reach)
                    bolt(q, end, max(0.35, width * (1 - 0.75 * t) * 0.55), depth + 1, alpha * 0.75)
                }
            }
            // nearer bolts are thicker – but fine, as real ones
            bolt(top, bottom, max(0.5, min(w, h) * 0.0012 * 5000 / dist), 0, strength)
        }
        g.dispose()
        if (!drawn) return null
        val data = (img.raster.dataBuffer as java.awt.image.DataBufferInt).data
        return FloatArray(w * h) { (data[it] ushr 24) / 255f }
    }

    /** A small 3-vector of doubles. */
    private class V3(@JvmField var x: Double, @JvmField var y: Double, @JvmField var z: Double) {
        fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        fun norm(): V3 { val l = max(1e-12, sqrt(x * x + y * y + z * z)); return V3(x / l, y / l, z / l) }
    }

    // ---- the physical atmosphere (Nishita): earth and atmosphere radii, scattering per meter, scale heights ----
    private const val EARTH = 6360e3
    private const val ATMOSPHERE = 6420e3
    private val RAYLEIGH = doubleArrayOf(5.802e-6, 13.558e-6, 33.1e-6)
    private const val MIE = 3.996e-6
    private val OZONE = doubleArrayOf(0.650e-6, 1.881e-6, 0.085e-6)
    private const val HR = 8000.0
    private const val HM = 1200.0

    /** Where a ray from [o] (relative to the earth's center) along [d] leaves a sphere of radius [r]; −1 if it misses. */
    private fun exitSphere(ox: Double, oy: Double, oz: Double, d: V3, r: Double): Double {
        val b = ox * d.x + oy * d.y + oz * d.z
        val c = ox * ox + oy * oy + oz * oz - r * r
        val disc = b * b - c
        if (disc < 0) return -1.0
        return -b + sqrt(disc)
    }

    /** Where it first hits the ground, or −1. */
    private fun hitGround(ox: Double, oy: Double, oz: Double, d: V3): Double {
        val b = ox * d.x + oy * d.y + oz * d.z
        val c = ox * ox + oy * oy + oz * oz - EARTH * EARTH
        val disc = b * b - c
        if (disc < 0) return -1.0
        val t = -b - sqrt(disc)
        return if (t > 0) t else -1.0
    }

    private fun ozoneDensity(h: Double) = max(0.0, 1 - abs(h - 25000) / 15000)

    /** The physical atmosphere's settings. */
    private class Air(val rayleigh: DoubleArray, val mie: Double, val ozone: DoubleArray, val altitude: Double)

    /** Light scattered towards the camera along [d] (radiance per unit sun), Nishita single scattering. */
    private fun scatter(d: V3, sun: V3, air: Air, out: DoubleArray) {
        val oy = EARTH + air.altitude + 1
        var end = exitSphere(0.0, oy, 0.0, d, ATMOSPHERE)
        out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
        if (end <= 0) return
        val ground = hitGround(0.0, oy, 0.0, d)
        if (ground > 0) end = ground
        val steps = 16
        val seg = end / steps
        var optR = 0.0; var optM = 0.0; var optO = 0.0
        var sr0 = 0.0; var sr1 = 0.0; var sr2 = 0.0
        var sm0 = 0.0; var sm1 = 0.0; var sm2 = 0.0
        for (i in 0 until steps) {
            val t = seg * (i + 0.5)
            val px = d.x * t; val py = oy + d.y * t; val pz = d.z * t
            val hgt = sqrt(px * px + py * py + pz * pz) - EARTH
            val hr = exp(-hgt / HR) * seg
            val hm = exp(-hgt / HM) * seg
            optR += hr; optM += hm; optO += ozoneDensity(hgt) * seg
            // towards the sun from here: in the earth's shadow, no light
            if (hitGround(px, py, pz, sun) > 0) continue
            val lend = exitSphere(px, py, pz, sun, ATMOSPHERE)
            val lseg = lend / 8
            var lR = 0.0; var lM = 0.0; var lO = 0.0
            for (j in 0 until 8) {
                val tl = lseg * (j + 0.5)
                val qx = px + sun.x * tl; val qy = py + sun.y * tl; val qz = pz + sun.z * tl
                val lh = sqrt(qx * qx + qy * qy + qz * qz) - EARTH
                lR += exp(-lh / HR) * lseg; lM += exp(-lh / HM) * lseg; lO += ozoneDensity(lh) * lseg
            }
            fun att(k: Int) = exp(-(air.rayleigh[k] * (optR + lR) + air.mie * 1.11 * (optM + lM) + air.ozone[k] * (optO + lO)))
            val a0 = att(0); val a1 = att(1); val a2 = att(2)
            sr0 += a0 * hr; sr1 += a1 * hr; sr2 += a2 * hr
            sm0 += a0 * hm; sm1 += a1 * hm; sm2 += a2 * hm
        }
        val mu = d.dot(sun)
        val phaseR = 3 / (16 * PI) * (1 + mu * mu)
        val g = 0.76
        val phaseM = 3 / (8 * PI) * ((1 - g * g) * (1 + mu * mu)) / ((2 + g * g) * (1 + g * g - 2 * g * mu).pow(1.5))
        out[0] = sr0 * air.rayleigh[0] * phaseR + sm0 * air.mie * phaseM
        out[1] = sr1 * air.rayleigh[1] * phaseR + sm1 * air.mie * phaseM
        out[2] = sr2 * air.rayleigh[2] * phaseR + sm2 * air.mie * phaseM
    }

    /** How much of the sunlight gets through the atmosphere to the camera from direction [d]. */
    private fun transmittance(d: V3, air: Air, out: DoubleArray) {
        val oy = EARTH + air.altitude + 1
        if (hitGround(0.0, oy, 0.0, d) > 0) { out.fill(0.0); return }
        val end = exitSphere(0.0, oy, 0.0, d, ATMOSPHERE)
        val seg = end / 32
        var r = 0.0; var m = 0.0; var o = 0.0
        for (i in 0 until 32) {
            val t = seg * (i + 0.5)
            val px = d.x * t; val py = oy + d.y * t; val pz = d.z * t
            val hgt = sqrt(px * px + py * py + pz * pz) - EARTH
            r += exp(-hgt / HR) * seg; m += exp(-hgt / HM) * seg; o += ozoneDensity(hgt) * seg
        }
        for (k in 0..2) out[k] = exp(-(air.rayleigh[k] * r + air.mie * 1.11 * m + air.ozone[k] * o))
    }

    // ---- Preetham: the Perez sky distribution with turbidity-dependent coefficients ----
    private fun perez(cosTheta: Double, gamma: Double, cosGamma: Double, c: DoubleArray) =
        (1 + c[0] * exp(c[1] / max(0.01, cosTheta))) * (1 + c[2] * exp(c[3] * gamma) + c[4] * cosGamma * cosGamma)

    /** Preetham's sky color along [d] for the sun at [sun], linear sRGB, roughly in the same units as the physical one. */
    private class Preetham(sun: V3, turbidity: Double) {
        private val sunDir = sun
        private val thetaS = acos(sun.y.coerceIn(-1.0, 1.0)).coerceAtMost(PI / 2 - 0.001)
        private val t = turbidity
        private val cY = doubleArrayOf(0.1787 * t - 1.4630, -0.3554 * t + 0.4275, -0.0227 * t + 5.3251, 0.1206 * t - 2.5771, -0.0670 * t + 0.3703)
        private val cx = doubleArrayOf(-0.0193 * t - 0.2592, -0.0665 * t + 0.0008, -0.0004 * t + 0.2125, -0.0641 * t - 0.8989, -0.0033 * t + 0.0452)
        private val cy = doubleArrayOf(-0.0167 * t - 0.2608, -0.0950 * t + 0.0092, -0.0079 * t + 0.2102, -0.0441 * t - 1.6537, -0.0109 * t + 0.0529)
        private val zY: Double
        private val zx: Double
        private val zy: Double

        init {
            val chi = (4.0 / 9 - t / 120) * (PI - 2 * thetaS)
            zY = max(0.0, (4.0453 * t - 4.9710) * kotlin.math.tan(chi) - 0.2155 * t + 2.4192)
            val th = thetaS
            val t2 = t * t
            zx = (0.00166 * th.pow(3) - 0.00375 * th * th + 0.00209 * th) * t2 + (-0.02903 * th.pow(3) + 0.06377 * th * th - 0.03202 * th + 0.00394) * t +
                (0.11693 * th.pow(3) - 0.21196 * th * th + 0.06052 * th + 0.25886)
            zy = (0.00275 * th.pow(3) - 0.00610 * th * th + 0.00317 * th) * t2 + (-0.04214 * th.pow(3) + 0.08970 * th * th - 0.04153 * th + 0.00516) * t +
                (0.15346 * th.pow(3) - 0.26756 * th * th + 0.06670 * th + 0.26688)
        }

        fun color(d: V3, out: DoubleArray) {
            val cosTheta = max(0.001, d.y)
            val cosGamma = d.dot(sunDir).coerceIn(-1.0, 1.0)
            val gamma = acos(cosGamma)
            val f0Y = perez(1.0, thetaS, cos(thetaS), cY)
            val f0x = perez(1.0, thetaS, cos(thetaS), cx)
            val f0y = perez(1.0, thetaS, cos(thetaS), cy)
            val bigY = zY * perez(cosTheta, gamma, cosGamma, cY) / f0Y
            val x = zx * perez(cosTheta, gamma, cosGamma, cx) / f0x
            val y = max(1e-4, zy * perez(cosTheta, gamma, cosGamma, cy) / f0y)
            // xyY → XYZ → linear sRGB
            val bx = x * bigY / y
            val bz = (1 - x - y) * bigY / y
            out[0] = max(0.0, 3.2406 * bx - 1.5372 * bigY - 0.4986 * bz)
            out[1] = max(0.0, -0.9689 * bx + 1.8758 * bigY + 0.0415 * bz)
            out[2] = max(0.0, 0.0557 * bx - 0.2040 * bigY + 1.0570 * bz)
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val projection = v["projection"]
        val model = v["model"]
        val below = v["below"]

        // the camera's frame: forward, right, up (y is up, z north, x east)
        val heading = Math.toRadians(v["heading"] / 100.0)
        val tilt = Math.toRadians(v["tilt"] / 100.0)
        val roll = Math.toRadians(v["roll"] / 100.0)
        val fov = Math.toRadians(v["fov"] / 100.0).coerceIn(0.01, 3.0)
        val fwd = V3(sin(heading) * cos(tilt), sin(tilt), cos(heading) * cos(tilt))
        val right0 = V3(cos(heading), 0.0, -sin(heading))
        val up0 = V3(-sin(heading) * sin(tilt), cos(tilt), -cos(heading) * sin(tilt))
        val right = V3(right0.x * cos(roll) + up0.x * sin(roll), right0.y * cos(roll) + up0.y * sin(roll), right0.z * cos(roll) + up0.z * sin(roll))
        val up = V3(up0.x * cos(roll) - right0.x * sin(roll), up0.y * cos(roll) - right0.y * sin(roll), up0.z * cos(roll) - right0.z * sin(roll))
        val half = tan(fov / 2)

        /** The direction the pixel at (px, py) looks along. */
        fun ray(px: Double, py: Double): V3 = when (projection) {
            1 -> {
                val lon = (px / w * 2 - 1) * PI + heading
                val lat = (0.5 - py / h) * PI
                V3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
            }
            2 -> {
                val s = min(w, h) / 2.0
                val dx = (px - w / 2.0) / s
                val dy = (py - h / 2.0) / s
                val rr = hypot(dx, dy)
                // the zenith in the middle, the horizon on the circle, beyond it below
                val zen = rr * PI / 2
                val az = atan2(dx, -dy) + heading + roll
                V3(sin(zen) * sin(az), cos(zen), sin(zen) * cos(az))
            }
            else -> {
                val sx = (px / w * 2 - 1) * half
                val sy = (1 - py / h * 2) * half * h / w
                V3(fwd.x + right.x * sx + up.x * sy, fwd.y + right.y * sx + up.y * sy, fwd.z + right.z * sx + up.z * sy).norm()
            }
        }

        // the sun
        val el = Math.toRadians(v["sunElevation"] / 100.0)
        val azS = Math.toRadians(v["sunAzimuth"] / 100.0)
        val sun = V3(cos(el) * sin(azS), sin(el), cos(el) * cos(azS))
        val strength = v["sunStrength"] / 100.0
        val air = Air(
            DoubleArray(3) { RAYLEIGH[it] * (if (model == 2) v["air"] / 100.0 else 1.0) },
            MIE * (when (model) { 2 -> v["dust"] / 100.0; 1 -> (v["turbidity"] / 100.0 - 1) / 1.5; else -> 1.0 }),
            DoubleArray(3) { OZONE[it] * (if (model == 2) v["ozone"] / 100.0 else 1.0) },
            if (model == 2) v["altitude"].toDouble() else 0.0,
        )
        // the sunlight as it arrives: reddened when low, gone below the horizon
        val sunColor = DoubleArray(3).also { transmittance(sun, air, it) }
        for (k in 0..2) sunColor[k] *= strength
        val preetham = if (model == 1) Preetham(sun, v["turbidity"] / 100.0) else null
        fun ch(c: Int, sh: Int) = ((c shr sh and 0xFF) / 255.0).pow(2.2)
        val zenith = v["zenith"]
        val horizon = v["horizon"]
        val groundColor = v["groundColor"]
        val curve = v["gradientCurve"] / 100.0
        // daylight: how much the sun still lights the sky (for the gradient's and the ground's brightness)
        val day = smoothstep(-0.12, 0.08, sun.y)

        /** The sky alone along [d] (above the horizon), in high range. */
        fun sky(d: V3, out: DoubleArray) {
            when (model) {
                0 -> {
                    val k = max(0.0, d.y).pow(0.5 / curve)
                    val glow = exp(-acos(d.dot(sun).coerceIn(-1.0, 1.0)) / 0.35) * 0.8
                    for (c in 0..2) {
                        val sh = 16 - 8 * c
                        out[c] = ((ch(horizon, sh) + (ch(zenith, sh) - ch(horizon, sh)) * k) * (0.05 + 0.95 * day) + sunColor[c] * glow) * 0.6
                    }
                }
                1 -> {
                    preetham!!.color(V3(d.x, max(0.001, d.y), d.z), out)
                    for (c in 0..2) out[c] *= 0.025 * strength * day.coerceAtLeast(0.02)
                }
                else -> {
                    scatter(V3(d.x, max(-0.02, d.y), d.z).norm(), sun, air, out)
                    for (c in 0..2) out[c] *= 9 * strength
                }
            }
        }

        // the smooth sky on a coarse grid, filled in between (the physical one is slow per pixel)
        val step = if (model == 2) 4 else 1
        val gw = w / step + 2
        val gh = h / step + 2
        val grid = Array(3) { FloatArray(gw * gh) }
        parallelRows(gh) { gy ->
            val c = DoubleArray(3)
            for (gx in 0 until gw) {
                var d = ray(gx * step + 0.5, gy * step + 0.5)
                if (d.y < 0) d = when (below) { 2 -> d; else -> V3(d.x, -d.y, d.z) }
                sky(V3(d.x, max(0.0, d.y), d.z), c)
                for (k in 0..2) grid[k][gy * gw + gx] = c[k].toFloat()
            }
        }
        fun gridAt(px: Double, py: Double, k: Int): Double {
            val fx = ((px - 0.5) / step).coerceIn(0.0, gw - 1.001)
            val fy = ((py - 0.5) / step).coerceIn(0.0, gh - 1.001)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val tx = fx - x0; val ty = fy - y0
            val g = grid[k]
            return (g[y0 * gw + x0] * (1 - tx) + g[y0 * gw + x0 + 1] * tx) * (1 - ty) + (g[(y0 + 1) * gw + x0] * (1 - tx) + g[(y0 + 1) * gw + x0 + 1] * tx) * ty
        }
        // the sky's light from above, for the clouds' shadow sides and the ground
        val ambient = DoubleArray(3).also { sky(V3(0.0, 1.0, 0.0), it) }
        val horizonLight = DoubleArray(3).also { sky(V3(sun.x, 0.02, sun.z).norm(), it) }

        // the angle one pixel spans
        val pixelAngle = (if (projection == 0) fov / w else PI / min(w, h)).coerceAtLeast(1e-5)

        // clouds: a layer at a height, its density from warped noise, lit from the sun
        val coverage = v["clouds"] / 100.0
        val density = v["cloudDensity"] / 100.0
        val cloudScale = 2500.0 * v["cloudSize"] / 100.0
        val cloudHeight = v["cloudHeight"] / 100.0 * 1000
        val time = v["time"] / 100.0
        val wind = Math.toRadians(v["wind"] / 100.0)
        val drift = time * 0.08
        val windX = sin(wind) * drift
        val windZ = cos(wind) * drift
        /** The clouds' noise at (x, z) (in cloud sizes), 0..1, with as much detail as [footprint] – the size of a pixel there – allows. */
        fun cloudNoise(x: Double, z: Double, footprint: Double): Double {
            val octaves = (5 - kotlin.math.log2(max(1.0, footprint * 12))).toInt().coerceIn(1, 5)
            val q = noise.fbm(x * 0.5 + 3.1, z * 0.5 - 7.3, min(3, octaves)) * 0.6
            return noise.fbm(x + q + windX * 1.3, z - q + windZ * 1.3, octaves) * 0.5 + 0.5
        }
        // the coverage moves the threshold through the noise's range
        val threshold = 0.72 - coverage * 0.62
        /** How much cloud the noise [n] makes, with the threshold raised by [lift] (narrower further up). */
        fun cover(n: Double, footprint: Double, lift: Double): Double {
            // a narrow edge gives defined clouds; it widens as the detail goes, and far enough away it is just the average cover
            val edge = 0.12 + min(0.5, footprint)
            val sharp = smoothstep(threshold + lift, threshold + lift + edge, n)
            return sharp + (coverage * 0.75 * (1 - lift * 2).coerceAtLeast(0.0) - sharp) * smoothstep(0.3, 1.5, footprint)
        }
        fun cloudField(x: Double, z: Double, footprint: Double) = cover(cloudNoise(x, z, footprint), footprint, 0.0)
        val thickness = v["cloudThickness"] / 100.0 * 1000
        val darkBases = v["darkBases"] / 100.0
        // under heavy, dark clouds the sky itself dims
        val overcast = 1 - 0.6 * darkBases * coverage * coverage
        // above the clouds (high up), they lie below as a sea of clouds
        val aboveClouds = air.altitude > cloudHeight + v["cloudThickness"] / 100.0 * 1000
        val gap = abs(cloudHeight - air.altitude)
        /**
         * Clouds of some height along [d] over the color in [col]: the ray marched through the layer, the clouds
         * narrowing upwards (billowing towers), each step lit by how high in the cloud it is (bright tops, dark
         * bases) and from the sun's side, laid front to back.
         */
        fun volume(d: V3, col: DoubleArray, jitter: Double) {
            col.fill(0.0)
            val toward = if (aboveClouds) -d.y else d.y
            if (toward <= 0.002) return
            // where the ray enters and leaves the layer (from above: the top first)
            val near = if (aboveClouds) max(0.0, air.altitude - (cloudHeight + thickness)) / toward else gap / toward
            val far = if (aboveClouds) gap / toward else (gap + thickness) / toward
            // more steps for taller clouds, so their layers don't show
            val steps = (12 + thickness / 250).toInt().coerceAtMost(40)
            val dt = (far - near) / steps
            val footprint = near * pixelAngle / cloudScale / max(0.05, toward)
            val mu = d.dot(sun)
            val g = 0.55
            val forward = (1 - g * g) / (4 * PI * (1 + g * g - 2 * g * mu).pow(1.5))
            var trans = 1.0
            val acc = DoubleArray(3)
            for (i in 0 until steps) {
                val t = near + dt * (i + jitter)
                val x = d.x * t / cloudScale + windX
                val z = d.z * t / cloudScale + windZ
                val height = air.altitude + d.y * t
                val n = cloudNoise(x, z, footprint * (1 + i * 0.2))
                // the thicker the cloud here, the lower its base hangs – a lumpy, rolling underside
                val thick = smoothstep(threshold, 1.0, n)
                val base = cloudHeight - thickness * 0.2 * thick
                val up = ((height - base) / max(1.0, thickness)).coerceIn(0.0, 1.0)
                if (height < base) continue
                // towers: the threshold rises with height, so only the thickest parts reach the top; denser in their cores
                val dens = cover(n, footprint, 0.32 * up * up) * smoothstep(0.0, 0.05, up) * (0.6 + 0.8 * thick)
                if (dens <= 0.002) continue
                // how much light gets through this step
                val sigma = dens * (0.4 + 2.6 * density) * dt / max(1.0, thickness) * 4
                val a = 1 - exp(-sigma)
                // light: higher in the cloud brighter, as the cloud above it shades it less; its base dark (the
                // dark bases make it darker still); the thin edges lit through towards the sun
                val top = up.pow(0.7)
                val shaded = exp(-3 * density * coverage * (1 - top))
                val sunLit = max(0.0, sun.y + 0.1).coerceAtMost(1.0) * (0.1 + 0.9 * top) * shaded + forward * (1 - dens) * 3
                // dark bases: darker low down, and darker under the thick parts
                val dark = (1 - darkBases * 0.9 * (1 - top)) * (1 - darkBases * 0.6 * thick * (1 - top))
                for (k in 0..2) {
                    val lit = (sunColor[k] * sunLit * 1.5 + ambient[k] * (0.3 + 0.5 * top) + horizonLight[k] * 0.1) * dark
                    acc[k] += trans * a * lit
                }
                trans *= 1 - a
                if (trans < 0.02) break
            }
            // far away they thin into the haze
            val fade = smoothstep(0.0, 0.12, toward) * exp(-near / (gap.coerceAtLeast(thickness) * 22))
            for (k in 0..2) col[k] = acc[k] * fade
            col[3] = (1 - trans) * fade
        }

        /**
         * Clouds along [d] into [col]: their light (already weighted by how much they cover, high range) and how
         * much they cover – a flat layer, or towering ones when they have a thickness.
         */
        fun clouds(d: V3, col: DoubleArray, jitter: Double = 0.5) {
            col.fill(0.0)
            if (coverage <= 0) return
            if (thickness > 1) { volume(d, col, jitter); return }
            val toward = if (aboveClouds) -d.y else d.y
            if (toward <= 0.002) return
            val t = gap / toward
            val x = d.x * t / cloudScale + windX
            val z = d.z * t / cloudScale + windZ
            // how much of the layer one pixel covers there, slanted at the horizon
            val footprint = t * pixelAngle / cloudScale / max(0.05, toward)
            val dens = cloudField(x, z, footprint)
            if (dens <= 0.001) return
            // thicker towards the sun: the side facing away is in shadow, the edges glow (silver lining)
            val sunward = cloudField(x + sun.x * 0.06, z + sun.z * 0.06, footprint)
            val shade = exp(-sunward * density * 3.0) * (1 - darkBases * 0.7 * sunward)
            val mu = d.dot(sun)
            val g = 0.55
            val silver = (1 - g * g) / (4 * PI * (1 + g * g - 2 * g * mu).pow(1.5)) * (1 - dens) * 6
            // far away (near the horizon) they thin into the haze, before they get too small to draw
            val alpha = (dens * (0.35 + 0.65 * density)) * smoothstep(0.0, 0.12, toward) * exp(-t / (gap * 22))
            for (k in 0..2) {
                val lit = sunColor[k] * max(0.0, sun.y + 0.1).coerceAtMost(1.0) * (0.25 + 0.75 * shade + silver) * 1.6 +
                    ambient[k] * 0.8 + horizonLight[k] * 0.4 * (1 - shade)
                col[k] = lit * alpha
            }
            col[3] = alpha
        }
        val sunSize = Math.toRadians(v["sunSize"] / 100.0 / 2)
        val stars = v["stars"] / 100.0 * (1 - smoothstep(-0.15, 0.03, sun.y))
        val ripples = v["ripples"] / 100.0
        val exposure = 2.0.pow(v["exposure"] / 100.0)
        val filmic = v["tonemap"] == 0

        // first the sky with its sun and stars, and the clouds apart (their light and cover), so the clouds can be
        // smoothed a touch before they are laid over – the steps through tall clouds leave a fine pattern otherwise
        val pre = Array(3) { FloatArray(w * h) }
        // the sun's disk and the stars apart: points so bright that what little of them a cloud lets through would still show
        val disk = Array(3) { FloatArray(w * h) }
        val cloud = Array(4) { FloatArray(w * h) }
        val mirrorAt = FloatArray(w * h) { 1f }
        parallelRows(h) { y ->
            val c = DoubleArray(3)
            val cl = DoubleArray(4)
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                val d = ray(px, py)
                // fisheye: beyond the horizon circle only when looking down
                var view = d
                var mirror = 1.0
                var groundMix = 0.0
                if (d.y < 0 && below != 2) {
                    if (below == 1) {
                        // water: the sky mirrored, rippled; less reflected looking down (Fresnel), the rest dark water
                        val dist = 1 / max(0.002, -d.y)
                        val wx = d.x * dist * 20
                        val wz = d.z * dist * 20
                        val nx = noise.fbm(wx * 0.3 + time * 0.2, wz * 0.3, 3) * ripples * 0.12
                        val nz = noise.fbm(wx * 0.3 - 9.0, wz * 0.3 + time * 0.2, 3) * ripples * 0.12
                        view = V3(d.x + nx, -d.y, d.z + nz).norm()
                        val cosI = -d.y
                        mirror = 0.02 + 0.98 * (1 - cosI).pow(5)
                    } else {
                        groundMix = 1.0
                    }
                }
                for (k in 0..2) c[k] = gridAt(px, py, k) * overcast
                if (groundMix > 0) {
                    // ground: lit by sun and sky, fading into the horizon's haze
                    val haze = exp(-(-d.y) * 12)
                    for (k in 0..2) {
                        val gc = ch(groundColor, 16 - 8 * k) * (sunColor[k] * max(0.0, sun.y) * 0.6 + ambient[k] * 0.6)
                        c[k] = gc + (c[k] - gc) * haze
                    }
                    if (aboveClouds) clouds(d, cl) else cl.fill(0.0)
                } else {
                    // stars: fixed in the sky – one in some of the cells of a grid around it (about a third of a degree
                    // each), at a random place in its cell, of random brightness (few bright, many faint) and tint;
                    // behind the sun's glare and the clouds
                    if (stars > 0 && view.y > 0) {
                        val gx = floor(view.x * STAR_GRID).toLong(); val gy = floor(view.y * STAR_GRID).toLong(); val gz = floor(view.z * STAR_GRID).toLong()
                        val chance = hash3(gx, gy, gz, seed)
                        if (chance < 0.18 * stars) {
                            val star = V3(
                                (gx + 0.1 + 0.8 * hash3(gx, gy, gz, seed + 1)) / STAR_GRID,
                                (gy + 0.1 + 0.8 * hash3(gx, gy, gz, seed + 2)) / STAR_GRID,
                                (gz + 0.1 + 0.8 * hash3(gx, gy, gz, seed + 3)) / STAR_GRID,
                            ).norm()
                            val ang = acos(view.dot(star).coerceIn(-1.0, 1.0))
                            val spot = (1 - ang / (pixelAngle * 0.9)).coerceIn(0.0, 1.0)
                            if (spot > 0) {
                                val magnitude = hash3(gx, gy, gz, seed + 4)
                                val twinkle = 1 + 0.3 * sin(time * 9 + magnitude * 400)
                                val bright = (0.08 + 3.0 * magnitude.pow(8)) * twinkle * stars * spot * smoothstep(0.0, 0.12, view.y)
                                // a little bluer or yellower
                                val tint = hash3(gx, gy, gz, seed + 5) - 0.5
                                val i = y * w + x
                                disk[0][i] += (bright * (1 + 0.25 * tint)).toFloat(); disk[1][i] += bright.toFloat(); disk[2][i] += (bright * (1 - 0.25 * tint)).toFloat()
                            }
                        }
                    }
                    // the sun's disk, limb-darkened, in the color the air leaves it
                    if (sunSize > 0) {
                        val ang = acos(view.dot(sun).coerceIn(-1.0, 1.0))
                        val edge = sunSize + pixelAngle
                        if (ang < edge) {
                            val k = ((edge - ang) / (pixelAngle * 1.5)).coerceIn(0.0, 1.0)
                            val limb = sqrt(max(0.0, 1 - (ang / max(1e-6, sunSize)).pow(2))).coerceAtLeast(0.4)
                            for (j in 0..2) disk[j][y * w + x] = (sunColor[j] * 40 * limb * k).toFloat()
                        }
                    }
                    // the steps through the clouds shifted per pixel in a fine, even pattern, so they don't show as layers
                    clouds(view, cl, ((52.9829189 * ((0.06711056 * x + 0.00583715 * y) % 1.0)) % 1.0))
                }
                val i = y * w + x
                for (k in 0..2) pre[k][i] = c[k].toFloat()
                for (k in 0..3) cloud[k][i] = cl[k].toFloat()
                mirrorAt[i] = mirror.toFloat()
            }
        }
        val smoothCloud = if (thickness > 1 && coverage > 0) Array(4) { Blur.gauss(cloud[it], w, h, 0.8) } else cloud

        // ---- lightning: bolts in the scene, from the clouds' base down to the ground, projected like the sky ----
        val lightning = v["lightning"] / 100.0
        val bolt = if (lightning > 0 && air.altitude < cloudHeight) lightning(
            seed, time, v["bolts"], min(v["boltNear"], v["boltFar"]) / 100.0 * 1000, max(v["boltNear"], v["boltFar"]) / 100.0 * 1000,
            v["branching"] / 100.0, v.bool("flashes"),
            cloudHeight - air.altitude, max(1.0, air.altitude + 1), View(w, h, projection, heading, roll, half, fwd, right, up),
            // the clouds at a place of their layer (relative to the camera), as they cover it
            if (coverage > 0) { x, z -> cloudField(x / cloudScale + windX, z / cloudScale + windZ, 0.05) } else null,
        ) else null
        val boltColor = DoubleArray(3) { ch(v["boltColor"], 16 - 8 * it) }
        val boltGlow = if (bolt != null) Blur.gauss(bolt, w, h, max(1.0, min(w, h) * 0.003)) else null
        // the flash: a wide light around the bolts, lighting the clouds from inside and a little of the sky
        val flash = if (bolt != null) Blur.gauss(bolt, w, h, max(2.0, min(w, h) * 0.1)) else null
        val dark = DoubleArray(3) { ch(groundColor, 16 - 8 * it) * 0.15 * (ambient[it] + 0.02) }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val c = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val a = smoothCloud[3][i].toDouble().coerceIn(0.0, 1.0)
                // the sun and stars dim much faster than the sky behind a cloud: a cloud that hides most of the sky hides them
                val through = (1 - a).pow(8)
                for (k in 0..2) c[k] = pre[k][i] * (1 - a) + smoothCloud[k][i] + disk[k][i] * through
                // the bolts over it, their glow, and the flash in the clouds
                if (bolt != null) {
                    val core = bolt[i].toDouble()
                    val glowL = min(1.0, boltGlow!![i] * 3.0)
                    val fl = min(1.0, flash!![i] * 7.0)
                    // a white-hot core, a glow in the bolt's color
                    // the flash lights the clouds up from inside – strongly, they glow around the bolt – and a little of the sky
                    for (k in 0..2) c[k] += lightning * (core * 3 + boltColor[k] * (glowL * 0.6 + fl * (0.06 + 3.0 * a)))
                }
                // water: the mirrored sky over the dark water
                val mirror = mirrorAt[i].toDouble()
                if (mirror < 1) for (k in 0..2) c[k] = dark[k] + (c[k] - dark[k]) * (0.15 + 0.85 * mirror)
                // exposed and tone mapped
                fun tone(q: Double): Int {
                    val e = max(0.0, q * exposure)
                    val m = if (filmic) (e * (2.51 * e + 0.03)) / (e * (2.43 * e + 0.59) + 0.14) else e
                    return (m.coerceIn(0.0, 1.0).pow(1 / 2.2) * 255 + 0.5).toInt().coerceIn(0, 255)
                }
                out.data[i] = (0xFF shl 24) or (tone(c[0]) shl 16) or (tone(c[1]) shl 8) or tone(c[2])
            }
        }
        return out
    }
}
