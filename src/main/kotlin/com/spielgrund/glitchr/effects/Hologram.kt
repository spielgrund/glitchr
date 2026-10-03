package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A holographic foil card. The picture is first simplified into flat areas (blobs, as in
 * "Particles"); every area gets a random tilt and a random depth. Turning the card by the
 * rotation angle then sweeps the foil's colors across it: each area catches the light at
 * its own angle, and deeper areas shift further (parallax) and change color faster, like
 * embossed layers of a real hologram. Patterns (stars, crosses, circles …) printed into
 * the areas move with them, and the bright parts of the foil can glow.
 */
object Hologram : Effect("hologram", "Hologram", "Holographic foil: the picture in tilted areas with depth that shimmer in rainbow, gold or diamond when turned") {
    private val styles = listOf("Rainbow", "Gold", "Diamond", "Custom gradient")
    private val blends = listOf("Screen", "Overlay", "Color")
    private val bandShapes = listOf("Lines", "Waves", "Rings", "Squares", "Diamonds", "Hexagons", "Rays", "Spiral")
    private val patterns = listOf("Off", "Stars", "Crosses", "Circles", "Dots", "Diamonds", "Mixed")

    override val params = listOf(
        Param.Slider("rotation", "Rotation", -90, 90, 20, "°", "Turns the card: the colors travel across the areas, deeper areas react more strongly"),
        Param.Slider("axis", "Rotation axis", 0, 359, 0, "°", "In which direction the card is tilted; 0° = left/right"),
        Param.Slider("areas", "Areas", 0, 100, 50, " %", "How coarsely the picture is split into areas"),
        Param.Slider("tilt", "Tilt", 0, 100, 60, " %", "How differently the areas are tilted: each catches the light at a different angle, and its stripe pattern is rotated against the rotation axis (up to 60° at 100 %)"),
        Param.Slider("depth", "Depth", 0, 100, 40, " %", "How far apart the areas lie in depth: deeper ones shimmer faster when turned"),
        Param.Slider("parallax", "Parallax", 0, 200, 40, " px", "How far the areas shift against each other when turned: front and back ones in opposite directions (value at 90°)"),
        Param.Choice("parallaxEdge", "Parallax at the edge", listOf("Mirror", "Repeat"), tip = "What a shifted area shows at its edge: its own picture mirrored or the edge pixel stretched – never parts of other areas"),
        Param.Slider("smooth", "Smooth edges", 0, 30, 2, " px", "Rounds off the outlines of the areas: jagged edges and thin offshoots disappear, the picture stays sharp"),
        Param.Choice("style", "Holo effect", styles),
        Param.Color("color1", "Color 1", 0x00E5FF, "For “Custom gradient”"),
        Param.Color("color2", "Color 2", 0xFF3FD8),
        Param.Color("color3", "Color 3", 0xFFE14D),
        Param.Slider("bands", "Stripes", 1, 20, 3, "", "How often the color gradient repeats across the card"),
        Param.Choice(
            "bandShape", "Stripe shape", bandShapes,
            tip = "The shape of the foil's color stripes: straight lines, waves, rings, squares, diamonds or hexagons around the middle, rays or a spiral; the rotation axis turns the shape too",
        ),
        Param.Slider(
            "tile", "Tiles", 0, 500, 0, " px",
            "Repeats the stripe shape as tiles of this size side by side (0 = one shape across the whole picture); small gives a fine grid, e.g. many small squares",
        ),
        Param.Slider("strength", "Strength", 0, 100, 55, " %"),
        Param.Choice("blend", "Mix", blends, 1, tip = "Screen: the foil glows over the picture · Overlay: stronger, dark spots stay dark · Color: the picture takes on the foil color, keeps its brightness"),
        Param.Slider("gloss", "Gloss", 0, 100, 50, " %", "Highlights on the areas currently facing the light"),
        Param.Slider("sparkle", "Sparkle", 0, 100, 0, " %", "How many little stars sit on the areas (100 %: packed tightly); they travel with their area and flash when turned"),
        Param.Slider("sparkleSize", "Sparkle size", 1, 60, 6, " px", "Size of the sparkle stars"),
        Param.Slider("edges", "Edges", 0, 100, 0, " %", "Bright embossed edges between the areas"),
        Param.Choice("pattern", "Pattern", patterns, tip = "Patterns embossed into the areas; they travel with their area and shimmer in a foil color of their own"),
        Param.Slider("patternSize", "Pattern size", 4, 300, 28, " px", "Spacing of the patterns"),
        Param.Slider("patternDensity", "Pattern density", 0, 100, 50, " %", "How many places get a pattern"),
        Param.Slider("patternStrength", "Pattern strength", 0, 100, 70, " %"),
        Param.Slider("glow", "Glow", 0, 100, 40, " %", "The bright parts – highlights, edges, patterns, sparks – radiate into their surroundings"),
        Param.Slider("glowRadius", "Glow radius", 1, 100, 12, " px"),
        Param.Slider("glowColor", "Glow color", 0, 100, 80, " %", "How much the glow takes on the color of the foil and the picture; 0 % glows white"),
        Param.Slider("fringe", "Glow fringe", 0, 100, 40, " %", "Red radiates further than blue: colored fringes around the lights"),
        Param.Slider("streak", "Light streaks", 0, 100, 30, " %", "Long light streaks through the brightest spots, as with an anamorphic lens"),
        Param.Slider("streakLength", "Streak length", 10, 800, 160, " px"),
        Param.Slider("streakAngle", "Streak angle", 0, 179, 0, "°", "0° = horizontal"),
        Param.Choice("tonemap", "Tone mapping", listOf("Off", "Filmic"), 1, "Filmic: bright spots roll off softly instead of burning out, highlights and shadows can be controlled separately"),
        Param.Slider("exposure", "Exposure", -200, 200, 0, " %", "Hundredths of a stop: −100 % halves the light, +100 % doubles it"),
        Param.Slider("highlights", "Highlights", 0, 300, 60, " %", "Filmic shoulder up to 100 %: the highlights roll off softly instead of burning out; above 100 % the highlights get even more contrast, bright spots become crisper, the shadows stay"),
        Param.Slider("shadows", "Shadows", -100, 100, 0, " %", "Negative: deeper shadows · positive: lift the shadows"),
    )

