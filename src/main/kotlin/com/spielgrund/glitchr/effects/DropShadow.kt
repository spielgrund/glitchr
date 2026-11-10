package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A shadow of the layer's content: a soft **drop shadow** cast away from it, an **inner shadow**
 * sinking it in at its edges, or a hard **long shadow** stretching far in one direction like in
 * flat design. What casts it: the opaque parts of the layer – or, for a picture without
 * transparency, its bright or its dark parts, the shadow then falling on the rest of the picture.
 */
object DropShadow : Effect("dropshadow", "Drop shadow", "A drop shadow, inner shadow or long shadow of the layer's content – from its transparency or its bright or dark parts") {
    private val kinds = listOf("Drop shadow", "Inner shadow", "Long shadow")
    private const val INNER = 1
    private const val LONG = 2
    private val sources = listOf("Transparency", "Bright parts", "Dark parts")
    private const val ALPHA = 0
    private const val DARK = 2

    override val params = listOf(
        Param.Choice("kind", "Kind", kinds, 0, "Drop shadow: cast away from the content · Inner shadow: inside, along its edges · Long shadow: stretching far in one direction"),
        Param.Choice("source", "Cast by", sources, ALPHA, "Transparency: the opaque parts of the layer (a layer without any transparency: its dark parts) · Bright parts / Dark parts: for a picture without transparency – the shadow falls on the rest of it"),
        Param.Slider("threshold", "Threshold", 0, 255, 128, "", "From which brightness on a part casts a shadow").shownWhen("source", 1, DARK),
        Param.Slider("angle", "Falls to", 0, 359, 315, "°", "Where the shadow falls: 0° right, 270° down"),
        Param.Slider("distance", "Distance", 0, 2000, 20, " px", "How far it is moved – the length of a long shadow", canvasMax = true),
        Param.Slider("softness", "Softness", 0, 300, 12, " px"),
        Param.Slider("spread", "Spread", 0, 100, 0, " %", "Fills the shadow's soft edge in: harder and bigger"),
        Param.Slider("opacity", "Opacity", 0, 100, 60, " %"),
        Param.Color("color", "Color", 0x000000),
        Param.Slider("fade", "Fade", 0, 100, 60, " %", "How much the long shadow fades towards its end").shownWhen("kind", LONG),
        Param.Choice("blend", "Blend", listOf("Multiply", "Normal"), 0, "Multiply: darkens what lies below, keeps its colors · Normal: the shadow color over it"),
        Param.Toggle("shadowOnly", "Shadow only", false, "Only the shadow, without the content"),
    )

    override val random = false

    /** [plane] at (x, y), bilinear, 0 beyond the edge. */
    private fun sample(plane: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
        val fx0 = x - 0.5
        val fy0 = y - 0.5
        val x0 = floor(fx0).toInt()
        val y0 = floor(fy0).toInt()
        val fx = (fx0 - x0).toFloat()
        val fy = (fy0 - y0).toFloat()
        fun at(xx: Int, yy: Int) = if (xx < 0 || yy < 0 || xx >= w || yy >= h) 0f else plane[yy * w + xx]
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        // a layer without any transparency casts the shadow from its dark parts instead
        val source = if (v["source"] == ALPHA && src.data.all { alpha(it) == 255 }) DARK else v["source"]
        val threshold = v["threshold"] / 255.0
        // what casts the shadow, 0..1
        val caster = FloatArray(n) { i ->
            val c = src.data[i]
            when (source) {
                ALPHA -> alpha(c) / 255f
                else -> {
                    val l = (0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)) / 255.0
                    // softly over a few levels, so the shadow's edge is smooth
                    val k = ((l - threshold) / 0.04 + 0.5).coerceIn(0.0, 1.0)
                    ((if (source == DARK) 1 - k else k) * alpha(c) / 255.0).toFloat()
                }
            }
        }
        val kind = v["kind"]
        val angle = Math.toRadians(v["angle"].toDouble())
        val distance = v["distance"].toDouble()
        val dx = cos(angle)
        val dy = -sin(angle)
        // inside: the shadow of what lies around the content, falling in over its edge
        val base = if (kind == INNER) FloatArray(n) { 1 - caster[it] } else caster
        val moved = FloatArray(n)
        if (kind == LONG) {
            // the content pushed along step by step, fading towards the end
            val fade = v["fade"] / 100.0
            val steps = ceil(distance).toInt().coerceAtLeast(1)
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    var m = 0f
                    for (k in 0..steps) {
                        val s = sample(base, w, h, x + 0.5 - dx * k, y + 0.5 - dy * k) * (1 - fade * k / steps).toFloat()
                        if (s > m) m = s
                        if (m >= 0.999f) break
                    }
                    moved[y * w + x] = m
                }
            }
        } else {
            // beyond the edge an inner shadow counts as outside, a drop shadow as nothing
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val sx = x + 0.5 - dx * distance
                    val sy = y + 0.5 - dy * distance
                    val outside = sx < 0 || sy < 0 || sx >= w || sy >= h
                    moved[y * w + x] = if (kind == INNER && outside) 1f else sample(base, w, h, sx, sy)
                }
            }
        }
        val softness = v["softness"].toDouble()
        val soft = if (softness >= 0.5) Blur.gauss(moved, w, h, softness * 0.5) else moved
        val spread = v["spread"] / 100.0
        val opacity = v["opacity"] / 100.0
        val color = v["color"]
        val multiply = v["blend"] == 0
        val only = v.bool("shadowOnly")

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = src.data[i]
                val m = caster[i].toDouble()
                var s = (soft[i] / max(0.05, 1 - spread * 0.95)).coerceIn(0.0, 1.0) * opacity
                // a drop or long shadow lies behind the content, an inner shadow only on it
                s *= if (kind == INNER) m else 1 - m
                fun shaded(base: Int, sh: Int): Double {
                    val col = (color shr sh and 0xFF).toDouble()
                    return if (multiply) base * (1 - s + s * col / 255) else base * (1 - s) + col * s
                }
                out.data[i] = when {
                    only -> argb((s * 255).roundToInt(), red(color), green(color), blue(color))
                    source == ALPHA && kind != INNER -> {
                        // behind transparent content: the shadow shows through where the layer is see-through
                        val a = alpha(c) / 255.0
                        val total = a + s * (1 - a)
                        if (total <= 0) 0 else {
                            fun ch(sh: Int) = (((c shr sh and 0xFF) * a + (color shr sh and 0xFF) * s * (1 - a)) / total).roundToInt().coerceIn(0, 255)
                            argb((total * 255).roundToInt(), ch(16), ch(8), ch(0))
                        }
                    }
                    else -> argb(alpha(c), shaded(red(c), 16).roundToInt().coerceIn(0, 255), shaded(green(c), 8).roundToInt().coerceIn(0, 255), shaded(blue(c), 0).roundToInt().coerceIn(0, 255))
                }
            }
        }
        return out
    }
}
