package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
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
 * A manta ray gliding through the water, as a 3D mesh drawn with a z-buffer in perspective and
 * turnable around all three axes. Its body is a flat diamond: a thick middle with rolled-up
 * cephalic fins either side of the wide mouth, eyes at the sides of the head, broad wings ending
 * in swept-back points, a thin whip of a tail. The wings beat: each is bent along its span, the
 * beat running out from the body to the tips and from the front to the back as a wave; the body
 * bobs against it, rocks and banks, the tail and fins follow. Dark on the back with pale shoulder
 * patches, white underneath with dark spots, gill slits and dark margins; lit from the surface
 * with wobbling caustics and fading into the water (the base color) with distance. The time loops:
 * at the loop length everything is as at 0. The center can be dragged.
 */
object MantaRay : Effect("mantaray", "Manta ray", "A manta ray beating its wings – turnable around all three axes; animate the time as a loop") {
    /** Color schemes: back, belly, shoulder patches. */
    private val schemes = listOf(
        "Reef manta" to intArrayOf(0x1C2329, 0xF1F0EA, 0xE4EAEE),
        "Oceanic" to intArrayOf(0x262B30, 0xF4F2EC, 0x9AA3AA),
        "Black manta" to intArrayOf(0x111417, 0x2C3035, 0x3C4248),
        "Ghost" to intArrayOf(0xB8C4CC, 0xF7F7F4, 0xFFFFFF),
        "Deep blue" to intArrayOf(0x163250, 0xDCEBF5, 0x6AB4E4),
        "Custom" to intArrayOf(0, 0, 0),
    )
    private val CUSTOM = schemes.size - 1

