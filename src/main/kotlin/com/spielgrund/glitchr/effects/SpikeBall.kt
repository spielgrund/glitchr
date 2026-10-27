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
 * A ball of liquid-metal spikes, like a sea urchin cast in chrome: a lumpy core with long and
 * short spikes shooting out in all directions, bent, with flared, melted bases. Built as a mesh,
 * drawn with a z-buffer in perspective and shaded as polished metal mirroring a studio (softboxes,
 * a bright horizon, a dark floor), tinted by the metal color and shifting to a second color at
 * grazing angles. A hot core glows out between the spikes. The time loops: at 100 % everything is
 * as at 0 %. The center can be dragged.
 */
object SpikeBall : Effect("spikeball", "Spike ball", "A ball of chrome spikes like a sea urchin of liquid metal, with a glowing core – turnable; animate the time as a loop") {
    /** Color schemes: metal, sheen at grazing angles, core glow. */
    private val schemes = listOf(
        "Violet" to intArrayOf(0x9A3CFF, 0x3E62FF, 0xFF9A2E),
        "Chrome" to intArrayOf(0xD9DCE4, 0x8FA6FF, 0xFFB45A),
        "Gold" to intArrayOf(0xFFC04A, 0xFF6A2A, 0xFFF2C0),
        "Blood" to intArrayOf(0xC8102E, 0xFF3A8A, 0xFFB040),
        "Toxic" to intArrayOf(0x5CFF3A, 0x00D8C0, 0xF6FF6A),
        "Ice" to intArrayOf(0x8FE2FF, 0xC4A0FF, 0xEAFFFF),
        "Custom" to intArrayOf(0, 0, 0),
    )
    private val CUSTOM = schemes.size - 1

