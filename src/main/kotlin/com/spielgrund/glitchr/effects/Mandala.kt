package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A mandala: concentric rings around a center, each filled with one motif (petals,
 * dots, arches, spikes, drops, rays, diamonds) repeated around the circle. The motifs
 * are symmetric in themselves, so every ring is mirror- and rotation-symmetric.
 *
 * Which motif a ring gets, how many times it repeats (the symmetry or twice that),
 * whether it is turned by half a step and the ring widths come from the seed – "Reroll"
 * gives a new mandala with the same settings. Only used as a generator.
 */
object Mandala : Effect("mandala", "Mandala", "Rings of symmetrically repeated motifs around a center") {
    private val motifs = listOf("Leaves", "Dots", "Arches", "Spikes", "Drops", "Rays", "Diamonds")

    override val params = listOf(
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("symmetry", "Symmetry", 3, 48, 12, tip = "How often every motif repeats around the middle (outer rings also twice as often)"),
        Param.Slider("rings", "Rings", 1, 24, 8),
        Param.Choice("motif", "Motif", listOf("Mixed (random)") + motifs, tip = "Mixed: every ring gets a random motif – “Reroll” gives a new mandala"),
        Param.Slider("size", "Size", 5, 150, 92, " %", "Radius relative to half the shorter side"),
        Param.Slider("growth", "Ring width outwards", 30, 300, 100, " %", "Below 100 %: outer rings narrower, above wider"),
        Param.Slider("variation", "Ring width variation", 0, 100, 30, " %"),
        Param.Slider("detail", "Ornament", 0, 100, 50, " %", "Inner outlines, veins and dots between the motifs"),
        Param.Toggle("separators", "Dividing circles", true, "A circle between the rings"),
        Param.Slider("rotation", "Rotation", 0, 359, 0, "°"),
        Param.Slider("centerX", "Center X", 0, 100, 50, " %"),
        Param.Slider("centerY", "Center Y", 0, 100, 50, " %"),
        Param.Heading("styleHeading", "Display"),
        Param.Choice("style", "Style", listOf("Lines", "Filled", "Filled with outline")),
        Param.Slider("lineWidth", "Line width", 1, 200, 20, " px", decimals = 1),
        Param.Choice(
            "colorMode", "Colors", listOf("One color", "Two colors alternating", "Gradient inside → outside", "Rainbow", "Pastel"),
            tip = "Color of the rings; for “Filled with outline” the fill",
        ),
        Param.Color("color1", "Color 1", 0xF5E6C8),
        Param.Color("color2", "Color 2", 0xD9487A),
        Param.Color("lineColor", "Outline color", 0x16121F, "For “Filled with outline”"),
        Param.Color("background", "Background", 0x16121F),
        Param.Toggle("transparent", "Transparent background", false),
    )

