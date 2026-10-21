package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Twist: turns the picture around a center, the middle more than the outside, so it winds
 * into a whirl. How the turn fades with the distance is chosen by the profile; rings can
 * turn alternately left and right, smoothly or in hard steps like a combination lock.
 */
object Twist : Effect("twist", "Twist", "Twists the picture around a center into a whirl") {
    private val profiles = listOf("Smooth", "Linear", "Sharp center", "Whole picture", "Growing outwards")
    private val ringStyles = listOf("None", "Waves", "Steps")

    override val params = listOf(
        Param.Slider("angle", "Twist", -180000, 180000, 18000, "°", "How far the middle is turned against the outside", decimals = 2),
        Param.Slider("radius", "Radius", 1, 3000, 400, " px", "How far the whirl reaches; at most the longer side of the canvas", canvasMax = true),
        Param.Choice(
            "profile", "Profile", profiles, 0,
            "How the turn fades towards the radius: smoothly, evenly, mostly in the middle, not at all (everything turns), " +
                "or the other way round – still in the middle, more and more outwards like a spiral",
        ),
        Param.Choice("rings", "Rings", ringStyles, 0, "Rings that turn alternately left and right: smoothly as waves, or in hard steps"),
        Param.Slider("ringCount", "Ring count", 1, 100, 4, tip = "Rings within the radius"),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Choice("edge", "Edge", Transform.edgeOptions, 3, "What comes in from outside the picture"),
        Param.Choice("antialias", "Anti-aliasing", listOf("Off", "2 × 2", "4 × 4"), 1, "Smoother where the whirl winds tightly"),
    )

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val angle = Math.toRadians(v["angle"] / 100.0)
        val radius = max(1.0, v["radius"].toDouble())
        val profile = v["profile"]
        val rings = v["rings"]
        val ringCount = v["ringCount"]
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        val edge = Transform.edgeOf(v["edge"])
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }

        /** The share of the full twist at distance [r]. */
        fun amount(r: Double): Double {
            val t = r / radius
            var f = when (profile) {
                1 -> (1 - t).coerceAtLeast(0.0)
                2 -> (1 - t).coerceAtLeast(0.0).let { it * it * it }
                3 -> 1.0
                4 -> t
                else -> if (t >= 1) 0.0 else 1 - t * t * (3 - 2 * t)
            }
            when (rings) {
                1 -> f *= cos(PI * t * ringCount)
                2 -> if (floor(t * ringCount).toInt() % 2 == 1) f = -f
            }
            return f
        }

        /** The picture at canvas point ([x], [y]): read from where the twist turned it from. */
        fun sample(x: Double, y: Double): Int {
            val dx = x - cx
            val dy = y - cy
            val a = -angle * amount(hypot(dx, dy))
            val c = cos(a)
            val s = sin(a)
            val sx = cx + dx * c - dy * s
            val sy = cy + dx * s + dy * c
            if (edge == null && (sx < 0 || sy < 0 || sx >= w || sy >= h)) return 0
            return sampleBilinear(src, sx, sy, edge ?: com.spielgrund.glitchr.image.Edge.CLAMP)
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val local = IntArray(sub * sub)
            for (x in 0 until w) {
                // beyond the radius the fading profiles leave the picture exactly as it is
                if (profile <= 2 && rings == 0 && hypot(x + 0.5 - cx, y + 0.5 - cy) > radius + 1) {
                    out.data[y * w + x] = src.data[y * w + x]
                    continue
                }
                out.data[y * w + x] = if (sub == 1) sample(x + 0.5, y + 0.5)
                else {
                    var k = 0
                    for (j in 0 until sub) for (i in 0 until sub) local[k++] = sample(x + (i + 0.5) / sub, y + (j + 0.5) / sub)
                    averageArgb(local)
                }
            }
        }
        return out
    }
}
