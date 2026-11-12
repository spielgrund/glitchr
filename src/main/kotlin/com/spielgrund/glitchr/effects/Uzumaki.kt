package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Uzumaki: a cut-out sky becomes the dome of a 360° sphere we stand in and look around in with a
 * camera, as in the Sky effects. Whatever of the layer isn't transparent is the sky: in every
 * column its lowest pixel sits on the horizon and the picture rises from there towards the
 * zenith; the picture's width runs around, repeated and mirrored so no seam shows, and where the
 * picture ends before the zenith it carries on, mirrored, up to the top. Below the horizon it is
 * empty (for the ground of the layers below) or mirrored. Holes in the cut-out sky are filled
 * from around them, so the dome has no gaps. From the zenith a whirl winds down, tighter towards
 * the top, and the sky can flow along it – into the vortex or around – like the Flow effects:
 * two copies travelling and cross-fading, so it streams on endlessly with the time.
 */
object Uzumaki : Effect("uzumaki", "Uzumaki", "A cut-out sky becomes the dome of a 360° sphere you look around in – with a whirl winding down from the zenith, the sky flowing along it") {
    private val belows = listOf("Empty", "Mirrored")
    private val projections = listOf("Camera", "Panorama (360°)", "Fisheye (looking up)")

    override val params = listOf(
        Param.Heading("domeHeading", "The dome"),
        Param.Slider("reach", "Sky reaches", 1000, 9000, 6000, "°", "How high up the dome the picture's top edge comes – above that the sky carries on, mirrored, to the zenith", decimals = 2),
        Param.Slider("repeats", "Repeats", 1, 16, 2, tip = "How many times the picture runs around – mirrored every other time, so it joins without a seam"),
        Param.Slider("spin", "Spin", -36000, 36000, 0, "°", "Turns the dome around the zenith – animate it", decimals = 2),
        Param.Choice("below", "Below the horizon", belows, 0, "Empty: transparent, for a ground on the layers below · Mirrored: the sky again, as in still water"),
        Param.Heading("whirlHeading", "Whirl"),
        Param.Slider("twist", "Twist", -144000, 144000, 0, "°", "From the zenith down: at the top the sky turns this much further than at the horizon", decimals = 2),
        Param.Slider("curve", "Curve", 20, 500, 200, " %", "How the whirl grows towards the zenith: higher = only close to the top, lower = far down the dome"),
        Param.Heading("flowHeading", "Flow"),
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Lets the sky flow – animate it", decimals = 3),
        Param.Slider("inward", "Into the whirl", -9000, 9000, 0, "°/s", "How fast the sky streams along the whirl's arms up into the zenith – minus: out of it", decimals = 2),
        Param.Slider("around", "Around", -36000, 36000, 0, "°/s", "How fast it streams around the zenith", decimals = 2),
        Param.Slider("distance", "Flow distance", 100, 9000, 1500, "°", "How far a copy travels before it fades out and starts over: longer flows smoother but smears more", decimals = 2),
        Param.Heading("cameraHeading", "Camera"),
        Param.Choice("projection", "Projection", projections, 0, "Camera: a view in one direction · Panorama: all around (360°), e.g. for an HDRI · Fisheye: straight up, the horizon round the edge"),
        Param.Slider("heading", "Heading", -18000, 18000, 0, "°", "Where the camera looks around", decimals = 2),
        Param.Slider("tilt", "Tilt", -9000, 9000, 0, "°", "Up (positive) or down", decimals = 2).shownWhen("projection", 0),
        Param.Slider("roll", "Roll", -18000, 18000, 0, "°", "Turns the view around its axis", decimals = 2).shownWhen("projection", 0, 2),
        Param.Slider("fov", "Field of view", 500, 17000, 9000, "°", "Across the picture: small = telephoto, large = wide angle", decimals = 2).shownWhen("projection", 0),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // the sky's lower edge in every column: its lowest pixel that isn't (mostly) transparent
        val horizon = IntArray(w) { -1 }
        for (x in 0 until w) for (y in h - 1 downTo 0) if ((src.data[y * w + x] ushr 24) > 127) { horizon[x] = y; break }
        if (horizon.all { it < 0 }) return Pixels(w, h)
        // empty columns take the nearest one with sky
        for (x in 0 until w) if (horizon[x] < 0) {
            var d = 1
            while (true) {
                if (x - d >= 0 && horizon[x - d] >= 0) { horizon[x] = horizon[x - d]; break }
                if (x + d < w && horizon[x + d] >= 0) { horizon[x] = horizon[x + d]; break }
                d++
            }
        }

        // something cut out from below – a tree, a roof – mustn't pull the horizon up: the horizon is the
        // lowest sky around (a twentieth of the width either way), and what lies above it counts as a hole
        val around = max(1, w / 20)
        val lowest = IntArray(w) { x -> (max(0, x - around)..min(w - 1, x + around)).maxOf { horizon[it] } }
        for (x in 0 until w) horizon[x] = lowest[x]
        val tex = fillHoles(src, horizon)

        val reach = Math.toRadians(v["reach"] / 100.0)
        val repeats = v["repeats"]
        val spin = Math.toRadians(v["spin"] / 100.0)
        val twist = Math.toRadians(v["twist"] / 100.0)
        val curve = v["curve"] / 100.0
        // the flow: two copies travelling along (into the whirl, around), each fading out as the other comes
        val inward = Math.toRadians(v["inward"] / 100.0)
        val aroundSpeed = Math.toRadians(v["around"] / 100.0)
        val speed = hypot(inward, aroundSpeed)
        val flowDistance = Math.toRadians(v["distance"] / 100.0)
        val time = v["time"] / 1000.0
        val flowing = speed > 1e-9
        val phase0 = if (flowing) (time * speed / flowDistance).let { it - floor(it) } else 0.0
        val mirrored = v["below"] == 1
        val projection = v["projection"]

        // the camera's frame (y up), as in the Sky effects
        val heading = Math.toRadians(v["heading"] / 100.0)
        val tilt = Math.toRadians(v["tilt"] / 100.0)
        val roll = Math.toRadians(v["roll"] / 100.0)
        val half = tan(Math.toRadians(v["fov"] / 100.0).coerceIn(0.01, 3.0) / 2)
        val fx = sin(heading) * cos(tilt); val fy = sin(tilt); val fz = cos(heading) * cos(tilt)
        val r0x = cos(heading); val r0z = -sin(heading)
        val u0x = -sin(heading) * sin(tilt); val u0y = cos(tilt); val u0z = -cos(heading) * sin(tilt)
        val rx = r0x * cos(roll) + u0x * sin(roll); val ry = u0y * sin(roll); val rz = r0z * cos(roll) + u0z * sin(roll)
        val ux = u0x * cos(roll) - r0x * sin(roll); val uy = u0y * cos(roll); val uz = u0z * cos(roll) - r0z * sin(roll)

        /** The sky at longitude [lon] (already whirled) and elevation [el] (0 = horizon). */
        fun sky(lon: Double, el: Double): Int {
            val a = ((lon % (2 * PI)) + 2 * PI) % (2 * PI)
            val u = a / (2 * PI) * repeats
            val n = floor(u)
            var f = u - n
            // every other time around mirrored: no seam
            if (n.toLong() % 2 != 0L) f = 1 - f
            val sx = (f * w).coerceIn(0.5, w - 0.5)
            // up the picture from its horizon to its top edge; beyond that, back down again (mirrored)
            var k = abs(el) / reach
            k %= 2.0
            if (k > 1) k = 2 - k
            val bottom = horizon[sx.toInt().coerceIn(0, w - 1)] + 0.5
            val sy = bottom - bottom * k
            return sampleBilinear(tex, sx, sy.coerceIn(0.5, bottom), Edge.CLAMP)
        }

        val out = Pixels(w, h)
        parallelRows(h) { py ->
            for (px in 0 until w) {
                val sx = px + 0.5
                val sy = py + 0.5
                var dx: Double; var dy: Double; var dz: Double
                when (projection) {
                    1 -> {
                        val lon = (sx / w * 2 - 1) * PI + heading
                        val lat = (0.5 - sy / h) * PI
                        dx = cos(lat) * sin(lon); dy = sin(lat); dz = cos(lat) * cos(lon)
                    }
                    2 -> {
                        val s = min(w, h) / 2.0
                        val ex = (sx - w / 2.0) / s
                        val ey = (sy - h / 2.0) / s
                        val zen = hypot(ex, ey) * PI / 2
                        val az = atan2(ex, -ey) + heading + roll
                        dx = sin(zen) * sin(az); dy = cos(zen); dz = sin(zen) * cos(az)
                    }
                    else -> {
                        val cx = (sx / w * 2 - 1) * half
                        val cy = (1 - sy / h * 2) * half * h / w
                        dx = fx + rx * cx + ux * cy; dy = fy + ry * cx + uy * cy; dz = fz + rz * cx + uz * cy
                        val len = sqrt(dx * dx + dy * dy + dz * dz)
                        dx /= len; dy /= len; dz /= len
                    }
                }
                if (dy < 0 && !mirrored) continue
                val el = abs(asin(dy.coerceIn(-1.0, 1.0)))
                // how far up towards the zenith (0..1): the whirl grows with it
                val t = el / (PI / 2)
                val whirl = twist * t.pow(curve)
                val lon = atan2(dx, dz) - spin - whirl
                val i = py * w + px
                if (!flowing) { out.data[i] = sky(lon, el); continue }
                // two copies, half a cycle apart: each travels the flow distance, fading in and out (as in Flow, smooth)
                var r = 0.0; var g = 0.0; var b = 0.0; var al = 0.0
                for (layer in 0..1) {
                    var ph = phase0 + layer * 0.5
                    ph -= floor(ph)
                    val weight = 1 - abs(2 * ph - 1)
                    if (weight <= 0) continue
                    val travelled = ph * flowDistance
                    // the sky came from further out (and behind): against the flow
                    val c = sky(lon - aroundSpeed / speed * travelled, el - inward / speed * travelled)
                    val ca = (c ushr 24) / 255.0 * weight
                    al += ca; r += (c shr 16 and 0xFF) * ca; g += (c shr 8 and 0xFF) * ca; b += (c and 0xFF) * ca
                }
                out.data[i] = if (al <= 0) 0 else
                    ((al * 255).roundToInt().coerceIn(0, 255) shl 24) or ((r / al).roundToInt().coerceIn(0, 255) shl 16) or
                        ((g / al).roundToInt().coerceIn(0, 255) shl 8) or (b / al).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /**
     * The cut-out sky made whole: every transparent hole above a column's horizon is filled from
     * the sky around it (pull–push: averaged over ever coarser grids, then pushed back into the
     * gaps), and below the horizon each column carries on with its horizon color – so the dome
     * texture is opaque everywhere.
     */
    private fun fillHoles(src: Pixels, horizon: IntArray): Pixels {
        val w = src.width
        val h = src.height
        // premultiplied colors and coverage of the sky (only above the horizon)
        var lw = w
        var lh = h
        var r = FloatArray(w * h); var g = FloatArray(w * h); var b = FloatArray(w * h); var a = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (y > horizon[x]) continue
            val i = y * w + x
            val c = src.data[i]
            val ca = (c ushr 24) / 255f
            a[i] = ca; r[i] = (c shr 16 and 0xFF) * ca; g[i] = (c shr 8 and 0xFF) * ca; b[i] = (c and 0xFF) * ca
        }
        if (a.all { it >= 1f }) return src
        // pull: ever coarser, coverage summed (at most 1), colors along
        val levels = ArrayList<Array<FloatArray>>()
        val sizes = ArrayList<IntArray>()
        levels += arrayOf(r, g, b, a); sizes += intArrayOf(lw, lh)
        while (lw > 1 || lh > 1) {
            val nw = (lw + 1) / 2
            val nh = (lh + 1) / 2
            val nr = FloatArray(nw * nh); val ng = FloatArray(nw * nh); val nb = FloatArray(nw * nh); val na = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = min(lw - 1, 2 * x + dx)
                    val yy = min(lh - 1, 2 * y + dy)
                    val k = yy * lw + xx
                    sr += r[k]; sg += g[k]; sb += b[k]; sa += a[k]
                }
                val k = y * nw + x
                if (sa > 0) {
                    val cover = min(1f, sa)
                    nr[k] = sr / sa * cover; ng[k] = sg / sa * cover; nb[k] = sb / sa * cover; na[k] = cover
                }
            }
            r = nr; g = ng; b = nb; a = na; lw = nw; lh = nh
            levels += arrayOf(r, g, b, a); sizes += intArrayOf(lw, lh)
        }
        // push: the gaps of each level filled from the next coarser one
        for (l in levels.size - 2 downTo 0) {
            val (cr, cg, cb, ca) = levels[l + 1]
            val cw = sizes[l + 1][0]
            val (fr, fg, fb, fa) = levels[l]
            val fw = sizes[l][0]
            val fh = sizes[l][1]
            for (y in 0 until fh) for (x in 0 until fw) {
                val k = y * fw + x
                val missing = 1f - fa[k]
                if (missing <= 0f) continue
                val ck = (y / 2) * cw + x / 2
                val cov = ca[ck]
                if (cov <= 0f) continue
                fr[k] += cr[ck] / cov * missing; fg[k] += cg[ck] / cov * missing; fb[k] += cb[ck] / cov * missing; fa[k] = 1f
            }
        }
        val (fr, fg, fb, fa) = levels[0]
        val out = Pixels(w, h)
        for (x in 0 until w) {
            val top = horizon[x]
            for (y in 0 until h) {
                // below the horizon: its color carried on downwards
                val k = min(y, top).coerceAtLeast(0) * w + x
                val cov = if (fa[k] > 0f) fa[k] else 1f
                fun c8(q: Float) = (q / cov).roundToInt().coerceIn(0, 255)
                out.data[y * w + x] = (0xFF shl 24) or (c8(fr[k]) shl 16) or (c8(fg[k]) shl 8) or c8(fb[k])
            }
        }
        return out
    }
}
