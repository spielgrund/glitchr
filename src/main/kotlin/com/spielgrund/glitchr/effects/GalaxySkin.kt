package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A galaxy skin, as on the rare skins in games: the shapes (alpha, bright or dark areas) become
 * windows into space – a nebula of drifting colored clouds and layers of stars, some with
 * four-pointed flares – that doesn't sit on the shapes but stays put in the picture. The camera
 * settings move the space the "wrong" way: panned, turned and zoomed against the view, its layers
 * by different amounts (parallax), so it slides and spins behind the shapes. The shapes' own
 * shading can stay on it, and a glowing rim runs along their edges.
 */
object GalaxySkin : Effect("galaxyskin", "Galaxy skin", "The shapes as windows into space – a nebula and layers of stars fixed in the picture, sliding and turning against the camera") {
    /** Color schemes: deep space, nebula, second nebula, its hot wisps, rim. */
    private val schemes = listOf(
        "Galaxy" to intArrayOf(0x05030F, 0x6A2CC8, 0x1E8CE8, 0xFF4FA8, 0x9FD8FF),
        "Nebula red" to intArrayOf(0x0A0204, 0xB01E3C, 0xE8641E, 0xFFC86A, 0xFFB28A),
        "Aurora" to intArrayOf(0x020A0A, 0x12A88A, 0x2E6CE0, 0x9AFF6A, 0xA8FFE0),
        "Deep blue" to intArrayOf(0x01030C, 0x123C9E, 0x0E8AC8, 0x8AE8FF, 0x7FC8FF),
        "Gold" to intArrayOf(0x070402, 0x8A4A0E, 0xC8902A, 0xFFE8A0, 0xFFD27A),
        "Toxic" to intArrayOf(0x020802, 0x2E8A12, 0x8ACC1E, 0xEAFF6A, 0xC8FF8A),
        "Candy" to intArrayOf(0x0A0310, 0xE83AB8, 0x3AC8E8, 0xFFF06A, 0xFFB8F0),
        "Ice" to intArrayOf(0x02060C, 0x5A8AB8, 0xA8D8F0, 0xFFFFFF, 0xDFF4FF),
        "Custom" to intArrayOf(0, 0, 0, 0, 0),
    )
    private val CUSTOM = schemes.size - 1

    override val params = listOf(
        Param.Choice("source", "Shapes from", shapeSources, 0, "What becomes space: the opaque parts (if the picture has no transparency: the bright ones), or the bright or dark areas"),
        Param.Slider("threshold", "Threshold", 0, 100, 50, " %", "For bright or dark areas: from which brightness on"),
        Param.Heading("cameraHeading", "Camera"),
        Param.Slider("panX", "Pan X", -20000, 20000, 0, " %", "The camera moving, in canvas widths: space slides against it, the far layers less – animate it", decimals = 2),
        Param.Slider("panY", "Pan Y", -20000, 20000, 0, " %", "In canvas heights", decimals = 2),
        Param.Slider("turn", "Turn", -36000, 36000, 0, "°", "The camera turning: space spins the other way", decimals = 2),
        Param.Slider("zoom", "Zoom", 25, 400, 100, " %", "The camera moving in: space grows, the near layers more"),
        Param.Slider("parallax", "Parallax", 0, 200, 100, " %", "How differently the near and far layers move"),
        Param.Slider("time", "Time", 0, 10000, 0, "", "The stars twinkle, the nebula drifts – animate it", decimals = 2),
        Param.Heading("spaceHeading", "Space"),
        Param.Slider("scale", "Scale", 10, 1000, 100, " %", "Size of the nebula and the spacing of the stars"),
        Param.Slider("nebula", "Nebula", 0, 100, 70, " %", "Colored clouds of gas"),
        Param.Choice("scheme", "Colors", schemes.map { it.first }, 0, "Custom: the colors below"),
        Param.Color("deep", "Deep space", 0x05030F).shownWhen("scheme", CUSTOM),
        Param.Color("nebula1", "Nebula", 0x6A2CC8).shownWhen("scheme", CUSTOM),
        Param.Color("nebula2", "Nebula, second", 0x1E8CE8).shownWhen("scheme", CUSTOM),
        Param.Color("nebula3", "Nebula, hot", 0xFF4FA8, "Its brightest wisps").shownWhen("scheme", CUSTOM),
        Param.Slider("stars", "Stars", 0, 100, 60, " %", "How many"),
        Param.Slider("starSize", "Star size", 20, 400, 100, " %"),
        Param.Slider("flares", "Flares", 0, 100, 50, " %", "Four-pointed spikes on the brightest stars"),
        Param.Heading("lookHeading", "Look"),
        Param.Slider("shading", "Shading", 0, 100, 35, " %", "How much of the shapes' own light and shadow stays on the space"),
        Param.Color("rimColor", "Rim", 0x9FD8FF).shownWhen("scheme", CUSTOM),
        Param.Slider("rim", "Rim glow", 0, 300, 100, " %", "A glow along the shapes' edges, inside them"),
        Param.Slider("rimWidth", "Rim width", 50, 10000, 800, " px", decimals = 2),
    )

