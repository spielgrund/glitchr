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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Differential growth: small rings (seeds) on the threshold range become closed curves
 * that grow. Every point is pulled towards the middle of its two neighbours (smooth), and
 * pushed away from all points nearby (also of other curves); edges that get too long are
 * split and some are split at random – the curve gets longer than its space and folds
 * into lobes, the curves press against each other and are held on the area.
 *
 * The grown shapes are drawn as inflated, lit lobes (a dome over the distance to the
 * curve), with metal in the creases (a ramp, copper by default); the surface shows the
 * picture carried along (like the horns: every point keeps where its lineage started),
 * the picture itself or a color. The rims are anti-aliased by the exact distance to the
 * curve.
 *
 * All sizes are bounded (a fixed maximum of points, fixed arrays), so it can't run away.
 */
object DiffGrowth : Effect("diffgrowth", "Differential Growth", "Kurven wachsen, falten sich zu Wülsten und drücken sich aneinander – wie Hirnwindungen oder Pilze") {
    private const val HARD_MAX_NODES = 40000

    private val COPPER = ColorRamp(
        listOf(
            ColorStop(0.0, 0x1A0703), ColorStop(0.3, 0x6B2A0C), ColorStop(0.55, 0xC0622A), ColorStop(0.8, 0xF2A65A), ColorStop(1.0, 0xFFE2B0),
        ),
    ).format()

    override val params = listOf(
        Param.Heading("startHeading", "Startmaske"),
        Param.Choice("source", "Wert", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP),
        Param.Slider("lower", "Untere Schwelle", 0, 255, 140, tip = "Beim Farbton darf sie über der oberen liegen (Bereich über Rot hinweg)"),
        Param.Slider("upper", "Obere Schwelle", 0, 255, 255),
        Param.Toggle("invert", "Bereich umkehren", false),
        Param.Slider("merge", "Verschmelzen", 0, 300, 60, " px", "Lücken der Startmaske bis zu dieser Weite schliessen sich zu einer Fläche", canvasMax = true),
        Param.Toggle("showMask", "Maske und Kurven zeigen", false, "Startmaske weiss, Fläche grau, Kurven rot"),
        Param.Heading("growHeading", "Wachstum"),
        Param.Slider("seeds", "Keime", 1, 200, 14, tip = "So viele Ringe beginnen auf der Fläche zu wachsen"),
        Param.Slider("seedSize", "Keimgrösse", 4, 300, 14, " px", "Radius der Ringe am Anfang"),
        Param.Slider("steps", "Schritte", 1, 2000, 300, tip = "Wie lange es wächst"),
        Param.Slider("fold", "Faltengrösse", 4, 300, 24, " px", "Wie weit sich die Kurven wegdrücken – bestimmt die Grösse der Wülste", canvasMax = true),
        Param.Slider("growth", "Wachstum", 0, 100, 30, " %", "Zusätzlich zufällig eingefügte Punkte – unruhigere, krausere Falten"),
        Param.Slider("repulsion", "Abstossung", 0, 200, 100, " %", "Wie stark sich Punkte wegdrücken – mehr: grössere, rundere Wülste"),
        Param.Slider("smooth", "Glätte", 0, 100, 45, " %", "Wie stark jeder Punkt zur Mitte seiner Nachbarn gezogen wird"),
        Param.Slider("confine", "An Fläche halten", 0, 100, 100, " %", "100 %: die Kurven bleiben in der Fläche, weniger: sie quellen über den Rand"),
        Param.Slider("maxNodes", "Max. Punkte", 500, HARD_MAX_NODES, 12000, tip = "Obergrenze – danach wächst nichts mehr (hält die Rechenzeit im Rahmen)"),
        Param.Heading("lookHeading", "Darstellung"),
        Param.Choice(
            "content", "Oberfläche", listOf("Bild mitgezogen", "Bild", "Farbe"),
            tip = "Bild mitgezogen: jede Stelle zeigt das Bild vom Ursprung ihres Kurvenstücks – wie bei den Hörnern wird es mit hinausgezogen",
        ),
        Param.Color("color", "Farbe", 0xE6E1D6),
        Param.Slider(
            "inflate", "Aufblähen", 0, 100, 60, " %",
            "Die Wülste quellen um so viel der Faltengrösse auf, bis sie aneinanderstossen – dazwischen bleiben nur Fugen",
        ),
        Param.Slider("lobe", "Wölbungsradius", 2, 300, 20, " px", "Wie weit von der Kurve nach innen die Wülste ansteigen"),
        Param.Slider("crease", "Fugen", 0, 100, 70, " %", "Metall in den Fugen zwischen den Wülsten"),
        Param.Ramp("creaseRamp", "Fugenverlauf", COPPER, "Die Farben des Metalls – dunkel in der Tiefe, hell im Glanz"),
        Param.Slider("creaseWidth", "Fugenbreite", 1, 60, 9, " px"),
        Param.Choice("background", "Aussen", listOf("Bild", "Schwarz", "Transparent")),
        Param.Heading("lightHeading", "Licht"),
        Param.Slider("lightAngle", "Lichtrichtung", 0, 359, 225, "°"),
        Param.Slider("lightHeight", "Lichthöhe", 5, 90, 45, "°"),
        Param.Slider("shading", "Schattierung", 0, 100, 60, " %"),
        Param.Slider("gloss", "Glanz", 0, 100, 45, " %"),
        Param.Slider("glossSize", "Glanzgrösse", 1, 100, 35, " %"),
        Param.Slider("amount", "Stärke", 0, 100, 100, " %"),
    )

