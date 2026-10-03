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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns everything into metal. The brightness of the picture, softened, is a relief (bright
 * is high); a surface finish – brushed, hammered, grainy, spun – is added on top. Every pixel
 * then mirrors an environment along its normal: a chrome horizon (sky above, dark ground
 * below), a photo studio with soft boxes, bands, or the picture itself. The metal's color
 * tints the reflection, towards grazing angles it turns white (Fresnel); a light adds a
 * highlight. Roughness blurs the reflection and widens the highlight.
 */
object Metal : Effect("metal", "Metal", "Turns everything into metal: chrome, gold, copper … with relief, surface and reflection") {
    private val metals = listOf("Chrome", "Silver", "Gold", "Copper", "Bronze", "Brass", "Steel", "Tempered", "Custom color")
    private const val TEMPERED = 7
    private const val CUSTOM = 8

    /** Reflectance (F0) of each metal, linear 0..1. */
    private val METAL_COLORS = listOf(
        doubleArrayOf(0.95, 0.95, 0.97),
        doubleArrayOf(0.97, 0.96, 0.91),
        doubleArrayOf(1.00, 0.78, 0.34),
        doubleArrayOf(0.96, 0.58, 0.42),
        doubleArrayOf(0.80, 0.55, 0.30),
        doubleArrayOf(0.91, 0.78, 0.42),
        doubleArrayOf(0.62, 0.64, 0.66),
    )

    /** Temper colors of heated steel, from cool to hot. */
    private val TEMPER = intArrayOf(0xC9C9C4, 0xE8D9A0, 0xC9933F, 0x9A4E3A, 0x7A3C8C, 0x3A55B8, 0x4E9EC4, 0xB8C8C8)

    private val surfaces = listOf("Smooth", "Brushed", "Hammered", "Grainy", "Spun")
    private const val BRUSHED = 1
    private const val HAMMERED = 2
    private const val GRAINY = 3
    private const val SPUN = 4

    private val environments = listOf("Chrome horizon", "Studio", "Stripes", "Picture")
    private const val HORIZON = 0
    private const val STUDIO = 1
    private const val BANDS = 2
    private const val PICTURE = 3

    override val params = listOf(
        Param.Heading("metalHeading", "Metal"),
        Param.Choice(
            "metal", "Metal", metals, 0,
            "Tempered: temper colors of heated steel – straw, bronze, violet, blue – depending on the height of the relief",
        ),
        Param.Color("color", "Custom color", 0xB0C4DE),
        Param.Slider("imageColor", "Picture color", 0, 100, 0, " %", "The metal takes on the picture's colors – like anodized"),
        Param.Heading("reliefHeading", "Relief"),
        Param.Slider("relief", "Embossing", 0, 500, 100, " %", "How high bright spots rise above dark ones"),
        Param.Slider("smooth", "Roundness", 0, 100, 4, " px", "Blurs the relief – round, cast edges instead of sharp steps"),
        Param.Toggle("invert", "Invert relief", false, "Dark is high, bright is low"),
        Param.Heading("surfaceHeading", "Surface"),
        Param.Choice("surface", "Surface", surfaces, BRUSHED),
        Param.Slider("surfaceSize", "Size", 1, 200, 6, " px", canvasMax = true),
        Param.Slider("surfaceStrength", "Strength", 0, 200, 50, " %"),
        Param.Slider("brushAngle", "Direction", 0, 179, 0, "°", "Direction of the brush strokes"),
        Param.Heading("envHeading", "Reflection"),
        Param.Choice(
            "env", "Environment", environments, HORIZON,
            "Chrome horizon: sky above, dark ground below – the classic chrome look · Studio: dark room with bright softboxes · " +
                "Stripes: light and dark bands · Picture: reflects the picture itself",
        ),
        Param.Slider("horizon", "Horizon", -60, 60, 15, "°", "Where the horizon lies in flat spots – higher: more ground"),
        Param.Slider("envAngle", "Rotation", -180, 180, 0, "°", "Rotates the environment"),
        Param.Slider("roughness", "Roughness", 0, 100, 15, " %", "Makes the reflection blurry and the gloss wider"),
        Param.Heading("lightHeading", "Light"),
        Param.Slider("lightAngle", "Light direction", 0, 359, 225, "°"),
        Param.Slider("lightHeight", "Light height", 5, 90, 45, "°"),
        Param.Slider("gloss", "Gloss", 0, 200, 80, " %"),
        Param.Slider("shading", "Shading", 0, 100, 30, " %", "Darkens areas that face away from the light"),
        Param.Heading("outHeading", "Output"),
        Param.Choice(
            "antialias", "Anti-aliasing", listOf("Off", "2 × 2", "4 × 4", "8 × 8"), 1,
            "Where neighboring pixels differ a lot – horizon, highlights, steep edges – it is sampled several times",
        ),
        Param.Slider("aaThreshold", "Smoothing threshold", 1, 100, 12, " %", "The color difference to a neighbor from which it is smoothed – less: more pixels are smoothed"),
        Param.Slider("amount", "Strength", 0, 100, 100, " %"),
    )

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        if (a == b) return if (x < a) 0.0 else 1.0
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val amount = v["amount"] / 100.0
        val metal = v["metal"]
        val custom = v["color"]
        val f0Base = when (metal) {
            CUSTOM -> doubleArrayOf(((custom shr 16) and 0xFF) / 255.0, ((custom shr 8) and 0xFF) / 255.0, (custom and 0xFF) / 255.0)
            TEMPERED -> METAL_COLORS[6]
            else -> METAL_COLORS[metal]
        }
        val imageColor = v["imageColor"] / 100.0
        val roughness = v["roughness"] / 100.0

