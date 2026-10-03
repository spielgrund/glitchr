package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * An old tube TV playing a worn VHS tape. First the tape: rows are shifted (jitter, slow waves,
 * tracking bands, head switching at the bottom), the color smears sideways while the brightness
 * stays sharp. Then the tube: the picture bulges, gets scanlines, a phosphor mask, color fringes
 * towards the edges, grain and a vignette.
 */
object Television : Effect("tv", "TV", "CRT television with VHS tape: curvature, scanlines, pixel grid, noise and tape errors") {
    private val masks = listOf("Off", "Aperture grille", "Shadow mask", "Slot mask")

    override val params = listOf(
        Param.Slider("curvature", "Bulge", 0, 100, 30, " %", "Curves the picture like a picture tube"),
        Param.Slider("corner", "Rounded corners", 0, 100, 25, " %"),
        Param.Slider("vignette", "Vignette", 0, 100, 40, " %", "Darkens the borders"),
        Param.Choice("border", "Edge", listOf("Black", "Transparent"), tip = "What lies outside the curved tube"),
        Param.Slider("scanlines", "Scanlines", 0, 100, 55, " %"),
        Param.Slider("lineSpacing", "Line spacing", 2, 40, 4, " px"),
        Param.Choice("mask", "Pixel grid", masks, 1, "The tube's phosphor dots: stripes (Trinitron), holes or slots"),
        Param.Slider("maskStrength", "Grid strength", 0, 100, 45, " %"),
        Param.Slider("maskSize", "Grid size", 3, 60, 3, " px", "Width of one red-green-blue triad"),
        Param.Slider("aberration", "Color fringe", 0, 50, 3, " px", "Red and blue drift apart towards the edge"),
        Param.Slider("glow", "Bloom", 0, 100, 40, " %", "Bright spots outshine scanlines and grid"),
        Param.Slider("saturation", "Saturation", 0, 200, 110, " %"),
        Param.Slider("noise", "Noise", 0, 100, 15, " %"),
        Param.Slider("blur", "Blur", 0, 20, 1, " px", "Horizontal blur of the signal"),
        Param.Slider("chromaSmear", "VHS color bleed", 0, 80, 8, " px", "The color smears sideways, the brightness stays sharp"),
        Param.Slider("jitter", "VHS line jitter", 0, 40, 1, " px"),
        Param.Slider("wobble", "VHS waves", 0, 100, 3, " px", "Slow sideways wobble of the picture"),
        Param.Slider("tracking", "VHS tracking errors", 0, 12, 2, "", "Number of disturbed bands with offset and snow"),
        Param.Slider("trackingStrength", "Tracking strength", 0, 400, 40, " px"),
        Param.Slider("headSwitch", "VHS head switching", 0, 300, 16, " px", "Height of the distorted rows at the bottom edge"),
    )