    /** One ring: from radius [r0] to [r1], [motif] repeated [count] times, turned by [offset] (radians). */
    private class Ring(val r0: Double, val r1: Double, val motif: Int, val count: Int, val offset: Double)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        if (!v.bool("transparent")) data.fill(v["background"] or 0xFF000000.toInt())
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.stroke = BasicStroke(v["lineWidth"] / 10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.translate(w * v["centerX"] / 100.0, h * v["centerY"] / 100.0)
        g.rotate(v["rotation"] * PI / 180)

        val rings = rings(v, min(w, h) / 2.0 * v["size"] / 100.0, random)
        val style = v["style"]
        val lineColor = Color(v["lineColor"])
        val detail = v["detail"] / 100.0
        // the heart: a round center inside the first ring
        rings.firstOrNull()?.let { first ->
            val r = first.r0 * 0.7
            draw(g, Ellipse2D.Double(-r, -r, 2 * r, 2 * r), false, style, ringColor(v, 0, rings.size), lineColor)
        }
        for ((i, ring) in rings.withIndex()) {
            val color = ringColor(v, i, rings.size)
            val shapes = motif(ring, detail, random)
            val step = 2 * PI / ring.count
            for (k in 0 until ring.count) {
                val turn = AffineTransform.getRotateInstance(ring.offset + k * step)
                for (shape in shapes) draw(g, turn.createTransformedShape(shape), shape is Line2D, style, color, lineColor)
            }
            if (v.bool("separators") && i < rings.size - 1) {
                val r = ring.r1
                val circle = Ellipse2D.Double(-r, -r, 2 * r, 2 * r)
                g.color = if (style == 2) lineColor else color
                g.draw(circle)
            }
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /** [open]: a line without area (rays, veins), always drawn as a line. */
    private fun draw(g: Graphics2D, shape: Shape, open: Boolean, style: Int, color: Color, lineColor: Color) {
        when {
            style == 0 || open -> { g.color = if (style == 2) lineColor else color; g.draw(shape) }
            style == 1 -> { g.color = color; g.fill(shape) }
            else -> { g.color = color; g.fill(shape); g.color = lineColor; g.draw(shape) }
        }
    }

    /** The ring layout: widths growing (or shrinking) outwards, a bit random; motifs, counts, offsets. */
    private fun rings(v: Values, radius: Double, random: Random): List<Ring> {
        val n = v["rings"]
        val symmetry = v["symmetry"]
        val growth = v["growth"] / 100.0
        val variation = v["variation"] / 100.0
        val fixed = v["motif"] - 1
        // widths: geometric growth from the inside out, each varied at random
        val widths = DoubleArray(n) { growth.pow(it.toDouble()) * (1 + (random.nextDouble() - 0.5) * variation) }
        val total = widths.sum()
        // the center gets a small round heart; the rings share the rest
        val heart = radius * 0.08
        var r = heart
        return List(n) { i ->
            val r0 = r
            r += (radius - heart) * widths[i] / total
            val motif = if (fixed >= 0) fixed else random.nextInt(motifs.size)
            // outer rings have room for twice as many motifs
            val double = i >= n / 2 && random.nextDouble() < 0.4
            val count = symmetry * if (double) 2 else 1
            val offset = if (random.nextBoolean()) PI / count else 0.0
            Ring(r0, r, motif, count, offset)
        }
    }

    /** Color of ring [i] of [n]. */
    private fun ringColor(v: Values, i: Int, n: Int): Color {
        val c1 = Color(v["color1"])
        val c2 = Color(v["color2"])
        val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
        return when (v["colorMode"]) {
            1 -> if (i % 2 == 0) c1 else c2
            2 -> Color(
                (c1.red + (c2.red - c1.red) * t).toInt(),
                (c1.green + (c2.green - c1.green) * t).toInt(),
                (c1.blue + (c2.blue - c1.blue) * t).toInt(),
            )
            3 -> Color(Color.HSBtoRGB(t * 0.85f, 0.85f, 1f))
            4 -> Color(Color.HSBtoRGB(t * 0.85f, 0.35f, 1f))
            else -> c1
        }
    }

    /**
     * The shapes of one motif of [ring], pointing along the positive x axis (angle 0);
     * they are symmetric to that axis. [detail] adds inner contours, veins and dots.
     */
    private fun motif(ring: Ring, detail: Double, random: Random): List<Shape> {
        val r0 = ring.r0
        val r1 = ring.r1
        val rm = (r0 + r1) / 2
        val dr = r1 - r0
        val half = PI / ring.count
        // half the width of the ring's slot at its middle
        val hw = rm * sin(half)
        val shapes = mutableListOf<Shape>()
        fun polar(r: Double, a: Double) = r * cos(a) to r * sin(a)
        fun path(block: Path2D.Double.() -> Unit) = Path2D.Double().apply(block)
        fun Path2D.Double.move(p: Pair<Double, Double>) = moveTo(p.first, p.second)
        fun Path2D.Double.line(p: Pair<Double, Double>) = lineTo(p.first, p.second)
        fun Path2D.Double.quad(c: Pair<Double, Double>, p: Pair<Double, Double>) = quadTo(c.first, c.second, p.first, p.second)

        /** The motif's outline, shrunk towards its middle by [s] (1 = full size). */
        fun outline(s: Double): Shape? {
            val a = rm - dr / 2 * s
            val b = rm + dr / 2 * s
            val wd = hw * s
            return when (ring.motif) {
                0 -> path { // petal: pointed at both ends, widest in the middle
                    moveTo(a, 0.0)
                    quadTo(rm, wd * 1.8, b, 0.0)
                    quadTo(rm, -wd * 1.8, a, 0.0)
                    closePath()
                }
                1 -> {
                    val d = min(dr / 2, hw) * 0.85 * s
                    Ellipse2D.Double(rm - d, -d, 2 * d, 2 * d)
                }
                2 -> path { // arch: a scallop over the slot, flat on the inner circle
                    val h = half * 0.98
                    move(polar(a, -h))
                    quad(b * 1.12 to 0.0, polar(a, h))
                    closePath()
                }
                3 -> path { // spike
                    move(polar(a, -half * 0.9 * s))
                    line(b to 0.0)
                    line(polar(a, half * 0.9 * s))
                    closePath()
                }
                4 -> path { // drop: round inside, pointed outside
                    val d = min(dr * 0.35, hw * 0.9) * s
                    val c = a + d
                    moveTo(b, 0.0)
                    quadTo(c + d * 0.2, d * 1.4, c - d, 0.0)
                    quadTo(c + d * 0.2, -d * 1.4, b, 0.0)
                    closePath()
                }
                5 -> if (s < 1) null else Line2D.Double(a, 0.0, b, 0.0) // ray
                else -> path { // diamond
                    moveTo(a, 0.0)
                    lineTo(rm, wd * 0.8)
                    lineTo(b, 0.0)
                    lineTo(rm, -wd * 0.8)
                    closePath()
                }
            }
        }

        outline(1.0)?.let(shapes::add)
        if (detail > 0) {
            // inner contour
            if (random.nextDouble() < detail && ring.motif != 5) outline(0.55)?.let(shapes::add)
            // vein along the middle of petals, drops and diamonds
            if (random.nextDouble() < detail && ring.motif in setOf(0, 4, 6)) shapes += Line2D.Double(r0 + dr * 0.2, 0.0, r1 - dr * 0.2, 0.0)
            // side rays next to a ray
            if (ring.motif == 5 && random.nextDouble() < detail) {
                val (x0, y0) = polar(r0 + dr * 0.3, half * 0.5)
                val (x1, y1) = polar(r1 - dr * 0.1, half * 0.5)
                shapes += Line2D.Double(x0, y0, x1, y1)
                shapes += Line2D.Double(x0, -y0, x1, -y1)
            }
            // a dot in the gap between two motifs, on the outer edge
            if (random.nextDouble() < detail * 0.8) {
                val d = max(0.8, min(dr, hw) * 0.12)
                val (x, y) = polar(r1 - d * 1.5, half)
                shapes += Ellipse2D.Double(x - d, y - d, 2 * d, 2 * d)
            }
        }
        return shapes
    }
}
