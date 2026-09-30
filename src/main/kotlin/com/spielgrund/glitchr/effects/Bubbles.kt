package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A foam over the picture: the start mask (a threshold range) first melts into one large
 * area (gaps up to "Verschmelzen" close). On it bubbles appear one after another and grow
 * until they press against their neighbours; now and then two pressed ones fuse into a
 * larger one (keeping their area). Afterwards the gaps are filled with ever smaller bubbles.
 *
 * Bubbles never overlap: where two press together, the wall between them is the straight
 * line of the power diagram (as in real foam). Each cell bends the picture like a sphere –
 * bloat (magnified in the middle, squeezed towards the walls) or pinch – running out
 * smoothly to its walls, and the larger the bubble, the stronger. The surface is lit
 * (diffuse, highlight, rim) and can shimmer like a thin film; walls, rims and highlights
 * are anti-aliased by supersampling.
 */
object Bubbles : Effect("bubbles", "Blasen", "Schaum: auf dem Schwellenbereich wachsen Blasen, drücken sich aneinander und wölben das Bild wie Kugeln") {
    class Bubble(var x: Double, var y: Double, var r: Double, var rate: Double, var pinch: Boolean, var limit: Double)

    private const val CELL = 48

    /** Interference colors of a thin soap film, from thin to thick; runs round. */
    val SOAP_FILM = ColorRamp(
        listOf(
            ColorStop(0.0, 0xF2C85B), ColorStop(0.18, 0xD8487A), ColorStop(0.36, 0x6A3FD0), ColorStop(0.52, 0x2F8FE0),
            ColorStop(0.68, 0x3FD6A0), ColorStop(0.84, 0xE6E26A), ColorStop(1.0, 0xF2C85B),
        ),
    ).format()

