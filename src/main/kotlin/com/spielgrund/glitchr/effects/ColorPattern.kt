package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.image.nearestColor
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Tile patterns in the picture's most prominent colors: stripes, checkerboard, hexagons, rings and more. */
object ColorPattern : TilePattern(
    "pattern", "Farbmuster", "Geometrische Muster aus den markantesten Bildfarben",
    listOf("Streifen", "Schachbrett", "Dreiecke", "Sechsecke", "Punkte", "Rauten", "Zickzack", "Ringe", "Truchet"),
    first = 0, withBands = false, defaultMapping = 0, defaultSize = 40,
)

/** Op-art patterns made of bands, lines and pieces – black and white by default. */
object Geometric : TilePattern(
    "geometric", "Geometrisch", "Op-Art-Muster aus Bändern und Linien, schwarzweiss oder in den Bildfarben",
    listOf(
        "Winkel", "Karo gewebt", "Mäander", "Rauten verschachtelt", "Quadrate verschachtelt", "Dreiecksbänder",
        "Würfel", "Scherben", "Labyrinth", "Y-Muster",
    ),
    first = 9, withBands = true, defaultMapping = 3, defaultSize = 70,
)

/**
 * A geometric tile pattern. The colors come from the picture's most prominent ones and
 * are spread over the tiles by the picture (each tile takes the palette color closest to
 * the picture there), at random, in order, or it is plain black and white.
 *
 * Many patterns consist of alternating bands inside a tile (nested chevrons, squares,
 * diamonds, triangles, spirals, hatched cube faces); a tile then shows its color and
 * the next palette color in turn ("shade"). [names] are the patterns this effect offers,
 * [first] their number in the shared pattern catalog below.
 */
