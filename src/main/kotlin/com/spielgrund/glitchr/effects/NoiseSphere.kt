package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
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
 * A sphere of thousands of points, like audio-reactive visuals: each point shoots out along its
 * direction as a fine filament, as far as 3D noise says – calm, a grainy, cellular ball; with
 * energy, hairy rays exploding outwards. Beneath them a solid surface: the sphere displaced by a
 * web of cells and the noise, ray marched, lit and darkened in its hollows (ambient occlusion). Streaks shooting out of the bright web, and glow on top; the noise
 * moves with time, so animated energy and time make it pulse and churn.
 */
object NoiseSphere : Effect("noisesphere", "Noise sphere", "A sphere of points and fine filaments shooting out by 3D noise – from a calm grainy ball to an exploding, hairy burst") {
    override val params = listOf(
        Param.Slider("energy", "Energy", 0, 100, 40, " %", "Calm and compact (0 %) up to exploding, hairy rays – animate it to the beat"),
        Param.Slider("time", "Time", 0, 100000, 0, "", "Moves the noise: animate it and the sphere churns", decimals = 2),
        Param.Slider("count", "Points", 500, 20000, 7000, tip = "How many points and filaments"),
        Param.Slider("size", "Size", 100, 10000, 3200, " %", "Radius, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("reach", "Reach", 0, 300, 90, " %", "How long the filaments get at full energy, of the radius"),
        Param.Slider("spikes", "Spikiness", 0, 100, 55, " %", "Few long spikes (high) or many even ones (low)"),
        Param.Slider("noiseScale", "Noise scale", 10, 400, 100, " %", "Size of the noise's patches on the sphere"),
        Param.Slider("detail", "Detail", 1, 5, 3, tip = "Layers of noise: more = finer, more ragged"),
        Param.Slider("cells", "Cells", 0, 100, 70, " %", "A web of cells on the surface – the grainy, calm look"),
        Param.Slider("cellCount", "Cell count", 10, 600, 260),
        Param.Heading("surfaceHeading", "Surface"),
        Param.Slider("surface", "Surface", 0, 100, 80, " %", "A solid sphere beneath the points: displaced by the cells and the noise, lit, with ambient occlusion"),
        Param.Slider("displace", "Displacement", 0, 100, 45, " %", "How far the cells and the noise push the surface in and out"),
        Param.Slider("ridges", "Web relief", -100, 100, 60, " %", "The cells' borders raised as ridges (positive) or sunk as grooves (negative)"),
        Param.Slider("ao", "Ambient occlusion", 0, 100, 75, " %", "Hollows and grooves darker, as by a shader"),
        Param.Color("surfaceColor", "Surface color", 0x9AA6B6),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("rotX", "Rotation X", -36000, 36000, 0, "°", decimals = 2),
        Param.Slider("rotY", "Rotation Y", -36000, 36000, 0, "°", decimals = 2),
        Param.Slider("rotZ", "Rotation Z", -36000, 36000, 0, "°", decimals = 2),
        Param.Slider("sphereX", "Position X", -10000, 10000, 0, " %", "From the middle, in canvas widths", decimals = 2, transform = true),
        Param.Slider("sphereY", "Position Y", -10000, 10000, 0, " %", "From the middle, in canvas heights", decimals = 2, transform = true),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Color", 0xCFE0F5),
        Param.Slider("brightness", "Brightness", 10, 400, 100, " %"),
        Param.Slider("width", "Line width", 30, 400, 100, " px", decimals = 2),
        Param.Slider("streaks", "Streaks", 0, 100, 15, " %", "Rays shooting outwards from the bright web between the cells, like a zoom blur"),
        Param.Slider("glow", "Glow", 0, 300, 25, " %"),
        Param.Toggle("transparent", "Transparent background", false, "Only the light, without the base color"),
    )

    // ---- 3D gradient noise ----
    private fun hash(x: Int, y: Int, z: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    private fun grad(h: Int, x: Double, y: Double, z: Double) = when (h and 15) {
        0 -> x + y; 1 -> -x + y; 2 -> x - y; 3 -> -x - y
        4 -> x + z; 5 -> -x + z; 6 -> x - z; 7 -> -x - z
        8 -> y + z; 9 -> -y + z; 10 -> y - z; 11 -> -y - z
        12 -> x + y; 13 -> -x + y; 14 -> -y + z; else -> -y - z
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    /** Perlin noise in 3D, about −1..1. */
    private fun noise(x: Double, y: Double, z: Double, seed: Int): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val xf = x - xi; val yf = y - yi; val zf = z - zi
        val u = fade(xf); val v = fade(yf); val w = fade(zf)
        fun g(dx: Int, dy: Int, dz: Int) = grad(hash(xi + dx, yi + dy, zi + dz, seed), xf - dx, yf - dy, zf - dz)
        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
        return lerp(
            lerp(lerp(g(0, 0, 0), g(1, 0, 0), u), lerp(g(0, 1, 0), g(1, 1, 0), u), v),
            lerp(lerp(g(0, 0, 1), g(1, 0, 1), u), lerp(g(0, 1, 1), g(1, 1, 1), u), v),
            w,
        )
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val energy = v["energy"] / 100.0
        val time = v["time"] / 100.0
        val count = v["count"]
        val radius = min(w, h) * v["size"] / 10000.0
        val reach = v["reach"] / 100.0
        val spikes = v["spikes"] / 100.0
        val freq = 2.2 / (v["noiseScale"] / 100.0)
        val octaves = v["detail"]
        val cells = v["cells"] / 100.0
        val cx = w / 2.0 + v["sphereX"] / 10000.0 * w
        val cy = h / 2.0 + v["sphereY"] / 10000.0 * h
        val ax = Math.toRadians(v["rotX"] / 100.0); val ay = Math.toRadians(v["rotY"] / 100.0); val az = Math.toRadians(v["rotZ"] / 100.0)
        val cX = cos(ax); val sX = sin(ax); val cY = cos(ay); val sY = sin(ay); val cZ = cos(az); val sZ = sin(az)
        val eye = radius * 6
        val lineWidth = v["width"] / 100.0

        /** Several layers of noise at the direction (x, y, z), moving with time. */
        fun fbm(x: Double, y: Double, z: Double): Double {
            var sum = 0.0; var amp = 1.0; var f = freq; var norm = 0.0
            for (o in 0 until octaves) {
                sum += noise(x * f + time * 0.31 + o * 5.3, y * f - time * 0.23, z * f + time * 0.17, s + o) * amp
                norm += amp; amp *= 0.5; f *= 2.1
            }
            return sum / norm
        }

        // the cells: random places on the sphere; a point near the border of two is part of the web
        val cellCount = v["cellCount"]
        val centres = Array(cellCount) {
            val u = random.nextDouble() * 2 - 1
            val a = random.nextDouble() * 2 * PI
            val r = sqrt(1 - u * u)
            doubleArrayOf(r * cos(a), u, r * sin(a))
        }

        // light gathered in a buffer, added up
        val light = FloatArray(w * h)
        // the bright web alone, where the streaks start
        val webLight = FloatArray(w * h)
        fun splat(x: Double, y: Double, amount: Double, into: FloatArray = light) {
            // a soft dot as wide as the line
            val r = max(0.5, lineWidth / 2)
            val x0 = floor(x - r).toInt(); val x1 = floor(x + r).toInt()
            val y0 = floor(y - r).toInt(); val y1 = floor(y + r).toInt()
            for (py in y0..y1) {
                if (py < 0 || py >= h) continue
                for (px in x0..x1) {
                    if (px < 0 || px >= w) continue
                    val d = hypot(px + 0.5 - x, py + 0.5 - y)
                    val k = (1 - d / (r + 0.5)).coerceIn(0.0, 1.0)
                    if (k > 0) into[py * w + px] += (amount * k).toFloat()
                }
            }
        }
        val golden = PI * (3 - sqrt(5.0))
        val p = DoubleArray(3)
        fun turn(x: Double, y: Double, z: Double) {
            val x1 = x * cY + z * sY
            val z1 = -x * sY + z * cY
            val y2 = y * cX - z1 * sX
            val z2 = y * sX + z1 * cX
            p[0] = x1 * cZ - y2 * sZ
            p[1] = x1 * sZ + y2 * cZ
            p[2] = z2
        }
        for (i in 0 until count) {
            // evenly over the sphere, a little shaken
            val yy = 1 - 2 * (i + 0.5) / count
            val rr = sqrt(max(0.0, 1 - yy * yy))
            val phi = i * golden + (random.nextDouble() - 0.5) * 0.3
            val dx = rr * cos(phi); val dy = yy; val dz = rr * sin(phi)
            // how far it shoots out: the noise, sharpened into spikes, scaled by the energy
            val n = (fbm(dx, dy, dz) * 0.9 + 0.5).coerceIn(0.0, 1.0)
            // spikes: only where the noise is above a threshold, rising softly from it
            val threshold = spikes * 0.6
            val spike = ((n - threshold) / (1 - threshold)).coerceIn(0.0, 1.0).pow(1.6) * (0.4 + random.nextDouble() * 0.6)
            val length = radius * reach * energy * spike * 1.6
            // the surface itself wobbles a little with the noise
            val surface = radius * (1 + (n - 0.5) * 0.12 * (0.3 + energy))
            // on the cells' web: near the border between the two nearest cells
            var web = 0.0
            if (cells > 0) {
                var d1 = 9.0; var d2 = 9.0
                for (c in centres) {
                    val d = (c[0] - dx) * (c[0] - dx) + (c[1] - dy) * (c[1] - dy) + (c[2] - dz) * (c[2] - dz)
                    if (d < d1) { d2 = d1; d1 = d } else if (d < d2) d2 = d
                }
                val edge = sqrt(d2) - sqrt(d1)
                web = exp(-edge * edge * cellCount * 45.0)
            }
            turn(dx, dy, dz)
            val ux = p[0]; val uy = p[1]; val uz = p[2]
            // the far side dimmer
            val depth = 0.35 + 0.65 * (uz * 0.5 + 0.5)
            fun screen(rad: Double, out: DoubleArray) {
                val z = uz * rad
                val f = eye / (eye - z)
                out[0] = cx + ux * rad * f
                out[1] = cy - uy * rad * f
            }
            val a = DoubleArray(2); val b = DoubleArray(2)
            screen(surface, a)
            // seen from the side the points crowd together: dimmer there, so the rim doesn't glare
            val facing = 0.3 + 0.7 * abs(uz)
            val dot = 0.5 * depth * facing * (1 - cells * 0.95 + cells * 3.6 * web)
            splat(a[0], a[1], dot)
            if (web > 0.3) splat(a[0], a[1], 0.5 * depth * facing * cells * 3.6 * (web - 0.3), webLight)
            if (length < 0.5) continue
            screen(surface + length, b)
            val steps = max(1, hypot(b[0] - a[0], b[1] - a[1]).toInt())
            for (k in 1..steps) {
                val t = k / steps.toDouble()
                // brighter at the root, fading out towards the tip
                splat(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, 0.32 * depth * (1 - t).pow(1.2))
            }
        }

        // ---- the surface: a sphere displaced by the cells and the noise, lit, with ambient occlusion ----
        val surface = v["surface"] / 100.0
        val cover = FloatArray(w * h)
        val shade = FloatArray(w * h)
        if (surface > 0) {
            val displace = v["displace"] / 100.0 * 0.22
            val ridges = v["ridges"] / 100.0
            val aoStrength = v["ao"] / 100.0
            // cells per unit of the sphere's direction, so about [cellCount] of them cover it
            val cellFreq = sqrt(cellCount / (4 * PI * 0.8))
            val webWidth = 0.09
            /** Worley noise at q: the distances to the nearest and second nearest feature point. */
            fun worley(qx: Double, qy: Double, qz: Double, out: DoubleArray) {
                val ix = floor(qx).toInt(); val iy = floor(qy).toInt(); val iz = floor(qz).toInt()
                var f1 = 9.0; var f2 = 9.0
                for (a in -1..1) for (b in -1..1) for (c in -1..1) {
                    val hx = hash(ix + a, iy + b, iz + c, s + 911)
                    val px = ix + a + ((hx and 1023) / 1023.0)
                    val py = iy + b + ((hx ushr 10 and 1023) / 1023.0)
                    val pz = iz + c + ((hx ushr 20 and 1023) / 1023.0)
                    val d = sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy) + (pz - qz) * (pz - qz))
                    if (d < f1) { f2 = f1; f1 = d } else if (d < f2) f2 = d
                }
                out[0] = f1; out[1] = f2
            }
            /** The point turned back into the sphere's own frame (the inverse of [turn]). */
            fun unturn(x: Double, y: Double, z: Double, out: DoubleArray) {
                val x1 = x * cZ + y * sZ
                val y2 = -x * sZ + y * cZ
                val y1 = y2 * cX + z * sX
                val z1 = -y2 * sX + z * cX
                out[0] = x1 * cY - z1 * sY
                out[1] = y1
                out[2] = x1 * sY + z1 * cY
            }
            val maxR = radius * (1 + displace * 1.6)
            val minR = radius * (1 - displace * 1.6)
            parallelRows(h) { y ->
                val q = DoubleArray(3)
                val wv = DoubleArray(2)
                /** How far the point lies outside the displaced surface (roughly), and its web share into [web]. */
                fun field(px: Double, py: Double, pz: Double, web: DoubleArray?): Double {
                    val len = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-9)
                    unturn(px / len, py / len, pz / len, q)
                    worley(q[0] * cellFreq, q[1] * cellFreq, q[2] * cellFreq, wv)
                    val edge = wv[1] - wv[0]
                    val onWeb = exp(-(edge / webWidth) * (edge / webWidth))
                    web?.set(0, onWeb)
                    // raised borders (or grooves), plates domed a little, the noise on top
                    val d = cells * (ridges * onWeb - 0.35 * wv[0]) + fbm(q[0], q[1], q[2]) * (0.35 + energy * 0.65)
                    return len - radius * (1 + displace * d)
                }
                val web = DoubleArray(1)
                val n = DoubleArray(3)
                // the web share where the last ray hit
                var hitWeb = 0.0
                /** One ray through the screen point (sx, sy) – y up – from the eye: the shade where it hits, or -1. */
                fun ray(sx: Double, sy: Double): Double {
                    val dl = sqrt(sx * sx + sy * sy + eye * eye)
                    val dx = sx / dl; val dy = sy / dl; val dz = -eye / dl
                    // where the ray meets the outer bound of the surface
                    val b = eye * dz
                    val c = eye * eye - maxR * maxR
                    val disc = b * b - c
                    if (disc < 0) return -1.0
                    var t = -b - sqrt(disc)
                    val tFar = -b + sqrt(disc)
                    var hit = false
                    var prevT = t
                    for (i in 0 until 48) {
                        val f = field(dx * t, dy * t, eye + dz * t, null)
                        if (f < radius * 0.0015) { hit = true; break }
                        prevT = t
                        t += max(f * 0.6, radius * 0.002)
                        if (t > tFar) break
                    }
                    if (!hit) return -1.0
                    // narrowed down between the last step outside and the hit
                    var lo = prevT; var hi = t
                    repeat(5) {
                        val mid = (lo + hi) / 2
                        if (field(dx * mid, dy * mid, eye + dz * mid, null) < 0) hi = mid else lo = mid
                    }
                    t = hi
                    val px = dx * t; val py = dy * t; val pz = eye + dz * t
                    // the surface's direction: the field's slope
                    val e = radius * 0.004
                    n[0] = field(px + e, py, pz, null) - field(px - e, py, pz, null)
                    n[1] = field(px, py + e, pz, null) - field(px, py - e, pz, null)
                    n[2] = field(px, py, pz + e, null) - field(px, py, pz - e, null)
                    val nl = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-12)
                    n[0] /= nl; n[1] /= nl; n[2] /= nl
                    field(px, py, pz, web)
                    hitWeb = web[0]
                    // ambient occlusion: how much of the space just above the surface is taken by the surface
                    var occluded = 0.0
                    var weight = 0.5
                    for (k in 1..4) {
                        val step = radius * displace * 0.45 * k
                        val f = field(px + n[0] * step, py + n[1] * step, pz + n[2] * step, null)
                        occluded += weight * ((step - f) / step).coerceIn(0.0, 1.0)
                        weight *= 0.5
                    }
                    val ao = 1 - aoStrength * (occluded / 0.9375).coerceIn(0.0, 1.0)
                    // light from the top left, in front
                    val diffuse = max(0.0, -0.45 * n[0] + 0.55 * n[1] + 0.7 * n[2])
                    // the web brighter than the plates
                    val albedo = 0.35 + 0.65 * (1 - cells) + cells * 1.1 * web[0]
                    return (0.18 + 0.82 * diffuse) * ao * albedo
                }
                for (x in 0 until w) {
                    val sx = x + 0.5 - cx
                    val sy = -(y + 0.5 - cy)
                    val r = hypot(sx, sy)
                    // a quick look past the sphere's outer bound
                    if (r > maxR * 1.02 + 1) continue
                    val i = y * w + x
                    if (r < minR * 0.98 - 1) {
                        val v0 = ray(sx, sy)
                        if (v0 >= 0) { cover[i] = 1f; shade[i] = v0.toFloat(); webLight[i] += (v0 * max(0.0, hitWeb - 0.4) * surface).toFloat() }
                    } else {
                        // near the outline: four rays, for a smooth edge
                        var hits = 0
                        var sum = 0.0
                        var bright = 0.0
                        for (k in 0 until 4) {
                            val v0 = ray(sx + (k % 2 - 0.5) * 0.5, sy + (k / 2 - 0.5) * 0.5)
                            if (v0 >= 0) { hits++; sum += v0; bright += v0 * max(0.0, hitWeb - 0.4) }
                        }
                        if (hits > 0) { cover[i] = hits / 4f; shade[i] = (sum / hits).toFloat(); webLight[i] += (bright / 4 * surface).toFloat() }
                    }
                }
            }
        }

        // streaks: the bright web smeared outwards from the middle, added to the light
        val streaks = v["streaks"] / 100.0
        var lit = light
        if (streaks > 0) {
            val rays = light.copyOf()
            val samples = 16
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    var sum = 0.0
                    for (k in 1..samples) {
                        // back towards the middle
                        val f = 1 - streaks * 0.35 * k / samples
                        val sx = (cx + (x - cx) * f).toInt()
                        val sy = (cy + (y - cy) * f).toInt()
                        if (sx in 0 until w && sy in 0 until h) sum += webLight[sy * w + sx] * (1 - (k - 1) / samples.toDouble())
                    }
                    rays[y * w + x] += (sum / samples * 3.0).toFloat()
                }
            }
            lit = rays
        }
        // glow around the light
        val glow = v["glow"] / 100.0
        val halo = if (glow > 0) Blur.gauss(lit, w, h, max(1.5, radius * 0.05)) else null
        val gain = v["brightness"] / 100.0
        val color = v["color"]
        val surfaceColor = v["surfaceColor"]
        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val raw = lit[i] + (halo?.get(i) ?: 0f) * glow * 2.5
                // added-up light, soft at the top
                val l = 1 - exp(-raw * gain)
                // the surface, where the rays met it
                val sa = cover[i] * surface
                val sv = shade[i].toDouble()
                if (transparent) {
                    val a = 1 - (1 - sa) * (1 - l)
                    if (a <= 0.002) continue
                    fun ch(sh: Int) = (((surfaceColor shr sh and 0xFF) * sv * sa * (1 - l) + (color shr sh and 0xFF) * l) / a).toInt().coerceIn(0, 255)
                    out.data[i] = ((a * 255 + 0.5).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                } else {
                    val base = src.data[i]
                    fun ch(sh: Int): Int {
                        val under = (base shr sh and 0xFF) * (1 - sa) + (surfaceColor shr sh and 0xFF) * sv * sa
                        return (under + (color shr sh and 0xFF) * l).toInt().coerceIn(0, 255)
                    }
                    out.data[i] = (base and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
        }
        return out
    }
}
