package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Surf: the picture is simplified into areas (blobs, as in "Particles"), and from every
 * area waves run out in one direction like surf breaking on rocks – lines that follow the
 * area's outline, only on the side facing the direction, stretched along it and further
 * apart the further out they are. Spray (little foam clouds) gathers along the crests and
 * travels with its wave. Very big
 * areas (usually the background) send no waves, so the waves run over them.
 */
object Surf : Effect("surf", "Surf", "Waves and spray run out from every area of the picture in one direction, like surf") {
    private val bases = listOf("Picture", "Blob colors", "Color")
    private val colorModes = listOf("Blob color", "Picture color at the edge", "White", "Custom")
    private val styles = listOf("Lines", "Bands")

    override val params = listOf(
        Param.Slider("areas", "Areas", 0, 100, 30, " %", "How coarsely the picture is split into areas; lower keeps pale shapes apart from their background"),
        Param.Slider("minArea", "Smallest area", 0, 100, 5, " %", "Smaller areas (specks, thin seams along edges) send no waves", decimals = 1),
        Param.Slider("maxArea", "Largest area", 1, 100, 15, " %", "Areas covering more than this share of the picture (usually the background) send no waves; 100 % = all"),
        Param.Choice("base", "Underneath", bases, 0, "What the waves are drawn on: the picture, its areas in their average colors, or one color"),
        Param.Color("baseColor", "Underneath color", 0xFFFFFF),

        Param.Heading("wavesHeading", "Waves"),
        Param.Slider("direction", "Direction", 0, 35900, 9000, "°", "Where the surf runs to; 90° = down", decimals = 2),
        Param.Slider("spread", "Spread", 10, 360, 200, "°", "How far around the area the waves reach; 180° = the half facing the direction, 360° = all around"),
        Param.Slider("stretch", "Stretch", 100, 600, 180, " %", "Waves reach further in the direction than to the sides"),
        Param.Slider("count", "Count", 1, 60, 4, tip = "Waves per area"),
        Param.Slider("spacing", "Spacing", 2, 500, 28, " px", "Distance of the first wave from the area"),
        Param.Slider("growth", "Widening", 0, 100, 15, " %", "Every wave lies this much further from the one before"),
        Param.Slider("phase", "Phase", -360000, 360000, 0, "°", "Moves the waves outwards; 360° = one wave further, which looks the same again – animate it to let the surf roll", decimals = 2),
        Param.Choice("style", "Style", styles, 0, "Thin lines, or alternating filled bands"),
        Param.Slider("width", "Line width", 1, 400, 30, " px", "Width of the lines", decimals = 1),
        Param.Slider("fade", "Fade", 0, 100, 60, " %", "Outer waves become weaker"),
        Param.Slider("wobble", "Wobble", 0, 200, 25, " px", "Waves bend like drawn by hand"),
        Param.Slider("wobbleSize", "Wobble size", 5, 1000, 120, " px"),
        Param.Toggle("outline", "Outline", true, "A line all around every area that sends waves"),

        Param.Heading("colorHeading", "Color"),
        Param.Choice("colorMode", "Wave color", colorModes),
        Param.Color("color", "Custom color", 0x9AF5C0),
        Param.Slider("colorMix", "Mix in custom color", 0, 100, 0, " %", "Blends the custom color into the wave color"),
        Param.Slider("strength", "Strength", 0, 100, 100, " %"),

        Param.Heading("sprayHeading", "Spray"),
        Param.Slider("spray", "Spray", 0, 100, 45, " %", "Little foam clouds along the crests; they travel with their wave"),
        Param.Slider("sprayReach", "Spray reach", 1, 300, 35, " px", "How far the spray flies off the crest, outwards"),
        Param.Slider("spraySize", "Cloud size", 1, 200, 12, " px", "Size of the foam clouds"),
        Param.Slider("spraySoft", "Cloud softness", 1, 100, 35, " %", "Soft, misty clouds or crisp ones"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val blobs = Particles.blobify(src, v["areas"] / 100.0, seed)
        val label = blobs.label
        val maxArea = if (v["maxArea"] >= 100) Long.MAX_VALUE else n.toLong() * v["maxArea"] / 100
        val minArea = n.toLong() * v["minArea"] / 1000
        val emits = BooleanArray(blobs.area.size) { blobs.area[it] in minArea..maxArea }

        val dir = Math.toRadians(v["direction"] / 100.0)
        val ux = cos(dir)
        val uy = sin(dir)
        val stretch = v["stretch"] / 100.0
        val nearest = nearestSeed(w, h, ux, uy, stretch) { i -> label[i] >= 0 && emits[label[i]] }

        val base = when (v["base"]) {
            1 -> blobs.image
            2 -> Pixels(w, h).apply { data.fill(v["baseColor"] or 0xFF000000.toInt()) }
            else -> src
        }
        val spacing = v["spacing"].toDouble()
        val growth = v["growth"] / 100.0
        val count = v["count"]
        val phase = v["phase"] / 36000.0
        val whole = floor(phase).toLong()
        val ph = phase - whole
        val bands = v["style"] == 1
        val halfWidth = v["width"] / 20.0
        val fade = v["fade"] / 100.0
        val wobble = v["wobble"].toDouble()
        val wobbleScale = 1.0 / v["wobbleSize"]
        val outline = v.bool("outline")
        val cosSpread = cos(Math.toRadians(v["spread"] / 2.0))
        val colorMode = v["colorMode"]
        val custom = v["color"] or 0xFF000000.toInt()
        val colorMix = v["colorMix"] / 100f
        val strength = v["strength"] / 100f
        val spray = v["spray"] / 100.0
        val sprayReach = v["sprayReach"].toDouble()
        val cloudSize = v["spraySize"].toDouble()
        val cloudSoft = v["spraySoft"] / 100.0
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val sprayNoise = Noise(seed + 77)

        /**
         * Foam at the point ([px], [py]) of the moving frame: little round clouds with frayed
         * edges, one maybe in every cell of a grid; their size and number grow with [size] (0..1),
         * so they are big and dense at the crest and become smaller and rarer further out.
         */
        fun puffs(px: Double, py: Double, size: Double): Double {
            if (size < 0.02) return 0.0
            val cell = cloudSize * 1.6
            val gi = floor(px / cell).toInt()
            val gj = floor(py / cell).toInt()
            // frayed edges: the distance is bent by noise
            val fray = sprayNoise.fbm(px / cloudSize * 1.3, py / cloudSize * 1.3, 3) * 0.45
            var best = 0.0
            for (j in gj - 1..gj + 1) for (i in gi - 1..gi + 1) {
                val h = hash(i, j, s)
                // fewer further out, too
                if ((h and 0xFF) / 255.0 > min(0.75, size * size * 1.5)) continue
                val cx = (i + ((h ushr 8) and 0xFF) / 255.0) * cell
                val cy = (j + ((h ushr 16) and 0xFF) / 255.0) * cell
                val r = cloudSize * (0.45 + 0.75 * ((h ushr 24) and 0x7F) / 127.0) * size
                val dist = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)) / r + fray
                val c = ((1 - dist) / cloudSoft + 0.5).coerceIn(0.0, 1.0)
                if (c > best) best = c
            }
            return best * best * (3 - 2 * best)
        }
        val noise = Noise(seed)

