package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Horns: strands grow out of the rim of the start mask (a threshold range), pointing outwards, and
 * curl up towards their tip into spirals (the curvature rises along the strand). Each
 * strand carries the picture's cross-section at its root and drags it along the curve as
 * streaks, like a loaded brush; it tapers and is shaded round. The strands are drawn one
 * after another, each over the ones before, with soft, anti-aliased edges (every stamp is
 * spread bilinearly over the pixels).
 */
object Horns : Effect("horns", "Horns", "Curled horns or branching fans with gills grow out of the picture") {
    private const val STEP = 0.35
    private const val ACROSS = 0.5

    override val params = listOf(
        Param.Heading("startHeading", "Base"),
        Param.Choice(
            "region", "Region", listOf("Thresholds", "Ellipse", "Ellipse and thresholds"),
            tip = "Where it grows from: the threshold range, an ellipse (e.g. around a head) or the threshold range inside the ellipse",
        ),
        Param.Slider("regionX", "Ellipse X", 0, 1000, 500, " %", decimals = 1),
        Param.Slider("regionY", "Ellipse Y", 0, 1000, 500, " %", decimals = 1),
        Param.Slider("regionW", "Ellipse width", 1, 1000, 300, " %", decimals = 1),
        Param.Slider("regionH", "Ellipse height", 1, 1000, 300, " %", decimals = 1),
        Param.Choice("source", "Value", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Lower threshold", 0, 255, 140, tip = "For hue it may lie above the upper one (a range across red)"),
        Param.Slider("upper", "Upper threshold", 0, 255, 255),
        Param.Toggle("invert", "Invert range", false),
        Param.Toggle("showMask", "Show start mask", false, "Shows in black and white which region the horns grow from"),
        Param.Slider("direction", "Direction", 0, 359, 0, "°", "The horns lean in this direction"),
        Param.Slider("directionStrength", "Direction strength", 0, 100, 0, " %", "0 %: they grow straight out of the edge"),
        Param.Heading("hornHeading", "Horns"),
        Param.Choice(
            "hornShape", "Shape", listOf("Horn", "Fan", "Split"),
            tip = "Fan: the strand widens instead of tapering, forks and carries fine gills – like a split gill mushroom. " +
                "Split: the whole region (e.g. a head) grows outwards, splits into two curled halves, the next one grows from the middle",
        ),
        Param.Slider("horns", "Count", 1, 300, 20, tip = "This many grow from the edge of the start mask"),
        Param.Slider("hornLength", "Length", 10, 3000, 250, " px", canvasMax = true),
        Param.Slider("hornWidth", "Width", 2, 500, 50, " px", "Width at the base"),
        Param.Slider("hornCurl", "Curl", 0, 100, 40, " %", "0 % straight, 100 % three full turns (fans only bend gently)"),
        Param.Slider("hornSpiral", "Spiral shape", 0, 400, 150, " %", "0 % an even arc, more: almost straight at first, curled tightly towards the tip"),
        Param.Choice("hornTurn", "Turning direction", listOf("Random", "Left", "Right")),
        Param.Slider("hornVariation", "Random", 0, 100, 40, " %", "How much length, width and curl vary"),
        Param.Slider("hornShade", "Roundness", 0, 100, 40, " %", "Darker towards the edges – looks round"),
        Param.Slider("hornSoft", "Edge softness", 0, 100, 15, " %"),
        Param.Slider("hornBaseSoft", "Soft base", 0, 100, 15, " %", "This much of the length fades the horn in softly at the base instead of starting hard"),
        Param.Heading("hornOnlyHeading", "Horn shape"),
        Param.Slider("hornTaper", "Taper", 0, 100, 70, " %", "How pointed they get"),
        Param.Slider(
            "hornTexture", "Drag texture along", 0, 100, 20, " %",
            "0 % – the picture's cross-section at the base is pulled into stripes, more – the picture content travels outwards too",
        ),
        Param.Heading("fanHeading", "Fan shape"),
        Param.Slider("fanSpread", "Fan out", 0, 3000, 900, " %", "The whole fan widens this much towards its edge"),
        Param.Slider("fanBranches", "Branches", 0, 4, 2, tip = "How often the strands fork"),
        Param.Slider("fanChildren", "Branches per fork", 2, 4, 2),
        Param.Slider("fanFork", "Fork angle", 0, 150, 60, "°"),
        Param.Slider("gills", "Gills", 0, 300, 60, tip = "This many fine gills run lengthwise through the strand and fan out"),
        Param.Slider("gillDepth", "Gill depth", 0, 100, 60, " %"),
        Param.Slider("ruffle", "Ruffles", 0, 100, 50, " %", "Wavy hem at the ends"),
        Param.Slider("ruffleWaves", "Ruffle waves", 1, 20, 5, tip = "This many waves the hem has per lobe"),
        Param.Color("fanTint", "Tint", 0xE8D2C4, "Tinted in this color, the picture's brightness stays as structure"),
        Param.Slider("fanTintAmount", "Tint strength", 0, 100, 0, " %"),
        Param.Slider("fanTexture", "Drag texture along", 0, 100, 0, " %", "More lets picture content travel across the fan"),
        Param.Heading("splitHeading", "Split shape"),
        Param.Slider("splitFront", "Front", 0, 359, 270, "°", "Where the front of the region is (270° = up): this is how every stem reads it"),
        Param.Slider("splitStems", "Stems", 1, 200, 2, tip = "It grows out of the region in this many directions – all are clones of one stem, so it's fast"),
        Param.Slider("splitTilt", "Rotate stems", -180, 180, 0, "°", "Every stem is rotated around its base – 0°: straight away from the region"),
        Param.Choice(
            "splitMirror", "Mirror texture", listOf("Off", "Sideways", "Lengthwise", "Both"),
            tip = "Sideways: left and right swapped. Lengthwise: front and back swapped",
        ),
        Param.Choice(
            "splitSpread", "Distribution", listOf("Even", "Random"),
            tip = "Even: all around, the first one in the direction. Random: in random directions, with variation also in different sizes",
        ),
        Param.Slider("splitLevels", "Levels", 1, 8, 3, tip = "It splits this often and keeps growing from the middle"),
        Param.Slider("splitGrowth", "Growth per level", 50, 200, 125, " %", "Every level is this much longer and wider than the previous one"),
        Param.Slider("splitStem", "Stalk", 0, 200, 70, " %", "How far the next head grows out of the split before it divides (share of the length)"),
        Param.Slider("splitWidth", "Head width", 10, 200, 60, " %", "Width of the strands relative to the region"),
        Param.Toggle("splitMaskOnly", "Region only", true, "The strands carry only the region itself, without its surroundings"),
        Param.Heading("outHeading", "Output"),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mode = v["source"]
        val lower = v["lower"]
        val upper = v["upper"]
        val invert = v.bool("invert")
        val region = v["region"]
        val w = src.width
        val h = src.height
        // the ellipse, in shares of the canvas
        val ex = v["regionX"] / 1000.0 * w
        val ey = v["regionY"] / 1000.0 * h
        val erx = max(0.5, v["regionW"] / 1000.0 * w / 2)
        val ery = max(0.5, v["regionH"] / 1000.0 * h / 2)
        val start = BooleanArray(src.data.size) {
            val c = src.data[it]
            val inEllipse = ((it % w + 0.5 - ex) / erx).let { a -> a * a } + ((it / w + 0.5 - ey) / ery).let { b -> b * b } <= 1.0
            val inRange = alpha(c) > 0 && thresholdSelects(c, mode, lower, upper, invert)
            when (region) {
                1 -> inEllipse && alpha(c) > 0
                2 -> inEllipse && inRange
                else -> inRange
            }
        }
        if (v.bool("showMask")) return Pixels(src.width, src.height, IntArray(start.size) { if (start[it]) -1 else 0xFF000000.toInt() })
        if (start.none { it }) return src
        if (v["hornShape"] == 2) return split(src, start, v, seed)
        return sprout(src, start, src.width, src.height, 1, v, seed)
    }

    /**
     * The picture of a whole area carried by a strand: along the strand it runs through the
     * area in direction ([dx], [dy]) from its front edge back ([front] − f · [extent]),
     * across it through [mid] ± [half]; [mask] says what belongs to the area.
     */
    private class Head(
        val cx: Double, val cy: Double, val dx: Double, val dy: Double,
        val front: Double, val extent: Double, val mid: Double, val half: Double,
        val mask: FloatArray, val w: Int, val h: Int, val maskOnly: Boolean,
        val flipAcross: Boolean = false, val flipAlong: Boolean = false,
    ) {
        // mirrored: across swaps left and right, along swaps front and back
        private fun along(f: Double) = front - (if (flipAlong) 1 - f.coerceIn(0.0, 1.0) else f) * extent
        private fun side(ur: Double) = mid + (if (flipAcross) -ur else ur) * half

        fun x(f: Double, ur: Double) = cx + dx * along(f) - dy * side(ur)
        fun y(f: Double, ur: Double) = cy + dy * along(f) + dx * side(ur)

        fun cover(px: Double, py: Double): Double {
            if (!maskOnly) return 1.0
            val x0 = floor(px - 0.5).toInt()
            val y0 = floor(py - 0.5).toInt()
            val fx = px - 0.5 - x0
            val fy = py - 0.5 - y0
            fun m(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) mask[y * w + x].toDouble() else 0.0
            return (m(x0, y0) * (1 - fx) + m(x0 + 1, y0) * fx) * (1 - fy) + (m(x0, y0 + 1) * (1 - fx) + m(x0 + 1, y0 + 1) * fx) * fy
        }
    }

    /**
     * Splitting: from the area's rim a stem carries the whole area outwards, then splits
     * into two curls, each carrying its half, curling outwards; from the split the next,
     * larger stem grows on and splits again.
     *
     * The area is always read the same way, with "Front" at its front, so every stem looks
     * the same: one stem is drawn once into a sprite (its size measured by a dry run first,
     * at most the canvas diagonal each way), then cloned – turned to each stem's direction,
     * scaled and, with a random turn, mirrored – and laid over the picture. The sprite is
     * premultiplied, read bilinearly.
     */
    private fun split(src: Pixels, start: BooleanArray, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val mask = Grow.blur(FloatArray(start.size) { if (start[it]) 1f else 0f }, w, h, 1)
        var sx = 0.0; var sy = 0.0; var count = 0
        for (i in start.indices) if (start[i]) { sx += i % w + 0.5; sy += i / w + 0.5; count++ }
        val cx = sx / count
        val cy = sy / count
        // the area's frame: its front is "Front"
        val front = Math.toRadians(v["splitFront"].toDouble())
        val fx = cos(front)
        val fy = sin(front)
        var a0 = Double.MAX_VALUE; var a1 = -Double.MAX_VALUE; var b0 = Double.MAX_VALUE; var b1 = -Double.MAX_VALUE
        for (i in start.indices) if (start[i]) {
            val px = i % w + 0.5 - cx
            val py = i / w + 0.5 - cy
            val a = px * fx + py * fy
            val b = -px * fy + py * fx
            if (a < a0) a0 = a
            if (a > a1) a1 = a
            if (b < b0) b0 = b
            if (b > b1) b1 = b
        }
        val mirror = v["splitMirror"]
        val head = Head(
            cx, cy, fx, fy, a1, a1 - a0, (b0 + b1) / 2, (b1 - b0) / 2, mask, w, h, v.bool("splitMaskOnly"),
            flipAcross = mirror == 1 || mirror == 3, flipAlong = mirror == 2 || mirror == 3,
        )
        val baseWidth = (b1 - b0) * v["splitWidth"] / 100.0

        // the stem, pointing along +x from (ox, oy): measured first, then drawn into the sprite
        fun stem(canvas: Canvas, out: Pixels, ox: Double, oy: Double, bounds: DoubleArray?) {
            val power = v["hornSpiral"] / 100.0
            val curl = v["hornCurl"] / 100.0 * 3 * 2 * Math.PI
            val growth = v["splitGrowth"] / 100.0
            val stemShare = v["splitStem"] / 100.0
            val flip = if (v["hornTurn"] == 2) -1.0 else 1.0
            val dummy = Root(ox, oy, 1.0, 0.0, baseWidth)
            var x = ox
            var y = oy
            for (level in 0 until v["splitLevels"]) {
                val scale = growth.pow(level)
                val len = v["hornLength"] * scale
                val width = baseWidth * sqrt(scale)
                if (level > 0 && stemShare > 0) {
                    val end = draw(src, out, canvas, Horn(x, y, 0.0, len * stemShare, width, 0.0, dummy, -1.0, 1.0, 0.0, level, false, head, 0.0), power, v, bounds)
                    x = end.x
                    y = end.y
                }
                for (side in listOf(-1.0, 1.0)) {
                    val u0 = if (side < 0) -1.0 else 0.0
                    draw(src, out, canvas, Horn(x, y + side * width / 4, 0.0, len, width / 2 * 1.1, curl * side * flip, dummy, u0, u0 + 1, 0.0, level, true, head, null), power, v, bounds)
                }
            }
        }
        val bounds = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        stem(Canvas(1, 1), Pixels(1, 1), 0.0, 0.0, bounds)
        val diag = hypot(w.toDouble(), h.toDouble())
        val left = max(bounds[0], -diag) - 2
        val top = max(bounds[1], -diag) - 2
        val sw = (min(bounds[2], diag) - left + 3).toInt()
        val sh = (min(bounds[3], diag) - top + 3).toInt()
        val sprite = Pixels(sw, sh)
        stem(Canvas(sw, sh), sprite, -left, -top, null)

        // clone the stem: each from the area's rim in its direction
        val rnd = java.util.Random(seed * 13 + 3)
        val stems = v["splitStems"]
        val random = v["splitSpread"] == 1
        val variation = v["hornVariation"] / 100.0
        val out = Pixels(w, h, src.data.copyOf())
        for (k in 0 until stems) {
            val angle = if (random) rnd.nextDouble() * 2 * Math.PI else Math.toRadians(v["direction"].toDouble()) + 2 * Math.PI * k / stems
            val scale = if (random) 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.6 else 1.0
            val mirror = v["hornTurn"] == 0 && random && rnd.nextBoolean()
            val dx = cos(angle)
            val dy = sin(angle)
            // the root: where a ray from the middle leaves the area
            var rx = cx
            var ry = cy
            while (true) {
                val nx = rx + dx
                val ny = ry + dy
                val ix = nx.toInt()
                val iy = ny.toInt()
                if (ix !in 0 until w || iy !in 0 until h || !start[iy * w + ix]) break
                rx = nx; ry = ny
            }
            // turned about its root
            val tilt = Math.toRadians(v["splitTilt"].toDouble())
            place(out, sprite, left, top, rx, ry, cos(angle + tilt), sin(angle + tilt), scale, mirror)
        }
        return out
    }

    /**
     * Lays the premultiplied [sprite] (its origin at (−[left], −[top])) over [out]: turned so
     * its +x points along ([dx], [dy]), scaled, mirrored across its axis if asked, with its
     * origin at ([rx], [ry]).
     */
    private fun place(out: Pixels, sprite: Pixels, left: Double, top: Double, rx: Double, ry: Double, dx: Double, dy: Double, scale: Double, mirror: Boolean) {
        val w = out.width
        val h = out.height
        // the sprite's corners on the canvas give the box to visit
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (cxs in listOf(left, left + sprite.width)) for (cys in listOf(top, top + sprite.height)) {
            val ly = if (mirror) -cys else cys
            val px = rx + (cxs * dx - ly * dy) * scale
            val py = ry + (cxs * dy + ly * dx) * scale
            x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, py); y1 = max(y1, py)
        }
        val bx0 = max(0, floor(x0).toInt())
        val bx1 = min(w - 1, kotlin.math.ceil(x1).toInt())
        val by0 = max(0, floor(y0).toInt())
        val by1 = min(h - 1, kotlin.math.ceil(y1).toInt())
        if (bx0 > bx1 || by0 > by1) return
        com.spielgrund.glitchr.image.parallelRows(by1 - by0 + 1) { row ->
            val y = by0 + row
            for (x in bx0..bx1) {
                // back into the sprite
                val qx = (x + 0.5 - rx) / scale
                val qy = (y + 0.5 - ry) / scale
                val lx = qx * dx + qy * dy
                var ly = -qx * dy + qy * dx
                if (mirror) ly = -ly
                val c = premultipliedAt(sprite, lx - left, ly - top)
                val a = alpha(c)
                if (a == 0) continue
                val i = y * w + x
                val d = out.data[i]
                val keep = 1 - a / 255.0
                out.data[i] = argb(
                    (a + alpha(d) * keep).roundToInt().coerceIn(0, 255),
                    (red(c) + red(d) * keep).roundToInt().coerceIn(0, 255),
                    (green(c) + green(d) * keep).roundToInt().coerceIn(0, 255),
                    (blue(c) + blue(d) * keep).roundToInt().coerceIn(0, 255),
                )
            }
        }
    }

