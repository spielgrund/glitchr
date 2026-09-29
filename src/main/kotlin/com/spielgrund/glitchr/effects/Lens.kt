package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
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

/**
 * Lens faults: barrel or pincushion distortion around an optical center, color fringes
 * that grow towards the edge (red/cyan or a whole spectrum), blur towards the edge and a
 * vignette. The glitches break the lens itself: shattered into shards, cracked like a
 * hit windscreen, cut into Fresnel rings, split into facets like an insect eye, or haunted
 * by ghost reflections. Where the glass breaks, bright lines show, with split colors and
 * blur around them.
 */
object Lens : Effect("lens", "Optik", "Objektivfehler: Wölbung, Vignette, chromatische Aberration, Randunschärfe und kaputte Linsen") {
    private val glitches = listOf("Aus", "Scherben", "Fresnel-Ringe", "Facetten", "Geisterbilder", "Risse")

    override val params = listOf(
        Param.Slider("distortion", "Wölbung", -100, 100, 30, " %", "Positiv: tonnenförmig nach aussen gewölbt · negativ: kissenförmig nach innen gezogen"),
        Param.Toggle("fit", "Bild füllen", true, "Skaliert so, dass keine leeren Ränder entstehen; aus: was ausserhalb liegt, wird transparent"),
        Param.Slider("centerX", "Mitte X", 0, 100, 50, " %", "Optische Mitte, bei „Risse“ auch der Einschlag"),
        Param.Slider("centerY", "Mitte Y", 0, 100, 50, " %"),
        Param.Slider("aberration", "Chromatische Aberration", 0, 150, 8, " px", "Farbsäume, die zum Rand hin wachsen (Verschiebung an den Ecken)"),
        Param.Toggle("spectral", "Spektral", false, "Regenbogensaum über das ganze Spektrum statt Rot/Cyan"),
        Param.Slider("edgeBlur", "Randunschärfe", 0, 150, 0, " px", "Unschärfe, die zum Rand hin wächst"),
        Param.Slider("vignette", "Vignette", 0, 100, 40, " %"),
        Param.Slider("vignetteSize", "Vignette Grösse", 0, 100, 55, " %", "Wo die Abdunklung beginnt"),
        Param.Slider("vignetteSoft", "Vignette Weichheit", 1, 100, 60, " %"),
        Param.Choice(
            "glitch", "Glitch", glitches,
            tip = "Scherben: zersprungene Linse, jede Scherbe verrutscht · Fresnel-Ringe: Ringe mit eigener Vergrösserung · " +
                "Facetten: viele kleine Linsen wie ein Insektenauge · Geisterbilder: Spiegelungen heller Stellen quer durch die Mitte · " +
                "Risse: ein Einschlag in der Mitte mit Sprüngen wie ein Spinnennetz",
        ),
        Param.Slider("glitchAmount", "Glitch Stärke", 0, 100, 50, " %"),
        Param.Slider("glitchSize", "Glitch Grösse", 8, 1000, 140, " px", "Grösse der Scherben, Ringe oder Facetten; bei Rissen der Abstand der Ringsprünge"),
        Param.Slider("lineWidth", "Bruchkanten Stärke", 1, 200, 16, tip = "Strichstärke der Trennlinien in Zehntelpixeln: 16 = 1,6 px"),
        Param.Slider("glitchLines", "Bruchkanten hell/dunkel", -100, 100, 70, " %", "Positiv: die Trennlinien zwischen Scherben, Ringen, Facetten und Rissen leuchten hell · negativ: sie werden dunkel"),
        Param.Slider("glitchFringe", "Kanten-Artefakte", 0, 100, 60, " %", "RGB-Verschiebung und Unschärfe, die zu den Bruchkanten hin zunehmen"),
        Param.Slider("elementGradient", "Element-Verlauf", -100, 100, 0, " %", "Verlauf in jedem Glasstück von innen nach aussen: positiv hellt zu den Kanten hin auf, negativ dunkelt ab"),
        Param.Slider("gradientWidth", "Verlauf Breite", 1, 100, 40, " %", "Wie weit der Verlauf von der Kante ins Stück reicht (Anteil der Glitch Grösse)"),
        Param.Slider("tilt", "Neigung", 0, 100, 0, " %", "Jedes Glasstück bekommt eine zufällige Neigung (Normale); sie steuert Beleuchtung und Brechung"),
        Param.Slider("light", "Beleuchtung", 0, 100, 50, " %", "Wie stark die geneigten Stücke vom Licht aufgehellt oder abgedunkelt werden"),
        Param.Slider("lightAngle", "Lichtrichtung", 0, 359, 225, "°", "Woher das Licht kommt; 225° = von oben links"),
        Param.Slider("refraction", "Brechung", 0, 200, 20, " px", "Wie weit jedes geneigte Stück das Bild in seine Neigungsrichtung verschiebt"),
        Param.Slider("crackDirection", "Risse: Vertikal → Radial", 0, 100, 100, " %", "0 %: die Risse laufen von oben nach unten · 100 %: sie laufen vom Einschlag strahlenförmig nach aussen"),
        Param.Slider("crackJag", "Risse: Zackigkeit", 0, 100, 70, " %"),
        Param.Slider("crackBranch", "Risse: Verzweigung", 0, 100, 50, " %", "Wie oft sich die Risse wie Blitze verästeln"),
    )

