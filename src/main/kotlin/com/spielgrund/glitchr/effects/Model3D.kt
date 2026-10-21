package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangle mesh, centred and scaled to radius 1: for every triangle its three corners'
 * positions, normals (smooth, or as the file gives them) and texture coordinates (or none),
 * and which of its edges are edges of the original polygon (for the wireframe).
 */
class Mesh(
    val pos: FloatArray,
    val nrm: FloatArray,
    val uv: FloatArray?,
    val edges: ByteArray,
    /** Faces as in the file, before splitting them into triangles. */
    val faces: Int,
    /** Own colors per triangle (0xRRGGBB) – the built-in duck; null for one color. */
    val colors: IntArray? = null,
) {
    val triangles get() = edges.size

    /** Collects vertices, texture coordinates and polygons, then makes the mesh. */
    class Builder {
        private val v = ArrayList<FloatArray>()
        private val vt = ArrayList<FloatArray>()
        private val vn = ArrayList<FloatArray>()
        // per triangle corner: position, texture and normal index (-1 none)
        private val corners = ArrayList<IntArray>()
        private val edgeFlags = ArrayList<Byte>()
        private val triColors = ArrayList<Int>()
        var faces = 0
            private set

        /** The color of the faces added from now on (0xRRGGBB); -1 none. */
        var color = -1

        fun vertex(x: Double, y: Double, z: Double) = v.size.also { v += floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat()) }
        fun texture(u: Double, w: Double) = vt.size.also { vt += floatArrayOf(u.toFloat(), w.toFloat()) }
        fun normal(x: Double, y: Double, z: Double) = vn.size.also { vn += floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat()) }

        /** A polygon of corners (position, texture, normal indices); split into a fan of triangles. */
        fun face(polygon: List<IntArray>) {
            if (polygon.size < 3) return
            faces++
            for (i in 1 until polygon.size - 1) {
                corners += polygon[0]; corners += polygon[i]; corners += polygon[i + 1]
                // edges 0-1, 1-2, 2-0 – only those that are the polygon's own
                var flags = 2
                if (i == 1) flags = flags or 1
                if (i == polygon.size - 2) flags = flags or 4
                edgeFlags += flags.toByte()
                triColors += color
            }
        }

        fun build(): Mesh? {
            val tris = edgeFlags.size
            if (tris == 0 || v.isEmpty()) return null
            // centred on its box, scaled to radius 1
            val lo = FloatArray(3) { Float.MAX_VALUE }
            val hi = FloatArray(3) { -Float.MAX_VALUE }
            for (p in v) for (a in 0..2) { lo[a] = min(lo[a], p[a]); hi[a] = max(hi[a], p[a]) }
            val mid = FloatArray(3) { (lo[it] + hi[it]) / 2 }
            var radius = 1e-9f
            for (p in v) radius = max(radius, sqrt((0..2).sumOf { ((p[it] - mid[it]) * (p[it] - mid[it])).toDouble() }).toFloat())
            val pos = FloatArray(tris * 9)
            for ((i, c) in corners.withIndex()) for (a in 0..2) pos[i * 3 + a] = (v[c[0]][a] - mid[a]) / radius
            // normals: the file's, or smooth ones – the faces around each position, by area, but only those
            // turned less than 60° from the corner's own face, so sharp edges stay sharp (like Auto Smooth)
            val nrm = FloatArray(tris * 9)
            val face = Array(tris) { t -> faceNormal(pos, t).let { (x, y, z) -> doubleArrayOf(x, y, z) } }
            val unit = Array(tris) { t -> face[t].let { f -> val l = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]).coerceAtLeast(1e-12); DoubleArray(3) { f[it] / l } } }
            val around = Array(v.size) { ArrayList<Int>() }
            for (t in 0 until tris) for (k in 0..2) around[corners[t * 3 + k][0]] += t
            val limit = cos(Math.toRadians(60.0))
            for ((i, c) in corners.withIndex()) {
                val own = unit[i / 3]
                val n = if (c[2] >= 0 && c[2] < vn.size) vn[c[2]].map { it.toDouble() }.toDoubleArray() else DoubleArray(3).also { sum ->
                    for (t in around[c[0]]) {
                        if (unit[t][0] * own[0] + unit[t][1] * own[1] + unit[t][2] * own[2] < limit) continue
                        for (a in 0..2) sum[a] += face[t][a]
                    }
                }
                val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-12)
                for (a in 0..2) nrm[i * 3 + a] = (n[a] / len).toFloat()
            }
            val hasUv = corners.all { it[1] >= 0 && it[1] < vt.size }
            val uv = if (!hasUv) null else FloatArray(tris * 6).also { u -> for ((i, c) in corners.withIndex()) { u[i * 2] = vt[c[1]][0]; u[i * 2 + 1] = vt[c[1]][1] } }
            val colors = if (triColors.none { it >= 0 }) null else IntArray(tris) { triColors[it].let { c -> if (c < 0) 0x999999 else c } }
            return Mesh(pos, nrm, uv, edgeFlags.toByteArray(), faces, colors)
        }

        /** The unnormalized face normal of triangle [t] (its length is twice the area). */
        private fun faceNormal(pos: FloatArray, t: Int): Triple<Double, Double, Double> {
            val o = t * 9
            val ax = (pos[o + 3] - pos[o]).toDouble(); val ay = (pos[o + 4] - pos[o + 1]).toDouble(); val az = (pos[o + 5] - pos[o + 2]).toDouble()
            val bx = (pos[o + 6] - pos[o]).toDouble(); val by = (pos[o + 7] - pos[o + 1]).toDouble(); val bz = (pos[o + 8] - pos[o + 2]).toDouble()
            return Triple(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
        }
    }

    companion object {
        /** Most faces a pasted model may have. */
        const val MAX_FACES = 1024

        /** A model with more faces than allowed. */
        class TooBig(val faces: Int) : Exception("$faces faces")

        /**
         * A Wavefront OBJ: positions, texture coordinates, normals and faces (also with negative
         * indices); polygons become fans of triangles. Null if it holds no faces; [TooBig] above
         * [MAX_FACES] faces.
         */
        fun parseObj(text: String): Mesh? {
            val b = Builder()
            var v = 0; var vt = 0; var vn = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#') continue
                val parts = line.split(Regex("\\s+"))
                fun num(i: Int) = parts.getOrNull(i)?.toDoubleOrNull() ?: 0.0
                when (parts[0]) {
                    "v" -> { b.vertex(num(1), num(2), num(3)); v++ }
                    "vt" -> { b.texture(num(1), num(2)); vt++ }
                    "vn" -> { b.normal(num(1), num(2), num(3)); vn++ }
                    "f" -> {
                        fun index(s: String?, count: Int): Int {
                            val i = s?.toIntOrNull() ?: return -1
                            return if (i < 0) count + i else i - 1
                        }
                        val polygon = parts.drop(1).map { c ->
                            val p = c.split('/')
                            intArrayOf(index(p.getOrNull(0), v), index(p.getOrNull(1)?.takeIf { it.isNotEmpty() }, vt), index(p.getOrNull(2), vn))
                        }.filter { it[0] in 0 until v }
                        b.face(polygon)
                        if (b.faces > MAX_FACES) throw TooBig(text.lineSequence().count { it.trimStart().startsWith("f ") })
                    }
                }
            }
            return b.build()
        }

        /** Built-in shapes for when nothing is pasted: torus, cube, sphere, torus knot. */
        fun builtIn(kind: Int): Mesh {
            val b = Builder()
            fun grid(cols: Int, rows: Int, wrapRows: Boolean, at: (Double, Double) -> DoubleArray) {
                // positions shared across the seams (so smooth shading has no crease), texture places not
                val index = Array(cols + 1) { IntArray(rows + 1) }
                val tex = Array(cols + 1) { IntArray(rows + 1) }
                for (i in 0..cols) for (j in 0..rows) {
                    tex[i][j] = b.texture(i / cols.toDouble(), j / rows.toDouble())
                    if (i == cols || (wrapRows && j == rows)) continue
                    val p = at(i / cols.toDouble(), j / rows.toDouble())
                    index[i][j] = b.vertex(p[0], p[1], p[2])
                }
                for (j in 0..rows) if (!(wrapRows && j == rows)) index[cols][j] = index[0][j]
                if (wrapRows) for (i in 0..cols) index[i][rows] = index[i][0]
                for (i in 0 until cols) for (j in 0 until rows) {
                    b.face(listOf(
                        intArrayOf(index[i][j], tex[i][j], -1), intArrayOf(index[i + 1][j], tex[i + 1][j], -1),
                        intArrayOf(index[i + 1][j + 1], tex[i + 1][j + 1], -1), intArrayOf(index[i][j + 1], tex[i][j + 1], -1),
                    ))
                }
            }
            when (kind) {
                4 -> {
                    // a pyramid: a square base and four sloping sides
                    val c = listOf(b.vertex(-1.0, -0.6, -1.0), b.vertex(1.0, -0.6, -1.0), b.vertex(1.0, -0.6, 1.0), b.vertex(-1.0, -0.6, 1.0))
                    val top = b.vertex(0.0, 1.0, 0.0)
                    fun f(vararg i: Int) = b.face(i.map { intArrayOf(it, -1, -1) })
                    f(c[0], c[1], c[2], c[3])
                    for (i in 0..3) f(c[(i + 1) % 4], c[i], top)
                }
                5 -> {
                    // a thick triangle with a triangular hole in the middle
                    val outer = (0..2).map { val a = PI / 2 + it * 2 * PI / 3; cos(a) to sin(a) }
                    val inner = outer.map { (x, y) -> x * 0.45 to y * 0.45 }
                    val d = 0.22
                    val of = outer.map { (x, y) -> b.vertex(x, y, d) }
                    val ob = outer.map { (x, y) -> b.vertex(x, y, -d) }
                    val inf = inner.map { (x, y) -> b.vertex(x, y, d) }
                    val inb = inner.map { (x, y) -> b.vertex(x, y, -d) }
                    fun f(vararg i: Int) = b.face(i.map { intArrayOf(it, -1, -1) })
                    for (i in 0..2) {
                        val j = (i + 1) % 3
                        f(of[i], of[j], inf[j], inf[i])
                        f(ob[j], ob[i], inb[i], inb[j])
                        f(ob[i], ob[j], of[j], of[i])
                        f(inb[j], inb[i], inf[i], inf[j])
                    }
                }
                // the skull: a low-poly model that comes with the program
                6 -> return skull
                7 -> return crystal
                8 -> {
                    // a dollar sign: the glyph of a bold font, made solid
                    val font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 100)
                    val glyph = font.createGlyphVector(java.awt.font.FontRenderContext(null, true, true), "$")
                    extrude(b, java.awt.geom.Area(glyph.outline), 13.0)
                }
                9 -> {
                    // a rubber duck: a body with its tail turned up, a head, a beak and eyes – in their own colors
                    fun blob(cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, rz: Double, cols: Int, rows: Int, bend: (Double, Double, Double) -> DoubleArray = { x, y, z -> doubleArrayOf(x, y, z) }) =
                        grid(cols, rows, false) { u, w ->
                            val a = u * 2 * PI
                            val e = (w - 0.5) * PI
                            bend(cx + cos(e) * cos(a) * rx, cy + sin(e) * ry, cz + cos(e) * sin(a) * rz)
                        }
                    b.color = 0xFFD21F
                    blob(0.0, 0.0, 0.0, 1.0, 0.55, 0.72, 28, 14) { x, y, z ->
                        // towards the back it narrows and its top rises into the tail
                        val back = ((-x - 0.35) / 0.65).coerceIn(0.0, 1.0)
                        doubleArrayOf(x - back * 0.1, y + back * back * (if (y > -0.1) 0.5 else 0.15), z * (1 - back * 0.4))
                    }
                    blob(0.5, 0.72, 0.0, 0.42, 0.4, 0.4, 18, 12)
                    b.color = 0xFF7A1A
                    blob(0.98, 0.64, 0.0, 0.26, 0.07, 0.17, 12, 6)
                    b.color = 0x111111
                    for (side in listOf(-1.0, 1.0)) blob(0.78, 0.84, side * 0.22, 0.07, 0.08, 0.06, 8, 6)
                }
                1 -> {
                    // a cube: six squares
                    val c = listOf(-1.0, 1.0)
                    val idx = HashMap<Int, Int>()
                    for (x in 0..1) for (y in 0..1) for (z in 0..1) idx[x * 4 + y * 2 + z] = b.vertex(c[x], c[y], c[z])
                    val uvs = listOf(b.texture(0.0, 0.0), b.texture(1.0, 0.0), b.texture(1.0, 1.0), b.texture(0.0, 1.0))
                    for (q in listOf(listOf(0, 2, 6, 4), listOf(1, 5, 7, 3), listOf(0, 4, 5, 1), listOf(2, 3, 7, 6), listOf(0, 1, 3, 2), listOf(4, 6, 7, 5))) {
                        b.face(q.mapIndexed { i, k -> intArrayOf(idx[k]!!, uvs[i], -1) })
                    }
                }
                2 -> grid(32, 16, false) { u, w ->
                    val a = u * 2 * PI
                    val e = (w - 0.5) * PI
                    doubleArrayOf(cos(e) * cos(a), sin(e), cos(e) * sin(a))
                }
                3 -> grid(128, 8, true) { u, w ->
                    // a (2,3) torus knot with a round tube
                    val t = u * 2 * PI
                    fun knot(s: Double) = doubleArrayOf((2 + cos(3 * s)) * cos(2 * s), (2 + cos(3 * s)) * sin(2 * s), sin(3 * s))
                    val p = knot(t)
                    val q = knot(t + 0.01)
                    val tan = doubleArrayOf(q[0] - p[0], q[1] - p[1], q[2] - p[2]).let { d -> val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); DoubleArray(3) { d[it] / l } }
                    // a side direction: away from the knot's axis, kept square to the tangent
                    var nx = p[0]; var ny = p[1]; var nz = 0.0
                    val dot = nx * tan[0] + ny * tan[1] + nz * tan[2]
                    nx -= dot * tan[0]; ny -= dot * tan[1]; nz -= dot * tan[2]
                    val nl = sqrt(nx * nx + ny * ny + nz * nz)
                    nx /= nl; ny /= nl; nz /= nl
                    val bx = tan[1] * nz - tan[2] * ny; val by = tan[2] * nx - tan[0] * nz; val bz = tan[0] * ny - tan[1] * nx
                    val a = w * 2 * PI
                    val r = 0.45
                    doubleArrayOf(p[0] + r * (cos(a) * nx + sin(a) * bx), p[1] + r * (cos(a) * ny + sin(a) * by), p[2] + r * (cos(a) * nz + sin(a) * bz))
                }
                else -> grid(32, 16, true) { u, w ->
                    // a torus
                    val a = u * 2 * PI
                    val c = w * 2 * PI
                    val r = 1 + 0.42 * cos(c)
                    doubleArrayOf(r * cos(a), 0.42 * sin(c), r * sin(a))
                }
            }
            return b.build()!!
        }

        /**
         * The outline of [shape] (y pointing down, as in fonts) made into a solid [depth] thick to
         * each side: front and back faces – holes and all, by ear clipping – and the walls around.
         */
        private fun extrude(b: Builder, shape: java.awt.Shape, depth: Double) {
            // the outline as closed polygons
            val raw = ArrayList<MutableList<DoubleArray>>()
            val path = shape.getPathIterator(null, 1.2)
            val seg = DoubleArray(6)
            while (!path.isDone) {
                when (path.currentSegment(seg)) {
                    java.awt.geom.PathIterator.SEG_MOVETO -> raw += arrayListOf(doubleArrayOf(seg[0], -seg[1]))
                    java.awt.geom.PathIterator.SEG_LINETO -> raw.lastOrNull()?.add(doubleArrayOf(seg[0], -seg[1]))
                }
                path.next()
            }
            val contours = raw.map { pts ->
                pts.filterIndexed { i, p -> val q = pts[(i + pts.size - 1) % pts.size]; hypot(p[0] - q[0], p[1] - q[1]) > 1e-6 }
            }.filter { it.size >= 3 }
            fun area(p: List<DoubleArray>) = p.indices.sumOf { i -> val a = p[i]; val q = p[(i + 1) % p.size]; a[0] * q[1] - q[0] * a[1] } / 2
            fun inside(pt: DoubleArray, poly: List<DoubleArray>): Boolean {
                var odd = false
                for (i in poly.indices) {
                    val a = poly[i]; val c = poly[(i + 1) % poly.size]
                    if ((a[1] > pt[1]) != (c[1] > pt[1]) && pt[0] < (c[0] - a[0]) * (pt[1] - a[1]) / (c[1] - a[1]) + a[0]) odd = !odd
                }
                return odd
            }
            // holes lie inside an odd number of other outlines; solids counter-clockwise, holes clockwise
            val hole = contours.map { c -> contours.count { o -> o !== c && inside(c[0], o) } % 2 == 1 }
            val outline = contours.mapIndexed { i, c -> if ((area(c) > 0) == !hole[i]) c else c.reversed() }
            val front = outline.map { c -> c.map { p -> b.vertex(p[0], p[1], depth) } }
            val back = outline.map { c -> c.map { p -> b.vertex(p[0], p[1], -depth) } }
            // the walls
            for (k in outline.indices) {
                val n = outline[k].size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    b.face(listOf(front[k][i], back[k][i], back[k][j], front[k][j]).map { intArrayOf(it, -1, -1) })
                }
            }
            fun point(e: Pair<Int, Int>) = outline[e.first][e.second]
            fun crosses(a: DoubleArray, c: DoubleArray, d: DoubleArray, e: DoubleArray): Boolean {
                fun side(p: DoubleArray, q: DoubleArray, r: DoubleArray) = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
                fun same(p: DoubleArray, q: DoubleArray) = abs(p[0] - q[0]) < 1e-9 && abs(p[1] - q[1]) < 1e-9
                if (same(a, d) || same(a, e) || same(c, d) || same(c, e)) return false
                return side(a, c, d) * side(a, c, e) < 0 && side(d, e, a) * side(d, e, c) < 0
            }
            // the faces front and back: every solid with the holes right inside it
            for (s in outline.indices) {
                if (hole[s]) continue
                val holes = outline.indices.filter { h ->
                    hole[h] && inside(outline[h][0], outline[s]) &&
                        outline.indices.none { o -> o != s && !hole[o] && inside(outline[o][0], outline[s]) && inside(outline[h][0], outline[o]) }
                }
                var poly = outline[s].indices.map { s to it }
                // each hole joined to the outline by a bridge that crosses no edge
                for (h in holes.sortedByDescending { h -> outline[h].maxOf { it[0] } }) {
                    val hp = outline[h]
                    val start = hp.indices.maxBy { hp[it][0] }
                    val from = hp[start]
                    val edges = ArrayList<Pair<DoubleArray, DoubleArray>>()
                    for (i in poly.indices) edges += point(poly[i]) to point(poly[(i + 1) % poly.size])
                    for (o in holes) for (i in outline[o].indices) edges += outline[o][i] to outline[o][(i + 1) % outline[o].size]
                    val order = poly.indices.sortedBy { val p = point(poly[it]); hypot(p[0] - from[0], p[1] - from[1]) }
                    val pick = order.firstOrNull { i -> val p = point(poly[i]); edges.none { (d, e) -> crosses(from, p, d, e) } } ?: order.first()
                    val joined = ArrayList<Pair<Int, Int>>()
                    joined += poly.subList(0, pick + 1)
                    for (m in 0..hp.size) joined += h to ((start + m) % hp.size)
                    joined += poly.subList(pick, poly.size)
                    poly = joined
                }
                for (t in earClip(poly.map(::point))) {
                    b.face(t.map { intArrayOf(front[poly[it].first][poly[it].second], -1, -1) })
                    b.face(t.reversed().map { intArrayOf(back[poly[it].first][poly[it].second], -1, -1) })
                }
            }
        }

        /** A simple counter-clockwise polygon (bridged holes allowed) cut into triangles, ear by ear. */
        private fun earClip(p: List<DoubleArray>): List<List<Int>> {
            val left = p.indices.toMutableList()
            val out = ArrayList<List<Int>>()
            fun cross(a: Int, c: Int, d: Int) = (p[c][0] - p[a][0]) * (p[d][1] - p[a][1]) - (p[c][1] - p[a][1]) * (p[d][0] - p[a][0])
            fun same(a: Int, c: Int) = abs(p[a][0] - p[c][0]) < 1e-9 && abs(p[a][1] - p[c][1]) < 1e-9
            fun inTriangle(q: Int, a: Int, c: Int, d: Int) = cross(a, c, q) >= 0 && cross(c, d, q) >= 0 && cross(d, a, q) >= 0
            while (left.size > 3) {
                var clipped = false
                for (k in left.indices) {
                    val a = left[(k + left.size - 1) % left.size]
                    val c = left[k]
                    val d = left[(k + 1) % left.size]
                    if (cross(a, c, d) <= 1e-12) continue
                    if (left.any { q -> !same(q, a) && !same(q, c) && !same(q, d) && inTriangle(q, a, c, d) }) continue
                    out += listOf(a, c, d)
                    left.removeAt(k)
                    clipped = true
                    break
                }
                // nothing clips cleanly (a flat stretch): drop a point and go on
                if (!clipped) left.removeAt(left.indices.minBy { k -> abs(cross(left[(k + left.size - 1) % left.size], left[k], left[(k + 1) % left.size])) })
            }
            if (left.size == 3 && cross(left[0], left[1], left[2]) > 0) out += left.toList()
            return out
        }

        /** A model that comes with the program (resources/models/[name].obj); [turned] a quarter so its face looks at us. */
        private fun resource(name: String, turned: Boolean = false): Mesh {
            val text = Mesh::class.java.getResourceAsStream("/models/$name.obj")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            return parseObj(if (!turned) text else text.lineSequence().joinToString("\n") { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[0] == "v") "v ${-p[3].toDouble()} ${p[2]} ${p[1]}" else line
            })!!
        }

        /** The low-poly skull: its face looks along x in the file. */
        private val skull: Mesh by lazy { resource("skull", turned = true) }

        /** A low-poly crystal, standing upright. */
        private val crystal: Mesh by lazy { resource("crystal") }

        private var lastText: String? = null
        private var last: Mesh? = null

        /** The mesh stored in [text] (OBJ), remembered; null if there is none or it can't be read. */
        @Synchronized
        fun of(text: String): Mesh? {
            if (text.isBlank()) return null
            if (text == lastText) return last
            val mesh = try { parseObj(text) } catch (e: Exception) { null }
            lastText = text
            last = mesh
            return mesh
        }

        /**
         * A script for Blender's Python console or text editor: puts the active object – with its
         * modifiers, turned and scaled as it is – on the clipboard as OBJ text, for pasting here.
         */
        const val BLENDER_SCRIPT = """import bpy
o = bpy.context.active_object
e = o.evaluated_get(bpy.context.evaluated_depsgraph_get())
m = e.to_mesh()
m.transform(o.matrix_world)
out = ["# " + o.name]
out += ["v %.5f %.5f %.5f" % (v.co.x, v.co.z, -v.co.y) for v in m.vertices]
uv = m.uv_layers.active
if uv:
    out += ["vt %.5f %.5f" % (d.uv[0], d.uv[1]) for d in uv.data]
for p in m.polygons:
    if uv:
        out.append("f " + " ".join("%d/%d" % (m.loops[i].vertex_index + 1, i + 1) for i in p.loop_indices))
    else:
        out.append("f " + " ".join(str(i + 1) for i in p.vertices))
bpy.context.window_manager.clipboard = "\n".join(out)
print(len(m.polygons), "faces copied")
e.to_mesh_clear()
"""
    }
}

