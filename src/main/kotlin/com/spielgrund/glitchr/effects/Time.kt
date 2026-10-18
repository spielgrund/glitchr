package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Feedback through time: exactly like [Feedback], but every copy shows the picture some
 * frames earlier, so moving things leave trails that zoom, turn and drift away.
 */
object TimeFeedback : TimeEffect(
    "timefeedback", "Time feedback",
    "Like Feedback, but every copy shows the picture some frames earlier – moving things leave zooming, turning trails",
) {
    override val params = Feedback.params + listOf(
        Param.Heading("timeHeading", "Time"),
        Param.Slider("frameStep", "Frames per copy", 1, 60, 1, tip = "How many frames earlier each further copy is"),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val step = v["frameStep"]
        val loop = v["ends"] == 1
        return Feedback.render(src, v) { k -> frames.offset(src, -k * step, loop) }
    }
}

/**
 * Time blend: several frames laid over each other, so motion smears and flickering
 * calms down – averaged, or only the lightest or darkest of them (light trails).
 */
object TimeBlend : TimeEffect("timeblend", "Time blend", "Blends several frames of the animation into one – smooth motion blur, calm flicker, light trails") {
    private val directions = listOf("Past", "Around the frame", "Future")
    private val weightings = listOf("Equal", "Fading", "Triangle")
    private val modes = listOf("Average", "Lightest (light trails)", "Darkest")

    override val params = listOf(
        Param.Slider("frames", "Frames", 1, 60, 6, tip = "How many frames are blended"),
        Param.Slider("frameStep", "Frame spacing", 1, 30, 1, tip = "Every n-th frame"),
        Param.Choice("direction", "From", directions, 0, "The frames before, around or after the current one"),
        Param.Choice("mode", "Mode", modes, 0),
        Param.Choice("weighting", "Weighting", weightings, 1, "Average: all alike, the current one most and fading further away, or most in the middle"),
        Param.Slider("fade", "Fading", 0, 100, 30, " %", "Weighting “Fading”: each frame further away counts this much less"),
        Param.Slider("mix", "Mix", 0, 100, 100, " %", "How much of the blend replaces the current frame"),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val n = v["frames"]
        val step = v["frameStep"]
        val loop = v["ends"] == 1
        val first = when (v["direction"]) {
            1 -> -(n - 1) / 2
            2 -> 0
            else -> -(n - 1)
        }
        // distance (in frames counted) of each from the current one, and its weight
        val offsets = IntArray(n) { (first + it) * step }
        val fade = 1 - v["fade"] / 100.0
        val weights = DoubleArray(n) { k ->
            val d = abs(first + k)
            when (v["weighting"]) {
                1 -> fade.pow(d)
                2 -> (n / 2.0 + 1 - d).coerceAtLeast(0.5)
                else -> 1.0
            }
        }
        val pictures = offsets.map { frames.offset(src, it, loop) }
        val mode = v["mode"]
        val mix = v["mix"] / 100f
        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var sum = 0.0
                var best = -1
                var bestLuma = if (mode == 2) Int.MAX_VALUE else -1
                for (k in 0 until n) {
                    val c = pictures[k].data[i]
                    if (mode == 0) {
                        // premultiplied, so transparent frames don't darken the others
                        val ca = (c ushr 24) * weights[k]
                        a += ca
                        r += (c shr 16 and 0xFF) * ca
                        g += (c shr 8 and 0xFF) * ca
                        b += (c and 0xFF) * ca
                        sum += weights[k]
                    } else {
                        val l = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) * (c ushr 24)
                        if (if (mode == 1) l > bestLuma else l < bestLuma) { bestLuma = l; best = c }
                    }
                }
                val blended = if (mode != 0) best
                else if (a <= 0) 0
                else argb((a / sum + 0.5).toInt().coerceIn(0, 255), (r / a + 0.5).toInt(), (g / a + 0.5).toInt(), (b / a + 0.5).toInt())
                out.data[i] = if (mix >= 1f) blended else lerpArgb(src.data[i], blended, mix)
            }
        }
        return out
    }
}

/** Difference: only what changed between frames is seen – motion lights up, everything still goes dark. */
object TimeDifference : TimeEffect("timedifference", "Time difference", "Shows only what changed between frames: moving parts light up, still parts disappear") {
    private val modes = listOf("Color difference", "Brightness difference", "Changed parts of the picture")
    private val grounds = listOf("Black", "Transparent", "Picture")

    override val params = listOf(
        Param.Slider("offset", "Compare with frame", -60, 60, -1, tip = "Which frame is compared: −1 = the one before, +1 = the one after"),
        Param.Choice("mode", "Show", modes, 0, "The color difference, its brightness in one color, or the current picture where it changed"),
        Param.Slider("gain", "Strength", 10, 2000, 300, " %", "Amplifies small differences"),
        Param.Slider("threshold", "Threshold", 0, 100, 4, " %", "Smaller differences (noise, compression) are ignored"),
        Param.Color("tint", "Color", 0xFFFFFF, "For “Brightness difference”"),
        Param.Choice("ground", "Underneath", grounds, 0, "What shows where nothing changed"),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val other = frames.offset(src, v["offset"], v["ends"] == 1)
        val mode = v["mode"]
        val gain = v["gain"] / 100.0
        val threshold = v["threshold"] / 100.0
        val tint = v["tint"] or 0xFF000000.toInt()
        val ground = v["ground"]
        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val a = src.data[i]
                val b = other.data[i]
                // transparency counts as black, so things appearing and vanishing are changes too
                fun ch(c: Int, s: Int) = (c shr s and 0xFF) * (c ushr 24) / 255
                val dr = abs(ch(a, 16) - ch(b, 16))
                val dg = abs(ch(a, 8) - ch(b, 8))
                val db = abs(ch(a, 0) - ch(b, 0))
                val m = max(dr, max(dg, db)) / 255.0
                val strength = if (m <= threshold) 0.0 else min(1.0, (m - threshold) / (1 - threshold).coerceAtLeast(1e-6) * gain)
                val color = when (mode) {
                    0 -> {
                        fun amp(d: Int) = if (m <= threshold) 0 else min(255.0, d * gain).toInt()
                        argb(255, amp(dr), amp(dg), amp(db))
                    }
                    1 -> tint
                    else -> a
                }
                // the color difference carries its strength in its colors; the others are laid on by strength
                val cover = if (mode == 0) (if (m <= threshold) 0.0 else 1.0) else strength
                out.data[i] = when (ground) {
                    1 -> {
                        val alpha = if (mode == 0) min(1.0, m * gain) else strength
                        (((alpha * 255) + 0.5).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
                    }
                    2 -> lerpArgb(a, color, cover.toFloat())
                    else -> lerpArgb(0xFF000000.toInt(), color, cover.toFloat())
                }
            }
        }
        return out
    }
}
