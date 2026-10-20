package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.geom.AffineTransform
import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tribal ornaments, always one connected piece: a trunk sweeps through the middle as an
 * S-curve, and every further stroke grows out of a stroke already there – as wide as its
 * host where it leaves it, running out into a sharp point – so nothing floats. Mirrored, the
 * two halves interlace. The thorns come in odd numbers: one point at each end of the trunk
 * on the middle line, all others in mirrored pairs, and hooks on a stroke one, three or five
 * at a time.
 *
 * Styles: bold neo-tribal blades as in the 90s and 2000s, thin thorny cyber sigilism as in
 * today's techno and gothic scene, Polynesian curves rolling into spirals, flames – as a
 * centrepiece, a tall armband or a mirrored pair – or Polynesian bands of shark teeth,
 * diamonds, waves and spearheads. Generated from a seed, or along arrows drawn on the canvas.
 */
object Tribal : Effect("tribal", "Tribal", "Tribal ornaments in one piece – 90s neo-tribal, thorny cyber sigilism, Polynesian spirals, flames or Polynesian bands; generated or drawn") {
    private val styles = listOf("Neo-tribal (bold, 90s)", "Cyber sigilism (thin, thorny)", "Polynesian spirals", "Flames", "Polynesian bands")
    private const val CYBER = 1
    private const val POLY = 2
    private const val FLAME = 3
    private const val BANDS = 4
    private val patterns = listOf("Mixed", "Shark teeth", "Diamonds", "Waves", "Spearheads", "Nested triangles", "Long diamonds", "Small triangles")

    override val params = listOf(
        Param.Choice("style", "Style", styles, 0),
        Param.Choice("shape", "Shape", listOf("Centrepiece", "Tall (armband)", "Mirrored pair"), 1, "Generated ornaments: one piece in the middle, a tall upright one, or two mirrored side pieces"),
        Param.Choice("source", "Shape from", listOf("Generated", "Drawn arrows"), 0, "Drawn arrows: draw in the picture; every arrow becomes a stroke"),
        Param.Flow("strokes", "Arrows", "Dragging in the picture draws a stroke the ornament follows"),
        Param.Toggle("mirror", "Mirror", true, "Symmetric to both sides of the middle"),
        Param.Slider("count", "Branches", 1, 40, 9, tip = "Strokes growing out of the trunk"),
        Param.Slider("levels", "Branch levels", 1, 4, 1, tip = "Branches growing out of branches: more levels make wider, more sweeping shapes"),
        Param.Slider("sub", "Sub-branches", 1, 4, 2, tip = "How many branches grow out of each branch on the next level"),
        Param.Slider("size", "Size", 10, 200, 90, " %"),
        Param.Slider("thickness", "Thickness", 5, 300, 100, " %"),
        Param.Slider("barbs", "Barbs", 0, 100, 40, " %", "Hooks and thorns on the strokes – always one, three or five"),
        Param.Slider("curl", "Curl", 0, 200, 100, " %", "How much the strokes bend"),
        Param.Slider("spread", "Spread", 0, 100, 50, " %", "How far the strokes reach out from the middle"),
        Param.Slider("rot1", "Level 1 rotation", -18000, 18000, 0, "°", "Turns the branches of the first level (outwards positive; mirrored pieces stay symmetric)", decimals = 2),
        Param.Slider("rot2", "Level 2 rotation", -18000, 18000, 0, "°", decimals = 2),
        Param.Slider("rot3", "Level 3 rotation", -18000, 18000, 0, "°", decimals = 2),
        Param.Slider("rot4", "Level 4 rotation", -18000, 18000, 0, "°", decimals = 2),
        Param.Color("color", "Color", 0x000000),
        Param.Heading("placeHeading", "Placement"),
        Param.Toggle("fit", "Fit to canvas", true, "Scales and centres the finished ornament so it fits completely onto the canvas"),
        Param.Slider("posX", "Position X", -1000, 1000, 0, " %", "Moves the ornament, from the middle of the canvas", decimals = 1),
        Param.Slider("posY", "Position Y", -1000, 1000, 0, " %", decimals = 1),
        Param.Slider("scale", "Scale", 5, 500, 100, " %", "Of the ornament, independent of the canvas"),
        Param.Toggle("transparent", "Transparent background", false, "Only the ornament, without the base color"),
        Param.Heading("bandsHeading", "Polynesian bands"),
        Param.Choice("pattern", "Pattern", patterns, 0),
        Param.Slider("bands", "Bands", 1, 24, 8, tip = "Bands side by side"),
        Param.Slider("gap", "Gap", 0, 100, 25, " %", "Space between the bands"),
    )

