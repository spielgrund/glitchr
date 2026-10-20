package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.BasicStroke
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Flowers drawn from petals: roses in layers around a curled heart, tulip cups on a stem,
 * daisies, sunflowers with their seed spiral, five-petalled cherry blossoms, pointed lotus
 * petals and broad poppies – alone in the middle, scattered over the picture or gathered
 * into a bouquet, in natural colors or your own, with soft shading.
 */
object Flowers : Effect("flowers", "Flowers", "Roses, tulips, daisies, sunflowers, cherry blossoms, lotus and poppies – alone, scattered or as a bouquet") {
    private val kinds = listOf("Rose", "Tulip", "Daisy", "Sunflower", "Cherry blossom", "Lotus", "Poppy", "Mixed")
    private const val ROSE = 0
    private const val TULIP = 1
    private const val DAISY = 2
    private const val SUNFLOWER = 3
    private const val CHERRY = 4
    private const val LOTUS = 5
    private const val POPPY = 6

    override val params = listOf(
        Param.Choice("kind", "Flower", kinds, 0),
        Param.Choice("layout", "Layout", listOf("One in the middle", "Scattered", "Bouquet"), 0),
        Param.Slider("count", "Count", 1, 200, 12, tip = "Scattered and bouquet"),
        Param.Slider("size", "Size", 3, 2000, 160, " px", "Radius of a flower", canvasMax = true),
        Param.Slider("variation", "Size variation", 0, 100, 40, " %"),
        Param.Toggle("stems", "Stems and leaves", true),
        Param.Slider("shading", "Shading", 0, 100, 60, " %", "Darker at the heart, lighter at the tips"),
        Param.Slider("outline", "Outline", 0, 20, 0, " px"),
        Param.Choice("colors", "Colors", listOf("Natural", "Custom"), 0),
        Param.Color("petal", "Petal color", 0xE8305A),
        Param.Color("heart", "Heart color", 0xF5C518),
        Param.Color("leaf", "Leaf color", 0x3E8E3A),
    )

    private class Look(val petal: Int, val heart: Int, val leaf: Int, val shading: Double, val outline: Float)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val random = Random(seed)
        val size = v["size"].toDouble()
        val variation = v["variation"] / 100.0
        val kindChoice = v["kind"]
        val custom = v["colors"] == 1
        val stems = v.bool("stems")
        val shading = v["shading"] / 100.0
        val outline = v["outline"].toFloat()

        fun kindOf() = if (kindChoice == kinds.size - 1) random.nextInt(kinds.size - 1) else kindChoice
        fun lookOf(kind: Int): Look {
            if (custom) return Look(v["petal"], v["heart"], v["leaf"], shading, outline)
            val petal = when (kind) {
                ROSE -> listOf(0xB3122E, 0xD81B60, 0xF06292, 0xFFF1E6, 0xFFB74D)[random.nextInt(5)]
                TULIP -> listOf(0xE53935, 0xFDD835, 0xEC407A, 0x8E24AA, 0xFF7043)[random.nextInt(5)]
                DAISY -> 0xFFFFFF
                SUNFLOWER -> 0xFFC107
                CHERRY -> listOf(0xFFC1D9, 0xFFB0CC, 0xFFE0EC)[random.nextInt(3)]
                LOTUS -> 0xF48FB1
                else -> listOf(0xE53935, 0xFF7043, 0xD32F2F)[random.nextInt(3)]
            }
            val heart = when (kind) {
                DAISY -> 0xFBC02D
                SUNFLOWER -> 0x5D3A1A
                CHERRY -> 0xC2185B
                LOTUS -> 0xFDD835
                POPPY -> 0x1A1A1A
                else -> petal
            }
            return Look(petal, heart, 0x3E8E3A + random.nextInt(0x10) * 0x000100, shading, outline)
        }

