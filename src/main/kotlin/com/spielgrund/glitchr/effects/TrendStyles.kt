package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Y2K chrome symbols: little emblems – circle flowers, shine stars, linked balls, swirls,
 * spiky bursts, eyes, striped spheres, planets, hearts – inflated into rounded metal and
 * shaded like polished chrome reflecting a sky above a dark horizon.
 */
internal object ChromeShapes {
    private const val KINDS = 12

    /** Symbol [k] in a box of half size [r] around (0, 0). */
    private fun symbol(k: Int, r: Double): Shape {
        fun circle(x: Double, y: Double, rr: Double) = Ellipse2D.Double(x - rr, y - rr, 2 * rr, 2 * rr)
        fun star(rr: Double, inner: Double): Path2D.Double {
            val p = Path2D.Double()
            p.moveTo(0.0, -rr)
            p.quadTo(inner, -inner, rr, 0.0)
            p.quadTo(inner, inner, 0.0, rr)
            p.quadTo(-inner, inner, -rr, 0.0)
            p.quadTo(-inner, -inner, 0.0, -rr)
            p.closePath()
            return p
        }
        fun thick(shape: Shape, width: Double) = Area(BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(shape))
        return when (k) {
            // five balls around a sixth
            0 -> Area().apply { for (i in 0 until 5) add(Area(circle(cos(i * 2 * PI / 5 - PI / 2) * r * 0.55, sin(i * 2 * PI / 5 - PI / 2) * r * 0.55, r * 0.38))); add(Area(circle(0.0, 0.0, r * 0.3))) }
            1 -> star(r, r * 0.1)
            // balls linked in a zigzag
            2 -> Area().apply {
                val pts = listOf(-0.6 to -0.6, 0.0 to -0.6, 0.6 to -0.6, -0.6 to 0.0, 0.0 to 0.0, 0.6 to 0.0, -0.6 to 0.6, 0.0 to 0.6, 0.6 to 0.6)
                for ((x, y) in pts) add(Area(circle(x * r, y * r, r * 0.24)))
                val link = Path2D.Double().apply { moveTo(-0.6 * r, -0.6 * r); lineTo(0.6 * r, 0.0); lineTo(-0.6 * r, 0.6 * r) }
                add(thick(link, r * 0.26))
            }
            // S swirl: two thick crescents
            3 -> Area().apply {
                add(thick(Arc2D.Double(-r * 0.8, -r * 0.85, r * 1.5, r * 1.0, 20.0, 230.0, Arc2D.OPEN), r * 0.28))
                add(thick(Arc2D.Double(-r * 0.7, -r * 0.15, r * 1.5, r * 1.0, 200.0, 230.0, Arc2D.OPEN), r * 0.28))
            }
            // a spiky burst
            4 -> Path2D.Double().apply {
                val n = 15
                for (i in 0 until 2 * n) {
                    val a = i * PI / n
                    val rr = if (i % 2 == 0) r * (0.85 + 0.15 * sin(i * 1.7)) else r * 0.38
                    if (i == 0) moveTo(cos(a) * rr, sin(a) * rr) else lineTo(cos(a) * rr, sin(a) * rr)
                }
                closePath()
            }
            // an eye: oval ring with a star inside
            5 -> Area(thick(Ellipse2D.Double(-r, -r * 0.62, 2 * r, r * 1.24), r * 0.2)).apply { add(Area(star(r * 0.45, r * 0.06))) }
            // a sphere, striped at the bottom
            6 -> Area(circle(0.0, 0.0, r * 0.85)).apply {
                for (i in 0 until 6) subtract(Area(Rectangle2D.Double(-r, r * (0.05 + i * 0.14), 2 * r, r * 0.06)))
            }
            // a planet with a tilted ring
            7 -> Area(circle(0.0, 0.0, r * 0.6)).apply {
                val ring = AffineTransform.getRotateInstance(-0.45).createTransformedShape(Ellipse2D.Double(-r, -r * 0.28, 2 * r, r * 0.56))
                add(thick(ring, r * 0.12))
            }
            // a heart outline
            8 -> Path2D.Double().apply {
                moveTo(0.0, r * 0.85)
                curveTo(-r * 1.3, -r * 0.05, -r * 0.55, -r * 0.95, 0.0, -r * 0.35)
                curveTo(r * 0.55, -r * 0.95, r * 1.3, -r * 0.05, 0.0, r * 0.85)
                closePath()
            }.let { thick(it, r * 0.22) }
            // an H of two slanted pills joined
            9 -> Area().apply {
                add(Area(AffineTransform.getRotateInstance(0.35).createTransformedShape(RoundRectangle2D.Double(-r * 0.75, -r, r * 0.42, 2 * r, r * 0.42, r * 0.42))))
                add(Area(AffineTransform.getRotateInstance(0.35).createTransformedShape(RoundRectangle2D.Double(r * 0.33, -r, r * 0.42, 2 * r, r * 0.42, r * 0.42))))
                add(thick(Line2D.Double(-r * 0.45, r * 0.1, r * 0.5, -r * 0.1), r * 0.25))
            }
            // a lens with a star cut out
            10 -> Area(Ellipse2D.Double(-r, -r * 0.62, 2 * r, r * 1.24)).apply { subtract(Area(star(r * 0.6, r * 0.09))) }
            // two balls with a soft neck
            else -> Area().apply {
                add(Area(circle(-r * 0.5, -r * 0.45, r * 0.3)))
                add(Area(circle(r * 0.5, r * 0.45, r * 0.3)))
                add(Area(circle(r * 0.55, -r * 0.55, r * 0.2)))
                add(thick(Path2D.Double().apply { moveTo(-r * 0.5, -r * 0.45); curveTo(0.0, -r * 0.1, 0.0, r * 0.1, r * 0.5, r * 0.45) }, r * 0.24))
                add(thick(Line2D.Double(r * 0.1, 0.0, r * 0.55, -r * 0.55), r * 0.16))
            }
        }
    }