    private class Band(val center: Double, val height: Double, val phase: Double)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val tape = vhs(src, v, seed)
        return tube(tape, v, seed)
    }

    /** The tape stage: works row by row on the flat picture. */
    private fun vhs(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val rnd = Random(seed)
        val bands = List(v["tracking"]) {
            Band(rnd.nextDouble() * h, h * (0.006 + rnd.nextDouble() * 0.04) + 2, rnd.nextDouble() * 2 * PI)
        }
        val wobblePhase = rnd.nextDouble() * 2 * PI
        val jitter = v["jitter"].toDouble()
        val wobble = v["wobble"].toDouble()
        val trackStrength = v["trackingStrength"].toDouble()
        val head = v["headSwitch"]
        val blur = v["blur"]
        val smear = v["chromaSmear"]
        val saturation = v["saturation"] / 100.0

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            var shift = jitter * noise.white(y, 1) + wobble * sin(2 * PI * y / (h * 0.43) + wobblePhase)
            var snow = 0.0
            for (b in bands) {
                val d = (y - b.center) / b.height
                if (abs(d) < 1) {
                    val f = 1 - d * d
                    shift += trackStrength * f * (0.65 + 0.35 * sin(y * 0.21 + b.phase))
                    snow = max(snow, f)
                }
            }
            if (head > 0 && y >= h - head) {
                val t = (y - (h - head) + 1).toDouble() / head
                shift += t * t * head * 1.5
                snow = max(snow, t * 0.4)
            }

            // the shifted row in YIQ: brightness and color are handled separately like on tape
            val lum = FloatArray(w)
            val ci = FloatArray(w)
            val cq = FloatArray(w)
            val alpha = FloatArray(w)
            val row = y * w
            for (x in 0 until w) {
                val sx = (x - shift).coerceIn(0.0, w - 1.0)
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val t = (sx - x0).toFloat()
                val a = src.data[row + x0]
                val b = src.data[row + x1]
                fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t)
                val r = ch(16)
                val g = ch(8)
                val bl = ch(0)
                lum[x] = 0.299f * r + 0.587f * g + 0.114f * bl
                ci[x] = 0.596f * r - 0.274f * g - 0.322f * bl
                cq[x] = 0.211f * r - 0.523f * g + 0.312f * bl
                alpha[x] = ch(24)
            }
            val l = boxBlur(lum, blur)
            // the color lags behind the brightness and is much blurrier
            val i = boxBlur(ci, blur + smear)
            val q = boxBlur(cq, blur + smear)
            val lag = smear / 2
            for (x in 0 until w) {
                var yy = l[x].toDouble()
                if (snow > 0) {
                    val n = noise.white(x, y + 7919)
                    yy += snow * n * 110
                    if (n > 1 - 0.03 * snow) yy = 255.0
                }
                val cx = (x - lag).coerceAtLeast(0)
                val ii = i[cx] * saturation * (1 - snow * 0.6)
                val qq = q[cx] * saturation * (1 - snow * 0.6)
                out.data[row + x] = argb(
                    clamp(alpha[x].toDouble()),
                    clamp(yy + 0.956 * ii + 0.621 * qq),
                    clamp(yy - 0.272 * ii - 0.647 * qq),
                    clamp(yy - 1.106 * ii + 1.703 * qq),
                )
            }
        }
        return out
    }

    /** The tube stage: bulge, scanlines, phosphor mask, fringes, grain and vignette. */
    private fun tube(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed xor 0x5DEECE66DL)
        val k = v["curvature"] / 100.0 * 0.28
        val corner = v["corner"] / 100.0 * 0.25 * min(w, h)
        val vignette = v["vignette"] / 100.0
        val transparent = v["border"] == 1
        val scan = v["scanlines"] / 100.0
        val spacing = v["lineSpacing"].toDouble()
        val mask = v["mask"]
        val maskStrength = v["maskStrength"] / 100.0
        val sub = v["maskSize"] / 3.0
        val aberration = v["aberration"].toDouble()
        val glow = v["glow"] / 100.0
        val grain = v["noise"] / 100.0 * 90
        val cx = w / 2.0
        val cy = h / 2.0
        // brighten a little so the dark lines and the mask don't make the picture murky
        val gain = 1 + scan * 0.25 + maskStrength * (if (mask == 0) 0.0 else 0.45)

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val ny = (y + 0.5 - cy) / cy
            for (x in 0 until w) {
                val nx = (x + 0.5 - cx) / cx
                val f = 1 + k * (nx * nx + ny * ny)
                val ux = nx * f
                val uy = ny * f
                // distance outside the rounded screen, in pixels, for a soft edge
                val qx = abs(ux) * cx - (cx - corner)
                val qy = abs(uy) * cy - (cy - corner)
                val outside = if (qx > 0 && qy > 0) hypot(qx, qy) - corner else max(qx, qy) - corner
                val cover = (0.5 - outside).coerceIn(0.0, 1.0)
                if (cover <= 0) {
                    out.data[y * w + x] = if (transparent) 0 else 0xFF000000.toInt()
                    continue
                }
                val sx = cx + ux * cx - 0.5
                val sy = cy + uy * cy - 0.5
                val c = sample(src, sx, sy)
                var r = channelOf(sample(src, sx + aberration * ux, sy + aberration * uy * 0.5), 16)
                var g = channelOf(c, 8)
                var b = channelOf(sample(src, sx - aberration * ux, sy - aberration * uy * 0.5), 0)
                val a = (c ushr 24).toDouble()
                val light = (0.299 * r + 0.587 * g + 0.114 * b) / 255
                // bright pixels bloom over the dark gaps
                val spread = 1 - glow * light

                if (scan > 0) {
                    val beam = sin(PI * (sy + 0.5) / spacing).let { it * it }
                    val m = 1 - scan * spread * (1 - beam)
                    r *= m; g *= m; b *= m
                }
                if (mask != 0 && maskStrength > 0) {
                    val row = floor((sy + 0.5) / (sub * 3)).toInt()
                    var col = floor((sx + 0.5) / sub + if (mask == 2 && row and 1 == 1) 1.5 else 0.0).toInt()
                    val lit = Math.floorMod(col, 3)
                    val dim = 1 - maskStrength * spread
                    var gap = 1.0
                    if (mask == 3) {
                        // slots: every triad has a horizontal gap, neighbouring triads staggered
                        col = Math.floorMod(floor((sx + 0.5) / (sub * 3)).toInt(), 2)
                        val pos = Math.floorMod((sy + 0.5 + col * sub * 1.5).toInt(), (sub * 3).roundToInt().coerceAtLeast(2))
                        if (pos < max(1.0, sub * 0.6)) gap = dim
                    }
                    r *= (if (lit == 0) 1.0 else dim) * gap
                    g *= (if (lit == 1) 1.0 else dim) * gap
                    b *= (if (lit == 2) 1.0 else dim) * gap
                }
                val n = if (grain > 0) noise.white(x, y) * grain else 0.0
                val u = (ux + 1) / 2
                val vv = (uy + 1) / 2
                val vig = if (vignette > 0) (16 * u * (1 - u) * vv * (1 - vv)).coerceIn(0.0, 1.0).pow(0.35 * vignette) else 1.0
                val scale = gain * vig
                var rr = clamp(r * scale + n)
                var gg = clamp(g * scale + n)
                var bb = clamp(b * scale + n)
                var aa = clamp(a)
                if (cover < 1) {
                    if (transparent) aa = (aa * cover).roundToInt()
                    else {
                        rr = (rr * cover).roundToInt(); gg = (gg * cover).roundToInt(); bb = (bb * cover).roundToInt()
                        aa = (255 - (255 - aa) * cover).roundToInt()
                    }
                }
                out.data[y * w + x] = argb(aa, rr, gg, bb)
            }
        }
        return out
    }

    private fun channelOf(c: Int, shift: Int) = (c shr shift and 0xFF).toDouble()

    private fun clamp(v: Double) = v.roundToInt().coerceIn(0, 255)

    /** Bilinear sample with clamped edges. */
    private fun sample(p: Pixels, x: Double, y: Double): Int {
        val fx = x.coerceIn(0.0, p.width - 1.0)
        val fy = y.coerceIn(0.0, p.height - 1.0)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, p.width - 1)
        val y1 = min(y0 + 1, p.height - 1)
        val tx = fx - x0
        val ty = fy - y0
        val c00 = p[x0, y0]
        val c10 = p[x1, y0]
        val c01 = p[x0, y1]
        val c11 = p[x1, y1]
        var result = 0
        for (s in 0..24 step 8) {
            val top = (c00 shr s and 0xFF) * (1 - tx) + (c10 shr s and 0xFF) * tx
            val bottom = (c01 shr s and 0xFF) * (1 - tx) + (c11 shr s and 0xFF) * tx
            result = result or ((top * (1 - ty) + bottom * ty).roundToInt().coerceIn(0, 255) shl s)
        }
        return result
    }

    /** Horizontal box blur with radius [r], clamped at the ends. */
    private fun boxBlur(a: FloatArray, r: Int): FloatArray {
        if (r <= 0) return a
        val n = a.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + a[i]
        return FloatArray(n) { i ->
            val lo = max(0, i - r)
            val hi = min(n - 1, i + r)
            ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
        }
    }
}
