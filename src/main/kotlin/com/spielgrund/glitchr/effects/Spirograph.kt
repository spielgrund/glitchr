package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A spirograph, like the plastic stencils: a toothed wheel rolls inside (or around) a
 * toothed ring, a pen in one of the wheel's holes draws the curve (hypotrochoid, or
 * epitrochoid outside). With whole tooth counts the curve closes by itself once the
 * wheel is back where it started – after wheel / gcd(ring, wheel) turns around the ring.
 *
 * Several passes can be drawn on top of each other, each started a bit further round
 * and optionally with the pen in another hole, as one would with the stencil.
 * Only used as a generator.
 */
object Spirograph : Effect("spirograph", "Spirograph", "A wheel rolls inside a toothed ring, a pen in a hole draws spiral patterns") {
    override val params = listOf(
        Param.Heading("gearHeading", "Stencil"),
        Param.Choice("mode", "Wheel rolls", listOf("Inside the ring", "Around the outside of the ring")),
        Param.Slider("ring", "Ring teeth", 12, 240, 96, tip = "Size of the fixed ring (or the disc the wheel rolls around)"),
        Param.Slider("wheel", "Wheel teeth", 3, 200, 63, tip = "Size of the rolling wheel. The smaller the common divisor with the ring, the more loops"),
        Param.Slider("hole", "Hole", 0, 150, 90, " %", "Where the pen sits in the wheel: 0 % in the middle, 100 % at the edge, above that outside (loops)"),
        Param.Slider("portion", "Drawn", 1, 100, 100, " %", "Draw only part of the closed curve"),
        Param.Heading("passHeading", "Passes"),
        Param.Slider("passes", "Passes", 1, 24, 1, tip = "Several curves over each other, as if drawn several times with the stencil"),
        Param.Slider("passTurn", "Offset per pass", 0, 36000, 1000, "°", decimals = 2, tip = "How much further rotated the next pass starts"),
        Param.Slider("passHole", "Hole per pass", -50, 50, 0, " %", "The pen moves to a different hole from pass to pass"),
        Param.Heading("styleHeading", "Display"),
        Param.Slider("size", "Size", 5, 150, 92, " %", "Radius relative to half the shorter side"),
        Param.Slider("rotation", "Rotation", 0, 359, 0, "°"),
        Param.Slider("centerX", "Center X", 0, 100, 50, " %"),
        Param.Slider("centerY", "Center Y", 0, 100, 50, " %"),
        Param.Slider("lineWidth", "Line width", 1, 200, 12, " px", decimals = 1),
        Param.Slider("lineOpacity", "Line opacity", 5, 100, 90, " %", "Lower: the passes show through each other"),
        Param.Choice(
            "colorMode", "Colors",
            listOf("One color", "Gradient per pass", "Rainbow per pass", "Rainbow along the curve", "Gradient along the curve"),
        ),
        Param.Color("color1", "Color 1", 0x2A6FDB),
        Param.Color("color2", "Color 2", 0xE8457A),
        Param.Color("background", "Background", 0xFAF7F0),
        Param.Toggle("transparent", "Transparent background", false),
    )

    override val random = false

    /** Longest curve in points, so extreme tooth counts stay fast. */
    private const val MAX_POINTS = 600_000

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        if (!v.bool("transparent")) data.fill(v["background"] or 0xFF000000.toInt())
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.stroke = BasicStroke(v["lineWidth"] / 10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, v["lineOpacity"] / 100f)
        g.translate(w * v["centerX"] / 100.0, h * v["centerY"] / 100.0)
        g.rotate(v["rotation"] * PI / 180)

        val inside = v["mode"] == 0
        val ring = v["ring"].toDouble()
        val wheel = v["wheel"].toDouble()
        val passes = v["passes"]
        val holes = DoubleArray(passes) { max(0.0, (v["hole"] + it * v["passHole"]) / 100.0) }
        // scale: the widest pass fills the chosen radius
        val reach = holes.maxOf { hole -> (if (inside) abs(ring - wheel) else ring + wheel) + hole * wheel }
        val scale = min(w, h) / 2.0 * v["size"] / 100.0 / max(1e-9, reach)

        // the curve closes after the wheel has gone round the ring this many times
        val loops = v["wheel"] / gcd(v["ring"], v["wheel"])
        val tEnd = 2 * PI * loops * v["portion"] / 100.0
        // enough points for the fast inner turning of the wheel
        val perLoop = 2 * PI * max(1.0, (ring + wheel) / wheel)
        val points = min(MAX_POINTS, ceil(tEnd / (2 * PI) * perLoop * 60).toInt()).coerceAtLeast(200)

        val colorMode = v["colorMode"]
        val c1 = Color(v["color1"])
        val c2 = Color(v["color2"])
        for (pass in 0 until passes) {
            val start = pass * v["passTurn"] / 100.0 * PI / 180
            val d = holes[pass] * wheel
            val passT = if (passes <= 1) 0f else pass.toFloat() / (passes - 1)

            fun point(t: Double): Pair<Double, Double> {
                val x: Double
                val y: Double
                if (inside) {
                    val k = (ring - wheel) / wheel
                    x = (ring - wheel) * cos(t) + d * cos(k * t)
                    y = (ring - wheel) * sin(t) - d * sin(k * t)
                } else {
                    val k = (ring + wheel) / wheel
                    x = (ring + wheel) * cos(t) - d * cos(k * t)
                    y = (ring + wheel) * sin(t) - d * sin(k * t)
                }
                // turned by the pass' start, scaled to the canvas
                return (x * cos(start) - y * sin(start)) * scale to (x * sin(start) + y * cos(start)) * scale
            }

            if (colorMode < 3) {
                g.color = when (colorMode) {
                    1 -> mix(c1, c2, passT)
                    2 -> Color(Color.HSBtoRGB(passT * 0.85f, 0.8f, 0.95f))
                    else -> c1
                }
                g.draw(path(0, points, points, tEnd, ::point))
            } else {
                // color changing along the curve: drawn in short pieces, with flat ends so
                // the joints don't show as dots when the lines are translucent
                val piece = 64
                g.stroke = BasicStroke(v["lineWidth"] / 10f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                var i = 0
                while (i < points) {
                    val f = i.toFloat() / points
                    g.color = if (colorMode == 3) Color(Color.HSBtoRGB(f, 0.8f, 0.95f)) else mix(c1, c2, if (f < 0.5f) f * 2 else 2 - f * 2)
                    g.draw(path(i, min(points, i + piece), points, tEnd, ::point))
                    i += piece
                }
            }
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /** The curve from point [from] to [to] of [points] along t = 0..[tEnd]. */
    private fun path(from: Int, to: Int, points: Int, tEnd: Double, point: (Double) -> Pair<Double, Double>) =
        Path2D.Double().apply {
            for (i in from..to) {
                val (x, y) = point(tEnd * i / points)
                if (i == from) moveTo(x, y) else lineTo(x, y)
            }
        }

    private fun mix(a: Color, b: Color, t: Float) = Color(
        (a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(), (a.blue + (b.blue - a.blue) * t).toInt(),
    )

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