    private const val SHARDS = 1
    private const val FRESNEL = 2
    private const val FACETS = 3
    private const val GHOSTS = 4
    private const val CRACKS = 5

    /** Samples per side where break lines are antialiased. */
    private const val AA = 4

    /** Blur taps on a small disk: center and six around it. */
    private val disk = listOf(0.0 to 0.0) + List(6) { i -> cos(i * PI / 3) to sin(i * PI / 3) }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val cx = v["centerX"] / 100.0 * w
        val cy = v["centerY"] / 100.0 * h
        val halfDiag = hypot(w.toDouble(), h.toDouble()) / 2
        val k = v["distortion"] / 100.0 * 0.5
        val glitch = v["glitch"]
        val amount = v["glitchAmount"] / 100.0
        val size = v["glitchSize"].toDouble()
        val noise = Noise(seed)
        val aberration = v["aberration"] / halfDiag
        val spectral = v.bool("spectral")
        val edgeBlur = v["edgeBlur"] / halfDiag
        val vignette = v["vignette"] / 100.0
        val vStart = v["vignetteSize"] / 100.0
        val vSoft = v["vignetteSoft"] / 100.0
        val lines = v["glitchLines"] / 100.0
        val gradient = v["elementGradient"] / 100.0
        val gradientWidth = max(1.0, size * 0.5 * v["gradientWidth"] / 100.0)
        val tilt = v["tilt"] / 100.0
        val lightStrength = v["light"] / 100.0
        val lightAngle = Math.toRadians(v["lightAngle"].toDouble())
        // the light shines from lightAngle: a piece tilted towards it gets brighter
        val lightX = cos(lightAngle)
        val lightY = sin(lightAngle)
        val refraction = v["refraction"].toDouble()
        val artifacts = v["glitchFringe"] / 100.0
        // the bright break line (half its width), and the zone around it with split colors and blur
        val lineWidth = v["lineWidth"] / 20.0
        val zone = lineWidth * 3 + min(size * 0.18, 40.0)
        val maxShift = 3 + min(size * 0.06, 14.0)
        val maxBlur = 2 + min(size * 0.04, 8.0)
        val cracks = if (glitch != CRACKS) null else Cracks(
            w, h, cx, cy, size, v["crackDirection"] / 100.0, v["crackJag"] / 100.0, v["crackBranch"] / 100.0,
            zone + lineWidth, seed,
        )

        fun bend(r2: Double) = max(0.05, 1 + k * r2)
        // the scale that keeps the whole border inside the picture
        val fit = if (!v.bool("fit")) 1.0 else {
            var worst = 0.0
            for (i in 0..64) {
                val t = i / 64.0
                for ((bx, by) in listOf(t * w to 0.0, t * w to h.toDouble(), 0.0 to t * h, w.toDouble() to t * h)) {
                    val dx = bx - cx
                    val dy = by - cy
                    val f = bend((dx * dx + dy * dy) / (halfDiag * halfDiag))
                    val sx = dx * f
                    val sy = dy * f
                    // how far the sampled point lies out towards the picture's edge (1 = on it)
                    if (abs(dx) > 1e-9) worst = max(worst, if (sx < 0) -sx / cx.coerceAtLeast(1e-9) else sx / (w - cx).coerceAtLeast(1e-9))
                    if (abs(dy) > 1e-9) worst = max(worst, if (sy < 0) -sy / cy.coerceAtLeast(1e-9) else sy / (h - cy).coerceAtLeast(1e-9))
                }
            }
            worst.coerceAtLeast(1e-6)
        }