        /** Wave coordinate of a distance: wave k lies at k; with widening each gap is (1 + growth) times the last. */
        fun waveAt(d: Double) = if (growth < 1e-6) d / spacing else ln(1 + growth * d / spacing) / ln(1 + growth)

        /** Pixels per wave unit at distance [d]. */
        fun gapAt(d: Double) = if (growth < 1e-6) spacing else spacing * ln(1 + growth) * (1 + growth * d / spacing) / growth

        val out = base.copy()
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val q = nearest[i]
                if (q < 0) continue
                val sx = (q % w).toDouble()
                val sy = (q / w).toDouble()
                val dx = x - sx
                val dy = y - sy
                val along = dx * ux + dy * uy
                val across = -dx * uy + dy * ux
                val plain = sqrt(dx * dx + dy * dy)
                if (plain < 0.5) continue
                // stretched distance: the waves reach further along the direction
                val a = if (along > 0) along / stretch else along
                var d = sqrt(a * a + across * across)
                if (wobble > 0) d += noise.fbm(x * wobbleScale, y * wobbleScale, 3) * wobble * min(1.0, d / spacing)
                if (d <= 0) continue
                // only the side facing the direction, fading out softly at the ends
                val facing = along / plain
                val reach = ((facing - cosSpread) / 0.25 + 0.5).coerceIn(0.0, 1.0)

