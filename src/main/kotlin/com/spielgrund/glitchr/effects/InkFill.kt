package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A reveal: the picture starts transparent and blots spreading from points uncover it
 * (progress 0 → 100 %) – the whole area they have reached, or only a band of a set width
 * travelling outwards with their front. The front runs faster through the opaque, bright, dark
 * or colorful areas as set, blotchy like ink soaking into paper. Its edge can look like ink
 * (a dark rim bleeding into the picture), fire (a glowing, burning edge), lightning (arcs
 * crackling along it) or ice (a frosty rim); the uncovered picture can also be tinted that way.
 */
object InkFill : Effect("inkfill", "Ink fill", "Reveals the picture with blots spreading from points – the whole area or a travelling band, with an ink, fire, lightning or ice edge; animate the progress") {
    private val styles = listOf("Ink", "Fire", "Lightning", "Ice")
    private val drives = listOf("Evenly", "Opaque areas", "Bright areas", "Dark areas", "Colorful areas")
    private val styleColors = intArrayOf(0x0B0C1A, 0xFF7A18, 0x7FB2FF, 0x9FD6FF)
    private val cache = LastResult<FloatArray>()

    override val params = listOf(
        Param.Choice(
            "style", "Edge", styles, 0,
            "Ink: a dark rim bleeding into the picture · Fire: a glowing, burning edge · " +
                "Lightning: arcs crackling along the edge · Ice: a frosty rim",
        ),
        Param.Slider("progress", "Progress", 0, 10000, 5000, " %", "0 %: nothing to see · 100 %: the whole picture uncovered (with a band: passed by) – animate it", decimals = 2),
        Param.Points("points", "Start points", "0.5000,0.5000", "Where the blots start – drag them in the picture"),
        Param.Choice("reveal", "Reveals", listOf("The area", "A band"), 0, "The area: everything the blots have reached shows · A band: only a band along their front, travelling outwards"),
        Param.Slider("bandWidth", "Band width", 100, 50000, 3000, " px", "With a band: how wide it is", decimals = 2, canvasMax = true),
        Param.Choice("drive", "Spreads along", drives, 3, "Where the front runs fastest: everywhere alike, or through the opaque, bright, dark or colorful parts of the picture"),
        Param.Slider("influence", "Influence", 0, 100, 70, " %", "How strongly the picture steers it: at 100 % the other areas are nearly a wall it has to creep around"),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("rough", "Roughness", 0, 100, 50, " %", "How blotchy and ragged the front is"),
        Param.Slider("detail", "Blot size", 200, 30000, 4000, " px", "Size of the blotches", decimals = 2, canvasMax = true),
        Param.Slider("feather", "Feather", 0, 100, 25, " %", "Soft edges"),
        Param.Heading("lookHeading", "Edge"),
        Param.Slider("front", "Edge width", 100, 30000, 2500, " px", "How far into the uncovered picture the ink, fire, lightning or frost reaches", decimals = 2),
        Param.Slider("edge", "Edge strength", 0, 100, 80, " %", "0 %: a plain reveal"),
        Param.Toggle("ownColor", "Own color", false, "Off: the style's color – black-blue ink, orange fire, blue lightning, pale blue ice"),
        Param.Color("color", "Color", 0x0B0C1A, "The ink, the flames, the electricity or the frost"),
        Param.Slider("tint", "Tint", 0, 100, 0, " %", "The whole uncovered picture in the style: inked, burnt, electrified or frozen"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %", "Fire, lightning and ice: light around the edge"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val points = PointList.parse(v.text("points"))
        if (points.isEmpty()) return Pixels(w, h)
        val noise = Noise(seed)
        val style = v["style"]
        val size = min(w, h).toDouble()
        val scale = max(2.0, v["detail"] / 100.0)
        val rough = v["rough"] / 100.0
        val drive = v["drive"]
        val influence = v["influence"] / 100.0

        // when the front reaches every pixel – the slow part, so kept while only the progress or the look changes
        val key = listOf(src.data.contentHashCode(), w, h, drive, influence, v.text("points"), v["detail"], v["rough"], seed)
        val times = cache.get(key) {
            val cost = FloatArray(w * h)
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = src.data[i]
                    val d = when (drive) {
                        1 -> alpha(c) / 255.0
                        2 -> luma(c) / 255.0
                        3 -> 1 - luma(c) / 255.0
                        4 -> saturation(c)
                        else -> 1.0
                    }
                    // the paper's grain: patches it soaks into faster or slower
                    val grain = 1 + rough * 0.9 * noise.fbm(x / scale * 0.7 + 3.1, y / scale * 0.7 - 7.7, 3)
                    val speed = (1 - influence + influence * d).coerceAtLeast(0.03) * grain.coerceAtLeast(0.15)
                    cost[i] = (1 / speed).toFloat()
                }
            }
            val seeds = points.map { p -> (p.y * h).toInt().coerceIn(0, h - 1) * w + (p.x * w).toInt().coerceIn(0, w - 1) }.toIntArray()
            spreadTimesScaled(cost, w, h, seeds)
        }