    /** The curves: points with position and origin, linked into closed loops. */
    private class Curves(val capacity: Int) {
        var count = 0
        val x = DoubleArray(capacity)
        val y = DoubleArray(capacity)
        val ox = DoubleArray(capacity)
        val oy = DoubleArray(capacity)
        val next = IntArray(capacity)
        val prev = IntArray(capacity)

        fun add(px: Double, py: Double, qx: Double, qy: Double): Int {
            val i = count++
            x[i] = px; y[i] = py; ox[i] = qx; oy[i] = qy
            return i
        }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val mode = v["source"]
        val lower = v["lower"]
        val upper = v["upper"]
        val invert = v.bool("invert")
        val start = BooleanArray(w * h) { val c = src.data[it]; alpha(c) > 0 && thresholdSelects(c, mode, lower, upper, invert) }
        if (start.none { it }) return src
        // the area: the start mask with its gaps closed; its softened version points inwards
        val r = max(1, (v["merge"] / 2.0).roundToInt())
        val soft = Grow.blur(FloatArray(w * h) { if (start[it]) 1f else 0f }, w, h, r)
        val area = BooleanArray(w * h) { start[it] || soft[it] > 0.25f }
        val curves = grow(area, soft, w, h, v, seed)
        if (v.bool("showMask")) return mask(start, area, curves, w, h)
        if (curves.count == 0) return src
        return render(src, curves, area, v, seed)
    }

