package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.BasicStroke
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The glossy abstract 3D look of early-2000s design portfolios as a generator of its own:
 * shards, wireframe worlds, glass, particle vortices, exploding shard bursts, glowing crystal
 * spikes, twisting ribbon bundles, rushing slabs, liquid chrome and chrome blades – a real 3D
 * scene, turnable on all three axes and seen in perspective, with light streaks, glowing rings,
 * little tech labels and bloom.
 */
object Trendwhore : Effect("trendwhore", "Trendwhore 3D", "Glossy early-2000s 3D abstracts – shards, crystals, ribbons, slabs, liquid chrome – turnable in space") {
    override val params = listOf(
        Param.Choice(
            "twStyle", "Style", TrendwhoreShapes.STYLES, 4,
            "Shards & ribbons · Wireframe · Glass tubes · Particle vortex · Shard burst: an explosion of shards · Crystal spikes: glowing clusters of spikes · " +
                "Ribbon twist: a twisting bundle of bands · Slab rush: slabs rushing at you · Liquid chrome: blobby chrome tubes and loops · Chrome blades: sweeping chrome with a wire globe",
        ),
        Param.Choice("twPalette", "Palette", listOf("Style's own") + TrendwhoreShapes.PALETTES, 0, "Style's own: the colors that go with the style"),
        Param.Slider("size", "Size", 1000, 30000, 10000, " %", decimals = 2, transform = true),
        Param.Slider("shards", "Amount", 0, 100, 50, " %", "How many of the style's main shapes: shards, bodies, bubbles, particles, spikes, slabs or cubes"),
        Param.Slider("ribbons", "Secondary", 0, 100, 50, " %", "The style's second element: ribbons, mesh, tubes, trails, big shards, loose spikes, bands, light panels or blades"),
        Param.Slider("light", "Light", 0, 100, 50, " %", "Bright streaks and glowing rings"),
        Param.Slider("hud", "Tech bits", 0, 100, 40, " %", "Little labels, codes, barcodes and crosses"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %"),
        Param.Slider("twTurnX", "Turn X", -18000, 18000, 0, "°", "Tips the scene forwards or back around the vanishing point", decimals = 2),
        Param.Slider("twTurnY", "Turn Y", -18000, 18000, 0, "°", "Turns the scene sideways", decimals = 2),
        Param.Slider("twRotation", "Rotation (Z)", -18000, 18000, 0, "°", "Turns the scene in the picture plane", decimals = 2),
        Param.Slider("twPerspective", "Perspective", 0, 100, 50, " %", "How strongly near parts grow and far ones shrink"),
        Param.Toggle("twMirror", "Mirror", false, "Symmetric, like a fractal creature"),
        Param.Toggle("twBackground", "Own background", true, "A gradient in the palette; off: drawn on the picture"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels = TrendwhoreShapes.draw(src, v, seed)
}

internal object TrendwhoreShapes {
    val STYLES = listOf(
        "Shards & ribbons", "Wireframe", "Glass tubes", "Particle vortex", "Shard burst",
        "Crystal spikes", "Ribbon twist", "Slab rush", "Liquid chrome", "Chrome blades",
    )

    /** A palette: background from top to bottom, three colors and the brightest one. */
    private class Pal(val name: String, val top: Int, val bottom: Int, val colors: IntArray, val bright: Int) {
        /** Light backgrounds want dark lines and little bloom. */
        val light = (bottom shr 16 and 0xFF) + (bottom shr 8 and 0xFF) + (bottom and 0xFF) > 450
    }

    private fun classic(name: String, p: IntArray) = Pal(name, mix(p[0], p[1], 0.35), p[0], intArrayOf(p[1], p[2], p[3]), p[4])

    private val palettes = listOf(
        classic("Ice blue", intArrayOf(0x030A14, 0x0B3D91, 0x2F7DE1, 0x9FD3FF, 0xFFFFFF)),
        classic("Red and white", intArrayOf(0xF4F6F8, 0x9A0E1C, 0xE0202E, 0x2A1A4A, 0x8FB8D8)),
        classic("Cyan glow", intArrayOf(0x020407, 0x0A3A44, 0x18B6C8, 0x7FF5FF, 0xFFFFFF)),
        classic("Amber", intArrayOf(0x0A0603, 0x5A2A04, 0xE8891A, 0xFFD27A, 0xFFFFFF)),
        Pal("Copper and navy", 0xFFFFFF, 0xE6E9F0, intArrayOf(0xC8735E, 0x1C2870, 0x6E84CC), 0x8E9CCC),
        Pal("Crimson night", 0x3A1530, 0x12070E, intArrayOf(0xFF3B2F, 0xE0224A, 0xFF7A4A), 0xFFB09A),
        Pal("Deep blue", 0x0A2A6A, 0x01040E, intArrayOf(0x1E6FE0, 0x0B3FA8, 0x5BA8FF), 0xCFE6FF),
        Pal("Red and navy", 0xFFFFFF, 0xD9E4EE, intArrayOf(0xD0101E, 0x1A1440, 0xA8C4DC), 0x9FB8D0),
        Pal("Silver", 0xEEF2F6, 0x8E9BA7, intArrayOf(0xC9D3DC, 0x7E8C99, 0xF4F8FB), 0xFFFFFF),
        Pal("Silver night", 0x2A3440, 0x05080C, intArrayOf(0xB8C6D2, 0x5E7486, 0xE6F0F8), 0xFFFFFF),
    )
    val PALETTES = palettes.map { it.name }

    /** The palette each style comes in by default. */
    private val ownPalette = intArrayOf(0, 2, 2, 3, 4, 5, 6, 7, 8, 9)

    /**
     * The camera: points (x, y, z) around the vanishing point – z away from us – turned about
     * y, then x, then z and seen in perspective from [focal] in front of it.
     */
    private class View(val cx: Double, val cy: Double, val focal: Double, turnX: Double, turnY: Double, turnZ: Double) {
        private val cX = cos(turnX); private val sX = sin(turnX)
        private val cY = cos(turnY); private val sY = sin(turnY)
        private val cZ = cos(turnZ); private val sZ = sin(turnZ)
        private val r = DoubleArray(3)

        /** The point turned with the scene, into [out]; the eye is at (0, 0, -focal). */
        fun turn(x: Double, y: Double, z: Double, out: DoubleArray) {
            val x1 = x * cY + z * sY
            val z1 = -x * sY + z * cY
            val y2 = y * cX - z1 * sX
            out[2] = y * sX + z1 * cX
            out[0] = x1 * cZ - y2 * sZ
            out[1] = x1 * sZ + y2 * cZ
        }

        /** How far the point lies from the eye, along the view. */
        fun distance(x: Double, y: Double, z: Double): Double {
            turn(x, y, z, r)
            return focal + r[2]
        }

        /** Screen x, y and the scale there into [out]; false when the point lies behind the eye. */
        fun at(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
            turn(x, y, z, r)
            val d = focal + r[2]
            if (d < focal * 0.05) return false
            val f = focal / d
            out[0] = cx + r[0] * f
            out[1] = cy + r[1] * f
            out[2] = f
            return true
        }

        /** A line through space on screen, cut off where it passes behind the eye; null if all of it does. */
        fun segment(a: DoubleArray, b: DoubleArray): Line2D.Double? {
            val near = focal * 0.05
            val da = distance(a[0], a[1], a[2])
            val db = distance(b[0], b[1], b[2])
            if (da < near && db < near) return null
            fun cut(p: DoubleArray, q: DoubleArray, dp: Double, dq: Double): DoubleArray =
                if (dp >= near) p else DoubleArray(3) { p[it] + (q[it] - p[it]) * (near - dp) / (dq - dp) }
            val p = cut(a, b, da, db)
            val q = cut(b, a, db, da)
            val sp = DoubleArray(3); val sq = DoubleArray(3)
            if (!at(p[0], p[1], p[2], sp) || !at(q[0], q[1], q[2], sq)) return null
            return Line2D.Double(sp[0], sp[1], sq[0], sq[1])
        }

        /** A curve through space on screen: x, y and scale per point, or null if a point lies behind the eye. */
        fun curve(xs: DoubleArray, ys: DoubleArray, zs: DoubleArray): Array<DoubleArray>? {
            val n = xs.size
            val sx = DoubleArray(n); val sy = DoubleArray(n); val sf = DoubleArray(n)
            val o = DoubleArray(3)
            for (i in 0 until n) {
                if (!at(xs[i], ys[i], zs[i], o)) return null
                sx[i] = o[0]; sy[i] = o[1]; sf[i] = o[2]
            }
            return arrayOf(sx, sy, sf)
        }

        /** A flat polygon through space on screen, or null if a corner lies behind the eye. */
        fun polygon(vararg p: DoubleArray): Path2D.Double? {
            val path = Path2D.Double()
            val o = DoubleArray(3)
            for ((i, q) in p.withIndex()) {
                if (!at(q[0], q[1], q[2], o)) return null
                if (i == 0) path.moveTo(o[0], o[1]) else path.lineTo(o[0], o[1])
            }
            path.closePath()
            return path
        }
    }

    /** How a face takes the light. */
    private enum class Finish { PLASTIC, CHROME, GLOW }

    /** A flat face of a 3D body: its corners in the scene, its color and finish. */
    private class Face(val p: Array<DoubleArray>, val color: Int, val finish: Finish = Finish.PLASTIC, val alpha: Double = 1.0)

    fun draw(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val random = Random(seed)
        val style = v["twStyle"]
        val p = palettes[if (v["twPalette"] == 0) ownPalette[style.coerceIn(0, ownPalette.size - 1)] else v["twPalette"] - 1]
        // the old palette layout for the first four styles: dark, three colors, bright
        val pal = intArrayOf(p.bottom, p.colors[0], p.colors[1], p.colors[2], p.bright)
        val dark = p.bottom
        // lines and labels: bright on dark grounds, the darkest color on light ones
        val ink = if (p.light) p.colors[1] else p.bright
        val shards = v["shards"] / 100.0
        val ribbons = v["ribbons"] / 100.0
        val light = v["light"] / 100.0
        val hud = v["hud"] / 100.0
        val s = min(w, h) * (v["size"] / 100.0) / 100.0
        fun pick() = pal[1 + random.nextInt(pal.size - 1)]
        val bodies = style >= 4
        val vx = w * if (bodies) 0.45 + random.nextDouble() * 0.1 else 0.35 + random.nextDouble() * 0.3
        val vy = h * if (bodies) 0.45 + random.nextDouble() * 0.1 else 0.4 + random.nextDouble() * 0.45
        // stronger perspective: the eye closer to the scene
        val perspective = v["twPerspective"] / 100.0
        val view = View(
            vx, vy, s * (0.8 + 8 * (1 - perspective).pow(2)),
            Math.toRadians(v["twTurnX"] / 100.0), Math.toRadians(v["twTurnY"] / 100.0), Math.toRadians(v["twRotation"] / 100.0),
        )
        val o = DoubleArray(3)
        fun p3(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

        var out = drawOn(src) { g ->
            if (v.bool("twBackground")) {
                g.paint = GradientPaint(0f, 0f, color(p.top), 0f, h.toFloat(), color(p.bottom))
                g.fill(Rectangle2D.Double(0.0, 0.0, w, h))
            }
            when (style) {
                1 -> wireframe(g, view, random, s, pal, shards, ribbons)
                2 -> glass(g, view, random, s, pal, dark, shards, ribbons)
                3 -> vortex(g, view, random, s, pal, shards, ribbons)
                4 -> faces(g, view, burst(random, s, p, shards, ribbons))
                5 -> faces(g, view, crystals(random, s, p, shards, ribbons))
                6 -> faces(g, view, twist(random, s, p, shards, ribbons))
                7 -> faces(g, view, slabs(random, s, p, view.focal, shards, ribbons))
                8 -> faces(g, view, liquid(random, s, p, shards, ribbons))
                9 -> {
                    // the wire globe behind, the blades through it, speed streaks out of the middle
                    g.color = color(ink, 0.7)
                    g.stroke = BasicStroke((s * 0.0022).toFloat().coerceAtLeast(0.6f))
                    globe(g, view, 0.0, 0.0, s * 0.4, s * 0.38, random.nextDouble() * PI, random.nextDouble() * PI)
                    faces(g, view, blades(random, s, p, ribbons))
                    g.stroke = BasicStroke((s * 0.002).toFloat().coerceAtLeast(0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    repeat((shards * 160).toInt()) {
                        val a = random.nextDouble() * 2 * PI
                        val dz = (random.nextDouble() - 0.5) * 1.6
                        val d0 = s * (0.3 + random.nextDouble() * 1.2)
                        val d1 = d0 * (1.05 + random.nextDouble() * 0.25)
                        g.color = color(0xFFFFFF, 0.3 + random.nextDouble() * 0.6)
                        view.segment(p3(cos(a) * d0, sin(a) * d0, dz * d0), p3(cos(a) * d1, sin(a) * d1, dz * d1))?.let { g.draw(it) }
                    }
                }
                else -> {
                    // shards: long slabs out of the vanishing point, wider and lighter towards us
                    repeat((shards * 40).toInt()) {
                        val a = random.nextDouble() * 2 * PI
                        val dz = (random.nextDouble() - 0.5) * 1.2
                        val len = s * (0.5 + random.nextDouble() * 1.4)
                        val spread = 0.04 + random.nextDouble() * 0.12
                        val start = s * random.nextDouble() * 0.25
                        // along the slab and across it
                        fun at(d: Double, side: Double) = p3(cos(a) * d - sin(a) * side * d, sin(a) * d + cos(a) * side * d, dz * d)
                        val c1 = at(start, -spread * 0.3); val c2 = at(start, spread * 0.3)
                        val c3 = at(start + len, spread); val c4 = at(start + len, -spread)
                        val poly = view.polygon(c1, c4, c3, c2) ?: return@repeat
                        val c = pick()
                        view.at(c3[0], c3[1], c3[2], o)
                        g.paint = GradientPaint(vx.toFloat(), vy.toFloat(), color(mix(c, 0x000000, 0.6), 0.85), o[0].toFloat(), o[1].toFloat(), color(c, 0.25 + random.nextDouble() * 0.4))
                        g.fill(poly)
                        // a bright edge on one side
                        g.stroke = BasicStroke((s * 0.003).toFloat().coerceAtLeast(0.6f))
                        g.color = color(mix(c, 0xFFFFFF, 0.6), 0.6)
                        view.segment(c1, c4)?.let { g.draw(it) }
                    }
                    // ribbons: a bundle of thin bands flowing through the scene
                    repeat((ribbons * 3).toInt() + if (ribbons > 0) 1 else 0) {
                        val a = random.nextDouble() * 2 * PI
                        val ux = cos(a); val uy = sin(a)
                        val nx = -uy; val ny = ux
                        val cxB = w * (0.2 + random.nextDouble() * 0.6) - vx
                        val cyB = h * (0.2 + random.nextDouble() * 0.6) - vy
                        val bend = s * (random.nextDouble() - 0.5) * 1.5
                        val twist = random.nextDouble() * 3
                        val bands = 8 + (ribbons * 24).toInt()
                        for (k in 0 until bands) {
                            val off = (random.nextDouble() - 0.5) * s * 0.5
                            val len = s * (1.0 + random.nextDouble() * 1.2)
                            val n = 40
                            val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
                            for (i in 0 until n) {
                                val t = i / (n - 1.0) * 2 - 1
                                val side = off * cos(t * twist) + bend * (1 - t * t)
                                xs[i] = cxB + ux * t * len + nx * side
                                ys[i] = cyB + uy * t * len + ny * side
                                // the band twists through depth as well
                                zs[i] = off * sin(t * twist) * 1.5
                            }
                            val c = pick()
                            val width = s * (0.006 + random.nextDouble() * 0.02)
                            val (sx, sy, sf) = view.curve(xs, ys, zs) ?: continue
                            g.paint = GradientPaint(sx[0].toFloat(), sy[0].toFloat(), color(mix(c, 0x000000, 0.5), 0.0), sx[n / 2].toFloat(), sy[n / 2].toFloat(), color(c, 0.55))
                            g.fill(taperedStroke(sx, sy) { t -> width * sf[(t * (n - 1)).toInt()] * sin(PI * t).pow(0.4) })
                        }
                    }
                }
            }
            lights(g, view, random, s, ink, light)
            // hud: little labels, codes, barcodes and crosses, mostly near the edges – flat on the screen
            if (hud > 0) {
                val mono = Font("Monospaced", Font.BOLD, max(6, (s * 0.022).toInt()))
                g.font = mono
                repeat((hud * 30).toInt()) {
                    val x = if (random.nextBoolean()) random.nextDouble() * w * 0.2 + w * 0.02 else w * 0.78 + random.nextDouble() * w * 0.18
                    val y = random.nextDouble() * h
                    g.color = color(ink, 0.55 + random.nextDouble() * 0.4)
                    g.stroke = BasicStroke(1f)
                    when (random.nextInt(4)) {
                        0 -> g.drawString(listOf("SYS", "RX", "NODE", "CORE", "DATA", "GRID", "VEC", "CRUNCH")[random.nextInt(8)] + "-" + random.nextInt(100).toString().padStart(2, '0'), x.toFloat(), y.toFloat())
                        1 -> repeat(10 + random.nextInt(14)) { k -> if (random.nextBoolean()) g.fill(Rectangle2D.Double(x + k * s * 0.006, y, s * 0.003 * (1 + random.nextInt(2)), s * 0.03)) }
                        2 -> { val r = s * 0.012; g.draw(Line2D.Double(x - r, y, x + r, y)); g.draw(Line2D.Double(x, y - r, x, y + r)) }
                        else -> g.draw(Line2D.Double(x, y, x + s * (0.05 + random.nextDouble() * 0.15), y))
                    }
                }
            }
        }
        if (v.bool("twMirror")) {
            // a symmetric creature: the left half mirrored onto the right
            val wi = out.width
            for (y in 0 until out.height) for (x in wi / 2 until wi) out.data[y * wi + x] = out.data[y * wi + (wi - 1 - x)]
        }
        // bloom: on light grounds only a touch, or everything would wash out
        val glow = (v["glow"] * if (p.light) 0.3 else 1.0).toInt()
        if (glow > 0) out = Glow.apply(out, Glow.defaultValues(mapOf("threshold" to 78, "strength" to glow / 2, "radius" to max(4, (s * 0.04).toInt()))), seed)
        return out
    }

    // ---- faces: bodies made of flat faces, lit and drawn from far to near ----

    /** Draws [faces] lit by a light from the top left front, far ones first. */
    private fun faces(g: Graphics2D, view: View, faces: List<Face>) {
        class Drawn(val depth: Double, val shape: Path2D.Double, val color: java.awt.Color, val solid: Boolean)
        val drawn = ArrayList<Drawn>(faces.size)
        val near = view.focal * 0.05
        // towards the light: up, left and towards us
        val lx = -0.45; val ly = -0.75; val lz = -0.5
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (f in faces) {
            val n = f.p.size
            val c = Array(n) { DoubleArray(3) }
            var behind = false
            for (i in 0 until n) {
                view.turn(f.p[i][0], f.p[i][1], f.p[i][2], c[i])
                if (view.focal + c[i][2] < near) behind = true
            }
            if (behind) continue
            // the face's normal (Newell: fine for any polygon, even with a doubled corner)
            var nx = 0.0; var ny = 0.0; var nz = 0.0
            var mx = 0.0; var my = 0.0; var mz = 0.0
            for (i in 0 until n) {
                val a = c[i]; val b = c[(i + 1) % n]
                nx += (a[1] - b[1]) * (a[2] + b[2])
                ny += (a[2] - b[2]) * (a[0] + b[0])
                nz += (a[0] - b[0]) * (a[1] + b[1])
                mx += a[0]; my += a[1]; mz += a[2]
            }
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-12) continue
            nx /= nl; ny /= nl; nz /= nl
            mx /= n; my /= n; mz /= n
            // the view onto it, from the eye
            var ex = mx; var ey = my; var ez = mz + view.focal
            val el = sqrt(ex * ex + ey * ey + ez * ez)
            ex /= el; ey /= el; ez /= el
            // both sides are seen: always the side facing us
            if (nx * ex + ny * ey + nz * ez > 0) { nx = -nx; ny = -ny; nz = -nz }
            val diffuse = max(0.0, (nx * lx + ny * ly + nz * lz) / ll)
            // the mirrored view: what the face reflects
            val d = ex * nx + ey * ny + ez * nz
            val rx = ex - 2 * d * nx; val ry = ey - 2 * d * ny; val rz = ez - 2 * d * nz
            val toLight = max(0.0, (rx * lx + ry * ly + rz * lz) / ll)
            val base = f.color
            val rgb = when (f.finish) {
                Finish.CHROME -> {
                    // polished metal: sky above, a dark horizon band, the ground below, and the light's highlight
                    val env = when {
                        ry < -0.05 -> 0.78 + 0.22 * min(1.0, -ry * 2)
                        ry < 0.08 -> 0.06 + 0.16 * (ry + 0.05) / 0.13
                        else -> 0.3 + 0.45 * min(1.0, (ry - 0.08) * 1.5)
                    }
                    val l = env + toLight.pow(60) * 1.5
                    val tint = mix(0xFFFFFF, base, 0.45)
                    scale(tint, l)
                }
                Finish.GLOW -> mix(scale(base, 0.55 + 0.7 * diffuse), 0xFFFFFF, toLight.pow(20) * 0.8)
                // plastic: the color by how it faces the light, a white gloss on top, a little sky at grazing angles
                else -> mix(mix(scale(base, 0.35 + 0.75 * diffuse), 0xFFFFFF, toLight.pow(40) * 0.9), 0xFFFFFF, (1 - abs(d)).pow(4) * 0.25)
            }
            val path = Path2D.Double()
            for (i in 0 until n) {
                val q = view.focal / (view.focal + c[i][2])
                val sx = view.cx + c[i][0] * q
                val sy = view.cy + c[i][1] * q
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            path.closePath()
            drawn += Drawn(el, path, color(rgb, f.alpha), f.alpha >= 0.99)
        }
        drawn.sortByDescending { it.depth }
        val seam = BasicStroke(0.8f)
        for (d in drawn) {
            g.color = d.color
            g.fill(d.shape)
            // opaque faces a hair larger, so neighbours meet without a seam
            if (d.solid) { g.stroke = seam; g.draw(d.shape) }
        }
    }

    private fun vec(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    private fun add(a: DoubleArray, b: DoubleArray, k: Double = 1.0) = doubleArrayOf(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k)
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun unit(a: DoubleArray): DoubleArray {
        val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-12)
        return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }

    /** A random direction, evenly over the sphere. */
    private fun direction(random: Random): DoubleArray {
        val u = random.nextDouble() * 2 - 1
        val t = random.nextDouble() * 2 * PI
        val r = sqrt(1 - u * u)
        return vec(r * cos(t), r * sin(t), u)
    }

    /** Two directions across [d], at right angles to it and each other. */
    private fun across(d: DoubleArray, random: Random): Pair<DoubleArray, DoubleArray> {
        val a = unit(cross(d, direction(random)))
        return a to cross(d, a)
    }

    /** An explosion of shards out of one middle: many small dark ones, a few big ones in the first color. */
    private fun burst(random: Random, s: Double, p: Pal, amount: Double, big: Double): List<Face> {
        val faces = ArrayList<Face>()
        repeat(120 + (amount * 900).toInt()) {
            val d = direction(random)
            val large = random.nextDouble() < 0.04 + 0.1 * big
            val len = s * if (large) 0.4 + random.nextDouble() * 0.4 else 0.08 + random.nextDouble().pow(2) * 0.5
            val width = len * if (large) 0.12 + random.nextDouble() * 0.18 else 0.03 + random.nextDouble() * 0.09
            val (a, b) = across(d, random)
            val foot = add(vec(0.0, 0.0, 0.0), d, s * 0.08 * random.nextDouble())
            // a blade with a kink: two faces at an angle
            val p0 = add(foot, a, width / 2)
            val p1 = add(foot, a, -width / 2)
            val tip = add(add(add(foot, d, len), a, (random.nextDouble() - 0.5) * len * 0.15), b, (random.nextDouble() - 0.5) * len * 0.15)
            val ridge = add(add(foot, d, len * (0.4 + random.nextDouble() * 0.3)), b, width * (0.2 + random.nextDouble() * 0.5))
            val col = if (large) p.colors[0] else if (random.nextDouble() < 0.6) p.colors[1] else p.colors[2]
            faces += Face(arrayOf(p0, ridge, tip), col)
            faces += Face(arrayOf(p1, tip, ridge), mix(col, 0x000000, 0.15))
        }
        return faces
    }

    /** Glowing crystal spikes in clusters – long thin double pyramids – and loose ones around. */
    private fun crystals(random: Random, s: Double, p: Pal, amount: Double, loose: Double): List<Face> {
        val faces = ArrayList<Face>()
        fun spike(c: DoubleArray, d: DoubleArray, len: Double) {
            val width = len * (0.025 + random.nextDouble() * 0.035)
            val (a, b) = across(d, random)
            val back = add(c, d, -len * 0.12)
            val front = add(c, d, len)
            val ringAt = add(c, d, len * 0.1)
            val ring = (0 until 4).map { k -> add(add(ringAt, a, cos(k * PI / 2) * width), b, sin(k * PI / 2) * width) }
            val col = p.colors[random.nextInt(3)]
            for (k in 0 until 4) {
                faces += Face(arrayOf(ring[k], ring[(k + 1) % 4], front), col, Finish.GLOW, 0.8)
                faces += Face(arrayOf(ring[(k + 1) % 4], ring[k], back), col, Finish.GLOW, 0.8)
            }
        }
        repeat(2 + (amount * 9).toInt()) {
            val c = vec((random.nextDouble() - 0.5) * s * 2.2, (random.nextDouble() - 0.5) * s * 1.2, random.nextDouble() * s * 2)
            repeat(5 + random.nextInt(10)) { spike(c, direction(random), s * (0.2 + random.nextDouble() * 0.8)) }
        }
        repeat((loose * 14).toInt()) {
            spike(vec((random.nextDouble() - 0.5) * s * 3.5, (random.nextDouble() - 0.5) * s * 2.2, random.nextDouble() * s * 3), direction(random), s * (0.5 + random.nextDouble() * 1.0))
        }
        return faces
    }

    /** A bundle of glossy bands twisting around each other, fanning out towards us. */
    private fun twist(random: Random, s: Double, p: Pal, amount: Double, spread: Double): List<Face> {
        val faces = ArrayList<Face>()
        val turns = 2.5 + random.nextDouble() * 2.5
        val ph = random.nextDouble() * 2 * PI
        // the bundle's middle line: from near on the left to far on the right, with a swing
        fun middle(t: Double) = vec((t - 0.5) * s * 2.2, s * 0.25 * sin(PI * t * 1.2 + ph), (t - 0.3) * s * 1.2)
        repeat(12 + (amount * 70).toInt()) {
            val radius = s * (0.03 + random.nextDouble() * 0.22)
            val phase = random.nextDouble() * 2 * PI
            val tilt = (random.nextDouble() - 0.5) * 0.6
            val width = s * (0.02 + random.nextDouble() * 0.05)
            val t0 = random.nextDouble() * 0.2
            val t1 = 0.75 + random.nextDouble() * 0.25
            val col = mix(p.colors[random.nextInt(3)], p.bright, random.nextDouble() * 0.15)
            val m = 50
            var prev: Pair<DoubleArray, DoubleArray>? = null
            for (i in 0..m) {
                val u = i / m.toDouble()
                val t = t0 + (t1 - t0) * u
                // further apart towards us
                val r = radius * (0.5 + (0.6 + spread * 2.4) * (1 - t).pow(2))
                val a = phase + turns * t * 2 * PI
                val c = add(middle(t), vec(0.0, cos(a) * r, sin(a) * r))
                // the band's width direction, turned a little against the radius
                val b = vec(0.0, -sin(a + tilt), cos(a + tilt))
                val half = width / 2 * sin(PI * u).pow(0.3)
                val edge = add(c, b, half) to add(c, b, -half)
                prev?.let { q -> faces += Face(arrayOf(q.first, edge.first, edge.second, q.second), col) }
                prev = edge
            }
        }
        return faces
    }

    /** Slabs and columns rushing out of the depth towards us, with pale light panels far behind. */
    private fun slabs(random: Random, s: Double, p: Pal, focal: Double, amount: Double, panels: Double): List<Face> {
        val faces = ArrayList<Face>()
        fun box(cx: Double, cy: Double, angle: Double, a: Double, b: Double, zFar: Double, zNear: Double, col: Int, alpha: Double = 1.0) {
            val ca = cos(angle); val sa = sin(angle)
            fun corner(i: Int, j: Int, z: Double) = vec(cx + ca * a * i - sa * b * j, cy + sa * a * i + ca * b * j, z)
            val f = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
            for (k in 0 until 4) {
                val (i0, j0) = f[k]; val (i1, j1) = f[(k + 1) % 4]
                faces += Face(arrayOf(corner(i0, j0, zFar), corner(i1, j1, zFar), corner(i1, j1, zNear), corner(i0, j0, zNear)), if (k % 2 == 0) col else mix(col, 0x000000, 0.2), alpha = alpha)
            }
            faces += Face(f.map { (i, j) -> corner(i, j, zNear) }.toTypedArray(), mix(col, 0xFFFFFF, 0.1), alpha = alpha)
            faces += Face(f.map { (i, j) -> corner(i, j, zFar) }.toTypedArray(), col, alpha = alpha)
        }
        repeat((panels * 10).toInt()) {
            val a = random.nextDouble() * 2 * PI
            val r = s * (0.8 + random.nextDouble() * 1.5)
            box(cos(a) * r, sin(a) * r, a, s * (0.3 + random.nextDouble() * 0.6), s * 0.01, s * 9, s * (2 + random.nextDouble() * 4), p.colors[2], 0.3)
        }
        val groups = 2 + random.nextInt(3)
        val headings = DoubleArray(groups) { random.nextDouble() * 2 * PI }
        repeat(40 + (amount * 260).toInt()) {
            val angle = headings[random.nextInt(groups)] + (random.nextDouble() - 0.5) * 1.4
            val r = s * (0.04 + random.nextDouble().pow(1.5) * 0.9)
            val zFar = s * (1 + random.nextDouble() * 7)
            // never through the eye
            val zNear = max(zFar - s * (0.5 + random.nextDouble() * 4), -focal * 0.35)
            val roll = random.nextDouble()
            val col = if (roll < 0.55) p.colors[0] else if (roll < 0.9) p.colors[1] else p.colors[2]
            box(cos(angle) * r, sin(angle) * r, angle, s * (0.01 + random.nextDouble() * 0.05), s * (0.015 + random.nextDouble() * 0.14), zFar, zNear, col)
        }
        return faces
    }

    /**
     * A tube along the curve ([xs], [ys], [zs]), [radius] thick at share u, [sides] faces round,
     * closed into a loop if wanted.
     */
    private fun tube(faces: MutableList<Face>, xs: DoubleArray, ys: DoubleArray, zs: DoubleArray, sides: Int, col: Int, finish: Finish, loop: Boolean, radius: (Double) -> Double) {
        val n = xs.size
        fun pt(i: Int) = vec(xs[(i + n) % n], ys[(i + n) % n], zs[(i + n) % n])
        // a frame carried along the curve without twisting
        var normal: DoubleArray? = null
        val rings = ArrayList<List<DoubleArray>>()
        for (i in 0 until n) {
            val ahead = if (loop || i < n - 1) pt(i + 1) else pt(i)
            val behind = if (loop || i > 0) pt(i - 1) else pt(i)
            val t = unit(add(ahead, behind, -1.0))
            var nrm = normal ?: unit(cross(t, if (abs(t[1]) < 0.9) vec(0.0, 1.0, 0.0) else vec(1.0, 0.0, 0.0)))
            val dot = nrm[0] * t[0] + nrm[1] * t[1] + nrm[2] * t[2]
            nrm = unit(add(nrm, t, -dot))
            normal = nrm
            val bi = cross(t, nrm)
            val r = radius(i / (n - 1.0))
            val c = pt(i)
            rings += (0 until sides).map { k -> add(add(c, nrm, cos(k * 2 * PI / sides) * r), bi, sin(k * 2 * PI / sides) * r) }
        }
        val last = if (loop) n else n - 1
        for (i in 0 until last) {
            val a = rings[i]; val b = rings[(i + 1) % n]
            for (k in 0 until sides) faces += Face(arrayOf(a[k], a[(k + 1) % sides], b[(k + 1) % sides], b[k]), col, finish)
        }
    }

    /** Liquid chrome: blobby tubes winding through space, a loop or two, little chrome cubes floating around. */
    private fun liquid(random: Random, s: Double, p: Pal, cubes: Double, tubes: Double): List<Face> {
        val faces = ArrayList<Face>()
        repeat(2 + (tubes * 5).toInt()) { k ->
            val n = 90
            val loop = k == 0 || random.nextDouble() < 0.25
            val c = vec((random.nextDouble() - 0.5) * s * 1.6, (random.nextDouble() - 0.5) * s * 1.0, random.nextDouble() * s * 1.5)
            val amp = DoubleArray(3) { s * (0.15 + random.nextDouble() * 0.4) }
            val freq = IntArray(3) { 1 + random.nextInt(3) }
            val ph = DoubleArray(3) { random.nextDouble() * 2 * PI }
            val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
            for (i in 0 until n) {
                val t = i / n.toDouble() * 2 * PI
                if (loop) {
                    // a loop: a wobbling ring
                    val r = s * 0.35 * (1 + 0.25 * sin(t * freq[0] + ph[0]))
                    xs[i] = c[0] + cos(t) * r
                    ys[i] = c[1] + sin(t) * r * 0.8
                    zs[i] = c[2] + amp[2] * 0.4 * sin(t * freq[2] + ph[2])
                } else {
                    val u = i / (n - 1.0) * 2 - 1
                    xs[i] = c[0] + u * s * 0.9 + amp[0] * sin(u * 2.5 * freq[0] + ph[0])
                    ys[i] = c[1] + amp[1] * sin(u * 2.0 * freq[1] + ph[1])
                    zs[i] = c[2] + amp[2] * sin(u * 1.5 * freq[2] + ph[2])
                }
            }
            val thick = s * (0.07 + random.nextDouble() * 0.09)
            val blob = random.nextDouble() * 2 * PI
            tube(faces, xs, ys, zs, 14, mix(p.colors[random.nextInt(3)], 0xFFFFFF, 0.3), Finish.CHROME, loop) { u ->
                // swelling and thinning like a liquid, pointed at open ends
                val swell = 0.6 + 0.4 * sin(u * 9 + blob) * sin(u * 4 + blob * 0.7)
                thick * swell * if (loop) 1.0 else sin(PI * u).pow(0.35)
            }
        }
        repeat((cubes * 16).toInt()) {
            val c = vec((random.nextDouble() - 0.5) * s * 2.6, (random.nextDouble() - 0.5) * s * 1.6, random.nextDouble() * s * 2.5)
            val r = s * (0.02 + random.nextDouble() * 0.045)
            val (a, b) = across(direction(random), random)
            val d = cross(a, b)
            fun corner(i: Int, j: Int, k: Int) = add(add(add(c, a, i * r), b, j * r), d, k * r)
            val sides = listOf(
                listOf(corner(-1, -1, -1), corner(1, -1, -1), corner(1, 1, -1), corner(-1, 1, -1)),
                listOf(corner(-1, -1, 1), corner(1, -1, 1), corner(1, 1, 1), corner(-1, 1, 1)),
                listOf(corner(-1, -1, -1), corner(1, -1, -1), corner(1, -1, 1), corner(-1, -1, 1)),
                listOf(corner(-1, 1, -1), corner(1, 1, -1), corner(1, 1, 1), corner(-1, 1, 1)),
                listOf(corner(-1, -1, -1), corner(-1, 1, -1), corner(-1, 1, 1), corner(-1, -1, 1)),
                listOf(corner(1, -1, -1), corner(1, 1, -1), corner(1, 1, 1), corner(1, -1, 1)),
            )
            for (side in sides) faces += Face(side.toTypedArray(), p.colors[2], Finish.CHROME)
        }
        return faces
    }

    /** Wide chrome blades sweeping out of the depth past us, twisting as they go. */
    private fun blades(random: Random, s: Double, p: Pal, amount: Double): List<Face> {
        val faces = ArrayList<Face>()
        repeat(3 + (amount * 8).toInt()) {
            val a0 = random.nextDouble() * 2 * PI
            val sweep = (random.nextDouble() - 0.5) * 3
            val width = s * (0.12 + random.nextDouble() * 0.3)
            val turns = (random.nextDouble() - 0.5) * 2
            val col = p.colors[random.nextInt(3)]
            val m = 60
            val strips = 6
            // from far behind the middle out past the edge of the picture, coming closer
            fun centre(u: Double): DoubleArray {
                val a = a0 + sweep * u
                val r = s * (0.1 + 1.3 * u.pow(1.5))
                return vec(cos(a) * r, sin(a) * r, s * (4 - 4.6 * u))
            }
            var prev: List<DoubleArray>? = null
            for (i in 0..m) {
                val u = i / m.toDouble()
                val a = a0 + sweep * u
                val c = centre(u)
                val ahead = unit(add(centre(u + 0.01), c, -1.0))
                val twist = turns * PI * u
                val across = vec(-sin(a) * cos(twist), cos(a) * cos(twist), sin(twist))
                val bow = unit(cross(ahead, across))
                val half = width / 2 * (0.15 + 0.85 * sin(PI * u.coerceAtMost(0.95)).pow(0.5)) * (0.4 + u)
                // across the blade: a shallow arch
                val row = (0..strips).map { k ->
                    val q = k * 2.0 / strips - 1
                    add(add(c, across, half * q), bow, half * 0.35 * (1 - q * q))
                }
                prev?.let { pr -> for (k in 0 until strips) faces += Face(arrayOf(pr[k], row[k], row[k + 1], pr[k + 1]), col, Finish.CHROME) }
                prev = row
            }
        }
        return faces
    }

    /** A wire globe: rings of latitude and longitude, turned by [ax] and [ay]. */
    private fun globe(g: Graphics2D, view: View, cx: Double, cy: Double, cz: Double, r: Double, ax: Double, ay: Double) {
        fun on(lat: Double, lon: Double): DoubleArray {
            val x = cos(lat) * cos(lon); val y = sin(lat); val z = cos(lat) * sin(lon)
            val x1 = x * cos(ay) + z * sin(ay)
            val z1 = -x * sin(ay) + z * cos(ay)
            val y2 = y * cos(ax) - z1 * sin(ax)
            val z2 = y * sin(ax) + z1 * cos(ax)
            return doubleArrayOf(cx + x1 * r, cy + y2 * r, cz + z2 * r)
        }
        for (i in 1 until 6) for (j in 0 until 16) {
            val lat = -PI / 2 + PI * i / 6
            view.segment(on(lat, 2 * PI * j / 16), on(lat, 2 * PI * (j + 1) / 16))?.let { g.draw(it) }
        }
        for (j in 0 until 8) for (i in 0 until 12) {
            val lon = PI * j / 8
            view.segment(on(-PI / 2 + PI * i / 12, lon), on(-PI / 2 + PI * (i + 1) / 12, lon))?.let { g.draw(it) }
        }
    }

    // ---- the first four styles, drawn as lines and strokes ----

    /** Thin bright streaks out of the vanishing point and glowing rings – in every style. */
    private fun lights(g: Graphics2D, view: View, random: Random, s: Double, bright: Int, light: Double) {
        repeat((light * 18).toInt()) {
            val a = random.nextDouble() * 2 * PI
            val dz = (random.nextDouble() - 0.5) * 1.2
            val len = s * (0.6 + random.nextDouble() * 1.5)
            g.stroke = BasicStroke((s * 0.0025).toFloat().coerceAtLeast(0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = color(bright, 0.3 + random.nextDouble() * 0.4)
            val d0 = s * 0.1
            view.segment(doubleArrayOf(cos(a) * d0, sin(a) * d0, dz * d0), doubleArrayOf(cos(a) * len, sin(a) * len, dz * len))?.let { g.draw(it) }
        }
        repeat((light * 4).toInt()) {
            // an arc of a circle lying tilted in space
            val rx = s * (0.3 + random.nextDouble() * 0.6)
            val tilt = acos(0.15 + random.nextDouble() * 0.3)
            val spin = (random.nextDouble() - 0.5) * 1.2
            val mx = (random.nextDouble() - 0.5) * s
            val my = -random.nextDouble() * s * 0.6
            val from = Math.toRadians(random.nextDouble() * 360)
            val extent = Math.toRadians(120 + random.nextDouble() * 200)
            val n = 64
            val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
            for (i in 0 until n) {
                val t = from + extent * i / (n - 1)
                val u = cos(t) * rx
                val vv = sin(t) * rx
                xs[i] = mx + u * cos(spin) - vv * cos(tilt) * sin(spin)
                ys[i] = my + u * sin(spin) + vv * cos(tilt) * cos(spin)
                zs[i] = vv * sin(tilt)
            }
            val (sx, sy) = view.curve(xs, ys, zs) ?: return@repeat
            val ring = Path2D.Double().apply { moveTo(sx[0], sy[0]); for (i in 1 until n) lineTo(sx[i], sy[i]) }
            for (k in 4 downTo 0) {
                g.stroke = BasicStroke((s * 0.004 * (1 + k * 1.5)).toFloat().coerceAtLeast(0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = color(if (k == 0) 0xFFFFFF else bright, if (k == 0) 0.85 else 0.05)
                g.draw(ring)
            }
        }
    }

    /**
     * A wireframe world: a grid floor running into the distance, a rolling mesh above, and
     * floating wire bodies – cubes, icosahedra and globes ([bodies] many), each turned its own way.
     */
    private fun wireframe(g: Graphics2D, view: View, random: Random, s: Double, pal: IntArray, bodies: Double, mesh: Double) {
        val thin = BasicStroke((s * 0.0018).toFloat().coerceAtLeast(0.5f))
        g.stroke = thin
        // the floor: a grid below the vanishing point, fading into the distance
        val step = s * 0.35
        val floorY = s * 0.3
        val o = DoubleArray(3)
        for (i in -12..12) for (j in -3..24) {
            val x = i * step
            val z = j * step
            view.at(x, floorY, z, o)
            g.color = color(pal[2], (0.65 * o[2]).coerceIn(0.05, 0.7))
            view.segment(doubleArrayOf(x, floorY, z), doubleArrayOf(x + step, floorY, z))?.let { g.draw(it) }
            view.segment(doubleArrayOf(x, floorY, z), doubleArrayOf(x, floorY, z + step))?.let { g.draw(it) }
        }
        // the mesh: a rolling sheet above
        if (mesh > 0) {
            val amp = s * 0.25 * mesh
            val ph = random.nextDouble() * 2 * PI
            fun y(x: Double, z: Double) = -s * 0.9 + amp * sin(x / s * 2.2 + ph) * cos(z / s * 1.7)
            g.color = color(pal[3], 0.35)
            val ms = s * 0.25
            for (i in -14..14) for (j in -2..20) {
                val x = i * ms
                val z = j * ms
                val a = doubleArrayOf(x, y(x, z), z)
                view.segment(a, doubleArrayOf(x + ms, y(x + ms, z), z))?.let { g.draw(it) }
                view.segment(a, doubleArrayOf(x, y(x, z + ms), z + ms))?.let { g.draw(it) }
            }
        }
        // the bodies
        val phi = (1 + sqrt(5.0)) / 2
        val ico = listOf(
            doubleArrayOf(0.0, 1.0, phi), doubleArrayOf(0.0, -1.0, phi), doubleArrayOf(0.0, 1.0, -phi), doubleArrayOf(0.0, -1.0, -phi),
            doubleArrayOf(1.0, phi, 0.0), doubleArrayOf(-1.0, phi, 0.0), doubleArrayOf(1.0, -phi, 0.0), doubleArrayOf(-1.0, -phi, 0.0),
            doubleArrayOf(phi, 0.0, 1.0), doubleArrayOf(-phi, 0.0, 1.0), doubleArrayOf(phi, 0.0, -1.0), doubleArrayOf(-phi, 0.0, -1.0),
        ).map { p -> DoubleArray(3) { p[it] / sqrt(1 + phi * phi) } }
        val icoEdges = ArrayList<Pair<Int, Int>>().apply {
            for (a in ico.indices) for (b in a + 1 until ico.size) {
                val d = sqrt((0..2).sumOf { (ico[a][it] - ico[b][it]).pow(2) })
                if (d < 1.1) add(a to b)
            }
        }
        val cube = (0 until 8).map { k -> doubleArrayOf(if (k and 1 == 0) -0.6 else 0.6, if (k and 2 == 0) -0.6 else 0.6, if (k and 4 == 0) -0.6 else 0.6) }
        val cubeEdges = (0 until 8).flatMap { a -> listOf(1, 2, 4).mapNotNull { bit -> if (a and bit == 0) a to (a or bit) else null } }
        repeat((bodies * 10).toInt()) {
            val cx = (random.nextDouble() - 0.5) * s * 3
            val cy = (random.nextDouble() - 0.7) * s * 1.2
            val cz = random.nextDouble() * s * 4
            val r = s * (0.12 + random.nextDouble() * 0.28)
            val ax = random.nextDouble() * PI; val ay = random.nextDouble() * PI
            fun place(p: DoubleArray): DoubleArray {
                // the body's own turn, then into the scene
                val x1 = p[0] * cos(ay) + p[2] * sin(ay)
                val z1 = -p[0] * sin(ay) + p[2] * cos(ay)
                val y2 = p[1] * cos(ax) - z1 * sin(ax)
                val z2 = p[1] * sin(ax) + z1 * cos(ax)
                return doubleArrayOf(cx + x1 * r, cy + y2 * r, cz + z2 * r)
            }
            g.color = color(if (random.nextBoolean()) pal[3] else pal[pal.size - 1], 0.75)
            g.stroke = BasicStroke((s * 0.0025).toFloat().coerceAtLeast(0.6f))
            when (random.nextInt(3)) {
                0 -> for ((a, b) in cubeEdges) view.segment(place(cube[a]), place(cube[b]))?.let { g.draw(it) }
                1 -> for ((a, b) in icoEdges) view.segment(place(ico[a]), place(ico[b]))?.let { g.draw(it) }
                else -> globe(g, view, cx, cy, cz, r, ax, ay)
            }
        }
        g.stroke = thin
    }

    /** Glass: translucent tubes curling through space ([tubes]) and glossy bubbles ([bubbles]), far ones first. */
    private fun glass(g: Graphics2D, view: View, random: Random, s: Double, pal: IntArray, dark: Int, bubbles: Double, tubes: Double) {
        // everything as (distance, painter), drawn from far to near
        val items = ArrayList<Pair<Double, () -> Unit>>()
        repeat((tubes * 8).toInt() + if (tubes > 0) 1 else 0) {
            val c = 1 + random.nextInt(pal.size - 1)
            val col = pal[c]
            val bx = (random.nextDouble() - 0.5) * s * 1.6
            val by = (random.nextDouble() - 0.5) * s * 1.2
            val bz = random.nextDouble() * s * 2
            val a = random.nextDouble() * 2 * PI
            val dz = (random.nextDouble() - 0.5) * 1.4
            val len = s * (0.8 + random.nextDouble() * 1.2)
            val amp = DoubleArray(3) { s * (0.1 + random.nextDouble() * 0.35) }
            val freq = DoubleArray(3) { 1.5 + random.nextDouble() * 3 }
            val ph = DoubleArray(3) { random.nextDouble() * 2 * PI }
            val width = s * (0.03 + random.nextDouble() * 0.06)
            val n = 90
            val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
            for (i in 0 until n) {
                val t = i / (n - 1.0) * 2 - 1
                xs[i] = bx + cos(a) * t * len + amp[0] * sin(t * freq[0] + ph[0])
                ys[i] = by + sin(a) * t * len + amp[1] * sin(t * freq[1] + ph[1])
                zs[i] = bz + dz * t * len + amp[2] * sin(t * freq[2] + ph[2])
            }
            val curve = view.curve(xs, ys, zs) ?: return@repeat
            val depth = (0 until n step 10).map { view.distance(xs[it], ys[it], zs[it]) }.average()
            items += depth to {
                val (sx, sy, sf) = curve
                fun wAt(t: Double) = width * sf[(t * (n - 1)).toInt()] * sin(PI * t).pow(0.3)
                // the body, darker at its rim …
                g.color = color(mix(col, dark, 0.35), 0.55)
                g.fill(taperedStroke(sx, sy) { t -> wAt(t) })
                // … lighter inside …
                g.color = color(mix(col, 0xFFFFFF, 0.25), 0.45)
                g.fill(taperedStroke(sx, sy) { t -> wAt(t) * 0.55 })
                // … and a gloss line along its upper side
                val hx = DoubleArray(n) { sx[it] - width * sf[it] * 0.12 }
                val hy = DoubleArray(n) { sy[it] - width * sf[it] * 0.2 }
                g.color = color(0xFFFFFF, 0.7)
                g.fill(taperedStroke(hx, hy) { t -> wAt(t) * 0.14 })
            }
        }
        repeat((bubbles * 30).toInt()) {
            val col = pal[1 + random.nextInt(pal.size - 1)]
            val x = (random.nextDouble() - 0.5) * s * 2.4
            val y = (random.nextDouble() - 0.5) * s * 1.6
            val z = random.nextDouble() * s * 3
            val r = s * (0.03 + random.nextDouble().pow(2) * 0.2)
            val o = DoubleArray(3)
            if (!view.at(x, y, z, o)) return@repeat
            val sx = o[0]; val sy = o[1]; val sr = r * o[2]
            items += view.distance(x, y, z) to {
                // a glass sphere: clear inside, a bright rim, the window's highlight at the top left
                g.paint = java.awt.RadialGradientPaint(
                    java.awt.geom.Point2D.Double(sx, sy), sr.toFloat().coerceAtLeast(0.5f),
                    floatArrayOf(0f, 0.7f, 0.92f, 1f),
                    arrayOf(color(col, 0.12), color(col, 0.22), color(mix(col, 0xFFFFFF, 0.5), 0.75), color(mix(col, 0xFFFFFF, 0.3), 0.0)),
                )
                g.fill(Ellipse2D.Double(sx - sr, sy - sr, 2 * sr, 2 * sr))
                g.paint = java.awt.RadialGradientPaint(
                    java.awt.geom.Point2D.Double(sx - sr * 0.35, sy - sr * 0.4), (sr * 0.35).toFloat().coerceAtLeast(0.5f),
                    floatArrayOf(0f, 1f), arrayOf(color(0xFFFFFF, 0.9), color(0xFFFFFF, 0.0)),
                )
                g.fill(Ellipse2D.Double(sx - sr * 0.7, sy - sr * 0.75, sr * 0.7, sr * 0.7))
            }
        }
        for ((_, paint) in items.sortedByDescending { it.first }) paint()
    }

    /** A vortex: particles spiralling around the axis into the depth ([particles]), with glowing trails ([trails]). */
    private fun vortex(g: Graphics2D, view: View, random: Random, s: Double, pal: IntArray, particles: Double, trails: Double) {
        val arms = 3 + random.nextInt(3)
        val twist = 1.2 + random.nextDouble() * 1.5
        val o = DoubleArray(3)
        // the trails first: thin spirals of light
        repeat((trails * 14).toInt()) {
            val arm = random.nextInt(arms)
            val r0 = s * (0.2 + random.nextDouble() * 1.0)
            val n = 160
            val z0 = -s * 0.8
            val z1 = s * (2 + random.nextDouble() * 4)
            val off = (random.nextDouble() - 0.5) * 0.6
            val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
            for (i in 0 until n) {
                val z = z0 + (z1 - z0) * i / (n - 1)
                val th = arm * 2 * PI / arms + z / s * twist + off
                val r = r0 * (1 - 0.4 * (z - z0) / (z1 - z0))
                xs[i] = cos(th) * r; ys[i] = sin(th) * r; zs[i] = z
            }
            val (sx, sy, sf) = view.curve(xs, ys, zs) ?: return@repeat
            val c = pal[1 + random.nextInt(pal.size - 1)]
            g.color = color(c, 0.5)
            g.fill(taperedStroke(sx, sy) { t -> s * 0.006 * sf[(t * (n - 1)).toInt()] * sin(PI * t).pow(0.5) })
        }
        // the particles, far ones first
        val count = (particles * 2500).toInt()
        val pts = ArrayList<DoubleArray>(count)
        repeat(count) {
            val z = -s * 0.8 + random.nextDouble() * s * 6
            val arm = random.nextInt(arms)
            val r = s * (0.12 + random.nextDouble().pow(0.7) * 1.2)
            val th = arm * 2 * PI / arms + z / s * twist + (random.nextDouble() - 0.5) * 0.5
            pts += doubleArrayOf(cos(th) * r, sin(th) * r, z, random.nextDouble(), random.nextInt(pal.size - 1) + 1.0)
        }
        for (p in pts.sortedByDescending { view.distance(it[0], it[1], it[2]) }) {
            if (!view.at(p[0], p[1], p[2], o)) continue
            val r = s * 0.006 * o[2] * (0.5 + p[3])
            g.color = color(pal[p[4].toInt()], 0.35 + 0.55 * p[3])
            g.fill(Ellipse2D.Double(o[0] - r, o[1] - r, 2 * r, 2 * r))
        }
    }

    /** [c] brightened or darkened by [k], each channel clamped. */
    private fun scale(c: Int, k: Double): Int {
        fun ch(sh: Int) = ((c shr sh and 0xFF) * k).toInt().coerceIn(0, 255) shl sh
        return ch(16) or ch(8) or ch(0)
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(sh: Int) = ((a shr sh and 0xFF) + ((b shr sh and 0xFF) - (a shr sh and 0xFF)) * k).toInt() shl sh
        return ch(16) or ch(8) or ch(0)
    }
}
