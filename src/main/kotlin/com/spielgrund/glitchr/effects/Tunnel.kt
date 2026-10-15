package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * A VJ tunnel: the picture is laid on the inside walls of an endless tube and seen down
 * its length, so it repeats deeper and deeper towards a vanishing point. The tube is round
 * or a polygon (a triangle, square …) with flat walls. "Fly" moves through it – one full
 * step looks exactly like the start, so animating it gives an endless zoom.
 */
object Tunnel : Effect("tunnel", "Tunnel", "An endless tunnel lined with the picture – round or a triangle, square … – to fly through") {
    private val shapes = listOf("Round", "Polygon")

    override val params = listOf(
        Param.Choice("shape", "Shape", shapes, 0, "Round tube, or flat walls in a polygon"),
        Param.Slider("corners", "Corners", 3, 12, 3, tip = "Polygon: 3 = triangle, 4 = square …"),
        Param.Toggle("perWall", "One picture per wall", true, "Polygon: every wall shows the picture; off: it runs around all walls"),
        Param.Slider("depth", "Depth", 5, 1000, 100, " %", "How quickly the tunnel recedes: more = the picture's repeats come closer together"),
        Param.Slider("repeatAround", "Repeats around", 1, 32, 1, tip = "How often the picture runs around (per wall for polygons with one picture per wall)"),
        Param.Slider("repeatDepth", "Repeats in depth", 1, 32, 1, tip = "How many times the picture repeats per depth step"),
        Param.Slider("fly", "Fly", -1000000, 1000000, 0, " %", "Moves through the tunnel; 100 % = one picture further, which looks the same again – animate it for an endless zoom", decimals = 2),
        Param.Slider("rotation", "Rotation", -180000, 180000, 0, "°", "Turns the whole tunnel", decimals = 2),
        Param.Slider("offset", "Texture offset", -10000, 10000, 0, " %", "Slides the picture around the walls; 100 % = once around", decimals = 2),
        Param.Slider("twist", "Twist", -180000, 180000, 0, "°", "Turns the picture further with every depth step: a spiral tunnel", decimals = 2),
        Param.Toggle("mirror", "Hide seams", false, "Runs the picture forth and mirrored back, so its edges meet without a seam"),
        Param.Slider("fade", "Depth fog", 0, 100, 40, " %", "Fades into the fog color towards the far end, where the repeats get too fine to see"),
        Param.Color("fogColor", "Fog color", 0x000000),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "Vanishing point, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "Vanishing point, from the middle of the canvas", decimals = 1),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "4 × 4"), 1, "Samples per pixel – the far end squeezes the picture into a few pixels"),
    )

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val polygon = v["shape"] == 1
        val n = v["corners"]
        val perWall = polygon && v.bool("perWall")
        val scale = min(w, h) / 2.0 * v["depth"] / 100.0
        val repeatAround = v["repeatAround"].toDouble()
        val repeatDepth = v["repeatDepth"].toDouble()
        val fly = v["fly"] / 10000.0
        val rotation = Math.toRadians(v["rotation"] / 100.0)
        val offset = v["offset"] / 10000.0
        val twist = v["twist"] / 100.0 / 360.0
        val mirror = v.bool("mirror")
        val fog = v["fade"] / 100.0
        val fogColor = v["fogColor"] or 0xFF000000.toInt()
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        val sector = 2 * PI / n
        val halfSector = PI / n
        val tanHalf = tan(halfSector)

        /** 0..1 within a repeat; with [mirror] forth and back, so the ends meet. */
        fun wrap(t: Double): Double {
            var s = t - floor(t)
            if (mirror) s = 1 - abs(2 * s - 1)
            return s
        }

        /** Depth (in tunnel steps) and the picture at canvas point ([x], [y]). */
        fun sample(x: Double, y: Double, depthOut: DoubleArray): Int {
            val dx = x - cx
            val dy = y - cy
            val r = hypot(dx, dy)
            // angle clockwise from the top, turned with the tunnel
            var a = atan2(dy, dx) + PI / 2 - rotation
            a -= 2 * PI * floor(a / (2 * PI))
            val around: Double
            val dist: Double
            if (polygon) {
                // which wall, and where along it: flat walls get an even texture
                val k = floor(a / sector)
                val local = a - k * sector - halfSector
                dist = r * cos(local)
                val along = 0.5 + 0.5 * tan(local) / tanHalf
                around = if (perWall) along else (k + along) / n
            } else {
                dist = r
                around = a / (2 * PI)
            }
            // perspective: a wall at distance dist from the axis is z steps deep
            val z = scale / max(dist, 1e-6)
            depthOut[0] = z
            val u = wrap((around + offset + twist * z) * repeatAround)
            val vv = wrap((z + fly) * repeatDepth)
            return sampleBilinear(src, u * w, vv * h, Edge.WRAP)
        }

        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val c = IntArray(sub * sub)
            val depth = DoubleArray(1)
            for (x in 0 until w) {
                var zSum = 0.0
                for (s in c.indices) {
                    c[s] = sample(x + (s % sub + 0.5) / sub, y + (s / sub + 0.5) / sub, depth)
                    zSum += depth[0]
                }
                var color = averageArgb(c)
                if (fog > 0) {
                    // fog thickens with depth; the first step stays clear
                    val z = zSum / c.size
                    val f = 1 - exp(-max(0.0, z - 1) * fog * fog * 1.5)
                    color = lerpArgb(color, fogColor, f.toFloat())
                }
                out.data[y * w + x] = color
            }
        }
        return out
    }
}