abstract class TilePattern(
    id: String, name: String, description: String,
    names: List<String>, private val first: Int, private val withBands: Boolean,
    defaultMapping: Int, defaultSize: Int,
) : Effect(id, name, description) {
    override val params = buildList {
        add(Param.Choice("pattern", "Muster", names))
        add(Param.Slider("colors", "Farben", 2, 12, 5, tip = "Wie viele der markantesten Bildfarben benutzt werden"))
        add(Param.Slider("size", "Grösse", 4, 600, defaultSize, " px"))
        add(Param.Slider("angle", "Winkel", 0, 179, 0, "°"))
        val mappings = listOf("Nach Bild", "Zufällig", "Der Reihe nach", "Schwarz/Weiss", "Verlauf", "Verlauf je Band", "Streifenverlauf")
        add(
            Param.Choice(
                "mapping", "Farbverteilung", mappings, defaultMapping,
                tip = "Nach Bild: jede Kachel nimmt die passendste Palettenfarbe. Verlauf: von Farbe 1 zu Farbe 2 über das Bild. " +
                    "Verlauf je Band: jedes Band eine Stufe weiter. Streifenverlauf: jedes Streifenpaar (dunkel + hell) " +
                    "verläuft von Farbe 1 an seiner Oberkante zu Farbe 2 an seiner Unterkante",
            ),
        )
        add(Param.Color("color1", "Verlauf Farbe 1", 0x14125A, "Für die Verläufe"))
        add(Param.Color("color2", "Verlauf Farbe 2", 0x19C3A0, "Für die Verläufe"))
        add(Param.Color("background", "Hintergrundfarbe", 0xFFFFFF, "Für die Verläufe: Farbe zwischen den Linien"))
        add(Param.Slider("gradientAngle", "Verlaufswinkel", 0, 359, 45, "°", "Richtung des Verlaufs über das Bild"))
        add(Param.Choice("smoothing", "Kantenglättung", listOf("Aus", "Normal (3×3)", "Hoch (5×5)"), 1, "Mehrere Stichproben je Pixel für weiche Kanten"))
        if (withBands) {
            add(Param.Slider("bands", "Bänder", 2, 24, 8, tip = "Linien in verschachtelten Mustern (Winkel, Mäander, Rauten, Quadrate, Dreiecke, Würfel)"))
            add(Param.Slider("stroke", "Strichstärke", 5, 50, 22, " %", "Fugen bei Scherben, Wände beim Labyrinth, Arme beim Y-Muster"))
        }
    }

    /**
     * One tile: its identity, its center (pattern coordinates), its index for "in order",
     * and its shade – 0 for the tile's color, 1 and 2 for the next palette colors
     * (background, stripes). [bands] marks patterns that are made of alternating bands
     * (or have their own background), [pos] is the band's place 0..1 inside its tile for
     * "Verlauf je Band" (-1 = none, then the gradient runs over the picture). [phase] is
     * the continuous position 0..1 across a pair of stripes – from the start of the dark
     * stripe to the end of the light one – for "Streifenverlauf" (-1 = the pattern has no stripes).
     */
    private class Tile(
        val ix: Int, val iy: Int, val part: Int, val cu: Double, val cv: Double, val seq: Int, val shade: Int,
        val bands: Boolean = false, val pos: Double = -1.0, val phase: Double = -1.0,
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mapping = v["mapping"]
        val blackWhite = mapping == 3
        val gradient = mapping >= 4
        val k = if (blackWhite || gradient) 2 else v["colors"]
        val palette = when {
            blackWhite -> intArrayOf(0xFF000000.toInt(), -1)
            gradient -> intArrayOf(0, 0)
            else -> dominantColors(src, k, seed)
        }
        val bands = if (withBands) v["bands"] else 8
        val stroke = if (withBands) v["stroke"] / 100.0 else 0.22
        val s = v["size"].toDouble()
        val a = v["angle"] * PI / 180
        val ca = cos(a)
        val sa = sin(a)
        val pattern = first + v["pattern"]
        val w = src.width
        val h = src.height
        val ox = w / 2.0
        val oy = h / 2.0

        val color1 = v["color1"]
        val color2 = v["color2"]
        val backColor = v["background"]
        val ga = v["gradientAngle"] * PI / 180
        val gx = cos(ga)
        val gy = sin(ga)
        // half the picture's extent along the gradient direction
        val reach = max(1.0, (abs(w * gx) + abs(h * gy)) / 2)
        val samples = when (v["smoothing"]) { 0 -> 1; 1 -> 3; else -> 5 }

        /** The color of the pattern at canvas position ([px], [py]). */
        fun colorAt(px: Double, py: Double): Int {
            // pattern coordinates: rotated around the canvas center
            val dx = px - ox
            val dy = py - oy
            val tile = tile(pattern, dx * ca + dy * sa, -dx * sa + dy * ca, s, bands, stroke)
            if (mapping == 6) {
                // a ramp from color 1 to color 2 across every dark + light stripe pair
                return if (tile.phase >= 0) mix(color1, color2, tile.phase)
                else if (tile.shade == 0) color1 else color2
            }
            if (gradient) {
                // tiles without their own bands alternate by their index, like the palette modes do
                val shade = if (tile.bands) tile.shade else (tile.seq + tile.shade).mod(2)
                val t = if (mapping == 5 && tile.pos >= 0) tile.pos
                else ((dx * gx + dy * gy) / (2 * reach) + 0.5).coerceIn(0.0, 1.0)
                val line = mix(color1, color2, t)
                return when (shade) {
                    0 -> line
                    1 -> backColor
                    else -> mix(line, backColor, 0.5)
                }
            }
            var index = when (mapping) {
                0 -> {
                    // sample the picture at the tile's center (back in canvas coordinates)
                    val cx = (ox + tile.cu * ca - tile.cv * sa).roundToInt().coerceIn(0, w - 1)
                    val cy = (oy + tile.cu * sa + tile.cv * ca).roundToInt().coerceIn(0, h - 1)
                    nearestColor(palette, src[cx, cy])
                }
                1 -> Math.floorMod(hash(tile.ix, tile.iy, tile.part, seed), k)
                3 -> if (tile.bands) 0 else Math.floorMod(tile.seq, 2)
                else -> Math.floorMod(tile.seq, k)
            }
            index = (index + tile.shade) % k
            return if (blackWhite && pattern == 10) GINGHAM[tile.shade] else palette[index]
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val c = src.data[y * w + x]
                // several samples per pixel, averaged: smooth edges
                var r = 0; var g = 0; var b = 0
                for (sy in 0 until samples) for (sx in 0 until samples) {
                    val rgb = colorAt(x + (sx + 0.5) / samples, y + (sy + 0.5) / samples)
                    r += red(rgb); g += green(rgb); b += blue(rgb)
                }
                val n = samples * samples
                out.data[y * w + x] = argb(alpha(c), (r + n / 2) / n, (g + n / 2) / n, (b + n / 2) / n)
            }
        }
        return out
    }

    /** Position 0..1 across a stripe pair, from a continuous band value [b] (even bands are the dark ones). */
    private fun phase(b: Double) = (b - 2 * floor(b / 2)) / 2

    /** RGB blend of [a] and [b], t = 0 gives [a]. */
    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t + 0.5).toInt()
        return argb(255, ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }

    private fun tile(pattern: Int, u: Double, v: Double, s: Double, n: Int, stroke: Double): Tile {
        fun cell(p: Double) = floor(p / s).toInt()
        if (pattern >= 9) return bandTile(pattern, u, v, s, n, stroke)
        return when (pattern) {
            1 -> { // checkerboard
                val i = cell(u); val j = cell(v)
                Tile(i, j, 0, (i + 0.5) * s, (j + 0.5) * s, i + j, 0)
            }
            2 -> { // triangles: squares split along a diagonal that alternates
                val i = cell(u); val j = cell(v)
                val fu = u / s - i
                val fv = v / s - j
                val flip = (i + j) and 1 == 1
                val part = if (flip) (if (fu + fv > 1) 1 else 0) else (if (fu > fv) 1 else 0)
                val (cu, cv) = when {
                    !flip && part == 1 -> 0.67 to 0.33
                    !flip -> 0.33 to 0.67
                    part == 1 -> 0.67 to 0.67
                    else -> 0.33 to 0.33
                }
                Tile(i, j, part, (i + cu) * s, (j + cv) * s, i + 2 * j + part, 0)
            }
            3 -> { // hexagons (pointy top), s = radius
                val (rq, rr) = hex(u, v, s)
                Tile(rq, rr, 0, s * sqrt(3.0) * (rq + rr / 2.0), s * 1.5 * rr, rq + 2 * rr, 0)
            }
            4 -> { // dots on a background
                val i = cell(u); val j = cell(v)
                val cu = (i + 0.5) * s; val cv = (j + 0.5) * s
                Tile(i, j, 0, cu, cv, i + j, if (hypot(u - cu, v - cv) > s * 0.38) 1 else 0, bands = true)
            }
            5 -> { // diamonds: checkerboard turned by 45°
                val r2 = sqrt(2.0) / 2
                val du = (u + v) * r2; val dv = (v - u) * r2
                val i = cell(du); val j = cell(dv)
                val cdu = (i + 0.5) * s; val cdv = (j + 0.5) * s
                Tile(i, j, 0, (cdu - cdv) * r2, (cdu + cdv) * r2, i + j, 0)
            }
            6 -> { // zigzag bands
                val period = 2 * s
                val m = u - floor(u / period) * period
                val shifted = v + abs(m - s)
                val i = cell(shifted)
                val column = floor(u / period).toInt()
                Tile(column, i, 0, (column + 0.5) * period, (i + 0.5) * s - s / 2, i, 0, phase = phase(shifted / s))
            }
            7 -> { // rings around the center, cut into sectors for the picture colors
                val d = hypot(u, v)
                val i = cell(d)
                val sectors = max(1, 6 * i)
                val ang = atan2(v, u) + PI
                val j = floor(ang / (2 * PI) * sectors).toInt()
                val ma = (j + 0.5) / sectors * 2 * PI - PI
                val md = (i + 0.5) * s
                Tile(i, j, 0, md * cos(ma), md * sin(ma), i, 0, phase = phase(d / s))
            }
            8 -> { // Truchet: quarter circles in randomly turned squares
                val i = cell(u); val j = cell(v)
                val fu = u / s - i
                val fv = v / s - j
                val turned = hash(i, j, 7, 12345L) and 1 == 1
                val d1 = if (turned) hypot(fu, fv) else hypot(1 - fu, fv)
                val d2 = if (turned) hypot(1 - fu, 1 - fv) else hypot(fu, 1 - fv)
                val inside = d1 < 0.5 || d2 < 0.5
                Tile(i, j, 0, (i + 0.5) * s, (j + 0.5) * s, i + j, if (inside) 0 else 1, bands = true)
            }
            else -> { // stripes
                val i = cell(u); val j = cell(v)
                Tile(i, j, 0, (i + 0.5) * s, (j + 0.5) * s, i, 0, phase = phase(u / s))
            }
        }
    }

    /** Axial coordinates of the pointy-top hexagon (radius [s]) containing ([u], [v]). */
    private fun hex(u: Double, v: Double, s: Double): Pair<Int, Int> {
        val q = (sqrt(3.0) / 3 * u - v / 3) / s
        val r = (2.0 / 3 * v) / s
        var rq = q.roundToInt()
        var rr = r.roundToInt()
        val rs = (-q - r).roundToInt()
        val dq = abs(rq - q); val dr = abs(rr - r); val ds = abs(rs - (-q - r))
        if (dq > dr && dq > ds) rq = -rr - rs else if (dr > ds) rr = -rq - rs
        return rq to rr
    }

    /** The patterns made of bands, lines or pieces inside tiles (the op-art set). */
    private fun bandTile(pattern: Int, u: Double, v: Double, s: Double, n: Int, stroke: Double): Tile {
        val i = floor(u / s).toInt()
        val j = floor(v / s).toInt()
        // position inside the square tile, -0.5..0.5
        val x = u / s - i - 0.5
        val y = v / s - j - 0.5
        val cu = (i + 0.5) * s
        val cv = (j + 0.5) * s
        fun odd(band: Int) = Math.floorMod(band, 2)
        return when (pattern) {
            9 -> { // nested chevrons (arrows) in every tile
                val b = (y + abs(x) + 0.5) * n
                val band = floor(b).toInt()
                Tile(i, j, 0, cu, cv, i + j, odd(band), bands = true, pos = (band / (1.5 * n)).coerceIn(0.0, 1.0), phase = phase(b))
            }
            10 -> { // gingham: stripes both ways, three tones
                val a = Math.floorMod(floor(u / (s / 2)).toInt(), 2)
                val b = Math.floorMod(floor(v / (s / 2)).toInt(), 2)
                Tile(i, j, 0, cu, cv, i + j, a + b, bands = true, pos = (a + b) / 2.0)
            }
            11 -> { // meander: a square spiral in every tile, mirrored from tile to tile
                val mx = if ((i + j) and 1 == 1) -x else x
                val d = max(abs(mx), abs(y)) * 2
                val turn = (atan2(y, mx) + PI) / (2 * PI)
                val b = d * n + turn
                val band = floor(b).toInt()
                Tile(i, j, 0, cu, cv, i + j, odd(band), bands = true, pos = (band / (n + 1.0)).coerceIn(0.0, 1.0), phase = phase(b))
            }
            12 -> { // concentric diamonds
                val b = (abs(x) + abs(y)) * n
                val band = floor(b).toInt()
                Tile(i, j, 0, cu, cv, i + j, odd(band), bands = true, pos = (band.toDouble() / (n - 1)).coerceIn(0.0, 1.0), phase = phase(b))
            }
            13 -> { // concentric squares
                val b = max(abs(x), abs(y)) * 2 * n
                val band = floor(b).toInt()
                Tile(i, j, 0, cu, cv, i + j, odd(band), bands = true, pos = (band.toDouble() / (n - 1)).coerceIn(0.0, 1.0), phase = phase(b))
            }
            14 -> { // nested triangles on a triangle grid
                val th = s * sqrt(3.0) / 2
                val a = v / th
                val b = (sqrt(3.0) * u - v) / (2 * th)
                val c = (-sqrt(3.0) * u - v) / (2 * th)
                val fa = a - floor(a); val fb = b - floor(b); val fc = c - floor(c)
                val up = fa + fb + fc < 1.5
                val edge = if (up) minOf(fa, fb, fc) else minOf(1 - fa, 1 - fb, 1 - fc)
                val band = floor(edge * 3 * n).toInt()
                Tile(
                    floor(a).toInt(), floor(b).toInt() * 2 + if (up) 0 else 1, 0, u, v, i + j, odd(band),
                    bands = true, pos = (band.toDouble() / (n - 1)).coerceIn(0.0, 1.0), phase = phase(edge * 3 * n),
                )
            }
            15 -> { // isometric cubes: hexagons split into three outlined faces, each hatched in its own direction
                val (q, r) = hex(u, v, s)
                val hx = s * sqrt(3.0) * (q + r / 2.0)
                val hy = s * 1.5 * r
                val lx = (u - hx) / s
                val ly = (v - hy) / s
                val deg = Math.toDegrees(atan2(ly, lx)).let { if (it < 0) it + 360 else it }
                val face = when {
                    deg >= 210 && deg < 330 -> 0 // top
                    deg >= 330 || deg < 90 -> 1 // right
                    else -> 2 // left
                }
                // top: along its upper edge, left: vertical, right: along its lower edge
                val proj = when (face) {
                    0 -> lx * 0.5 + ly * sqrt(3.0) / 2
                    1 -> -lx * 0.5 + ly * sqrt(3.0) / 2
                    else -> lx
                }
                // outlines: the three edges between the faces and the hexagon border
                val line = stroke * 0.25
                var outline = false
                for (a in doubleArrayOf(90.0, 210.0, 330.0)) {
                    val rad = Math.toRadians(a)
                    val along = lx * cos(rad) + ly * sin(rad)
                    if (along > 0 && abs(lx * sin(rad) - ly * cos(rad)) < line / 2) outline = true
                }
                var toBorder = 0.0
                for (e in 0 until 6) {
                    val rad = Math.toRadians(e * 60.0)
                    toBorder = max(toBorder, lx * cos(rad) + ly * sin(rad))
                }
                if (sqrt(3.0) / 2 - toBorder < line / 2) outline = true
                val band = floor(proj * n).toInt()
                Tile(
                    q, r, face, hx, hy, q + 2 * r, if (outline) 0 else odd(band), bands = true, pos = face / 2.0,
                    phase = if (outline) 0.0 else phase(proj * n),
                )
            }
            16 -> shard(u, v, s, i, j, stroke)
            17 -> { // maze: every cell has a wall on its top or its left side
                val top = hash(i, j, 3, 777L) and 1 == 0
                val fu = x + 0.5
                val fv = y + 0.5
                val wall = if (top) fv < stroke else fu < stroke
                Tile(i, j, 0, cu, cv, i + j, if (wall) 0 else 1, bands = true)
            }
            else -> { // Y shapes: three arms from each hexagon's center towards its corners, ending short of them
                val (q, r) = hex(u, v, s)
                val hx = s * sqrt(3.0) * (q + r / 2.0)
                val hy = s * 1.5 * r
                val lx = (u - hx) / s
                val ly = (v - hy) / s
                var nearest = Double.MAX_VALUE
                for (k in 0 until 3) {
                    val a = PI / 2 + k * 2 * PI / 3
                    val dx = cos(a)
                    val dy = sin(a)
                    val along = lx * dx + ly * dy
                    // the arms stop before the corner, so the Ys stay separate shapes
                    if (along < 0 || along > 0.92 - stroke * 0.5) continue
                    nearest = min(nearest, abs(lx * dy - ly * dx))
                }
                Tile(q, r, 0, hx, hy, q + 2 * r, if (nearest < stroke * 0.5) 0 else 1, bands = true)
            }
        }
    }

    /**
     * Shards: a grid of randomly shifted points, every square cut into two triangles
     * along a random diagonal; a strip along the triangle edges stays background.
     */
    private fun shard(u: Double, v: Double, s: Double, i: Int, j: Int, stroke: Double): Tile {
        fun corner(a: Int, b: Int): Pair<Double, Double> {
            val jx = (hash(a, b, 1, 99L) % 1000) / 1000.0 - 0.5
            val jy = (hash(a, b, 2, 99L) % 1000) / 1000.0 - 0.5
            return (a + jx * 0.7) * s to (b + jy * 0.7) * s
        }
        val a = corner(i, j); val b = corner(i + 1, j); val c = corner(i + 1, j + 1); val d = corner(i, j + 1)
        val triangles = if (hash(i, j, 5, 99L) and 1 == 0) listOf(listOf(a, b, c), listOf(a, c, d)) else listOf(listOf(a, b, d), listOf(b, c, d))
        var best = -Double.MAX_VALUE
        var part = 0
        for ((t, tri) in triangles.withIndex()) {
            // smallest signed distance to the three edges (positive inside)
            var inside = Double.MAX_VALUE
            val cx = tri.sumOf { it.first } / 3
            val cy = tri.sumOf { it.second } / 3
            for (e in 0 until 3) {
                val (x1, y1) = tri[e]
                val (x2, y2) = tri[(e + 1) % 3]
                val len = hypot(x2 - x1, y2 - y1)
                var dist = ((x2 - x1) * (v - y1) - (y2 - y1) * (u - x1)) / len
                // orient so the centroid is on the positive side
                if (((x2 - x1) * (cy - y1) - (y2 - y1) * (cx - x1)) < 0) dist = -dist
                inside = min(inside, dist)
            }
            if (inside > best) { best = inside; part = t }
        }
        val tri = triangles[part]
        return Tile(
            i, j, part, tri.sumOf { it.first } / 3, tri.sumOf { it.second } / 3, i + j + part,
            if (best < stroke * s * 0.5) 1 else 0, bands = true,
        )
    }

    /** Black-and-white gingham: no stripe, one stripe, both stripes. */
    private val GINGHAM = intArrayOf(-1, 0xFF8C8C8C.toInt(), 0xFF000000.toInt())

    private fun hash(i: Int, j: Int, part: Int, seed: Long): Int {
        var h = i * 374761393L + j * 668265263L + part * 2246822519L + seed
        h = (h xor (h ushr 13)) * 1274126177L
        return (h xor (h ushr 16)).toInt() and 0x7FFFFFFF
    }
}
