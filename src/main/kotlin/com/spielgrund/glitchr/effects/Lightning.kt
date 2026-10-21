package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightning: bolts zigzagging out from points (a list, each one dragged in the picture) and
 * along drawn arrows, or across the picture in a direction. Each bolt forks
 * into thinner branches; a bright core in a glow of its color.
 */
object Lightning : Effect("lightning", "Lightning", "Lightning bolts from a point, in a direction or along drawn arrows – forking, glowing") {
    private val modes = listOf("From points", "Along arrows", "In a direction")

    override val params = listOf(
        Param.Choice(
            "mode", "Bolts", modes, 0,
            "From points: bolts out of every point in the list (drag them in the picture) · Along arrows: a bolt along every arrow drawn in the picture · " +
                "In a direction: bolts across the picture",
        ),
        Param.Points("points", "Points", "0.5000,0.5000", "The points bolts start from", activeWhen = "mode" to 0),
        Param.Flow("strokes", "Arrows", "Drag in the picture: every arrow becomes a bolt along it; a short drag marks a point bolts start from", activeWhen = "mode" to 1),
        Param.Slider("count", "Bolts", 1, 40, 5, tip = "How many bolts – from every point (also short arrows), or across the picture"),
        Param.Slider("angle", "Direction", -18000, 18000, 9000, "°", "Where the bolts head: 90° = down", decimals = 2),
        Param.Slider("fan", "Fan", 0, 36000, 12000, "°", "How widely the bolts spread around the direction; 360° = all around the point", decimals = 2),
        Param.Slider("length", "Length", 500, 30000, 7000, " %", "Of the canvas size (from a point, in a direction)", decimals = 2, transform = true),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("jagged", "Jaggedness", 0, 100, 55, " %", "How wildly the bolts zigzag"),
        Param.Slider("branches", "Branches", 0, 100, 45, " %", "How often a bolt forks"),
        Param.Slider("branchLength", "Branch length", 5, 100, 45, " %", "Of the rest of the bolt"),
        Param.Slider("thickness", "Thickness", 20, 4000, 260, " px", "Of the main bolts; branches get thinner", decimals = 2),
        Param.Slider("taper", "Taper", 0, 100, 70, " %", "How much the bolts thin out towards their ends"),
        Param.Heading("lightHeading", "Light"),
        Param.Color("coreColor", "Core", 0xFFFFFF),
        Param.Color("color", "Glow color", 0x8FB4FF),
        Param.Slider("glow", "Glow", 0, 300, 100, " %"),
        Param.Slider("glowSize", "Glow size", 100, 30000, 2400, " px", decimals = 2),
        Param.Toggle("transparent", "Transparent background", false, "Only the bolts and their glow, without the base color"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val size = min(w, h).toDouble()
        val jag = v["jagged"] / 100.0
        val forks = v["branches"] / 100.0
        val branchLength = v["branchLength"] / 100.0
        val thick = v["thickness"] / 100.0
        val taper = v["taper"] / 100.0
        val dir = Math.toRadians(v["angle"] / 100.0)
        val fan = Math.toRadians(v["fan"] / 100.0)
        val length = v["length"] / 10000.0 * size
        val count = v["count"]

        // the bolts are drawn as white lines; their brightness is the core, blurred it becomes the glow
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE

        /** A zigzag line from (x0, y0) to (x1, y1): each half bent aside, again and again, down to a few pixels. */
        fun zigzag(x0: Double, y0: Double, x1: Double, y1: Double, out: MutableList<DoubleArray>) {
            val len = hypot(x1 - x0, y1 - y0)
            if (len < max(3.0, size * 0.006)) {
                out += doubleArrayOf(x1, y1)
                return
            }
            val nx = -(y1 - y0) / len
            val ny = (x1 - x0) / len
            val aside = (random.nextDouble() * 2 - 1) * len * (0.05 + 0.3 * jag)
            val mx = (x0 + x1) / 2 + nx * aside
            val my = (y0 + y1) / 2 + ny * aside
            zigzag(x0, y0, mx, my, out)
            zigzag(mx, my, x1, y1, out)
        }

        /** Draws [path] [width] wide at its start, thinning out towards its end. */
        fun stroke(path: List<DoubleArray>, width: Double) {
            for (i in 1 until path.size) {
                val t = i / (path.size - 1.0)
                val wd = max(0.35, width * (1 - taper * t))
                g.stroke = BasicStroke(wd.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(path[i - 1][0], path[i - 1][1], path[i][0], path[i][1]))
            }
        }

        /**
         * A bolt along [route] (at least two points): zigzagging between them, forking into thinner
         * branches that head off to the side, which may fork again ([depth]).
         */
        fun bolt(route: List<DoubleArray>, width: Double, depth: Int) {
            val path = ArrayList<DoubleArray>().apply { add(route[0]) }
            for (i in 1 until route.size) zigzag(route[i - 1][0], route[i - 1][1], route[i][0], route[i][1], path)
            stroke(path, width)
            if (depth <= 0 || forks <= 0) return
            val total = (1 until path.size).sumOf { hypot(path[it][0] - path[it - 1][0], path[it][1] - path[it - 1][1]) }
            var walked = 0.0
            for (i in 1 until path.size - 1) {
                walked += hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1])
                // forks more often near the start, where the bolt is strong
                if (random.nextDouble() > forks * 0.06 * (1.2 - walked / total)) continue
                val heading = atan2(path[i + 1][1] - path[i - 1][1], path[i + 1][0] - path[i - 1][0])
                val turn = heading + (if (random.nextBoolean()) 1 else -1) * (0.3 + random.nextDouble() * 0.6)
                val reach = (total - walked) * branchLength * (0.4 + random.nextDouble() * 0.6)
                if (reach < size * 0.02) continue
                val t = walked / total
                val here = width * (1 - taper * t)
                bolt(listOf(path[i], doubleArrayOf(path[i][0] + cos(turn) * reach, path[i][1] + sin(turn) * reach)), max(0.4, here * 0.55), depth - 1)
            }
        }