    /** Bilinear read of a premultiplied sprite at (x, y) in its pixels (transparent outside). */
    private fun premultipliedAt(p: Pixels, x: Double, y: Double): Int {
        val fx = x - 0.5
        val fy = y - 0.5
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
        for (k in 0..3) {
            val sx = ix + (k and 1)
            val sy = iy + (k shr 1)
            if (sx !in 0 until p.width || sy !in 0 until p.height) continue
            val f = (if (k and 1 == 0) 1 - tx else tx) * (if (k shr 1 == 0) 1 - ty else ty)
            val c = p.data[sy * p.width + sx]
            a += alpha(c) * f; r += red(c) * f; g += green(c) * f; b += blue(c) * f
        }
        return argb(a.roundToInt(), r.roundToInt(), g.roundToInt(), b.roundToInt())
    }

    private fun sprout(src: Pixels, start: BooleanArray, gw: Int, gh: Int, n: Int, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // roots: start cells with a free neighbour
        val rim = (0 until gw * gh).filter { i ->
            if (!start[i]) return@filter false
            val x = i % gw
            val y = i / gw
            (x > 0 && !start[i - 1]) || (x < gw - 1 && !start[i + 1]) || (y > 0 && !start[i - gw]) || (y < gh - 1 && !start[i + gw])
        }
        if (rim.isEmpty()) return src
        val width = v["hornWidth"].toDouble()
        // the outward direction: down the slope of the softened mask
        val soft = Grow.blur(FloatArray(start.size) { if (start[it]) 1f else 0f }, gw, gh, max(1, (width / 2 / n).roundToInt()))
        val rnd = java.util.Random(seed * 7 + 11)
        val dirAngle = Math.toRadians(v["direction"].toDouble())
        val dirStrength = v["directionStrength"] / 100.0 * 2
        val length = v["hornLength"].toDouble()
        val fan = v["hornShape"] == 1
        val depth = if (fan) v["fanBranches"] else 0
        val curl = v["hornCurl"] / 100.0 * 3 * 2 * Math.PI
        val power = v["hornSpiral"] / 100.0
        val variation = v["hornVariation"] / 100.0
        val turnMode = v["hornTurn"]
        val out = Pixels(w, h, src.data.copyOf())
        val canvas = Canvas(w, h)
        repeat(v["horns"]) {
            val cell = rim[rnd.nextInt(rim.size)]
            val cx = cell % gw
            val cy = cell / gw
            var nx = -(soft[cy * gw + min(gw - 1, cx + 1)] - soft[cy * gw + max(0, cx - 1)]).toDouble()
            var ny = -(soft[min(gh - 1, cy + 1) * gw + cx] - soft[max(0, cy - 1) * gw + cx]).toDouble()
            val len = hypot(nx, ny)
            if (len < 1e-6) {
                val a = rnd.nextDouble() * 2 * Math.PI
                nx = cos(a); ny = sin(a)
            } else { nx /= len; ny /= len }
            nx += cos(dirAngle) * dirStrength
            ny += sin(dirAngle) * dirStrength
            fun vary() = 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.6
            val sign = when (turnMode) { 1 -> -1.0; 2 -> 1.0; else -> if (rnd.nextBoolean()) 1.0 else -1.0 }
            val x = (cx + rnd.nextDouble()) * n
            val y = (cy + rnd.nextDouble()) * n
            val angle = kotlin.math.atan2(ny, nx)
            val hornWidth = width * vary()
            val root = Root(x, y, cos(angle), sin(angle), hornWidth)
            if (fan) {
                // the whole length is shared by the root piece and its ever shorter branches
                val total = (0..depth).sumOf { CHILD_LENGTH.pow(it) }
                val horn = Horn(x, y, angle, length * vary() / total, hornWidth, curl * FAN_CURL * vary() * sign, root, -1.0, 1.0, 0.0, 0, depth == 0)
                drawFan(src, out, canvas, horn, power, depth, rnd, v)
            } else {
                draw(src, out, canvas, Horn(x, y, angle, length * vary(), hornWidth, curl * vary() * sign, root, -1.0, 1.0, 0.0, 0, true), power, v)
            }
        }
        return out
    }

