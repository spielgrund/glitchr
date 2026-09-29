package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Folds the picture into a kaleidoscope: the circle around the center is cut into
 * equal segments that all show the same wedge of the picture, every second one
 * mirrored. The wedge can be taken from elsewhere (offset), turned and zoomed.
 *
 * With several levels the folding repeats: after a fold, each segment gets its own
 * center on the segment's middle line, around which it is folded again (fold within
 * the fold), optionally turned and scaled from level to level.
 */
object Kaleidoscope : Effect("kaleido", "Kaleidoskop", "Faltet das Bild mehrfach wie in einem Kaleidoskop") {
    override val params = listOf(
        Param.Slider("segments", "Faltungen", 2, 32, 6, tip = "In wie viele Segmente der Kreis geteilt wird"),
        Param.Slider("rotation", "Drehung", 0, 359, 0, "°", "Dreht das ganze Kaleidoskop"),
        Param.Slider("sourceAngle", "Quellwinkel", 0, 359, 0, "°", "Welcher Ausschnitt des Bilds in die Segmente gefaltet wird"),
        Param.Slider("offsetX", "Versatz X", -100, 100, 0, " %", "Verschiebt den Ausschnitt, der gespiegelt wird"),
        Param.Slider("offsetY", "Versatz Y", -100, 100, 0, " %"),
        Param.Slider("centerX", "Mitte X", 0, 100, 50, " %"),
        Param.Slider("centerY", "Mitte Y", 0, 100, 50, " %"),
        Param.Slider("zoom", "Zoom", 10, 400, 100, " %"),
        Param.Slider("levels", "Stufen", 1, 6, 1, tip = "Faltung in der Faltung: wie oft jedes Segment noch einmal gefaltet wird. 1 = einfaches Kaleidoskop"),
        Param.Slider("innerSegments", "Innere Faltungen", 2, 32, 6, tip = "Segmente jeder inneren Faltung (ab Stufe 2)"),
        Param.Slider("innerDistance", "Innerer Abstand", 0, 100, 35, " %", "Wie weit der Mittelpunkt der inneren Faltung vom äusseren entfernt liegt (bezogen auf die halbe kürzere Bildseite)"),
        Param.Slider("innerRotation", "Innere Drehung", 0, 359, 0, "°", "Dreht jede innere Faltung gegenüber der vorigen"),
        Param.Slider("innerScale", "Innere Skalierung", 25, 400, 100, " %", "Vergrössert (über 100 %) oder verkleinert jede innere Stufe"),
        Param.Toggle("mirror", "Segmente spiegeln", true, "Aus: alle Segmente gleich gedreht statt abwechselnd gespiegelt – mit sichtbaren Nähten"),
        Param.Choice("edge", "Rand", Edge.labels, default = Edge.MIRROR.ordinal),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val rotation = v["rotation"] * PI / 180
        val sourceAngle = v["sourceAngle"] * PI / 180
        val cx = w * v["centerX"] / 100.0
        val cy = h * v["centerY"] / 100.0
        val sx0 = cx + w * v["offsetX"] / 100.0
        val sy0 = cy + h * v["offsetY"] / 100.0
        val scale = 100.0 / v["zoom"]
        val mirror = v.bool("mirror")
        val edge = Edge.entries[v["edge"]]
        val levels = v["levels"]
        val outer = 2 * PI / v["segments"]
        val inner = 2 * PI / v["innerSegments"]
        val innerDistance = v["innerDistance"] / 100.0 * minOf(w, h) / 2
        val innerRotation = v["innerRotation"] * PI / 180
        val innerScale = 100.0 / v["innerScale"]
        val cosSource = cos(sourceAngle)
        val sinSource = sin(sourceAngle)

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                // point relative to the center, turned by the rotation
                var px = x + 0.5 - cx
                var py = y + 0.5 - cy
                for (level in 0 until levels) {
                    val segment = if (level == 0) outer else inner
                    val r = hypot(px, py)
                    var a = atan2(py, px) - if (level == 0) rotation else innerRotation
                    a -= floor(a / (2 * PI)) * 2 * PI
                    val k = floor(a / segment).toInt()
                    var f = a - k * segment
                    if (mirror && k % 2 == 1) f = segment - f
                    px = r * cos(f)
                    py = r * sin(f)
                    if (level < levels - 1) {
                        // the next fold turns around a center on this segment's middle line
                        px = (px - innerDistance * cos(segment / 2)) * innerScale
                        py = (py - innerDistance * sin(segment / 2)) * innerScale
                    }
                }
                // turn by the source angle, zoom and take the pixel from the (offset) source
                val sx = (px * cosSource - py * sinSource) * scale
                val sy = (px * sinSource + py * cosSource) * scale
                out.data[y * w + x] = sampleBilinear(src, sx0 + sx, sy0 + sy, edge)
            }
        }
        return out
    }
}
