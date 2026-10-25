package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Rays cast per pixel, across and down. */
private const val RAYS = 3

/** The kinds of water surface (see [Water]). */
internal val waveTypes = listOf("Pool", "Ocean", "Rings", "Cells")

/**
 * A water surface: its height at a pixel and a time, by [type] (see [waveTypes]):
 * - pool: three layers of smooth swells – the wave size, three quarters and a little under half
 *   of it – drifting against each other;
 * - ocean: seven wave trains from about one direction ([angle]), longer ones running slower, their
 *   crests bent a little by noise – long, parallel bands of light;
 * - rings: circular waves running out from [rings] (centers in pixels), fading with distance, over
 *   a little pool swell;
 * - cells: three wave trains at 120° to each other, bent a little by noise – a lattice of bumps
 *   that draws an even, honeycomb-like net.
 * Pool and cells can be drawn out along [angle] ([stretch]); [detail] weighs the smaller waves.
 * With a [loop] length the water crossfades from now to one loop earlier, so at the loop length
 * it is exactly the water at 0 – evened out, so it doesn't get calmer halfway.
 */
internal class Water(
    private val noise: Noise, private val type: Int, val size: Double, angle: Double, stretch: Double,
    detail: Double, private val rings: List<DoubleArray>, private val loop: Double,
) {
    private val ca = cos(angle)
    private val sa = sin(angle)
    private val squash = 1 + 2.5 * stretch
    private val fine = detail * 2

    // ocean: per train its direction (x, y), wave number, height, phase and speed
    private val trains = Array(7) { k ->
        val dir = angle + noise.white(k, 901) * 0.6
        val length = size * 1.3 * (0.8 + 0.3 * (noise.white(k, 902) + 1))
        doubleArrayOf(cos(dir), sin(dir), 2 * PI / length, 0.22 * length / (size * 1.3), (noise.white(k, 903) + 1) * PI, 2 * PI * 0.15 * sqrt(size * 1.3 / length))
    }

    fun height(px: Double, py: Double, t: Double): Double {
        if (loop <= 0) return raw(px, py, t)
        val now = ((t % loop) + loop) % loop
        val u = now / loop
        return ((1 - u) * raw(px, py, now) + u * raw(px, py, now - loop)) / sqrt((1 - u) * (1 - u) + u * u)
    }

    private fun raw(px: Double, py: Double, t: Double): Double {
        // in wave sizes, turned to the direction and drawn out along it
        val x = (px * ca + py * sa) / size / squash
        val y = (-px * sa + py * ca) / size
        return when (type) {
            1 -> {
                val wx = px + noise.perlin(px / (size * 3) + t * 0.05, py / (size * 3)) * size * 0.6
                val wy = py + noise.perlin(px / (size * 3) - 40.0, py / (size * 3) + t * 0.05) * size * 0.6
                var sum = 0.0
                for (w in trains) sum += w[3] * sin((w[0] * wx + w[1] * wy) * w[2] - w[5] * t + w[4])
                sum + fine * 0.1 * noise.perlin(px / (size * 0.5) + t * 0.3, py / (size * 0.5) - t * 0.2)
            }
            2 -> {
                val length = size * 1.1
                var sum = 0.0
                for (c in rings) {
                    val r = hypot(px - c[0], py - c[1])
                    sum += 0.18 * sin(2 * PI * r / length - 2 * PI * 0.25 * t) / (1 + r / (length * 5))
                }
                sum + 0.12 * fine * pool(px / size, py / size, t)
            }
            3 -> {
                // three wave trains at 120° to each other: a lattice of bumps, bent a little by noise
                val wx = x + noise.perlin(x * 0.35 + t * 0.05, y * 0.35) * 0.45
                val wy = y + noise.perlin(x * 0.35 - 40.0, y * 0.35 + t * 0.05) * 0.45
                var sum = 0.0
                for (k in 0..2) {
                    val a = k * 2 * PI / 3
                    sum += cos((cos(a) * wx + sin(a) * wy) * 2 * PI / 1.2 + t * 0.6 * (k + 1) * (if (k == 1) -1 else 1))
                }
                0.12 * sum + 0.1 * fine * pool(x * 1.5, y * 1.5, t)
            }
            else -> pool(x, y, t)
        }
    }

    private fun pool(x: Double, y: Double, t: Double) =
        noise.perlin(x + t * 0.3, y + t * 0.2) +
            fine * (0.7 * noise.perlin(x / 0.75 - t * 0.25 + 31.3, y / 0.75 + t * 0.35 - 17.7) +
                0.3 * noise.perlin(x / 0.45 + t * 0.4 - 8.1, y / 0.45 - t * 0.15 + 5.9))
}

/**
 * Light shining through a wavy water surface onto the ground below: every ray is bent by the
 * surface's slope, and where many arrive together they draw the bright, wobbling net of water
 * caustics. The surface is the [water] at [time]; the rays are cast
 * [RAYS] × [RAYS] per pixel (also from beyond the edge) and gathered where they land. The focus
 * can vary across the picture at random ([focusVariation]): tight nets here, soft ripples there.
 * With [dispersion] red, green and blue are bent a little differently, for colored fringes.
 * Returns the light per pixel – 1 on average, brighter in the net – once, or per channel.
 */