    private val gold = intArrayOf(0x4A2E06, 0xA8740F, 0xF2C94C, 0xFFF4C8, 0xD9A32E, 0x7A5410)
    private val diamond = intArrayOf(0xE6F2FF, 0x7FCBF2, 0xA99AF0, 0xF0A8D4, 0xCFE0F5, 0xF2DB9A, 0x86E8C4)

    private const val STARS = 1
    private const val CROSSES = 2
    private const val CIRCLES = 3
    private const val DOTS = 4
    private const val DIAMONDS = 5
    private const val MIXED = 6

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val blobs = Particles.blobify(src, v["areas"] / 100.0, seed)
        // the areas' outlines rounded off (the picture itself stays sharp)
        val label = if (v["smooth"] > 0) smoothLabels(blobs.label, w, h, v["smooth"]) else blobs.label
        val count = blobs.area.size
        val tilt = v["tilt"] / 100.0
        val depth = v["depth"] / 100.0
        val rotation = Math.toRadians(v["rotation"].toDouble())
        val axis = Math.toRadians(v["axis"].toDouble())
        val ax = cos(axis)
        val ay = sin(axis)
        val style = v["style"]
        val bands = v["bands"].toDouble()
        val bandShape = v["bandShape"]
        val tile = v["tile"].toDouble()
        val strength = v["strength"] / 100.0
        val blend = v["blend"]
        val gloss = v["gloss"] / 100.0
        val edges = v["edges"] / 100.0
        val custom = intArrayOf(v["color1"], v["color2"], v["color3"])
        val pattern = v["pattern"]
        val patternSize = v["patternSize"].toDouble()
        val density = v["patternDensity"] / 100.0
        val patternStrength = v["patternStrength"] / 100.0
        val glow = v["glow"] / 100.0
        val noise = Noise(seed)

        // every area: a tilt (how its normal leans along the turning direction) and a depth
        val lean = DoubleArray(count) { b -> tilt * noise.white(b, 101) }
        // a tilted area turns its stripes too: its own axis, up to 60° off the card's
        val areaAx = DoubleArray(count) { b -> cos(axis + lean[b] * PI / 3) }
        val areaAy = DoubleArray(count) { b -> sin(axis + lean[b] * PI / 3) }
        val z = DoubleArray(count) { b -> (noise.white(b, 102) + 1) / 2 }
        // each area's pattern sits a little differently
        val patternOffset = DoubleArray(count) { b -> (noise.white(b, 103) + 1) / 2 * patternSize }
        val diag = hypot(w.toDouble(), h.toDouble())
        val turn = sin(rotation)
        // front areas (z = 0) and back areas (z = 1) move in opposite directions when turned
        val parallax = v["parallax"] * turn
        val mirror = v["parallaxEdge"] == 0
        val glowColor = v["glowColor"] / 100.0
        val sparkleAmount = v["sparkle"] / 100.0
        val sparkleSize = v["sparkleSize"].toDouble()