    /** Where a strand's picture comes from: the root's position, direction and width. */
    private class Root(val x: Double, val y: Double, val tx: Double, val ty: Double, val width: Double)

    /**
     * One strand piece. [u0]..[u1] is the part of the root's cross-section (-1..1) it
     * carries (a branch carries its share of the parent's), [s0] how far from the root it
     * starts, [depth] its branching level; a [leaf] ends in the ruffled rim.
     */
    private class Horn(
        val x: Double, val y: Double, val angle: Double, val length: Double, val width: Double, val turn: Double,
        val root: Root, val u0: Double, val u1: Double, val s0: Double, val depth: Int, val leaf: Boolean,
        val head: Head? = null, val taper: Double? = null,
    )

    /** Where a strand piece ended: position, direction and half width. */
    private class End(val x: Double, val y: Double, val angle: Double, val half: Double)

    private const val CHILD_LENGTH = 0.75
    private const val FAN_CURL = 0.06

    /** A fan piece, then its branches: each takes its share of the end's width and of the root's picture. */
    private fun drawFan(src: Pixels, out: Pixels, canvas: Canvas, horn: Horn, power: Double, depth: Int, rnd: java.util.Random, v: Values) {
        val end = draw(src, out, canvas, horn, power, v)
        if (horn.leaf) return
        val children = v["fanChildren"]
        val fork = Math.toRadians(v["fanFork"].toDouble())
        val variation = v["hornVariation"] / 100.0
        for (k in 0 until children) {
            val share = (2.0 * k + 1) / children - 1
            val jitter = 1 + variation * (rnd.nextDouble() * 2 - 1) * 0.5
            val nx = -sin(end.angle)
            val ny = cos(end.angle)
            val child = Horn(
                x = end.x + nx * share * end.half, y = end.y + ny * share * end.half,
                angle = end.angle + share * fork / 2 * jitter,
                length = horn.length * CHILD_LENGTH * jitter,
                // wider than their share, so the branches overlap into one fan
                width = 2 * end.half / children * 1.6,
                turn = abs(horn.turn) * jitter * if (rnd.nextBoolean()) 1 else -1,
                root = horn.root,
                u0 = horn.u0 + (horn.u1 - horn.u0) * k / children,
                u1 = horn.u0 + (horn.u1 - horn.u0) * (k + 1) / children,
                s0 = horn.s0 + horn.length, depth = horn.depth + 1, leaf = horn.depth + 1 >= depth,
            )
            drawFan(src, out, canvas, child, power, depth, rnd, v)
        }
    }