        // the samples taken per pixel: position along the fringe (-1..1) and the weight per channel
        val fringe = if (spectral) {
            List(9) { i ->
                val t = i / 4.0 - 1
                doubleArrayOf(t, max(0.0, t), 1 - abs(t), max(0.0, -t))
            }.let { s ->
                val sums = DoubleArray(3) { c -> s.sumOf { it[c + 1] } }
                s.map { doubleArrayOf(it[0], it[1] / sums[0], it[2] / sums[1], it[3] / sums[2]) }
            }
        } else listOf(doubleArrayOf(1.0, 1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0, 1.0))
        val blurTaps = if (edgeBlur > 0) 7 else 1
        // the broken lenses and the fringes can still reach past the border: mirrored then
        val fill = v.bool("fit")
        val none = listOf(0.0 to 0.0)

        /** The whole lens at canvas position ([fx], [fy]): writes r, g, b, a and the distance to the break line into [res]. */
        fun shadeAt(fx: Double, fy: Double, p: DoubleArray, res: DoubleArray) {
            // 1. the broken lens moves the point; p[2] = distance to the nearest break line,
            // p[3] = which piece of glass (NaN: none)
            p[0] = fx
            p[1] = fy
            p[2] = Double.MAX_VALUE
            p[3] = Double.NaN
            when (glitch) {
                SHARDS -> shard(p, size, amount, noise)
                FRESNEL -> fresnel(p, cx, cy, size, amount, noise)
                FACETS -> facet(p, size, amount)
                CRACKS -> cracks!!.apply(p, amount)
            }
            val lineDist = p[2]
            // every piece is tilted at random: its normal bends the view and catches the light
            var nx = 0.0
            var ny = 0.0
            if (tilt > 0 && !p[3].isNaN()) {
                val piece = p[3].toLong()
                val id = (piece xor (piece ushr 32)).toInt()
                val angle = PI * (noise.white(id, 7771) + 1)
                val lean = tilt * sqrt((noise.white(id, 7772) + 1) / 2)
                nx = cos(angle) * lean
                ny = sin(angle) * lean
                p[0] += nx * refraction
                p[1] += ny * refraction
            }
            val near = if (lineDist >= zone) 0.0 else (1 - lineDist / zone).let { it * it } * artifacts
            val shift = near * maxShift
            val localBlur = near * maxBlur
            // 2. distortion around the optical center
            val dx = p[0] - cx
            val dy = p[1] - cy
            val r2 = (dx * dx + dy * dy) / (halfDiag * halfDiag)
            val f = bend(r2) / fit
            val sx = dx * f
            val sy = dy * f
            // 3. fringes and edge blur along the radius; near break lines colors split sideways and blur
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var a = 0.0
            val spots = if (localBlur > 0.3) disk else none
            for (tap in 0 until blurTaps) {
                val blur = if (blurTaps == 1) 0.0 else edgeBlur * r2 * (tap / (blurTaps - 1.0) * 2 - 1)
                for ((ox, oy) in spots) for (s in fringe) {
                    val scale = 1 + aberration * s[0] * sqrt(r2).coerceAtMost(2.0) + blur
                    val c = sample(src, cx + sx * scale + ox * localBlur + s[0] * shift, cy + sy * scale + oy * localBlur, fill)
                    val ca = (c ushr 24) / 255.0
                    r += s[1] * (c shr 16 and 0xFF) * ca
                    g += s[2] * (c shr 8 and 0xFF) * ca
                    b += s[3] * (c and 0xFF) * ca
                    a += s[2] * ca
                }
            }
            val taps = blurTaps * spots.size
            r /= taps; g /= taps; b /= taps; a /= taps
            // back from premultiplied
            if (a > 1e-6) { r /= a; g /= a; b /= a }

            // 4. ghost reflections of the bright parts
            if (glitch == GHOSTS && amount > 0) {
                for ((i, s) in doubleArrayOf(-0.55, -1.25, 0.45, -2.1).withIndex()) {
                    val c = sample(src, cx + (fx - cx) * s, cy + (fy - cy) * s, false)
                    val light = ((0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)) / 255 - 0.45) / 0.55
                    if (light <= 0) continue
                    val strength = light * amount * 0.6 * (c ushr 24) / 255.0
                    // each ghost tinted differently, like reflections off coated glass
                    val tint = ghostTints[i]
                    r += (c shr 16 and 0xFF) * strength * tint[0]
                    g += (c shr 8 and 0xFF) * strength * tint[1]
                    b += (c and 0xFF) * strength * tint[2]
                    a = max(a, strength)
                }
            }