    /** A stroke: its points, its widest width and the width profile along it (0..1). */
    private class Stroke(val xs: DoubleArray, val ys: DoubleArray, val width: Double, val profile: (Double) -> Double) {
        fun widthAt(t: Double) = width * profile(t)
    }

    // width profiles: a blade pointed at both ends; a branch, as wide as its host where it leaves it and running to a point;
    // a thorn, broad at its foot; a band rolling into a spiral; a flame
    private val blade = { t: Double -> sin(PI * t.pow(0.8)).pow(0.65) }
    private val branch = { t: Double -> (1 - t).pow(0.85) * (1 + 0.35 * sin(PI * t)) }
    private val thorn = { t: Double -> (1 - t).pow(1.15) }
    private val spiral = { t: Double -> (1 - 0.8 * t) }
    private val flame = { t: Double -> (1 - t).pow(0.7) * (1 + 0.5 * sin(PI * t)) }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        if (v["style"] == BANDS) return bands(src, v, seed)
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val random = Random(seed)
        val style = v["style"]
        val shape = v["shape"]
        val mirror = v.bool("mirror")
        val pair = shape == 2 && v["source"] == 0
        // one piece: around the middle, or – for a pair – around a third of the width
        val pieceX = if (pair) w * 0.32 else w / 2
        // a pair has two pieces side by side: each one narrower
        val size = min(w, h) * v["size"] / 100.0 * (if (pair) 0.62 else if (shape == 1) 1.0 else 0.8)
        val height = if (shape == 0) size else min(h * 0.92, size * 1.9)
        val thick = v["thickness"] / 100.0
        val barbs = v["barbs"] / 100.0
        val curl = v["curl"] / 100.0
        val spread = v["spread"] / 100.0
        val strokes = ArrayList<Stroke>()
        val baseWidth = size * thick * when (style) {
            CYBER -> 0.03
            POLY -> 0.075
            FLAME -> 0.09
            else -> 0.1
        }

        /** A curve from (x, y) heading [angle], [length] long, bending by [bend] (radians per pixel) at share t. */
        fun walk(x: Double, y: Double, angle: Double, length: Double, bend: (Double) -> Double): Pair<DoubleArray, DoubleArray> {
            val steps = max(10, (length / 3).toInt())
            val xs = DoubleArray(steps + 1)
            val ys = DoubleArray(steps + 1)
            var px = x; var py = y; var a = angle
            val step = length / steps
            for (i in 0..steps) {
                xs[i] = px; ys[i] = py
                a += bend(i / steps.toDouble()) * step
                px += cos(a) * step
                py += sin(a) * step
            }
            return xs to ys
        }

        fun direction(s: Stroke, i: Int): Double {
            val a = max(0, i - 1)
            val b = min(s.xs.size - 1, i + 1)
            return atan2(s.ys[b] - s.ys[a], s.xs[b] - s.xs[a])
        }

        /** Hooks off a stroke – an odd number – broad at their foot, curling and pointed. */
        fun hooks(host: Stroke, side: Double) {
            val count = when {
                barbs <= 0 -> 0
                barbs < 0.34 -> 1
                barbs < 0.67 -> 3
                else -> 5
            }
            for (k in 0 until count) {
                val t = 0.2 + 0.6 * (k + 0.5) / count
                val i = (t * (host.xs.size - 1)).toInt().coerceIn(1, host.xs.size - 2)
                // alternate sides; the middle one (odd count) to the stroke's own side
                val s = if (k == count / 2 || k % 2 == 0) side else -side
                val a = direction(host, i) + s * (0.55 + random.nextDouble() * 0.35)
                val len = size * (if (style == CYBER) 0.08 else 0.16) * (0.7 + random.nextDouble() * 0.6)
                val (bx, by) = walk(host.xs[i], host.ys[i], a, len) { -s * curl * 2.0 / size }
                strokes += Stroke(bx, by, max(host.widthAt(t), host.width * 0.5) * (if (style == CYBER) 1.6 else 0.9), thorn)
            }
        }

