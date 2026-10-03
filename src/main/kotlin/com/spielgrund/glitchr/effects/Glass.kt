package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
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
 * Patterned glass, like looking through a glass-block wall, reeded or hammered glass. Every
 * kind of glass is a height profile over the picture: its slope refracts (shifts where the
 * picture is seen) and catches the light. Two patterns can be laid over each other – blocks
 * with reeds in them, honeycombs with hammered dimples … – their slopes add up.
 *
 * On top: dispersion (the colors refract a little differently), frosting (a soft scatter),
 * a tint, the joints between blocks, facets and cells as bright or dark lines, and light
 * with a highlight. Along joints and facet edges the pixel is supersampled.
 */
object Glass : Effect("glass", "Glass", "Patterned glass: glass blocks, reeded glass, hammered glass, prisms, honeycomb … also combined") {
    private val patterns = listOf("None", "Glass blocks", "Reeded glass", "Waves", "Prisms", "Honeycomb", "Crystal", "Dimples", "Panes")
    private const val NONE = 0
    private const val BLOCKS = 1
    private const val REEDED = 2
    private const val WAVES = 3
    private const val PRISMS = 4
    private const val HONEYCOMB = 5
    private const val CRYSTAL = 6
    private const val DOTS = 7
    private const val PANES = 8

    /** Colors of architectural glass: amber, yellow, green, turquoise, blue, clear. */
    private val PANE_COLORS = ColorRamp(
        listOf(
            ColorStop(0.0, 0xFF8A1E), ColorStop(0.2, 0xFFD42A), ColorStop(0.4, 0x9BE05A), ColorStop(0.6, 0x2FD6C8),
            ColorStop(0.8, 0x3A8CFF), ColorStop(1.0, 0xF4FBFF),
        ),
    ).format()

    private const val PATTERN_TIP = "Glass blocks: pillows that show the picture shrunk and mirrored · Reeded glass: parallel round rods · " +
        "Waves: gentle waves · Prisms: pyramids with four facets · Honeycomb: hexagonal lenses · " +
        "Crystal: random flat facets · Dimples: round bumps · Panes: architectural glass – tall colored panes, slightly offset, with frames"