            // 5. vignette, on an ellipse following the picture's shape
            val ex = (fx - cx) / (w / 2.0)
            val ey = (fy - cy) / (h / 2.0)
            val er = sqrt(ex * ex + ey * ey) / sqrt(2.0)
            val t = ((er - vStart) / vSoft).coerceIn(0.0, 1.0)
            val shade = 1 - vignette * t * t * (3 - 2 * t)
            r *= shade; g *= shade; b *= shade

            // 6. light on the tilted pieces
            if (nx != 0.0 || ny != 0.0) {
                val lit = (1 + 1.4 * lightStrength * (nx * lightX + ny * lightY)).coerceAtLeast(0.0)
                r *= lit; g *= lit; b *= lit
            }
            // 7. gradient in each piece, from its inside out to its edges
            if (gradient != 0.0 && lineDist < Double.MAX_VALUE) {
                val e = (1 - lineDist / gradientWidth).coerceIn(0.0, 1.0)
                val m = e * e * (3 - 2 * e) * abs(gradient)
                val target = if (gradient > 0) 255.0 else 0.0
                r += (target - r) * m; g += (target - g) * m; b += (target - b) * m
            }

            // 8. the break line, antialiased over about a pixel: bright or dark
            val line = ((lineWidth - lineDist) / 0.6 + 0.5).coerceIn(0.0, 1.0) * abs(lines)
            if (line > 0) {
                val target = if (lines > 0) 255.0 else 0.0
                r += (target - r) * line
                g += (target - g) * line
                b += (target - b) * line
                a = max(a, line)
            }
            res[0] = r
            res[1] = g
            res[2] = b
            res[3] = a
            res[4] = lineDist
        }

        val out = Pixels(w, h)
        val pieces = glitch == SHARDS || glitch == FRESNEL || glitch == FACETS || glitch == CRACKS
        parallelRows(h) { y ->
            val p = DoubleArray(4)
            val res = DoubleArray(5)
            val sum = DoubleArray(4)
            for (x in 0 until w) {
                shadeAt(x + 0.5, y + 0.5, p, res)
                // along the break lines (and the jumps between tilted pieces) the pixel is
                // sampled 4×4 times, so the lines and edges come out smooth
                if (pieces && res[4] < lineWidth + 2.5) {
                    sum.fill(0.0)
                    for (j in 0 until AA) for (i in 0 until AA) {
                        shadeAt(x + (i + 0.5) / AA, y + (j + 0.5) / AA, p, res)
                        sum[0] += res[0] * res[3]
                        sum[1] += res[1] * res[3]
                        sum[2] += res[2] * res[3]
                        sum[3] += res[3]
                    }
                    val alpha = sum[3] / (AA * AA)
                    for (c in 0..2) res[c] = if (sum[3] > 1e-9) sum[c] / sum[3] else 0.0
                    res[3] = alpha
                }
                out.data[y * w + x] = argb(
                    (res[3] * 255).roundToInt().coerceIn(0, 255),
                    res[0].roundToInt().coerceIn(0, 255),
                    res[1].roundToInt().coerceIn(0, 255),
                    res[2].roundToInt().coerceIn(0, 255),
                )
            }
        }
        return out
    }

    private val ghostTints = arrayOf(
        doubleArrayOf(0.6, 1.0, 0.7), doubleArrayOf(1.0, 0.55, 0.9), doubleArrayOf(0.5, 0.8, 1.0), doubleArrayOf(1.0, 0.85, 0.5),
    )

    /** Bilinear sample; outside the picture it is mirrored when the picture is to be filled, else transparent. */
    private fun sample(p: Pixels, x: Double, y: Double, fill: Boolean): Int {
        if (!fill && (x < 0 || y < 0 || x > p.width || y > p.height)) return 0
        return sampleBilinear(p, x, y, Edge.MIRROR)
    }

    /**
     * Shattered lens: a jittered grid of shards (Voronoi cells); every shard is shifted,
     * turned and scaled a little around its own center.
     */
    private fun shard(p: DoubleArray, size: Double, amount: Double, noise: Noise) {
        val gx = floor(p[0] / size).toInt()
        val gy = floor(p[1] / size).toInt()
        var best = Double.MAX_VALUE
        var bi = 0
        var bj = 0
        var bx = 0.0
        var by = 0.0
        for (j in gy - 2..gy + 2) for (i in gx - 2..gx + 2) {
            val sx = (i + 0.5 + 0.45 * noise.white(i, j * 7 + 1)) * size
            val sy = (j + 0.5 + 0.45 * noise.white(i, j * 7 + 2)) * size
            val d = hypot(p[0] - sx, p[1] - sy)
            if (d < best) {
                best = d
                bi = i; bj = j; bx = sx; by = sy
            }
        }
        // exact distance to the nearest border: to the bisector between this shard's center and each neighbour's
        var border = Double.MAX_VALUE
        for (j in gy - 2..gy + 2) for (i in gx - 2..gx + 2) {
            if (i == bi && j == bj) continue
            val sx = (i + 0.5 + 0.45 * noise.white(i, j * 7 + 1)) * size
            val sy = (j + 0.5 + 0.45 * noise.white(i, j * 7 + 2)) * size
            val ex = sx - bx
            val ey = sy - by
            val len = hypot(ex, ey)
            if (len < 1e-9) continue
            // how far the midpoint between the two centers lies beyond the point, along their connection
            border = min(border, ((bx + sx) / 2 - p[0]) * ex / len + ((by + sy) / 2 - p[1]) * ey / len)
        }
        val turn = amount * 0.2 * noise.white(bi, bj * 7 + 3)
        val zoom = 1 + amount * 0.25 * noise.white(bi, bj * 7 + 4)
        val ox = amount * size * 0.35 * noise.white(bi, bj * 7 + 5)
        val oy = amount * size * 0.35 * noise.white(bi, bj * 7 + 6)
        val dx = (p[0] - bx) / zoom
        val dy = (p[1] - by) / zoom
        p[0] = bx + dx * cos(turn) - dy * sin(turn) + ox
        p[1] = by + dx * sin(turn) + dy * cos(turn) + oy
        // the break: where the two nearest shards are about equally far away
        p[2] = border
        p[3] = (bi.toLong() * 73856093L xor bj.toLong() * 19349663L).toDouble()
    }

    /** Fresnel lens: concentric rings, each with its own magnification. */
    private fun fresnel(p: DoubleArray, cx: Double, cy: Double, size: Double, amount: Double, noise: Noise) {
        val dx = p[0] - cx
        val dy = p[1] - cy
        val r = hypot(dx, dy)
        val ring = floor(r / size).toInt()
        // the rings step between enlarging and shrinking, a little randomly
        val m = 1 + amount * (0.35 * noise.white(ring, 11) + if (ring % 2 == 0) 0.12 else -0.12)
        p[0] = cx + dx * m
        p[1] = cy + dy * m
        // the innermost ring has no edge at its center
        p[2] = if (ring == 0) size - r else min(r - ring * size, (ring + 1) * size - r)
        p[3] = ring.toDouble()
    }

    /** Facets like an insect eye: a brick grid of small lenses, each showing a shrunken view around its center. */
    private fun facet(p: DoubleArray, size: Double, amount: Double) {
        val row = floor(p[1] / size)
        val shift = if (row.toInt() % 2 == 0) 0.0 else size / 2
        val col = floor((p[0] - shift) / size)
        val fx = (col + 0.5) * size + shift
        val fy = (row + 0.5) * size
        val z = 1 + amount * 1.5
        val ux = p[0] - fx
        val uy = p[1] - fy
        p[0] = fx + ux * z
        p[1] = fy + uy * z
        p[2] = size / 2 - max(abs(ux), abs(uy))
        p[3] = (row.toLong() * 73856093L xor col.toLong() * 19349663L).toDouble()
    }

    /**
     * Cracked glass: jagged cracks that fork like lightning. They start at the top and run
     * down, or burst out of the point of impact, or anything in between. The branches get
     * thinner with every fork. The glass on either side of a crack is pushed apart a little.
     */
    private class Cracks(
        val w: Int, val h: Int, cx: Double, cy: Double, size: Double,
        radial: Double, jag: Double, branching: Double, reach: Double, seed: Long,
    ) {
        /** Segments: x0, y0, x1, y1, width factor (1 for main cracks), crack number. */
        private val segs = ArrayList<DoubleArray>()
        private val cell = max(16.0, reach)
        private val cols = (w / cell).toInt() + 1
        private val rows = (h / cell).toInt() + 1
        private val grid = Array(cols * rows) { IntArrayList() }
        private val push = size * 0.06

        /** Which piece of glass each pixel belongs to: the areas the cracks enclose, numbered. */
        private val pieces = IntArray(w * h) { -1 }

        init {
            val rnd = kotlin.random.Random(seed)
            fun gauss() = rnd.nextDouble() * 2 - 1 + rnd.nextDouble() * 2 - 1 + rnd.nextDouble() * 2 - 1
            val step = (size * 0.05).coerceIn(2.0, 14.0)
            val limit = 2.5 * hypot(w.toDouble(), h.toDouble())
            // the zigzag of every step around a course that only drifts slowly, like lightning
            val wiggle = 0.1 + jag * 1.0
            // fixed, so the pattern stays the same whatever the line width
            val margin = 50.0

            fun walk(startX: Double, startY: Double, heading: Double, length: Double, depth: Int, crack: Int) {
                var x = startX
                var y = startY
                var course = heading
                var travelled = 0.0
                while (travelled < length && segs.size < 60_000) {
                    course += (heading - course) * 0.08 + gauss() * 0.05
                    val dir = course + gauss() / 1.7 * wiggle
                    val len = step * (0.4 + rnd.nextDouble() * 1.2)
                    val nx = x + cos(dir) * len
                    val ny = y + sin(dir) * len
                    segs.add(doubleArrayOf(x, y, nx, ny, Math.pow(0.62, depth.toDouble()), crack.toDouble()))
                    x = nx
                    y = ny
                    travelled += len
                    if (x < -margin || y < -margin || x > w + margin || y > h + margin) return
                    // forks: rarer on thin branches
                    if (depth < 4 && rnd.nextDouble() < branching * 0.07 / (1 + depth)) {
                        val side = if (rnd.nextBoolean()) 1 else -1
                        val turn = side * (0.3 + rnd.nextDouble() * 0.6)
                        val rest = if (depth == 0) size * (1.5 + rnd.nextDouble() * 4) else (length - travelled) * (0.2 + rnd.nextDouble() * 0.5)
                        walk(x, y, course + turn, rest, depth + 1, crack)
                    }
                }
            }

            val count = (w / size).roundToInt().coerceIn(3, 60)
            for (i in 0 until count) {
                // vertical: evenly along the top edge, heading down; radial: out of the impact
                val vx = (i + 0.5 + 0.6 * (rnd.nextDouble() - 0.5)) * w / count
                val vy = -2.0
                val angle = 2 * PI * (i + 0.7 * (rnd.nextDouble() - 0.5)) / count
                val sx = vx + (cx - vx) * radial
                val sy = vy + (cy - vy) * radial
                var diff = angle - PI / 2
                diff -= 2 * PI * floor(diff / (2 * PI) + 0.5)
                walk(sx, sy, PI / 2 + diff * radial, limit, 0, i)
            }

            for ((index, sg) in segs.withIndex()) {
                val x0 = floor((min(sg[0], sg[2]) - reach) / cell).toInt().coerceIn(0, cols - 1)
                val x1 = floor((max(sg[0], sg[2]) + reach) / cell).toInt().coerceIn(0, cols - 1)
                val y0 = floor((min(sg[1], sg[3]) - reach) / cell).toInt().coerceIn(0, rows - 1)
                val y1 = floor((max(sg[1], sg[3]) + reach) / cell).toInt().coerceIn(0, rows - 1)
                for (gy in y0..y1) for (gx in x0..x1) grid[gy * cols + gx].add(index)
            }

            // draw the cracks one pixel thin, then number the areas between them (4-connected,
            // so a diagonal crack still separates)
            val crack = -2
            for (sg in segs) {
                val steps = (hypot(sg[2] - sg[0], sg[3] - sg[1]) / 0.4).toInt() + 1
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val x = floor(sg[0] + (sg[2] - sg[0]) * t).toInt()
                    val y = floor(sg[1] + (sg[3] - sg[1]) * t).toInt()
                    if (x in 0 until w && y in 0 until h) pieces[y * w + x] = crack
                }
            }
            var next = 0
            val stack = IntArrayList()
            for (start in pieces.indices) {
                if (pieces[start] != -1) continue
                pieces[start] = next
                stack.clear()
                stack.add(start)
                while (stack.size > 0) {
                    val i = stack.pop()
                    val x = i % w
                    val y = i / w
                    if (x > 0 && pieces[i - 1] == -1) { pieces[i - 1] = next; stack.add(i - 1) }
                    if (x < w - 1 && pieces[i + 1] == -1) { pieces[i + 1] = next; stack.add(i + 1) }
                    if (y > 0 && pieces[i - w] == -1) { pieces[i - w] = next; stack.add(i - w) }
                    if (y < h - 1 && pieces[i + w] == -1) { pieces[i + w] = next; stack.add(i + w) }
                }
                next++
            }
            // the crack pixels themselves join a neighbouring piece
            repeat(2) {
                for (i in pieces.indices) if (pieces[i] == crack) {
                    val x = i % w
                    pieces[i] = when {
                        x > 0 && pieces[i - 1] >= 0 -> pieces[i - 1]
                        x < w - 1 && pieces[i + 1] >= 0 -> pieces[i + 1]
                        i >= w && pieces[i - w] >= 0 -> pieces[i - w]
                        i + w < pieces.size && pieces[i + w] >= 0 -> pieces[i + w]
                        else -> crack
                    }
                }
            }
        }

        fun apply(p: DoubleArray, amount: Double) {
            val px = floor(p[0]).toInt().coerceIn(0, w - 1)
            val py = floor(p[1]).toInt().coerceIn(0, h - 1)
            pieces[py * w + px].let { if (it >= 0) p[3] = it.toDouble() }
            val gx = (p[0] / cell).toInt().coerceIn(0, cols - 1)
            val gy = (p[1] / cell).toInt().coerceIn(0, rows - 1)
            val list = grid[gy * cols + gx]
            var best = Double.MAX_VALUE
            var side = 0.0
            var nx = 0.0
            var ny = 0.0
            var crack = 0
            for (n in 0 until list.size) {
                val sg = segs[list[n]]
                val dx = sg[2] - sg[0]
                val dy = sg[3] - sg[1]
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-12) continue
                val t = (((p[0] - sg[0]) * dx + (p[1] - sg[1]) * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(p[0] - sg[0] - dx * t, p[1] - sg[1] - dy * t)
                // thinner branches: the same distance counts as further away
                val scaled = d / sg[4]
                if (scaled < best) {
                    best = scaled
                    val len = sqrt(len2)
                    nx = -dy / len
                    ny = dx / len
                    side = if ((p[0] - sg[0]) * nx + (p[1] - sg[1]) * ny >= 0) 1.0 else -1.0
                    crack = sg[5].toInt()
                }
            }
            if (best == Double.MAX_VALUE) return
            // the glass on both sides of the crack is pushed apart, fading with the distance
            val fade = (1 - best / (push * 8).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val strength = amount * push * fade * (0.6 + 0.4 * ((crack * 0.618) % 1.0))
            p[0] += nx * side * strength
            p[1] += ny * side * strength
            p[2] = best
        }
    }

    /** A growable list of ints, lighter than a List<Int>. */
    private class IntArrayList {
        private var data = IntArray(4)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int) = data[i]

        fun pop() = data[--size]

        fun clear() {
            size = 0
        }
    }
}