    /** How many symbol shapes there are (see [symbol]). */
    const val COUNT = KINDS

    /**
     * Chrome symbols on [src]: [layout] 0 grid, 1 scattered, 2 one big; [symbol] 1..[COUNT]
     * picks the shape, 0 a random one per symbol. Each symbol is a bulged plate turned in
     * space – [turnX] tips it forwards or back, [turnY] turns it sideways, [turnZ] in the
     * picture plane (radians) – while the surroundings it mirrors stay, so sky, horizon and
     * highlight slide over the metal; seen from behind it shows mirrored. Anti-aliasing: [aa] × [aa]
     * samples, but only for the pixels that need them – edges, the horizon line, crinkles.
     */
    fun draw(
        src: Pixels, seed: Long, layout: Int, count: Int, sizeShare: Double, symbol: Int,
        turnX: Double, turnY: Double, turnZ: Double, tint: Int, depth: Double, aa: Int,
        thickness: Double = 0.0, crinkle: Double = 0.0, crinkleSize: Double = 0.25, crinkleReach: Double = 0.6,
        crinkleAngle: Double = 0.0, crinklePull: Double = 0.0, icon: CustomIcon? = null,
        contrast: Double = 0.0, brightness: Double = 0.0,
    ): Pixels {
        val k = aa.coerceIn(1, 4)
        val w = src.width
        val h = src.height
        // the symbols' own maps a little finer with anti-aliasing, so crinkles and edges keep their detail
        val mapScale = min(k, 2).toDouble()
        val random = Random(seed)
        // where the symbols go: centre, half size, kind, own turn in the picture plane
        data class Spot(val x: Double, val y: Double, val r: Double, val kind: Int, val angle: Double)
        val spots = ArrayList<Spot>()
        fun kind() = if (symbol in 1..KINDS) symbol - 1 else random.nextInt(KINDS)
        when (layout) {
            0 -> {
                val cols = max(1, sqrt(count * w.toDouble() / h).toInt())
                val rows = max(1, (count + cols - 1) / cols)
                val cw = w.toDouble() / cols
                val ch = h.toDouble() / rows
                for (i in 0 until min(count, cols * rows)) spots += Spot((i % cols + 0.5) * cw, (i / cols + 0.5) * ch, min(cw, ch) * 0.36 * sizeShare, kind(), 0.0)
            }
            1 -> repeat(count) {
                spots += Spot(random.nextDouble() * w, random.nextDouble() * h, min(w, h) * (0.06 + random.nextDouble() * 0.12) * sizeShare, kind(), (random.nextDouble() - 0.5) * 0.8)
            }
            else -> spots += Spot(w / 2.0, h / 2.0, min(w, h) * 0.38 * sizeShare, kind(), 0.0)
        }

        // the turn in space: R = Rz · Rx · Ry; its columns are where the plate's x, y and normal point
        val cx = cos(turnX); val sx = sin(turnX)
        val cy = cos(turnY); val sy = sin(turnY)
        fun rotate(x: Double, y: Double, z: Double, zTurn: Double, out: DoubleArray) {
            // about y, then x, then z
            val x1 = x * cy + z * sy
            val z1 = -x * sy + z * cy
            val y2 = y * cx - z1 * sx
            val z2 = y * sx + z1 * cx
            val cz = cos(zTurn); val sz = sin(zTurn)
            out[0] = x1 * cz - y2 * sz
            out[1] = x1 * sz + y2 * cz
            out[2] = z2
        }

        /** The turn undone: a point in space back into the plate's own frame. */
        fun unrotate(x: Double, y: Double, z: Double, zTurn: Double, out: DoubleArray) {
            val cz = cos(zTurn); val sz = sin(zTurn)
            val x1 = x * cz + y * sz
            val y1 = -x * sz + y * cz
            val y2 = y1 * cx + z * sx
            val z2 = -y1 * sx + z * cx
            out[0] = x1 * cy - z2 * sy
            out[1] = y2
            out[2] = x1 * sy + z2 * cy
        }

        /** The color of polished metal whose surface faces ([ux], [uy], [uz]) (unit length, towards us). */
        fun shade(ux: Double, uy: Double, uz: Double): Int {
            // reflection of the view: which part of the surroundings the metal mirrors
            val ry = 2 * uy * uz
            val rx = 2 * ux * uz
            val env = when {
                // sky above: bright, a little darker straight up
                ry < -0.02 -> 0.82 + 0.18 * min(1.0, (-ry - 0.02) * 3).let { 1 - (it - 0.6).pow(2) }
                // the thin dark horizon band
                ry < 0.07 -> 0.06 + 0.12 * ((ry + 0.02) / 0.09)
                // the ground: grey, lighter towards us
                else -> 0.32 + 0.5 * min(1.0, (ry - 0.07) * 1.6)
            }.coerceIn(0.0, 1.0)
            // a sharp highlight from the top left
            val spec = max(0.0, -rx * 0.55 - ry * 0.55 + uz * 0.62).pow(40) * 1.2
            val l = min(1.0, env + spec)
            val (cr, cg, cb) = when (tint) {
                1 -> Triple(1.0, 0.82, 0.45)
                2 -> {
                    // holographic: the hue follows the surface direction
                    val hue = (kotlin.math.atan2(uy, ux) / (2 * PI) + 0.5 + l * 0.3)
                    val c = Color.HSBtoRGB(hue.toFloat(), 0.35f, 1f)
                    Triple((c shr 16 and 0xFF) / 255.0, (c shr 8 and 0xFF) / 255.0, (c and 0xFF) / 255.0)
                }
                3 -> Triple(0.7, 0.82, 1.0)
                else -> Triple(1.0, 1.0, 1.0)
            }
            return ((l * cr * 255).toInt().coerceIn(0, 255) shl 16) or ((l * cg * 255).toInt().coerceIn(0, 255) shl 8) or (l * cb * 255).toInt().coerceIn(0, 255)
        }

        val creaseAngles = DoubleArray(spots.size) { random.nextDouble() * PI }
        // every symbol as something that tells its color at any point of the picture
        val bodies = arrayOfNulls<Body>(spots.size)
        for (si in spots.indices) {
            val spot = spots[si]
            val zTurn = turnZ + spot.angle
            // the plate's own picture: the symbol on a square around its middle, [q] local pixels per
            // picture pixel – for huge symbols coarser, so the maps stay at most 1600 wide
            val q = min(mapScale, 1600.0 / (2.5 * spot.r))
            val r = spot.r * q
            val half = r * 1.25
            val size = max(4, (2 * half).toInt() + 2)
            val local = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = local.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            // an own icon instead of the built-in symbols, as wide as they are
            if (icon != null) icon.fill(g, size / 2.0, size / 2.0, 2 * r)
            else g.fill(AffineTransform.getTranslateInstance(size / 2.0, size / 2.0).createTransformedShape(symbol(spot.kind, r)))
            g.dispose()
            val cover = (local.raster.dataBuffer as DataBufferInt).data
            var a = FloatArray(size * size) { (cover[it] ushr 24) / 255f }
            // the surface: a dome over the symbol (0 at its edge, about 1 where it is fattest), with a
            // crisper rim, dented by foil crinkles
            val height = Blur.gauss(a, size, size, max(1.0, r * 0.3))
            val fineSigma = max(0.7, r * 0.05)
            val fine = Blur.gauss(a, size, size, fineSigma)
            var peak = 1e-6f
            for (b in height) if (b > peak) peak = b
            val creases = com.spielgrund.glitchr.image.Noise(seed + si * 7919L)
            val creaseAngle = creaseAngles[si]
            val creaseScale = max(2.0, r * crinkleSize)
            val tilt = kotlin.math.tan(crinkleAngle)
            // how deep each crinkle dents the surface (0..1): folds pulling in from the edge across the
            // balloon, as on an inflatable – lined up along the edge, each running inwards (tilted by
            // [crinkleAngle]) and fading out at its own length
            val dent = FloatArray(size * size)
            // the distance to the edge in pixels, and the deepest one: thin and fat parts alike
            val edge = edgeDistance(a, size)
            // the same, softened: the measured distance runs in octagons, the rounding should not
            val edgeSoft = Blur.gauss(edge, size, size, max(1.0, fineSigma * 0.6))
            var deepest = 1f
            for (d in edge) if (d > deepest) deepest = d
            if (crinkle > 0) parallelRows(size) { y ->
                for (x in 0 until size) {
                    val j = y * size + x
                    if (a[j] <= 0f) continue
                    // how far into the balloon: 0 at the edge, 1 deep inside
                    val inward = (edge[j] / deepest).toDouble()
                    // position along the edge, in folds: the arc length around the symbol's middle – it runs on
                    // along round edges as well as along straight ones pointing outwards
                    val px = x - size / 2.0
                    val py = y - size / 2.0
                    val along = kotlin.math.atan2(py, px) * kotlin.math.hypot(px, py) / creaseScale
                    // the slant: the further in, the further along the edge the fold has moved
                    val base = along + tilt * edge[j] / creaseScale
                    // not ruler straight: each fold sways a little as it runs in
                    val f = base + creases.perlin(base * 0.35, inward * 2.5 + creaseAngle) * 0.55
                    // a soft valley with a crisp bottom
                    val fold = (1 - kotlin.math.abs(sin(PI * f))).pow(2.2)
                    // every fold reaches in by its own length
                    val length = crinkleReach * (0.45 + 0.55 * (creases.perlin(kotlin.math.floor(f) * 1.73 + 0.5, 3.1) + 1) / 2)
                    val t = (1 - inward / length.coerceAtLeast(0.02)).coerceIn(0.0, 1.0)
                    dent[j] = (fold * t * t * (3 - 2 * t)).toFloat()
                }
            }
            // where a fold meets the outline it pulls the outline in: the edge moves inwards there
            // (measured on the softened coverage, so the new edge stays smooth and anti-aliased)
            val notch = FloatArray(size * size) { (crinkle * crinklePull * 0.42 * dent[it]).toFloat() }
            if (crinkle > 0 && crinklePull > 0) {
                val pulled = FloatArray(size * size)
                for (y in 0 until size) for (x in 0 until size) {
                    val j = y * size + x
                    if (a[j] <= 0f || notch[j] <= 0f) { pulled[j] = a[j]; continue }
                    fun fAt(xx: Int, yy: Int) = fine[yy.coerceIn(0, size - 1) * size + xx.coerceIn(0, size - 1)]
                    val gx = (fAt(x + 1, y) - fAt(x - 1, y)) / 2
                    val gy = (fAt(x, y + 1) - fAt(x, y - 1)) / 2
                    val slope = max(1e-4f, sqrt(gx * gx + gy * gy))
                    val inside = ((fine[j] - 0.5f - notch[j]) / slope + 0.5f).coerceIn(0f, 1f)
                    val weight = min(1f, dent[j] * 4)
                    pulled[j] = min(a[j], 1 - weight * (1 - inside))
                }
                a = pulled
            }
            val surface = FloatArray(size * size) { j ->
                if (a[j] <= 0f) return@FloatArray 0f
                // round to nothing at the (pulled-in) edge, like the cross-section of a puffed-up balloon; domed inside
                // by the true distance to the edge, so thin lines get a low rounding instead of none
                val rim = sqrt((edgeSoft[j] / (2.5 * fineSigma) - 2 * notch[j]).coerceIn(0.0, 1.0))
                val dome = rim * (0.45 + 0.55 * sqrt(height[j] / peak.toDouble()))
                (dome - crinkle * 0.22 * dent[j]).coerceAtLeast(0.0).toFloat()
            }.let { raw -> if (crinkle > 0) Blur.gauss(raw, size, size, max(0.6, r * 0.008)) else raw }
            val gain = r * 0.6 * (0.4 + depth)
            fun hAt(x: Int, y: Int): Float = surface[y.coerceIn(0, size - 1) * size + x.coerceIn(0, size - 1)]
            if (thickness > 0) {
                bodies[si] = solid(spot.x, spot.y, q, half, size, a, surface, r * thickness * 0.75, zTurn, w, h, ::rotate, ::unrotate, ::shade)
                continue
            }
            // the plate's axes on screen
            val u = DoubleArray(3).also { rotate(1.0, 0.0, 0.0, zTurn, it) }
            val vv = DoubleArray(3).also { rotate(0.0, 1.0, 0.0, zTurn, it) }
            val det = u[0] * vv[1] - vv[0] * u[1]
            // seen exactly edge-on: nothing to see
            if (kotlin.math.abs(det) < 1e-3) continue
            val reach = half * 1.5 / q
            val coverage = a
            bodies[si] = Body(
                max(0, (spot.x - reach).toInt()), max(0, (spot.y - reach).toInt()),
                min(w - 1, (spot.x + reach).toInt()), min(h - 1, (spot.y + reach).toInt()),
            ) { sx, sy ->
                // which point of the plate lies here: solve screen = u · lx + v · ly (in local pixels)
                val dx = (sx - spot.x) * q
                val dy = (sy - spot.y) * q
                val lx = (dx * vv[1] - vv[0] * dy) / det
                val ly = (u[0] * dy - dx * u[1]) / det
                val fx = lx + size / 2.0
                val fy = ly + size / 2.0
                if (fx < 0 || fy < 0 || fx >= size - 1 || fy >= size - 1) return@Body 0
                val ix = fx.toInt()
                val iy = fy.toInt()
                val tx = (fx - ix).toFloat()
                val ty = (fy - iy).toFloat()
                val i0 = iy * size + ix
                val alpha = (coverage[i0] * (1 - tx) + coverage[i0 + 1] * tx) * (1 - ty) + (coverage[i0 + size] * (1 - tx) + coverage[i0 + size + 1] * tx) * ty
                if (alpha <= 0.002f) return@Body 0
                // the plate's normal there, turned into space with the plate
                val nrm = DoubleArray(3)
                val nx = -(hAt(ix + 1, iy) - hAt(ix - 1, iy)) * gain
                val ny = -(hAt(ix, iy + 1) - hAt(ix, iy - 1)) * gain
                rotate(nx, ny, 1.0, zTurn, nrm)
                // from behind we see the other face
                if (nrm[2] < 0) { nrm[0] = -nrm[0]; nrm[1] = -nrm[1]; nrm[2] = -nrm[2] }
                val len = sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2])
                ((alpha * 255 + 0.5f).toInt() shl 24) or shade(nrm[0] / len, nrm[1] / len, nrm[2] / len)
            }
        }

        /**
         * The metal at the point (sx, sy) of the picture, premultiplied into [acc] (r, g, b, alpha):
         * the symbols from front to back – later ones lie over earlier ones – until it is covered.
         */
        fun at(sx: Double, sy: Double, acc: DoubleArray) {
            var cr = 0.0; var cg = 0.0; var cb = 0.0; var ca = 0.0
            for (bi in bodies.indices.reversed()) {
                val b = bodies[bi] ?: continue
                if (sx < b.x0 || sx > b.x1 + 1 || sy < b.y0 || sy > b.y1 + 1) continue
                val c = b.sample(sx, sy)
                val a = (c ushr 24) / 255.0
                if (a <= 0) continue
                val k0 = (1 - ca) * a
                cr += k0 * (c shr 16 and 0xFF); cg += k0 * (c shr 8 and 0xFF); cb += k0 * (c and 0xFF)
                ca += k0
                if (ca > 0.997) break
            }
            acc[0] += cr; acc[1] += cg; acc[2] += cb; acc[3] += ca
        }
        fun pack(acc: DoubleArray, n: Int): Int {
            val a = acc[3] / n
            if (a <= 0.001) return 0
            fun ch(v: Double) = (v / n / a + 0.5).toInt().coerceIn(0, 255)
            return ((a * 255 + 0.5).toInt().coerceIn(0, 255) shl 24) or (ch(acc[0]) shl 16) or (ch(acc[1]) shl 8) or ch(acc[2])
        }
        // one sample in the middle of every pixel …
        val metal = IntArray(w * h)
        parallelRows(h) { y ->
            val acc = DoubleArray(4)
            for (x in 0 until w) {
                acc.fill(0.0)
                at(x + 0.5, y + 0.5, acc)
                metal[y * w + x] = pack(acc, 1)
            }
        }
        // … and k × k where the picture changes sharply: at edges, the horizon line, crinkles
        if (k > 1) {
            val first = metal.copyOf()
            fun differs(c: Int, d: Int): Boolean {
                if (abs((c ushr 24) - (d ushr 24)) > 12) return true
                return abs((c shr 16 and 0xFF) - (d shr 16 and 0xFF)) > 20 || abs((c shr 8 and 0xFF) - (d shr 8 and 0xFF)) > 20 || abs((c and 0xFF) - (d and 0xFF)) > 20
            }
            parallelRows(h) { y ->
                val acc = DoubleArray(4)
                for (x in 0 until w) {
                    val c = first[y * w + x]
                    val partial = (c ushr 24) in 1..254
                    var rough = partial
                    if (!rough) for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= h) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue
                            if (differs(c, first[yy * w + xx])) { rough = true; break }
                        }
                        if (rough) break
                    }
                    if (!rough) continue
                    acc.fill(0.0)
                    for (j in 0 until k) for (i in 0 until k) at(x + (i + 0.5) / k, y + (j + 0.5) / k, acc)
                    metal[y * w + x] = pack(acc, k * k)
                }
            }
        }

        // contrast around the middle grey and brightness of the metal, not of the picture below – on its
        // brightness, so the metal keeps its tint
        val contrastGain = if (contrast >= 0) 1 + contrast * 2 else 1 + contrast
        val lift = brightness * 128
        fun adjust(c: Int): Int {
            if (contrast == 0.0 && brightness == 0.0) return c
            val luma = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000.0
            val shift = (luma - 128) * contrastGain + 128 + lift - luma
            fun ch(sh: Int) = ((c shr sh and 0xFF) + shift).toInt().coerceIn(0, 255) shl sh
            return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
        }

        // the metal laid over the picture
        val out = src.copy()
        val sw = src.width
        parallelRows(src.height) { y ->
            for (x in 0 until sw) {
                val c = adjust(metal[y * sw + x])
                val ca = (c ushr 24) / 255f
                if (ca <= 0f) continue
                val i = y * sw + x
                val base = out.data[i]
                fun mix(t: Int, b: Int) = (b + (t - b) * ca + 0.5f).toInt()
                out.data[i] = argb(
                    max(base ushr 24, c ushr 24), mix(c shr 16 and 0xFF, base shr 16 and 0xFF),
                    mix(c shr 8 and 0xFF, base shr 8 and 0xFF), mix(c and 0xFF, base and 0xFF),
                )
            }
        }
        return out
    }
}