        // the front's own fine edge: frayed fibres (ink), flickering (fire), forks (lightning), crystal facets (ice)
        val fine = max(1.5, scale * 0.22)
        val amp = (0.15 + rough) * fine * 1.6
        val reach = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val t = times[i]
                if (t.isInfinite()) { reach[i] = t; continue }
                val n = when (style) {
                    2 -> noise.ridged(x / fine, y / fine, 3)
                    3 -> 0.6 * noise.voronoi(x / (fine * 1.4), y / (fine * 1.4)) + 0.4 * noise.fbm(x / fine, y / fine, 2)
                    else -> noise.fbm(x / fine + 11.0, y / fine - 4.0, 4)
                }
                // big lobes, growing with the blot, so it doesn't stay round
                val lobe = noise.fbm(x / (scale * 2.5) - 21.0, y / (scale * 2.5) + 13.0, 3)
                reach[i] = (t * (1 + rough * 1.1 * lobe) + n * amp).toFloat()
            }
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (t in reach) if (t.isFinite()) { lo = min(lo, t); hi = max(hi, t) }
        if (hi < lo) return Pixels(w, h)

        val front = max(1.0, v["front"] / 100.0)
        val feather = 0.7 + v["feather"] / 100.0 * size * 0.03
        val band = v["reveal"] == 1
        val bandWidth = max(1.0, v["bandWidth"] / 100.0)
        // 0 %: the front just before the first pixel · 100 %: past the last one, with the edge (or the band) behind it
        val tail = (if (band) bandWidth + feather else front * 2) + feather * 2 + 2
        val now = lo - feather - 1 + v["progress"] / 10000.0 * (hi - lo + tail)

        val color = if (v.bool("ownColor")) v["color"] else styleColors[style]
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val col = doubleArrayOf(ch(color, 16), ch(color, 8), ch(color, 0))
        val strength = v["edge"] / 100.0
        val tint = v["tint"] / 100.0
        // fire: charred → embers → the flame color → yellow → white
        val fireStops = arrayOf(
            doubleArrayOf(0.07, 0.03, 0.02), doubleArrayOf(0.55, 0.06, 0.01), col,
            doubleArrayOf(1.0, 0.83, 0.3), doubleArrayOf(1.0, 0.97, 0.86),
        )
        fun fire(heat: Double, k: Int): Double {
            val f = heat.coerceIn(0.0, 1.0) * (fireStops.size - 1)
            val i = min(fireStops.size - 2, f.toInt())
            return fireStops[i][k] + (fireStops[i + 1][k] - fireStops[i][k]) * (f - i)
        }

        val emit = FloatArray(w * h)
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val c = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val t = reach[i]
                val age = now - t
                // not reached yet: nothing
                if (t.isInfinite() || age < -feather) continue
                val p = src.data[i]
                // uncovered from the soft front on – with a band only up to its far side
                var show = smoothstep(-feather, feather * 0.15, age)
                if (band) show *= 1 - smoothstep(bandWidth - feather, bandWidth + feather * 0.15, age)
                if (show <= 0) continue
                for (k in 0..2) c[k] = ch(p, 16 - 8 * k)
                val l = luma(p) / 255.0
                // the whole uncovered picture in the style
                if (tint > 0) for (k in 0..2) {
                    val styled = when (style) {
                        0 -> c[k] * (0.25 + 0.75 * col[k])
                        1 -> fire(l * 0.85, k)
                        2 -> if (l < 0.5) col[k] * (0.12 + 1.76 * l) else col[k] + (1 - col[k]) * (l - 0.5) * 1.6
                        else -> c[k] * 0.12 + ((l * 0.6 + 0.4) * col[k] + 0.1) * 0.88
                    }
                    c[k] += (styled - c[k]) * tint
                }
                // the edge's look: strongest at the front, gone further in
                val e = strength * (1 - smoothstep(front, front * 2, age))
                var light = 0.0
                if (e > 0) when (style) {
                    0 -> {
                        // ink bleeding in from the edge, mottled
                        val mottle = noise.fbm(x / (scale * 0.3) + 40.0, y / (scale * 0.3), 3)
                        val rim = (exp(-max(0.0, age) / (front * 0.5)) * (0.85 + 0.3 * mottle)).coerceIn(0.0, 1.0) * e
                        for (k in 0..2) c[k] += (col[k] - c[k]) * rim
                    }
                    1 -> {
                        // a glowing, flickering edge with a charred line behind it
                        val flick = noise.fbm(x / (scale * 0.15), y / (scale * 0.15) + 5.5, 3)
                        val heat = (max(0.0, 1 - max(0.0, age) / (front * 0.6)) * (0.75 + 0.7 * flick)).coerceIn(0.0, 1.0)
                        val char = exp(-((age - front * 0.75) / (front * 0.3)).pow(2)) * e * 0.7
                        for (k in 0..2) {
                            c[k] *= 1 - char
                            c[k] += (fire(heat, k) - c[k]) * min(1.0, heat * 1.4) * e
                        }
                        emit[i] = (heat * e).toFloat()
                    }
                    2 -> {
                        // arcs forking along the edge, and a bright line right at it
                        val warp = noise.fbm(x / (fine * 3), y / (fine * 3), 2) * 2.5
                        val arc = (1 - abs(noise.perlin(x / (fine * 1.3) + warp, y / (fine * 1.3) - warp))).pow(26) +
                            0.6 * (1 - abs(noise.perlin(x / (fine * 0.6) - 3.3, y / (fine * 0.6) + warp))).pow(30)
                        val edgeLine = exp(-(age / (feather + front * 0.08)).pow(2))
                        light = (arc * exp(-max(0.0, age) / front) * 1.6 + edgeLine) * e
                        for (k in 0..2) c[k] = min(1.0, c[k] + (col[k] * 0.5 + 0.5) * light)
                        emit[i] = (light * 0.8).toFloat()
                    }
                    else -> {
                        // a rim of fern-like frost crystals and glints
                        val fern = ((noise.ridged(x / (fine * 0.55), y / (fine * 0.55), 3) + 1) / 2).pow(6)
                        val frost = fern * exp(-max(0.0, age) / (front * 0.7)) + 0.3 * exp(-abs(age) / (front * 0.2))
                        val glint = if (noise.white(x, y) > 0.994) 1.0 else 0.0
                        val white = min(1.0, (frost + glint) * e)
                        for (k in 0..2) c[k] += ((col[k] * 0.4 + 0.6) - c[k]) * white
                        emit[i] = (white * 0.6).toFloat()
                    }
                }
                // the picture's own opacity, uncovered; the lightning's light also shows on the transparent side of the edge
                val oa = max((p ushr 24) / 255.0 * show, min(1.0, light) * smoothstep(-feather, 0.0, age))
                if (oa <= 0.002) continue
                fun c8(k: Int) = (c[k] * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(0) shl 16) or (c8(1) shl 8) or c8(2)
            }
        }

        // light around the edge: fire, lightning, ice
        val glow = v["glow"] / 100.0
        if (style == 0 || glow <= 0) return out
        val halo = Blur.gauss(emit, w, h, max(1.5, front * 0.6))
        val glowColor = if (style == 3) DoubleArray(3) { col[it] * 0.6 + 0.4 } else col
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val light = min(1.0, halo[i] * 1.6) * glow * 0.8
                if (light <= 0.004) continue
                val p = out.data[i]
                val pa = (p ushr 24) / 255.0
                fun add(sh: Int, k: Int) = min(1.0, ch(p, sh) * pa + glowColor[k] * light)
                val r = add(16, 0); val g = add(8, 1); val b = add(0, 2)
                val oa = min(1.0, max(pa, max(r, max(g, b))))
                fun c8(q: Double) = (min(1.0, q / oa) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
            }
        }
        return out
    }

    private fun saturation(c: Int): Double {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return if (mx == 0) 0.0 else (mx - mn) / mx.toDouble()
    }
}