internal fun causticLight(
    w: Int, h: Int, noise: Noise, water: Water, time: Double,
    focus: Double, focusVariation: Double, sharpness: Double, dispersion: Double,
): Array<FloatArray> {
    val s = max(4.0, water.size)
    // the rays land up to this far from where they start: cast them from this far beyond the edge too
    val margin = ceil(min(s * 0.8 * focus * (1 + focusVariation) + 2, max(w, h).toDouble())).toInt()
    val ew = w + 2 * margin
    val eh = h + 2 * margin
    val surface = FloatArray(ew * eh)
    parallelRows(eh) { y ->
        for (x in 0 until ew) {
            val px = (x - margin).toDouble()
            val py = (y - margin).toDouble()
            // smooth swells only – fine ripples would scatter the light into noise
            surface[y * ew + x] = water.height(px, py, time).toFloat()
        }
    }
    // the focus here: varying slowly across the picture, staying put while the water moves
    val local = if (focusVariation <= 0) null else FloatArray(ew * eh).also { f ->
        parallelRows(eh) { y ->
            for (x in 0 until ew) {
                val n = noise.fbm((x - margin) / (s * 3.5) + 77.7, (y - margin) / (s * 3.5) - 33.3, 2)
                f[y * ew + x] = (1 + focusVariation * 1.8 * n).coerceIn(0.05, 1 + focusVariation).toFloat()
            }
        }
    }
    // the slope, per pixel
    val gx = FloatArray(ew * eh)
    val gy = FloatArray(ew * eh)
    parallelRows(eh) { y ->
        for (x in 0 until ew) {
            val i = y * ew + x
            gx[i] = (surface[y * ew + min(ew - 1, x + 1)] - surface[y * ew + max(0, x - 1)]) / 2
            gy[i] = (surface[min(eh - 1, y + 1) * ew + x] - surface[max(0, y - 1) * ew + x]) / 2
        }
    }
    // how far a slope bends a ray: the net forms when the bend is about half a wave
    val bend = focus * 0.5 * s * s

    fun gather(k: Double): FloatArray {
        val sum = AtomicIntegerArray(w * h)
        val unit = 256
        parallelRows(eh * RAYS) { sy ->
            val fy = (sy + 0.5) / RAYS
            // the slope between the pixels' middles, so neighbouring rays bend smoothly apart
            val ry = (fy - 0.5).coerceIn(0.0, eh - 1.0)
            val y0 = min(eh - 2, ry.toInt()).coerceAtLeast(0)
            val ty0 = ry - y0
            for (sx in 0 until ew * RAYS) {
                val fx = (sx + 0.5) / RAYS
                val rx = (fx - 0.5).coerceIn(0.0, ew - 1.0)
                val x0 = min(ew - 2, rx.toInt()).coerceAtLeast(0)
                val tx0 = rx - x0
                val i = y0 * ew + x0
                fun lerp(a: FloatArray) = (a[i] * (1 - tx0) + a[i + 1] * tx0) * (1 - ty0) + (a[i + ew] * (1 - tx0) + a[i + ew + 1] * tx0) * ty0
                // where the ray lands on the canvas, spread over the four pixels around it
                val kk = if (local == null) k else k * lerp(local)
                val lx = fx - margin + lerp(gx) * kk - 0.5
                val ly = fy - margin + lerp(gy) * kk - 0.5
                val ix = floor(lx).toInt()
                val iy = floor(ly).toInt()
                if (ix < -1 || iy < -1 || ix >= w || iy >= h) continue
                val tx = lx - ix
                val ty = ly - iy
                fun add(x: Int, y: Int, wt: Double) {
                    if (x in 0 until w && y in 0 until h) sum.getAndAdd(y * w + x, (wt * unit / (RAYS * RAYS) + 0.5).toInt())
                }
                add(ix, iy, (1 - tx) * (1 - ty))
                add(ix + 1, iy, tx * (1 - ty))
                add(ix, iy + 1, (1 - tx) * ty)
                add(ix + 1, iy + 1, tx * ty)
            }
        }
        val light = FloatArray(w * h) { sum.get(it) / unit.toFloat() }
        // softened by how unsharp the net should be
        return Blur.gauss(light, w, h, 0.5 + (1 - sharpness) * s * 0.03)
    }
    if (dispersion <= 0) return arrayOf(gather(bend))
    return arrayOf(gather(bend * (1 - dispersion * 0.08)), gather(bend), gather(bend * (1 + dispersion * 0.08)))
}

/**
 * Water caustics on their own, for the generator: the wobbling net of light on the bottom of a
 * pool, the sea or a pond, over water colors darker in the hollows of the surface – or only the
 * light. Animate the time; with a seamless loop the time's loop length comes back to the start.
 */