    override val params = listOf(
        Param.Heading("pattern1Heading", "Pattern 1"),
        Param.Choice("pattern1", "Glass", patterns, REEDED, PATTERN_TIP),
        Param.Slider("size1", "Size", 4, 1000, 24, " px", canvasMax = true),
        Param.Slider("strength1", "Refraction", -300, 300, 100, " %", "How far the slope shifts the picture – negative: curved the other way"),
        Param.Slider("angle1", "Angle", 0, 179, 0, "°"),
        Param.Heading("pattern2Heading", "Pattern 2"),
        Param.Choice("pattern2", "Glass", patterns, NONE, "Laid over pattern 1 – the slopes add up. $PATTERN_TIP"),
        Param.Slider("size2", "Size", 4, 1000, 12, " px", canvasMax = true),
        Param.Slider("strength2", "Refraction", -300, 300, 40, " %"),
        Param.Slider("angle2", "Angle", 0, 179, 90, "°"),
        Param.Heading("glassHeading", "Glass"),
        Param.Slider(
            "offset", "Offset", 0, 1000, 0, " px",
            "Every piece of glass shows the picture randomly shifted – mostly along the stripes, as with architectural glass", canvasMax = true,
        ),
        Param.Slider("dispersion", "Dispersion", 0, 100, 20, " %", "The colors are refracted differently – rainbow fringes at steep spots"),
        Param.Slider("frost", "Frosting", 0, 100, 0, " %", "Scatters the light – the picture behind becomes milky and blurred"),
        Param.Color("tint", "Glass color", 0xD6F2EC),
        Param.Slider("tintAmount", "Tint", 0, 100, 15, " %"),
        Param.Slider("lineWidth", "Joint width", 0, 200, 20, tip = "Lines between blocks, facets and cells, in tenths of a pixel: 20 = 2 px"),
        Param.Slider("lines", "Joints light/dark", -100, 100, -40, " %", "Positive: the joints glow bright · negative: they are dark"),
        Param.Heading("paneHeading", "Colored glass"),
        Param.Ramp(
            "paneColors", "Glass colors", PANE_COLORS,
            "Every piece of glass – pane, block, rod, cell, facet – gets a color from this gradient; overlapping ones mix",
        ),
        Param.Slider("paneColor", "Colorfulness", 0, 100, 0, " %", "Mixes its color into every piece of glass – like architectural colored glass"),
        Param.Slider("paneGlow", "Edge glow", 0, 100, 0, " %", "The edges of the pieces of glass glow in their color"),
        Param.Slider("reflection", "Reflection", 0, 100, 0, " %", "Slanted light reflections, different for every piece of glass"),
        Param.Heading("lightHeading", "Light"),
        Param.Slider("lightAngle", "Light direction", 0, 359, 225, "°"),
        Param.Slider("lightHeight", "Light height", 5, 90, 45, "°"),
        Param.Slider("shading", "Shading", 0, 100, 35, " %"),
        Param.Slider("gloss", "Gloss", 0, 100, 40, " %"),
        Param.Slider("glossSize", "Gloss size", 1, 100, 40, " %"),
        Param.Heading("outHeading", "Output"),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "4 × 4"), 2, "Joints and facet edges are sampled several times"),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    /** One pattern as set up: kind, size, refraction, direction. */
    private class Layer(val kind: Int, val size: Double, val strength: Double, angle: Double) {
        val cos = cos(angle)
        val sin = sin(angle)
    }

    /**
     * The pattern at (x, y): writes into [out] its slope (per unit of half its size, turned
     * back to the canvas), the distance to the nearest joint or facet edge in pixels
     * (MAX_VALUE: none), a cell id, whether that edge is drawn as a line (1) or only
     * smoothed (0), and for panes the pane's color on the ramp (0..1, else -1).
     */
    private fun pattern(layer: Layer, x: Double, y: Double, seed: Long, out: DoubleArray) {
        out[0] = 0.0; out[1] = 0.0; out[2] = Double.MAX_VALUE; out[3] = 0.0; out[4] = 0.0; out[5] = -1.0
        if (layer.kind == NONE) return
        val s = layer.size
        val half = s / 2
        // into the pattern's own direction
        val lx = x * layer.cos + y * layer.sin
        val ly = -x * layer.sin + y * layer.cos
        var gu = 0.0
        var gv = 0.0
        when (layer.kind) {
            BLOCKS, PRISMS, DOTS -> {
                val i = floor(lx / s)
                val j = floor(ly / s)
                val u = (lx - (i + 0.5) * s) / half
                val v = (ly - (j + 0.5) * s) / half
                out[2] = (1 - max(abs(u), abs(v))) * half
                out[3] = cellId(i.toLong(), j.toLong())
                when (layer.kind) {
                    BLOCKS -> {
                        // a pillow: (1 − u²)(1 − v²)
                        gu = -2 * u * (1 - v * v)
                        gv = -2 * v * (1 - u * u)
                        out[4] = 1.0
                    }
                    PRISMS -> {
                        // a pyramid: four flat facets, edges also along the diagonals
                        if (abs(u) > abs(v)) gu = -sign(u) * 1.5 else gv = -sign(v) * 1.5
                        out[2] = min(out[2], abs(abs(u) - abs(v)) * half / sqrt(2.0))
                        out[4] = 1.0
                    }
                    else -> {
                        // a round bump on a flat plate
                        val r = hypot(u, v)
                        val top = 0.8
                        if (r < top) {
                            val z = sqrt(max(0.05, top * top - r * r))
                            gu = -u / z
                            gv = -v / z
                        }
                        out[2] = abs(top - r) * half
                        out[4] = 0.0
                    }
                }
            }
            REEDED -> {
                // rods side by side: a half cylinder across each stripe
                val i = floor(lx / s)
                val u = ((lx - (i + 0.5) * s) / half).coerceIn(-0.97, 0.97)
                gu = -u / sqrt(1 - u * u) * 0.6
                out[2] = (1 - abs(u)) * half
                out[3] = i
                out[4] = 1.0
            }
            WAVES -> {
                // soft waves, crossed a little
                gu = cos(2 * PI * lx / s) * PI * 0.5
                gv = cos(2 * PI * ly / (s * 1.7)) * PI * 0.15
            }
            PANES -> {
                // tall panes side by side, of random width: the borders are jittered
                fun border(k: Long) = (k + 0.35 * (hash(k, 0, 5, seed) - 0.5) * 2) * s
                var i = floor(lx / s).toLong()
                if (lx < border(i)) i-- else if (lx >= border(i + 1)) i++
                val b0 = border(i)
                val b1 = border(i + 1)
                out[2] = min(lx - b0, b1 - lx)
                out[3] = i.toDouble()
                out[4] = 1.0
                out[5] = hash(i, 0, 6, seed)
                // each pane a little tilted: it shifts the view behind it
                gu = (hash(i, 0, 7, seed) - 0.5) * 1.2
                gv = (hash(i, 0, 8, seed) - 0.5) * 0.4
            }
            CRYSTAL -> {
                // jittered cells: the nearest centre and the distance to the border
                val gx = floor(lx / s).toLong()
                val gy = floor(ly / s).toLong()
                var best = Double.MAX_VALUE
                var bx = 0.0; var by = 0.0; var bid = 0.0
                for (j in gy - 1..gy + 1) for (i in gx - 1..gx + 1) {
                    val cx = (i + 0.5 + 0.8 * (hash(i, j, 1, seed) - 0.5)) * s
                    val cy = (j + 0.5 + 0.8 * (hash(i, j, 2, seed) - 0.5)) * s
                    val d = hypot(lx - cx, ly - cy)
                    if (d < best) { best = d; bx = cx; by = cy; bid = cellId(i, j) }
                }
                var border = Double.MAX_VALUE
                for (j in gy - 1..gy + 1) for (i in gx - 1..gx + 1) {
                    val cx = (i + 0.5 + 0.8 * (hash(i, j, 1, seed) - 0.5)) * s
                    val cy = (j + 0.5 + 0.8 * (hash(i, j, 2, seed) - 0.5)) * s
                    val ex = cx - bx
                    val ey = cy - by
                    val len = hypot(ex, ey)
                    if (len < 1e-9) continue
                    border = min(border, ((bx + cx) / 2 - lx) * ex / len + ((by + cy) / 2 - ly) * ey / len)
                }
                out[2] = border
                out[3] = bid
                // a flat facet with a random tilt
                val id = bid.toLong()
                val a = 2 * PI * hash(id, 0, 3, seed)
                val t = 0.4 + 1.2 * hash(id, 0, 4, seed)
                gu = cos(a) * t
                gv = sin(a) * t
                out[4] = 1.0
            }
            HONEYCOMB -> {
                // pointy hexagons, each a dome lens
                val r = half / (sqrt(3.0) / 2)
                val q = (sqrt(3.0) / 3 * lx - ly / 3) / r
                val rr = (2.0 / 3 * ly) / r
                var cq = Math.round(q).toDouble()
                var cr = Math.round(rr).toDouble()
                val cs = Math.round(-q - rr).toDouble()
                val dq = abs(cq - q)
                val dr = abs(cr - rr)
                val ds = abs(cs - (-q - rr))
                if (dq > dr && dq > ds) cq = -cr - cs else if (dr > ds) cr = -cq - cs
                val cx = r * (sqrt(3.0) * cq + sqrt(3.0) / 2 * cr)
                val cy = r * (1.5 * cr)
                val dx = lx - cx
                val dy = ly - cy
                val u = dx / half
                val v = dy / half
                gu = -2 * u
                gv = -2 * v
                // the sides face 0°, 60° and 120°
                var m = 0.0
                for (k in 0..2) {
                    val a = k * PI / 3
                    m = max(m, abs(dx * cos(a) + dy * sin(a)))
                }
                out[2] = half - m
                out[3] = cellId(cq.toLong(), cr.toLong())
                out[4] = 1.0
            }
        }
        // every piece of glass gets its color on the ramp (the waves have no pieces)
        if (out[5] < 0 && layer.kind != WAVES) out[5] = hash(out[3].toLong(), 2, 6, seed)
        // back to the canvas direction
        out[0] = gu * layer.cos - gv * layer.sin
        out[1] = gu * layer.sin + gv * layer.cos
    }

    private fun sign(a: Double) = if (a < 0) -1.0 else 1.0

    private fun cellId(i: Long, j: Long) = (i * 73856093L xor j * 19349663L).toDouble()

    /** A fixed pseudo-random number 0..1 per cell and channel. */
    private fun hash(i: Long, j: Long, k: Int, seed: Long): Double {
        var x = i * -0x61c8864680b583ebL + j * 0x5851F42D4C957F2DL + k * 0x14057B7EF767814FL + seed
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val layers = listOf(
            Layer(v["pattern1"], v["size1"].toDouble(), v["strength1"] / 100.0, Math.toRadians(v["angle1"].toDouble())),
            Layer(v["pattern2"], v["size2"].toDouble(), v["strength2"] / 100.0, Math.toRadians(v["angle2"].toDouble())),
        ).filter { it.kind != NONE }
        val amount = v["amount"] / 100.0
        val dispersion = v["dispersion"] / 100.0 * 0.35
        val frost = v["frost"] / 100.0
        val tint = v["tint"]
        val tintAmount = v["tintAmount"] / 100.0
        val lineWidth = v["lineWidth"] / 20.0
        val lines = v["lines"] / 100.0
        val shading = v["shading"] / 100.0
        val gloss = v["gloss"] / 100.0
        val shininess = 4 + (1 - v["glossSize"] / 100.0).pow(2) * 200
        val la = Math.toRadians(v["lightAngle"].toDouble())
        val le = Math.toRadians(v["lightHeight"].toDouble())
        val lx = cos(la) * cos(le)
        val ly = sin(la) * cos(le)
        val lz = sin(le)
        val hl = sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1))
        val hx = lx / hl
        val hy = ly / hl
        val hz = (lz + 1) / hl
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        val offset = v["offset"].toDouble()
        val paneRamp = ColorRamp.parse(v.text("paneColors").ifBlank { PANE_COLORS })
        val paneColor = v["paneColor"] / 100.0
        val paneGlow = v["paneGlow"] / 100.0
        val reflection = v["reflection"] / 100.0
        // frosting: a few fixed taps on a small disk
        val frostTaps = if (frost > 0) List(7) { k -> if (k == 0) 0.0 to 0.0 else cos(k * 2 * PI / 6 + 0.3) * (0.5 + 0.5 * (k % 2)) to sin(k * 2 * PI / 6 + 0.3) * (0.5 + 0.5 * (k % 2)) } else listOf(0.0 to 0.0)
        val frostRadius = frost * (layers.maxOfOrNull { it.size } ?: 20.0) * 0.15

        /** The glass at (x, y): writes r, g, b (0..255), alpha (0..1) and the edge distance into [res]. */
        fun shade(x: Double, y: Double, p: DoubleArray, res: DoubleArray) {
            var dx = 0.0
            var dy = 0.0
            var gx = 0.0
            var gy = 0.0
            var edge = Double.MAX_VALUE
            var lineEdge = Double.MAX_VALUE
            // colored panes: their filters multiply; the glow of the nearest pane edge; a reflex per pane
            var fr = 1.0; var fg = 1.0; var fb = 1.0
            var glow = 0.0; var glowR = 0.0; var glowG = 0.0; var glowB = 0.0
            var sheen = 0.0
            for (layer in layers) {
                pattern(layer, x, y, seed, p)
                if (p[5] >= 0) {
                    val c = paneRamp.lut[(p[5] * 255).roundToInt().coerceIn(0, 255)]
                    fr *= 1 + (red(c) / 255.0 - 1) * paneColor
                    fg *= 1 + (green(c) / 255.0 - 1) * paneColor
                    fb *= 1 + (blue(c) / 255.0 - 1) * paneColor
                    // the glass edge lights up in its color, over a few pixels
                    val e = (1 - p[2] / max(2.0, layer.size * 0.06)).coerceIn(0.0, 1.0)
                    if (e * e > glow) { glow = e * e; glowR = red(c).toDouble(); glowG = green(c).toDouble(); glowB = blue(c).toDouble() }
                    // a slanted reflex, shifted per pane
                    val band = sin((x * 0.45 + y) / (layer.size * 2.2) + p[5] * 11)
                    sheen = max(sheen, band.coerceAtLeast(0.0).pow(6) * (0.4 + 0.6 * hash(p[3].toLong(), 1, 9, seed)))
                }
                // refraction: shifted against the slope, by a quarter of the pattern's size per unit
                dx -= p[0] * layer.size * 0.25 * layer.strength
                dy -= p[1] * layer.size * 0.25 * layer.strength
                if (offset > 0 && layer.kind != WAVES) {
                    // every piece its own jump: mostly along the stripes (the pattern's local y), a little across
                    val id = p[3].toLong()
                    val along = (hash(id, 3, 10, seed) - 0.5) * 2 * offset
                    val across = (hash(id, 3, 11, seed) - 0.5) * 0.5 * offset
                    dx += across * layer.cos - along * layer.sin
                    dy += across * layer.sin + along * layer.cos
                }
                gx += p[0] * layer.strength.coerceIn(-1.5, 1.5)
                gy += p[1] * layer.strength.coerceIn(-1.5, 1.5)
                edge = min(edge, p[2])
                if (p[4] > 0) lineEdge = min(lineEdge, p[2])
            }
            var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
            for ((fx, fy) in frostTaps) {
                val bx = x + dx * amount + fx * frostRadius
                val by = y + dy * amount + fy * frostRadius
                if (dispersion > 0) {
                    // red bends least, blue most
                    val cr = sampleBilinear(src, x + dx * amount * (1 - dispersion) + fx * frostRadius, y + dy * amount * (1 - dispersion) + fy * frostRadius, Edge.MIRROR)
                    val cg = sampleBilinear(src, bx, by, Edge.MIRROR)
                    val cb = sampleBilinear(src, x + dx * amount * (1 + dispersion) + fx * frostRadius, y + dy * amount * (1 + dispersion) + fy * frostRadius, Edge.MIRROR)
                    r += red(cr); g += green(cg); b += blue(cb); a += alpha(cg)
                } else {
                    val c = sampleBilinear(src, bx, by, Edge.MIRROR)
                    r += red(c); g += green(c); b += blue(c); a += alpha(c)
                }
            }
            val n = frostTaps.size
            r /= n; g /= n; b /= n; a /= n * 255.0
            // colored panes
            if (fr != 1.0 || fg != 1.0 || fb != 1.0) {
                r += (r * fr - r) * amount; g += (g * fg - g) * amount; b += (b * fb - b) * amount
            }
            if (glow > 0 && paneGlow > 0) {
                val t = glow * paneGlow * amount
                r += (max(r, glowR) * 1.15 - r) * t; g += (max(g, glowG) * 1.15 - g) * t; b += (max(b, glowB) * 1.15 - b) * t
            }
            if (sheen > 0 && reflection > 0) {
                val t = sheen * reflection * amount * 255 * 0.8
                r += t; g += t; b += t
            }
            // tint
            if (tintAmount > 0) {
                val t = tintAmount * amount
                r += (r * ((tint shr 16) and 0xFF) / 255.0 - r) * t
                g += (g * ((tint shr 8) and 0xFF) / 255.0 - g) * t
                b += (b * (tint and 0xFF) / 255.0 - b) * t
            }
            // light on the glass surface
            if (layers.isNotEmpty() && (shading > 0 || gloss > 0)) {
                val nl = sqrt(gx * gx * 0.25 + gy * gy * 0.25 + 1)
                val nx = -gx * 0.5 / nl
                val ny = -gy * 0.5 / nl
                val nz = 1 / nl
                val lambert = max(0.0, nx * lx + ny * ly + nz * lz) / lz
                val diffuse = 1 + shading * amount * (min(1.4, lambert) - 1)
                val spec = gloss * amount * max(0.0, nx * hx + ny * hy + nz * hz).pow(shininess) * 255
                r = r * diffuse + spec; g = g * diffuse + spec; b = b * diffuse + spec
            }
            // the joints
            if (lines != 0.0 && lineWidth > 0 && lineEdge < Double.MAX_VALUE) {
                val line = ((lineWidth / 2 - lineEdge) / 0.6 + 0.5).coerceIn(0.0, 1.0) * abs(lines) * amount
                if (line > 0) {
                    val target = if (lines > 0) 255.0 else 0.0
                    r += (target - r) * line; g += (target - g) * line; b += (target - b) * line
                    a = max(a, line)
                }
            }
            res[0] = r; res[1] = g; res[2] = b; res[3] = a; res[4] = edge
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val p = DoubleArray(6)
            val res = DoubleArray(5)
            for (x in 0 until w) {
                shade(x + 0.5, y + 0.5, p, res)
                // near joints and facet edges the refraction jumps: sampled several times
                if (sub > 1 && res[4] < lineWidth / 2 + 1.5) {
                    var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
                    for (j in 0 until sub) for (i in 0 until sub) {
                        shade(x + (i + 0.5) / sub, y + (j + 0.5) / sub, p, res)
                        r += res[0] * res[3]; g += res[1] * res[3]; b += res[2] * res[3]; a += res[3]
                    }
                    if (a > 1e-9) { res[0] = r / a; res[1] = g / a; res[2] = b / a }
                    res[3] = a / (sub * sub)
                }
                out.data[y * w + x] = argb(
                    (res[3] * 255).roundToInt().coerceIn(0, 255),
                    res[0].roundToInt().coerceIn(0, 255), res[1].roundToInt().coerceIn(0, 255), res[2].roundToInt().coerceIn(0, 255),
                )
            }
        }
        return out
    }
}
