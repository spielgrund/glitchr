package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Color bands: a shape (linear, mirrored, radial, angle, diamond, square, spiral) running
 * through a gradient as in [Ramp], several times (back and forth or with hard jumps), shifted
 * along. The transitions smooth as the gradient, in hard steps, or hard at the gradient's stops
 * (each stop's color holds until the next) – anti-aliased, softened by as many pixels as wanted.
 * Waves and turbulence bend the bands.
 */
object Colorbands : Effect("colorbands", "Colorbands", "Bands of color through a gradient – smooth, in hard steps or hard at the color stops") {
    private val shapes = listOf("Linear", "Mirrored", "Radial", "Angle", "Diamond", "Square", "Spiral")
    private val transitions = listOf("Smooth", "Hard steps", "Hard at the stops")

    override val params = listOf(
        Param.Ramp("ramp", "Gradient", RampPalette.NEON_RINGS.ramp.format(), "Click: add a color stop · Drag: move · Double-click: choose color · Right-click: remove"),
        Param.Choice("shape", "Shape", shapes, 0),
        Param.Choice(
            "transition", "Transitions", transitions, 2,
            "Smooth: as the gradient · Hard steps: cut into even steps, each in the gradient's color there · " +
                "Hard at the stops: every color stop's color holds until the next stop",
        ),
        Param.Slider("steps", "Steps", 2, 64, 6, tip = "Hard steps: how many per run through the gradient"),
        Param.Slider("softness", "Edge softness", 0, 20000, 0, " px", "Of the hard edges: 0 = sharp (anti-aliased)", decimals = 2),
        Param.Slider("repeats", "Repeats", 1, 64, 4, tip = "The gradient runs through several times"),
        Param.Toggle("mirror", "Mirror repeats", false, "Every second repeat runs back; off: hard jumps"),
        Param.Slider("shift", "Shift", -100000, 100000, 0, " %", "Shifts the bands along – animate it and they flow", decimals = 2),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("angle", "Angle", -18000, 18000, 0, "°", decimals = 2),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("size", "Size", 100, 50000, 10000, " %", "Length of the gradient, of the larger side of the canvas", decimals = 2, transform = true),
        Param.Heading("bendHeading", "Bend"),
        Param.Slider("waves", "Waves", 0, 100, 0, " %", "Bends the bands into waves"),
        Param.Slider("waveLength", "Wave length", 100, 10000, 2500, " %", "Of the size", decimals = 2),
        Param.Slider("turbulence", "Turbulence", 0, 100, 0, " %", "Bends the bands along noise – organic, flowing"),
        Param.Slider("turbulenceSize", "Turbulence size", 100, 10000, 3000, " %", "Of the size", decimals = 2),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val ramp = ColorRamp.parse(v.text("ramp").ifBlank { RampPalette.NEON_RINGS.ramp.format() })
        val noise = Noise(seed)
        val shape = v["shape"]
        val transition = v["transition"]
        val steps = v["steps"]
        val soft = v["softness"] / 100.0
        val repeats = v["repeats"]
        val mirror = v.bool("mirror")
        val shift = v["shift"] / 10000.0
        val a = Math.toRadians(v["angle"] / 100.0)
        val ca = cos(a)
        val sa = sin(a)
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        val size = max(1.0, v["size"] / 10000.0 * max(w, h))
        val waves = v["waves"] / 100.0
        val waveLen = max(1.0, v["waveLength"] / 10000.0 * size)
        val turb = v["turbulence"] / 100.0
        val turbSize = max(1.0, v["turbulenceSize"] / 10000.0 * size)

        /** Where (x, y) lies on the gradient, before the repeats are folded: 0..1 for one run. */
        fun value(x: Double, y: Double): Double {
            var dx = x - cx
            var dy = y - cy
            if (turb > 0) {
                dx += turb * size * 0.15 * noise.fbm(x / turbSize, y / turbSize, 3)
                dy += turb * size * 0.15 * noise.fbm(x / turbSize + 31.4, y / turbSize - 17.2, 3)
            }
            var u = dx * ca + dy * sa
            val vv = -dx * sa + dy * ca
            if (waves > 0) u += waves * waveLen * 0.25 * sin(vv / waveLen * 2 * PI)
            val half = size / 2
            return when (shape) {
                1 -> abs(u) / half
                2 -> hypot(u, vv) / half
                3 -> (atan2(vv, u) / (2 * PI) + 0.5)
                4 -> (abs(u) + abs(vv)) / half
                5 -> max(abs(u), abs(vv)) / half
                6 -> atan2(vv, u) / (2 * PI) + 0.5 + hypot(u, vv) / half
                else -> u / size + 0.5
            }
        }

        /** The repeats folded back into 0..1. */
        fun fold(t: Double): Double = when {
            repeats == 1 && shift == 0.0 -> t.coerceIn(0.0, 1.0)
            mirror -> (t - 2 * floor(t / 2)).let { if (it > 1) 2 - it else it }
            else -> (t - floor(t)).let { if (it == 0.0 && t > 0) 1.0 else it }
        }

        // hard edges: the boundaries and the color after each
        val edges: DoubleArray
        val colors: IntArray
        when (transition) {
            1 -> {
                edges = DoubleArray(steps - 1) { (it + 1.0) / steps }
                colors = IntArray(steps) { ramp.colorAt(it / (steps - 1.0)) }
            }
            2 -> {
                edges = DoubleArray(ramp.stops.size - 1) { ramp.stops[it + 1].pos }
                colors = IntArray(ramp.stops.size) { ramp.stops[it].rgb }
            }
            else -> {
                edges = DoubleArray(0)
                colors = IntArray(0)
            }
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val px = x + 0.5
                val py = y + 0.5
                val raw = value(px, py) * repeats + shift
                val t = fold(raw)
                val rgb = if (transition == 0) ramp.colorAt(t) else {
                    // how far one pixel moves along the gradient: the width over which an edge is smoothed
                    val dx = abs(value(px + 1, py) * repeats + shift - raw)
                    val dy = abs(value(px, py + 1) * repeats + shift - raw)
                    val foot = min(0.25, dx + dy)
                    val d = max(1e-6, foot * (1 + soft))
                    var r = (colors[0] shr 16 and 0xFF).toDouble()
                    var g = (colors[0] shr 8 and 0xFF).toDouble()
                    var b = (colors[0] and 0xFF).toDouble()
                    // across every edge, blended over the width
                    for (j in edges.indices) {
                        val s = ((t - edges[j]) / d + 0.5).coerceIn(0.0, 1.0)
                        if (s <= 0) break
                        val c = colors[j + 1]
                        r += ((c shr 16 and 0xFF) - r) * s
                        g += ((c shr 8 and 0xFF) - g) * s
                        b += ((c and 0xFF) - b) * s
                    }
                    ((r + 0.5).toInt() shl 16) or ((g + 0.5).toInt() shl 8) or (b + 0.5).toInt()
                }
                out.data[y * w + x] = 0xFF000000.toInt() or rgb
            }
        }
        return out
    }
}