/**
 * How far every covered pixel of [cover] (a [size] square) lies from the nearest uncovered one,
 * in pixels – a two-pass chamfer distance, close enough to the true distance.
 */
private fun edgeDistance(cover: FloatArray, size: Int): FloatArray {
    val far = 1e9f
    val d = FloatArray(size * size) { if (cover[it] >= 0.5f) far else 0f }
    val diag = 1.4142135f
    for (y in 0 until size) for (x in 0 until size) {
        val j = y * size + x
        if (d[j] == 0f) continue
        var m = d[j]
        if (x > 0) m = min(m, d[j - 1] + 1)
        if (y > 0) {
            m = min(m, d[j - size] + 1)
            if (x > 0) m = min(m, d[j - size - 1] + diag)
            if (x < size - 1) m = min(m, d[j - size + 1] + diag)
        }
        // the square's own border counts as outside
        d[j] = min(m, (min(min(x, y), min(size - 1 - x, size - 1 - y)) + 1).toFloat())
    }
    for (y in size - 1 downTo 0) for (x in size - 1 downTo 0) {
        val j = y * size + x
        if (d[j] == 0f) continue
        var m = d[j]
        if (x < size - 1) m = min(m, d[j + 1] + 1)
        if (y < size - 1) {
            m = min(m, d[j + size] + 1)
            if (x < size - 1) m = min(m, d[j + size + 1] + diag)
            if (x > 0) m = min(m, d[j + size - 1] + diag)
        }
        d[j] = m
    }
    return d
}