        // where the flowers go: (x, y, radius), plus the point their stems run to
        data class Spot(val x: Double, val y: Double, val r: Double, val kind: Int)
        val spots = ArrayList<Spot>()
        var stemTo = w / 2 to h * 1.05
        fun radius() = size * (1 - variation * random.nextDouble())
        when (v["layout"]) {
            1 -> repeat(v["count"]) { spots += Spot(random.nextDouble() * w, random.nextDouble() * h, radius(), kindOf()) }
            2 -> {
                // a dome of flowers above the hand that holds them
                stemTo = w / 2 to h * 0.98
                repeat(v["count"]) {
                    val a = PI + random.nextDouble() * PI
                    val d = sqrt(random.nextDouble())
                    spots += Spot(w / 2 + cos(a) * d * w * 0.38, h * 0.55 + sin(a) * d * h * 0.42, radius(), kindOf())
                }
                spots.sortBy { it.y }
            }
            else -> spots += Spot(w / 2, h / 2, size, kindOf())
        }

        return drawOn(src) { g ->
            // stems and leaves first, behind all flowers
            if (stems) for (s in spots) {
                val look = lookOf(s.kind)
                stem(g, s.x, s.y + s.r * (if (s.kind == TULIP) 0.9 else 0.2), stemTo.first + (s.x - stemTo.first) * 0.15, stemTo.second, s.r, look, random)
            }
            for (s in spots) {
                val look = lookOf(s.kind)
                val turn = random.nextDouble() * 2 * PI
                when (s.kind) {
                    ROSE -> rose(g, s.x, s.y, s.r, turn, look, random)
                    TULIP -> tulip(g, s.x, s.y, s.r, look)
                    DAISY -> daisy(g, s.x, s.y, s.r, turn, look, 21, 0.13, 0.26)
                    SUNFLOWER -> sunflower(g, s.x, s.y, s.r, turn, look)
                    CHERRY -> cherry(g, s.x, s.y, s.r, turn, look)
                    LOTUS -> lotus(g, s.x, s.y, s.r, turn, look)
                    else -> poppy(g, s.x, s.y, s.r, turn, look, random)
                }
            }
        }
    }

    /**
     * A petal pointing up from (0, 0): [len] long, [wide] at its widest; [round] 0 = pointed
     * tip, 1 = round; [notch] cuts a little dent into the tip (cherry blossoms).
     */
    private fun petal(len: Double, wide: Double, round: Double, notch: Double = 0.0): Path2D.Double {
        val p = Path2D.Double()
        val hw = wide / 2
        p.moveTo(0.0, 0.0)
        p.curveTo(hw * 1.1, -len * 0.2, hw * (0.6 + round * 0.6), -len * (0.95 - round * 0.1), notch * hw, -len * (1 - notch * 0.12))
        if (notch > 0) {
            // the dent in the tip
            p.lineTo(0.0, -len * (1 - notch * 0.25))
            p.lineTo(-notch * hw, -len * (1 - notch * 0.12))
        }
        p.curveTo(-hw * (0.6 + round * 0.6), -len * (0.95 - round * 0.1), -hw * 1.1, -len * 0.2, 0.0, 0.0)
        p.closePath()
        return p
    }

    /** Fills [shape] (drawn in a frame turned by [angle] at (x, y)) with [rgb], darker towards its base by the look's shading. */
    private fun fillPetal(g: Graphics2D, shape: Path2D.Double, x: Double, y: Double, angle: Double, len: Double, rgb: Int, look: Look, lighter: Int = 0xFFFFFF) {
        val old = g.transform
        g.transform(AffineTransform.getTranslateInstance(x, y))
        g.transform(AffineTransform.getRotateInstance(angle))
        val base = mix(rgb, 0x000000, 0.45 * look.shading)
        val tip = mix(rgb, lighter, 0.25 * look.shading)
        g.paint = GradientPaint(0f, 0f, color(base), 0f, (-len).toFloat(), color(tip))
        g.fill(shape)
        if (look.outline > 0) {
            g.stroke = BasicStroke(look.outline, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = color(mix(rgb, 0x000000, 0.55))
            g.draw(shape)
        }
        g.transform = old
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t.coerceIn(0.0, 1.0)).toInt() shl s
        return ch(16) or ch(8) or ch(0)
    }

    private fun disc(g: Graphics2D, x: Double, y: Double, r: Double, rgb: Int, look: Look) {
        g.paint = RadialGradientPaint(
            java.awt.geom.Point2D.Double(x - r * 0.2, y - r * 0.2), r.toFloat().coerceAtLeast(0.5f), floatArrayOf(0f, 1f),
            arrayOf(color(mix(rgb, 0xFFFFFF, 0.25 * look.shading)), color(mix(rgb, 0x000000, 0.4 * look.shading))),
        )
        g.fill(Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r))
    }

    private fun rose(g: Graphics2D, x: Double, y: Double, r: Double, turn: Double, look: Look, random: Random) {
        // rings of broad petals, smaller and turned further towards the heart
        val rings = listOf(Triple(5, 1.0, 0.95), Triple(5, 0.78, 0.9), Triple(5, 0.56, 0.85), Triple(4, 0.38, 0.8))
        for ((k, ring) in rings.withIndex()) {
            val (n, len, wide) = ring
            for (i in 0 until n) {
                val a = turn + k * 0.6 + 2 * PI * i / n
                val shade = mix(look.petal, 0x000000, 0.12 * k * look.shading)
                fillPetal(g, petal(r * len, r * len * wide * 1.1, 1.0), x, y, a, r * len, shade, look)
            }
        }
        // the curled heart: arcs winding inwards
        g.stroke = BasicStroke((r * 0.035).toFloat().coerceAtLeast(0.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = color(mix(look.petal, 0x000000, 0.5))
        var rr = r * 0.3
        var a = turn
        while (rr > r * 0.03) {
            g.draw(java.awt.geom.Arc2D.Double(x - rr, y - rr, 2 * rr, 2 * rr, Math.toDegrees(a), 200.0, java.awt.geom.Arc2D.OPEN))
            a += 2.2 + random.nextDouble() * 0.4
            rr *= 0.72
        }
    }

    private fun tulip(g: Graphics2D, x: Double, y: Double, r: Double, look: Look) {
        // a cup seen from the side: two petals behind, one broad in front
        val len = r * 1.5
        fillPetal(g, petal(len, r * 0.9, 0.85), x - r * 0.28, y + r * 0.75, -0.28, len, mix(look.petal, 0x000000, 0.18), look)
        fillPetal(g, petal(len, r * 0.9, 0.85), x + r * 0.28, y + r * 0.75, 0.28, len, mix(look.petal, 0x000000, 0.18), look)
        fillPetal(g, petal(len * 0.98, r * 1.05, 0.9), x, y + r * 0.8, 0.0, len, look.petal, look)
    }

    private fun daisy(g: Graphics2D, x: Double, y: Double, r: Double, turn: Double, look: Look, n: Int, wide: Double, heart: Double) {
        for (i in 0 until n) fillPetal(g, petal(r, r * wide * 2, 0.9), x, y, turn + 2 * PI * i / n, r, look.petal, look, 0xFFFFFF)
        disc(g, x, y, r * heart, look.heart, look)
        // a fine dotted texture on the heart
        g.color = color(mix(look.heart, 0x000000, 0.35), 0.6)
        val dots = 30
        for (k in 0 until dots) {
            val a = k * 2.39996
            val d = sqrt(k / dots.toDouble()) * r * heart * 0.85
            val s = r * heart * 0.06
            g.fill(Ellipse2D.Double(x + cos(a) * d - s, y + sin(a) * d - s, 2 * s, 2 * s))
        }
    }

    private fun sunflower(g: Graphics2D, x: Double, y: Double, r: Double, turn: Double, look: Look) {
        for (layer in 0..1) {
            val n = 24
            for (i in 0 until n) {
                val a = turn + (i + layer * 0.5) * 2 * PI / n
                fillPetal(g, petal(r * (1 - layer * 0.1), r * 0.24, 0.2), x, y, a, r, mix(look.petal, 0xE65100, 0.25 * layer), look)
            }
        }
        disc(g, x, y, r * 0.45, look.heart, look)
        // the seed spiral (golden angle)
        val seeds = 160
        g.color = color(mix(look.heart, 0x000000, 0.45))
        for (k in 1..seeds) {
            val a = k * 2.39996 + turn
            val d = sqrt(k / seeds.toDouble()) * r * 0.42
            val s = r * 0.012 + r * 0.012 * (d / (r * 0.42))
            g.fill(Ellipse2D.Double(x + cos(a) * d - s, y + sin(a) * d - s, 2 * s, 2 * s))
        }
    }

    private fun cherry(g: Graphics2D, x: Double, y: Double, r: Double, turn: Double, look: Look) {
        for (i in 0 until 5) fillPetal(g, petal(r, r * 0.95, 1.0, 0.35), x, y, turn + 2 * PI * i / 5, r, look.petal, look)
        // stamens: thin lines with dots
        g.stroke = BasicStroke((r * 0.02).toFloat().coerceAtLeast(0.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (k in 0 until 12) {
            val a = turn + k * 2 * PI / 12
            val d = r * 0.32
            g.color = color(look.heart)
            g.draw(java.awt.geom.Line2D.Double(x, y, x + cos(a) * d, y + sin(a) * d))
            val s = r * 0.035
            g.color = color(0xFFE082)
            g.fill(Ellipse2D.Double(x + cos(a) * d - s, y + sin(a) * d - s, 2 * s, 2 * s))
        }
        disc(g, x, y, r * 0.08, look.heart, look)
    }

    private fun lotus(g: Graphics2D, x: Double, y: Double, r: Double, turn: Double, look: Look) {
        for (layer in 0..2) {
            val n = 8
            val len = r * (1 - layer * 0.22)
            for (i in 0 until n) {
                val a = turn + (i + layer * 0.5) * 2 * PI / n
                // white at the base, colored to the pointed tip
                fillPetal(g, petal(len, len * 0.42, 0.1), x, y, a, len, mix(look.petal, 0xFFFFFF, 0.2 * layer), look)
            }
        }
        disc(g, x, y, r * 0.16, look.heart, look)
    }

    private fun poppy(g: Graphics2D, x: Double, y: Double, r: Double, turn: Double, look: Look, random: Random) {
        for (i in 0 until 4) fillPetal(g, petal(r, r * 1.35, 1.0), x, y, turn + i * PI / 2 + random.nextDouble() * 0.2, r, look.petal, look)
        disc(g, x, y, r * 0.2, look.heart, look)
        g.color = color(0x111111)
        for (k in 0 until 18) {
            val a = k * 2 * PI / 18
            val d = r * 0.28
            val s = r * 0.025
            g.fill(Ellipse2D.Double(x + cos(a) * d - s, y + sin(a) * d - s, 2 * s, 2 * s))
        }
    }

    /** A stem curving from the flower down to (tx, ty), with a leaf or two. */
    private fun stem(g: Graphics2D, x: Double, y: Double, tx: Double, ty: Double, r: Double, look: Look, random: Random) {
        val width = (r * 0.07).toFloat().coerceAtLeast(1f)
        val bend = (random.nextDouble() - 0.5) * r
        val path = Path2D.Double()
        path.moveTo(x, y)
        path.quadTo((x + tx) / 2 + bend, (y + ty) / 2, tx, ty)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = color(mix(look.leaf, 0x000000, 0.15))
        g.draw(path)
        repeat(1 + random.nextInt(2)) {
            val t = 0.3 + random.nextDouble() * 0.4
            val lx = x + (tx - x) * t + bend * 0.5
            val ly = y + (ty - y) * t
            val side = if (random.nextBoolean()) 1.0 else -1.0
            val len = r * (0.9 + random.nextDouble() * 0.6)
            fillPetal(g, petal(len, len * 0.32, 0.05), lx, ly, side * (0.7 + random.nextDouble() * 0.5), len, look.leaf, look, 0xB9F6CA)
        }
    }
}