        if (v["source"] == 1) {
            // along the drawn arrows: smoothed, each one stroke of the style, with hooks
            for (drawn in FlowStrokes.parse(v.text("strokes"))) {
                var pts = drawn.map { it.x * w to it.y * h }
                repeat(2) { pts = chaikin(pts) }
                val s = Stroke(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), baseWidth, if (style == POLY) spiral else blade)
                strokes += s
                hooks(s, if (random.nextBoolean()) 1.0 else -1.0)
            }
        } else {
            // the trunk: an S through the middle line, pointed at both ends (the two middle thorns)
            val top = h / 2 - height / 2
            val sway = size * (0.08 + 0.18 * spread)
            val n = 80
            val tx = DoubleArray(n + 1) { i -> pieceX + sway * sin(i / n.toDouble() * 2 * PI) * (if (mirror && !pair) 1.0 else 0.6) }
            val ty = DoubleArray(n + 1) { i -> top + height * i / n }
            val trunk = Stroke(tx, ty, baseWidth * (if (style == CYBER) 1.4 else 1.1), blade)
            strokes += trunk
            hooks(trunk, 1.0)
            /**
             * A branch growing out of [host]: as wide as the host where it leaves it. Deeper
             * [level]s are shorter and lean further out, so the piece spreads wider.
             */
            fun grow(host: Stroke, level: Int): Pair<Stroke, Double> {
                // sub-branches leave their host further out (so the piece spreads), but before it gets thin
                val t = if (level == 0) 0.12 + random.nextDouble() * 0.76 else 0.3 + random.nextDouble() * 0.35
                val i = (t * (host.xs.size - 1)).toInt().coerceIn(1, host.xs.size - 2)
                val x0 = host.xs[i]
                val y0 = host.ys[i]
                // outwards from the middle line, upwards or downwards along the host
                val outward = if (x0 >= pieceX) 1.0 else -1.0
                val along = direction(host, i)
                val up = if (random.nextBoolean()) 1.0 else -1.0
                val lean = 1 + 0.6 * level
                val levelTurn = Math.toRadians(v["rot${(level + 1).coerceAtMost(4)}"] / 100.0) * outward
                // nearly along the host, up or down it, leaning a little outwards: long sweeping blades
                val heading = if (up > 0) along else along + PI
                val angle = when (style) {
                    FLAME -> -PI / 2 + outward * (0.25 + random.nextDouble() * 0.6) * (0.4 + spread) * lean
                    else -> heading + outward * up * (0.15 + random.nextDouble() * 0.35) * (0.5 + spread) * lean
                } + levelTurn
                val length = size * (0.45 + random.nextDouble() * 0.45) * (0.6 + spread * 0.8) * 0.72.pow(level)
                // curling back towards the middle, like the sweeping tribal blades
                val bendSign = -outward * up
                val k0 = curl * 2.6 / size / 0.8.pow(level)
                val (xs, ys) = when (style) {
                    POLY -> walk(x0, y0, angle, length * 1.2) { tt -> bendSign * k0 * (0.2 + 9 * tt.pow(3)) }
                    FLAME -> {
                        val ph = random.nextDouble() * 2 * PI
                        walk(x0, y0, angle, length) { tt -> k0 * 1.2 * sin(tt * 8 + ph) }
                    }
                    CYBER -> {
                        val flip = if (random.nextBoolean()) 1.0 else -1.0
                        walk(x0, y0, angle, length * 1.2) { tt -> (if (tt < 0.5) bendSign else flip * bendSign) * k0 * 1.4 }
                    }
                    // an S: out and back, crossing the strokes it passes
                    else -> walk(x0, y0, angle, length) { tt -> bendSign * k0 * 1.3 * (0.6 - tt * 1.4) }
                }
                // as wide as the host where it leaves it, so the two grow into one
                val stroke = Stroke(xs, ys, host.widthAt(t) * 0.95, when (style) {
                    POLY -> spiral
                    FLAME -> flame
                    else -> branch
                })
                strokes += stroke
                return stroke to bendSign
            }

            // the first level: branches out of the trunk and out of each other
            val hosts = arrayListOf(trunk)
            val firstLevel = ArrayList<Stroke>()
            repeat(v["count"]) { k ->
                val (stroke, bendSign) = grow(hosts[if (k < 3) 0 else random.nextInt(hosts.size)], 0)
                hosts += stroke
                firstLevel += stroke
                // hooks only near the heart of the piece, so it stays bold, not hairy
                if (k < 3) hooks(stroke, -bendSign)
            }
            // further levels: every branch of the level before grows its own
            var previous: List<Stroke> = firstLevel
            for (level in 1 until v["levels"]) {
                val next = ArrayList<Stroke>()
                for (host in previous) repeat(v["sub"]) {
                    if (strokes.size < 700) next += grow(host, level).first
                }
                previous = next
            }
        }

        // all strokes, filled one by one in the same color: overlaps melt together without the
        // (slow) work of joining them into one outline first
        var all: List<Shape> = strokes.map { s -> taperedStroke(s.xs, s.ys) { t -> s.widthAt(t) } }
        if (mirror) all = all + all.map { AffineTransform(-1.0, 0.0, 0.0, 1.0, 2 * pieceX, 0.0).createTransformedShape(it) }
        // a pair: the same piece mirrored on the other side
        if (pair) all = all + all.map { AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0).createTransformedShape(it) }
        return paint(src, v, all)
    }

    /**
     * Fills the finished [shape] in the color: fitted onto the canvas if wanted, then moved and
     * scaled as set, on the base color or a transparent ground.
     */
    private fun paint(src: Pixels, v: Values, shapes: List<Shape>, margin: Double = 0.92): Pixels {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val b = Rectangle2D.Double()
        shapes.forEachIndexed { i, shape -> if (i == 0) b.setRect(shape.bounds2D) else b.add(shape.bounds2D) }
        val t = AffineTransform()
        t.translate(w / 2 + v["posX"] / 1000.0 * w, h / 2 + v["posY"] / 1000.0 * h)
        t.scale(v["scale"] / 100.0, v["scale"] / 100.0)
        if (v.bool("fit") && b.width > 0 && b.height > 0) {
            // the whole ornament with a little margin
            val fit = min(w * margin / b.width, h * margin / b.height)
            t.scale(fit, fit)
            t.translate(-b.centerX, -b.centerY)
        } else t.translate(-w / 2, -h / 2)
        val ground = if (v.bool("transparent")) Pixels(src.width, src.height) else src
        return drawOn(ground) { g ->
            g.color = color(v["color"])
            g.transform(t)
            for (shape in shapes) g.fill(shape)
        }
    }

    /** Polynesian bands: columns of repeated motifs – shark teeth, diamonds, waves, spearheads, triangles. */
    private fun bands(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val random = Random(seed)
        val n = v["bands"]
        val gap = v["gap"] / 100.0
        val cell = w / n
        val bw = cell * (1 - gap * 0.6)
        // the bands collected as one shape, so they can be placed like the ornaments
        val all = Area()
        for (k in 0 until n) {
            val pattern = if (v["pattern"] == 0) 1 + random.nextInt(patterns.size - 1) else v["pattern"]
            band({ all.add(Area(it)) }, pattern, k * cell + (cell - bw) / 2, bw, h)
        }
        // bands fill the canvas edge to edge
        return paint(src, v, listOf(all.apply { intersect(Area(Rectangle2D.Double(0.0, 0.0, w, h))) }), 1.0)
    }

    /** One band of [pattern] from [x0], [bw] wide, down the whole height [h]. */
    private fun band(fill: (java.awt.Shape) -> Unit, pattern: Int, x0: Double, bw: Double, h: Double) {
        fun poly(vararg p: Double) = Path2D.Double().apply {
            moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
            closePath()
        }
        val x1 = x0 + bw
        val xm = x0 + bw / 2
        when (pattern) {
            // shark teeth: triangles from both sides, interlocking
            1 -> {
                val step = bw * 0.9
                var y = 0.0
                var i = 0
                // one row of teeth from the left: the zigzag between them stays free
                while (y < h) {
                    fill(poly(x0, y, x1, y + step / 2, x0, y + step))
                    y += step
                    i++
                }
            }
            // a chain of diamonds, each inside a diamond outline
            2 -> {
                val step = bw * 1.6
                var y = 0.0
                while (y < h) {
                    val c = y + step / 2
                    fill(Area(poly(xm, y, x1, c, xm, y + step, x0, c)).apply { subtract(Area(poly(xm, y + step * 0.12, x1 - bw * 0.12, c, xm, y + step * 0.88, x0 + bw * 0.12, c))) })
                    fill(poly(xm, y + step * 0.25, x1 - bw * 0.27, c, xm, y + step * 0.75, x0 + bw * 0.27, c))
                    y += step
                }
            }
            // waves: rolling scrolls along one side
            3 -> {
                val step = bw * 1.3
                var y = 0.0
                val stroke = java.awt.BasicStroke((bw * 0.16).toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                val path = Path2D.Double()
                path.moveTo(x0 + bw * 0.25, 0.0)
                while (y < h) {
                    path.curveTo(x1 + bw * 0.1, y + step * 0.1, x1, y + step * 0.55, xm, y + step * 0.5)
                    path.curveTo(x0 + bw * 0.25, y + step * 0.45, x0 + bw * 0.35, y + step * 0.2, xm, y + step * 0.3)
                    path.moveTo(x0 + bw * 0.25, y + step)
                    y += step
                }
                fill(stroke.createStrokedShape(path))
                fill(Rectangle2D.Double(x0, 0.0, bw * 0.12, h))
            }
            // spearheads: stacked downward triangles with lines inside
            4 -> {
                val step = bw * 1.0
                var y = 0.0
                while (y < h) {
                    val head = Area(poly(x0, y, x1, y, xm, y + step))
                    for (l in 1..3) head.subtract(Area(Rectangle2D.Double(x0, y + step * (0.12 + l * 0.12), bw, step * 0.04)))
                    head.subtract(Area(poly(xm, y + step * 0.62, xm + bw * 0.1, y + step * 0.78, xm, y + step * 0.94, xm - bw * 0.1, y + step * 0.78)))
                    fill(head)
                    y += step * 0.92
                }
            }
            // nested triangles, pointing alternately left and right
            5 -> {
                val step = bw * 1.2
                var y = 0.0
                var i = 0
                while (y < h) {
                    val right = i % 2 == 0
                    val tip = if (right) x1 else x0
                    val back = if (right) x0 else x1
                    val d = if (right) 1.0 else -1.0
                    val outer = Area(poly(back, y, tip, y + step / 2, back, y + step))
                    outer.subtract(Area(poly(back + d * bw * 0.14, y + step * 0.2, tip - d * bw * 0.3, y + step / 2, back + d * bw * 0.14, y + step * 0.8)))
                    outer.add(Area(poly(back + d * bw * 0.26, y + step * 0.36, tip - d * bw * 0.48, y + step / 2, back + d * bw * 0.26, y + step * 0.64)))
                    fill(outer)
                    y += step
                    i++
                }
            }
            // long diamonds joined by a thin line
            6 -> {
                val step = bw * 2.6
                var y = 0.0
                fill(Rectangle2D.Double(xm - bw * 0.04, 0.0, bw * 0.08, h))
                while (y < h) {
                    fill(poly(xm, y, xm + bw * 0.42, y + step / 2, xm, y + step, xm - bw * 0.42, y + step / 2))
                    y += step * 1.05
                }
            }
            // rows of small triangles
            else -> {
                val cols = 3
                val tw = bw / cols
                val th = tw * 0.9
                var y = 0.0
                var row = 0
                val clip = Area(Rectangle2D.Double(x0, 0.0, bw, h))
                while (y < h) {
                    val shift = if (row % 2 == 0) 0.0 else tw / 2
                    for (c in 0..cols) {
                        val x = x0 + c * tw - shift
                        fill(Area(poly(x, y + th, x + tw / 2, y, x + tw, y + th)).apply { intersect(clip) })
                    }
                    y += th * 1.15
                    row++
                }
            }
        }
    }

    /** One step of Chaikin's corner cutting: a smoother line through the same points. */
    private fun chaikin(p: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (p.size < 3) return p
        val out = ArrayList<Pair<Double, Double>>()
        out += p.first()
        for (i in 0 until p.size - 1) {
            val (ax, ay) = p[i]
            val (bx, by) = p[i + 1]
            out += (ax * 0.75 + bx * 0.25) to (ay * 0.75 + by * 0.25)
            out += (ax * 0.25 + bx * 0.75) to (ay * 0.25 + by * 0.75)
        }
        out += p.last()
        return out
    }
}