/**
 * A 3D model, pasted from Blender (as OBJ text or file, up to 1024 faces) or one of the built-in
 * shapes, rendered with a z-buffer in perspective: turned on three axes, moved and scaled; as
 * plastic, chrome, toon, textured with the picture below or in its normals' colors; with a
 * wireframe over it or alone; lit from a direction, with gloss and a rim light.
 */
object Model3D : Effect("model3d", "3D model", "A 3D model – pasted from Blender as OBJ, or a built-in shape – turned in space and rendered as plastic, chrome, toon, textured or wireframe") {
    private val materials = listOf("Plastic", "Chrome", "Toon", "Texture: the picture", "Normals")

    override val params = listOf(
        Param.Model("model", "Model", "Paste OBJ text or an .obj file – e.g. from Blender with the script (up to ${Mesh.MAX_FACES} faces)"),
        Param.Choice("builtin", "Built-in shape", listOf("Torus", "Cube", "Sphere", "Torus knot", "Pyramid", "Triangle with hole", "Skull", "Crystal", "Dollar sign", "Rubber duck"), 5, "Shown while no model is pasted"),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("rotX", "Rotation X", -36000, 36000, 2500, "°", "Tips the model forwards or back", decimals = 2),
        Param.Slider("rotY", "Rotation Y", -36000, 36000, 3000, "°", "Turns it sideways", decimals = 2),
        Param.Slider("rotZ", "Rotation Z", -36000, 36000, 0, "°", "Turns it in the picture plane", decimals = 2),
        Param.Slider("modelX", "Position X", -10000, 10000, 0, " %", "From the middle, in canvas widths: 100 % moves it one whole width", decimals = 2, transform = true),
        Param.Slider("modelY", "Position Y", -10000, 10000, 0, " %", "From the middle, in canvas heights; positive moves it down", decimals = 2, transform = true),
        Param.Slider("scale", "Scale", 100, 100000, 10000, " %", decimals = 2, transform = true),
        Param.Slider("perspective", "Perspective", 0, 100, 40, " %", "How strongly near parts grow and far ones shrink"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("material", "Material", materials, 1, "Texture: the picture below (or the base color) wrapped around the model along its UVs"),
        Param.Color("color", "Color", 0x999999),
        Param.Toggle("ownColors", "Model colors", true, "Models with colors of their own (the rubber duck) keep them; off: all in the color above"),
        Param.Choice("metal", "Metal", listOf("Silver", "Gold", "Holographic", "Blue steel", "The color"), 0, "For chrome: the metal's tint, as with the chrome shapes – holographic changes its hue with the surface's direction"),
        Param.Toggle("smooth", "Smooth shading", false, "Off: every face flat, faceted"),
        Param.Slider("gloss", "Gloss", 0, 100, 60, " %"),
        Param.Slider("rim", "Rim light", 0, 100, 30, " %", "Light along the outline"),
        Param.Slider("light", "Light direction", -18000, 18000, 13500, "°", "Where the light comes from, around the picture: 135° = top left", decimals = 2),
        Param.Slider("ambient", "Ambient", 0, 100, 25, " %", "Light in the shadows"),
        Param.Choice("wire", "Wireframe", listOf("Off", "Over the faces", "Only"), 0, "The polygons' edges (not the diagonals of split faces)"),
        Param.Color("wireColor", "Wire color", 0xFFFFFF),
        Param.Slider("wireWidth", "Wire width", 25, 1000, 120, " px", decimals = 2),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "3 × 3", "4 × 4"), 1),
        Param.Toggle("transparent", "Transparent background", false, "Only the model, without the base color"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mesh = Mesh.of(v.text("model")) ?: Mesh.builtIn(v["builtin"])
        return render(if (v.bool("transparent")) Pixels(src.width, src.height) else src, src, mesh, v)
    }