    // materials
    private const val BACK = 0
    private const val BELLY = 1
    private const val EYE = 2
    private const val FIN = 3

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: a seamless loop – the wings beat, the body rocks, tail and fins follow", decimals = 3),
        Param.Slider("loopLength", "Loop length", 2, 600, 40, " s", "How long one round takes: after it everything is as at the start, so the animation can repeat seamlessly", decimals = 1),
        Param.Slider("beats", "Wing beats", 1, 8, 2, "", "How often the wings beat in a loop"),
        Param.Slider("flap", "Flap", 0, 100, 55, " %", "How far the wings swing up and down"),
        Param.Slider("wave", "Wave", 0, 100, 50, " %", "How much the beat runs out along the wings as a wave – from the body to the tips, from the front to the back"),
        Param.Slider("sway", "Sway", 0, 100, 30, " %", "The body rocks, banks and turns a little over the loop"),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("size", "Size", 100, 20000, 4000, " %", "Half its wingspan, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("rotX", "Rotation X", -36000, 36000, 5500, "°", "Tips it towards you or away: 0° = seen edge-on from behind, 90° = from straight above", decimals = 2),
        Param.Slider("rotY", "Rotation Y", -36000, 36000, 2500, "°", "Turns where it heads", decimals = 2),
        Param.Slider("rotZ", "Rotation Z", -36000, 36000, 0, "°", "Turns it in the picture plane", decimals = 2),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("sweep", "Wing sweep", 0, 100, 50, " %", "How far back the pointed wingtips reach"),
        Param.Slider("thickness", "Body", 30, 250, 100, " %", "Thickness of the body and the wing roots"),
        Param.Slider("horns", "Cephalic fins", 0, 200, 100, " %", "The rolled-up fins either side of the mouth"),
        Param.Slider("tail", "Tail", 0, 300, 100, " %", "Length of the thin tail"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("scheme", "Colors", schemes.map { it.first }, 0, "Custom: the three colors below"),
        Param.Color("backColor", "Back", 0x1C2329, "Custom: the top side – and the spots on the belly"),
        Param.Color("bellyColor", "Belly", 0xF1F0EA, "Custom: the underside"),
        Param.Color("patchColor", "Shoulder patches", 0xE4EAEE, "Custom: the pale markings on the back"),
        Param.Slider("patches", "Shoulder patches", 0, 100, 80, " %", "The pale chevrons on its back"),
        Param.Slider("spots", "Belly spots", 0, 60, 18, "", "Dark spots on the white belly – Reroll for another pattern"),
        Param.Slider("caustics", "Caustics", 0, 200, 80, " %", "Wobbling light from the surface on whatever faces the sun"),
        Param.Slider("haze", "Water haze", 0, 100, 25, " %", "Fades it into the water – the base color – more where it is further away"),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "3 × 3"), 2),
        Param.Toggle("transparent", "Transparent background", false, "Only the manta, without the base color"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /**
     * The mesh in object space: half the wingspan is 1, x to the right wing, y up to the back, z
     * backwards (the head at −z). Per vertex: position, normal, the place on the body the markings
     * are painted by (across, forward, from the leading to the trailing edge – on the fins how much
     * the surface faces up) and its material.
     */
    private class Mesh(val pos: FloatArray, val nrm: FloatArray, val attr: FloatArray, val mat: IntArray, val tris: IntArray) {
        val vertices get() = pos.size / 3
        val triangles get() = tris.size / 3
    }

    private class Builder(val place: (Double, Double, Double, DoubleArray) -> Unit) {
        val pos = ArrayList<Float>(1 shl 15)
        val attr = ArrayList<Float>(1 shl 15)
        val mat = ArrayList<Int>(1 shl 14)
        val tris = ArrayList<Int>(1 shl 16)
        private val p = DoubleArray(3)

        /** A vertex at (x, y, forward) in the manta's own frame, placed by its sway; returns its index. */
        fun vertex(x: Double, y: Double, f: Double, u: Double, fo: Double, v: Double, m: Int): Int {
            place(x, y, f, p)
            pos += p[0].toFloat(); pos += p[1].toFloat(); pos += p[2].toFloat()
            attr += u.toFloat(); attr += fo.toFloat(); attr += v.toFloat()
            mat += m
            return mat.size - 1
        }
        fun tri(a: Int, b: Int, c: Int) { tris += a; tris += b; tris += c }

        /** With smooth normals: the face normals summed at their corners. */
        fun build(): Mesh {
            val p = pos.toFloatArray()
            val t = tris.toIntArray()
            val n = FloatArray(p.size)
            for (i in t.indices step 3) {
                val a = t[i] * 3; val b = t[i + 1] * 3; val c = t[i + 2] * 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
                for (o in intArrayOf(a, b, c)) { n[o] += fx; n[o + 1] += fy; n[o + 2] += fz }
            }
            for (o in n.indices step 3) {
                val l = sqrt(n[o] * n[o] + n[o + 1] * n[o + 1] + n[o + 2] * n[o + 2]).coerceAtLeast(1e-12f)
                n[o] /= l; n[o + 1] /= l; n[o + 2] /= l
            }
            return Mesh(p, n, attr.toFloatArray(), mat.toIntArray(), t)
        }

        /**
         * A tube through [points] (x, y, forward), [radius] at each, closed with a point at the end; its
         * attribute v says how much the surface faces up.
         */
        fun tube(points: List<DoubleArray>, radius: (Double) -> Double, m: Int, sides: Int = 12) {
            val n = points.size
            val rings = IntArray(n * sides)
            var last = DoubleArray(3)
            for (k in 0 until n) {
                val a = points[max(0, k - 1)]
                val b = points[min(n - 1, k + 1)]
                var tx = b[0] - a[0]; var ty = b[1] - a[1]; var tz = b[2] - a[2]
                val tl = max(1e-12, sqrt(tx * tx + ty * ty + tz * tz))
                tx /= tl; ty /= tl; tz /= tl
                last = doubleArrayOf(tx, ty, tz)
                // across: level, and up, both square to the direction
                var nx = -tz; var nz = tx
                var ny = 0.0
                if (abs(ty) > 0.95) { nx = 1.0; ny = 0.0; nz = 0.0 }
                val nl = max(1e-12, sqrt(nx * nx + ny * ny + nz * nz))
                nx /= nl; ny /= nl; nz /= nl
                val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
                val r = radius(k / (n - 1.0))
                val q = points[k]
                for (s in 0 until sides) {
                    val phi = 2 * PI * s / sides
                    val ox = cos(phi) * nx + sin(phi) * bx
                    val oy = cos(phi) * ny + sin(phi) * by
                    val oz = cos(phi) * nz + sin(phi) * bz
                    rings[k * sides + s] = vertex(q[0] + ox * r, q[1] + oy * r, q[2] + oz * r, q[0], q[2], oy, m)
                }
            }
            for (k in 0 until n - 1) for (s in 0 until sides) {
                val a = rings[k * sides + s]; val b = rings[k * sides + (s + 1) % sides]
                val c = rings[(k + 1) * sides + s]; val d = rings[(k + 1) * sides + (s + 1) % sides]
                tri(a, c, b); tri(b, c, d)
            }
            val e = points[n - 1]
            val r = radius(1.0)
            val tip = vertex(e[0] + last[0] * r, e[1] + last[1] * r, e[2] + last[2] * r, e[0], e[2], 0.0, m)
            for (s in 0 until sides) tri(rings[(n - 1) * sides + s], tip, rings[(n - 1) * sides + (s + 1) % sides])
        }

        /** A ball around (x, y, forward). */
        fun ball(x: Double, y: Double, f: Double, r: Double, m: Int) {
            val lat = 8
            val lon = 12
            val idx = IntArray((lat + 1) * lon)
            for (i in 0..lat) for (j in 0 until lon) {
                val th = PI * i / lat
                val ph = 2 * PI * j / lon
                val oy = cos(th)
                idx[i * lon + j] = vertex(x + sin(th) * cos(ph) * r, y + oy * r, f + sin(th) * sin(ph) * r, x, f, oy, m)
            }
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = idx[i * lon + j]; val b = idx[i * lon + (j + 1) % lon]
                val c = idx[(i + 1) * lon + j]; val d = idx[(i + 1) * lon + (j + 1) % lon]
                tri(a, c, b); tri(b, c, d)
            }
        }
    }