    override val params = listOf(
        Param.Slider("time", "Time", 0, 10000, 0, " %", "Animate it from 0 to 100 %: a seamless loop – the spikes sway and melt, the ball turns as set", decimals = 2),
        Param.Slider("spin", "Spin", -5, 5, 1, " turns", "How often the ball turns around once while the time runs from 0 to 100 %"),
        Param.Slider("sway", "Sway", 0, 100, 30, " %", "How much the spikes swing and wobble over the time"),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("size", "Size", 100, 20000, 4200, " %", "Radius to the spike tips, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("rotX", "Rotation X", -36000, 36000, 1500, "°", "Tips the ball forwards or back", decimals = 2),
        Param.Slider("rotY", "Rotation Y", -36000, 36000, 0, "°", "Turns it sideways", decimals = 2),
        Param.Slider("rotZ", "Rotation Z", -36000, 36000, 0, "°", "Turns it in the picture plane", decimals = 2),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("spikes", "Spikes", 10, 600, 260, "", "How many long spikes – Reroll for other directions"),
        Param.Slider("small", "Small spikes", 0, 200, 120, " %", "Short spikes in between, as many as the long ones at 100 %"),
        Param.Slider("length", "Length", 10, 100, 85, " %", "Of the long spikes, from the core to the tips"),
        Param.Slider("lengthVariation", "Length variation", 0, 100, 55, " %", "How different the spikes are in length"),
        Param.Slider("thickness", "Thickness", 10, 300, 130, " %"),
        Param.Slider("curl", "Bend", 0, 100, 30, " %", "How much the spikes curve"),
        Param.Slider("melt", "Melt", 0, 100, 50, " %", "Lumpy, molten metal: dents along the spikes, flowing bases, a knobbly core"),
        Param.Slider("core", "Core", 5, 80, 30, " %", "Size of the ball the spikes grow from"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("scheme", "Colors", schemes.map { it.first }, 0, "Custom: the three colors below"),
        Param.Color("metal", "Metal", 0x9A3CFF, "Custom: the color of the metal"),
        Param.Color("sheen", "Sheen", 0x3E62FF, "Custom: what it turns to at grazing angles"),
        Param.Color("glowColor", "Core glow", 0xFF9A2E, "Custom: the hot light inside"),
        Param.Slider("iridescence", "Iridescence", 0, 100, 45, " %", "How strongly the metal turns to the sheen color towards its edges"),
        Param.Slider("reflection", "Reflection", 0, 200, 100, " %", "Brightness of the mirrored studio lights"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %", "Of the core: between the spikes and on their bases"),
        Param.Slider("depth", "Depth", 0, 100, 60, " %", "How dark the spikes get towards the core"),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "3 × 3"), 2),
        Param.Toggle("transparent", "Transparent background", false, "Only the ball and its glow, without the base color"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /** The mesh in object space: radius 1 to the longest tips. Per vertex: position, normal and how deep it lies (0 core … 1 tip). */
    private class Mesh(val pos: FloatArray, val nrm: FloatArray, val depth: FloatArray, val tris: IntArray) {
        val vertices get() = pos.size / 3
        val triangles get() = tris.size / 3
    }

    private class Builder {
        val pos = ArrayList<Float>(1 shl 16)
        val depth = ArrayList<Float>(1 shl 15)
        val tris = ArrayList<Int>(1 shl 17)
        fun vertex(x: Double, y: Double, z: Double, d: Double): Int {
            pos += x.toFloat(); pos += y.toFloat(); pos += z.toFloat(); depth += d.toFloat()
            return depth.size - 1
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
            return Mesh(p, n, depth.toFloatArray(), t)
        }
    }

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

    private fun build(v: Values, seed: Long, t: Double): Mesh {
        val random = Random(seed)
        val ns = random.nextInt()
        val b = Builder()
        val core = v["core"] / 100.0 * 0.6
        val longest = (1 - core) * v["length"] / 100.0
        val variation = v["lengthVariation"] / 100.0
        val thick = v["thickness"] / 100.0
        val curl = v["curl"] / 100.0
        val melt = v["melt"] / 100.0
        val sway = v["sway"] / 100.0
        // the time on a circle through the noise, so 100 % meets 0 %
        val ta = 2 * PI * t
        val tx = cos(ta) * 0.6; val ty = sin(ta) * 0.6

        // the core: a lumpy sphere
        val cols = 72; val rows = 36
        val first = b.depth.size
        for (r in 0..rows) {
            val th = PI * r / rows
            for (c in 0 until cols) {
                val ph = 2 * PI * c / cols
                val x = sin(th) * cos(ph); val y = cos(th); val z = sin(th) * sin(ph)
                val bump = 1 + melt * 0.22 * (noise3(x * 3 + tx, y * 3 + ty, z * 3, ns) + 0.5 * noise3(x * 7, y * 7 + tx, z * 7 + ty, ns + 1))
                b.vertex(x * core * bump, y * core * bump, z * core * bump, 0.0)
            }
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = first + r * cols + c; val a2 = first + r * cols + (c + 1) % cols
            b.tri(a, a + cols, a2 + cols); b.tri(a, a2 + cols, a2)
        }

        // the spikes: long ones evenly around (a Fibonacci sphere, jittered), short ones at random
        val long = v["spikes"]
        val short = long * v["small"] / 100
        val golden = PI * (3 - sqrt(5.0))
        val turn = random.nextDouble() * 2 * PI
        for (i in 0 until long + short) {
            val dx: Double; val dy: Double; val dz: Double
            if (i < long) {
                val y = 1 - 2 * (i + 0.5) / long
                val rr = sqrt(1 - y * y)
                val ph = turn + golden * i
                val j = 0.55 / sqrt(long.toDouble())
                val jx = rr * cos(ph) + (random.nextDouble() - 0.5) * j
                val jy = y + (random.nextDouble() - 0.5) * j
                val jz = rr * sin(ph) + (random.nextDouble() - 0.5) * j
                val l = sqrt(jx * jx + jy * jy + jz * jz)
                dx = jx / l; dy = jy / l; dz = jz / l
            } else {
                val y = random.nextDouble() * 2 - 1
                val ph = random.nextDouble() * 2 * PI
                val rr = sqrt(1 - y * y)
                dx = rr * cos(ph); dy = y; dz = rr * sin(ph)
            }
            val len = if (i < long) longest * (1 - variation * random.nextDouble().pow(0.8))
            else longest * (0.12 + 0.3 * random.nextDouble())
            val base = thick * (0.022 + 0.05 * sqrt(len / max(1e-6, longest))) * (0.75 + 0.5 * random.nextDouble())
            spike(b, dx, dy, dz, core * 0.82, len, base, curl * (0.3 + 0.7 * random.nextDouble()), random.nextDouble() * 2 * PI,
                melt, sway, ta, random.nextDouble() * 2 * PI, ns, if (i < long) 14 else 6, if (i < long) 9 else 7)
        }
        return b.build()
    }

    /** One spike from [r0] along (dx, dy, dz), bent towards [bendAngle] and swaying with the time angle [ta]. */
    private fun spike(
        b: Builder, dx: Double, dy: Double, dz: Double, r0: Double, len: Double, base: Double, bend: Double, bendAngle: Double,
        melt: Double, sway: Double, ta: Double, phase: Double, ns: Int, rings: Int, sides: Int,
    ) {
        // a frame across the spike
        var ux: Double; var uy: Double; var uz: Double
        if (abs(dy) < 0.9) { ux = dz; uy = 0.0; uz = -dx } else { ux = 0.0; uy = -dz; uz = dy }
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val vx = dy * uz - dz * uy; val vy = dz * ux - dx * uz; val vz = dx * uy - dy * ux
        val bx = cos(bendAngle); val by = sin(bendAngle)
        val swing = sway * 0.18 * len
        val start = b.depth.size
        val total = r0 + len
        fun center(s: Double, out: DoubleArray) {
            val along = r0 + s * len
            val off = bend * 0.45 * len * s * s
            // the sway: a swing growing towards the tip, as a wave running along it
            val sw = swing * s * s * sin(ta + phase - s * 2.2)
            val sw2 = swing * 0.6 * s * s * cos(ta + phase * 1.7 - s * 1.6)
            out[0] = dx * along + ux * (bx * off + sw) + vx * (by * off + sw2)
            out[1] = dy * along + uy * (bx * off + sw) + vy * (by * off + sw2)
            out[2] = dz * along + uz * (bx * off + sw) + vz * (by * off + sw2)
        }
        val c = DoubleArray(3)
        val tc = cos(ta) * 0.5; val ts = sin(ta) * 0.5
        for (j in 0 until rings) {
            // denser towards the tip, where it bends most
            val s = 1 - (1 - j.toDouble() / rings).pow(1.25)
            center(s, c)
            // tapering to a point, flaring where it meets the core
            var radius = base * (1 - s).pow(1.1) * (1 + 1.6 * (1 - s).pow(10))
            for (k in 0 until sides) {
                val a = 2 * PI * k / sides
                val ox = ux * cos(a) + vx * sin(a); val oy = uy * cos(a) + vy * sin(a); val oz = uz * cos(a) + vz * sin(a)
                val px = c[0] + ox * radius; val py = c[1] + oy * radius; val pz = c[2] + oz * radius
                val dent = 1 + melt * 0.55 * noise3(px * 9 + tc, py * 9 + ts, pz * 9, ns + 7)
                val r = radius * dent
                b.vertex(c[0] + ox * r, c[1] + oy * r, c[2] + oz * r, (r0 + s * len) / total)
            }
        }
        center(1.0, c)
        val tip = b.vertex(c[0], c[1], c[2], 1.0)
        for (j in 0 until rings - 1) for (k in 0 until sides) {
            val a = start + j * sides + k; val a2 = start + j * sides + (k + 1) % sides
            b.tri(a, a2, a2 + sides); b.tri(a, a2 + sides, a + sides)
        }
        val last = start + (rings - 1) * sides
        for (k in 0 until sides) b.tri(last + k, last + (k + 1) % sides, tip)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val t = v["time"] / 10000.0
        val mesh = build(v, seed, t)
        val transparent = v.bool("transparent")
        val colors = schemes[v["scheme"]].second.let { if (v["scheme"] == CUSTOM) intArrayOf(v["metal"], v["sheen"], v["glowColor"]) else it }
        fun rgb(c: Int) = doubleArrayOf((c shr 16 and 0xFF) / 255.0, (c shr 8 and 0xFF) / 255.0, (c and 0xFF) / 255.0)
        // linear light: the colors squared
        val metal = rgb(colors[0]).map { it * it }.toDoubleArray()
        val sheen = rgb(colors[1]).map { it * it }.toDoubleArray()
        val glowC = rgb(colors[2]).map { it * it }.toDoubleArray()
        val glow = v["glow"] / 100.0
        val iris = v["iridescence"] / 100.0
        val refl = v["reflection"] / 100.0
        val deep = v["depth"] / 100.0
        val core = v["core"] / 100.0 * 0.6
        val flicker = sin(2 * PI * t) * 0.8

        var k = v["antialias"] + 1
        while (k > 1 && w.toLong() * h * k * k > 40_000_000L) k--
        val ww = w * k; val hh = h * k
        val size = max(1.0, min(w, h) * v["size"] / 10000.0) * k
        val eye = size * 5.0
        val cx = (w / 2.0 + v["centerX"] / 10.0) * k
        val cy = (h / 2.0 + v["centerY"] / 10.0) * k
        val ax = Math.toRadians(v["rotX"] / 100.0)
        val ay = Math.toRadians(v["rotY"] / 100.0 + 360.0 * v["spin"] * t)
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
            val d = eye - cam[i * 3 + 2]
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

        // the ground with the core's light around it
        val out = if (transparent) Pixels(w, h) else src.copy()
        val glowR = size / k * (core + 0.12)
        val ccx = cx / k; val ccy = cy / k
        com.spielgrund.glitchr.image.parallelRows(h) { y ->
            for (x in 0 until w) {
                val d2 = ((x + 0.5 - ccx) * (x + 0.5 - ccx) + (y + 0.5 - ccy) * (y + 0.5 - ccy)) / (glowR * glowR)
                val g = glow * (1.6 * exp(-d2 * 2.0) + 0.06 * exp(-d2 * 0.12))
                if (g < 0.002) continue
                val i = y * w + x
                out.data[i] = addLight(out.data[i], glowC, g)
            }
        }

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
                        shade(tr, bary1[j].toDouble(), bary2[j].toDouble(), mesh, cam, nrm, eye, size, metal, sheen, glowC, glow, iris, refl, deep, core, flicker, col)
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

    /** [c] with [light] (linear) times [amount] screened over it in linear light; on transparency it becomes as opaque as the light. */
    private fun addLight(c: Int, light: DoubleArray, amount: Double): Int {
        val a = (c ushr 24) / 255.0
        var na = a
        val res = DoubleArray(3)
        for (i in 0..2) {
            val o = (c shr (16 - 8 * i) and 0xFF) / 255.0
            val add = 1 - exp(-light[i] * amount)
            val base = o * o * a
            res[i] = base + (1 - base) * add
            na = max(na, add)
        }
        if (na <= 0) return c
        fun ch(i: Int) = (sqrt((res[i] / na).coerceIn(0.0, 1.0)) * 255 + 0.5).toInt()
        return ((na * 255 + 0.5).toInt().coerceIn(0, 255) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    /** The studio mirrored in direction (x, y, z): softboxes, a bright horizon, a dark floor; brightness in linear light. */
    private fun studio(x: Double, y: Double, z: Double): Double {
        fun box(lx: Double, ly: Double, lz: Double, from: Double, to: Double): Double {
            val l = sqrt(lx * lx + ly * ly + lz * lz)
            val d = (x * lx + y * ly + z * lz) / l
            return smoothstep(from, to, d)
        }
        // a soft sky over a dark floor, split by a bright horizon with a dark band beneath, the classic chrome look
        var e = if (y > 0) 0.03 + 0.28 * y.pow(0.7) else 0.008
        e += 1.1 * exp(-(y - 0.03) * (y - 0.03) * 500) - 0.02 * exp(-(y + 0.06) * (y + 0.06) * 300)
        e += 4.0 * box(-0.55, 0.65, 0.5, 0.9, 0.96)
        e += 2.2 * box(0.85, 0.15, 0.35, 0.95, 0.98)
        e += 1.4 * box(-0.3, 0.2, -1.0, 0.85, 0.95)
        e += 0.5 * box(0.1, -0.1, 1.0, 0.975, 0.995)
        e += 2.5 * box(0.4, 0.9, -0.2, 0.97, 0.99)
        e += 1.8 * box(-0.9, -0.1, 0.3, 0.975, 0.992)
        return e
    }

    private fun shade(
        tr: Int, b1: Double, b2: Double, mesh: Mesh, cam: DoubleArray, nrm: DoubleArray, eye: Double, size: Double,
        metal: DoubleArray, sheen: DoubleArray, glowC: DoubleArray, glow: Double, iris: Double, refl: Double, deep: Double, core: Double,
        flicker: Double, out: DoubleArray,
    ) {
        val b0 = 1 - b1 - b2
        val a = mesh.tris[tr * 3] * 3; val b = mesh.tris[tr * 3 + 1] * 3; val c = mesh.tris[tr * 3 + 2] * 3
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
        // a normal turned away along a silhouette: bent back towards the eye
        if (ndv < 0.02) {
            val f = 0.02 - ndv
            nx += vx * f; ny += vy * f; nz += vz * f
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l; ny /= l; nz /= l
            ndv = 0.02
        }
        val rx = 2 * ndv * nx - vx; val ry = 2 * ndv * ny - vy; val rz = 2 * ndv * nz - vz
        val env = studio(rx, ry, rz) * refl
        // deep down between the spikes: dark, and lit by the core
        val vd = b0 * mesh.depth[a / 3] + b1 * mesh.depth[b / 3] + b2 * mesh.depth[c / 3]
        val r = sqrt(px * px + py * py + pz * pz) / size
        val open = smoothstep(core * 0.9, core + 0.55 * (1 - core), r)
        val occ = 1 - deep * (1 - open) * 0.97
        // patches of heat flickering over the bases, deep inside only
        val patch = 0.5 + 0.5 * noise3(px / size * 6 + flicker, py / size * 6, pz / size * 6 - flicker, 11)
        val heat = glow * 3.0 * exp(-max(0.0, r - core * 0.85) / (0.1 + core * 0.5)) * (0.2 + 0.8 * (1 - vd) + 2.5 * (1 - vd).pow(8)) * (0.35 + 1.3 * patch * patch)
        // the metal's color, turning to the sheen towards grazing angles; a little white at the very edge
        val graze = (1 - ndv).pow(2.2)
        val fres = (1 - ndv).pow(5)
        val m = iris * graze
        for (i in 0..2) {
            val tint = metal[i] * (1 - m) + sheen[i] * m
            val f = tint + (1 - tint) * fres * 0.3
            out[i] = 1 - exp(-(env * f * occ * (1 - 0.6 * min(1.0, heat)) * 2.2 + heat * glowC[i] * (0.55 + 0.45 * graze)))
        }
    }
}
