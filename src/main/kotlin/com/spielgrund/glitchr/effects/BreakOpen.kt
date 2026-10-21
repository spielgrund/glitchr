package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The picture breaks open: cracks run out from a point to the edge and part it into pieces,
 * which move apart – pushed outwards, swinging around the break point, turning a little and
 * rolling up from their tip outwards like a carpet, in 3D with light and shadow – and reveal
 * what lies below (the layers underneath, a color or the darkened original). The
 * cracks are evenly spaced and straight, or random – jagged like cracks or like lightning,
 * with branching hairline cracks.
 */
object BreakOpen : Effect("breakopen", "Break open", "The picture cracks from a point and its pieces move apart, revealing what lies below – even cracks, random cracks or lightning") {
    private val layouts = listOf("Even", "Random cracks", "Lightning")
    private val behinds = listOf("Transparent (layers below)", "Color", "Darkened original")

    override val params = listOf(
        Param.Slider("open", "Open", 0, 10000, 10000, " %", "0 % leaves the picture untouched; up to 100 % it breaks open as set below – animate this one to break it open", decimals = 2),
        Param.Choice(
            "layout", "Cracks", layouts, 0,
            "Even: straight crack edges, as many as set, evenly around the point · Random cracks: jagged, at random angles · Lightning: sharply zigzagging with branches",
        ),
        Param.Slider("count", "Crack edges", 1, 32, 4, tip = "How many cracks run out from the point – they part the picture into as many pieces"),
        Param.Slider("angle", "Angle", -18000, 18000, 9000, "°", "Direction of the first crack (Even)", decimals = 2),
        Param.Slider("jagged", "Jaggedness", 0, 100, 50, " %", "How much random cracks and lightning zigzag"),
        Param.Slider("branches", "Hairline cracks", 0, 100, 30, " %", "Side cracks branching off, like in glass or lightning – they open thin gaps, but don't part the pieces"),
        Param.Slider("centerX", "Center X", -500000, 500000, 0, " px", "The break point, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 2, transform = true),
        Param.Slider("centerY", "Center Y", -500000, 500000, 0, " px", decimals = 2, transform = true),
        Param.Heading("moveHeading", "Pieces"),
        Param.Slider("spread", "Opening", 0, 100000, 4000, " px", "How far the pieces move apart, outwards from the break point", decimals = 2, transform = true),
        Param.Slider("swing", "Swing", -9000, 9000, 800, "°", "The pieces swing around the break point – on curved paths, like a flower opening", decimals = 2),
        Param.Slider("vary", "Variation", 0, 100, 40, " %", "Every piece moves and swings by its own amount"),
        Param.Slider("tilt", "Piece rotation", 0, 9000, 400, "°", "Every piece also turns around its own middle, by up to ± this much", decimals = 2),
        Param.Heading("rollHeading", "Roll up"),
        Param.Slider("roll", "Roll up", 0, 100, 55, " %", "The pieces roll up from their tip at the break point outwards, like a carpet – this share of each piece"),
        Param.Slider("rollRadius", "Roll size", 1, 100, 12, " %", "Thickness of the roll, of the piece's length: small = a tight roll, large = a wide curl"),
        Param.Choice("back", "Back side", listOf("Picture, darker", "Color"), 1, "What the rolled-up underside shows"),
        Param.Color("backColor", "Back color", 0xF2EEE6),
        Param.Slider("backMix", "Back color mix", 0, 100, 0, " %", "For the picture back side: how much of the back color is mixed in"),
        Param.Slider("shadow", "Shadow", 0, 100, 50, " %", "The roll's shadow on the piece"),
        Param.Heading("edgeHeading", "Edges"),
        Param.Slider("gap", "Crack width", 0, 5000, 200, " px", "Gap along the cracks even before the pieces move", decimals = 2, transform = true),
        Param.Slider("edgeLight", "Edge light", -100, 100, 35, " %", "Light (positive) or dark (negative) rims along the broken edges"),
        Param.Slider("edgeWidth", "Edge width", 0, 4000, 400, " px", decimals = 2),
        Param.Choice("behind", "Behind", behinds, 0, "What shows where the pieces have moved away"),
        Param.Color("behindColor", "Color behind", 0x000000),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /** A crack from the break point outwards: its direction and, along it, how far it zigzags aside. */
    private class Crack(val angle: Double, val aside: DoubleArray, val step: Double) {
        /** Sideways offset in pixels at distance [r] from the break point. */
        fun offset(r: Double): Double {
            val f = r / step
            val i = f.toInt()
            if (i >= aside.size - 1) return aside.last()
            val t = f - i
            return aside[i] + (aside[i + 1] - aside[i]) * t
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val layout = v["layout"]
        val n = v["count"]
        val cx = w / 2.0 + v["centerX"] / 100.0
        val cy = h / 2.0 + v["centerY"] / 100.0
        val reach = hypot(max(cx, w - cx), max(cy, h - cy)) + 4
        val jag = v["jagged"] / 100.0
        val size = min(w, h).toDouble()

        // ---- the cracks: directions, then how they zigzag ----
        val angles = DoubleArray(n)
        if (layout == 0) {
            val first = Math.toRadians(v["angle"] / 100.0)
            for (k in 0 until n) angles[k] = first + k * 2 * PI / n
        } else {
            // random, but never too close: one in every n-th of the circle
            val turn = random.nextDouble() * 2 * PI
            for (k in 0 until n) angles[k] = turn + (k + 0.15 + random.nextDouble() * 0.7) * 2 * PI / n
        }
        val cracks = Array(n) { k ->
            // zigzag: corners every few pixels, each one aside of the last – lightning finer and wilder
            val step = when (layout) { 0 -> reach; 1 -> size * (0.03 + random.nextDouble() * 0.03); else -> size * (0.012 + random.nextDouble() * 0.015) }
            val count = (reach / step).toInt() + 2
            val amount = when (layout) { 0 -> 0.0; 1 -> 0.9; else -> 1.8 } * jag
            val aside = DoubleArray(count)
            for (i in 1 until count) aside[i] = aside[i - 1] * 0.85 + (random.nextDouble() * 2 - 1) * step * amount
            Crack(angles[k], aside, step)
        }
        // the cracks' room to zigzag: never into the next one
        val room = DoubleArray(n) { k ->
            if (n == 1) PI * 0.45 else {
                val prev = Math.floorMod(k - 1, n)
                val next = (k + 1) % n
                0.45 * min(gapAngle(angles[prev], angles[k]), gapAngle(angles[k], angles[next]))
            }
        }
        /** The direction of crack [k] at distance [r] from the break point. */
        fun crackAt(k: Int, r: Double): Double {
            val c = cracks[k]
            if (r < 1e-6) return c.angle
            return c.angle + atan(c.offset(r) / r).coerceIn(-room[k], room[k])
        }

        // how far open: everything that changes the picture grows with it
        val open = (v["open"] / 10000.0).coerceIn(0.0, 1.0)

        // ---- hairline cracks: drawn into a map of the picture ----
        val hair = if (open <= 0) FloatArray(w * h) else hairlines(w, h, cx, cy, cracks, ::crackAt, reach, v["branches"] / 100.0, layout, size, v["gap"] / 100.0 * open, random)

        // ---- the pieces and how they move ----
        val spread = v["spread"] / 100.0 * open
        val swing = Math.toRadians(v["swing"] / 100.0) * open
        val vary = v["vary"] / 100.0
        val tilt = Math.toRadians(v["tilt"] / 100.0) * open
        val pushX = DoubleArray(n)
        val pushY = DoubleArray(n)
        val turn = DoubleArray(n)
        val own = DoubleArray(n)
        val midX = DoubleArray(n)
        val midY = DoubleArray(n)
        // the roll: along the piece's middle direction (u) the front has reached [front], across it is v
        val ux = DoubleArray(n)
        val uy = DoubleArray(n)
        val front = DoubleArray(n)
        val radius = DoubleArray(n)
        val roll = v["roll"] / 100.0 * open
        for (k in 0 until n) {
            // the piece between crack k and the next one, around its middle direction
            val span = if (n == 1) 2 * PI else gapAngle(angles[k], angles[(k + 1) % n])
            val mid = angles[k] + span / 2
            val push = spread * (1 + vary * (random.nextDouble() * 2 - 1))
            pushX[k] = cos(mid) * push
            pushY[k] = sin(mid) * push
            turn[k] = swing * (1 + vary * (random.nextDouble() * 2 - 1))
            own[k] = tilt * (random.nextDouble() * 2 - 1)
            // roughly the piece's middle: halfway to the picture's edge in its direction
            val edge = edgeDistance(cx, cy, mid, w, h)
            midX[k] = cx + cos(mid) * edge * 0.5
            midY[k] = cy + sin(mid) * edge * 0.5
            ux[k] = cos(mid)
            uy[k] = sin(mid)
            front[k] = edge * roll * (1 + vary * 0.5 * (random.nextDouble() * 2 - 1))
            radius[k] = max(1.0, edge * v["rollRadius"] / 100.0)
        }

        val gap = v["gap"] / 100.0 * open
        val edgeLight = v["edgeLight"] / 100.0 * min(1.0, open * 3)
        val edgeWidth = v["edgeWidth"] / 100.0
        val behind = v["behind"]
        val behindColor = v["behindColor"]
        val back = v["back"]
        val backColor = v["backColor"]
        val backMix = (v["backMix"] / 100.0).toFloat()
        val shadow = v["shadow"] / 100.0
        // the light: from the top left, above
        val lx = -0.45; val ly = -0.55; val lz = 0.70
        val out = Pixels(w, h)

        /**
         * Whether the picture's point (tx, ty) belongs to piece [k]: how much it is covered (soft
         * edges, the crack's gap, hairline cracks) and how far inside the broken edge it lies.
         */
        fun belongs(k: Int, tx: Double, ty: Double, result: DoubleArray): Boolean {
            if (tx < 0 || ty < 0 || tx >= w || ty >= h) return false
            val rx = tx - cx
            val ry = ty - cy
            val r = hypot(rx, ry)
            val a = atan2(ry, rx)
            val lo = crackAt(k, r)
            val hi = if (n == 1) lo + 2 * PI else crackAt((k + 1) % n, r).let { if (it < lo) it + 2 * PI else it }.let { if (it - lo > 2 * PI) it - 2 * PI else it }
            var rel = a - lo
            rel -= Math.floor(rel / (2 * PI)) * 2 * PI
            val width = hi - lo
            if (rel > width) return false
            // distance to the broken edges in pixels
            val inside = min(rel, width - rel) * r - gap / 2
            // without any opening the cracks don't show at all
            val cover = if (open <= 0) 1.0 else (inside + 0.5).coerceIn(0.0, 1.0) * (1 - hair[min(h - 1, ty.toInt()) * w + min(w - 1, tx.toInt())])
            if (cover <= 0) return false
            result[0] = cover
            result[1] = inside
            return true
        }

        parallelRows(h) { y ->
            val hit = DoubleArray(2)
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                // the pieces nearest in direction first: usually the first one covers the pixel
                val dir = atan2(py - cy, px - cx)
                var first = 0
                var best = Double.MAX_VALUE
                for (k in 0 until n) {
                    val d = gapAngle(angles[k], dir)
                    if (d < best) { best = d; first = k }
                }
                var ca = 0.0; var cr = 0.0; var cg = 0.0; var cb = 0.0
                /** Lays the color [c] at coverage [cover], darkened or lit by [light], under what is already there. */
                fun add(c: Int, cover: Double, light: Double, rimFrom: Double) {
                    var rr = (c shr 16 and 0xFF) * light
                    var gg = (c shr 8 and 0xFF) * light
                    var bb = (c and 0xFF) * light
                    if (edgeLight != 0.0 && edgeWidth > 0 && rimFrom < edgeWidth) {
                        val e = edgeLight * (1 - rimFrom.coerceAtLeast(0.0) / edgeWidth)
                        if (e > 0) { rr += (255 - rr) * e; gg += (255 - gg) * e; bb += (255 - bb) * e } else { rr *= 1 + e; gg *= 1 + e; bb *= 1 + e }
                    }
                    val alpha = (c ushr 24) / 255.0 * cover * (1 - ca)
                    cr += rr.coerceIn(0.0, 255.0) * alpha; cg += gg.coerceIn(0.0, 255.0) * alpha; cb += bb.coerceIn(0.0, 255.0) * alpha
                    ca += alpha
                }
                for (step in 0 until n) {
                    // first, then its neighbours to both sides
                    val k = Math.floorMod(first + (if (step % 2 == 1) -(step + 1) / 2 else step / 2), n)
                    // back from where the piece has moved to where it came from
                    var qx = px - pushX[k] - cx
                    var qy = py - pushY[k] - cy
                    val c1 = cos(-turn[k]); val s1 = sin(-turn[k])
                    var tx = qx * c1 - qy * s1
                    var ty = qx * s1 + qy * c1
                    qx = tx + cx - midX[k]
                    qy = ty + cy - midY[k]
                    val c2 = cos(-own[k]); val s2 = sin(-own[k])
                    tx = qx * c2 - qy * s2 + midX[k]
                    ty = qx * s2 + qy * c2 + midY[k]
                    // along the piece's direction and across it
                    val along = (tx - cx) * ux[k] + (ty - cy) * uy[k]
                    val across = -(tx - cx) * uy[k] + (ty - cy) * ux[k]
                    val f = front[k]
                    val rad = radius[k]
                    // the roll on top: where the lifted part of the piece lies above this point, the highest
                    // layer shows – rising, its picture side; over the top, its underside
                    if (f > 0 && abs(along - f) < rad) {
                        val q = ((f - along) / rad).coerceIn(-1.0, 1.0)
                        val a1 = kotlin.math.asin(q)
                        // every place on the roll above this point that has rolled-up material: the highest
                        // first, of equal height the outer winding (the one rolled up last)
                        val layers = ArrayList<Double>(4)
                        for (start in doubleArrayOf(a1, PI - a1)) {
                            var bb = start
                            while (bb < 0) bb += 2 * PI
                            while (bb <= f / rad + 1e-9) { layers += bb; bb += 2 * PI }
                        }
                        layers.sortWith(compareByDescending<Double> { Math.round((1 - cos(it)) * 1e6) }.thenBy { it })
                        // towards its tip a piece gets narrow: where a layer has no material here, the one below shows
                        for (beta in layers) {
                            val height = 1 - cos(beta)
                            val from = f - rad * beta
                            // a little perspective: lifted parts are nearer to the eye, so they look wider
                            val lifted = across / (1 + height * rad * 0.0035)
                            val sx = cx + ux[k] * from - uy[k] * lifted
                            val sy = cy + uy[k] * from + ux[k] * lifted
                            if (!belongs(k, sx, sy, hit)) continue
                            // over the top the underside faces up, rising the picture side faces up
                            val over = cos(beta) < 0
                            val sign = if (over) -1.0 else 1.0
                            val nu = sign * sin(beta)
                            val nz = sign * cos(beta)
                            val dot = (ux[k] * nu * lx + uy[k] * nu * ly + nz * lz).coerceAtLeast(0.0)
                            val light = 0.3 + 0.8 * dot + 0.35 * dot.pow(24)
                            val c = when {
                                !over -> sample(src, sx, sy)
                                back == 1 -> backColor or 0xFF000000.toInt()
                                // the picture back side, with the back color mixed in
                                else -> sample(src, sx, sy).let { p -> com.spielgrund.glitchr.image.lerpArgb(p, (p and 0xFF000000.toInt()) or backColor, backMix) }
                            }
                            val edgeSoft = (rad - abs(along - f) + 0.5).coerceIn(0.0, 1.0)
                            add(c, hit[0] * edgeSoft, if (over && back == 0) light * 0.7 else light, Double.MAX_VALUE)
                            if (ca > 0.996) break
                        }
                        if (ca > 0.996) break
                    }
                    // the piece lying flat, beyond the roll
                    if (f <= 0 || along > f) {
                        if (!belongs(k, tx, ty, hit)) continue
                        // the roll's shadow just beyond it
                        val shade = if (f > 0) 1 - shadow * 0.6 * (1 - ((along - f) / (rad * 1.6)).coerceIn(0.0, 1.0)).let { it * it * (3 - 2 * it) } else 1.0
                        add(sample(src, tx, ty), hit[0], shade, hit[1])
                        if (ca > 0.996) break
                    }
                }
                // what lies behind
                val i = y * w + x
                val under = when (behind) {
                    1 -> behindColor or 0xFF000000.toInt()
                    2 -> src.data[i].let { c -> (c and 0xFF000000.toInt()) or (((c shr 16 and 0xFF) * 35 / 100) shl 16) or (((c shr 8 and 0xFF) * 35 / 100) shl 8) or ((c and 0xFF) * 35 / 100) }
                    else -> 0
                }
                val ba = (under ushr 24) / 255.0 * (1 - ca)
                cr += (under shr 16 and 0xFF) * ba; cg += (under shr 8 and 0xFF) * ba; cb += (under and 0xFF) * ba
                ca += ba
                out.data[i] = if (ca <= 0.001) 0 else (((ca * 255 + 0.5).toInt().coerceIn(0, 255)) shl 24) or
                    ((cr / ca + 0.5).toInt().coerceIn(0, 255) shl 16) or ((cg / ca + 0.5).toInt().coerceIn(0, 255) shl 8) or (cb / ca + 0.5).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** The angle from [a] counter-clockwise to [b], 0 until 2π. */
    private fun gapAngle(a: Double, b: Double): Double {
        val d = b - a
        return d - Math.floor(d / (2 * PI)) * 2 * PI
    }

    /** How far from (cx, cy) in direction [angle] the picture's edge lies. */
    private fun edgeDistance(cx: Double, cy: Double, angle: Double, w: Int, h: Int): Double {
        val dx = cos(angle)
        val dy = sin(angle)
        var t = Double.MAX_VALUE
        if (dx > 1e-9) t = min(t, (w - cx) / dx)
        if (dx < -1e-9) t = min(t, -cx / dx)
        if (dy > 1e-9) t = min(t, (h - cy) / dy)
        if (dy < -1e-9) t = min(t, -cy / dy)
        return t.coerceAtLeast(0.0)
    }

    /** Bilinear sample of [src] at (x, y), the pixel centres at .5. */
    private fun sample(src: Pixels, x: Double, y: Double): Int {
        val fx = x - 0.5
        val fy = y - 0.5
        val x0 = Math.floor(fx).toInt()
        val y0 = Math.floor(fy).toInt()
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        fun at(xx: Int, yy: Int) = src.data[yy.coerceIn(0, src.height - 1) * src.width + xx.coerceIn(0, src.width - 1)]
        val a = com.spielgrund.glitchr.image.lerpArgb(at(x0, y0), at(x0 + 1, y0), tx)
        val b = com.spielgrund.glitchr.image.lerpArgb(at(x0, y0 + 1), at(x0 + 1, y0 + 1), tx)
        return com.spielgrund.glitchr.image.lerpArgb(a, b, ty)
    }

    /**
     * Hairline cracks branching off the main cracks ([amount] 0..1), zigzagging outwards – for
     * lightning longer and branching again. As a map of the picture: 0 none, 1 crack.
     */
    private fun hairlines(
        w: Int, h: Int, cx: Double, cy: Double, cracks: Array<Crack>, crackAt: (Int, Double) -> Double,
        reach: Double, amount: Double, layout: Int, size: Double, gap: Double, random: Random,
    ): FloatArray {
        val map = FloatArray(w * h)
        if (amount <= 0) return map
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color.WHITE
        val lightning = layout == 2
        fun branch(x: Double, y: Double, heading: Double, length: Double, width: Float, depth: Int) {
            val p = Path2D.Double()
            p.moveTo(x, y)
            var px = x
            var py = y
            var dir = heading
            val step = size * (if (lightning) 0.015 else 0.025)
            var walked = 0.0
            while (walked < length) {
                dir += (random.nextDouble() * 2 - 1) * (if (lightning) 0.9 else 0.5)
                // keep the general direction
                dir += (heading - dir) * 0.35
                px += cos(dir) * step
                py += sin(dir) * step
                p.lineTo(px, py)
                walked += step
                if (depth > 0 && random.nextDouble() < (if (lightning) 0.12 else 0.05)) {
                    branch(px, py, dir + (if (random.nextBoolean()) 1 else -1) * (0.4 + random.nextDouble() * 0.6), (length - walked) * 0.6, width * 0.7f, depth - 1)
                }
            }
            g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(p)
        }
        val perCrack = max(1, Math.round(amount * (if (lightning) 9 else 5)).toInt())
        for (k in cracks.indices) repeat(perCrack) {
            // from a point on the crack, off to one side and outwards
            val r = reach * (0.08 + random.nextDouble() * 0.6)
            val a = crackAt(k, r)
            val side = if (random.nextBoolean()) 1 else -1
            val heading = a + side * (0.35 + random.nextDouble() * 0.7)
            branch(cx + cos(a) * r, cy + sin(a) * r, heading, size * (0.08 + random.nextDouble() * (if (lightning) 0.35 else 0.2)), max(1f, (gap * 0.6 + 1).toFloat()), if (lightning) 2 else 1)
        }
        g.dispose()
        val raster = img.raster
        for (y in 0 until h) for (x in 0 until w) map[y * w + x] = raster.getSample(x, y, 0) / 255f
        return map
    }
}
