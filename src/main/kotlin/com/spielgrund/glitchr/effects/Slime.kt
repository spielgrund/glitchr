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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glossy, translucent slime over the picture – around the frame, on a threshold area or everywhere:
 * flowing membranes full of round pores like lace, blobs, webby strands or drips hanging from the top.
 * The slime is a height profile: thick in the middle, rounded at its edges and around the pores. It
 * refracts the picture below, is tinted in its color the more the thicker it is, is lit from one side
 * with wet highlights, a bright rim and a shimmer of rainbow colors, and casts a soft shadow; inside
 * the pores the picture lies deep in the dark. The flow animates it: the forms drift and churn.
 */
object Slime : Effect("slime", "Slime", "Glossy translucent slime with round pores, strands or drips – around the frame, on an area or everywhere") {
    private val places = listOf("Frame", "Threshold area", "Everywhere")
    private val styles = listOf("Lace", "Blobs", "Strands", "Drips")
    private const val FRAME = 0
    private const val AREA = 1
    private const val BLOBS = 1
    private const val STRANDS = 2
    private const val DRIPS = 3
    private val spreads = listOf("Patches", "Fine detail", "Thin parts")
    private const val ON_FINE = 1
    private const val ON_THIN = 2

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: the slime drifts and churns", decimals = 3),
        Param.Heading("whereHeading", "Where"),
        Param.Choice("place", "Place", places, FRAME, "Frame: grows in from the edges · Threshold area: on a range of the picture · Everywhere: all over"),
        Param.Slider("depth", "Frame width", 1, 100, 22, " %", "How far it reaches in from the edges, of the shorter side").shownWhen("place", FRAME),
        Param.Choice("source", "Value", thresholdModes, THRESHOLD_LUMA, THRESHOLD_TIP).shownWhen("place", AREA),
        Param.Slider("lower", "Lower threshold", 0, 255, 160).shownWhen("place", AREA),
        Param.Slider("upper", "Upper threshold", 0, 255, 255).shownWhen("place", AREA),
        Param.Toggle("invert", "Invert range", false).shownWhen("place", AREA),
        Param.Heading("shapeHeading", "Shape"),
        Param.Choice("style", "Style", styles, 0, "Lace: membranes full of round pores · Blobs: thick lumps, few pores · Strands: a web of threads · Drips: hanging from the top edge"),
        Param.Slider("scale", "Size", 5, 400, 100, " %", "Size of the forms"),
        Param.Slider("amount", "Amount", -100, 100, 0, " %", "Less or more slime"),
        Param.Slider("warp", "Churning", 0, 100, 60, " %", "How twisted and flowing the forms are"),
        Param.Slider("pores", "Pores", 0, 100, 60, " %", "Round holes in the slime – more and bigger where it is thin"),
        Param.Slider("poreSize", "Pore size", 5, 200, 100, " %"),
        Param.Slider("detail", "Fine detail", 0, 100, 40, " %", "Smaller patches and droplets in between the big forms"),
        Param.Slider("detailSize", "Detail size", 10, 100, 25, " %", "Size of the small patches, of the big forms"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Color", 0xC4DAF2),
        Param.Color("color2", "Second color", 0xB4F2CF),
        Param.Choice("spread", "Second color on", spreads, 0, "Patches: flowing patches of it · Fine detail: the small patches and droplets · Thin parts: the thin edges and membranes"),
        Param.Slider("color2Amount", "Second color amount", 0, 100, 35, " %"),
        Param.Slider("opacity", "Opacity", 0, 100, 40, " %", "How milky the slime is: 0 % clear like water, 100 % its color only"),
        Param.Slider("thickness", "Thickness", 1, 100, 35, " %", "How round and swollen it is"),
        Param.Slider("refraction", "Refraction", 0, 200, 60, " %", "How much it bends the picture below"),
        Param.Slider("gloss", "Gloss", 0, 100, 80, " %", "Wet highlights"),
        Param.Slider("rim", "Rim light", 0, 100, 60, " %", "Bright edges where it curves away"),
        Param.Slider("iridescence", "Iridescence", 0, 100, 25, " %", "A shimmer of rainbow colors along the curves"),
        Param.Slider("lightAngle", "Light from", 0, 359, 135, "°", "0° from the right, 90° from above"),
        Param.Slider("shadow", "Shadow", 0, 100, 50, " %", "The shadow it casts on the picture"),
        Param.Slider("poreDepth", "Pore depth", 0, 100, 75, " %", "How dark the picture lies deep inside the pores"),
    )

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** 0..1 for cell ([i], [j]) and [salt]. */
    private fun rnd(i: Int, j: Int, salt: Int, seed: Long): Double {
        var z = seed * -0x61c8864680b583ebL + i * 0x9E3779B1L + j * 0x85EBCA77L + salt * 0xC2B2AE3DL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return ((z xor (z ushr 31)) ushr 11) * (1.0 / (1L shl 53))
    }

