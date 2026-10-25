package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Barbed wire: strands stretched across the picture (sagging between posts if wanted), criss-cross
 * over it, along drawn arrows or in rings – two wires twisted around each other with barbs of two
 * or four points, or razor wire with its blades; optionally coiled into loops like concertina wire.
 * Shaded as metal, the back darker than the front, with rust.
 */
object Barbwire : Effect("barbwire", "Barbed wire", "Barbed or razor wire – stretched, criss-cross, along drawn arrows or as rings, optionally coiled") {
    private val layouts = listOf("Strands across", "Along arrows", "Rings", "Criss-cross")
    private val kinds = listOf("Barbed wire", "Razor wire")
    private val slideDirections = listOf("Right", "Left", "Alternating", "Random")

    override val params = listOf(
        Param.Choice(
            "layout", "Layout", layouts, 0,
            "Strands across: straight across the picture, sagging between posts · Along arrows: a wire along every arrow drawn in the picture · " +
                "Rings: around the middle, like a crown of thorns or an armband tattoo · Criss-cross: wires at random angles across the picture, lying over each other",
        ),
        Param.Flow("strokes", "Arrows", "Drag in the picture: every arrow becomes a wire along it", activeWhen = "layout" to 1),
        Param.Choice("kind", "Wire", kinds, 0, "Barbed wire: two twisted wires with barbs · Razor wire: one wire with sharp blades"),
        Param.Slider("count", "Strands", 1, 20, 3, tip = "Strands across, criss-cross wires, or rings"),
        Param.Slider("angle", "Direction", -18000, 18000, 0, "°", "Of the strands across: 0° = horizontal", decimals = 2),
        Param.Slider("spacing", "Spacing", 100, 10000, 1800, " %", "Between the strands or rings, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("size", "Ring size", 100, 10000, 3000, " %", "Radius of the rings, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("sag", "Sag", 0, 100, 0, " %", "How much the strands droop between their posts – criss-cross: how much the wires bow"),
        Param.Slider("posts", "Posts", 1, 12, 3, tip = "Droops across the picture"),
        Param.Slider("wobble", "Wobble", 0, 100, 20, " %", "How unevenly the wire runs"),
        Param.Slider("slide", "Slide", -1000000, 1000000, 0, " px", "Pulls every wire along itself – barbs, twist, loops and rust move, the course stays; animate it", decimals = 1, transform = true),
        Param.Choice(
            "slideDirection", "Slide direction", slideDirections, 0,
            "Right: to the right (down for upright wires) – along drawn arrows: forwards, rings: clockwise · Left: the other way · " +
                "Alternating: every second wire the other way · Random: every wire its own direction and speed",
        ),
        Param.Heading("coilHeading", "Coil"),
        Param.Toggle("coiled", "Coiled", false, "Loops like concertina wire on a fence"),
        Param.Slider("coilSize", "Coil size", 50, 3000, 700, " %", "Radius of the loops, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Slider("pitch", "Loop spacing", 5, 200, 40, " %", "Of the loops' size: small = dense loops"),
        Param.Heading("wireHeading", "Wire"),
        Param.Slider("thickness", "Thickness", 5, 500, 35, " px", decimals = 1),
        Param.Slider("twist", "Twist length", 20, 3000, 220, " px", "Of one turn of the two twisted wires", decimals = 1),
        Param.Slider("barbSpacing", "Barb spacing", 10, 3000, 110, " px"),
        Param.Slider("barbLength", "Barb length", 2, 400, 18, " px"),
        Param.Choice("points", "Barbs", listOf("2 points", "4 points"), 1),
        Param.Slider("irregular", "Irregular", 0, 100, 35, " %", "How much the barbs differ in place, angle and length"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Metal", 0x9CA2A8),
        Param.Slider("shine", "Shine", 0, 100, 70, " %"),
        Param.Slider("rust", "Rust", 0, 100, 15, " %", "Rusty patches along the wire"),
        Param.Color("rustColor", "Rust color", 0x7A3F1C),
        Param.Toggle("transparent", "Transparent background", false, "Only the wire, without the base color"),
    )

    /** A point of a wire's course: place, depth (towards the viewer: positive) and how far along it is. */
    private class Pt(val x: Double, val y: Double, val depth: Double, val s: Double)

    /** Something to draw, by [layer] (a whole wire over another) and then its depth: back first. */
    private class Piece(val depth: Double, val layer: Int, val draw: (Graphics2D) -> Unit)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val noise = Noise(seed)
        val size = min(w, h).toDouble()
        val thick = max(0.5, v["thickness"] / 10.0)
        val twistLen = max(2.0, v["twist"] / 10.0)
        val wobble = v["wobble"] / 100.0
        val razor = v["kind"] == 1
        // fine enough for the twist and the loops, not finer than needed
        val step = max(0.8, min(thick * 0.6, twistLen / 12))

        // sliding: how far the material of wire k is pulled forwards along its course (negative: backwards)
        val slide = v["slide"] / 10.0
        val slideDirection = v["slideDirection"]
        fun slideOf(k: Int): Double = slide * when (slideDirection) {
            1 -> -1.0
            2 -> if (k % 2 == 0) 1.0 else -1.0
            3 -> Random(seed * 7919 + k * 104729L + 17).let { r -> (if (r.nextBoolean()) 1.0 else -1.0) * (0.5 + r.nextDouble()) }
            else -> 1.0
        }

        /**
         * [pts] (canvas pixels) evenly resampled every [step] px, with wobble across. The points' place
         * along the wire is the course's length minus the slide: everything drawn by it moves forwards.
         * [backwards]: the course runs against the direction "Right" means, so the slide turns around.
         */
        fun resample(pts: List<DoubleArray>, strand: Int, backwards: Boolean = false): List<Pt> {
            val out = ArrayList<Pt>()
            val shift = slideOf(strand) * (if (backwards) -1 else 1)
            var s = 0.0
            var last = 0.0
            for (i in 1 until pts.size) {
                val (x0, y0) = pts[i - 1].let { it[0] to it[1] }
                val (x1, y1) = pts[i].let { it[0] to it[1] }
                val len = hypot(x1 - x0, y1 - y0)
                if (len < 1e-9) continue
                var t = if (out.isEmpty()) 0.0 else (last + step - s) / len
                while (t <= 1.0) {
                    val ss = s + t * len
                    val nx = -(y1 - y0) / len
                    val ny = (x1 - x0) / len
                    val off = wobble * size * 0.02 * noise.fbm(ss / (size * 0.12), strand * 9.7 + 0.5, 3)
                    out += Pt(x0 + (x1 - x0) * t + nx * off, y0 + (y1 - y0) * t + ny * off, 0.0, ss - shift)
                    last = ss
                    t += step / len
                }
                s += len
            }
            return out
        }

        // ---- the courses ----
        val courses = ArrayList<List<Pt>>()
        val count = v["count"]
        val spacing = v["spacing"] / 10000.0 * size
        when (v["layout"]) {
            0 -> {
                val a = Math.toRadians(v["angle"] / 100.0)
                val dx = cos(a)
                val dy = sin(a)
                val half = hypot(w.toDouble(), h.toDouble()) * 0.6
                val posts = v["posts"]
                val sag = v["sag"] / 100.0 * half * 2 / posts * 0.25
                for (k in 0 until count) {
                    val off = (k - (count - 1) / 2.0) * spacing
                    // the posts at the same places on every strand, a little shifted per strand
                    val shift = random.nextDouble() * 0.1
                    val pts = (0..400).map { j ->
                        val t = j / 400.0
                        val along = -half + 2 * half * t
                        val span = (t * posts + shift) % 1.0
                        val droop = sag * 4 * span * (1 - span)
                        doubleArrayOf(w / 2.0 + dx * along - dy * (off + droop), h / 2.0 + dy * along + dx * (off + droop))
                    }
                    courses += resample(pts, k, dx < -1e-9)
                }
            }
            1 -> FlowStrokes.parse(v.text("strokes")).forEachIndexed { k, stroke ->
                courses += resample(stroke.map { doubleArrayOf(it.x * w, it.y * h) }, k)
            }
            3 -> {
                // wires through random places at random angles, spread so that no two run alike, each bowed a little;
                // a whole diagonal to each side, so they leave the picture wherever they cross it
                val diag = hypot(w.toDouble(), h.toDouble())
                val half = diag * 1.05
                val start = random.nextDouble() * PI
                for (k in 0 until count) {
                    val a = start + (k + (random.nextDouble() - 0.5) * 0.7) * PI / count
                    val dx = cos(a)
                    val dy = sin(a)
                    val mx = w * (0.15 + random.nextDouble() * 0.7)
                    val my = h * (0.15 + random.nextDouble() * 0.7)
                    val bow = v["sag"] / 100.0 * diag * 0.6 * (if (random.nextBoolean()) 1 else -1)
                    val pts = (0..400).map { j ->
                        val t = j / 400.0
                        val along = -half + 2 * half * t
                        val off = bow * 4 * t * (1 - t)
                        doubleArrayOf(mx + dx * along - dy * off, my + dy * along + dx * off)
                    }
                    courses += resample(pts, k, dx < -1e-9)
                }
            }
            else -> {
                val r0 = v["size"] / 10000.0 * size
                for (k in 0 until count) {
                    val r = r0 + (k - (count - 1) / 2.0) * spacing
                    if (r <= thick) continue
                    val n = max(24, (2 * PI * r / 4).toInt())
                    val start = random.nextDouble() * 2 * PI
                    courses += resample((0..n).map { j ->
                        val a = start + j * 2 * PI / n
                        doubleArrayOf(w / 2.0 + cos(a) * r, h / 2.0 + sin(a) * r)
                    }, k)
                }
            }
        }

        // ---- coiled: the course becomes a row of loops around it, seen slightly from the side ----
        val coilR = v["coilSize"] / 10000.0 * size
        val wires = if (!v.bool("coiled")) courses else courses.map { course ->
            val pitch = max(1.0, coilR * v["pitch"] / 100.0)
            val out = ArrayList<Pt>()
            // per loop about 2πr of wire: sampled along the course so finely that the loop's points are [step] apart
            val sub = max(1, (2 * PI * coilR / pitch * 1.0).toInt())
            // along the loops' wire, which is that much longer than the course – slid with it
            var s = course.firstOrNull()?.s?.times(2 * PI * coilR / pitch) ?: 0.0
            for (i in 1 until course.size) {
                val p0 = course[i - 1]
                val p1 = course[i]
                val len = hypot(p1.x - p0.x, p1.y - p0.y)
                if (len < 1e-9) continue
                val tx = (p1.x - p0.x) / len
                val ty = (p1.y - p0.y) / len
                for (j in 0 until sub) {
                    val f = j / sub.toDouble()
                    val ss = p0.s + (p1.s - p0.s) * f
                    val th = ss / pitch * 2 * PI
                    // a ring standing across the course, tilted: its front sways forwards
                    val across = cos(th) * coilR
                    val forth = sin(th) * coilR * 0.5
                    val x = p0.x + (p1.x - p0.x) * f
                    val y = p0.y + (p1.y - p0.y) * f
                    out += Pt(x - ty * across + tx * forth, y + tx * across + ty * forth, sin(th) * coilR, s)
                    s += 2 * PI * coilR / pitch * len / sub
                }
            }
            out
        }

        // ---- colors ----
        val metal = v["color"]
        val rustC = v["rustColor"]
        val rust = v["rust"] / 100.0
        val shine = v["shine"] / 100.0
        val maxDepth = max(1.0, if (v.bool("coiled")) coilR else thick)

        /** Metal at [s] along wire [k], at [depth]: [tone] 0 dark edge, 1 body, 2 highlight. */
        fun color(k: Int, s: Double, depth: Double, tone: Int): Color {
            val rn = noise.fbm(s / 60.0 + k * 13.1, k * 3.7, 3) * 0.5 + 0.5
            val r = if (rust > 0) ((rn - (1 - rust)) / 0.15).coerceIn(0.0, 1.0) * min(1.0, rust * 2) else 0.0
            val back = 0.6 + 0.4 * ((depth / maxDepth) * 0.5 + 0.5).coerceIn(0.0, 1.0)
            fun c(sh: Int): Int {
                val m = (metal shr sh and 0xFF) + ((rustC shr sh and 0xFF) - (metal shr sh and 0xFF)) * r
                val t = when (tone) {
                    0 -> m * 0.32
                    1 -> m * 0.85
                    else -> m + (255 - m) * shine * (1 - r * 0.8)
                }
                return (t * back).toInt().coerceIn(0, 255)
            }
            return Color(c(16), c(8), c(0))
        }

        val pieces = ArrayList<Piece>()
        // criss-cross: every wire lies on the ones before it
        val stacked = v["layout"] == 3
        var layer = 0

        /** A wire along [pts] drawn as runs of a few points each, every run lit as a round wire. */
        fun wire(pts: List<Pt>, k: Int, width: Double, runLen: Double) {
            var i = 0
            while (i < pts.size - 1) {
                var j = i + 1
                while (j < pts.size - 1 && pts[j].s - pts[i].s < runLen) j++
                val run = pts.subList(i, j + 1)
                val depth = run.sumOf { it.depth } / run.size
                val mid = run[run.size / 2]
                pieces += Piece(depth, layer) { g ->
                    val path = Path2D.Double().apply {
                        moveTo(run[0].x, run[0].y)
                        for (p in run.drop(1)) lineTo(p.x, p.y)
                    }
                    g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = color(k, mid.s, depth, 0)
                    g.draw(path)
                    g.stroke = BasicStroke((width * 0.7).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = color(k, mid.s, depth, 1)
                    g.draw(path)
                    // the highlight: thin, towards the light at the top left
                    if (shine > 0) {
                        g.stroke = BasicStroke((width * 0.26).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g.color = color(k, mid.s, depth, 2)
                        val o = width * 0.17
                        g.translate(-o, -o)
                        g.draw(path)
                        g.translate(o, o)
                    }
                }
                i = j
            }
        }

        /** A pointed spike from (x, y) to (tx, ty), [base] wide at its root. */
        fun spike(x: Double, y: Double, tx: Double, ty: Double, base: Double, k: Int, s: Double, depth: Double) {
            val len = hypot(tx - x, ty - y)
            if (len < 0.5) return
            val nx = -(ty - y) / len * base / 2
            val ny = (tx - x) / len * base / 2
            pieces += Piece(depth, layer) { g ->
                val path = Path2D.Double().apply {
                    moveTo(x + nx, y + ny); lineTo(tx, ty); lineTo(x - nx, y - ny); closePath()
                }
                g.color = color(k, s, depth, 1)
                g.fill(path)
                g.stroke = BasicStroke(max(0.5f, (base * 0.25).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = color(k, s, depth, 0)
                g.draw(path)
                if (shine > 0) {
                    g.stroke = BasicStroke(max(0.4f, (base * 0.22).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = color(k, s, depth, 2)
                    g.draw(java.awt.geom.Line2D.Double(x + nx * 0.4, y + ny * 0.4, x + (tx - x) * 0.75, y + (ty - y) * 0.75))
                }
            }
        }

        val barbSpacing = max(2.0, v["barbSpacing"].toDouble())
        val barbLen = v["barbLength"].toDouble()
        val irregular = v["irregular"] / 100.0
        val fourPoints = v["points"] == 1
        for ((k, pts) in wires.withIndex()) {
            if (pts.size < 2) continue
            layer = if (stacked) k else 0
            if (razor) {
                wire(pts, k, thick, max(step * 3, if (v.bool("coiled")) coilR * 0.4 else 12.0))
            } else {
                // two wires winding around the course, each half turn a run of its own so they cross in front and behind
                val off = thick * 0.5
                for (strand in 0..1) {
                    val twisted = pts.indices.map { i ->
                        val p = pts[i]
                        val q = pts[min(i + 1, pts.lastIndex)]
                        val o = pts[max(i - 1, 0)]
                        val len = max(1e-9, hypot(q.x - o.x, q.y - o.y))
                        val ph = p.s / twistLen * 2 * PI + strand * PI
                        Pt(p.x - (q.y - o.y) / len * off * cos(ph), p.y + (q.x - o.x) / len * off * cos(ph), p.depth + sin(ph) * thick * 0.5, p.s)
                    }
                    wire(twisted, k, thick, twistLen / 4)
                }
            }

            // barbs or blades, every so often along the wire: at fixed places of its material, so they slide with it,
            // each one shifted and shaped by its own random numbers
            fun barbRandom(n: Long) = Random(seed * 31 + k * 1000003L + n * 7919)
            fun barbAt(n: Long) = (n + 0.5 + (barbRandom(n).nextDouble() - 0.5) * irregular * 0.5) * barbSpacing
            for (i in 1 until pts.size - 1) {
                val s0 = pts[i - 1].s
                val s1 = pts[i].s
                val n = floor(s1 / barbSpacing).toLong().let { n -> listOf(n - 1, n, n + 1).firstOrNull { barbAt(it) > s0 && barbAt(it) <= s1 } } ?: continue
                val random = barbRandom(n).also { it.nextDouble() }
                val p = pts[i]
                val q = pts[i + 1]
                val o = pts[i - 1]
                val len = max(1e-9, hypot(q.x - o.x, q.y - o.y))
                val tx = (q.x - o.x) / len
                val ty = (q.y - o.y) / len
                if (razor) {
                    // a blade: a flat strip on the wire, two hooked points to each side, pointing back
                    val l = barbLen * (1 + (random.nextDouble() - 0.5) * irregular * 0.6)
                    val bw = max(thick * 0.9, l * 0.12)
                    val depth = p.depth + thick
                    pieces += Piece(depth, layer) { g ->
                        fun at(a: Double, b: Double) = doubleArrayOf(p.x + tx * a - ty * b, p.y + ty * a + tx * b)
                        val outline = listOf(
                            at(-l * 0.5, 0.0), at(-l * 0.32, bw), at(-l * 0.05, l * 0.42), at(-l * 0.12, bw), at(l * 0.12, bw), at(l * 0.05, l * 0.42),
                            at(l * 0.32, bw), at(l * 0.5, 0.0), at(l * 0.32, -bw), at(l * 0.05, -l * 0.42), at(l * 0.12, -bw),
                            at(-l * 0.12, -bw), at(-l * 0.05, -l * 0.42), at(-l * 0.32, -bw),
                        )
                        val path = Path2D.Double().apply {
                            moveTo(outline[0][0], outline[0][1])
                            for (c in outline.drop(1)) lineTo(c[0], c[1])
                            closePath()
                        }
                        g.color = color(k, p.s, depth, 1)
                        g.fill(path)
                        g.stroke = BasicStroke(max(0.5f, (thick * 0.3).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g.color = color(k, p.s, depth, 0)
                        g.draw(path)
                        if (shine > 0) {
                            val a = at(-l * 0.4, -bw * 0.3)
                            val b = at(l * 0.4, -bw * 0.3)
                            g.stroke = BasicStroke(max(0.4f, (bw * 0.35).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            g.color = color(k, p.s, depth, 2)
                            g.draw(java.awt.geom.Line2D.Double(a[0], a[1], b[0], b[1]))
                        }
                    }
                } else {
                    // a barb: a short wire wound around, its ends sticking out as points
                    val depth = p.depth + thick
                    val turns = if (fourPoints) 2 else 1
                    for (t in 0 until turns) {
                        val along = (t - (turns - 1) / 2.0) * thick * 1.6
                        val cx = p.x + tx * along
                        val cy = p.y + ty * along
                        val tilt = (if (t == 0) 1.0 else -1.0) * (0.95 + (random.nextDouble() - 0.5) * irregular * 0.8)
                        for (side in listOf(1.0, -1.0)) {
                            // out to the side, leaning along the wire; foreshortened as they point towards or away from the viewer
                            val a = side * tilt
                            val l = barbLen * (1 - irregular * 0.5 * random.nextDouble())
                            val ex = tx * cos(a) * side - ty * sin(a) * side
                            val ey = ty * cos(a) * side + tx * sin(a) * side
                            spike(cx, cy, cx + ex * l, cy + ey * l, thick * 0.9, k, p.s, depth + side * thick * 0.3)
                        }
                        // the winding: a few thick turns across the wire
                        pieces += Piece(depth + thick * 0.5, layer) { g ->
                            val path = Path2D.Double().apply {
                                moveTo(cx - ty * thick * 1.1 - tx * thick * 0.4, cy + tx * thick * 1.1 - ty * thick * 0.4)
                                lineTo(cx + ty * thick * 1.1 + tx * thick * 0.4, cy - tx * thick * 1.1 + ty * thick * 0.4)
                            }
                            g.stroke = BasicStroke((thick * 1.15).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            g.color = color(k, p.s, depth, 0)
                            g.draw(path)
                            g.stroke = BasicStroke((thick * 0.75).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            g.color = color(k, p.s, depth, 1)
                            g.draw(path)
                        }
                    }
                }
            }
        }

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        for (piece in pieces.sortedWith(compareBy({ it.layer }, { it.depth }))) piece.draw(g)
        g.dispose()
        val drawn = (img.raster.dataBuffer as DataBufferInt).data

        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val ba = if (transparent) 0.0 else (base ushr 24) / 255.0
                // the wire over the background
                val c = drawn[i]
                val ca = (c ushr 24) / 255.0
                val a = ca + ba * (1 - ca)
                if (a <= 0.002) continue
                fun ch(sh: Int) = (((c shr sh and 0xFF) * ca + (base shr sh and 0xFF) * ba * (1 - ca)) / a + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((a * 255 + 0.5).toInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }
}
