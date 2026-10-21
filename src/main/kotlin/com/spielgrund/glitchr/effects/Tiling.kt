package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The picture repeated as tiles (ported from Noiser's image layers): seamless by cross-fading
 * its edges with a half-shifted copy, and stochastic tiling that breaks up the repetition – a
 * triangle grid over the tiles gives every grid point a randomly shifted (and turned) part of
 * the picture, and each pixel blends the three of its triangle, keeping the picture's contrast.
 * Large-scale brightness and color differences can be evened out, which hides repeats further.
 */
object Tiling : Effect("tiling", "Tiling", "Repeats the picture as tiles – seamless, or stochastic: randomly shifted and turned patches blended so no repetition shows") {
    override val params = listOf(
        Param.Slider("repeats", "Repeats", 1, 32, 3, tip = "How many times the picture repeats across the width; its proportions stay"),
        Param.Toggle("seamless", "Seamless", true, "Hides the picture's own edges: towards them it cross-fades into a copy shifted by half a picture"),
        Param.Toggle("stochastic", "Stochastic", true, "Breaks up the repetition: randomly shifted parts of the picture blended into each other (Reroll = another arrangement)"),
        Param.Slider("cells", "Cells", 1, 8, 2, tip = "Random patches per repeat of the picture: more = mixed in smaller pieces"),
        Param.Slider("rotation", "Random rotation", 0, 18000, 0, "°", "Each patch is turned by a random angle up to ± this much; 180° = any direction", decimals = 2),
        Param.Slider("blend", "Transition width", 1, 50, 25, " %", "Width of the transitions: small = hard borders, large = softly blended"),
        Param.Slider("blendNoise", "Transition noise", 0, 100, 0, " %", "Makes the transitions irregular, so even narrow ones look natural"),
        Param.Heading("evenHeading", "Even out"),
        Param.Slider("evenLight", "Brightness", 0, 100, 0, " %", "Evens out bright and dark areas (light falloff in a photo), fine structure stays"),
        Param.Slider("evenColor", "Color", 0, 100, 0, " %", "Removes large color casts, brightness and fine structure stay"),
        Param.Slider("evenSize", "Area size", 300, 5000, 1200, " %", "Size of the evened-out areas, of the picture: small = also smaller patches, large = only gradients", decimals = 2),
    )

    /** The picture with its halved levels (mipmaps), so it can be shrunk into many tiles without flickering. */
    private class Mipmap(src: Pixels) {
        private class Level(val w: Int, val h: Int, val px: IntArray)

        private val levels = ArrayList<Level>().apply {
            var level = Level(src.width, src.height, src.data.copyOf())
            add(level)
            while (level.w > 1 || level.h > 1) {
                level = halve(level)
                add(level)
            }
        }
        val width = src.width
        val aspect = src.width.toDouble() / src.height

        /** Average color (the 1×1 level): a, r, g, b in 0..1. */
        val mean = DoubleArray(4).also { channels(levels.last().px[0], it) }

        private fun channels(c: Int, out: DoubleArray) {
            out[0] = (c ushr 24) / 255.0
            out[1] = (c shr 16 and 0xFF) / 255.0
            out[2] = (c shr 8 and 0xFF) / 255.0
            out[3] = (c and 0xFF) / 255.0
        }

        /**
         * Bilinear sample at ([x], [y]), the picture repeating every 1; [footprint] is how many picture
         * pixels one output pixel covers and picks the level. With [clampEdges] the filter stops at the
         * picture's edges instead of mixing in the opposite side. a, r, g, b in 0..1 into [out].
         */
        fun sample(x: Double, y: Double, footprint: Double, out: DoubleArray, clampEdges: Boolean = false) {
            val index = if (footprint <= 1) 0 else (ln(footprint) / ln(2.0)).toInt().coerceAtMost(levels.lastIndex)
            val l = levels[index]
            val fx = (if (clampEdges) x - floor(x) else x) * l.w - 0.5
            val fy = (if (clampEdges) y - floor(y) else y) * l.h - 0.5
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            fun ix(i: Int) = if (clampEdges) i.coerceIn(0, l.w - 1) else Math.floorMod(i, l.w)
            fun iy(i: Int) = if (clampEdges) i.coerceIn(0, l.h - 1) else Math.floorMod(i, l.h)
            val c00 = l.px[iy(y0) * l.w + ix(x0)]
            val c10 = l.px[iy(y0) * l.w + ix(x0 + 1)]
            val c01 = l.px[iy(y0 + 1) * l.w + ix(x0)]
            val c11 = l.px[iy(y0 + 1) * l.w + ix(x0 + 1)]
            for (ch in 0..3) {
                val shift = 24 - ch * 8
                fun v(c: Int) = ((c ushr shift) and 0xFF).toDouble()
                val top = v(c00) + (v(c10) - v(c00)) * tx
                val bottom = v(c01) + (v(c11) - v(c01)) * tx
                out[ch] = (top + (bottom - top) * ty) / 255
            }
        }

        /** 2 × 2 box filter down to half size. */
        private fun halve(src: Level): Level {
            val w = max(1, src.w / 2)
            val h = max(1, src.h / 2)
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = src.px[min(y * 2 + dy, src.h - 1) * src.w + min(x * 2 + dx, src.w - 1)]
                    a += c ushr 24; r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
                }
                px[y * w + x] = ((a / 4) shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
            }
            return Level(w, h, px)
        }
    }

    /** Deterministic hash of a grid point → 0..1. */
    private fun hash01(x: Int, y: Int, seed: Int): Double {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / 2147483647.0
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)
    private fun luminance(c: DoubleArray) = 0.2126 * c[1] + 0.7152 * c[2] + 0.0722 * c[3]

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val image = Mipmap(src)
        val w = src.width
        val h = src.height
        // tile size in output pixels: the picture's proportions kept
        val tileW = w.toDouble() / v["repeats"]
        val tileH = tileW / image.aspect
        val footprint = image.width / tileW
        val seamless = v.bool("seamless")
        val stochastic = v.bool("stochastic")
        val cells = v["cells"]
        val rotation = (v["rotation"] / 100.0).toDouble()
        val blend = (v["blend"] / 100.0).coerceIn(0.01, 0.5)
        val blendNoise = v["blendNoise"] / 100.0
        val evenLight = v["evenLight"] / 100.0
        val evenColor = v["evenColor"] / 100.0
        val evenSize = ((v["evenSize"] / 100.0) / 100.0).coerceIn(0.03, 0.5)
        val evening = evenLight > 0 || evenColor > 0
        val s = seed.toInt() xor (seed ushr 32).toInt()
        val noise = Noise(seed + 401)
        fun fbm01(x: Double, y: Double) = (noise.fbm(x, y, 3) * 0.5 + 0.5).coerceIn(0.0, 1.0)
        val mean = image.mean
        val meanLuma = luminance(mean)

        /** A sample with large-scale brightness and color differences evened out; fine detail stays. */
        fun evened(x: Double, y: Double, out: DoubleArray, blur: DoubleArray) {
            image.sample(x, y, footprint, out)
            if (!evening) return
            // the surroundings end at the picture's edges: wrapping around would even out the wrong way
            image.sample(x, y, max(footprint, image.width * evenSize), blur, clampEdges = true)
            val blurLuma = luminance(blur)
            // color: the surroundings' cast swapped for the picture's average cast – brightness untouched
            if (evenColor > 0) for (c in 1..3) out[c] += evenColor * ((mean[c] - meanLuma) - (blur[c] - blurLuma))
            // brightness: scaled towards the picture's average brightness
            if (evenLight > 0) {
                val factor = 1 + evenLight * (meanLuma / max(blurLuma, 0.02) - 1)
                for (c in 1..3) out[c] *= factor
            }
            for (c in 1..3) out[c] = out[c].coerceIn(0.0, 1.0)
        }

        /**
         * A sample that is seamless even if the picture is not: towards its edges it cross-fades into a
         * copy shifted by half a picture, whose seam lies in the middle where the original takes over.
         */
        fun tileable(x: Double, y: Double, out: DoubleArray, tmp: DoubleArray, blur: DoubleArray) {
            if (!seamless) {
                evened(x, y, out, blur)
                return
            }
            val fx = x - floor(x)
            val fy = y - floor(y)
            // noise moves the transition inwards by up to the room that is left, never outwards
            val shift = if (blendNoise > 0) (0.5 - blend) * blendNoise * fbm01(fx * 6 + floor(x) * 13.1, fy * 6 + floor(y) * 7.7) else 0.0
            val wx = smooth(((min(fx, 1 - fx) - shift) / blend).coerceIn(0.0, 1.0))
            val wy = smooth(((min(fy, 1 - fy) - shift) / blend).coerceIn(0.0, 1.0))
            out.fill(0.0)
            for (sx in 0..1) for (sy in 0..1) {
                val wgt = (if (sx == 0) wx else 1 - wx) * (if (sy == 0) wy else 1 - wy)
                if (wgt <= 0) continue
                evened(fx + sx * 0.5, fy + sy * 0.5, tmp, blur)
                for (c in 0..3) out[c] += wgt * tmp[c]
            }
        }

        val out = Pixels(w, h)
        parallelRows(h) { py ->
            val color = DoubleArray(4)
            val tmp = DoubleArray(4)
            val tmp2 = DoubleArray(4)
            val blur = DoubleArray(4)
            for (px in 0 until w) {
                // position in the picture, which repeats every 1
                val x = (px + 0.5) / tileW
                val y = (py + 0.5) / tileH
                if (!stochastic) {
                    tileable(x, y, color, tmp, blur)
                } else {
                    // a triangle grid: each square split in two, the pixel blends its triangle's three corners
                    val gx = x * cells
                    val gy = y * cells
                    val i = floor(gx).toInt()
                    val j = floor(gy).toInt()
                    val fx = gx - i
                    val fy = gy - j
                    val lower = fx + fy < 1
                    val ac = if (lower) 0 else 1
                    val wa = if (lower) 1 - fx - fy else fx + fy - 1
                    val wb = if (lower) fx else 1 - fy
                    val wc = if (lower) fy else 1 - fx
                    color.fill(0.0)
                    var squares = 0.0
                    // sharper weights = shorter transitions; width 25 % gives the classic power of 4
                    val sharpness = (1 / blend).coerceIn(2.0, 64.0)
                    fun corner(ci: Int, cj: Int, wIn: Double) {
                        if (wIn <= 0) return
                        var weight = wIn
                        // noise per corner scales its weight, so the borders between patches wander
                        if (blendNoise > 0) weight *= exp(blendNoise * 4 * (fbm01(gx * 2 + hash01(ci, cj, s + 303) * 50, gy * 2) - 0.5))
                        val sharp = weight.pow(sharpness)
                        if (sharp <= 0) return
                        var sx = x
                        var sy = y
                        if (rotation > 0) {
                            // the patch turned around its corner, in the picture's own proportions
                            val cx = ci.toDouble() / cells
                            val cy = cj.toDouble() / cells
                            val turn = Math.toRadians((hash01(ci, cj, s + 304) * 2 - 1) * rotation)
                            val c = cos(turn)
                            val sn = sin(turn)
                            val dx = (x - cx) * image.aspect
                            val dy = y - cy
                            sx = cx + (dx * c - dy * sn) / image.aspect
                            sy = cy + (dx * sn + dy * c)
                        }
                        tileable(sx + hash01(ci, cj, s + 301), sy + hash01(ci, cj, s + 302), tmp, tmp2, blur)
                        for (ch in 0..3) color[ch] += sharp * (tmp[ch] - mean[ch])
                        squares += sharp * sharp
                    }
                    corner(i + ac, j + ac, wa)
                    corner(i + 1, j, wb)
                    corner(i, j + 1, wc)
                    // normalized weights: the deviations from the mean scaled to keep the contrast
                    val scale = 1 / sqrt(max(squares, 1e-12))
                    for (ch in 0..3) color[ch] = (mean[ch] + color[ch] * scale).coerceIn(0.0, 1.0)
                }
                fun b(c: Double) = (c * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[py * w + px] = (b(color[0]) shl 24) or (b(color[1]) shl 16) or (b(color[2]) shl 8) or b(color[3])
            }
        }
        return out
    }
}