        // the lights (gloss, edges, patterns, sparkles) are kept apart as well, for the glow
        val lightR = if (glow > 0) FloatArray(n) else null
        val lightG = if (glow > 0) FloatArray(n) else null
        val lightB = if (glow > 0) FloatArray(n) else null

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val b = label[i]
                if (b < 0) {
                    out.data[i] = src.data[i]
                    continue
                }
                // shifted by the parallax, but only ever showing its own area: at the area's edge
                // the picture is mirrored back (or the edge pixel repeated)
                val shift = inside(label, w, h, b, x + 0.5, y + 0.5, ax, ay, (z[b] - 0.5) * 2 * parallax, mirror)
                val px = x + 0.5 - ax * shift
                val py = y + 0.5 - ay * shift
                val base = sampleBilinear(src, px, py, Edge.CLAMP)
                // where in the foil's color cycle this point is: across the card, turned by the
                // rotation, offset by the area's tilt, deeper areas running faster; the bands wave a little
                val along = bandPosition(bandShape, px - w / 2.0, py - h / 2.0, areaAx[b], areaAy[b], diag, tile)
                val wave = 0.25 * noise.perlin(px / diag * 5, py / diag * 5)
                val t = bands * along + wave + rotation / (PI / 2) * 1.5 * (1 + depth * z[b] * 2) + lean[b] * 1.2
                val holo = foil(style, t - floor(t), custom)
                // little stars sitting on their area (so they move with it) that flash
                // up and fade again while the card turns
                val sparkle = if (sparkleAmount > 0)
                    sparkleAt(px + patternOffset[b] * 1.3, py + patternOffset[b] * 0.9, sparkleSize, sparkleAmount, turn + lean[b], noise)
                else 0.0
                // gloss: the areas whose tilt matches the current turn catch the light
                val facing = 1 - abs(turn * 1.1 - lean[b]) / 0.18
                val shine = if (facing > 0) facing * facing * gloss else 0.0
                // embossed edges between the areas
                val border = x > 0 && label[i - 1] != b || x < w - 1 && label[i + 1] != b ||
                    y > 0 && label[i - w] != b || y < h - 1 && label[i + w] != b
                val edge = if (border) edges else 0.0
                // printed patterns, moving with their area
                val mark = if (pattern == 0) 0.0 else patternStrength * mark(pattern, px + patternOffset[b], py + patternOffset[b] * 0.7, patternSize, density, noise)

                val br = (base shr 16 and 0xFF) / 255.0
                val bg = (base shr 8 and 0xFF) / 255.0
                val bb = (base and 0xFF) / 255.0
                val hr = (holo shr 16 and 0xFF) / 255.0
                val hg = (holo shr 8 and 0xFF) / 255.0
                val hb = (holo and 0xFF) / 255.0
                var r: Double
                var g: Double
                var bl: Double
                when (blend) {
                    0 -> { r = screen(br, hr); g = screen(bg, hg); bl = screen(bb, hb) }
                    1 -> { r = overlay(br, hr); g = overlay(bg, hg); bl = overlay(bb, hb) }
                    else -> {
                        // the foil's color at the picture's brightness
                        val light = 0.299 * br + 0.587 * bg + 0.114 * bb
                        val hl = (0.299 * hr + 0.587 * hg + 0.114 * hb).coerceAtLeast(0.05)
                        r = (hr * light / hl).coerceAtMost(1.0); g = (hg * light / hl).coerceAtMost(1.0); bl = (hb * light / hl).coerceAtMost(1.0)
                    }
                }
                r = br + (r - br) * strength
                g = bg + (g - bg) * strength
                bl = bb + (bl - bb) * strength

