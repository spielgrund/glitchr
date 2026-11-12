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
import kotlin.math.roundToInt
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
        Param.Slider("segments", "Folds", 200, 3200, 600, tip = "How many segments the circle is divided into – in between whole numbers the last segment is cut off", decimals = 2),
        Param.Slider("rotation", "Rotation", 0, 35900, 0, "°", "Rotates the whole kaleidoscope", decimals = 2),
        Param.Slider("sourceAngle", "Source angle", 0, 35900, 0, "°", "Which part of the picture is folded into the segments", decimals = 2),
        Param.Slider("offsetX", "Offset X", -10000, 10000, 0, " %", "Shifts the part that is mirrored", decimals = 2, transform = true),
        Param.Slider("offsetY", "Offset Y", -10000, 10000, 0, " %", decimals = 2, transform = true),
        Param.Slider("centerX", "Center X", 0, 10000, 5000, " %", decimals = 2, transform = true),
        Param.Slider("centerY", "Center Y", 0, 10000, 5000, " %", decimals = 2, transform = true),
        Param.Slider("zoom", "Zoom", 1000, 40000, 10000, " %", decimals = 2, transform = true),
        Param.Slider("levels", "Levels", 100, 600, 100, tip = "Fold within the fold: how often every segment is folded once more. 1 = simple kaleidoscope – in between, the next fold fades in", decimals = 2),
        Param.Slider("innerSegments", "Inner folds", 200, 3200, 600, tip = "Segments of every inner fold (from level 2)", decimals = 2),
        Param.Slider("innerDistance", "Inner distance", 0, 10000, 3500, " %", "How far the center of the inner fold lies from the outer one (relative to half the shorter side of the picture)", decimals = 2),
        Param.Slider("innerRotation", "Inner rotation", 0, 35900, 0, "°", "Rotates every inner fold against the previous one", decimals = 2),
        Param.Slider("innerScale", "Inner scale", 2500, 40000, 10000, " %", "Enlarges (above 100 %) or shrinks every inner level", decimals = 2, transform = true),
        Param.Toggle("mirror", "Mirror segments", true, "Off: all segments rotated the same instead of alternately mirrored – with visible seams"),
        Param.Choice("edge", "Edge", Edge.labels, default = Edge.MIRROR.ordinal),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val rotation = (v["rotation"] / 100.0) * PI / 180
        val sourceAngle = (v["sourceAngle"] / 100.0) * PI / 180
        val cx = w * (v["centerX"] / 100.0) / 100.0
        val cy = h * (v["centerY"] / 100.0) / 100.0
        val sx0 = cx + w * (v["offsetX"] / 100.0) / 100.0
        val sy0 = cy + h * (v["offsetY"] / 100.0) / 100.0
        val scale = 100.0 / (v["zoom"] / 100.0)
        val mirror = v.bool("mirror")
        val edge = Edge.entries[v["edge"]]
        // between whole levels the next fold fades in
        val levelsExact = v["levels"] / 100.0
        val levelsLow = floor(levelsExact).toInt().coerceAtLeast(1)
        val levelsBlend = levelsExact - levelsLow
        val outer = 2 * PI / (v["segments"] / 100.0)
        val inner = 2 * PI / (v["innerSegments"] / 100.0)
        val innerDistance = v["innerDistance"] / 10000.0 * minOf(w, h) / 2
        val innerRotation = (v["innerRotation"] / 100.0) * PI / 180
        val innerScale = 100.0 / (v["innerScale"] / 100.0)
        val cosSource = cos(sourceAngle)
        val sinSource = sin(sourceAngle)

        /** The color at output pixel ([x], [y]) with [levels] folds within each other. */
        fun fold(x: Int, y: Int, levels: Int): Int {
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
                return sampleBilinear(src, sx0 + sx, sy0 + sy, edge)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val a = fold(x, y, levelsLow)
                out.data[y * w + x] = if (levelsBlend < 1e-6) a else {
                    val b = fold(x, y, levelsLow + 1)
                    fun mix(sh: Int) = ((a ushr sh and 0xFF) * (1 - levelsBlend) + (b ushr sh and 0xFF) * levelsBlend).roundToInt()
                    (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                }
            }
        }
        return out
    }
}
