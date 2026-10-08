package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A ball in the picture. Bulge pushes the picture inside a circle outwards like a
 * spherize filter (or pinches it inwards). Globe wraps the whole picture around a ball
 * that can be turned and tilted. Glass ball refracts the picture behind it – upside
 * down like a photographer's lens ball – and reflects a little at its rim. Mirror ball
 * reflects the picture like chrome. Light from one side shades the ball and puts a
 * highlight on it.
 */
object Sphere : Effect("sphere", "Sphere", "Bulges the picture, wraps it around a globe or shows it through a glass or mirror ball") {
    private val modes = listOf("Bulge / pinch", "Globe", "Glass ball", "Mirror ball")
    private const val BULGE = 0
    private const val GLOBE = 1
    private const val GLASS = 2
    private const val MIRROR = 3

    private val backgrounds = listOf("Picture", "Transparent", "Black")

    override val params = listOf(
        Param.Choice(
            "mode", "Type", modes, GLOBE,
            tip = "Bulge / pinch: the picture inside the circle swells outwards or is pulled inwards · " +
                "Globe: the whole picture wrapped around a ball · Glass ball: the picture seen through glass, upside down · " +
                "Mirror ball: the picture reflected like chrome",
        ),
        Param.Slider("radius", "Radius", 5, 400, 80, " %", "Size of the ball (100 % = half the shorter side)"),
        Param.Slider("centerX", "Center X", -2000, 2000, 0, " px", "Pixels from the middle of the canvas – can also be dragged with the handle in the picture"),
        Param.Slider("centerY", "Center Y", -2000, 2000, 0, " px"),
        Param.Choice("background", "Around the ball", backgrounds, tip = "What stays outside the ball (globe, glass and mirror ball)"),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("amount", "Bulge", -100, 100, 100, " %", "Bulge / pinch: positive swells outwards, negative pinches inwards"),
        Param.Slider("turn", "Turn", -360, 360, 0, "°", "Globe and mirror ball: turns the ball around its vertical axis – animate it to let it spin"),
        Param.Slider("tilt", "Tilt", -90, 90, 0, "°", "Globe and mirror ball: tips the ball forwards or backwards"),
        Param.Slider("repeat", "Repeat", 1, 8, 1, tip = "Globe and mirror ball: how often the picture runs around the ball"),
        Param.Toggle("seamless", "Hide seam", true, "Globe and mirror ball: runs the picture forth and mirrored back, so its ends meet without a seam"),
        Param.Slider("ior", "Refraction", 100, 250, 150, decimals = 2, tip = "Glass ball: refractive index – 1.00 bends nothing, 1.50 is glass, higher bends more"),
        Param.Slider("zoom", "Backdrop zoom", 10, 400, 100, " %", "Glass ball: enlarges the picture seen through the ball"),
        Param.Heading("lightHeading", "Light"),
        Param.Slider("shading", "Shading", 0, 100, 50, " %", "Brightens the side facing the light and darkens the other one"),
        Param.Slider("lightAngle", "Light direction", 0, 359, 225, "°", "Where the light comes from; 225° = from the top left"),
        Param.Slider("highlight", "Highlight", 0, 100, 40, " %", "Shiny spot where the light is reflected"),
        Param.Slider("gloss", "Highlight size", 1, 100, 30, " %", "Small: a sharp spot · large: a broad sheen"),
        Param.Choice(
            "antialias", "Anti-aliasing", listOf("Off", "2 × 2", "4 × 4"), 1,
            "Samples per pixel inside the ball – smooths fine stripes on the globe and in the reflections; the rim always gets 4 × 4 unless off",
        ),
    )

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val mode = v["mode"]
        val cx = w / 2.0 + v["centerX"]
        val cy = h / 2.0 + v["centerY"]
        val radius = max(1.0, min(w, h) / 2.0 * v["radius"] / 100.0)
        val background = v["background"]
        val amount = v["amount"] / 100.0
        val turn = v["turn"] * PI / 180
        val tilt = v["tilt"] * PI / 180
        val repeat = v["repeat"].toDouble()
        val seamless = v.bool("seamless")
        val ior = v["ior"] / 100.0
        val zoom = v["zoom"] / 100.0
        // bulging only shades as much as it bulges
        val shading = v["shading"] / 100.0 * if (mode == BULGE) abs(amount) else 1.0
        val highlight = v["highlight"] / 100.0 * if (mode == BULGE) abs(amount) else 1.0
        val shininess = 4 + 200 * (1 - v["gloss"] / 100.0).pow(2)
        val lightAngle = v["lightAngle"] * PI / 180
        // light from the chosen side and somewhat from the front
        val lx = cos(lightAngle) * 0.6
        val ly = sin(lightAngle) * 0.6
        val lz = 0.53
        // halfway between light and eye, for the highlight
        val hl = sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1))
        val hx = lx / hl
        val hy = ly / hl
        val hz = (lz + 1) / hl
        val cosTurn = cos(turn)
        val sinTurn = sin(turn)
        val cosTilt = cos(tilt)
        val sinTilt = sin(tilt)

        /** The picture as the world around the ball: direction ([dx], [dy], [dz]) to a spot on the wrapped picture. */
        fun world(dx: Double, dy: Double, dz: Double): Int {
            // tilt around the horizontal axis, then turn around the vertical one
            val y1 = dy * cosTilt - dz * sinTilt
            val z1 = dy * sinTilt + dz * cosTilt
            val x2 = dx * cosTurn + z1 * sinTurn
            val z2 = -dx * sinTurn + z1 * cosTurn
            var u = atan2(x2, z2) / (2 * PI) + 0.5
            u *= repeat
            // seamless: forth and mirrored back, with the picture's middle facing the front
            if (seamless) u += 0.25
            u -= floor(u)
            if (seamless) u = 1 - abs(2 * u - 1)
            val t = acos((-y1).coerceIn(-1.0, 1.0)) / PI
            return sampleBilinear(src, u * w, t * h, Edge.CLAMP)
        }

        /** The ball's color at (nx, ny) inside the unit circle, or null outside. */
        fun ball(nx: Double, ny: Double): Int? {
            val rho2 = nx * nx + ny * ny
            if (rho2 >= 1) return null
            val nz = sqrt(1 - rho2)
            val color = when (mode) {
                BULGE -> {
                    val rho = sqrt(rho2)
                    val curved = if (amount >= 0) asin(rho) * 2 / PI else sin(rho * PI / 2)
                    val f = if (rho < 1e-9) 1.0 else (rho + abs(amount) * (curved - rho)) / rho
                    sampleBilinear(src, cx + nx * f * radius, cy + ny * f * radius, Edge.CLAMP)
                }
                GLOBE -> world(nx, ny, nz)
                MIRROR -> world(2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1)
                else -> glass(src, nx, ny, nz, ior, zoom, cx, cy, radius) { dx, dy, dz -> world(dx, dy, dz) }
            }
            if (shading == 0.0 && highlight == 0.0) return color
            val diffuse = max(0.0, nx * lx + ny * ly + nz * lz)
            val light = 1 + shading * (diffuse * 1.3 - 0.75)
            val spec = highlight * max(0.0, nx * hx + ny * hy + nz * hz).pow(shininess) * 255
            fun ch(c: Int) = (c * light + spec).coerceIn(0.0, 255.0).toInt()
            return (color and 0xFF000000.toInt()) or (ch(color shr 16 and 0xFF) shl 16) or (ch(color shr 8 and 0xFF) shl 8) or ch(color and 0xFF)
        }

        // null: the picture itself stays around the ball
        val outside: Int? = when {
            mode == BULGE || background == 0 -> null
            background == 2 -> 0xFF000000.toInt()
            else -> 0
        }

        // samples per side inside the ball, and at its rim (where the ball meets what's around it)
        val sub = when (v["antialias"]) { 1 -> 2; 2 -> 4; else -> 1 }
        val rimSub = if (sub == 1) 1 else 4
        val out = Pixels(w, h)
        // how far a pixel reaches in ball units: pixels touching the rim are mixed with the background
        val pixel = 1.0 / radius
        parallelRows(h) { y ->
            val inner = IntArray(sub * sub)
            val rim = IntArray(rimSub * rimSub)

            /** The mean of [n] × [n] samples over the pixel, the background where they miss the ball. */
            fun sampled(x: Int, n: Int, samples: IntArray, back: Int): Int {
                for (s in samples.indices) {
                    val sx = (x + (s % n + 0.5) / n - cx) / radius
                    val sy = (y + (s / n + 0.5) / n - cy) / radius
                    samples[s] = ball(sx, sy) ?: back
                }
                return averageArgb(samples)
            }

            for (x in 0 until w) {
                val i = y * w + x
                val nx = (x + 0.5 - cx) / radius
                val ny = (y + 0.5 - cy) / radius
                val rho = sqrt(nx * nx + ny * ny)
                val back = outside ?: src.data[i]
                out.data[i] = when {
                    rho > 1 + pixel -> back
                    rho < 1 - pixel -> if (sub == 1) ball(nx, ny)!! else sampled(x, sub, inner, back)
                    else -> sampled(x, rimSub, rim, back)
                }
            }
        }
        return out
    }

    /**
     * The glass ball at unit normal (nx, ny, nz): the view ray is refracted into the ball,
     * out again at its back and hits the picture behind it, which lies on a plane three
     * radii away. At grazing angles more of the surroundings ([world]) is reflected.
     */
    private inline fun glass(
        src: Pixels, nx: Double, ny: Double, nz: Double, ior: Double, zoom: Double,
        cx: Double, cy: Double, radius: Double, world: (Double, Double, Double) -> Int,
    ): Int {
        val reflected = world(2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1)
        // into the ball: looking along -z, the surface normal points at the eye
        val t1 = refract(0.0, 0.0, -1.0, nx, ny, nz, 1 / ior) ?: return reflected
        // the second crossing of the ball's surface
        val s = -2 * (nx * t1[0] + ny * t1[1] + nz * t1[2])
        val px = nx + t1[0] * s
        val py = ny + t1[1] * s
        val pz = nz + t1[2] * s
        val t2 = refract(t1[0], t1[1], t1[2], -px, -py, -pz, ior) ?: return reflected
        // the picture stands behind the ball; a ray leaving forwards sees the reflection instead
        val distance = 3.0
        if (t2[2] > -1e-6) return reflected
        val k = (-distance - pz) / t2[2]
        val bx = px + t2[0] * k
        val by = py + t2[1] * k
        val behind = sampleBilinear(src, cx + bx * radius / zoom, cy + by * radius / zoom, Edge.MIRROR)
        // Schlick: almost no reflection head-on, a lot at the rim
        val f = 0.04 + 0.96 * (1 - nz).pow(5)
        return com.spielgrund.glitchr.image.lerpArgb(behind, reflected, f.toFloat())
    }

    /** Refracts direction [d] at a surface with normal [n] facing against it; null on total internal reflection. */
    private fun refract(dx: Double, dy: Double, dz: Double, nx: Double, ny: Double, nz: Double, eta: Double): DoubleArray? {
        val cosI = -(dx * nx + dy * ny + dz * nz)
        val k = 1 - eta * eta * (1 - cosI * cosI)
        if (k < 0) return null
        val f = eta * cosI - sqrt(k)
        return doubleArrayOf(eta * dx + f * nx, eta * dy + f * ny, eta * dz + f * nz)
    }
}
