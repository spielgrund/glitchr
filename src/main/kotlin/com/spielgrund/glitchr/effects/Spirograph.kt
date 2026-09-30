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
object Spirograph : Effect("spirograph", "Spirograph", "Ein Rad rollt in einem Zahnring, ein Stift im Loch zeichnet Spiralmuster") {
    override val params = listOf(
        Param.Heading("gearHeading", "Schablone"),
        Param.Choice("mode", "Rad rollt", listOf("Innen im Ring", "Aussen um den Ring")),
        Param.Slider("ring", "Zähne Ring", 12, 240, 96, tip = "Grösse des festen Rings (bzw. der Scheibe, um die das Rad rollt)"),
        Param.Slider("wheel", "Zähne Rad", 3, 200, 63, tip = "Grösse des rollenden Rads. Je kleiner der gemeinsame Teiler mit dem Ring, desto mehr Schlaufen"),
        Param.Slider("hole", "Loch", 0, 150, 90, " %", "Wo der Stift im Rad steckt: 0 % in der Mitte, 100 % am Rand, darüber ausserhalb (Schlaufen)"),
        Param.Slider("portion", "Gezeichnet", 1, 100, 100, " %", "Nur einen Teil der geschlossenen Kurve zeichnen"),
        Param.Heading("passHeading", "Durchgänge"),
        Param.Slider("passes", "Durchgänge", 1, 24, 1, tip = "Mehrere Kurven übereinander, wie mehrmals mit der Schablone gezeichnet"),
        Param.Slider("passTurn", "Versatz je Durchgang", 0, 36000, 1000, "°", decimals = 2, tip = "Um wie viel der nächste Durchgang weitergedreht beginnt"),
        Param.Slider("passHole", "Loch je Durchgang", -50, 50, 0, " %", "Der Stift wandert von Durchgang zu Durchgang in ein anderes Loch"),
        Param.Heading("styleHeading", "Darstellung"),
        Param.Slider("size", "Grösse", 5, 150, 92, " %", "Radius im Verhältnis zur halben kürzeren Seite"),
        Param.Slider("rotation", "Drehung", 0, 359, 0, "°"),
        Param.Slider("centerX", "Mitte X", 0, 100, 50, " %"),
        Param.Slider("centerY", "Mitte Y", 0, 100, 50, " %"),
        Param.Slider("lineWidth", "Strichstärke", 1, 200, 12, " px", decimals = 1),
        Param.Slider("lineOpacity", "Deckkraft der Linien", 5, 100, 90, " %", "Geringer: die Durchgänge scheinen durcheinander durch"),
        Param.Choice(
            "colorMode", "Farben",
            listOf("Eine Farbe", "Verlauf je Durchgang", "Regenbogen je Durchgang", "Regenbogen entlang der Kurve", "Verlauf entlang der Kurve"),
        ),
        Param.Color("color1", "Farbe 1", 0x2A6FDB),
        Param.Color("color2", "Farbe 2", 0xE8457A),
        Param.Color("background", "Hintergrund", 0xFAF7F0),
        Param.Toggle("transparent", "Hintergrund transparent", false),
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
