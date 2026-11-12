package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Beat: fires every few beats – in the animation's tempo or its own – and pushes the picture by
 * a curve over each stretch between two hits: bigger, moved, turned, brighter or fainter by as
 * much as set, then back. The curve is one of the usual shapes (a kick that snaps and decays,
 * a pulse, a bounce, saws, square, sine …) or drawn by hand. Every other hit can go the other way.
 */
object Beat : TimeEffect("beat", "Beat", "Fires every few beats and pushes the picture along a curve – scale, move, turn, brightness, fade; the usual curve shapes or your own drawn one") {
    private val everyOptions = listOf("1/4 beat", "1/2 beat", "1 beat", "2 beats", "4 beats (a bar)", "8 beats", "16 beats")
    private val everyBeats = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0)
    val curves = listOf("Kick (snaps, decays)", "Pulse (in and out)", "Bounce", "Saw up", "Saw down", "Square", "Sine", "Ease in", "Drawn curve")
    const val DRAWN = 8

    override val params = listOf(
        Param.Heading("timingHeading", "Timing"),
        Param.Choice("tempo", "Tempo", listOf("The animation's", "Its own"), 0, "The animation's: the beats per minute of the timeline · Its own: set below"),
        Param.Slider("bpm", "Beats per minute", 300, 3000, 1200, "", decimals = 1).shownWhen("tempo", 1),
        Param.Choice("every", "Every", everyOptions, 2, "How often it fires"),
        Param.Slider("offset", "Offset", 0, 100, 0, " %", "Shifts the hits, of the stretch between two of them"),
        Param.Slider("preview", "Position", 0, 100, 0, " %", "Without an animation: where in the stretch between two hits the picture is shown"),
        Param.Heading("curveHeading", "Curve"),
        Param.Choice("curve", "Curve", curves, 0, "How it moves between two hits – 0 to the full amount and back · Drawn curve: draw it yourself below"),
        Param.Curve("drawn", "Drawn curve", "Left: the hit, right: just before the next one · up: the full amount. Click sets a point, dragging moves it, right-click removes it",
            defaultText = "rgb:0,255 30,140 90,40 255,0", single = true).shownWhen("curve", DRAWN),
        Param.Toggle("alternate", "Alternate", false, "Every other hit goes the other way: left instead of right, smaller instead of bigger …"),
        Param.Heading("amountHeading", "By how much"),
        Param.Slider("scale", "Scale", -100, 300, 15, " %", "Bigger at the full amount – minus: smaller"),
        Param.Slider("moveX", "X", -100, 100, 0, " %", "Moved right, of the canvas width – minus: left"),
        Param.Slider("moveY", "Y", -100, 100, 0, " %", "Moved down, of the canvas height – minus: up"),
        Param.Slider("rotation", "Rotation", -36000, 36000, 0, "°", decimals = 2),
        Param.Slider("brightness", "Brightness", -100, 300, 0, " %", "Brighter at the full amount – minus: darker"),
        Param.Slider("fade", "Fade", 0, 100, 0, " %", "More transparent at the full amount"),
        Param.Choice("edge", "Edge", Transform.edgeOptions, 0, "What fills the canvas where the moved or shrunken picture leaves it"),
    )

    override val random = false

    /** The curve's height (0..1, a drawn one may go anywhere between) at [p] (0..1) between two hits. */
    fun curveAt(kind: Int, p: Double, drawn: DoubleArray?): Double = when (kind) {
        0 -> (1 - p).pow(3.0)
        1 -> sin(PI * p).pow(2.0)
        2 -> abs(cos(p * PI * 2.5)) * (1 - p).pow(1.5)
        3 -> p
        4 -> 1 - p
        5 -> if (p < 0.5) 1.0 else 0.0
        6 -> 0.5 - 0.5 * cos(2 * PI * p)
        7 -> p * p * p
        else -> drawn?.let { Curves.apply(it, p) } ?: (1 - p)
    }

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val every = everyBeats[v["every"].coerceIn(0, everyBeats.size - 1)]
        val bpm = if (v["tempo"] == 1 || frames == null) v["bpm"] / 10.0 else frames.bpm
        // where in the stretches between hits this frame lies, counted in stretches
        val stretches = if (frames == null) v["preview"] / 100.0
        else frames.frame / frames.fps / (60.0 / bpm * every) + v["offset"] / 100.0
        val hit = floor(stretches)
        val p = stretches - hit
        val kind = v["curve"]
        val drawn = if (kind == DRAWN) Curves.lut(Curves.parse(v.text("drawn"))[0]) else null
        var k = curveAt(kind, p, drawn)
        // every other hit the other way
        if (v["alternate"] == 1 && hit.toLong() % 2 != 0L) k = -k
        if (k == 0.0) return src.copy()

        val w = src.width
        val h = src.height
        val scale = (1 + k * v["scale"] / 100.0).coerceAtLeast(0.01)
        var out = transformPixels(
            src, offsetX = k * v["moveX"] / 100.0 * w, offsetY = k * v["moveY"] / 100.0 * h, scaleX = scale, scaleY = scale,
            rotation = k * v["rotation"] / 100.0, edge = Transform.edgeOf(v["edge"]),
        )
        val bright = 1 + k * v["brightness"] / 100.0
        val fade = (1 - abs(k) * v["fade"] / 100.0).coerceIn(0.0, 1.0)
        if (bright != 1.0 || fade != 1.0) {
            val from = out
            val result = Pixels(w, h)
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = from.data[i]
                    fun ch(s: Int) = ((c shr s and 0xFF) * bright).roundToInt().coerceIn(0, 255)
                    val a = ((c ushr 24) * fade).roundToInt().coerceIn(0, 255)
                    result.data[i] = (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
            out = result
        }
        return if (out === src) src.copy() else out
    }
}