    /** Seeds rings on the area and lets them grow. */
    private fun grow(area: BooleanArray, soft: FloatArray, w: Int, h: Int, v: Values, seed: Long): Curves {
        val radius = v["fold"].toDouble()
        // points along the curve: a quarter of the fold size apart
        val spacing = max(1.5, radius / 4)
        val capacity = min(HARD_MAX_NODES, max(16, v["maxNodes"]))
        val c = Curves(capacity)
        val rnd = java.util.Random(seed * 5 + 1)
        val cells = area.indices.filter { area[it] }
        if (cells.isEmpty()) return c
        val seedSize = v["seedSize"].toDouble()
        // how far each pixel lies inside the area: a seed shrinks to fit where it is narrow
        val depth = distanceTransform(BooleanArray(w * h) { !area[it] }, w, h).first
        val centres = ArrayList<Triple<Double, Double, Double>>()
        repeat(v["seeds"]) {
            for (attempt in 0 until 30) {
                val cell = cells[rnd.nextInt(cells.size)]
                val cx = cell % w + 0.5
                val cy = cell / w + 0.5
                val size = min(seedSize, depth[cell] - 1.5)
                if (size < 2) continue
                if (centres.any { hypot(it.first - cx, it.second - cy) < (it.third + size) * 1.1 }) continue
                val points = max(8, (2 * Math.PI * size / spacing).roundToInt())
                // the whole ring has to lie on the area
                val fits = (0 until points).all { k ->
                    val a = 2 * Math.PI * k / points
                    val px = (cx + cos(a) * size).toInt()
                    val py = (cy + sin(a) * size).toInt()
                    px in 0 until w && py in 0 until h && area[py * w + px]
                }
                if (!fits) continue
                if (c.count + points > capacity) return@repeat
                centres += Triple(cx, cy, size)
                val first = c.count
                for (k in 0 until points) {
                    val a = 2 * Math.PI * k / points
                    val px = cx + cos(a) * size
                    val py = cy + sin(a) * size
                    c.add(px, py, px, py)
                }
                for (k in 0 until points) {
                    val i = first + k
                    c.next[i] = first + (k + 1) % points
                    c.prev[i] = first + (k + points - 1) % points
                }
                break
            }
        }
        if (c.count == 0) return c

        val steps = v["steps"]
        val growth = v["growth"] / 100.0 * 0.004
        val repulsion = v["repulsion"] / 100.0
        val smooth = v["smooth"] / 100.0 * 0.5
        val confine = v["confine"] / 100.0
        val maxEdge = spacing * 1.5
        val maxMove = spacing * 0.5
        // spatial hash with cells of the repulsion radius
        val gcw = max(1, (w / radius).toInt() + 1)
        val gch = max(1, (h / radius).toInt() + 1)
        val head = IntArray(gcw * gch)
        val link = IntArray(capacity)
        val nx = DoubleArray(capacity)
        val ny = DoubleArray(capacity)
        // how many points crowd around each one; where it is full, the curve stops growing
        val crowd = IntArray(capacity)
        val crowdLimit = (2 * radius / spacing * 1.6).roundToInt()
        for (step in 0 until steps) {
            val n = c.count
            head.fill(-1)
            for (i in 0 until n) {
                val g = cellOf(c.x[i], c.y[i], radius, gcw, gch)
                link[i] = head[g]
                head[g] = i
            }
            parallelRows(n) { i ->
                val px = c.x[i]
                val py = c.y[i]
                // pulled to the middle of its neighbours
                val p = c.prev[i]
                val q = c.next[i]
                var fx = ((c.x[p] + c.x[q]) / 2 - px) * smooth
                var fy = ((c.y[p] + c.y[q]) / 2 - py) * smooth
                // pushed away from every point nearby
                val gx = (px / radius).toInt().coerceIn(0, gcw - 1)
                val gy = (py / radius).toInt().coerceIn(0, gch - 1)
                var near = 0
                for (yy in max(0, gy - 1)..min(gch - 1, gy + 1)) for (xx in max(0, gx - 1)..min(gcw - 1, gx + 1)) {
                    var j = head[yy * gcw + xx]
                    while (j >= 0) {
                        if (j != i) {
                            val dx = px - c.x[j]
                            val dy = py - c.y[j]
                            val d = hypot(dx, dy)
                            if (d < radius && d > 1e-6) {
                                near++
                                val f = (1 - d / radius) * repulsion * spacing * 0.6 / d
                                fx += dx * f
                                fy += dy * f
                            }
                        }
                        j = link[j]
                    }
                }
                crowd[i] = near
                val fl = hypot(fx, fy)
                if (fl > maxMove) { fx *= maxMove / fl; fy *= maxMove / fl }
                var tx = (px + fx).coerceIn(0.0, w - 0.01)
                var ty = (py + fy).coerceIn(0.0, h - 0.01)
                // held on the area: a move out of it is damped (at 100 % refused)
                val here = py.toInt().coerceIn(0, h - 1) * w + px.toInt().coerceIn(0, w - 1)
                val there = ty.toInt() * w + tx.toInt()
                // outside it only moves back towards the area (up the softened mask)
                if (confine > 0 && !area[there] && (area[here] || soft[there] <= soft[here])) {
                    tx = px + (tx - px) * (1 - confine)
                    ty = py + (ty - py) * (1 - confine)
                }
                nx[i] = tx
                ny[i] = ty
            }
            System.arraycopy(nx, 0, c.x, 0, n)
            System.arraycopy(ny, 0, c.y, 0, n)
            // split long edges, and some at random
            for (i in 0 until n) {
                if (c.count >= capacity) break
                val j = c.next[i]
                val len = hypot(c.x[j] - c.x[i], c.y[j] - c.y[i])
                if (crowd[i] > crowdLimit && crowd[j] > crowdLimit) continue
                if (len > maxEdge || (len > spacing * 0.5 && rnd.nextDouble() < growth)) {
                    val k = c.add((c.x[i] + c.x[j]) / 2, (c.y[i] + c.y[j]) / 2, (c.ox[i] + c.ox[j]) / 2, (c.oy[i] + c.oy[j]) / 2)
                    c.next[i] = k; c.prev[k] = i
                    c.next[k] = j; c.prev[j] = k
                }
            }
        }
        return c
    }

    private fun cellOf(x: Double, y: Double, size: Double, gw: Int, gh: Int) =
        (y / size).toInt().coerceIn(0, gh - 1) * gw + (x / size).toInt().coerceIn(0, gw - 1)

