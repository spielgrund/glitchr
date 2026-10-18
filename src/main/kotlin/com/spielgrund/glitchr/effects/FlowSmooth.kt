package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Flow smooth: the picture streams endlessly along the drawn flow, like a flow map for
 * water, clouds or rivers. Several copies (layers) of the picture travel along the flow,
 * each a little later than the next; every copy fades in, travels, and fades out again
 * before it jumps back, while the others cover it – so the flow never jumps and one full
 * turn of the time loops seamlessly. So the whole picture doesn't pulse at once, every
 * spot gets its own point in time: segments of an adjustable size with soft or hard edges.
 */
object FlowSmooth : Effect("flowsmooth", "Flow smooth", "The picture streams endlessly and smoothly along a drawn flow – for water, clouds, rivers; loops seamlessly") {
    override val params = listOf(
        Param.Flow("strokes", "Flow direction", "Dragging in the picture draws an arrow: the picture flows in the drawing direction, without arrows everything in the base direction"),
        Param.Slider(
            "time", "Time", -360000, 360000, 0, "°",
            "Animate it: 360° = one full cycle, after which everything looks the same again – so 0° → 360° loops seamlessly", decimals = 2,
        ),
        Param.Slider("distance", "Flow distance", 1, 2000, 60, " px", "How far the picture travels in one cycle: faster flow, but longer smears", canvasMax = true),
        Param.Heading("layersHeading", "Layers and segments"),
        Param.Slider("layers", "Layers", 2, 8, 2, tip = "Copies of the picture crossfading into each other; more is calmer and softer"),
        Param.Slider("fade", "Crossfade", 5, 100, 100, " %", "How much of its cycle a copy spends fading in and out: 100 % = always fading (softest), less = longer fully visible (sharper)"),
        Param.Slider("variation", "Segment offset", 0, 100, 60, " %", "Every segment is at its own point in time, so the picture doesn't pulse as a whole"),
        Param.Slider("segment", "Segment size", 4, 2000, 160, " px", canvasMax = true),
        Param.Slider("segmentSoftness", "Segment edges", 0, 100, 100, " %", "Width of the soft mask where neighbouring segments blend: 100 % = across the whole segment, 0 % = hard borders"),
        Param.Slider("scatter", "Scatter along the flow", 0, 2000, 0, " px", "Shifts every segment by its own distance along the flow direction, so the flow doesn't look like one sheet; the segments blend at their soft edges", canvasMax = true),
        Param.Heading("flowHeading", "Flow"),
        Param.Choice("method", "Method", listOf("Follow the flow", "Direct (UV offset)"), tip = "Follow the flow: the picture travels along the curves · Direct: every point moves straight in its direction, like a UV offset"),
        Param.Slider("reach", "Reach", 5, 1000, 120, " px", "How far around itself a stroke works"),
        Param.Choice("outside", "Outside", listOf("Continue direction", "Standstill"), tip = "What happens away from the strokes"),
        Param.Choice("edge", "Edges", listOf("Hard", "Soft"), 1, "Edge of the flow area towards still parts"),
        Param.Choice(
            "outsideCanvas", "Beyond the canvas", listOf("Mirror", "Repeat", "Stretch edge"), 0,
            "What the copies take where they reach beyond the canvas. Repeat brings in the opposite side, which shows as a hard seam across the flow",
        ),
        Param.Slider("baseAngle", "Base direction", 0, 35900, 0, "°", "Direction without drawn strokes; 0° = to the right", decimals = 2),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val field = Flow.field(w, h, v)
        val time = v["time"] / 36000.0
        val distance = v["distance"].toDouble()
        val n = v["layers"]
        val fade = v["fade"] / 100.0
        val variation = v["variation"] / 100.0
        val segment = v["segment"].toDouble()
        val softness = v["segmentSoftness"] / 100.0
        val direct = v["method"] == 1
        val soft = v["edge"] == 1
        val scatter = v["scatter"].toDouble()
        val edge = when (v["outsideCanvas"]) {
            1 -> com.spielgrund.glitchr.image.Edge.WRAP
            2 -> com.spielgrund.glitchr.image.Edge.CLAMP
            else -> com.spielgrund.glitchr.image.Edge.MIRROR
        }
        val s = seed.toInt() xor (seed ushr 32).toInt()

        /** Opacity of a copy at point [p] (0..1) of its cycle: fading in, fully there, fading out. */
        fun window(p: Double): Double {
            val half = fade / 2
            fun ease(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            return ease(p / half) * ease((1 - p) / half)
        }

        /** Random 0..1 per segment cell; [salt] gives another set. */
        fun cellValue(i: Int, j: Int, salt: Int = 0): Double {
            var q = i * 374761393 + j * 668265263 + (s + salt) * 1442695041
            q = (q xor (q ushr 13)) * 1274126177
            return ((q xor (q ushr 16)) and 0xFFFFFF) / 16777215.0
        }

        /** How much a segment covers at a point [t] (0..1) across the cell: soft edges overlap widely, hard ones in a thin seam. */
        fun shape(t: Double): Double {
            val k = ((t - 0.5) / max(softness, 0.02) + 0.5).coerceIn(0.0, 1.0)
            return k * k * (3 - 2 * k)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val f = DoubleArray(3)
            val py = y + 0.5
            val gy = py / segment - 0.5
            val j = floor(gy).toInt()
            val ty = shape(gy - j)
            for (x in 0 until w) {
                val px = x + 0.5
                val gx = px / segment - 0.5
                val i = floor(gx).toInt()
                val tx = shape(gx - i)
                // every segment flows with its own fixed time and scatter; at their borders the finished
                // results are blended through a soft mask – nothing in between is stretched
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
                for (cell in 0 until 4) {
                    val ci = i + (cell and 1)
                    val cj = j + (cell shr 1)
                    val cover = (if (cell and 1 == 1) tx else 1 - tx) * (if (cell shr 1 == 1) ty else 1 - ty)
                    if (cover <= 1e-6) continue
                    val shift = if (variation > 0) cellValue(ci, cj) * variation else 0.0
                    val extra = if (scatter > 0) (cellValue(ci, cj, 7919) * 2 - 1) * scatter else 0.0
                    var sum = 0.0
                    for (k in 0 until n) sum += window((time + shift + k.toDouble() / n).let { it - floor(it) })
                    if (sum <= 1e-9) continue
                    for (k in 0 until n) {
                        val phase = (time + shift + k.toDouble() / n).let { it - floor(it) }
                        val weight = window(phase) / sum * cover
                        if (weight <= 0) continue
                        // centred on the middle of the cycle: when a copy is fully there it is least moved
                        val c = Flow.sample(src, field, px, py, (phase - 0.5) * distance + extra, direct, soft, f, edge)
                        // premultiplied, so transparent parts don't darken
                        val ca = (c ushr 24) * weight
                        a += ca
                        r += (c shr 16 and 0xFF) * ca
                        g += (c shr 8 and 0xFF) * ca
                        b += (c and 0xFF) * ca
                        total += weight
                    }
                }
                out.data[y * w + x] = when {
                    total <= 1e-9 -> src.data[y * w + x]
                    a <= 1e-9 -> 0
                    else -> argb(
                        min(255, (a / total + 0.5).toInt()),
                        (r / a + 0.5).toInt().coerceIn(0, 255), (g / a + 0.5).toInt().coerceIn(0, 255), (b / a + 0.5).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
        return out
    }
}