    private fun render(ground: Pixels, picture: Pixels, mesh: Mesh, v: Values): Pixels {
        val w = ground.width
        val h = ground.height
        var k = (v["antialias"] + 1).coerceIn(1, 4)
        while (k > 1 && w.toLong() * h * k * k > 40_000_000L) k--
        val ww = w * k
        val hh = h * k
        val size = min(ww, hh) * 0.38 * v["scale"] / 10000.0
        val persp = v["perspective"] / 100.0
        val eye = size * (1.4 + 12 * (1 - persp).pow(2))
        val cx = ww / 2.0 + v["modelX"] / 10000.0 * ww
        val cy = hh / 2.0 + v["modelY"] / 10000.0 * hh
        // the turn: about y, then x, then z
        val ax = Math.toRadians(v["rotX"] / 100.0); val ay = Math.toRadians(v["rotY"] / 100.0); val az = Math.toRadians(v["rotZ"] / 100.0)
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
        val tris = mesh.triangles
        // per corner: turned position (scene units, y up, z towards us), turned normal, screen place and 1/depth
        val cam = DoubleArray(tris * 9)
        val nrm = DoubleArray(tris * 9)
        val sx = DoubleArray(tris * 3)
        val sy = DoubleArray(tris * 3)
        val iz = DoubleArray(tris * 3)
        val usable = BooleanArray(tris)
        for (t in 0 until tris) {
            var ok = true
            for (c in 0..2) {
                val i = t * 3 + c
                turn(mesh.pos[i * 3].toDouble(), mesh.pos[i * 3 + 1].toDouble(), mesh.pos[i * 3 + 2].toDouble(), cam, i * 3)
                for (a in 0..2) cam[i * 3 + a] *= size
                turn(mesh.nrm[i * 3].toDouble(), mesh.nrm[i * 3 + 1].toDouble(), mesh.nrm[i * 3 + 2].toDouble(), nrm, i * 3)
                val depth = eye - cam[i * 3 + 2]
                if (depth < eye * 0.05) ok = false
                iz[i] = 1 / depth
                sx[i] = cx + cam[i * 3] * eye / depth
                sy[i] = cy - cam[i * 3 + 1] * eye / depth
            }
            usable[t] = ok
        }

        val material = v["material"]
        val metal = v["metal"]
        val own = if (v.bool("ownColors")) mesh.colors else null
        val base = v["color"]
        val smooth = v.bool("smooth")
        val gloss = v["gloss"] / 100.0
        val rim = v["rim"] / 100.0
        val ambient = v["ambient"] / 100.0
        val la = Math.toRadians(v["light"] / 100.0)
        val lx = cos(la) * 0.62; val ly = sin(la) * 0.62; val lz = 0.78
        val wire = v["wire"]
        val wireColor = v["wireColor"]
        val halfWire = v["wireWidth"] / 100.0 * k / 2
        val uvs = mesh.uv

        // in bands of output rows: a z-buffer for the band, shaded, then averaged down
        val band = 8
        val bands = (h + band - 1) / band
        val lists = Array(bands) { ArrayList<Int>() }
        for (t in 0 until tris) {
            if (!usable[t]) continue
            val y0 = min(sy[t * 3], min(sy[t * 3 + 1], sy[t * 3 + 2]))
            val y1 = max(sy[t * 3], max(sy[t * 3 + 1], sy[t * 3 + 2]))
            if (y1 < 0 || y0 >= hh) continue
            val b0 = (max(0.0, y0) / k / band).toInt().coerceIn(0, bands - 1)
            val b1 = (min(hh - 1.0, y1) / k / band).toInt().coerceIn(0, bands - 1)
            for (b in b0..b1) lists[b] += t
        }
        val out = ground.copy()
        java.util.stream.IntStream.range(0, bands).parallel().forEach { b ->
            val rowFrom = b * band * k
            val rowTo = min(hh, (b + 1) * band * k)
            val rows = rowTo - rowFrom
            val depth = FloatArray(ww * rows)
            val tri = IntArray(ww * rows) { -1 }
            val bary1 = FloatArray(ww * rows)
            val bary2 = FloatArray(ww * rows)
            val edge = FloatArray(ww * rows)
            for (t in lists[b]) {
                val i0 = t * 3; val i1 = i0 + 1; val i2 = i0 + 2
                val area = (sx[i1] - sx[i0]) * (sy[i2] - sy[i0]) - (sx[i2] - sx[i0]) * (sy[i1] - sy[i0])
                if (abs(area) < 1e-9) continue
                val x0 = max(0, kotlin.math.floor(min(sx[i0], min(sx[i1], sx[i2]))).toInt())
                val x1 = min(ww - 1, kotlin.math.ceil(max(sx[i0], max(sx[i1], sx[i2]))).toInt())
                val ya = max(rowFrom, kotlin.math.floor(min(sy[i0], min(sy[i1], sy[i2]))).toInt())
                val yb = min(rowTo - 1, kotlin.math.ceil(max(sy[i0], max(sy[i1], sy[i2]))).toInt())
                // pixel distance to each edge per unit of its barycentric: 2 · area / edge length
                val e0 = abs(area) / max(1e-9, hypot(sx[i2] - sx[i1], sy[i2] - sy[i1]))
                val e1 = abs(area) / max(1e-9, hypot(sx[i0] - sx[i2], sy[i0] - sy[i2]))
                val e2 = abs(area) / max(1e-9, hypot(sx[i1] - sx[i0], sy[i1] - sy[i0]))
                val flags = mesh.edges[t].toInt()
                for (y in ya..yb) {
                    val py = y + 0.5
                    for (x in x0..x1) {
                        val px = x + 0.5
                        val l0 = ((sx[i1] - px) * (sy[i2] - py) - (sx[i2] - px) * (sy[i1] - py)) / area
                        val l1 = ((sx[i2] - px) * (sy[i0] - py) - (sx[i0] - px) * (sy[i2] - py)) / area
                        val l2 = 1 - l0 - l1
                        if (l0 < 0 || l1 < 0 || l2 < 0) continue
                        val z = l0 * iz[i0] + l1 * iz[i1] + l2 * iz[i2]
                        val j = (y - rowFrom) * ww + x
                        if (z <= depth[j]) continue
                        depth[j] = z.toFloat()
                        tri[j] = t
                        // perspective-correct shares of the corners
                        bary1[j] = (l1 * iz[i1] / z).toFloat()
                        bary2[j] = (l2 * iz[i2] / z).toFloat()
                        // distance to the nearest own edge of the polygon, in pixels: 1-2 is across corner 0, …
                        var d = Double.MAX_VALUE
                        if (flags and 2 != 0) d = min(d, l0 * e0)
                        if (flags and 4 != 0) d = min(d, l1 * e1)
                        if (flags and 1 != 0) d = min(d, l2 * e2)
                        edge[j] = d.toFloat()
                    }
                }
            }
            // shading, then k × k averaged into the output
            val acc = DoubleArray(4)
            val outRows = (rows + k - 1) / k
            for (oy in 0 until outRows) {
                val y = b * band + oy
                if (y >= h) break
                for (x in 0 until w) {
                    acc.fill(0.0)
                    for (dy in 0 until k) for (dx in 0 until k) {
                        val j = (oy * k + dy) * ww + x * k + dx
                        if (j >= ww * rows) continue
                        val t = tri[j]
                        if (t < 0) continue
                        val c = shade(
                            t, bary1[j].toDouble(), bary2[j].toDouble(), edge[j].toDouble(),
                            mesh, cam, nrm, uvs, eye, material, metal, own?.get(t) ?: base, smooth, gloss, rim, ambient, lx, ly, lz, wire, wireColor, halfWire, picture,
                        )
                        if (c == 0) continue
                        acc[0] += 1.0
                        acc[1] += c shr 16 and 0xFF; acc[2] += c shr 8 and 0xFF; acc[3] += c and 0xFF
                    }
                    if (acc[0] <= 0) continue
                    val a = acc[0] / (k * k)
                    val i = y * w + x
                    val g = out.data[i]
                    fun mix(sh: Int, total: Double) = ((g shr sh and 0xFF) * (1 - a) + total / acc[0] * a + 0.5).toInt().coerceIn(0, 255)
                    out.data[i] = (max(g ushr 24, (a * 255 + 0.5).toInt()) shl 24) or (mix(16, acc[1]) shl 16) or (mix(8, acc[2]) shl 8) or mix(0, acc[3])
                }
            }
        }
        return out
    }