    /** Inside of the curves (non-zero winding, all rings run the same way), by scanlines. */
    private fun inside(c: Curves, w: Int, h: Int): BooleanArray {
        val rows = Array(h) { ArrayList<Double>() }
        val dirs = Array(h) { ArrayList<Int>() }
        for (i in 0 until c.count) {
            val j = c.next[i]
            val y0 = c.y[i]
            val y1 = c.y[j]
            if (y0 == y1) continue
            val lo = min(y0, y1)
            val hi = max(y0, y1)
            var row = kotlin.math.ceil(lo - 0.5).toInt().coerceAtLeast(0)
            while (row < h && row + 0.5 < hi) {
                val sy = row + 0.5
                if (sy >= lo) {
                    val t = (sy - y0) / (y1 - y0)
                    rows[row] += c.x[i] + (c.x[j] - c.x[i]) * t
                    dirs[row] += if (y1 > y0) 1 else -1
                }
                row++
            }
        }
        val out = BooleanArray(w * h)
        parallelRows(h) { y ->
            val xs = rows[y]
            if (xs.isEmpty()) return@parallelRows
            val order = xs.indices.sortedBy { xs[it] }
            var winding = 0
            for ((k, idx) in order.withIndex()) {
                winding += dirs[y][idx]
                if (winding != 0 && k + 1 < order.size) {
                    val from = kotlin.math.ceil(xs[idx] - 0.5).toInt().coerceAtLeast(0)
                    val to = kotlin.math.floor(xs[order[k + 1]] - 0.5).toInt().coerceAtMost(w - 1)
                    for (x in from..to) out[y * w + x] = true
                }
            }
        }
        return out
    }

    /** Exact distance from (px, py) to the curve near point [k] (its two edges). */
    private fun edgeDistance(c: Curves, k: Int, px: Double, py: Double): Double {
        fun seg(a: Int, b: Int): Double {
            val ex = c.x[b] - c.x[a]
            val ey = c.y[b] - c.y[a]
            val l2 = ex * ex + ey * ey
            val t = if (l2 < 1e-12) 0.0 else (((px - c.x[a]) * ex + (py - c.y[a]) * ey) / l2).coerceIn(0.0, 1.0)
            return hypot(px - c.x[a] - ex * t, py - c.y[a] - ey * t)
        }
        return min(seg(c.prev[k], k), seg(k, c.next[k]))
    }