    private fun build(v: Values, t: Double): Mesh {
        val ta = 2 * PI * t
        // whole beats over the loop, so 100 % meets 0 %
        val beat = ta * v["beats"]
        val flap = v["flap"] / 100.0
        val wave = v["wave"] / 100.0
        val sway = v["sway"] / 100.0
        val sweep = v["sweep"] / 100.0
        val thick = v["thickness"] / 100.0
        val horns = v["horns"] / 100.0
        val tailLen = v["tail"] / 100.0

        // the body rises as the wings beat down; it rocks, banks and turns a little
        val bob = -0.035 * flap * sin(beat + 0.6)
        val pitch = sway * 0.1 * sin(ta + 1.1) + 0.03 * flap * cos(beat)
        val roll = sway * 0.22 * sin(ta + 0.4)
        val yaw = sway * 0.12 * sin(ta + 2.0)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        val cyw = cos(yaw); val syw = sin(yaw)
        val b = Builder { x, y, f, out ->
            // roll around the body's length, pitch around its width, then turn its heading
            val x1 = x * cr - y * sr
            val y1 = x * sr + y * cr
            val y2 = y1 * cp + f * sp
            val f2 = -y1 * sp + f * cp
            out[0] = x1 * cyw + f2 * syw
            out[1] = y2 + bob
            out[2] = -(-x1 * syw + f2 * cyw)
        }

        // the outline: the head's front straight between the fins, the leading edge sweeping back in a curve to the
        // pointed tip, the trailing edge hollow (sickle-shaped wings), the body's end in the middle
        val tipF = -0.04 - 0.52 * sweep
        val leadK = (0.36 - tipF) / 0.92.pow(1.35)
        fun lead(a: Double) = 0.36 - leadK * max(0.0, a - 0.08).pow(1.35)
        fun trail(a: Double) = min(-0.36 + (tipF + 0.36) * a * a + 0.9 * a * (1 - a).pow(1.4) - 0.06 * exp(-(a / 0.06).pow(2)), lead(a) - 0.002)
        // thick in the middle, thin wings; blunt in front, tapering behind
        fun thickAt(a: Double) = thick * (0.07 * exp(-(a / 0.15).pow(2)) + 0.022 * exp(-(a / 0.4).pow(2))) + 0.005
        fun profile(c: Double) = sin(PI * c).coerceAtLeast(0.0).pow(0.55) * (1.15 - 0.4 * c)
        // how steep a wing is at [a] out along its span and [c] back along its chord: the beat, later further out and
        // further back
        fun angle(a: Double, c: Double) = flap * 0.6 * smoothstep(0.05, 0.6, a) * sin(beat - wave * (2.4 * a + 1.0 * c) * 1.6)

        // ---- the wings and body: a top and a bottom sheet over the same outline, meeting at the edges ----
        val half = 72
        val nc = 32
        val cols = 2 * half + 1
        for ((m, side) in listOf(BACK to 1.0, BELLY to -0.5)) {
            val grid = IntArray(cols * (nc + 1))
            val bx = DoubleArray(half + 1)
            val by = DoubleArray(half + 1)
            val bt = DoubleArray(half + 1)
            for (j in 0..nc) {
                val c = (1 - cos(PI * j / nc)) / 2
                // the bent span line along this chord: integrated from the middle out, so the wings keep their length
                var x = 0.0
                var y = 0.0
                for (i in 0..half) {
                    if (i > 0) {
                        val th = angle((i - 0.5) / half, c)
                        x += cos(th) / half; y += sin(th) / half
                    }
                    bx[i] = x; by[i] = y; bt[i] = angle(i.toDouble() / half, c)
                }
                for (i in -half..half) {
                    val k = abs(i)
                    val a = k.toDouble() / half
                    val s = if (i < 0) -1.0 else 1.0
                    val le = lead(a)
                    val f = le + (trail(a) - le) * c
                    // the skin, off the bent span line along its normal
                    val d = thickAt(a) * profile(c) * side
                    grid[(i + half) * (nc + 1) + j] = b.vertex(s * (bx[k] - sin(bt[k]) * d), by[k] + cos(bt[k]) * d, f, s * a, f, c, m)
                }
            }
            for (i in 0 until cols - 1) for (j in 0 until nc) {
                val p00 = grid[i * (nc + 1) + j]; val p10 = grid[(i + 1) * (nc + 1) + j]
                val p01 = grid[i * (nc + 1) + j + 1]; val p11 = grid[(i + 1) * (nc + 1) + j + 1]
                b.tri(p00, p10, p11); b.tri(p00, p11, p01)
            }
        }

        // ---- the cephalic fins: rolled up, pointing forward and curling in and down, waving a little ----
        if (horns > 0) for (s in listOf(1.0, -1.0)) {
            val pts = (0..16).map { k ->
                val q = k / 16.0
                val curl = q.pow(1.6) * min(1.5, horns)
                doubleArrayOf(
                    s * (0.078 + 0.008 * q - 0.022 * curl),
                    0.004 - 0.03 * curl + 0.008 * q * sin(beat - 2.0 * q),
                    0.3 + 0.17 * horns * q,
                )
            }
            b.tube(pts, { q -> (0.021 * (1 - 0.55 * q)) * (0.55 + 0.45 * min(1.0, horns)) }, FIN)
        }
        // ---- the eyes, at the sides of the head ----
        for (s in listOf(1.0, -1.0)) b.ball(s * 0.098, 0.012, 0.3, 0.013, EYE)
        // ---- the tail: a thin whip trailing behind, swinging with the beat ----
        if (tailLen > 0) {
            val pts = (0..24).map { k ->
                val q = k / 24.0
                doubleArrayOf(0.03 * q * q * wave * sin(beat - 3.0 * q), 0.006 - 0.012 * q + 0.02 * flap * q * q * sin(beat - 2.5 * q - 0.8), -0.34 - 0.6 * tailLen * q)
            }
            b.tube(pts, { q -> 0.012 * (1 - q).pow(1.3) + 0.0012 }, FIN, 8)
        }
        return b.build()
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // where in the loop: 0 … 1, at the loop length exactly back at 0
        val t = (v["time"] / 100.0 / max(2.0, v["loopLength"].toDouble())) % 1.0
        val mesh = build(v, t)
        val transparent = v.bool("transparent")
        val colors = if (v["scheme"] == CUSTOM) intArrayOf(v["backColor"], v["bellyColor"], v["patchColor"]) else schemes[v["scheme"].coerceIn(0, CUSTOM)].second
        // linear light: the colors squared
        fun lin(c: Int) = doubleArrayOf((c shr 16 and 0xFF) / 255.0, (c shr 8 and 0xFF) / 255.0, (c and 0xFF) / 255.0).map { it * it }.toDoubleArray()
        val look = Look(
            back = lin(colors[0]), belly = lin(colors[1]), patch = lin(colors[2]),
            // the water: the base color under the manta
            water = lin(src.data[(h / 2).coerceIn(0, h - 1) * w + (w / 2).coerceIn(0, w - 1)]),
            patches = v["patches"] / 100.0, caustics = v["caustics"] / 100.0, haze = v["haze"] / 100.0, ta = 2 * PI * t,
            // the belly's spots, scattered over its middle, of different sizes – its own pattern for every seed
            spots = Random(seed).let { r ->
                Array(v["spots"]) { doubleArrayOf((r.nextDouble() * 2 - 1) * (0.04 + 0.3 * r.nextDouble()), -0.22 + 0.42 * r.nextDouble(), 0.007 + 0.022 * r.nextDouble().pow(2)) }
            },
        )

        var k = v["antialias"] + 1
        while (k > 1 && w.toLong() * h * k * k > 40_000_000L) k--
        val ww = w * k; val hh = h * k
        val size = max(1.0, min(w, h) * v["size"] / 10000.0) * k
        val eye = size * 5.0
        val cx = (w / 2.0 + v["centerX"] / 10.0) * k
        val cy = (h / 2.0 + v["centerY"] / 10.0) * k
        val ax = Math.toRadians(v["rotX"] / 100.0)
        val ay = Math.toRadians(v["rotY"] / 100.0)
        val az = Math.toRadians(v["rotZ"] / 100.0)
        val cX = cos(ax); val sX = sin(ax); val cY = cos(ay); val sY = sin(ay); val cZ = cos(az); val sZ = sin(az)
        fun turn(x: Double, y: Double, z: Double, out: DoubleArray, o: Int) {
            val x1 = x * cY + z * sY
            val z1 = -x * sY + z * cY
            val y2 = y * cX - z1 * sX
            val z2 = y * sX + z1 * cX
            out[o] = x1 * cZ - y2 * sZ
            out[o + 1] = x1 * sZ + y2 * cZ
            out[o + 2] = z2
        }
        // per vertex: turned position (pixels, y up, z towards us), turned normal, screen place, 1/depth
        val nv = mesh.vertices
        val cam = DoubleArray(nv * 3)
        val nrm = DoubleArray(nv * 3)
        val sx = DoubleArray(nv); val sy = DoubleArray(nv); val iz = DoubleArray(nv)
        for (i in 0 until nv) {
            turn(mesh.pos[i * 3].toDouble(), mesh.pos[i * 3 + 1].toDouble(), mesh.pos[i * 3 + 2].toDouble(), cam, i * 3)
            for (a in 0..2) cam[i * 3 + a] *= size
            turn(mesh.nrm[i * 3].toDouble(), mesh.nrm[i * 3 + 1].toDouble(), mesh.nrm[i * 3 + 2].toDouble(), nrm, i * 3)
            val d = max(1e-6, eye - cam[i * 3 + 2])
            iz[i] = 1 / d
            sx[i] = cx + cam[i * 3] * eye / d
            sy[i] = cy - cam[i * 3 + 1] * eye / d
        }

        val band = 8
        val bands = (h + band - 1) / band
        val lists = Array(bands) { ArrayList<Int>() }
        val tris = mesh.tris
        for (tr in 0 until mesh.triangles) {
            val a = tris[tr * 3]; val bb = tris[tr * 3 + 1]; val c = tris[tr * 3 + 2]
            val y0 = min(sy[a], min(sy[bb], sy[c])); val y1 = max(sy[a], max(sy[bb], sy[c]))
            val x0 = min(sx[a], min(sx[bb], sx[c])); val x1 = max(sx[a], max(sx[bb], sx[c]))
            if (y1 < 0 || y0 >= hh || x1 < 0 || x0 >= ww) continue
            val b0 = (max(0.0, y0) / k / band).toInt().coerceIn(0, bands - 1)
            val b1 = (min(hh - 1.0, y1) / k / band).toInt().coerceIn(0, bands - 1)
            for (bn in b0..b1) lists[bn] += tr
        }

        val out = if (transparent) Pixels(w, h) else src.copy()
        java.util.stream.IntStream.range(0, bands).parallel().forEach { bn ->
            val rowFrom = bn * band * k
            val rowTo = min(hh, (bn + 1) * band * k)
            val rows = rowTo - rowFrom
            if (rows <= 0) return@forEach
            val zb = FloatArray(ww * rows)
            val tri = IntArray(ww * rows) { -1 }
            val bary1 = FloatArray(ww * rows)
            val bary2 = FloatArray(ww * rows)
            for (tr in lists[bn]) {
                val i0 = tris[tr * 3]; val i1 = tris[tr * 3 + 1]; val i2 = tris[tr * 3 + 2]
                val area = (sx[i1] - sx[i0]) * (sy[i2] - sy[i0]) - (sx[i2] - sx[i0]) * (sy[i1] - sy[i0])
                if (abs(area) < 1e-9) continue
                val x0 = max(0, floor(min(sx[i0], min(sx[i1], sx[i2]))).toInt())
                val x1 = min(ww - 1, kotlin.math.ceil(max(sx[i0], max(sx[i1], sx[i2]))).toInt())
                val ya = max(rowFrom, floor(min(sy[i0], min(sy[i1], sy[i2]))).toInt())
                val yb = min(rowTo - 1, kotlin.math.ceil(max(sy[i0], max(sy[i1], sy[i2]))).toInt())
                for (y in ya..yb) {
                    val py = y + 0.5
                    for (x in x0..x1) {
                        val px = x + 0.5
                        val l0 = ((sx[i1] - px) * (sy[i2] - py) - (sx[i2] - px) * (sy[i1] - py)) / area
                        if (l0 < 0) continue
                        val l1 = ((sx[i2] - px) * (sy[i0] - py) - (sx[i0] - px) * (sy[i2] - py)) / area
                        if (l1 < 0) continue
                        val l2 = 1 - l0 - l1
                        if (l2 < 0) continue
                        val z = l0 * iz[i0] + l1 * iz[i1] + l2 * iz[i2]
                        val j = (y - rowFrom) * ww + x
                        if (z <= zb[j]) continue
                        zb[j] = z.toFloat()
                        tri[j] = tr
                        bary1[j] = (l1 * iz[i1] / z).toFloat()
                        bary2[j] = (l2 * iz[i2] / z).toFloat()
                    }
                }
            }
            val col = DoubleArray(3)
            val acc = DoubleArray(4)
            val outRows = (rows + k - 1) / k
            for (oy in 0 until outRows) {
                val y = bn * band + oy
                if (y >= h) break
                for (x in 0 until w) {
                    acc.fill(0.0)
                    for (dy in 0 until k) for (dx in 0 until k) {
                        val j = (oy * k + dy) * ww + x * k + dx
                        if (j >= ww * rows) continue
                        val tr = tri[j]
                        if (tr < 0) continue
                        shade(tr, bary1[j].toDouble(), bary2[j].toDouble(), mesh, cam, nrm, eye, size, look, col)
                        acc[0] += 1.0; acc[1] += col[0]; acc[2] += col[1]; acc[3] += col[2]
                    }
                    if (acc[0] <= 0) continue
                    val a = acc[0] / (k * k)
                    val i = y * w + x
                    val g = out.data[i]
                    fun mix(sh: Int, total: Double): Int {
                        val c = (sqrt((total / acc[0]).coerceIn(0.0, 1.0)) * 255)
                        return ((g shr sh and 0xFF) * (1 - a) + c * a + 0.5).toInt().coerceIn(0, 255)
                    }
                    out.data[i] = (max(g ushr 24, (a * 255 + 0.5).toInt()) shl 24) or (mix(16, acc[1]) shl 16) or (mix(8, acc[2]) shl 8) or mix(0, acc[3])
                }
            }
        }
        return out
    }