    /**
     * How much of a pore is at ([x], [y]) on a grid of [cell] pixels: 1 inside, 0 outside, soft over
     * [soft] pixels. Every cell may hold a round pore, bigger by [grow].
     */
    private fun pore(x: Double, y: Double, cell: Double, chance: Double, grow: Double, soft: Double, seed: Long, salt: Int): Double {
        val gx = x / cell
        val gy = y / cell
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        var best = 0.0
        for (j in iy - 1..iy + 1) for (i in ix - 1..ix + 1) {
            if (rnd(i, j, salt, seed) > chance) continue
            val cx = (i + 0.15 + 0.7 * rnd(i, j, salt + 1, seed)) * cell
            val cy = (j + 0.15 + 0.7 * rnd(i, j, salt + 2, seed)) * cell
            val r = cell * (0.16 + 0.3 * rnd(i, j, salt + 3, seed)) * grow
            val d = hypot(x - cx, y - cy)
            best = max(best, smoothstep(r + soft, r - soft, d))
        }
        return best
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val short = min(w, h).toDouble()
        // the size of a form in pixels
        val s = max(4.0, short * 0.22 * v["scale"] / 100.0)
        val t = v["time"] / 1000.0
        val noise = Noise(seed)
        val warpNoise = Noise(seed + 77)
        val place = v["place"]
        val style = v["style"]
        val depth = max(2.0, short * v["depth"] / 100.0)
        val amount = v["amount"] / 100.0
        val warp = v["warp"] / 100.0 * 1.6
        val pores = v["pores"] / 100.0
        val poreCell = s * 0.6 * v["poreSize"] / 100.0
        val detail = v["detail"] / 100.0
        val detailScale = v["detailSize"] / 100.0
        val fineSoft = 1.2 / (s * detailScale)

        // the threshold area, softened into a coverage
        val area = if (place != AREA) null else {
            val a = FloatArray(w * h) { if (thresholdSelects(src.data[it], v["source"], v["lower"], v["upper"], v.bool("invert"))) 1f else 0f }
            Blur.gauss(a, w, h, s * 0.25)
        }

        // the slime: coverage, shaped by churned noise, with pores cut out – soft over about a pixel
        val mask = FloatArray(w * h)
        // how much of it belongs to the small patches – for the second color
        val fine = FloatArray(w * h)
        val edgeSoft = 1.2 / s
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val base = when (place) {
                    FRAME -> {
                        // an uneven width: wider and narrower stretches along the edge
                        val wide = 1 + 0.6 * warpNoise.fbm(x / (s * 3.5) + 31.0, y / (s * 3.5) + 17.0, 2)
                        if (style == DRIPS) {
                            // drips hang down where the noise along the edge has a crest, each its own length
                            val crest = (1 - abs(noise.fbm(x / s * 1.1 + 7.7, 3.1 + t * 0.02, 2))).pow(8.0)
                            1 - y / (depth * (0.35 + 0.3 * wide + crest * 4.5))
                        } else 1 - min(min(x, w - 1 - x), min(y, h - 1 - y)) / (depth * wide)
                    }
                    AREA -> area!![y * w + x].toDouble()
                    else -> 0.5
                }
                // far from any slime: skip the noise
                if (base < -0.9 || base > 2.2) { mask[y * w + x] = if (base > 2.2) 1f else 0f; continue }
                var qx = x / s
                var qy = y / s
                if (style == DRIPS) { qx *= 1.3; qy *= 0.2 }
                val wx = warpNoise.fbm(qx * 0.7 + 5.2 + t * 0.06, qy * 0.7 + 1.3, 3)
                val wy = warpNoise.fbm(qx * 0.7 + 1.7, qy * 0.7 + 9.2 - t * 0.05, 3)
                qx += wx * warp
                qy += wy * warp
                val n = when (style) {
                    STRANDS -> (1 - abs(noise.fbm(qx, qy, 4))).pow(10.0)
                    else -> noise.fbm(qx, qy, 4) * 0.5 + 0.5
                }
                val field = when (style) {
                    // threads along the ridges of the noise – many and thick near the edge, thin and few further in
                    STRANDS -> n + (base - 0.5) * 0.4 + amount * 0.25
                    BLOBS -> base + (n - 0.5) * 1.1 + amount * 0.45
                    DRIPS -> base + (n - 0.5) * 0.9 + amount * 0.45
                    else -> base + (n - 0.5) * 1.7 + amount * 0.45
                }
                var m = smoothstep(0.5 - edgeSoft, 0.5 + edgeSoft, field)
                // the pores and droplets drift with the slime
                val px = x + wx * warp * s * 0.5
                val py = y + wy * warp * s * 0.5
                if (m > 0 && pores > 0 && style != STRANDS) {
                    // the pores grow where it is thin
                    val thin = (1.4 - (field - 0.5) * 1.6).coerceIn(0.8, 1.4)
                    val chance = pores * (when (style) { BLOBS -> 0.35; DRIPS -> 0.4; else -> 1.0 })
                    val hole = max(
                        pore(px, py, poreCell, chance, thin, 1.0, seed, 10),
                        pore(px, py, poreCell * 0.42, chance, thin, 0.8, seed, 20),
                    )
                    m *= 1 - hole
                }
                if (detail > 0 && field < 0.5) {
                    // smaller patches and droplets in the gaps, the more the nearer to the big forms
                    // and only close to where the slime may be
                    val near = smoothstep(0.5 - 0.9 * (0.3 + detail), 0.5 - 0.1 * detail, field) *
                        (if (place == FRAME || place == AREA) smoothstep(0.08, 0.35, base) else 1.0)
                    val nf = noise.fbm(qx / detailScale + 13.1, qy / detailScale + 4.7, 3) * 0.5 + 0.5
                    val patch = smoothstep(0.5 - fineSoft, 0.5 + fineSoft, nf + (near - 1) * 0.3 + detail * 0.25 - 0.2)
                    val drop = pore(px, py, s * detailScale * 0.8, min(1.0, detail * 1.2) * near, 1.0, 0.8, seed, 30)
                    val f = max(patch, drop)
                    if (f > m) { fine[y * w + x] = f.toFloat(); m = f }
                }
                mask[y * w + x] = m.toFloat()
            }
        }

        // the height: the mask rounded off – thick in the middle, sloping at the edges and into the pores
        val sigma = max(1.0, s * 0.12 * v["thickness"] / 35.0)
        val soft = Blur.gauss(mask, w, h, sigma)
        val height = FloatArray(w * h) { (sqrt(max(0.0, soft[it].toDouble())) * mask[it]).toFloat() }
        // a little smoother for the normals
        val hs = Blur.gauss(height, w, h, max(0.8, sigma * 0.35))
        // shadow: the slime shifted away from the light, blurred; darkness inside the pores
        val lightAngle = Math.toRadians(v["lightAngle"].toDouble())
        val lx0 = cos(lightAngle)
        val ly0 = -sin(lightAngle)
        val shift = sigma * 1.5
        val shifted = FloatArray(w * h)
        val sx = (lx0 * shift).roundToInt()
        val sy = (ly0 * shift).roundToInt()
        for (y in 0 until h) for (x in 0 until w) {
            val xx = x + sx
            val yy = y + sy
            if (xx in 0 until w && yy in 0 until h) shifted[y * w + x] = mask[yy * w + xx]
        }
        val shadowMap = Blur.gauss(shifted, w, h, sigma * 1.6)
        val occlusion = Blur.gauss(mask, w, h, max(1.0, poreCell * 0.18))

        val color = v["color"]
        val color2 = v["color2"]
        val spread = v["spread"]
        val amount2 = v["color2Amount"] / 100.0
        // where the second color is: 0..1
        val second = when {
            amount2 <= 0 -> null
            spread == ON_FINE -> Blur.gauss(fine, w, h, max(1.0, sigma * 0.5)).also { f -> for (i in f.indices) f[i] = min(1f, f[i] * (amount2 * 2.5).toFloat()) }
            spread == ON_THIN -> FloatArray(w * h) { smoothstep(0.75 - amount2 * 1.2, 0.95 - amount2 * 1.2, 1 - soft[it].toDouble()).toFloat() }
            else -> FloatArray(w * h) {
                val x = it % w
                val y = it / w
                val n = warpNoise.fbm(x / (s * 2.2) + 41.0 + t * 0.03, y / (s * 2.2) + 23.0 - t * 0.02, 3)
                smoothstep(-0.12, 0.12, n + (amount2 - 0.5) * 1.0).toFloat()
            }
        }
        val opacity = v["opacity"] / 100.0
        val refraction = v["refraction"] / 100.0 * sigma * 2.5
        val gloss = v["gloss"] / 100.0
        val rim = v["rim"] / 100.0
        val iridescence = v["iridescence"] / 100.0
        val shadow = v["shadow"] / 100.0
        val poreDepth = v["poreDepth"] / 100.0
        // light from the side and a little above, the view straight on
        val lz = 0.75
        val ll = sqrt(lx0 * lx0 + ly0 * ly0 + lz * lz)
        val lx = lx0 / ll
        val ly = ly0 / ll
        val lzn = lz / ll
        val hx = lx
        val hy = ly
        val hz = lzn + 1
        val hl = sqrt(hx * hx + hy * hy + hz * hz)
        val bump = sigma * 2.2

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                val m = mask[i].toDouble()
                // the picture where there is no slime: shadowed, deep inside the pores darker
                val dark = max(shadow * shadowMap[i] * 0.55, poreDepth * occlusion[i] * 0.9 * (1 - m))
                val br = red(c) * (1 - dark)
                val bg = green(c) * (1 - dark)
                val bb = blue(c) * (1 - dark)
                if (m <= 0.002) {
                    out.data[i] = argb(alpha(c), br.roundToInt(), bg.roundToInt(), bb.roundToInt())
                    continue
                }
                fun at(xx: Int, yy: Int) = hs[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)].toDouble()
                val gx = (at(x + 1, y) - at(x - 1, y)) * 0.5 * bump
                val gy = (at(x, y + 1) - at(x, y - 1)) * 0.5 * bump
                val nl = sqrt(gx * gx + gy * gy + 1)
                val nx = -gx / nl
                val ny = -gy / nl
                val nz = 1 / nl
                val th = height[i].toDouble()
                val k2 = second?.get(i)?.toDouble() ?: 0.0
                val cr = (red(color) * (1 - k2) + red(color2) * k2) / 255.0
                val cg = (green(color) * (1 - k2) + green(color2) * k2) / 255.0
                val cb = (blue(color) * (1 - k2) + blue(color2) * k2) / 255.0

                // the picture seen through it, bent by the slope
                val seen = sampleBilinear(src, x + 0.5 + nx * refraction, y + 0.5 + ny * refraction, Edge.CLAMP)
                // the thicker, the more it soaks up what its color lacks, and the milkier
                val milk = (opacity * (0.25 + 0.75 * th)).coerceIn(0.0, 1.0)
                val soak = 0.7 * th
                var r = (red(seen) / 255.0) * (1 - soak * (1 - cr)) * (1 - milk) + cr * milk
                var g = (green(seen) / 255.0) * (1 - soak * (1 - cg)) * (1 - milk) + cg * milk
                var b = (blue(seen) / 255.0) * (1 - soak * (1 - cb)) * (1 - milk) + cb * milk
                // lit from the side: the slopes facing away a deeper tint of its color
                val diffuse = max(0.0, nx * lx + ny * ly + nz * lzn)
                val lit = 0.62 + 0.5 * diffuse
                r *= lit * (0.85 + 0.15 * cr); g *= lit * (0.85 + 0.15 * cg); b *= lit * (0.85 + 0.15 * cb)
                // rim and rainbow shimmer where it curves away
                val fres = (1 - nz).pow(1.6)
                val rimK = (fres * rim * 1.4).coerceIn(0.0, 1.0)
                r += (1 - r) * rimK * (0.6 + 0.4 * cr); g += (1 - g) * rimK * (0.6 + 0.4 * cg); b += (1 - b) * rimK * (0.6 + 0.4 * cb)
                if (iridescence > 0) {
                    val hue = (Math.atan2(ny, nx) / (2 * PI) + th * 0.8 + 1) % 1.0
                    val k = iridescence * fres * 0.9
                    r += (0.5 + 0.5 * cos(2 * PI * hue)) * k * 0.6
                    g += (0.5 + 0.5 * cos(2 * PI * (hue - 1 / 3.0))) * k * 0.6
                    b += (0.5 + 0.5 * cos(2 * PI * (hue - 2 / 3.0))) * k * 0.6
                }
                // wet highlights: a sharp one and a broad sheen
                val nh = max(0.0, (nx * hx + ny * hy + nz * hz) / hl)
                val spec = gloss * (nh.pow(90.0) * 1.6 + nh.pow(14.0) * 0.18)
                r += spec; g += spec; b += spec

                fun mix(slime: Double, ground: Double) = (slime.coerceIn(0.0, 1.0) * 255 * m + ground * (1 - m)).roundToInt().coerceIn(0, 255)
                val a = (alpha(c) + (255 - alpha(c)) * m).roundToInt()
                out.data[i] = argb(a, mix(r, br), mix(g, bg), mix(b, bb))
            }
        }
        return out
    }
}