        // the relief: brightness (transparent is low), softened; in pixels of height
        val sign = if (v.bool("invert")) -1f else 1f
        val lum = FloatArray(n) {
            val c = src.data[it]
            val l = (0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)) / 255f
            val a = alpha(c) / 255f
            (if (sign < 0) 1 - l else l) * a
        }
        val smoothed = Blur.gauss(lum, w, h, v["smooth"].toDouble())
        val reliefPx = v["relief"] / 100.0 * 6
        val height = DoubleArray(n) { smoothed[it] * reliefPx }

        // the surface finish, added to the height
        val surface = v["surface"]
        val sSize = v["surfaceSize"].toDouble()
        val sAmp = v["surfaceStrength"] / 100.0
        if (surface != 0 && sAmp > 0) {
            val noise = Noise(seed)
            val ba = Math.toRadians(v["brushAngle"].toDouble())
            val bc = cos(ba)
            val bs = sin(ba)
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val d = when (surface) {
                        BRUSHED -> {
                            // fine lines: fast across the brush direction, very slow along it
                            val along = x * bc + y * bs
                            val across = -x * bs + y * bc
                            0.6 * noise.fbm(along / (sSize * 40), across / sSize, 2) + 0.2 * noise.perlin(along / (sSize * 9) + 50, across / (sSize * 0.7))
                        }
                        HAMMERED -> {
                            // round dimples, deepest in the middle of each
                            val d = (noise.worley(x / (sSize * 3), y / (sSize * 3)) + 1) / 2
                            d * d * 2 + 0.15 * noise.perlin(x / sSize, y / sSize)
                        }
                        GRAINY -> noise.fbm(x / sSize, y / sSize, 3) * 0.6 + noise.white(x, y) * 0.06
                        else -> {
                            // spun: rings around the middle
                            val r = hypot(x - w / 2.0, y - h / 2.0)
                            val a = atan2(y - h / 2.0, x - w / 2.0)
                            noise.fbm(r / sSize, a * 2, 3) + 0.4 * noise.perlin(r / (sSize * 0.4), a * 30)
                        }
                    }
                    height[y * w + x] += d * sAmp * sSize * 0.12
                }
            }
        }

        // the picture as environment: blurred by the roughness
        val envMode = v["env"]
        val envPicture = if (envMode == PICTURE) {
            val sigma = roughness * max(w, h) * 0.05
            if (sigma < 0.5) src else {
                val ch = Array(3) { k -> FloatArray(n) { ((src.data[it] shr (16 - 8 * k)) and 0xFF).toFloat() } }
                val bl = Array(3) { Blur.gauss(ch[it], w, h, sigma) }
                Pixels(w, h, IntArray(n) { argb(255, bl[0][it].roundToInt().coerceIn(0, 255), bl[1][it].roundToInt().coerceIn(0, 255), bl[2][it].roundToInt().coerceIn(0, 255)) })
            }
        } else null

        val tilt = Math.toRadians(v["horizon"].toDouble())
        val envRot = Math.toRadians(v["envAngle"].toDouble())
        val soft = 0.01 + roughness * 0.5

        /** The environment's color in direction (rx, ry, rz) – rz towards the viewer, ry down – written into [out], 0..1. */
        fun environment(rx: Double, ry: Double, rz: Double, px: Double, py: Double, out: DoubleArray) {
            // turn around the view axis, then tilt so flat parts look a little above the horizon
            val ex = rx * cos(envRot) - ry * sin(envRot)
            val ey = rx * sin(envRot) + ry * cos(envRot)
            val up = -ey * cos(tilt) + rz * sin(tilt)
            val elev = asin(up.coerceIn(-1.0, 1.0)) / (PI / 2)
            when (envMode) {
                HORIZON -> {
                    // sky: bright at the horizon, deeper blue above; ground: black at the horizon, warm and lighter below
                    val s = smoothstep(-soft * 0.3, soft * 0.3, elev)
                    val sky = (1 - elev.coerceAtLeast(0.0)).pow(2.5)
                    val skyR = 0.35 + 0.65 * sky; val skyG = 0.45 + 0.55 * sky; val skyB = 0.62 + 0.38 * sky
                    val gd = (-elev).coerceAtLeast(0.0)
                    val ground = 0.04 + 0.5 * smoothstep(0.0, 0.8, gd)
                    out[0] = ground * 1.05 + (skyR - ground * 1.05) * s
                    out[1] = ground * 0.9 + (skyG - ground * 0.9) * s
                    out[2] = ground * 0.75 + (skyB - ground * 0.75) * s
                }
                STUDIO -> {
                    // a dim room, lighter above; a large soft box in front, a tall one at the side, a strip light above
                    val az = ex / (1 + abs(rz))
                    fun box(cx: Double, cy: Double, hw: Double, hh: Double): Double {
                        val k = soft * 0.6
                        return smoothstep(hw + k, hw - k * 0.3, abs(az - cx)) * smoothstep(hh + k, hh - k * 0.3, abs(elev - cy))
                    }
                    val room = 0.06 + 0.22 * smoothstep(-0.6, 0.9, elev)
                    val light = max(box(-0.12, 0.3, 0.32, 0.22) * 0.9, max(box(0.62, 0.05, 0.08, 0.5), box(0.0, 0.85, 0.6, 0.05) * 0.8))
                    val l = room + (1 - room) * light
                    out[0] = l; out[1] = l; out[2] = l
                }
                BANDS -> {
                    val t = sin(elev * PI * 5 + 0.6)
                    val l = 0.1 + 0.85 * smoothstep(-soft, soft, t) * (0.65 + 0.35 * (elev + 1) / 2)
                    out[0] = l; out[1] = l; out[2] = l
                }
                else -> {
                    // the picture itself, seen shifted along the reflection
                    val k = max(w, h) * 0.35
                    val c = sampleBilinear(envPicture!!, px + ex * k, py + ey * k, Edge.MIRROR)
                    out[0] = red(c) / 255.0; out[1] = green(c) / 255.0; out[2] = blue(c) / 255.0
                }
            }
        }

        val la = Math.toRadians(v["lightAngle"].toDouble())
        val le = Math.toRadians(v["lightHeight"].toDouble())
        val lx = cos(la) * cos(le)
        val ly = sin(la) * cos(le)
        val lz = sin(le)
        val hl = sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1))
        val hx = lx / hl
        val hy = ly / hl
        val hz = (lz + 1) / hl
        val gloss = v["gloss"] / 100.0
        val shininess = 8 + (1 - roughness).pow(2) * 300
        val shading = v["shading"] / 100.0

        val cx = w / 2.0
        val cy = h / 2.0
        val focal = max(w, h) * 1.1

        // the slope of the relief per pixel; between pixels it is interpolated
        val slopeX = FloatArray(n)
        val slopeY = FloatArray(n)
        parallelRows(h) { y ->
            fun at(xx: Int, yy: Int) = height[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                slopeX[y * w + x] = ((at(x + 1, y) - at(x - 1, y)) / 2).toFloat()
                slopeY[y * w + x] = ((at(x, y + 1) - at(x, y - 1)) / 2).toFloat()
            }
        }

        /** [a] at (x, y) in pixel coordinates (pixel centres on whole numbers), bilinear. */
        fun bilinear(a: FloatArray, x: Double, y: Double): Double {
            val xc = x.coerceIn(0.0, w - 1.0)
            val yc = y.coerceIn(0.0, h - 1.0)
            val x0 = min(w - 2, xc.toInt()).coerceAtLeast(0)
            val y0 = min(h - 2, yc.toInt()).coerceAtLeast(0)
            val x1 = min(w - 1, x0 + 1)
            val y1 = min(h - 1, y0 + 1)
            val fx = xc - x0
            val fy = yc - y0
            val top = a[y0 * w + x0] + (a[y0 * w + x1] - a[y0 * w + x0]) * fx
            val bottom = a[y1 * w + x0] + (a[y1 * w + x1] - a[y1 * w + x0]) * fx
            return top + (bottom - top) * fy
        }

        /** The metal at (x, y) – pixel coordinates, [c] the pixel's own color – written into [res] as r, g, b 0..255. */
        fun shade(x: Double, y: Double, c: Int, env: DoubleArray, f0: DoubleArray, res: DoubleArray) {
            val gx = bilinear(slopeX, x, y)
            val gy = bilinear(slopeY, x, y)
            val nl = sqrt(gx * gx + gy * gy + 1)
            val nx = -gx / nl
            val ny = -gy / nl
            val nz = 1 / nl
            // reflect the view direction at the normal; seen in perspective, so flat parts have a gradient too
            val vl = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + focal * focal)
            val vx = (x - cx) / vl
            val vy = (y - cy) / vl
            val vz = -focal / vl
            val vn = vx * nx + vy * ny + vz * nz
            val rx = vx - 2 * vn * nx
            val ry = vy - 2 * vn * ny
            val rz = vz - 2 * vn * nz
            environment(rx, ry, rz, x, y, env)

            // the metal's color, maybe from the picture or by the height (temper colors)
            if (metal == TEMPERED) {
                val t = (bilinear(smoothed, x, y) * (TEMPER.size - 1)).coerceIn(0.0, TEMPER.size - 1.0)
                val k = min(TEMPER.size - 2, t.toInt())
                val f = t - k
                for (ch in 0..2) {
                    val a = (TEMPER[k] shr (16 - 8 * ch)) and 0xFF
                    val b = (TEMPER[k + 1] shr (16 - 8 * ch)) and 0xFF
                    f0[ch] = (a + (b - a) * f) / 255.0
                }
            } else for (ch in 0..2) f0[ch] = f0Base[ch]
            if (imageColor > 0) {
                val m = max(1, max(red(c), max(green(c), blue(c)))).toDouble()
                f0[0] += (red(c) / m * 0.95 - f0[0]) * imageColor
                f0[1] += (green(c) / m * 0.95 - f0[1]) * imageColor
                f0[2] += (blue(c) / m * 0.95 - f0[2]) * imageColor
            }

            // Fresnel: grazing angles reflect more and whiter
            val fres = (1 + vn).coerceIn(0.0, 1.0).pow(5)
            val lambert = max(0.0, nx * lx + ny * ly + nz * lz) / lz
            val diffuse = 1 + shading * (min(1.3, lambert) - 1)
            val spec = gloss * max(0.0, nx * hx + ny * hy + nz * hz).pow(shininess) * (1 + (1 - roughness) * 2)
            for (ch in 0..2) {
                val f = f0[ch] + (1 - f0[ch]) * fres
                res[ch] = (env[ch] * f * diffuse + spec * (0.6 * f0[ch] + 0.4)) * 255
            }
        }

        // first every pixel once, at its centre
        val first = FloatArray(n * 3)
        parallelRows(h) { y ->
            val env = DoubleArray(3)
            val f0 = DoubleArray(3)
            val res = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                shade(x.toDouble(), y.toDouble(), src.data[i], env, f0, res)
                for (ch in 0..2) first[i * 3 + ch] = res[ch].coerceIn(0.0, 255.0).toFloat()
            }
        }

        // then, where a neighbour differs a lot, several times inside the pixel
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; 3 -> 8; else -> 1 }
        val threshold = v["aaThreshold"] / 100f * 255f
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val env = DoubleArray(3)
            val f0 = DoubleArray(3)
            val res = DoubleArray(3)
            val sum = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                var rough = false
                if (sub > 1) {
                    for ((dx, dy) in NEIGHBOURS) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx !in 0 until w || yy !in 0 until h) continue
                        val j = yy * w + xx
                        if (abs(first[i * 3] - first[j * 3]) > threshold || abs(first[i * 3 + 1] - first[j * 3 + 1]) > threshold ||
                            abs(first[i * 3 + 2] - first[j * 3 + 2]) > threshold
                        ) { rough = true; break }
                    }
                }
                if (rough) {
                    sum.fill(0.0)
                    for (sy in 0 until sub) for (sx in 0 until sub) {
                        shade(x + (sx + 0.5) / sub - 0.5, y + (sy + 0.5) / sub - 0.5, c, env, f0, res)
                        for (ch in 0..2) sum[ch] += res[ch].coerceIn(0.0, 255.0)
                    }
                    for (ch in 0..2) res[ch] = sum[ch] / (sub * sub)
                } else for (ch in 0..2) res[ch] = first[i * 3 + ch].toDouble()
                val r = red(c) + (res[0] - red(c)) * amount
                val g = green(c) + (res[1] - green(c)) * amount
                val b = blue(c) + (res[2] - blue(c)) * amount
                out.data[i] = argb(alpha(c), r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
            }
        }
        return out
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