    /** A random number 0..1 for a grid cell and a [salt], well mixed. */
    private fun hash(x: Long, y: Long, salt: Long): Double {
        var h = x * -0x61c8864680b583ebL + y * -0x3d4d51c2d82b14b1L + salt * 0x27D4EB2F165667C5L
        h = (h xor (h ushr 31)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 29)
        return (h ushr 11).toDouble() / (1L shl 53)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val size = min(w, h).toDouble()
        val raw = shapeMask(src, v["source"], v["threshold"] / 100.0 * 255)
        // the bright or dark areas a little smoothed, so specks don't each become a window
        val mask = if (v["source"] == 0) raw else Blur.gauss(raw, w, h, max(0.5, size * 0.002))
        val inside = BooleanArray(w * h) { mask[it] >= 0.5f }
        // how far inside a shape each pixel is, for the rim
        val (depth, _) = distanceTransform(BooleanArray(w * h) { !inside[it] }, w, h)

        val panX = v["panX"] / 10000.0 * w
        val panY = v["panY"] / 10000.0 * h
        val turn = Math.toRadians(v["turn"] / 100.0)
        val zoom = v["zoom"] / 100.0
        val parallax = v["parallax"] / 100.0
        val time = v["time"] / 100.0
        val scale = size * 0.25 * v["scale"] / 100.0
        val nebula = v["nebula"] / 100.0
        val stars = v["stars"] / 100.0
        val starSize = v["starSize"] / 100.0
        val flares = v["flares"] / 100.0
        val shading = v["shading"] / 100.0
        val rim = v["rim"] / 100.0
        val rimWidth = max(0.5, v["rimWidth"] / 100.0)
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        fun rgb(c: Int) = doubleArrayOf(ch(c, 16), ch(c, 8), ch(c, 0))
        val scheme = v["scheme"].coerceIn(0, CUSTOM)
        val colors = if (scheme == CUSTOM) intArrayOf(v["deep"], v["nebula1"], v["nebula2"], v["nebula3"], v["rimColor"]) else schemes[scheme].second
        val deep = rgb(colors[0])
        val neb1 = rgb(colors[1])
        val neb2 = rgb(colors[2])
        val neb3 = rgb(colors[3])
        val rimColor = rgb(colors[4])

        /**
         * A pixel's place in a layer of space at [depthOfLayer] (0 far, 1 near): the camera's pan, turn and zoom undone
         * against it – the near layers moved more, the far ones less (parallax).
         */
        fun layer(x: Double, y: Double, depthOfLayer: Double, out: DoubleArray) {
            val k = 1 + (depthOfLayer - 0.5) * parallax
            val dx = x - w / 2.0
            val dy = y - h / 2.0
            // turned and zoomed around the middle, against the camera
            val a = -turn * k
            val z = zoom.pow(k)
            val rx = (dx * cos(a) - dy * sin(a)) / z
            val ry = (dx * sin(a) + dy * cos(a)) / z
            out[0] = rx + panX * k
            out[1] = ry + panY * k
        }

        // the star layers: far and many and faint, near and few and big
        val layers = arrayOf(
            doubleArrayOf(0.15, 0.06, 0.7, 0.35), // depth, cell (of the scale), size, brightness
            doubleArrayOf(0.5, 0.11, 1.0, 0.7),
            doubleArrayOf(0.9, 0.22, 1.6, 1.0),
        )

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val q = DoubleArray(2)
            val col = DoubleArray(3)
            for (x in 0 until w) {
                val i = y * w + x
                val m = mask[i].toDouble().coerceIn(0.0, 1.0)
                val p = src.data[i]
                if (m <= 0) { out.data[i] = p; continue }
                // the nebula: drifting, warped clouds in three colors over deep space, in the farthest layer
                layer(x + 0.5, y + 0.5, 0.0, q)
                val nx = q[0] / scale
                val ny = q[1] / scale
                val warp = noise.fbm(nx * 0.7 + time * 0.03, ny * 0.7 - 4.0, 3)
                val cloud = (noise.fbm(nx + warp * 1.4 + 11.0, ny - warp * 1.4 + time * 0.02, 5) * 0.5 + 0.5)
                val tint = noise.fbm(nx * 0.5 - 7.0, ny * 0.5 + 3.0, 2) * 0.5 + 0.5
                val dense = smoothstep(0.3, 0.78, cloud) * nebula
                val hot = smoothstep(0.62, 0.92, cloud) * nebula
                for (k in 0..2) {
                    val gas = neb1[k] + (neb2[k] - neb1[k]) * tint
                    col[k] = deep[k] + (gas - deep[k]) * dense + neb3[k] * hot * 0.8
                }
                // the stars, layer by layer: one in some cells of a grid, at a random place, of random brightness;
                // the brightest with flares
                for ((li, l) in layers.withIndex()) {
                    layer(x + 0.5, y + 0.5, l[0], q)
                    val cell = max(3.0, scale * l[1])
                    val gx = floor(q[0] / cell).toLong()
                    val gy = floor(q[1] / cell).toLong()
                    for (oy in -1..1) for (ox in -1..1) {
                        val cx = gx + ox
                        val cy = gy + oy
                        val salt = seed + li * 101L
                        if (hash(cx, cy, salt) > 0.25 + 0.7 * stars) continue
                        val sx = (cx + 0.15 + 0.7 * hash(cx, cy, salt + 1)) * cell
                        val sy = (cy + 0.15 + 0.7 * hash(cx, cy, salt + 2)) * cell
                        val mag = hash(cx, cy, salt + 3)
                        val twinkle = 0.75 + 0.25 * sin(time * 6 + mag * 300)
                        val bright = l[3] * (0.25 + 1.6 * mag.pow(4)) * twinkle
                        val dx = q[0] - sx
                        val dy = q[1] - sy
                        val r = max(0.6, size * 0.0016 * l[2] * starSize * (0.6 + 0.8 * mag))
                        val d2 = dx * dx + dy * dy
                        var light = exp(-d2 / (r * r)) * bright
                        // flares: thin spikes along the layer's axes on the brightest
                        if (flares > 0 && mag > 0.8) {
                            val len = r * 12 * flares * (mag - 0.8) / 0.2
                            light += bright * flares * (exp(-abs(dy) / (r * 0.25)) * exp(-abs(dx) / max(1e-6, len)) + exp(-abs(dx) / (r * 0.25)) * exp(-abs(dy) / max(1e-6, len))) * 0.6
                        }
                        if (light > 0.002) {
                            // a little bluer or warmer
                            val warm = hash(cx, cy, salt + 4) - 0.5
                            col[0] += light * (1 + 0.3 * warm); col[1] += light; col[2] += light * (1 - 0.3 * warm)
                        }
                    }
                }
                // the shapes' own shading on it
                val l = luma(p) / 255.0
                val shade = 1 - shading + shading * l * 1.8
                for (k in 0..2) col[k] *= shade
                // the rim: a glow along the edges, inside
                val edge = exp(-depth[i] / rimWidth) * rim
                for (k in 0..2) col[k] += rimColor[k] * edge
                // softly into the picture at the shapes' edge
                val pa = (p ushr 24) / 255.0
                fun c8(k: Int) = ((ch(p, 16 - 8 * k) * (1 - m) + min(1.0, col[k]) * m) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((max(pa, m) * 255 + 0.5).toInt() shl 24) or (c8(0) shl 16) or (c8(1) shl 8) or c8(2)
            }
        }
        return out
    }
}