/** A symbol as seen in the picture: within its box ([x0], [y0])–([x1], [y1]), its color at any point (ARGB, 0 where it isn't). */
private class Body(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val sample: (Double, Double) -> Int)

/**
 * A symbol as a solid body, puffed up like a foil balloon: its surface [surface] (0..1 over a
 * [size] square around its middle, [q] local pixels per picture pixel) gives the thickness to
 * both sides, at most [thick] local pixels, running round to nothing at the edge. Turned by
 * [rotate] / [unrotate], each sample sends a ray through the body and shades the surface it
 * meets first.
 */
private fun solid(
    cx: Double, cy: Double, q: Double, half: Double, size: Int, cover: FloatArray, surface: FloatArray, thick: Double, zTurn: Double,
    w: Int, h: Int,
    rotate: (Double, Double, Double, Double, DoubleArray) -> Unit,
    unrotate: (Double, Double, Double, Double, DoubleArray) -> Unit,
    shade: (Double, Double, Double) -> Int,
): Body {
    /** The thickness to one side at the plate's own point (x, y) – bilinear, 0 outside. */
    fun depthAt(x: Double, y: Double): Double {
        val fx = x + size / 2.0
        val fy = y + size / 2.0
        if (fx < 0 || fy < 0 || fx >= size - 1 || fy >= size - 1) return -1.0
        val ix = fx.toInt()
        val iy = fy.toInt()
        val tx = fx - ix
        val ty = fy - iy
        val i0 = iy * size + ix
        val cov = (cover[i0] * (1 - tx) + cover[i0 + 1] * tx) * (1 - ty) + (cover[i0 + size] * (1 - tx) + cover[i0 + size + 1] * tx) * ty
        if (cov < 0.5) return -1.0
        val s = (surface[i0] * (1 - tx) + surface[i0 + 1] * tx) * (1 - ty) + (surface[i0 + size] * (1 - tx) + surface[i0 + size + 1] * tx) * ty
        return s * thick
    }
    val reach = half * 1.45 + thick
    // a ray straight into the picture, in the plate's frame: start + z · dir
    val dir = DoubleArray(3).also { unrotate(0.0, 0.0, 1.0, zTurn, it) }
    return Body(
        max(0, (cx - reach / q).toInt()), max(0, (cy - reach / q).toInt()),
        min(w - 1, (cx + reach / q).toInt()), min(h - 1, (cy + reach / q).toInt()),
    ) { sx, sy ->
        val start = DoubleArray(3)
        unrotate((sx - cx) * q, (sy - cy) * q, 0.0, zTurn, start)
        fun inside(z: Double): Boolean {
            val ox = start[0] + z * dir[0]
            val oy = start[1] + z * dir[1]
            val oz = start[2] + z * dir[2]
            val d = depthAt(ox, oy)
            return d >= 0 && kotlin.math.abs(oz) <= d
        }
        // only the stretch of the ray inside the body's box can meet it: |z| <= thick in the plate's
        // frame, and within the square the symbol was drawn on
        var near = reach
        var far = -reach
        var empty = false
        fun clip(origin: Double, d: Double, lo: Double, hi: Double) {
            if (kotlin.math.abs(d) < 1e-9) {
                if (origin < lo || origin > hi) empty = true
                return
            }
            val ta = (lo - origin) / d
            val tb = (hi - origin) / d
            near = min(near, max(ta, tb))
            far = max(far, min(ta, tb))
        }
        val edgeLo = -size / 2.0
        val edgeHi = size / 2.0 - 1
        clip(start[2], dir[2], -thick, thick)
        clip(start[0], dir[0], edgeLo, edgeHi)
        clip(start[1], dir[1], edgeLo, edgeHi)
        if (empty || near <= far) return@Body 0
        // from the front backwards, until the body is met: about 160 steps over the stretch, never
        // finer than needed – the crossing is narrowed down afterwards
        val step = max(0.75, min((near - far) / 160, thick * 0.06))
        var z = near
        var hit = Double.NaN
        while (z >= far - step) {
            if (inside(z)) {
                // narrow the crossing down
                var outside = z + step
                var inner = z
                repeat(8) {
                    val mid = (outside + inner) / 2
                    if (inside(mid)) inner = mid else outside = mid
                }
                hit = inner
                break
            }
            z -= step
        }
        if (hit.isNaN()) return@Body 0
        val ox = start[0] + hit * dir[0]
        val oy = start[1] + hit * dir[1]
        val oz = start[2] + hit * dir[2]
        // the surface's slope there: the body is |z| <= depth(x, y)
        val e = 1.0
        val dx = (depthAt(ox + e, oy).coerceAtLeast(0.0) - depthAt(ox - e, oy).coerceAtLeast(0.0)) / (2 * e)
        val dy = (depthAt(ox, oy + e).coerceAtLeast(0.0) - depthAt(ox, oy - e).coerceAtLeast(0.0)) / (2 * e)
        val n = DoubleArray(3)
        rotate(-dx, -dy, if (oz >= 0) 1.0 else -1.0, zTurn, n)
        if (n[2] < 0) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2] }
        val len = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        0xFF000000.toInt() or shade(n[0] / len, n[1] / len, n[2] / len)
    }
}