        /** A bolt from (x, y) heading [angle], [len] long – a few bends on the way so it wanders. */
        fun boltFrom(x: Double, y: Double, angle: Double, len: Double) {
            val route = ArrayList<DoubleArray>()
            route += doubleArrayOf(x, y)
            var a = angle
            var px = x
            var py = y
            val legs = 3
            repeat(legs) {
                a += (random.nextDouble() * 2 - 1) * 0.35 * jag
                px += cos(a) * len / legs
                py += sin(a) * len / legs
                route += doubleArrayOf(px, py)
            }
            bolt(route, thick, 3)
        }

        /** [count] bolts out of (x, y), spread over the fan around the direction. */
        fun fromPoint(x: Double, y: Double, len: Double) {
            for (k in 0 until count) {
                val spread = if (fan >= 2 * PI - 1e-6) (k + random.nextDouble() * 0.8) / count * 2 * PI
                else dir + (if (count == 1) 0.0 else (k / (count - 1.0) - 0.5) * fan) + (random.nextDouble() - 0.5) * fan / max(1, count) * 0.8
                boltFrom(x, y, spread, len * (0.6 + random.nextDouble() * 0.4))
            }
        }

        when (v["mode"]) {
            2 -> {
                // across the picture: starting beyond the edge they come from, spread across it
                val ux = cos(dir)
                val uy = sin(dir)
                val diag = hypot(w.toDouble(), h.toDouble())
                for (k in 0 until count) {
                    val side = ((k + 0.2 + random.nextDouble() * 0.6) / count - 0.5) * diag
                    val sx = w / 2.0 - ux * diag / 2 - uy * side
                    val sy = h / 2.0 - uy * diag / 2 + ux * side
                    boltFrom(sx, sy, dir + (random.nextDouble() - 0.5) * fan * 0.2, length * (0.7 + random.nextDouble() * 0.6))
                }
            }
            0 -> for (p in PointList.parse(v.text("points"))) fromPoint(p.x * w, p.y * h, length)
            else -> {
                for (stroke in FlowStrokes.parse(v.text("strokes"))) {
                    val pts = stroke.map { doubleArrayOf(it.x * w, it.y * h) }
                    val drawn = (1 until pts.size).sumOf { hypot(pts[it][0] - pts[it - 1][0], pts[it][1] - pts[it - 1][1]) }
                    if (drawn < size * 0.04) {
                        // a short drag: a point the bolts start from
                        fromPoint(pts[0][0], pts[0][1], length)
                    } else {
                        // a bolt along the arrow: through a few points of it
                        val route = ArrayList<DoubleArray>()
                        var walked = 0.0
                        var next = 0.0
                        val gap = drawn / 6
                        for (i in pts.indices) {
                            if (i > 0) walked += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
                            if (walked >= next || i == pts.lastIndex) { route += pts[i]; next += gap }
                        }
                        bolt(route, thick, 3)
                    }
                }
            }
        }
        g.dispose()

        // light: the core, and its blur as glow – additive, in the glow's color
        val core = FloatArray(w * h) { ((img.raster.dataBuffer as DataBufferInt).data[it] ushr 24) / 255f }
        val glow = v["glow"] / 100.0
        val glowSize = v["glowSize"] / 100.0
        val wide = if (glow > 0) Blur.gauss(core, w, h, glowSize) else null
        val near = if (glow > 0) Blur.gauss(core, w, h, max(1.0, glowSize / 5)) else null
        // a blurred thin line is faint: lifted back by how much it was spread
        val wideGain = glowSize / max(1.0, thick) * 0.9
        val nearGain = glowSize / 5 / max(1.0, thick) * 0.9
        val gc = v["color"]
        val cc = v["coreColor"]
        val out = Pixels(w, h)
        if (v.bool("transparent")) {
            // only the light: the glow's color as far as it reaches, the core's color in the middle
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val light = if (wide == null) 0.0 else (min(1.0, wide[i] * wideGain) * 0.6 + min(1.0, near!![i] * nearGain) * 0.6) * glow
                    val c = core[i].toDouble()
                    val a = min(1.0, light + c)
                    if (a <= 0.002) continue
                    // the core over the glow, as unblended colors (no dark fringes when laid over something)
                    val k = (c / a).coerceIn(0.0, 1.0)
                    fun ch(sh: Int) = ((gc shr sh and 0xFF) + ((cc shr sh and 0xFF) - (gc shr sh and 0xFF)) * k).toInt().coerceIn(0, 255)
                    out.data[i] = ((a * 255 + 0.5).toInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
            return out
        }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val light = if (wide == null) 0.0 else (min(1.0, wide[i] * wideGain) * 0.6 + min(1.0, near!![i] * nearGain) * 0.6) * glow
                fun ch(sh: Int): Int {
                    val b = (base shr sh and 0xFF).toDouble()
                    val lit = b + (gc shr sh and 0xFF) * light
                    // the core over it, towards the core color
                    val c = core[i].toDouble()
                    return (lit + ((cc shr sh and 0xFF) - lit) * c).toInt().coerceIn(0, 255)
                }
                val a = max(base ushr 24, (min(1.0, light + core[i]) * 255).toInt())
                out.data[i] = (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }
}