    override val params = listOf(
        Param.Heading("startHeading", "Startmaske"),
        Param.Choice("source", "Wert", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Untere Schwelle", 0, 255, 140, tip = "Beim Farbton darf sie über der oberen liegen (Bereich über Rot hinweg)"),
        Param.Slider("upper", "Obere Schwelle", 0, 255, 255),
        Param.Toggle("invert", "Bereich umkehren", false),
        Param.Toggle("showMask", "Maske und Blasen zeigen", false, "Startmaske weiss, verschmolzene Fläche grau, Blasenwände rot"),
        Param.Slider("merge", "Verschmelzen", 0, 300, 80, " px", "Lücken der Startmaske bis zu dieser Weite schliessen sich zu einer grossen Fläche", canvasMax = true),
        Param.Heading("foamHeading", "Schaum"),
        Param.Slider("steps", "Schritte", 1, 1000, 200, tip = "Wie lange die Blasen wachsen"),
        Param.Slider("bubbles", "Anzahl", 1, 500, 80, tip = "So viele Blasen entstehen im Lauf der Schritte auf der Fläche"),
        Param.Slider("bubbleGrowth", "Blasenwachstum", 1, 100, 3, " px", "So viel wächst der Radius je Schritt", decimals = 1),
        Param.Slider("bubbleMax", "Max. Radius", 2, 1000, 60, " px", "Bis hierhin wächst eine Blase allein – verschmolzene werden grösser", canvasMax = true),
        Param.Slider("overhang", "Über den Rand", 0, 200, 30, " %", "Wie weit eine Blase über den Rand der Fläche wachsen darf"),
        Param.Slider("press", "Wanddruck", 0, 100, 60, " %", "Wie fest sich Blasen aneinanderdrücken, bevor sie aufhören zu wachsen – mehr: längere, flache Wände"),
        Param.Slider("fuse", "Zusammenschluss", 0, 100, 20, " %", "Wie oft die Wand zwischen zwei gedrückten Blasen platzt und sie zu einer grösseren werden"),
        Param.Slider("fill", "Zwischenräume füllen", 0, 2000, 300, tip = "So viele kleine Blasen füllen danach die Lücken, von gross nach klein"),
        Param.Slider("fillMin", "Kleinste Blase", 1, 100, 3, " px", "Kleiner werden die Füllblasen nicht"),
        Param.Heading("bendHeading", "Wölbung"),
        Param.Slider("bulge", "Wölbung", -100, 100, 90, " %", "Positiv aufblähen wie eine Kugel (Mitte vergrössert, zu den Wänden gestaucht), negativ zusammenziehen (Pinch)"),
        Param.Slider("pinchShare", "Anteil umgekehrt", 0, 100, 0, " %", "So viele Blasen wölben in die Gegenrichtung"),
        Param.Slider("sizeInfluence", "Grösse wirkt", 0, 100, 50, " %", "100 %: je grösser die Blase, desto stärker die Wölbung (voll ab Max. Radius)"),
        Param.Heading("lightHeading", "Licht"),
        Param.Toggle("lightOn", "Licht an", true, "Aus: keine Schattierung, kein Glanz, kein Randlicht – die Regler bleiben erhalten"),
        Param.Slider("lightAngle", "Lichtrichtung", 0, 359, 225, "°", "Aus dieser Richtung fällt das Licht (225° = von oben links)"),
        Param.Slider("lightHeight", "Lichthöhe", 5, 90, 40, "°", "Flach: Glanz am Rand, 90°: Licht von vorn"),
        Param.Slider("shading", "Schattierung", 0, 100, 45, " %", "Die vom Licht abgewandte Seite wird dunkler"),
        Param.Slider("gloss", "Glanz", 0, 100, 60, " %", "Glanzpunkt, wo das Licht gespiegelt wird"),
        Param.Slider("glossSize", "Glanzgrösse", 1, 100, 25, " %"),
        Param.Slider("rim", "Randlicht", 0, 100, 25, " %", "Heller Saum an den Wänden, wie bei Glas oder Seifenblasen"),
        Param.Heading("irisHeading", "Schillern"),
        Param.Slider("iris", "Schillern", 0, 100, 40, " %", "Farben wie ein dünner Film (Seifenblase, Öl) – zum Rand hin stärker"),
        Param.Ramp("irisRamp", "Filmfarben", SOAP_FILM, "Die Farben, die der Film je nach Dicke zeigt (von links nach rechts, läuft mehrmals durch)"),
        Param.Slider("irisBands", "Farbbänder", 1, 20, 3, tip = "Wie oft der Verlauf von der Mitte bis zum Rand durchläuft"),
        Param.Slider("irisSwirl", "Schlieren", 0, 100, 50, " %", "Der Film ist ungleich dick – wirbelnde Farbschlieren"),
        Param.Heading("outHeading", "Ausgabe"),
        Param.Choice(
            "antialias", "Kantenglättung", listOf("Aus", "2 × 2", "4 × 4"), 2,
            "Wände, Ränder und Glanzpunkte werden mehrfach abgetastet und gemittelt – glatte Kanten",
        ),
        Param.Slider("amount", "Stärke", 0, 100, 100, " %"),
        Param.Choice("precision", "Rechengenauigkeit", listOf("1 px (voll)", "2 px", "4 px"), 1, "Für die Fläche – gröber ist schneller"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val mode = v["source"]
        val lower = v["lower"]
        val upper = v["upper"]
        val invert = v.bool("invert")
        val n = 1 shl v["precision"]
        val small = Grow.reduce(src, n)
        val start = BooleanArray(small.data.size) { alpha(small.data[it]) > 0 && thresholdSelects(small.data[it], mode, lower, upper, invert) }
        if (start.none { it } && !v.bool("showMask")) return src
        val gw = small.width
        val gh = small.height
        val area = area(start, gw, gh, n, v["merge"])
        val bubbles = simulate(area, gw, gh, n, v, seed)
        if (v.bool("showMask")) return mask(src, start, area, gw, n, bubbles)
        if (bubbles.isEmpty()) return src
        return bend(src, bubbles, v, seed)
    }

    /** The start mask with its gaps closed: blurred and cut low, so neighbouring parts melt into one area. */
    private fun area(start: BooleanArray, w: Int, h: Int, n: Int, merge: Int): BooleanArray {
        val radius = (merge / 2.0 / n).roundToInt()
        if (radius < 1) return start
        val soft = Grow.blur(FloatArray(start.size) { if (start[it]) 1f else 0f }, w, h, radius)
        return BooleanArray(start.size) { start[it] || soft[it] > 0.25f }
    }

    /** How deep two bubbles press into each other, relative to the smaller one. */
    private fun press(a: Bubble, b: Bubble) = (a.r + b.r - hypot(a.x - b.x, a.y - b.y)) / min(a.r, b.r)

    /** Bubbles are born over the steps at free places of the area, grow until pressed, fuse; then the gaps fill. */
    private fun simulate(area: BooleanArray, w: Int, h: Int, n: Int, v: Values, seed: Long): List<Bubble> {
        val cells = area.indices.filter { area[it] }
        if (cells.isEmpty()) return emptyList()
        // how far each cell lies inside the area (full picture pixels)
        val inside = distanceTransform(BooleanArray(area.size) { !area[it] }, w, h).first
        val rnd = java.util.Random(seed * 31 + 7)
        val steps = v["steps"]
        val growth = v["bubbleGrowth"] / 10.0
        val maxRadius = v["bubbleMax"].toDouble()
        val overhang = v["overhang"] / 100.0
        val pressLimit = v["press"] / 100.0 * 0.8
        val fuseChance = v["fuse"] / 100.0 * 0.02
        val pinchShare = v["pinchShare"] / 100.0
        fun insideAt(x: Double, y: Double): Double {
            val cx = (x / n).toInt().coerceIn(0, w - 1)
            val cy = (y / n).toInt().coerceIn(0, h - 1)
            return if (area[cy * w + cx]) inside[cy * w + cx] * n * (1 + overhang) else 0.0
        }
        fun limitAt(x: Double, y: Double) = min(maxRadius, max(1.0, insideAt(x, y)))

        val births = IntArray(v["bubbles"]) { 1 + rnd.nextInt(steps) }.sorted()
        val bubbles = ArrayList<Bubble>()
        var next = 0
        for (step in 1..steps) {
            while (next < births.size && births[next] <= step) {
                next++
                // a free place on the area (not inside a bubble), a few tries
                for (attempt in 0 until 12) {
                    val cell = cells[rnd.nextInt(cells.size)]
                    val x = (cell % w + rnd.nextDouble()) * n
                    val y = (cell / w + rnd.nextDouble()) * n
                    if (bubbles.any { hypot(it.x - x, it.y - y) < it.r + 1 }) continue
                    bubbles += Bubble(x, y, 1.0, growth * (0.5 + rnd.nextDouble()), rnd.nextDouble() < pinchShare, limitAt(x, y))
                    break
                }
            }
            // grow while not pressed too hard against a neighbour
            val pressed = BooleanArray(bubbles.size)
            for (i in bubbles.indices) for (j in i + 1 until bubbles.size) {
                if (press(bubbles[i], bubbles[j]) > pressLimit) { pressed[i] = true; pressed[j] = true }
            }
            for ((i, b) in bubbles.withIndex()) if (!pressed[i] && b.r < b.limit) b.r = min(b.limit, b.r + b.rate)
            // now and then the wall between two pressed bubbles bursts
            if (fuseChance > 0) {
                var i = 0
                while (i < bubbles.size) {
                    var j = i + 1
                    while (j < bubbles.size) {
                        if (press(bubbles[i], bubbles[j]) > pressLimit * 0.7 && rnd.nextDouble() < fuseChance) {
                            fuse(bubbles[i], bubbles[j], ::limitAt)
                            bubbles.removeAt(j)
                        } else j++
                    }
                    i++
                }
            }
            absorb(bubbles, ::limitAt)
        }

        // fill the gaps: again and again the largest free circle of a few random places, down to the smallest size
        val fill = v["fill"]
        val fillMin = v["fillMin"].toDouble()
        var placed = 0
        var misses = 0
        while (placed < fill && misses < 8) {
            var bestX = 0.0; var bestY = 0.0; var bestFree = 0.0
            repeat(60) {
                val cell = cells[rnd.nextInt(cells.size)]
                val x = (cell % w + rnd.nextDouble()) * n
                val y = (cell / w + rnd.nextDouble()) * n
                var free = min(maxRadius, insideAt(x, y))
                for (b in bubbles) {
                    free = min(free, hypot(b.x - x, b.y - y) - b.r)
                    if (free < bestFree) break
                }
                if (free > bestFree) { bestFree = free; bestX = x; bestY = y }
            }
            if (bestFree < fillMin) { misses++; continue }
            misses = 0
            // it presses a little into its neighbours, so the walls close
            val r = bestFree * (1 + pressLimit * 0.5)
            bubbles += Bubble(bestX, bestY, r, 0.0, rnd.nextDouble() < pinchShare, r)
            placed++
        }
        return bubbles
    }

    /** [b] melts into [a]: their joint area, centred on their weighted middle. */
    private fun fuse(a: Bubble, b: Bubble, limitAt: (Double, Double) -> Double) {
        val wa = a.r * a.r
        val wb = b.r * b.r
        a.x = (a.x * wa + b.x * wb) / (wa + wb)
        a.y = (a.y * wa + b.y * wb) / (wa + wb)
        a.rate = (a.rate * wa + b.rate * wb) / (wa + wb)
        if (wb > wa) a.pinch = b.pinch
        a.r = sqrt(wa + wb)
        a.limit = max(a.r, limitAt(a.x, a.y))
    }

    /** Bubbles whose middle ended up inside a larger one are swallowed by it. */
    private fun absorb(bubbles: MutableList<Bubble>, limitAt: (Double, Double) -> Double) {
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in bubbles.indices) for (j in bubbles.indices) {
                if (i == j) continue
                val big = bubbles[i]
                val small = bubbles[j]
                if (big.r >= small.r && hypot(big.x - small.x, big.y - small.y) < big.r) {
                    fuse(big, small, limitAt)
                    bubbles.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
    }

    /** The bubbles sorted into a coarse grid of [CELL] pixels, so each pixel only looks at the ones near it. */
    private fun grid(bubbles: List<Bubble>, w: Int, h: Int): Array<IntArray> {
        val cw = (w + CELL - 1) / CELL
        val ch = (h + CELL - 1) / CELL
        val lists = Array(cw * ch) { ArrayList<Int>() }
        bubbles.forEachIndexed { k, b ->
            val cx0 = ((b.x - b.r) / CELL).toInt().coerceIn(0, cw - 1)
            val cx1 = ((b.x + b.r) / CELL).toInt().coerceIn(0, cw - 1)
            val cy0 = ((b.y - b.r) / CELL).toInt().coerceIn(0, ch - 1)
            val cy1 = ((b.y + b.r) / CELL).toInt().coerceIn(0, ch - 1)
            for (cy in cy0..cy1) for (cx in cx0..cx1) lists[cy * cw + cx] += k
        }
        return Array(lists.size) { lists[it].toIntArray() }
    }

    /** For every bubble the ones it presses against (they share a wall). */
    private fun neighbours(bubbles: List<Bubble>): Array<IntArray> = Array(bubbles.size) { i ->
        val a = bubbles[i]
        bubbles.indices.filter { j -> j != i && hypot(a.x - bubbles[j].x, a.y - bubbles[j].y) < a.r + bubbles[j].r }.toIntArray()
    }

    /**
     * The foam cell a point belongs to: of the bubbles covering it the one with the lowest
     * power (distance² − radius²), so neighbours meet at straight walls. -1 if none covers it.
     */
    private fun owner(bubbles: List<Bubble>, candidates: IntArray, px: Double, py: Double): Int {
        var best = -1
        var bestPower = 0.0
        for (k in candidates) {
            val b = bubbles[k]
            val dx = px - b.x
            val dy = py - b.y
            val power = dx * dx + dy * dy - b.r * b.r
            if (power < bestPower) { bestPower = power; best = k }
        }
        return best
    }

    /**
     * Liquify per foam cell: along the ray from the bubble's middle through the pixel, the
     * cell reaches to its rim or the nearest wall; within that the picture is bent like a
     * sphere – bloat magnifies the middle and squeezes towards the walls, pinch the other
     * way round – and runs out smoothly exactly at the wall, so neighbouring cells meet
     * without a jump.
     */
    private fun bend(src: Pixels, bubbles: List<Bubble>, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val bulge = v["bulge"] / 100.0
        val sizeInfluence = v["sizeInfluence"] / 100.0
        val amount = v["amount"] / 100.0
        val light = Light(v, seed)
        val full = max(1.0, v["bubbleMax"].toDouble())
        // strength per bubble 0..0.97, scaled by its size (full at the maximal radius); negative: pinch
        val strength = DoubleArray(bubbles.size) { k ->
            val b = bubbles[k]
            val size = 1 - sizeInfluence + sizeInfluence * min(1.0, b.r / full)
            val s = (bulge * size).coerceIn(-0.97, 0.97)
            if (b.pinch) -s else s
        }
        val grid = grid(bubbles, w, h)
        val walls = neighbours(bubbles)
        val cw = (w + CELL - 1) / CELL
        val ch = (h + CELL - 1) / CELL
        fun ownerAt(px: Double, py: Double): Int {
            val gx = (px / CELL).toInt().coerceIn(0, cw - 1)
            val gy = (py / CELL).toInt().coerceIn(0, ch - 1)
            return owner(bubbles, grid[gy * cw + gx], px, py)
        }

        /** The color at (px, py); [info] receives how close to the wall it lies (t) and the highlight. */
        fun colorAt(px: Double, py: Double, base: Int, info: DoubleArray): Int {
            info[0] = 0.0
            info[1] = 0.0
            val k = ownerAt(px, py)
            if (k < 0) return base
            val b = bubbles[k]
            val ox = px - b.x
            val oy = py - b.y
            val d = hypot(ox, oy)
            if (d < 1e-9) return sampleBilinear(src, px, py, Edge.CLAMP)
            val ux = ox / d
            val uy = oy / d
            // how far the cell reaches in this direction: its rim or the wall to a neighbour
            var reach = b.r
            for (j in walls[k]) {
                val c = bubbles[j]
                val cx = c.x - b.x
                val cy = c.y - b.y
                val along = ux * cx + uy * cy
                if (along <= 1e-9) continue
                val wall = (cx * cx + cy * cy - c.r * c.r + b.r * b.r) / (2 * along)
                if (wall < reach) reach = wall
            }
            val t = (d / reach).coerceIn(0.0, 1.0)
            val s = strength[k]
            // sphere: bloat samples closer to the middle (1 − √(1 − t²) is flat there, steep at the wall)
            val t2 = if (s >= 0) (1 - s) * t + s * (1 - sqrt(1 - t * t))
            else (1 + s) * t - s * sqrt(max(0.0, 1 - (1 - t) * (1 - t)))
            val sx = b.x + ux * t2 * reach
            val sy = b.y + uy * t2 * reach
            val c = sampleBilinear(src, px + (sx - px) * amount, py + (sy - py) * amount, Edge.CLAMP)
            // the surface normal of a ball over the cell (a dimple for pinch)
            val nz = sqrt(max(0.0, 1 - t * t))
            val flip = if (s < 0) -1 else 1
            info[0] = t
            info[1] = light.highlight(ux * t * flip, uy * t * flip, nz)
            return light.shade(c, ux * t * flip, uy * t * flip, nz, px, py, k, amount)
        }

        // supersampling where it matters: walls and outer rims (the owner changes within the
        // pixel), the steep part near the walls, and highlights
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val info = DoubleArray(2)
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val center = colorAt(x + 0.5, y + 0.5, base, info)
                if (sub == 1) {
                    out.data[i] = center
                    continue
                }
                val o = ownerAt(x + 0.5, y + 0.5)
                val edge = info[0] > 0.8 || info[1] > 0.02 ||
                    ownerAt(x.toDouble(), y.toDouble()) != o || ownerAt(x + 1.0, y.toDouble()) != o ||
                    ownerAt(x.toDouble(), y + 1.0) != o || ownerAt(x + 1.0, y + 1.0) != o
                if (!edge) {
                    out.data[i] = center
                    continue
                }
                var a = 0; var r = 0; var g = 0; var bl = 0
                for (sy in 0 until sub) for (sx in 0 until sub) {
                    val c = colorAt(x + (sx + 0.5) / sub, y + (sy + 0.5) / sub, base, info)
                    a += alpha(c); r += red(c); g += green(c); bl += blue(c)
                }
                val count = sub * sub
                out.data[i] = argb((a + count / 2) / count, (r + count / 2) / count, (g + count / 2) / count, (bl + count / 2) / count)
            }
        }
        return out
    }

    /**
     * Lighting of a bubble surface with normal (nx, ny, nz): diffuse light from a direction
     * (relative to a flat surface, so the lit side keeps the picture's brightness), a
     * Blinn-Phong highlight, a Fresnel rim, and thin-film interference colors whose film
     * thickness grows towards the rim and swirls with noise.
     */
    private class Light(v: Values, seed: Long) {
        private val on = v.bool("lightOn")
        private val shading = if (on) v["shading"] / 100.0 else 0.0
        private val gloss = if (on) v["gloss"] / 100.0 else 0.0
        private val shininess = 4 + (1 - v["glossSize"] / 100.0).pow(2) * 300
        private val rim = if (on) v["rim"] / 100.0 else 0.0
        private val iris = v["iris"] / 100.0
        private val ramp = ColorRamp.parse(v.text("irisRamp").ifBlank { SOAP_FILM })
        private val bands = v["irisBands"].toDouble()
        private val swirl = v["irisSwirl"] / 100.0
        private val noise = Noise(seed * 17 + 3)
        private val seed = seed
        private val lx: Double
        private val ly: Double
        private val lz: Double
        private val hx: Double
        private val hy: Double
        private val hz: Double

        init {
            val a = Math.toRadians(v["lightAngle"].toDouble())
            val e = Math.toRadians(v["lightHeight"].toDouble())
            lx = kotlin.math.cos(a) * kotlin.math.cos(e)
            ly = kotlin.math.sin(a) * kotlin.math.cos(e)
            lz = kotlin.math.sin(e)
            // half vector between light and viewer (0, 0, 1)
            val hl = sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1))
            hx = lx / hl
            hy = ly / hl
            hz = (lz + 1) / hl
        }