                // the lights: gloss, edges and sparkles (white, or tinted by the foil for the glow's color),
                // patterns in the foil's opposite color
                val white = min(1.0, shine + edge + sparkle)
                var lr = white * (1 - glowColor * 0.6 * (1 - hr))
                var lg = white * (1 - glowColor * 0.6 * (1 - hg))
                var lb = white * (1 - glowColor * 0.6 * (1 - hb))
                if (mark > 0) {
                    val other = foil(style, (t + 0.5) - floor(t + 0.5), custom)
                    lr = min(1.0, lr + mark * (0.35 + 0.65 * (other shr 16 and 0xFF) / 255.0))
                    lg = min(1.0, lg + mark * (0.35 + 0.65 * (other shr 8 and 0xFF) / 255.0))
                    lb = min(1.0, lb + mark * (0.35 + 0.65 * (other and 0xFF) / 255.0))
                }
                r = screen(r, lr)
                g = screen(g, lg)
                bl = screen(bl, lb)
                if (lightR != null) {
                    // what glows: the lights, and the brightest parts of the result in their own color
                    val bright = ((0.299 * r + 0.587 * g + 0.114 * bl - 0.6) / 0.4).coerceIn(0.0, 1.0) * glowColor
                    lightR[i] = maxOf(lr, r * bright).toFloat()
                    lightG!![i] = maxOf(lg, g * bright).toFloat()
                    lightB!![i] = maxOf(lb, bl * bright).toFloat()
                }
                out.data[i] = argb(
                    base ushr 24,
                    (r * 255).roundToInt().coerceIn(0, 255),
                    (g * 255).roundToInt().coerceIn(0, 255),
                    (bl * 255).roundToInt().coerceIn(0, 255),
                )
            }
        }


        if (lightR != null) {
            // glow in three sizes for a soft, deep falloff; red reaches further than blue (color fringe)
            val radius = v["glowRadius"].toDouble()
            val fringe = v["fringe"] / 100.0
            fun bloom(plane: FloatArray, stretch: Double): FloatArray {
                val a = Blur.gauss(plane, w, h, radius * stretch)
                val b2 = Blur.gauss(plane, w, h, radius * stretch * 2.5)
                val c = Blur.gauss(plane, w, h, radius * stretch * 6)
                return FloatArray(n) { 0.55f * a[it] + 0.3f * b2[it] + 0.25f * c[it] }
            }
            val gr = bloom(lightR, 1 + fringe * 0.8)
            val gg = bloom(lightG!!, 1.0)
            val gb = bloom(lightB!!, 1 - fringe * 0.45)
            // light streaks through the brightest spots
            val streak = v["streak"] / 100.0
            val sr: FloatArray?
            val sg: FloatArray?
            val sb: FloatArray?
            if (streak > 0) {
                val angle = Math.toRadians(v["streakAngle"].toDouble())
                val length = v["streakLength"].toDouble()
                sr = streaks(lightR, w, h, cos(angle), sin(angle), length * (1 + fringe * 0.3))
                sg = streaks(lightG, w, h, cos(angle), sin(angle), length)
                sb = streaks(lightB!!, w, h, cos(angle), sin(angle), length * (1 - fringe * 0.2))
            } else {
                sr = null; sg = null; sb = null
            }
            val k = glow * 2.2
            val ks = streak * 3.0
            parallelRows(h) { y ->
                for (i in y * w until (y + 1) * w) {
                    val c = out.data[i]
                    fun add(s: Int, l: Float, st: FloatArray?): Int {
                        val v0 = (c shr s and 0xFF) / 255.0
                        val light = l * k + (st?.get(i) ?: 0f) * ks
                        return (screen(v0, min(1.0, light)) * 255).roundToInt().coerceIn(0, 255)
                    }
                    out.data[i] = argb(c ushr 24, add(16, gr[i], sr), add(8, gg[i], sg), add(0, gb[i], sb))
                }
            }
        }
        if (v["tonemap"] == 1) tonemap(out, v["exposure"] / 100.0, v["highlights"] / 100.0, v["shadows"] / 100.0)
        return out
    }

    /**
     * Where the point ([x], [y]) (relative to the picture's middle) lies in the foil's bands,
     * one unit being about one band set across the picture. The shape is turned so that its
     * "along" direction is ([ax], [ay]), the rotation axis. With a [tile] size the shape is
     * repeated in a grid of tiles that size, each tile holding the whole shape.
     */
    private fun bandPosition(shape: Int, x: Double, y: Double, ax: Double, ay: Double, diag: Double, tile: Double): Double {
        // turned into the axis' frame: u along the axis, v across it
        var u = x * ax + y * ay
        var v = -x * ay + y * ax
        var unit = diag
        if (tile > 0) {
            // relative to the middle of the own tile; the tile is the whole picture for the shape
            u -= Math.round(u / tile) * tile
            v -= Math.round(v / tile) * tile
            unit = tile * 1.4142
        }
        u /= unit
        v /= unit
        return when (shape) {
            1 -> u + 0.06 * sin(v * 2 * PI * 4)
            2 -> hypot(u, v) * 2
            3 -> maxOf(abs(u), abs(v)) * 2
            4 -> (abs(u) + abs(v)) * 1.5
            5 -> maxOf(abs(u), abs(u / 2 + v * 0.8660254), abs(u / 2 - v * 0.8660254)) * 2
            6 -> kotlin.math.atan2(v, u) / (2 * PI)
            7 -> kotlin.math.atan2(v, u) / (2 * PI) + hypot(u, v) * 2
            else -> u
        }
    }

    /**
     * Filmic tone mapping on the brightness (the colors keep their hue): [exposure] in
     * stops, a filmic shoulder that rolls the lights off softly instead of clipping them
     * ([highlights] = how much), and a toe that deepens or lifts the shadows.
     */
    private fun tonemap(out: Pixels, exposure: Double, highlights: Double, shadows: Double) {
        val gain = Math.pow(2.0, exposure)
        // the curve on the linear brightness, looked up from a table
        val table = DoubleArray(1024) { k ->
            val x = k / 1023.0 * 4
            // ACES filmic curve (Narkowicz), normalized so white stays white at the shoulder's end
            val aces = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)
            val shoulder = min(1.0, highlights)
            var y = min(1.0, x) * (1 - shoulder) + aces * shoulder
            // beyond 100 %: more contrast in the lights – bright parts pushed up, the upper
            // midtones pulled down, around a pivot in the middle; the shadows untouched
            if (highlights > 1) {
                val e = highlights - 1
                val s = ((y - 0.3) / 0.4).coerceIn(0.0, 1.0)
                y += e * 1.2 * (y - 0.5) * s * s * (3 - 2 * s)
            }
            // toe: shadows deeper (< 0) or lifted (> 0), strongest in the dark end
            if (shadows != 0.0) y = y + (Math.pow(y, Math.pow(2.0, -shadows)) - y) * (1 - y) * (1 - y)
            y.coerceIn(0.0, 1.0)
        }
        fun curve(x: Double): Double {
            val pos = (x / 4 * 1023).coerceIn(0.0, 1023.0)
            val k = pos.toInt().coerceAtMost(1022)
            val f = pos - k
            return table[k] * (1 - f) + table[k + 1] * f
        }
        fun toLinear(c: Int) = Math.pow(c / 255.0, 2.2)
        fun toScreen(l: Double) = (Math.pow(l.coerceIn(0.0, 1.0), 1 / 2.2) * 255).roundToInt()
        val w = out.width
        parallelRows(out.height) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = out.data[i]
                val r = toLinear(c shr 16 and 0xFF) * gain
                val g = toLinear(c shr 8 and 0xFF) * gain
                val b = toLinear(c and 0xFF) * gain
                val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
                if (l <= 1e-6) {
                    out.data[i] = c and 0xFF000000.toInt()
                    continue
                }
                val k = curve(l) / l
                // very bright colors bleach towards white, like film
                val over = ((l * k - 0.8) / 0.2).coerceIn(0.0, 1.0) * min(1.0, highlights) * 0.5
                fun ch(v0: Double) = toScreen(v0 * k + (curve(l) - v0 * k) * over)
                out.data[i] = argb(c ushr 24, ch(r), ch(g), ch(b))
            }
        }
    }

    /**
     * A sparkle at ([x], [y]): a grid of cells a little bigger than the stars, some ([amount])
     * holding a four-pointed star of [size] at a random spot. Each flashes up at its own point
     * of the turn. The neighbouring cells count too, so stars reaching over a cell's edge stay whole.
     */
    private fun sparkleAt(x: Double, y: Double, size: Double, amount: Double, turn: Double, noise: Noise): Double {
        // at 100 % about one star per (6 + size)² pixels
        val cell = 6 + size
        val gx = floor(x / cell).toInt()
        val gy = floor(y / cell).toInt()
        var best = 0.0
        for (cy in gy - 1..gy + 1) for (cx in gx - 1..gx + 1) best = maxOf(best, star(x, y, cx, cy, cell, size, amount, turn, noise))
        return best
    }

    private fun star(x: Double, y: Double, cx: Int, cy: Int, cell: Double, size: Double, amount: Double, turn: Double, noise: Noise): Double {
        if ((noise.white(cx, cy * 5 + 31) + 1) / 2 >= amount) return 0.0
        // flashing: bright only near its own angle of the turn
        val phase = noise.white(cx, cy * 5 + 32) * PI
        val flash = cos(turn * 4 + phase).let { if (it > 0) Math.pow(it, 6.0) else 0.0 }
        if (flash < 0.02) return 0.0
        val sx = (cx + 0.2 + 0.6 * (noise.white(cx, cy * 5 + 33) + 1) / 2) * cell
        val sy = (cy + 0.2 + 0.6 * (noise.white(cx, cy * 5 + 34) + 1) / 2) * cell
        val r = size * (0.5 + 0.5 * flash)
        val u = abs(x - sx)
        val v = abs(y - sy)
        if (u > r || v > r) return 0.0
        // a four-pointed star (astroid), with a soft core
        val q = sqrt(u / r) + sqrt(v / r)
        val star = ((1 - q) * r * 0.5 + 0.5).coerceIn(0.0, 1.0)
        val core = (1 - hypot(u, v) / (r * 0.35)).coerceIn(0.0, 1.0)
        return min(1.0, star + core) * flash
    }

    /**
     * How far the area [b] may actually be shifted at ([x], [y]) by [shift] along ([dx], [dy]),
     * so the sample stays inside the area: past the area's edge it comes back mirrored, or
     * (without [mirror]) stops at the edge, repeating the edge pixel.
     */
    private fun inside(label: IntArray, w: Int, h: Int, b: Int, x: Double, y: Double, dx: Double, dy: Double, shift: Double, mirror: Boolean): Double {
        fun isIn(t: Double): Boolean {
            val sx = floor(x - dx * t).toInt()
            val sy = floor(y - dy * t).toInt()
            return sx in 0 until w && sy in 0 until h && label[sy * w + sx] == b
        }
        if (shift == 0.0 || isIn(shift)) return shift
        // walk towards the shifted point to find where the area ends
        val sign = if (shift > 0) 1.0 else -1.0
        val total = abs(shift)
        var edge = 0.0
        var t = 1.0
        while (t < total) {
            if (!isIn(sign * t)) break
            edge = t
            t += 1.0
        }
        if (!mirror) return sign * edge
        // mirrored at the edge; in areas thinner than the shift, bounce between both edges
        val back = total - edge
        var pos = edge - back
        if (pos < 0) pos = min(edge, -pos)
        return sign * pos
    }

    /**
     * Rounds off the outlines of the areas up to about [radius] pixels: every pixel joins
     * the area most of its surroundings belong to. This runs from the coarse scale down to
     * the fine one (a spread-out 5×5 sample of the neighbourhood, [radius] wide first, then
     * half as wide …), so even big bulges and spurs are rounded, not only single pixels.
     * The picture itself stays sharp. Transparent pixels (-1) stay as they are.
     */
    private fun smoothLabels(label: IntArray, w: Int, h: Int, radius: Int): IntArray {
        var current = label.copyOf()
        var next = IntArray(label.size)
        val scales = generateSequence(radius.toDouble()) { it / 2 }.takeWhile { it >= 1 }.toList().ifEmpty { listOf(1.0) }
        for (scale in scales) repeat(2) {
            val from = current
            val to = next
            val offsets = doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0).map { (it * scale).roundToInt() }.distinct()
            parallelRows(h) { y ->
                val ids = IntArray(25)
                val counts = IntArray(25)
                for (x in 0 until w) {
                    val i = y * w + x
                    val own = from[i]
                    if (own < 0) { to[i] = own; continue }
                    var kinds = 0
                    for (dy in offsets) for (dx in offsets) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val l = from[ny * w + nx]
                        if (l < 0) continue
                        var k = 0
                        while (k < kinds && ids[k] != l) k++
                        if (k == kinds) { ids[kinds] = l; counts[kinds] = 0; kinds++ }
                        counts[k]++
                    }
                    // the most common area around; on a tie the pixel keeps its own
                    var best = own
                    var bestCount = 0
                    for (k in 0 until kinds) if (counts[k] > bestCount || counts[k] == bestCount && ids[k] == own) {
                        best = ids[k]; bestCount = counts[k]
                    }
                    to[i] = best
                }
            }
            current = to
            next = from
        }
        return current
    }

    /** Long streaks through the bright spots of [plane], along ([dx], [dy]), fading over [length]. */
    private fun streaks(plane: FloatArray, w: Int, h: Int, dx: Double, dy: Double, length: Double): FloatArray {
        val out = FloatArray(w * h)
        val taps = 40
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var sum = 0.0
                var weights = 0.0
                for (k in -taps..taps) {
                    val t = k.toDouble() / taps
                    val weight = (1 - abs(t)).let { it * it * it }
                    val sx = (x + dx * t * length).roundToInt()
                    val sy = (y + dy * t * length).roundToInt()
                    weights += weight
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                    // only the brightest parts streak
                    val l = plane[sy * w + sx] - 0.5f
                    if (l > 0) sum += l * 2 * weight
                }
                out[y * w + x] = (sum / weights * 2.5).toFloat()
            }
        }
        return out
    }

    /**
     * Coverage 0..1 of the pattern at ([x], [y]): a grid of [size] cells, some of them
     * ([density]) holding a shape, a little varied in size, antialiased over a pixel.
     */
    private fun mark(kind: Int, x: Double, y: Double, size: Double, density: Double, noise: Noise): Double {
        val cx = floor(x / size).toInt()
        val cy = floor(y / size).toInt()
        if ((noise.white(cx, cy * 3 + 17) + 1) / 2 >= density) return 0.0
        val shape = if (kind == MIXED) STARS + ((noise.white(cx, cy * 3 + 18) + 1) / 2 * 5).toInt().coerceIn(0, 4) else kind
        val scale = 0.75 + 0.25 * noise.white(cx, cy * 3 + 19)
        // position in the cell in pixels from its center
        val u = x - (cx + 0.5) * size
        val v = y - (cy + 0.5) * size
        val r = size * 0.32 * scale
        val line = (size * 0.06).coerceAtLeast(0.9)
        // signed distance to the shape's outline in pixels (negative inside)
        val d = when (shape) {
            STARS -> {
                // a four-pointed sparkle star: an astroid
                val q = sqrt(abs(u) / r) + sqrt(abs(v) / r)
                (q - 1) * r * 0.5
            }
            CROSSES -> min(box(u, v, r, line), box(u, v, line, r))
            CIRCLES -> abs(hypot(u, v) - r * 0.85) - line
            DOTS -> hypot(u, v) - r * 0.45
            else -> (abs(u) + abs(v) - r) / 1.4142
        }
        return (0.5 - d).coerceIn(0.0, 1.0)
    }

    /** Signed distance to a box of half size [bx] × [by] around the origin. */
    private fun box(u: Double, v: Double, bx: Double, by: Double): Double {
        val qx = abs(u) - bx
        val qy = abs(v) - by
        return hypot(max0(qx), max0(qy)) + min(maxOf(qx, qy), 0.0)
    }

    private fun max0(v: Double) = if (v > 0) v else 0.0

    private fun screen(a: Double, b: Double) = 1 - (1 - a) * (1 - b)

    private fun overlay(a: Double, b: Double) = if (a < 0.5) 2 * a * b else 1 - 2 * (1 - a) * (1 - b)

    /** The foil's color at [t] (0..1, one full cycle). */
    private fun foil(style: Int, t: Double, custom: IntArray): Int = when (style) {
        0 -> Color.HSBtoRGB(t.toFloat(), 0.75f, 1f)
        1 -> cycle(gold, t)
        2 -> cycle(diamond, t)
        else -> cycle(custom, t)
    }

    /** A smooth loop through [stops], back to the first. */
    private fun cycle(stops: IntArray, t: Double): Int {
        val pos = t * stops.size
        val k = floor(pos).toInt() % stops.size
        val f = pos - floor(pos)
        val a = stops[k]
        val b = stops[(k + 1) % stops.size]
        fun mix(s: Int) = ((a shr s and 0xFF) * (1 - f) + (b shr s and 0xFF) * f).roundToInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
