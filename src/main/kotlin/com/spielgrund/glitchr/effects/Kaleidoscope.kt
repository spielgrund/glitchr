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
object Kaleidoscope : Effect("kaleido", "Kaleidoscope", "Folds the picture several times as in a kaleidoscope") {
    override val params = listOf(
        Param.Slider("segments", "Folds", 2, 32, 6, tip = "How many segments the circle is divided into"),
        Param.Slider("rotation", "Rotation", 0, 359, 0, "°", "Rotates the whole kaleidoscope"),
        Param.Slider("sourceAngle", "Source angle", 0, 359, 0, "°", "Which part of the picture is folded into the segments"),
        Param.Slider("offsetX", "Offset X", -100, 100, 0, " %", "Shifts the part that is mirrored"),
        Param.Slider("offsetY", "Offset Y", -100, 100, 0, " %"),
        Param.Slider("centerX", "Center X", 0, 100, 50, " %"),
        Param.Slider("centerY", "Center Y", 0, 100, 50, " %"),
        Param.Slider("zoom", "Zoom", 10, 400, 100, " %"),
        Param.Slider("levels", "Levels", 1, 6, 1, tip = "Fold within the fold: how often every segment is folded once more. 1 = simple kaleidoscope"),
        Param.Slider("innerSegments", "Inner folds", 2, 32, 6, tip = "Segments of every inner fold (from level 2)"),
        Param.Slider("innerDistance", "Inner distance", 0, 100, 35, " %", "How far the center of the inner fold lies from the outer one (relative to half the shorter side of the picture)"),
        Param.Slider("innerRotation", "Inner rotation", 0, 359, 0, "°", "Rotates every inner fold against the previous one"),
        Param.Slider("innerScale", "Inner scale", 25, 400, 100, " %", "Enlarges (above 100 %) or shrinks every inner level"),
        Param.Toggle("mirror", "Mirror segments", true, "Off: all segments rotated the same instead of alternately mirrored – with visible seams"),
        Param.Choice("edge", "Edge", Edge.labels, default = Edge.MIRROR.ordinal),
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