        /** How bright the highlight is at this normal (to find where supersampling is needed). */
        fun highlight(nx: Double, ny: Double, nz: Double) =
            gloss * max(0.0, nx * hx + ny * hy + nz * hz).pow(shininess) + rim * (1 - nz).pow(3)

        fun shade(c: Int, nx: Double, ny: Double, nz: Double, px: Double, py: Double, bubble: Int, amount: Double): Int {
            var r = red(c) / 255.0
            var g = green(c) / 255.0
            var b = blue(c) / 255.0
            // diffuse: 1 where the surface faces the light like a flat one, darker away from it
            val lambert = max(0.0, nx * lx + ny * ly + nz * lz) / lz
            val diffuse = 1 + shading * amount * (min(1.4, lambert) - 1)
            r *= diffuse; g *= diffuse; b *= diffuse
            val fresnel = (1 - nz).pow(3)
            // thin film: thicker towards the rim and swirling; the ramp runs round with the thickness
            if (iris > 0) {
                val phase = hash(bubble)
                val swirlValue = if (swirl > 0) swirl * noise.fbm(px / 40.0, py / 40.0, 3) else 0.0
                val thickness = phase + bands * (1 - nz * 0.8) + swirlValue * 1.5
                val f = thickness - kotlin.math.floor(thickness)
                val film = ramp.lut[(f * 255).roundToInt().coerceIn(0, 255)]
                val weight = iris * amount * (0.35 + 0.65 * (1 - nz))
                // screen: the film's reflection lies over the picture
                r = r + (1 - (1 - r) * (1 - red(film) / 255.0) - r) * weight
                g = g + (1 - (1 - g) * (1 - green(film) / 255.0) - g) * weight
                b = b + (1 - (1 - b) * (1 - blue(film) / 255.0) - b) * weight
            }
            // highlight and rim: reflected white light
            val spec = gloss * max(0.0, nx * hx + ny * hy + nz * hz).pow(shininess) + rim * fresnel
            val add = spec * amount
            r += add; g += add; b += add
            fun ch(x: Double) = (x * 255).roundToInt().coerceIn(0, 255)
            return argb(alpha(c), ch(r), ch(g), ch(b))
        }

        private fun hash(i: Int): Double {
            var x = i.toLong() * -0x61c8864680b583ebL + seed
            x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
            x = x xor (x ushr 31)
            return (x ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }

    /** Start mask white, the melted area grey, the foam walls red. */
    private fun mask(src: Pixels, start: BooleanArray, area: BooleanArray, gw: Int, n: Int, bubbles: List<Bubble>): Pixels {
        val w = src.width
        val h = src.height
        val grid = grid(bubbles, w, h)
        val cw = (w + CELL - 1) / CELL
        val owners = IntArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) owners[y * w + x] = owner(bubbles, grid[(y / CELL) * cw + x / CELL], x + 0.5, y + 0.5)
        }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val o = owners[i]
                val wall = (x + 1 < w && owners[i + 1] != o) || (y + 1 < h && owners[i + w] != o)
                val cell = min(y / n, area.size / gw - 1) * gw + min(x / n, gw - 1)
                out.data[i] = when {
                    wall -> 0xFFFF0033.toInt()
                    start[cell] -> -1
                    area[cell] -> 0xFF606060.toInt()
                    else -> 0xFF000000.toInt()
                }
            }
        }
        return out
    }
}
