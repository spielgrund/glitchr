package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The picture freezes over (progress 0 → 100 %): ice creeps in from the edges, the bottom, the
 * top or everywhere at once, along a frayed front. Under the ice the picture blurs as behind a
 * frosted window and turns pale and icy; frost grain and feathery frost ferns grow over it –
 * the ferns most where the ice is still forming – with cracks and glinting sparkles. Seen from
 * an angle (as with the hologram) the picture under the ice shifts against the frost on top,
 * every ice plate by its own depth, so the picture jumps at the cracks; the glints flash.
 */
object FreezeOver : Effect("freeze", "Freeze over", "The picture freezes over – frost creeping in from the edges with frost ferns, icy blur, cracks and sparkles; animate the progress") {
    private val origins = listOf("The edges", "The bottom", "The top", "Everywhere at once")

    override val params = listOf(
        Param.Slider("progress", "Progress", 0, 10000, 6000, " %", "0 %: untouched · 100 %: frozen all over – animate it", decimals = 2),
        Param.Choice("from", "Freezes from", origins, 0, "Where the ice starts and creeps in from"),
        Param.Slider("soft", "Front softness", 1, 100, 20, " %", "How gradually the ice sets in"),
        Param.Heading("frostHeading", "Frost"),
        Param.Slider("frost", "Frost", 0, 100, 60, " %", "White frost grain over the ice – densest where it started"),
        Param.Slider("ferns", "Frost ferns", 0, 100, 60, " %", "Feathery crystals, growing most where the ice is still forming"),
        Param.Slider("crystal", "Crystal size", 200, 20000, 2500, " px", decimals = 2, canvasMax = true),
        Param.Slider("cracks", "Cracks", 0, 100, 25, " %", "Fine cracks in the solid ice"),
        Param.Slider("sparkle", "Sparkle", 0, 100, 40, " %", "Glinting ice crystals"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Ice color", 0xA8DCFF),
        Param.Slider("tint", "Ice tint", 0, 100, 70, " %", "How much the frozen picture turns pale and icy"),
        Param.Slider("blur", "Frozen blur", 0, 100, 40, " %", "The picture blurred under the ice, like behind a frosted window"),
        Param.Heading("parallaxHeading", "Parallax"),
        Param.Slider("view", "View angle", -9000, 9000, 0, "°", "Looking at the ice from the side: the picture under it shifts against the frost – animate it", decimals = 2),
        Param.Slider("axis", "View direction", 0, 35900, 0, "°", "Which way the view turns; 0° = left/right", decimals = 2),
        Param.Slider("parallax", "Parallax", 0, 300, 40, " px", "How far the picture under the ice shifts against the frost (at 90°)"),
        Param.Slider("plates", "Plate depth", 0, 100, 50, " %", "How differently deep the ice plates lie: each refracts the picture its own way, with jumps at the cracks"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val size = min(w, h).toDouble()
        val progress = v["progress"] / 10000.0
        if (progress <= 0) return src.copy()
        val from = v["from"]
        val cs = max(2.0, v["crystal"] / 100.0)
        val soft = v["soft"] / 100.0 * 0.4 + 0.02
        // the front: 0 % before the first pixel, 100 % past the last
        val front = progress * (1 + 2 * soft) - soft

        // how frozen every pixel is
        val frozen = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                // when the ice gets here: 0 first, 1 last – frayed by noise
                val base = when (from) {
                    0 -> min(min(x, w - 1 - x), min(y, h - 1 - y)) / (size / 2)
                    1 -> 1 - y / (h - 1.0)
                    2 -> y / (h - 1.0)
                    else -> 0.5
                }
                val n = noise.fbm(x / (cs * 3), y / (cs * 3), 4)
                val a = if (from == 3) (0.5 + n * 0.9).coerceIn(0.0, 1.0)
                else (base * 0.8 + 0.1 + n * 0.22 + noise.fbm(x / (cs * 0.7) + 5.0, y / (cs * 0.7), 2) * 0.05).coerceIn(0.0, 1.0)
                frozen[y * w + x] = smoothstep(a - soft, a + soft, front).toFloat()
            }
        }

        // the picture behind the frosted ice
        val blur = v["blur"] / 100.0
        val blurred = if (blur > 0) {
            val sigma = max(0.6, blur * size * 0.012)
            Array(3) { k -> Blur.gauss(FloatArray(w * h) { (src.data[it] shr (16 - 8 * k) and 0xFF).toFloat() }, w, h, sigma) }
        } else null

        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val color = v["color"]
        val col = doubleArrayOf(ch(color, 16), ch(color, 8), ch(color, 0))
        val tint = v["tint"] / 100.0
        val frost = v["frost"] / 100.0
        val ferns = v["ferns"] / 100.0
        val cracks = v["cracks"] / 100.0
        val sparkle = v["sparkle"] / 100.0
        // the view from the side: the frost on the surface moves one way, the picture under the ice the other, each plate by its depth
        val view = Math.toRadians(v["view"] / 100.0)
        val turn = kotlin.math.sin(view)
        val axis = Math.toRadians(v["axis"] / 100.0)
        val ax = cos(axis)
        val ay = kotlin.math.sin(axis)
        val parallax = v["parallax"] * turn
        val plates = v["plates"] / 100.0
        val surface = -parallax * 0.35
        val plate = cs * 7

        /** Where on the ice plates (x, y) lies, jagged like the cracks: the plate's random value, −1..1. */
        fun plateAt(px: Double, py: Double): Double {
            val jx = px + noise.fbm(px / (cs * 0.8), py / (cs * 0.8), 2) * cs * 0.5
            val jy = py + noise.fbm(px / (cs * 0.8) + 9.0, py / (cs * 0.8), 2) * cs * 0.5
            return noise.voronoi(jx / plate, jy / plate)
        }

        /** The picture (blurred as frozen) at (px, py), for the shifted view. */
        fun picture(px: Double, py: Double, fz: Double, k: Int): Double {
            val own = (sampleBilinear(src, px, py, Edge.CLAMP) shr (16 - 8 * k) and 0xFF) / 255.0
            if (blurred == null) return own
            val bx = (px - 0.5).coerceIn(0.0, w - 1.0)
            val by = (py - 0.5).coerceIn(0.0, h - 1.0)
            val x0 = bx.toInt(); val y0 = by.toInt()
            val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
            val tx = bx - x0; val ty = by - y0
            val b = blurred[k]
            val blur = ((b[y0 * w + x0] * (1 - tx) + b[y0 * w + x1] * tx) * (1 - ty) + (b[y1 * w + x0] * (1 - tx) + b[y1 * w + x1] * tx) * ty) / 255.0
            return own + (blur - own) * fz
        }
        val glints = FloatArray(w * h)
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val c = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val p = src.data[i]
                val fz = frozen[i].toDouble()
                if (fz <= 0.001) { out.data[i] = p; continue }
                // the frost's place on the surface, and the picture seen through the ice
                val fx = x + ax * surface * fz
                val fy = y + ay * surface * fz
                if (parallax == 0.0) for (k in 0..2) {
                    val own = ch(p, 16 - 8 * k)
                    c[k] = if (blurred == null) own else own + (blurred[k][i] / 255.0 - own) * fz
                } else {
                    val depth = 1 + plates * plateAt(fx, fy) * 0.8
                    val shift = parallax * depth * fz
                    for (k in 0..2) c[k] = picture(x + 0.5 - ax * shift, y + 0.5 - ay * shift, fz, k)
                }
                // pale and icy: the brightness carried into the ice color, a little lighter
                val l = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
                for (k in 0..2) {
                    val ice = col[k] * (0.3 + 0.8 * l) + 0.05
                    c[k] += (ice - c[k]) * tint * fz
                    c[k] += (1 - c[k]) * 0.1 * fz
                }
                // frost: fine grain, and feathery ferns – the ferns most where the ice is still forming
                val grain = (0.5 + 0.5 * noise.fbm(fx / (cs * 0.15), fy / (cs * 0.15), 3)).coerceIn(0.0, 1.0).pow(3)
                val warp = noise.fbm(fx / cs * 0.5, fy / cs * 0.5, 2) * 2
                val fern = ((noise.ridged(fx / (cs * 0.6) + warp, fy / (cs * 0.6) - warp, 4) + 1) / 2).coerceIn(0.0, 1.0).pow(5)
                val forming = exp(-((fz - 0.5) / 0.32).pow(2))
                var white = frost * fz * grain * 0.9 + ferns * fern * (forming + 0.35 * fz)
                // cracks in the solid ice: thin bright lines with a dark side
                if (cracks > 0 && fz > 0.5) {
                    // along the seams between large ice plates – only some of them, so they read as single cracks; jagged by noise
                    fun crack(px: Double, py: Double): Double {
                        val jx = px + noise.fbm(px / (cs * 0.8), py / (cs * 0.8), 2) * cs * 0.5
                        val jy = py + noise.fbm(px / (cs * 0.8) + 9.0, py / (cs * 0.8), 2) * cs * 0.5
                        val here = noise.voronoi(jx / plate, jy / plate)
                        val right = noise.voronoi((jx + 1) / plate, jy / plate)
                        val down = noise.voronoi(jx / plate, (jy + 1) / plate)
                        val seam = (here != right && here + right > -0.1) || (here != down && here + down > -0.1)
                        return if (seam) 1.0 else 0.0
                    }
                    val solid = smoothstep(0.5, 1.0, fz) * cracks
                    val line = min(1.0, crack(fx, fy)) * solid
                    val shade = min(1.0, crack(fx - 1.5, fy - 1.5)) * solid
                    for (k in 0..2) c[k] *= 1 - 0.45 * max(0.0, shade - line)
                    white += line * 0.8
                }
                white = min(1.0, white)
                for (k in 0..2) c[k] += (1 - c[k]) * white
                // the glints sit on the surface, moving with the frost
                val gx = kotlin.math.floor(fx).toInt()
                val gy = kotlin.math.floor(fy).toInt()
                if (sparkle > 0 && fz > 0.5 && noise.white(gx, gy) > 1 - sparkle * 0.004) {
                    // each glint flashes at its own angle of view
                    val flash = if (turn == 0.0) 1.0 else 0.35 + 0.65 * (0.5 + 0.5 * cos(view * 8 + noise.white(gx, gy + 7) * 977))
                    glints[i] = (fz * flash).toFloat()
                }
                val pa = (p ushr 24) / 255.0
                val oa = max(pa, min(1.0, white * fz))
                fun c8(k: Int) = (c[k] * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(0) shl 16) or (c8(1) shl 8) or c8(2)
            }
        }
        if (sparkle <= 0) return out
        // the glints: a bright point in a small soft glow
        val halo = Blur.gauss(glints, w, h, max(0.7, size * 0.0025))
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val light = min(1.0, glints[i] + halo[i] * 3.0)
                if (light <= 0.004) continue
                val p = out.data[i]
                fun add(sh: Int) = (((p shr sh and 0xFF) + 255 * light).toInt()).coerceAtMost(255)
                out.data[i] = (max(p ushr 24, (light * 255).toInt()) shl 24) or (add(16) shl 16) or (add(8) shl 8) or add(0)
            }
        }
        return out
    }
}