    /** The color of triangle [t] at the corner shares (1 − b1 − b2, b1, b2); 0 for nothing (wireframe only). */
    private fun shade(
        t: Int, b1: Double, b2: Double, edgeDistance: Double,
        mesh: Mesh, cam: DoubleArray, nrm: DoubleArray, uvs: FloatArray?, eye: Double,
        material: Int, metal: Int, base: Int, smooth: Boolean, gloss: Double, rim: Double, ambient: Double,
        lx: Double, ly: Double, lz: Double, wire: Int, wireColor: Int, halfWire: Double, picture: Pixels,
    ): Int {
        val onWire = wire > 0 && edgeDistance <= halfWire
        if (wire == 2) return if (onWire) wireColor or 0xFF000000.toInt() else 0
        if (onWire) return wireColor or 0xFF000000.toInt()
        val b0 = 1 - b1 - b2
        val o = t * 9
        // where on the surface, in the scene
        val px = b0 * cam[o] + b1 * cam[o + 3] + b2 * cam[o + 6]
        val py = b0 * cam[o + 1] + b1 * cam[o + 4] + b2 * cam[o + 7]
        val pz = b0 * cam[o + 2] + b1 * cam[o + 5] + b2 * cam[o + 8]
        // the face's own direction
        val ax = cam[o + 3] - cam[o]; val ay = cam[o + 4] - cam[o + 1]; val az = cam[o + 5] - cam[o + 2]
        val bx = cam[o + 6] - cam[o]; val by = cam[o + 7] - cam[o + 1]; val bz = cam[o + 8] - cam[o + 2]
        var fx = ay * bz - az * by; var fy = az * bx - ax * bz; var fz = ax * by - ay * bx
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-12)
        fx /= fl; fy /= fl; fz /= fl
        // towards the eye
        var vx = -px; var vy = -py; var vz = eye - pz
        val vl = sqrt(vx * vx + vy * vy + vz * vz)
        vx /= vl; vy /= vl; vz /= vl
        var nx: Double; var ny: Double; var nz: Double
        if (smooth) {
            nx = b0 * nrm[o] + b1 * nrm[o + 3] + b2 * nrm[o + 6]
            ny = b0 * nrm[o + 1] + b1 * nrm[o + 4] + b2 * nrm[o + 7]
            nz = b0 * nrm[o + 2] + b1 * nrm[o + 5] + b2 * nrm[o + 8]
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
            nx /= nl; ny /= nl; nz /= nl
        } else { nx = fx; ny = fy; nz = fz }
        // both sides are seen: the one facing us
        val facing = fx * vx + fy * vy + fz * vz
        if (facing < 0 == (nx * fx + ny * fy + nz * fz) > 0) { nx = -nx; ny = -ny; nz = -nz }
        val ndl = nx * lx + ny * ly + nz * lz
        val diffuse = max(0.0, ndl)
        val ndv = (nx * vx + ny * vy + nz * vz).coerceIn(0.0, 1.0)
        // the light mirrored on the surface, seen by the eye
        val rx = 2 * ndl * nx - lx; val ry = 2 * ndl * ny - ly; val rz = 2 * ndl * nz - lz
        val spec = max(0.0, rx * vx + ry * vy + rz * vz).pow(8 + 120 * gloss) * gloss
        val fresnel = (1 - ndv).pow(3) * rim
        fun rgb(r: Double, g: Double, b: Double) = 0xFF000000.toInt() or ((r * 255).toInt().coerceIn(0, 255) shl 16) or ((g * 255).toInt().coerceIn(0, 255) shl 8) or (b * 255).toInt().coerceIn(0, 255)
        val br = (base shr 16 and 0xFF) / 255.0; val bg = (base shr 8 and 0xFF) / 255.0; val bb = (base and 0xFF) / 255.0
        return when (material) {
            1 -> {
                // chrome: the surroundings mirrored – sky above, a dark horizon, the ground – tinted by the color
                val dv = nx * vx + ny * vy + nz * vz
                val my = 2 * dv * ny - vy
                val env = when {
                    my > 0.05 -> 0.82 + 0.18 * min(1.0, my * 1.5)
                    my > -0.06 -> 0.03 + 0.12 * (my + 0.06) / 0.11
                    else -> 0.22 + 0.5 * min(1.0, (-my - 0.06) * 1.3)
                }
                val l = env + spec * 1.5 + fresnel * 0.5
                // the metal's tint, as with the chrome shapes
                val (tr, tg, tb) = when (metal) {
                    1 -> Triple(1.0, 0.82, 0.45)
                    2 -> {
                        // holographic: the hue follows the surface's direction and its brightness
                        val hue = atan2(ny, nx) / (2 * PI) + 0.5 + min(1.0, l) * 0.3
                        val c = java.awt.Color.HSBtoRGB(hue.toFloat(), 0.35f, 1f)
                        Triple((c shr 16 and 0xFF) / 255.0, (c shr 8 and 0xFF) / 255.0, (c and 0xFF) / 255.0)
                    }
                    3 -> Triple(0.7, 0.82, 1.0)
                    4 -> Triple(0.75 + 0.25 * br, 0.75 + 0.25 * bg, 0.75 + 0.25 * bb)
                    else -> Triple(1.0, 1.0, 1.0)
                }
                rgb(l * tr, l * tg, l * tb)
            }
            2 -> {
                // toon: three hard steps of light and a hard highlight
                val step = when {
                    diffuse > 0.7 -> 1.0
                    diffuse > 0.3 -> 0.7
                    else -> 0.4
                }
                val l = ambient + (1 - ambient) * step
                val hi = if (spec > 0.35 * gloss && gloss > 0) 0.6 else 0.0
                val edge = if (ndv < 0.22) 0.15 else 1.0
                rgb((br * l + hi) * edge, (bg * l + hi) * edge, (bb * l + hi) * edge)
            }
            3 -> {
                // the picture wrapped around: along the UVs, or – without them – around the model like a globe
                val (u, w) = if (uvs != null) {
                    val q = t * 6
                    (b0 * uvs[q] + b1 * uvs[q + 2] + b2 * uvs[q + 4]) to (b0 * uvs[q + 1] + b1 * uvs[q + 3] + b2 * uvs[q + 5])
                } else {
                    val mx = b0 * mesh.pos[o] + b1 * mesh.pos[o + 3] + b2 * mesh.pos[o + 6]
                    val my = b0 * mesh.pos[o + 1] + b1 * mesh.pos[o + 4] + b2 * mesh.pos[o + 7]
                    val mz = b0 * mesh.pos[o + 2] + b1 * mesh.pos[o + 5] + b2 * mesh.pos[o + 8]
                    (atan2(mx, mz) / (2 * PI) + 0.5) to (asin((my / sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-9)).coerceIn(-1.0, 1.0)) / PI + 0.5)
                }
                val tx = Math.floorMod((u * picture.width).toInt(), picture.width)
                val ty = Math.floorMod(((1 - w) * picture.height).toInt(), picture.height)
                val c = picture.data[ty * picture.width + tx]
                val l = ambient + (1 - ambient) * diffuse
                rgb(
                    (c shr 16 and 0xFF) / 255.0 * l + spec + fresnel,
                    (c shr 8 and 0xFF) / 255.0 * l + spec + fresnel,
                    (c and 0xFF) / 255.0 * l + spec + fresnel,
                )
            }
            4 -> rgb(nx * 0.5 + 0.5, ny * 0.5 + 0.5, nz * 0.5 + 0.5)
            else -> {
                val l = ambient + (1 - ambient) * diffuse
                rgb(br * l + spec + fresnel, bg * l + spec + fresnel, bb * l + spec + fresnel)
            }
        }
    }
}