    /** Per-strand accumulation buffers over the whole canvas, cleared after each strand within its box. */
    private class Canvas(val w: Int, val h: Int) {
        val a = FloatArray(w * h)
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val weight = FloatArray(w * h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1

        fun splat(px: Double, py: Double, c: Int, light: Double, wgt: Double) {
            val fx = px - 0.5
            val fy = py - 0.5
            val ix = floor(fx).toInt()
            val iy = floor(fy).toInt()
            val tx = fx - ix
            val ty = fy - iy
            for (k in 0..3) {
                val x = ix + (k and 1)
                val y = iy + (k shr 1)
                if (x !in 0 until w || y !in 0 until h) continue
                val f = ((if (k and 1 == 0) 1 - tx else tx) * (if (k shr 1 == 0) 1 - ty else ty) * wgt).toFloat()
                if (f <= 0f) continue
                val i = y * w + x
                a[i] += alpha(c) * f
                r[i] += (red(c) * light).toFloat() * f
                g[i] += (green(c) * light).toFloat() * f
                b[i] += (blue(c) * light).toFloat() * f
                weight[i] += f
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
    }

    /**
     * Walks the strand in small steps; its curvature grows with (s / length)^power, so all
     * of [Horn.turn] is turned, most of it near the tip. At every step the cross-section
     * of the root (scaled to the tapered width, shifted inwards by the texture share) is
     * stamped across the strand.
     */
    private fun draw(src: Pixels, out: Pixels, canvas: Canvas, horn: Horn, power: Double, v: Values, bounds: DoubleArray? = null): End {
        val fan = v["hornShape"] == 1
        val taper = horn.taper ?: (v["hornTaper"] / 100.0)
        // the widening is shared by the root piece and its branch levels, so the whole fan widens by
        // 1 + spread; the leaves take a double share and open up like wedges
        val levels = if (fan) v["fanBranches"] + 1 else 1
        val share = (if (horn.leaf) 2.0 else 1.0) / (levels + 1)
        val spread = (1 + v["fanSpread"] / 100.0).pow(share) - 1
        // fan: gills along the strand, fanning out with the width, and a ruffled rim at the leaves
        val gills = if (fan) v["gills"] / 2.0 else 0.0
        val gillDepth = v["gillDepth"] / 100.0
        val ruffle = if (fan && horn.leaf) v["ruffle"] / 100.0 else 0.0
        // a leaf's rim is rounded: towards its sides it ends earlier
        val round = if (fan && horn.leaf) 0.3 else 0.0
        val scallops = v["ruffleWaves"].toDouble()
        val tint = if (fan) v["fanTintAmount"] / 100.0 else 0.0
        val tintColor = v["fanTint"]
        val root = horn.root
        val texture = (if (fan) v["fanTexture"] else v["hornTexture"]) / 100.0
        val shade = v["hornShade"] / 100.0
        val softEdge = max(0.02, v["hornSoft"] / 100.0)
        // the root fades in over this share of the length
        val baseSoft = v["hornBaseSoft"] / 100.0
        val amount = v["amount"] / 100.0
        val steps = max(1, (horn.length / STEP).toInt())
        val t0x = root.tx
        val t0y = root.ty
        // the root's cross-section runs along its normal
        val n0x = -t0y
        val n0y = t0x
        var x = horn.x
        var y = horn.y
        var angle = horn.angle
        val norm = horn.turn * (power + 1) / horn.length
        for (step in 0..steps) {
            val s = step * STEP
            val f = s / horn.length
            val half = max(0.5, horn.width * (if (fan) 1 + spread * f else 1 - taper * f) / 2)
            val tx = cos(angle)
            val ty = sin(angle)
            val nx = -ty
            val ny = tx
            val across = max(1, (2 * half / ACROSS).toInt())
            val fadeIn = if (horn.depth > 0 || horn.head != null || baseSoft <= 0 || f >= baseSoft) 1.0 else (f / baseSoft).let { it * it * (3 - 2 * it) }
            // a dry run only measures how far the strand reaches
            if (bounds != null) {
                bounds[0] = min(bounds[0], x - half); bounds[1] = min(bounds[1], y - half)
                bounds[2] = max(bounds[2], x + half); bounds[3] = max(bounds[3], y + half)
            } else for (k in 0..across) {
                val u = k.toDouble() / across * 2 - 1
                // where across the root's cross-section this lies (-1..1)
                val ur = horn.u0 + (u + 1) / 2 * (horn.u1 - horn.u0)
                var edge = min(1.0, (1 - abs(u)) / softEdge) * fadeIn
                if (ruffle > 0 || round > 0) {
                    // the rim of a leaf is rounded and scalloped: it ends a little earlier to the sides and between the waves
                    val scallop = 0.5 + 0.5 * cos((u + 1) / 2 * 2 * Math.PI * scallops)
                    val cut = horn.length * (1 - round * u * u - ruffle * 0.18 * scallop)
                    if (s > cut) continue
                    edge *= min(1.0, (cut - s) / 1.5)
                }
                if (edge <= 0) continue
                val o = u * half
                // the root's picture: its cross-section, dragged in from inside by the texture share
                val o0 = ur * root.width / 2
                val head = horn.head
                val sx = head?.x(f, ur) ?: (root.x + n0x * o0 - t0x * (horn.s0 + s) * texture)
                val sy = head?.y(f, ur) ?: (root.y + n0y * o0 - t0y * (horn.s0 + s) * texture)
                if (head != null) {
                    // only what belongs to the area
                    edge *= head.cover(sx, sy)
                    if (edge <= 0.001) continue
                }
                var c = sampleBilinear(src, sx, sy, Edge.CLAMP)
                if (tint > 0) {
                    // tinted: the color takes over, the picture's brightness stays as texture
                    val luma = (0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)) / 160.0
                    fun t(p: Int, q: Int) = (p + (min(255.0, q * luma) - p) * tint).roundToInt().coerceIn(0, 255)
                    c = argb(alpha(c), t(red(c), (tintColor shr 16) and 0xFF), t(green(c), (tintColor shr 8) and 0xFF), t(blue(c), tintColor and 0xFF))
                }
                var light = 1 - shade * u * u
                if (gills > 0) {
                    // thin dark grooves between light ridges
                    val groove = (0.5 + 0.5 * cos(2 * Math.PI * ur * gills)).pow(2)
                    light *= 1 - gillDepth * 0.85 * groove
                    light += gillDepth * 0.15 * (1 - groove)
                }
                // each stamp covers its share of the area, so the weights add up to about 1 per pixel
                canvas.splat(x + nx * o, y + ny * o, c, light, edge * STEP * (2 * half / across))
            }
            // never bent tighter than the strand is wide, else its inner side would fold over
            var bend = norm * f.pow(power)
            if (fan || horn.head != null) bend = bend.coerceIn(-0.8 / half, 0.8 / half)
            angle += bend * STEP
            x += tx * STEP
            y += ty * STEP
        }
        val end = End(x, y, angle, max(0.5, horn.width * (if (fan) 1 + spread else 1 - taper) / 2))
        // lay the strand over what is there: covered pixels take its (averaged) color
        if (canvas.x1 < 0) return end
        val w = canvas.w
        for (py in canvas.y0..canvas.y1) for (px in canvas.x0..canvas.x1) {
            val i = py * w + px
            val wt = canvas.weight[i]
            if (wt <= 0f) continue
            // a little more than 1, so the thinned outer side of tight curls still covers
            val cover = min(1.0, wt * 1.3) * amount
            val sa = canvas.a[i] / wt
            val base = out.data[i]
            val a = cover * sa / 255.0
            fun mix(o: Int, m: Float) = (o + (m / wt - o) * a).roundToInt().coerceIn(0, 255)
            out.data[i] = argb(
                max(alpha(base), (sa * cover).roundToInt()).coerceIn(0, 255),
                mix(red(base), canvas.r[i]), mix(green(base), canvas.g[i]), mix(blue(base), canvas.b[i]),
            )
            canvas.a[i] = 0f; canvas.r[i] = 0f; canvas.g[i] = 0f; canvas.b[i] = 0f; canvas.weight[i] = 0f
        }
        canvas.x0 = w; canvas.y0 = canvas.h; canvas.x1 = -1; canvas.y1 = -1
        return end
    }
}