    private fun render(src: Pixels, c: Curves, area: BooleanArray, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val inside = inside(c, w, h)
        // inflated: the shape reaches this far beyond the curve
        val inflate = v["inflate"] / 100.0 * v["fold"] / 2
        // every pixel's nearest curve point: the points laid on the grid, then a distance transform
        val owner = IntArray(w * h) { -1 }
        for (i in 0 until c.count) {
            val ix = c.x[i].toInt().coerceIn(0, w - 1)
            val iy = c.y[i].toInt().coerceIn(0, h - 1)
            owner[iy * w + ix] = i
        }
        val (_, nearest) = distanceTransform(BooleanArray(w * h) { owner[it] >= 0 }, w, h)
        // signed distance to the curve (exact near it): positive inside
        val dist = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val np = nearest[i]
                val k = if (np >= 0) owner[np] else -1
                val d = if (k >= 0) edgeDistance(c, k, x + 0.5, y + 0.5) else 1e6
                dist[i] = ((if (inside[i]) d else -d) + inflate).toFloat()
            }
        }
        // carried picture: each point's shift back to where its lineage started, smoothed between the points
        val content = v["content"]
        var shiftX = FloatArray(0)
        var shiftY = FloatArray(0)
        if (content == 0) {
            shiftX = FloatArray(w * h)
            shiftY = FloatArray(w * h)
            for (i in 0 until w * h) {
                val np = nearest[i]
                val k = if (np >= 0) owner[np] else continue
                shiftX[i] = (c.ox[k] - c.x[k]).toFloat()
                shiftY[i] = (c.oy[k] - c.y[k]).toFloat()
            }
            val r = max(2, (v["fold"] / 3.0).roundToInt())
            shiftX = Grow.blur(shiftX, w, h, r)
            shiftY = Grow.blur(shiftY, w, h, r)
        }
        val lobe = v["lobe"].toDouble()
        // height: a dome over the distance inside, a little smoothed for the normals
        val height = FloatArray(w * h) { val d = dist[it]; if (d <= 0f) 0f else dome(d / lobe).toFloat() * lobe.toFloat() }
        val smooth = Grow.blur(height, w, h, 1)

        val color = v["color"]
        val crease = v["crease"] / 100.0
        val creaseWidth = v["creaseWidth"].toDouble()
        val fold = v["fold"].toDouble()
        val ramp = ColorRamp.parse(v.text("creaseRamp").ifBlank { COPPER })
        val background = v["background"]
        val amount = v["amount"] / 100.0
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
        val noise = Noise(seed * 3 + 9)

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                val ground = when (background) { 1 -> 0xFF000000.toInt(); 2 -> 0; else -> base }
                val d = dist[i]
                // coverage: anti-aliased by the exact distance; the crease band also reaches outside
                val cover = (d + 0.5f).coerceIn(0f, 1f).toDouble()
                val band = when {
                    crease <= 0 -> 0.0
                    // the gaps between the lobes, within the area, are all metal
                    d < 0 && area[i] && -d < fold -> 1.0
                    else -> (1 - kotlin.math.abs(d) / creaseWidth).coerceIn(0.0, 1.0)
                }
                if (cover <= 0 && band <= 0) {
                    out.data[i] = ground
                    continue
                }
                // the normal from the height's slope
                val gx = (smooth[y * w + min(w - 1, x + 1)] - smooth[y * w + max(0, x - 1)]) / 2.0
                val gy = (smooth[min(h - 1, y + 1) * w + x] - smooth[max(0, y - 1) * w + x]) / 2.0
                val nl = sqrt(gx * gx + gy * gy + 1)
                val nx = -gx / nl
                val ny = -gy / nl
                val nz = 1 / nl
                val lambert = max(0.0, nx * lx + ny * ly + nz * lz) / lz
                val diffuse = 1 + shading * (min(1.3, lambert) - 1)
                val spec = gloss * max(0.0, nx * hx + ny * hy + nz * hz).pow(shininess)
                // the surface
                val surface = when (content) {
                    2 -> color or 0xFF000000.toInt()
                    1 -> base
                    else -> sampleBilinear(src, x + 0.5 + shiftX[i], y + 0.5 + shiftY[i], Edge.CLAMP)
                }
                var sr = red(surface) * diffuse / 255.0 + spec
                var sg = green(surface) * diffuse / 255.0 + spec
                var sb = blue(surface) * diffuse / 255.0 + spec
                // metal in the creases: the ramp by how the light catches it, with some ripple
                if (band > 0) {
                    val catchLight = (0.25 + 0.55 * min(1.0, lambert) + 0.35 * noise.fbm(x / 9.0, y / 9.0, 2) + spec).coerceIn(0.0, 1.0)
                    val m = ramp.lut[(catchLight * 255).roundToInt().coerceIn(0, 255)]
                    val a = crease * band * band * (3 - 2 * band)
                    sr += (red(m) / 255.0 - sr) * a
                    sg += (green(m) / 255.0 - sg) * a
                    sb += (blue(m) / 255.0 - sb) * a
                }
                val ca = max(cover, if (crease > 0) crease * band else 0.0) * amount
                fun ch(value: Double, g: Int) = (g + (value * 255 - g) * ca).roundToInt().coerceIn(0, 255)
                out.data[i] = argb(
                    max(alpha(ground), (255 * ca).roundToInt()).coerceIn(0, 255),
                    ch(sr, red(ground)), ch(sg, green(ground)), ch(sb, blue(ground)),
                )
            }
        }
        return out
    }

    /** A rounded profile: rises steeply at the rim and flattens towards [t] = 1. */
    private fun dome(t: Double): Double {
        val u = min(1.0, t)
        return sqrt(1 - (1 - u) * (1 - u))
    }

    /** Start mask white, area grey, curves red. */
    private fun mask(start: BooleanArray, area: BooleanArray, c: Curves, w: Int, h: Int): Pixels {
        val out = Pixels(w, h, IntArray(w * h) { if (start[it]) -1 else if (area[it]) 0xFF606060.toInt() else 0xFF000000.toInt() })
        for (i in 0 until c.count) {
            val j = c.next[i]
            val steps = max(1, (hypot(c.x[j] - c.x[i], c.y[j] - c.y[i]) * 2).toInt())
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val x = (c.x[i] + (c.x[j] - c.x[i]) * t).toInt()
                val y = (c.y[i] + (c.y[j] - c.y[i]) * t).toInt()
                if (x in 0 until w && y in 0 until h) out.data[y * w + x] = 0xFFFF0033.toInt()
            }
        }
        return out
    }
}