    /** What the shading needs besides the mesh; colors in linear light. */
    private class Look(
        val back: DoubleArray, val belly: DoubleArray, val patch: DoubleArray, val water: DoubleArray,
        val patches: Double, val caustics: Double, val haze: Double, val ta: Double, val spots: Array<DoubleArray>,
    )

    /** Smooth 3D value noise, −1 … 1. */
    private fun noise3(x: Double, y: Double, z: Double, seed: Int): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); val iz = floor(z).toInt()
        val fx = x - ix; val fy = y - iy; val fz = z - iz
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy); val sz = fz * fz * (3 - 2 * fz)
        fun h(i: Int, j: Int, k: Int): Double {
            var n = i * 374761393 + j * 668265263 + k * 1274126177 + seed * 1442695041
            n = (n xor (n ushr 13)) * 1274126177
            n = n xor (n ushr 16)
            return (n and 0xFFFF) / 32767.5 - 1
        }
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        return l(
            l(l(h(ix, iy, iz), h(ix + 1, iy, iz), sx), l(h(ix, iy + 1, iz), h(ix + 1, iy + 1, iz), sx), sy),
            l(l(h(ix, iy, iz + 1), h(ix + 1, iy, iz + 1), sx), l(h(ix, iy + 1, iz + 1), h(ix + 1, iy + 1, iz + 1), sx), sy),
            sz,
        )
    }

    private fun shade(tr: Int, b1: Double, b2: Double, mesh: Mesh, cam: DoubleArray, nrm: DoubleArray, eye: Double, size: Double, look: Look, out: DoubleArray) {
        val b0 = 1 - b1 - b2
        val ia = mesh.tris[tr * 3]; val ib = mesh.tris[tr * 3 + 1]; val ic = mesh.tris[tr * 3 + 2]
        val a = ia * 3; val b = ib * 3; val c = ic * 3
        val px = b0 * cam[a] + b1 * cam[b] + b2 * cam[c]
        val py = b0 * cam[a + 1] + b1 * cam[b + 1] + b2 * cam[c + 1]
        val pz = b0 * cam[a + 2] + b1 * cam[b + 2] + b2 * cam[c + 2]
        var nx = b0 * nrm[a] + b1 * nrm[b] + b2 * nrm[c]
        var ny = b0 * nrm[a + 1] + b1 * nrm[b + 1] + b2 * nrm[c + 1]
        var nz = b0 * nrm[a + 2] + b1 * nrm[b + 2] + b2 * nrm[c + 2]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
        nx /= nl; ny /= nl; nz /= nl
        var vx = -px; var vy = -py; var vz = eye - pz
        val vl = sqrt(vx * vx + vy * vy + vz * vz)
        vx /= vl; vy /= vl; vz /= vl
        var ndv = nx * vx + ny * vy + nz * vz
        // lit from both sides: a surface seen from behind (a thin wing's edge) faces us
        if (ndv < 0) { nx = -nx; ny = -ny; nz = -nz; ndv = -ndv }
        if (ndv < 0.02) {
            val f = 0.02 - ndv
            nx += vx * f; ny += vy * f; nz += vz * f
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l; ny /= l; nz /= l
            ndv = 0.02
        }
        val u = b0 * mesh.attr[a] + b1 * mesh.attr[b] + b2 * mesh.attr[c]
        val f = b0 * mesh.attr[a + 1] + b1 * mesh.attr[b + 1] + b2 * mesh.attr[c + 1]
        val cv = b0 * mesh.attr[a + 2] + b1 * mesh.attr[b + 2] + b2 * mesh.attr[c + 2]
        val au = abs(u)

        // ---- the skin's color ----
        val alb = DoubleArray(3)
        var gloss = 1.0
        when (mesh.mat[ia]) {
            BACK -> {
                // pale wedges on the shoulders, running back and out from the sides of the head and tapering, with ragged
                // edges; the dark middle and leading edges stay
                val along = ((au - 0.13) / 0.4).coerceIn(0.0, 1.0)
                val center = 0.17 - 0.2 * along
                val halfWidth = 0.1 * (1 - along).pow(0.8) + 0.008
                val ragged = noise3(u * 9, f * 9, 1.5, 7) * 0.025 + noise3(u * 31, f * 31, 4.5, 8) * 0.008
                val patch = look.patches * (1 - smoothstep(halfWidth * 0.6, halfWidth, abs(f - center) + ragged)) * smoothstep(0.11, 0.16, au) *
                    (1 - smoothstep(0.48, 0.56, au))
                val mottle = 1 + 0.18 * noise3(u * 14, f * 14, 0.5, 5)
                for (i in 0..2) alb[i] = (look.back[i] + (look.patch[i] - look.back[i]) * patch) * mottle
            }
            BELLY -> {
                // dark margins along the wings and their trailing edges
                val margin = (0.85 * smoothstep(0.55, 1.0, au) + 0.45 * smoothstep(0.8, 1.0, cv) * smoothstep(0.15, 0.5, au)).coerceIn(0.0, 0.9)
                var spot = 0.0
                for (s in look.spots) {
                    val d = hypot(u - s[0], f - s[1]) / s[2]
                    if (d < 1) spot = max(spot, 1 - smoothstep(0.65, 1.0, d))
                }
                // five gill slits either side, curving back
                var gill = 0.0
                if (au in 0.05..0.115) for (g in 0 until 5) {
                    val line = 0.235 - g * 0.034 - 0.25 * (au - 0.05) - 60 * (au - 0.0825).pow(2)
                    gill = max(gill, (1 - smoothstep(0.0012, 0.0035, abs(f - line))) * smoothstep(0.05, 0.06, au) * (1 - smoothstep(0.105, 0.115, au)))
                }
                val dark = max(margin, max(spot * 0.9, gill * 0.6))
                for (i in 0..2) alb[i] = look.belly[i] + (look.back[i] - look.belly[i]) * dark
            }
            EYE -> { for (i in 0..2) alb[i] = 0.004; gloss = 12.0 }
            else -> {
                // fins and tail: dark on top, pale underneath
                val top = smoothstep(-0.5, 0.4, cv)
                for (i in 0..2) alb[i] = look.belly[i] * 0.7 + (look.back[i] - look.belly[i] * 0.7) * top
            }
        }
        // the wide mouth between the fins: dark
        if (mesh.mat[ia] <= BELLY) {
            val mouth = (1 - smoothstep(0.06, 0.08, au)) * (1 - smoothstep(0.03, 0.07, cv))
            for (i in 0..2) alb[i] = alb[i] + (0.003 - alb[i]) * mouth
        }

        // ---- the light: the sun from above and behind the viewer, through the water ----
        val lx = -0.33; val ly = 0.7; val lz = 0.63
        val ndl = nx * lx + ny * ly + nz * lz
        val diffuse = max(0.0, (ndl + 0.25) / 1.25)
        val hemi = 0.5 + 0.5 * ny
        var hx = lx + vx; var hy = ly + vy; var hz = lz + vz
        val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-12)
        hx /= hl; hy /= hl; hz /= hl
        // a faint wet sheen on the skin, a sharp glint in the eyes
        val spec = max(0.0, nx * hx + ny * hy + nz * hz).pow(if (gloss > 1) 80 else 20) * 0.05 * gloss
        // caustics: a wobbling net of light on what faces the sun, looping with the time
        var caustic = 0.0
        if (look.caustics > 0 && ndl > 0) {
            val qx = px / size; val qz = pz / size
            val lc = cos(look.ta) * 0.7; val ls = sin(look.ta) * 0.7
            val n1 = (1 - abs(noise3(qx * 5 + lc, qz * 5 + ls, py / size * 2, 3))).pow(8)
            val n2 = (1 - abs(noise3(qx * 9 - ls, qz * 9 + lc, py / size * 2 + 4, 9))).pow(10)
            caustic = look.caustics * ndl * (n1 + 0.6 * n2) * 1.6
        }
        val fres = (1 - ndv).pow(4)
        val sun = doubleArrayOf(1.0, 0.97, 0.9)
        val sky = doubleArrayOf(0.42, 0.62, 0.78)
        // nearer is clearer: fades into the water further away
        val fog = (look.haze * (0.15 + 0.7 * smoothstep(-1.2, 1.2, -pz / size))).coerceIn(0.0, 1.0)
        for (i in 0..2) {
            val lit = alb[i] * (sun[i] * (diffuse * 1.25 + caustic) + sky[i] * (0.22 + 0.3 * hemi)) + spec * sun[i] + fres * 0.25 * (sky[i] * 0.5 + look.water[i])
            val c = 1 - exp(-lit * 1.4)
            out[i] = c + (look.water[i] - c) * fog
        }
    }
}