                // wave k lies at wave position k + ph; the phase moves them out, and since a wave
                // fades in at the area and out beyond the last one, a whole step loops seamlessly
                val t = waveAt(d) - ph
                val gap = gapAt(d)
                fun weight(p: Double) = (min(1.0, p) * min(1.0, count + 1 - p) * (1 - fade * (p - 1).coerceAtLeast(0.0) / count))
                    .coerceIn(0.0, 1.0) * reach
                var cover = 0.0
                if (reach > 0) {
                    if (bands) {
                        val k = floor(t)
                        // every second gap filled; counted with the whole phase steps, so the pattern doesn't flip
                        if (k >= 0 && (k.toLong() + whole) % 2 == 0L) {
                            val f = t - k
                            cover = (min(f, 1 - f) * gap + 0.5).coerceIn(0.0, 1.0) * weight(k + ph + 0.5)
                        }
                    } else {
                        val k = Math.round(t).toDouble()
                        if (k >= 0) cover = (halfWidth - abs(t - k) * gap + 0.5).coerceIn(0.0, 1.0) * weight(k + ph)
                    }
                }
                // the outline: a line just outside the area, all around
                if (outline) cover = max(cover, (halfWidth - abs(plain - halfWidth) + 0.5).coerceIn(0.0, 1.0))

                // spray: little clouds off the crests, mostly outwards, thinning with distance. They are
                // read in the frame of their wave (moved back by how far it has run out), so they
                // travel with it, and after a whole wave the picture is the same again
                if (spray > 0 && reach > 0) {
                    val k = floor(t)
                    val past = (t - k) * gap - halfWidth
                    val wk = if (k >= 0) weight(k + ph) else 0.0
                    if (wk > 0 && past > -halfWidth) {
                        val chance = spray * exp(-max(0.0, past) / sprayReach) * wk
                        // moved back along the surf direction by how far the wave has run (in front it
                        // runs stretched); along the outline instead, neighbours that share their nearest
                        // edge point would smear the clouds into rays
                        val run = (k + ph) * gap * stretch
                        cover = max(cover, puffs(x - ux * run, y - uy * run, sqrt(chance)))
                    }
                }
                if (cover <= 0) continue
                val color = when (colorMode) {
                    1 -> src.data[q]
                    2 -> -1
                    3 -> custom
                    else -> blobs.image.data[q]
                }.let { if (colorMix > 0) lerpArgb(it or 0xFF000000.toInt(), custom, colorMix) else it } or 0xFF000000.toInt()
                out.data[i] = lerpArgb(out.data[i], color, (cover * strength).toFloat())
            }
        }
        return out
    }

    /**
     * For every pixel the nearest pixel for which [isSeed] holds (itself, if it is one),
     * measured with the distance stretched by [stretch] along ([ux], [uy]) in front;
     * -1 without any. Jump flooding: rough steps first, then finer, plus a last pass of 1.
     */
    private fun nearestSeed(w: Int, h: Int, ux: Double, uy: Double, stretch: Double, isSeed: (Int) -> Boolean): IntArray {
        var cur = IntArray(w * h) { if (isSeed(it)) it else -1 }
        var next = IntArray(w * h)
        fun metric(x: Int, y: Int, q: Int): Double {
            val dx = (x - q % w).toDouble()
            val dy = (y - q / w).toDouble()
            var a = dx * ux + dy * uy
            if (a > 0) a /= stretch
            val c = -dx * uy + dy * ux
            return a * a + c * c
        }
        var step = Integer.highestOneBit(max(w, h))
        val steps = buildList {
            while (step >= 1) { add(step); step /= 2 }
            add(1)
        }
        for (st in steps) {
            val from = cur
            val to = next
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    var best = from[y * w + x]
                    var bestD = if (best < 0) Double.MAX_VALUE else metric(x, y, best)
                    for (oy in -1..1) {
                        val yy = y + oy * st
                        if (yy < 0 || yy >= h) continue
                        for (ox in -1..1) {
                            val xx = x + ox * st
                            if (xx < 0 || xx >= w) continue
                            val c = from[yy * w + xx]
                            if (c < 0 || c == best) continue
                            val d = metric(x, y, c)
                            if (d < bestD) { bestD = d; best = c }
                        }
                    }
                    to[y * w + x] = best
                }
            }
            cur = to
            next = from
        }
        return cur
    }

    private fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }
}
