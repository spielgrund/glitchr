package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rays of light shooting out of a point, like sunbeams through haze: either out of the bright parts
 * of the picture, or from a light behind the picture at the point that shines through its bright
 * parts while the dark ones cast long shadows. The rays fade over their length and can be broken up
 * into single beams, which the time lets shimmer.
 */
object LightRays : Effect("lightrays", "Light rays", "Rays shooting out of a point like sunbeams through haze – from the bright parts or a light behind the picture") {
    private val sources = listOf("Bright parts", "Light behind the picture")
    private const val BEHIND = 1

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: the broken-up rays shimmer", decimals = 3),
        Param.Slider("centerX", "Point X", -50000, 50000, 0, " px", "Where the rays come from, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Point Y", -50000, 50000, -2000, " px", "Where the rays come from, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Choice("source", "Rays from", sources, 0, "Bright parts: the bright parts of the picture streak away from the point · Light behind the picture: a light at the point shines through the bright parts, the dark ones cast shadows"),
        Param.Slider("lightSize", "Light size", 1, 100, 20, " %", "Of the shorter side").shownWhen("source", BEHIND),
        Param.Slider("length", "Length", 5, 3000, 400, " px", canvasMax = true),
        Param.Slider("strength", "Strength", 0, 400, 100, " %"),
        Param.Heading("raysHeading", "Rays"),
        Param.Slider("breakup", "Breakup", 0, 100, 40, " %", "Breaks the light up into single beams"),
        Param.Slider("beams", "Beams", 2, 200, 40, "", "How many beams around the point"),
        Param.Slider("threshold", "Threshold", 0, 100, 65, " %", "From which brightness on the picture gives rays (or lets the light through)"),
        Param.Slider("knee", "Softness", 0, 100, 30, " %", "How gently it sets in around the threshold"),
        Param.Heading("colorHeading", "Color"),
        Param.Choice("color", "Color", listOf("From picture", "White", "Custom"), 0, "From picture: every part gives rays in its own color"),
        Param.Color("rayColor", "Ray color", 0xFFE3B0, "For “Custom” and the light behind the picture"),
        Param.Slider("saturation", "Saturation", 0, 300, 100, " %"),
        Param.Choice("blend", "Blend", listOf("Screen", "Add"), tip = "Screen: soft, never above white · Add: stronger, burns out sooner"),
        Param.Toggle("raysOnly", "Rays only", false, "Shows only the rays on black, e.g. to blend them yourself in the layer"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        val behind = v["source"] == BEHIND
        val rayColor = v["rayColor"]
        // what gives rays: the bright parts – or, behind the picture, the light where they let it through
        val planes = LightStreaks.brightParts(
            src, v["threshold"] / 100.0, v["knee"] / 100.0 / 2,
            if (behind) 1 else v["color"], rayColor, v["saturation"] / 100.0,
        )
        val lightR = min(w, h) * v["lightSize"] / 100.0
        val breakup = v["breakup"] / 100.0
        val ring = v["beams"] / (2 * PI)
        val t = v["time"] / 1000.0
        val noise = Noise(seed)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                var k = 1.0
                if (behind) {
                    // the light: a soft disk at the point in the ray color, let through where the picture is bright
                    val d = sqrt(dx * dx + dy * dy) / lightR
                    val disk = (1 - d * d).coerceAtLeast(0.0).pow(1.5)
                    if (disk <= 0) { for (p in planes) p[i] = 0f; continue }
                    val through = planes[0][i]
                    planes[0][i] = (through * disk * red(rayColor) / 255.0).toFloat()
                    planes[1][i] = (through * disk * green(rayColor) / 255.0).toFloat()
                    planes[2][i] = (through * disk * blue(rayColor) / 255.0).toFloat()
                }
                if (breakup > 0) {
                    // single beams: noise around the point, on a circle so it has no seam
                    val a = atan2(dy, dx)
                    val b = noise.fbm(cos(a) * ring + 3.3, sin(a) * ring + t * 0.15, 3)
                    k *= 1 - breakup + breakup * (b * 1.8 + 0.5).coerceIn(0.0, 1.0).pow(1.5)
                }
                if (k != 1.0) for (p in planes) p[i] = (p[i] * k).toFloat()
            }
        }
        val gain = v["strength"] / 100.0 * (if (behind) 0.05 else 0.04)
        val length = v["length"].toDouble()
        val light = Array(3) { ch ->
            LightStreaks.streak(planes[ch], w, h, length) { x, y, step ->
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val d = sqrt(dx * dx + dy * dy)
                // not back beyond the point
                if (d < step) null else doubleArrayOf(dx / d, dy / d)
            }.also { s -> for (i in 0 until n) s[i] = (s[i] * gain).toFloat() }
        }
        return LightStreaks.lay(src, light, v["blend"] == 1, v.bool("raysOnly"))
    }
}