object Caustics : Effect("caustics", "Water caustics", "The wobbling net of light under water – pool, ocean, rings or cells; animate the time, also as a seamless loop") {
    override val params = listOf(
        Param.Slider("time", "Time", 0, 100000, 0, "", "Moves the water – animate it", decimals = 2),
        Param.Toggle("loop", "Seamless loop", false, "At the loop length the water is exactly as at 0: animate the time from 0 to the loop length"),
        Param.Slider("loopLength", "Loop length", 100, 100000, 2000, "", "Of the time, for a seamless loop", decimals = 2),
        Param.Heading("wavesHeading", "Waves"),
        Param.Choice(
            "waves", "Waves", waveTypes, 0,
            "Pool: soft swells from all sides · Ocean: wave trains from one direction – long, parallel bands of light · " +
                "Rings: circular waves running out from points · Cells: a dimpled surface – an even, honeycomb-like net",
        ),
        Param.Points("points", "Ring centers", "0.5000,0.5000", "Where the rings start – drag them in the picture", activeWhen = "waves" to 2),
        Param.Slider("size", "Wave size", 300, 50000, 6000, " px", decimals = 2, canvasMax = true, transform = true),
        Param.Slider("direction", "Direction", -18000, 18000, 0, "°", "Ocean: where the waves run · Pool and cells: the direction the stretch draws them out along", decimals = 2),
        Param.Slider("stretch", "Stretch", 0, 100, 0, " %", "Pool and cells: drawn out along the direction, like wind on the water"),
        Param.Slider("detail", "Detail", 0, 100, 50, " %", "How much the smaller waves add"),
        Param.Heading("lightHeading", "Light"),
        Param.Slider("focus", "Focus", 10, 300, 60, " %", "How strongly the waves gather the light: low = soft ripples, high = a tight, folded net"),
        Param.Slider("focusVariation", "Focus variation", 0, 100, 0, " %", "The focus varies across the picture at random – tight nets here, soft ripples there; Reroll for another spread"),
        Param.Slider("sharpness", "Sharpness", 0, 100, 80, " %"),
        Param.Slider("dispersion", "Dispersion", 0, 100, 0, " %", "Red, green and blue bent apart: colored fringes along the net"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("deep", "Water", 0x0A4A66),
        Param.Color("shallow", "Water, light", 0x1E9BB8, "Where the surface bulges up"),
        Param.Color("light", "Light", 0xE6FFFF),
        Param.Slider("brightness", "Brightness", 0, 300, 100, " %"),
        Param.Slider("contrast", "Contrast", 0, 100, 60, " %", "How much darker the water between the bright lines is"),
        Param.Toggle("transparent", "Transparent background", false, "Only the light, without the water"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val size = max(4.0, v["size"] / 100.0)
        val time = v["time"] / 100.0
        val rings = PointList.parse(v.text("points")).map { doubleArrayOf(it.x * w, it.y * h) }
        val water = Water(
            noise, v["waves"], size, Math.toRadians(v["direction"] / 100.0), v["stretch"] / 100.0, v["detail"] / 100.0,
            rings, if (v.bool("loop")) v["loopLength"] / 100.0 else 0.0,
        )
        val light = causticLight(w, h, noise, water, time, v["focus"] / 100.0, v["focusVariation"] / 100.0, v["sharpness"] / 100.0, v["dispersion"] / 100.0)
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        val deep = v["deep"]
        val shallow = v["shallow"]
        val lc = v["light"]
        val brightness = v["brightness"] / 100.0
        val contrast = v["contrast"] / 100.0
        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                // the water: lighter where the surface bulges
                val bulge = (0.5 + 0.35 * water.height(x.toDouble(), y.toDouble(), time)).coerceIn(0.0, 1.0)
                fun channel(k: Int, sh: Int): Pair<Double, Double> {
                    // (the blur can leave tiny negatives)
                    val l = max(0.0, light[if (light.size == 3) k else 0][i].toDouble())
                    // bright lines stand out, the cells between them sink in
                    val shine = l.pow(1 + contrast * 1.2) * brightness * 0.16
                    val water = ch(deep, sh) + (ch(shallow, sh) - ch(deep, sh)) * bulge
                    return water to shine * ch(lc, sh)
                }
                val (wr, lr) = channel(0, 16)
                val (wg, lg) = channel(1, 8)
                val (wb, lb) = channel(2, 0)
                if (transparent) {
                    val a = min(1.0, max(lr, max(lg, lb)))
                    if (a <= 0.002) continue
                    fun c8(q: Double) = (min(1.0, q / a) * 255 + 0.5).toInt().coerceIn(0, 255)
                    out.data[i] = ((a * 255 + 0.5).toInt() shl 24) or (c8(lr) shl 16) or (c8(lg) shl 8) or c8(lb)
                } else {
                    fun c8(q: Double) = (min(1.0, q) * 255 + 0.5).toInt().coerceIn(0, 255)
                    out.data[i] = (0xFF shl 24) or (c8(wr + lr) shl 16) or (c8(wg + lg) shl 8) or c8(wb + lb)
                }
            }
        }
        return out
    }
}
