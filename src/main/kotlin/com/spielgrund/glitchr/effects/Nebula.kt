package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Nebula: a glowing cloud of gas in space, ray-marched in 3D. Its density is 3D noise, warped into
 * itself for wisps and curls, shaped into a cloud, a shell (like a planetary nebula) or long
 * filaments; the gas glows in a palette by how dense it is, dark dust lanes swallow light in front of
 * it, and stars shine behind and inside it. A camera circles it; the time lets the gas drift and
 * churn. Rendered at a lower resolution (the gas is soft anyway) and enlarged; the stars are drawn
 * sharp afterwards.
 */
object Nebula : Effect("nebula", "Nebula", "A glowing space nebula, ray-marched in 3D: wisps, a shell or filaments of gas in palettes, dark dust lanes and stars – turn the camera, animate the drift") {
    private val shapes = listOf("Cloud", "Shell", "Filaments")
    /** Thin gas, gas, second gas, hot core, deep space – as in the Galaxy skin. */
    private val palettes = listOf(
        "Galaxy" to intArrayOf(0x1A0B3A, 0x6A2CC8, 0x1E8CE8, 0xFF4FA8, 0x05030F),
        "Orion" to intArrayOf(0x1A0B3A, 0xC2307A, 0x5A3AD8, 0xFFB0D8, 0x07030C),
        "Carina" to intArrayOf(0x0B2A3A, 0xE07A2A, 0x2A9AB8, 0xFFE6B0, 0x03070A),
        "Hubble (SHO)" to intArrayOf(0x0A2A4A, 0x2FA89A, 0xD08A2A, 0xF2C04A, 0x02060A),
        "Ice" to intArrayOf(0x061A3A, 0x3A8AE0, 0x7A5AE0, 0xD8F6FF, 0x01030C),
        "Inferno" to intArrayOf(0x2A0606, 0xD8361A, 0x8A1E5A, 0xFFE07A, 0x0A0202),
        "Toxic" to intArrayOf(0x061E12, 0x34C46A, 0x1E8AB8, 0xE6FF9A, 0x020802),
        "Custom" to intArrayOf(0, 0, 0, 0, 0),
    )
    private val CUSTOM = palettes.size - 1

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: the gas drifts and churns", decimals = 3),
        Param.Slider("drift", "Drift", 0, 400, 60, " %", "How fast it churns with the time"),
        Param.Heading("shapeHeading", "Shape"),
        Param.Choice("shape", "Shape", shapes, 0, "Cloud: a billowing mass · Shell: a hollow sphere of gas, like a planetary nebula · Filaments: long glowing threads"),
        Param.Slider("size", "Size", 10, 300, 100, " %", "How much of the picture the nebula fills"),
        Param.Slider("detail", "Detail", 20, 400, 100, " %", "Smaller = big soft billows · larger = fine structure"),
        Param.Slider("warp", "Swirl", 0, 300, 100, " %", "How much the gas is curled and twisted into itself"),
        Param.Slider("density", "Density", 1, 400, 100, " %", "How thick the gas is"),
        Param.Slider("cut", "Clearings", 0, 100, 40, " %", "How much of the thin gas is cleared away, leaving clouds with gaps"),
        Param.Heading("colorHeading", "Light"),
        Param.Choice("palette", "Palette", palettes.map { it.first }, 0),
        Param.Color("color1", "Thin gas", 0x10204A).shownWhen("palette", CUSTOM),
        Param.Color("color2", "Gas", 0x8040C0).shownWhen("palette", CUSTOM),
        Param.Color("color4", "Second gas", 0x2A80E0).shownWhen("palette", CUSTOM),
        Param.Color("color3", "Hot core", 0xFFD0F0).shownWhen("palette", CUSTOM),
        Param.Color("color5", "Deep space", 0x05030F, "The faint haze far behind").shownWhen("palette", CUSTOM),
        Param.Slider("glow", "Brightness", 10, 500, 100, " %"),
        Param.Slider("core", "Core light", 0, 300, 100, " %", "A bright light in the middle that lights the gas around it"),
        Param.Slider("dust", "Dust lanes", 0, 300, 80, " %", "Dark dust that swallows the light in front of the glow"),
        Param.Slider("hueShift", "Color variety", 0, 100, 35, " %", "How much the colors wander through the cloud"),
        Param.Heading("starsHeading", "Stars"),
        Param.Slider("stars", "Stars", 0, 300, 100, " %", "Stars all around in the sky – they turn with the camera and are dimmed behind the gas"),
        Param.Slider("bigStars", "Bright stars", 0, 60, 8, tip = "Bright stars in the sky with a glow and four-pointed flares"),
        Param.Slider("innerStars", "Stars in the nebula", 0, 300, 40, tip = "Stars inside the gas: veiled by what lies in front of them, lighting the gas around them"),
        Param.Slider("starSize", "Star size", 20, 400, 100, " %"),
        Param.Slider("flares", "Flares", 0, 100, 60, " %", "Four-pointed spikes on the brightest stars"),
        Param.Slider("twinkle", "Twinkle", 0, 100, 30, " %", "The stars flicker with the time"),
        Param.Slider("haze", "Space haze", 0, 200, 60, " %", "A faint glow of far-away gas behind everything – it turns with the camera"),
        Param.Heading("cameraHeading", "Camera"),
        Param.Slider("yaw", "Turn", -18000, 18000, 0, "°", "Circles the camera around the nebula – animate it to fly around", decimals = 2),
        Param.Slider("pitch", "Tilt", -9000, 9000, 1500, "°", decimals = 2),
        Param.Slider("zoom", "Zoom", 30, 400, 100, " %"),
        Param.Choice("quality", "Quality", listOf("Draft", "Normal", "High"), 1, "Draft is quick for adjusting, High for the final render"),
        Param.Toggle("transparent", "Transparent background", false, "Only the nebula and stars, without the base color"),
    )

    /** A random number 0..1 for a lattice point. */
    private fun hash(x: Int, y: Int, z: Int, s: Int): Double {
        var h = x * 374761393 + y * 668265263 + z * 1274126177 + s * 1103515245
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216.0
    }

    /** Smooth 3D value noise, 0..1. */
    private fun noise(x: Double, y: Double, z: Double, s: Int): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val xf = x - xi; val yf = y - yi; val zf = z - zi
        val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf); val w = zf * zf * (3 - 2 * zf)
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        val x00 = l(hash(xi, yi, zi, s), hash(xi + 1, yi, zi, s), u)
        val x10 = l(hash(xi, yi + 1, zi, s), hash(xi + 1, yi + 1, zi, s), u)
        val x01 = l(hash(xi, yi, zi + 1, s), hash(xi + 1, yi, zi + 1, s), u)
        val x11 = l(hash(xi, yi + 1, zi + 1, s), hash(xi + 1, yi + 1, zi + 1, s), u)
        return l(l(x00, x10, v), l(x01, x11, v), w)
    }

    /** Fractal noise of [octaves] layers, about 0..1. */
    private fun fbm(x: Double, y: Double, z: Double, s: Int, octaves: Int): Double {
        var sum = 0.0; var amp = 0.5; var f = 1.0; var norm = 0.0
        for (o in 0 until octaves) {
            sum += amp * noise(x * f, y * f, z * f, s + o * 17)
            norm += amp; amp *= 0.5; f *= 2.03
        }
        return sum / norm
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val quality = v["quality"]
        val div = intArrayOf(4, 2, 1)[quality]
        val steps = intArrayOf(28, 48, 80)[quality]
        val octaves = intArrayOf(4, 5, 6)[quality]
        val lw = (w + div - 1) / div
        val lh = (h + div - 1) / div

        val time = v["time"] / 1000.0 * v["drift"] / 100.0 * 0.05
        val shape = v["shape"]
        val detail = 3.2 * v["detail"] / 100.0
        val warp = v["warp"] / 100.0
        val density = 6.0 * v["density"] / 100.0
        val cut = v["cut"] / 100.0 * 0.45
        val glow = v["glow"] / 100.0
        val coreK = v["core"] / 100.0
        val dustK = v["dust"] / 100.0
        val hueShift = v["hueShift"] / 100.0
        val pal = if (v["palette"] == CUSTOM) intArrayOf(v["color1"], v["color2"], v["color4"], v["color3"], v["color5"]) else palettes[v["palette"]].second
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val c1 = DoubleArray(3) { ch(pal[0], 16 - 8 * it) }
        val c2 = DoubleArray(3) { ch(pal[1], 16 - 8 * it) }
        val c4 = DoubleArray(3) { ch(pal[2], 16 - 8 * it) }
        val c3 = DoubleArray(3) { ch(pal[3], 16 - 8 * it) }
        val deep = DoubleArray(3) { ch(pal[4], 16 - 8 * it) }
        val hazeK = v["haze"] / 100.0

        // the camera: circling the nebula (a unit sphere) at a distance, looking at its middle
        val yaw = Math.toRadians(v["yaw"] / 100.0)
        val pitch = Math.toRadians(v["pitch"] / 100.0)
        val dist = 3.2 / (v["zoom"] / 100.0)
        val camX = dist * cos(pitch) * sin(yaw); val camY = dist * sin(pitch); val camZ = -dist * cos(pitch) * cos(yaw)
        // forward, right and up
        val fx = -camX / dist; val fy = -camY / dist; val fz = -camZ / dist
        // right: level, from the turn alone – so it stays defined looking straight down or up (±90° tilt)
        val rx = cos(yaw); val rz = sin(yaw)
        // up = forward × right
        val ux = rz * fy; val uy = rx * fz - rz * fx; val uz = -rx * fy
        val half = tan(Math.toRadians(40.0) / 2) / (v["size"] / 100.0)
        val radius = 1.0

        /** How dense the gas is at p, and a second value for its color. */
        fun gas(px: Double, py: Double, pz: Double, out: DoubleArray) {
            val r = sqrt(px * px + py * py + pz * pz)
            if (r > radius) { out[0] = 0.0; return }
            val qx = px * detail + time; val qy = py * detail - time * 0.7; val qz = pz * detail + time * 0.4
            // the gas curled into itself
            val wx = (fbm(qx + 5.2, qy + 1.3, qz, s + 101, 2) - 0.5) * 2 * warp
            val wy = (fbm(qx + 1.7, qy + 9.2, qz + 3.1, s + 202, 2) - 0.5) * 2 * warp
            val wz = (fbm(qx + 8.3, qy + 2.8, qz + 7.7, s + 303, 2) - 0.5) * 2 * warp
            // fractal noise bunches around its middle: spread out for more contrast between thick and thin
            var n = ((fbm(qx + wx, qy + wy, qz + wz, s, octaves) - 0.5) * 2.6 + 0.5).coerceIn(0.0, 1.0)
            // its outline: not a ball – bulging out and drawn in here and there
            val lobes = 0.5 + 1.0 * fbm(px * 1.9 + 3.3, py * 1.9 - 7.1, pz * 1.9 + 1.9, s + 505, 3)
            val re = r / lobes
            val envelope = when (shape) {
                1 -> exp(-((re - 0.6) / 0.16).pow(2))
                else -> (1 - re).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            }
            if (shape == 2) n = (1 - kotlin.math.abs(n * 2 - 1)).pow(3) * 1.4
            out[0] = ((n - cut) / (1 - cut)).coerceAtLeast(0.0) * envelope
            out[1] = fbm(qx * 0.5 + 11.0, qy * 0.5, qz * 0.5 - 4.0, s + 404, 2)
        }

        val lr = FloatArray(lw * lh); val lg = FloatArray(lw * lh); val lb = FloatArray(lw * lh); val lt = FloatArray(lw * lh)
        parallelRows(lh) { y ->
            val g = DoubleArray(2)
            for (x in 0 until lw) {
                val sx = ((x + 0.5) / lw * 2 - 1) * half * w / h
                val sy = (1 - (y + 0.5) / lh * 2) * half
                var dx = fx + rx * sx + ux * sy; var dy = fy + uy * sy; var dz = fz + rz * sx + uz * sy
                val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
                // where the ray crosses the nebula's sphere
                val b = camX * dx + camY * dy + camZ * dz
                val c = camX * camX + camY * camY + camZ * camZ - radius * radius
                val disc = b * b - c
                var cr = 0.0; var cg = 0.0; var cb = 0.0; var trans = 1.0
                if (disc > 0) {
                    val t0 = max(0.0, -b - sqrt(disc)); val t1 = -b + sqrt(disc)
                    val dt = (t1 - t0) / steps
                    // a little jitter per pixel, so the steps don't show as bands
                    var t = t0 + dt * hash(x, y, 7, s)
                    while (t < t1 && trans > 0.01) {
                        val px = camX + dx * t; val py = camY + dy * t; val pz = camZ + dz * t
                        gas(px, py, pz, g)
                        val d = g[0]
                        if (d > 1e-4) {
                            val dd = d * density
                            // its color: thin to dense through the palette, wandering a little
                            val k = (dd * 0.35 + (g[1] - 0.5) * hueShift * 1.6).coerceIn(0.0, 1.0)
                            val r0 = sqrt(px * px + py * py + pz * pz)
                            val lit = glow * (0.32 + coreK * 0.9 * exp(-r0 * r0 * 6))
                            // the gas in two colors, wandering from one to the other
                            val second = ((g[1] - 0.35) * 2.5).coerceIn(0.0, 1.0)
                            fun gasColor(i: Int) = c2[i] + (c4[i] - c2[i]) * second
                            fun mix(i: Int) = if (k < 0.5) c1[i] + (gasColor(i) - c1[i]) * k * 2 else gasColor(i) + (c3[i] - gasColor(i)) * (k - 0.5) * 2
                            val e = dd.pow(1.3) * lit * dt
                            cr += trans * mix(0) * e; cg += trans * mix(1) * e; cb += trans * mix(2) * e
                            // the dust: swallows light, more where the second noise is high
                            val absorb = dd * (0.25 + dustK * 1.8 * ((g[1] - 0.45) * 3).coerceIn(0.0, 1.0))
                            trans *= exp(-absorb * dt)
                        }
                        t += dt
                    }
                }
                // far behind: a faint haze of gas on the sky, fixed in space so it turns with the camera
                if (hazeK > 0) {
                    val hz = fbm(dx * 2.2 + 13.0, dy * 2.2, dz * 2.2 - 5.0, s + 606, 3)
                    val hz2 = fbm(dx * 3.1 - 2.0, dy * 3.1 + 7.0, dz * 3.1, s + 707, 2)
                    val amount = ((hz - 0.42) * 2.4).coerceIn(0.0, 1.0).pow(2) * hazeK * 0.35 * trans
                    for (ci in 0..2) {
                        val col = deep[ci] * 2.5 + (c2[ci] + (c4[ci] - c2[ci]) * hz2) * 0.6
                        when (ci) { 0 -> cr += col * amount; 1 -> cg += col * amount; else -> cb += col * amount }
                    }
                }
                val i = y * lw + x
                lr[i] = cr.toFloat(); lg[i] = cg.toFloat(); lb[i] = cb.toFloat(); lt[i] = trans.toFloat()
            }
        }

        // enlarged to the picture, toned, over the base and with the stars
        val transparent = v.bool("transparent")
        fun up(a: FloatArray, x: Int, y: Int): Double {
            val gx = ((x + 0.5) / div - 0.5).coerceIn(0.0, lw - 1.0); val gy = ((y + 0.5) / div - 0.5).coerceIn(0.0, lh - 1.0)
            val x0 = gx.toInt(); val y0 = gy.toInt(); val x1 = min(lw - 1, x0 + 1); val y1 = min(lh - 1, y0 + 1)
            val tx = gx - x0; val ty = gy - y0
            return (a[y0 * lw + x0] * (1 - tx) + a[y0 * lw + x1] * tx) * (1 - ty) + (a[y1 * lw + x0] * (1 - tx) + a[y1 * lw + x1] * tx) * ty
        }
        val out = Pixels(w, h)
        val light = Array(3) { FloatArray(w * h) }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                light[0][i] = up(lr, x, y).toFloat(); light[1][i] = up(lg, x, y).toFloat(); light[2][i] = up(lb, x, y).toFloat()
            }
        }

        // the stars: in 3D – on the sky far away or inside the nebula – seen through the camera
        val starTime = v["time"] / 1000.0
        val twinkle = v["twinkle"] / 100.0
        val starSize = v["starSize"] / 100.0
        val flares = v["flares"] / 100.0
        val aspect = half * w / h
        /** Where direction (or offset from the camera) ([x], [y], [z]) lands on the picture; null behind the camera. */
        fun project(x: Double, y: Double, z: Double): DoubleArray? {
            val cz = x * fx + y * fy + z * fz
            if (cz <= 1e-6) return null
            val sx = (x * rx + z * rz) / cz
            val sy = (x * ux + y * uy + z * uz) / cz
            return doubleArrayOf((sx / aspect + 1) / 2 * w, (1 - sy / half) / 2 * h, cz)
        }
        fun twinkleOf(k: Int) = 1 - twinkle * (0.5 + 0.5 * sin(starTime * (2.0 + hash(k, 8, 11, s) * 5.0) + hash(k, 9, 11, s) * 6.283))
        /** A star: a sharp point (shared out between pixels), a glow and, when bright, four spikes. */
        fun star(px: Double, py: Double, power: Double, col: DoubleArray, flare: Double) {
            if (power <= 0.003) return
            // the point itself
            val fx0 = px - 0.5; val fy0 = py - 0.5
            val ix = floor(fx0).toInt(); val iy = floor(fy0).toInt(); val tx = fx0 - ix; val ty = fy0 - iy
            for ((ox, oy, k) in listOf(Triple(0, 0, (1 - tx) * (1 - ty)), Triple(1, 0, tx * (1 - ty)), Triple(0, 1, (1 - tx) * ty), Triple(1, 1, tx * ty))) {
                val xx = ix + ox; val yy = iy + oy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                for (c in 0..2) light[c][yy * w + xx] += (power * k * col[c] * 1.6).toFloat()
            }
            if (power < 0.25) return
            // the glow and the spikes
            val reach = (min(w, h) * 0.012 * starSize * sqrt(power) * (1 + flare * 2)).toInt().coerceIn(2, min(w, h) / 3)
            val glowR = reach * 0.18 + 0.8
            for (yy in max(0, py.toInt() - reach)..min(h - 1, py.toInt() + reach)) for (xx in max(0, px.toInt() - reach)..min(w - 1, px.toInt() + reach)) {
                val ox = xx + 0.5 - px; val oy = yy + 0.5 - py
                val d2 = (ox * ox + oy * oy) / (glowR * glowR)
                val r = sqrt(ox * ox + oy * oy)
                val spike = if (flare > 0) (exp(-oy * oy / 0.8) + exp(-ox * ox / 0.8)) * exp(-r / (reach * 0.4)) * flare else 0.0
                val e = (exp(-d2) * 0.5 + 0.06 / (1 + d2) + spike * 0.7) * power
                val i = yy * w + xx
                for (c in 0..2) light[c][i] += (e * col[c]).toFloat()
            }
        }
        fun starColor(k: Int): DoubleArray {
            val t = hash(k, 4, 9, s)
            // from orange-white through white to blue-white
            return if (t < 0.3) doubleArrayOf(1.0, 0.86, 0.7) else if (t < 0.75) doubleArrayOf(1.0, 0.97, 0.94) else doubleArrayOf(0.78, 0.87, 1.0)
        }
        fun transAt(px: Double, py: Double) = up(lt, px.toInt().coerceIn(0, w - 1), py.toInt().coerceIn(0, h - 1))
        // the sky: stars evenly all around, far away – they turn with the camera, veiled by the gas in front
        val skyCount = (v["stars"] / 100.0 * w * h / 14).toInt()
        for (k in 0 until skyCount) {
            val zz = hash(k, 1, 21, s) * 2 - 1; val a = hash(k, 2, 21, s) * 6.283
            val rr = sqrt(1 - zz * zz)
            val p = project(rr * cos(a), zz, rr * sin(a)) ?: continue
            if (p[0] < -2 || p[1] < -2 || p[0] > w + 2 || p[1] > h + 2) continue
            val power = hash(k, 3, 21, s).pow(6) * 0.9 * twinkleOf(k) * transAt(p[0], p[1])
            star(p[0], p[1], power, starColor(k), 0.0)
        }
        for (k in 0 until v["bigStars"]) {
            val zz = hash(k, 1, 31, s) * 2 - 1; val a = hash(k, 2, 31, s) * 6.283
            val rr = sqrt(1 - zz * zz)
            val p = project(rr * cos(a), zz, rr * sin(a)) ?: continue
            val power = (0.6 + hash(k, 3, 31, s) * 1.6) * twinkleOf(k + 5000) * transAt(p[0], p[1])
            star(p[0], p[1], power, starColor(k + 5000), flares)
        }
        // inside the nebula: placed where there is gas, veiled by what lies between them and the camera
        val inner = v["innerStars"]
        if (inner > 0) {
            val g = DoubleArray(2)
            var placed = 0; var tries = 0
            while (placed < inner && tries < inner * 30) {
                tries++
                val px = hash(tries, 1, 41, s) * 2 - 1; val py = hash(tries, 2, 41, s) * 2 - 1; val pz = hash(tries, 3, 41, s) * 2 - 1
                if (px * px + py * py + pz * pz > 0.8) continue
                gas(px, py, pz, g)
                if (g[0] < 0.03 && hash(tries, 4, 41, s) > 0.15) continue
                placed++
                val ox = px - camX; val oy = py - camY; val oz = pz - camZ
                val p = project(ox, oy, oz) ?: continue
                // the gas between: marched from the camera to the star
                val len = sqrt(ox * ox + oy * oy + oz * oz)
                val n = 24
                var tr = 1.0
                for (m in 0 until n) {
                    val t = (m + 0.5) / n
                    gas(camX + ox * t, camY + oy * t, camZ + oz * t, g)
                    val dd = g[0] * density
                    tr *= exp(-dd * (0.25 + dustK * 1.8 * ((g[1] - 0.45) * 3).coerceIn(0.0, 1.0)) * len / n)
                }
                // nearer stars look a little bigger and brighter
                val near = dist / p[2]
                val power = (0.3 + hash(placed, 5, 41, s).pow(2) * 1.8) * near * twinkleOf(placed + 9000) * tr
                // lights the gas around it in its color
                val col = starColor(placed + 9000)
                val haloR = min(w, h) * 0.05 * near * starSize
                val hr = haloR.toInt().coerceAtLeast(2)
                for (yy in max(0, p[1].toInt() - hr * 2)..min(h - 1, p[1].toInt() + hr * 2)) for (xx in max(0, p[0].toInt() - hr * 2)..min(w - 1, p[0].toInt() + hr * 2)) {
                    val dx2 = xx + 0.5 - p[0]; val dy2 = yy + 0.5 - p[1]
                    val e = exp(-(dx2 * dx2 + dy2 * dy2) / (haloR * haloR)) * 0.12 * power
                    val ii = yy * w + xx
                    for (c in 0..2) light[c][ii] += (e * (col[c] * 0.5 + c3[c] * 0.5)).toFloat()
                }
                star(p[0], p[1], power, col, flares * 0.7)
            }
        }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val tr = up(lt, x, y)
                fun tone(l: Float) = 1 - exp(-l * 1.4)
                val r = tone(light[0][i]); val g = tone(light[1][i]); val b = tone(light[2][i])
                if (transparent) {
                    val a = max(r, max(g, b)).coerceIn(0.0, 1.0)
                    out.data[i] = if (a <= 0.002) 0 else {
                        fun c8(c: Double) = (c / a * 255).roundToInt().coerceIn(0, 255)
                        ((a * 255).roundToInt() shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
                    }
                } else {
                    val base = src.data[i]
                    // the base shows through where the gas lets it, the light adds on top
                    fun add(sh: Int, l: Double) = ((base shr sh and 0xFF) * (0.35 + 0.65 * tr) + l * 255 * (1 - (base shr sh and 0xFF) / 255.0)).roundToInt().coerceIn(0, 255)
                    out.data[i] = (base and 0xFF000000.toInt()) or (add(16, r) shl 16) or (add(8, g) shl 8) or add(0, b)
                }
            }
        }
        return out
    }
}
