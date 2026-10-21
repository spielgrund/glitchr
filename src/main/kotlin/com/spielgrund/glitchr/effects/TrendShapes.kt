package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.BasicStroke
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Trend shapes as on the flyers and websites of the early 2000s: swooshes, concentric
 * rings and arcs, halftone dot fields, four-pointed shine stars, round blobs, bundles of
 * speed lines and little circles and crosses scattered about – in the palettes of the time.
 */
object TrendShapes : Effect("trendshapes", "Trend shapes", "Early-2000s flyer shapes: swooshes, rings, halftones, shine stars, blobs and speed lines") {
    private val palettes = listOf(
        "Y2K orange" to intArrayOf(0xFF6A00, 0x1A1A1A, 0xFFB347, 0x7A7A7A),
        "Aqua" to intArrayOf(0x00A8E8, 0x7FDBFF, 0x003459, 0xC0F0FF),
        "Chrome" to intArrayOf(0x8C8C8C, 0xD8D8D8, 0x3A3A3A, 0xFFFFFF),
        "Candy" to intArrayOf(0xFF7AC6, 0x9BE15D, 0x6EC6FF, 0xFFE066),
        "Black" to intArrayOf(0x000000, 0x000000, 0x444444, 0x000000),
        "Custom" to intArrayOf(0, 0, 0, 0),
    )

