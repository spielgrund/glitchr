package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Blood on the shapes (from the alpha channel or the dark areas) that runs in a direction
 * (progress 0 → 100 %): first a thin coat on their lower part, which drains and gathers in a
 * thick, wavy bead hanging along the lower edges; then drips break off and run down, each
 * starting at its own time, thinning, wobbling and ending in a drop. Shaded as a glossy liquid.
 * Worked out in a frame turned so the blood runs straight down, then turned back.
 */
object Bleed : Effect("bleed", "Bleed", "Blood gathers on the lower edges of the shapes – from alpha or dark areas – and runs down in drips, in any direction; animate the progress") {
    override val params = listOf(
        Param.Choice(
            "source", "Shapes from", listOf("Alpha (else dark areas)", "Dark areas", "White areas"), 0,
            "What bleeds: the opaque parts (if the picture has no transparency: the dark ones), everything darker than the threshold, or everything brighter",
        ),
        Param.Slider("threshold", "Threshold", 0, 100, 30, " %", "Dark areas: up to this brightness · White areas: from this far below white (30 % = brighter than 70 %)"),
        Param.Slider("progress", "Progress", 0, 10000, 6000, " %", "0 %: no blood · it gathers on the lower edges, then drips run down – animate it", decimals = 2),
        Param.Slider("angle", "Direction", -18000, 18000, 0, "°", "Where the blood runs: 0° = down, 90° = right", decimals = 2),
        Param.Heading("bloodHeading", "Blood"),
        Param.Slider("coat", "Coat", 0, 100, 15, " %", "How far up the shapes a thin coat of blood reaches before it drains to the lower edges"),
        Param.Slider("pool", "Pooling", 0, 100, 50, " %", "How thick the blood gathers along the lower edges"),
        Param.Slider("drips", "Drips", 0, 100, 50, " %", "How many drips run off"),
        Param.Slider("length", "Drip length", 100, 10000, 3500, " %", "The longest drips, of the canvas size", decimals = 2, transform = true),
        Param.Slider("width", "Drip width", 50, 5000, 500, " px", decimals = 2, transform = true),
        Param.Slider("wobble", "Wobble", 0, 100, 25, " %", "How much the drips sway on their way"),
        Param.Slider("dropSize", "Drop size", 20, 400, 100, " %", "How big and thick the drops at the drips' ends are, of the drip width"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Blood", 0x7A0007),
        Param.Slider("gloss", "Gloss", 0, 100, 70, " %"),
        Param.Toggle("onlyBlood", "Only the blood", false, "Only the blood, on a transparent background"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val progress = v["progress"] / 10000.0
        val onlyBlood = v.bool("onlyBlood")
        if (progress <= 0) return if (onlyBlood) Pixels(w, h) else src.copy()
        val noise = Noise(seed)
        val random = Random(seed)
        val size = min(w, h).toDouble()
        val hasAlpha = src.data.any { alpha(it) < 250 }
        val threshold = v["threshold"] / 100.0 * 255
        val byAlpha = v["source"] == 0 && hasAlpha
        val white = v["source"] == 2
        val inShape = if (byAlpha) BooleanArray(w * h) { alpha(src.data[it]) >= 128 } else {
            // dark or white areas smoothed, so specks don't each bleed
            val raw = FloatArray(w * h) {
                val c = src.data[it]
                val bright = luma(c)
                if (alpha(c) >= 128 && (if (white) bright > 255 - threshold else bright < threshold)) 1f else 0f
            }
            val soft = Blur.gauss(raw, w, h, max(0.6, size * 0.005))
            BooleanArray(w * h) { soft[it] >= 0.5f }
        }

        // the turned frame: the blood runs down its columns (v), across them is u
        val a = Math.toRadians(v["angle"] / 100.0)
        val ca = cos(a)
        val sa = sin(a)
        val tw = ceil(w * abs(ca) + h * abs(sa)).toInt() + 2
        val th = ceil(w * abs(sa) + h * abs(ca)).toInt() + 2
        fun toX(u: Double, vv: Double) = w / 2.0 + (u - tw / 2.0) * ca + (vv - th / 2.0) * sa
        fun toY(u: Double, vv: Double) = h / 2.0 - (u - tw / 2.0) * sa + (vv - th / 2.0) * ca
        val mask = BooleanArray(tw * th)
        // where the turned frame lies within the picture: blood doesn't hang off the picture's edge
        val within = BooleanArray(tw * th)
        parallelRows(th) { vv ->
            for (u in 0 until tw) {
                val x = toX(u + 0.5, vv + 0.5).toInt()
                val y = toY(u + 0.5, vv + 0.5).toInt()
                if (x in 0 until w && y in 0 until h) {
                    within[vv * tw + u] = true
                    mask[vv * tw + u] = inShape[y * w + x]
                }
            }
        }

        val dripW = max(1.5, v["width"] / 100.0)
        // the coat drains as the blood gathers below
        val collect = smoothstep(0.0, 0.3, progress)
        val coatLen = v["coat"] / 100.0 * size * 0.4 * smoothstep(0.0, 0.08, progress) * (1 - 0.6 * collect)
        val poolLen = v["pool"] / 100.0 * size * 0.06 * collect
        val height = FloatArray(tw * th)
        fun put(u: Int, vv: Int, value: Double) {
            if (u < 0 || vv < 0 || u >= tw || vv >= th) return
            val i = vv * tw + u
            if (value > height[i]) height[i] = value.toFloat()
        }

        // every run of shape down a column: its coat, and the bead hanging off its lower end
        val edges = ArrayList<IntArray>()
        for (u in 0 until tw) {
            var vv = 0
            while (vv < th) {
                if (!mask[vv * tw + u]) { vv++; continue }
                val top = vv
                while (vv < th && mask[vv * tw + u]) vv++
                val bottom = vv - 1
                // a shape running off the picture's edge: its blood runs off with it
                if (vv >= th || !within[vv * tw + u]) continue
                // reaching only so far up, a little wavy
                val coat = coatLen * (0.75 + 0.5 * noise.fbm(u / (dripW * 6) - 4.1, bottom / (dripW * 6), 2)).coerceAtLeast(0.2)
                if (coat > 0.5) for (y in max(top, (bottom - coat).toInt())..bottom) {
                    put(u, y, 0.16 + 0.3 * (1 - (bottom - y) / coat))
                }
                edges += intArrayOf(u, bottom, bottom - top + 1)
                if (poolLen > 0.5) {
                    // a wavy bead: thicker here, thinner there, half on the shape and half hanging below
                    val t = poolLen * (0.55 + 0.45 * noise.fbm(u / (dripW * 4), bottom / (dripW * 4) + 3.7, 2) * 1.6).coerceIn(0.25, 1.4)
                    val up = min(t * 0.7, (bottom - top + 1).toDouble())
                    for (s in -up.toInt()..ceil(t).toInt()) {
                        val k = if (s < 0) s / max(1.0, up) else s / t
                        if (abs(k) > 1) continue
                        put(u, bottom + s, 0.8 * sqrt(1 - k * k))
                    }
                }
            }
        }

        // drips: spread along the lower edges, each starting at its own time
        val drips = v["drips"] / 100.0
        val maxLength = v["length"] / 10000.0 * size
        val wobble = v["wobble"] / 100.0
        val dropSize = v["dropSize"] / 100.0
        val chosen = ArrayList<IntArray>()
        for (e in edges) {
            // the same random draws whatever the progress, so every drip keeps its place and timing
            val pick = random.nextDouble()
            val r1 = random.nextDouble(); val r2 = random.nextDouble(); val r3 = random.nextDouble()
            if (pick > drips * 0.06 || e[2] < 2) continue
            if (chosen.takeLast(24).any { abs(it[0] - e[0]) < dripW * 2.2 && abs(it[1] - e[1]) < dripW * 4 }) continue
            chosen += e
            val u0 = e[0].toDouble()
            val start = e[1].toDouble()
            val width = dripW * (0.6 + 0.8 * r3)
            val begin = 0.15 + 0.45 * r2
            val grow = ((progress - begin) / (1 - begin)).coerceIn(0.0, 1.0)
            val len = maxLength * (0.15 + 0.85 * r1.pow(1.6)) * (1 - (1 - grow).pow(2.2))
            // the drop swells at the edge before it runs
            val swell = smoothstep(begin - 0.15, begin, progress)
            if (swell <= 0) continue
            fun center(y: Double) = u0 + wobble * width * 1.5 * noise.perlin((y - start) / (width * 6), u0 * 0.37 + 0.5)
            var y = start
            while (y <= start + len) {
                val r = width / 2 * (1 - 0.3 * (y - start) / max(1.0, len))
                val cu = center(y)
                for (u in (cu - r - 1).toInt()..(cu + r + 1).toInt()) {
                    val d = abs(u + 0.5 - cu) / r
                    if (d < 1) put(u, y.toInt(), 0.75 * sqrt(1 - d * d))
                }
                y += 1.0
            }
            // the drop at its end
            val rb = width / 2 * 1.35 * dropSize * swell
            val ey = start + len + rb * 0.4
            val eu = center(start + len)
            for (yy in (ey - rb - 1).toInt()..(ey + rb + 1).toInt()) for (u in (eu - rb - 1).toInt()..(eu + rb + 1).toInt()) {
                val d = kotlin.math.hypot(u + 0.5 - eu, yy + 0.5 - ey) / rb
                if (d < 1) put(u, yy, sqrt(1 - d * d) * (0.7 + 0.3 * min(2.0, dropSize)))
            }
        }

        // back into the picture, smoothed a little for the shading
        val back = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val px = x + 0.5 - w / 2.0
                val py = y + 0.5 - h / 2.0
                val u = tw / 2.0 + px * ca - py * sa - 0.5
                val vv = th / 2.0 + px * sa + py * ca - 0.5
                val u0 = kotlin.math.floor(u).toInt()
                val v0 = kotlin.math.floor(vv).toInt()
                val fu = u - u0
                val fv = vv - v0
                fun at(uu: Int, yy: Int) = if (uu < 0 || yy < 0 || uu >= tw || yy >= th) 0f else height[yy * tw + uu]
                back[y * w + x] = ((at(u0, v0) * (1 - fu) + at(u0 + 1, v0) * fu) * (1 - fv) + (at(u0, v0 + 1) * (1 - fu) + at(u0 + 1, v0 + 1) * fu) * fv).toFloat()
            }
        }
        val smooth = Blur.gauss(back, w, h, 0.8)

        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val color = v["color"]
        val col = doubleArrayOf(ch(color, 16), ch(color, 8), ch(color, 0))
        val gloss = v["gloss"] / 100.0
        val slope = dripW * 0.6
        // light from the top left, and the halfway direction to the eye for the highlights
        val lx = -0.45; val ly = -0.6; val lz = 0.66
        val hl = sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1))
        val hx = lx / hl; val hy = ly / hl; val hz = (lz + 1) / hl
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val p = if (onlyBlood) 0 else src.data[i]
                val depth = back[i].toDouble()
                val cover = smoothstep(0.0, 0.08, depth)
                if (cover <= 0) { out.data[i] = p; continue }
                fun hAt(xx: Int, yy: Int) = smooth[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)].toDouble()
                var nx = -(hAt(x + 1, y) - hAt(x - 1, y)) / 2 * slope
                var ny = -(hAt(x, y + 1) - hAt(x, y - 1)) / 2 * slope
                var nz = 1.0
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= nl; ny /= nl; nz /= nl
                val diffuse = max(0.0, nx * lx + ny * ly + nz * lz)
                val nh = max(0.0, nx * hx + ny * hy + nz * hz)
                val spec = (nh.pow(60) * 1.4 + nh.pow(8) * 0.12) * gloss
                // thin blood lighter and redder, thick blood darker
                val body = (1.25 - 0.55 * depth) * (0.5 + 0.65 * diffuse)
                val pa = (p ushr 24) / 255.0
                val oa = pa + cover - pa * cover
                fun c8(k: Int): Int {
                    val blood = min(1.0, col[k] * body + spec)
                    return (((ch(p, 16 - 8 * k) * pa * (1 - cover) + blood * cover) / oa) * 255 + 0.5).toInt().coerceIn(0, 255)
                }
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(0) shl 16) or (c8(1) shl 8) or c8(2)
            }
        }
        return out
    }
}
