package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The camera moves a little, and the picture shows it in 2.5D: after the guessed depth,
 * near things shift more than far ones, and they cover what lies behind them. The focus
 * depth stays still. One moved view, several views along a line laid over each other
 * (wiggle), views around a circle (orbit), or two views as a red/cyan anaglyph.
 *
 * Every view is found backwards: for an output pixel the shift is searched along the
 * camera's direction, from the nearest possible depth to the farthest, and the first
 * place whose own shift brings it here wins – so the nearest surface covers the others.
 */
object Wiggle : Effect("wiggle", "Kamera wackeln", "2.5D: die Kamera bewegt sich, Nahes verschiebt sich mehr als Fernes – Wackelbild, Kreisen oder Rot/Cyan") {
    private val modes = listOf("Versetzt", "Wackeln", "Kreisen", "Anaglyph (Rot/Cyan)")
    private const val SHIFTED = 0
    private const val WIGGLE = 1
    private const val ORBIT = 2
    private const val ANAGLYPH = 3

    override val params = listOf(
        Param.Heading("cameraHeading", "Kamera"),
        Param.Choice(
            "mode", "Art", modes, WIGGLE,
            tip = "Versetzt: eine verschobene Ansicht · Wackeln: mehrere Ansichten entlang einer Linie übereinander · " +
                "Kreisen: Ansichten rund um einen Kreis · Anaglyph: zwei Ansichten für eine Rot/Cyan-Brille",
        ),
        Param.Slider("camX", "Kamera X", -500, 500, 24, " px", "Wie weit und wohin sich die Kamera bewegt – auch mit dem Anfasser im Bild zu ziehen"),
        Param.Slider("camY", "Kamera Y", -500, 500, 0, " px"),
        Param.Slider("focus", "Fokusebene", 0, 100, 50, " %", "Diese Tiefe bleibt stehen (0 % fern, 100 % nah); davor und dahinter bewegt es sich gegenläufig"),
        Param.Slider("views", "Ansichten", 2, 32, 6, tip = "Wackeln und Kreisen: so viele Ansichten werden übereinandergelegt"),
        Param.Slider("trail", "Nachzieher", 0, 100, 0, " %", "Wackeln: die Ansichten zur Mitte hin zählen mehr – der Rest wird zu Geisterbildern"),
    ) + Depth.params

    override val random = false

    override val canvasHandle = CanvasHandle("camX", "camY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val depth = Depth.estimate(src, v)
        if (v.bool("depthShow")) return Depth.show(src, depth)
        // the camera's move: its length is how far the nearest and farthest things shift, its direction the way
        val shift = kotlin.math.hypot(v["camX"].toDouble(), v["camY"].toDouble())
        val angle = kotlin.math.atan2(v["camY"].toDouble(), v["camX"].toDouble())
        val focus = v["focus"] / 100.0
        val mode = v["mode"]
        val count = v["views"]
        // the camera positions: a direction and a signed amount each, with a weight
        val views: List<Triple<Double, Double, Double>> = when (mode) {
            SHIFTED -> listOf(Triple(cos(angle), sin(angle), 1.0))
            ORBIT -> List(count) { k ->
                val a = angle + 2 * PI * k / count
                Triple(cos(a), sin(a), 1.0)
            }
            ANAGLYPH -> listOf(Triple(-cos(angle), -sin(angle), 1.0), Triple(cos(angle), sin(angle), 1.0))
            else -> {
                val trail = v["trail"] / 100.0
                List(count) { k ->
                    val c = k.toDouble() / (count - 1) * 2 - 1
                    Triple(cos(angle) * c, sin(angle) * c, 1 - trail * abs(c))
                }
            }
        }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                if (mode == ANAGLYPH) {
                    val left = view(src, depth, x + 0.5, y + 0.5, views[0].first, views[0].second, shift, focus)
                    val right = view(src, depth, x + 0.5, y + 0.5, views[1].first, views[1].second, shift, focus)
                    out.data[i] = argb(alpha(src.data[i]), red(left), green(right), blue(right))
                    continue
                }
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
                for ((dx, dy, weight) in views) {
                    val c = view(src, depth, x + 0.5, y + 0.5, dx, dy, shift, focus)
                    val ca = alpha(c) * weight
                    a += ca; r += red(c) * ca; g += green(c) * ca; b += blue(c) * ca; total += weight
                }
                out.data[i] = if (a <= 0) 0 else argb((a / total).roundToInt(), (r / a).roundToInt(), (g / a).roundToInt(), (b / a).roundToInt())
            }
        }
        return out
    }

    /**
     * The color seen at ([qx], [qy]) when the camera moves by ([dx], [dy]) · [shift]: every
     * place p moves by (depth(p) − focus) · shift along it. Searched along the line from
     * the nearest possible shift to the farthest, the first p that lands here is taken.
     */
    private fun view(src: Pixels, depth: FloatArray, qx: Double, qy: Double, dx: Double, dy: Double, shift: Double, focus: Double): Int {
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-9 || shift <= 0) return sampleBilinear(src, qx, qy, Edge.CLAMP)
        val ux = dx / len
        val uy = dy / len
        val amount = shift * len
        // the shift of a place along the direction, and the range it can take
        fun moveAt(t: Double): Double = (depthAt(depth, src.width, src.height, qx - ux * t, qy - uy * t) - focus) * amount
        val near = (1 - focus) * amount
        val far = -focus * amount
        val step = if (near >= far) -0.5 else 0.5
        var t = near
        var prev = moveAt(t) - t
        var best = t
        var bestGap = abs(prev)
        val steps = (abs(near - far) / 0.5).toInt() + 1
        for (k in 1..steps) {
            val nt = t + step
            val f = moveAt(nt) - nt
            if (abs(f) < bestGap) { bestGap = abs(f); best = nt }
            // it lands here between t and nt: take the crossing
            if ((prev <= 0 && f >= 0) || (prev >= 0 && f <= 0)) {
                val s = if (abs(f - prev) < 1e-9) 0.0 else prev / (prev - f)
                best = t + (nt - t) * s
                break
            }
            t = nt
            prev = f
        }
        return sampleBilinear(src, qx - ux * best, qy - uy * best, Edge.CLAMP)
    }

    private fun depthAt(d: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
        val cx = (x - 0.5).coerceIn(0.0, w - 1.0)
        val cy = (y - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = cx - x0
        val fy = cy - y0
        return (d[y0 * w + x0] * (1 - fx) + d[y0 * w + x1] * fx) * (1 - fy) + (d[y1 * w + x0] * (1 - fx) + d[y1 * w + x1] * fx) * fy
    }
}