    override val params = listOf(
        Param.Slider("size", "Size", 1000, 30000, 10000, " %", decimals = 2, transform = true),
        Param.Choice("palette", "Palette", palettes.map { it.first }, 0),
        Param.Color("color1", "Color 1", 0xFF2E88),
        Param.Color("color2", "Color 2", 0x2EC4FF),
        Param.Color("color3", "Color 3", 0x111111),
        Param.Slider("count", "Shapes", 1, 120, 22),
        Param.Slider("line", "Line width", 1, 40, 4, " px"),
        Param.Heading("mixHeading", "Mix"),
        Param.Slider("swoosh", "Swooshes", 0, 100, 60, " %"),
        Param.Slider("rings", "Rings", 0, 100, 60, " %"),
        Param.Slider("halftone", "Halftone dots", 0, 100, 40, " %"),
        Param.Slider("stars", "Shine stars", 0, 100, 40, " %"),
        Param.Slider("blobs", "Blobs", 0, 100, 30, " %"),
        Param.Slider("lines", "Speed lines", 0, 100, 40, " %"),
        Param.Slider("bits", "Small bits", 0, 100, 50, " %", "Little circles, crosses and dots scattered around"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val random = Random(seed)
        val colors = palettes[v["palette"]].second.let { p -> if (v["palette"] == palettes.size - 1) intArrayOf(v["color1"], v["color2"], v["color3"], v["color1"]) else p }
        val scale = min(w, h) * (v["size"] / 100.0) / 100.0
        val line = v["line"].toFloat()
        val kinds = listOf("swoosh", "rings", "halftone", "stars", "blobs", "lines", "bits").map { v[it].toDouble() }
        val total = kinds.sum()
        fun pick(): Int {
            var r = random.nextDouble() * total
            for ((i, k) in kinds.withIndex()) { r -= k; if (r < 0) return i }
            return kinds.size - 1
        }
        fun anyColor() = colors[random.nextInt(colors.size)]

        return drawOn(src) { g ->
            if (total <= 0) return@drawOn
            repeat(v["count"]) {
                val x = random.nextDouble() * w
                val y = random.nextDouble() * h
                val s = scale * (0.15 + random.nextDouble() * 0.6)
                val c = anyColor()
                when (pick()) {
                    0 -> {
                        // a swoosh: a crescent band between two curves, fading out to one end
                        val a = random.nextDouble() * 2 * PI
                        val len = s * 2.2
                        val bend = s * (0.4 + random.nextDouble() * 0.5)
                        val thick = s * (0.08 + random.nextDouble() * 0.15)
                        val ux = cos(a); val uy = sin(a)
                        val nx = -uy; val ny = ux
                        val x0 = x - ux * len / 2; val y0 = y - uy * len / 2
                        val x1 = x + ux * len / 2; val y1 = y + uy * len / 2
                        val p = GeneralPath()
                        p.moveTo(x0, y0)
                        p.quadTo(x + nx * bend, y + ny * bend, x1, y1)
                        p.quadTo(x + nx * (bend - thick * 2), y + ny * (bend - thick * 2), x0, y0)
                        p.closePath()
                        g.paint = java.awt.GradientPaint(x0.toFloat(), y0.toFloat(), color(c), x1.toFloat(), y1.toFloat(), color(c, 0.0))
                        g.fill(p)
                    }
                    1 -> {
                        // concentric rings, some only arcs
                        g.color = color(c)
                        val rings = 2 + random.nextInt(5)
                        val gap = s / rings * 0.5
                        g.stroke = BasicStroke(line * (0.5f + random.nextFloat()))
                        for (k in 1..rings) {
                            val r = gap * k + s * 0.1
                            if (random.nextDouble() < 0.4) {
                                g.draw(java.awt.geom.Arc2D.Double(x - r, y - r, 2 * r, 2 * r, random.nextDouble() * 360, 90 + random.nextDouble() * 200, java.awt.geom.Arc2D.OPEN))
                            } else g.draw(Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r))
                        }
                    }
                    2 -> {
                        // a field of halftone dots, shrinking in one direction, in a circle
                        g.color = color(c)
                        val step = max(4.0, s / 9)
                        val a = random.nextDouble() * 2 * PI
                        val ux = cos(a); val uy = sin(a)
                        var gy = -s
                        while (gy <= s) {
                            var gx = -s
                            while (gx <= s) {
                                if (gx * gx + gy * gy <= s * s) {
                                    val t = ((gx * ux + gy * uy) / s + 1) / 2
                                    val r = step * 0.5 * t
                                    if (r > 0.4) g.fill(Ellipse2D.Double(x + gx - r, y + gy - r, 2 * r, 2 * r))
                                }
                                gx += step
                            }
                            gy += step
                        }
                    }
                    3 -> {
                        // a four-pointed shine star with curved sides
                        val r = s * (0.25 + random.nextDouble() * 0.4)
                        val inner = r * 0.12
                        val p = Path2D.Double()
                        p.moveTo(x, y - r)
                        p.quadTo(x + inner, y - inner, x + r, y)
                        p.quadTo(x + inner, y + inner, x, y + r)
                        p.quadTo(x - inner, y + inner, x - r, y)
                        p.quadTo(x - inner, y - inner, x, y - r)
                        g.color = color(c)
                        g.fill(p)
                    }
                    4 -> {
                        // a round, soft blob
                        val points = 7
                        val radii = DoubleArray(points) { s * (0.35 + random.nextDouble() * 0.35) }
                        val p = Path2D.Double()
                        fun pt(k: Int): Pair<Double, Double> {
                            val a = 2 * PI * k / points
                            return (x + cos(a) * radii[Math.floorMod(k, points)]) to (y + sin(a) * radii[Math.floorMod(k, points)])
                        }
                        // smooth closed curve through the midpoints
                        fun mid(k: Int): Pair<Double, Double> { val a = pt(k); val b = pt(k + 1); return ((a.first + b.first) / 2) to ((a.second + b.second) / 2) }
                        val start = mid(0)
                        p.moveTo(start.first, start.second)
                        for (k in 1..points) { val c1 = pt(k); val m = mid(k); p.quadTo(c1.first, c1.second, m.first, m.second) }
                        p.closePath()
                        g.color = color(c, 0.85)
                        g.fill(p)
                    }
                    5 -> {
                        // a bundle of parallel, curved speed lines
                        g.color = color(c)
                        val a = random.nextDouble() * 2 * PI
                        val ux = cos(a); val uy = sin(a)
                        val nx = -uy; val ny = ux
                        val bend = s * (random.nextDouble() - 0.5)
                        val lines = 4 + random.nextInt(8)
                        for (k in 0 until lines) {
                            val off = (k - lines / 2.0) * line * 2.5
                            val len = s * (1.2 + random.nextDouble())
                            g.stroke = BasicStroke(line * 0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            val p = GeneralPath()
                            p.moveTo(x - ux * len + nx * off, y - uy * len + ny * off)
                            p.quadTo(x + nx * (off + bend), y + ny * (off + bend), x + ux * len + nx * off, y + uy * len + ny * off)
                            g.draw(p)
                        }
                    }
                    else -> {
                        // little bits: circles, crosses, dots
                        g.color = color(c)
                        g.stroke = BasicStroke(line * 0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        val r = s * 0.08 + 2
                        when (random.nextInt(3)) {
                            0 -> g.draw(Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r))
                            1 -> {
                                g.draw(java.awt.geom.Line2D.Double(x - r, y, x + r, y))
                                g.draw(java.awt.geom.Line2D.Double(x, y - r, x, y + r))
                            }
                            else -> g.fill(Ellipse2D.Double(x - r / 2, y - r / 2, r, r))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Y2K chrome symbols as their own generator: circle flowers, shine stars, linked balls,
 * swirls, spiky bursts, eyes, striped spheres, planets, hearts, H-swooshes, lenses – inflated
 * and shaded like polished chrome mirroring sky, horizon and ground; turnable in the picture
 * plane while the light stays, with anti-aliasing.
 */
object ChromeSymbols : Effect("chromeshapes", "Chrome shapes", "Y2K chrome symbols – flowers, stars, swirls, planets, hearts … – in polished chrome, turnable in space, anti-aliased") {
    override val params = listOf(
        Param.Choice("layout", "Layout", listOf("Grid", "Scattered", "One big"), 2),
        Param.Slider("symbol", "Symbol", 0, ChromeShapes.COUNT, 0, tip = "Which shape: 1 to ${ChromeShapes.COUNT}; 0 = chosen by the seed (Reroll), a different one each in grid and scattered"),
        Param.Icon("icon", "Own icon", "Your own symbol instead of the built-in ones: copy SVG code, an .svg file or a black-and-white graphic and press Paste"),
        Param.Choice(
            "iconParts", "Icon parts", listOf("Auto", "Dark parts", "Light parts", "Opaque parts"), 0,
            "Which parts of a pasted picture make the symbol – auto: the opaque ones of a transparent picture, otherwise whatever differs from the background at the border, lighter or darker",
        ),
        Param.Slider("iconContrast", "Icon contrast", -100, 200, 50, " %", "Of the pasted picture before it becomes the symbol: higher separates the parts more sharply"),
        Param.Slider("iconBrightness", "Icon brightness", -100, 100, 0, " %", "Of the pasted picture before it becomes the symbol: shifts which parts count"),
        Param.Slider("iconWidth", "Line width", -100, 100, 0, " %", "Makes the icon's lines and shapes bolder or thinner – also for SVG"),
        Param.Slider("iconSmooth", "Smooth outline", 0, 100, 0, " %", "Rounds the outline of a pasted picture: wavy or stepped edges of small logos become smooth curves, corners round"),
        Param.Slider("count", "Symbols", 1, 80, 12, tip = "Grid and scattered"),
        Param.Slider("size", "Size", 1000, 30000, 10000, " %", decimals = 2, transform = true),
        Param.Slider("turnX", "Turn X", -18000, 18000, 0, "°", "Tips the symbols forwards or back in space; the surroundings stay, so the reflections slide over the metal", decimals = 2),
        Param.Slider("turnY", "Turn Y", -18000, 18000, 0, "°", "Turns the symbols sideways in space – past 90° you see the back", decimals = 2),
        Param.Slider("rotation", "Rotation (Z)", -18000, 18000, 0, "°", "Turns the symbols in the picture plane", decimals = 2),
        Param.Choice("tint", "Metal", listOf("Silver", "Gold", "Holographic", "Blue steel"), 0),
        Param.Slider("contrast", "Metal contrast", -100, 100, 0, " %", "Of the metal: harder or softer reflections"),
        Param.Slider("brightness", "Metal brightness", -100, 100, 0, " %", "Of the metal"),
        Param.Slider("bulge", "Bulge", 0, 100, 50, " %", "How round and deep the metal looks (without thickness)"),
        Param.Slider("thickness", "Thickness", 0, 100, 0, " %", "A real body with depth, puffed up like a foil balloon – turned in space you see its round sides; 0 = a flat bulged plate"),
        Param.Slider("crinkle", "Crinkles", 0, 100, 0, " %", "Folds pulling in from the edge across the balloon, as on an inflatable"),
        Param.Slider("crinkleSize", "Crinkle spacing", 3, 100, 12, " %", "Distance between the folds along the edge, of the symbol"),
        Param.Slider("crinkleReach", "Crinkle reach", 5, 100, 45, " %", "How far the folds pull in towards the middle"),
        Param.Slider("crinkleAngle", "Crinkle angle", -7500, 7500, 0, "°", "Slants the folds: 0° runs straight in from the edge, positive and negative lean them to either side", decimals = 2),
        Param.Slider("crinklePull", "Edge pull", 0, 100, 50, " %", "Where a fold reaches the outline it pulls the edge inwards – 0 keeps the outline smooth"),
        Param.Toggle("transparent", "Transparent background", false, "Only the symbols, on transparency"),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "3 × 3", "4 × 4"), 0, "Renders finer and averages: smooth edges, horizon line and stripes"),
    )

    /** How a pasted icon becomes the symbol, from the icon settings. */
    internal fun iconLook(v: Values) = CustomIcon.Look(v["iconParts"], v["iconContrast"] / 100.0, v["iconBrightness"] / 100.0, v["iconWidth"] / 100.0, v["iconSmooth"] / 100.0)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels = ChromeShapes.draw(
        if (v.bool("transparent")) Pixels(src.width, src.height) else src, seed, v["layout"], v["count"], (v["size"] / 100.0) / 100.0, v["symbol"],
        Math.toRadians(v["turnX"] / 100.0), Math.toRadians(v["turnY"] / 100.0), Math.toRadians(v["rotation"] / 100.0),
        v["tint"], v["bulge"] / 100.0, v["antialias"] + 1, v["thickness"] / 100.0, v["crinkle"] / 100.0, v["crinkleSize"] / 100.0, v["crinkleReach"] / 100.0,
        Math.toRadians(v["crinkleAngle"] / 100.0), v["crinklePull"] / 100.0, CustomIcon.decode(v.text("icon"), iconLook(v)),
        v["contrast"] / 100.0, v["brightness"] / 100.0,
    )
}
