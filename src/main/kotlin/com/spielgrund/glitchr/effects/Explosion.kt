package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * An explosion as it unfolds over the progress (animate it from 0 to 100 %): a white flash,
 * a billowing fireball that swells, cools from white to orange and turns into smoke, a shockwave
 * racing outwards and glowing debris flying off. As a mushroom cloud the fire rises on a stem from
 * a wide skirt on the ground. Nothing is seen at 0 % and 100 %. The center can be dragged.
 */
object Explosion : Effect("explosion", "Explosion", "A fireball or mushroom cloud with flash, shockwave and debris – animate the progress") {
    private val styles = listOf("Fireball", "Mushroom cloud")

    /** Color schemes: core, flame, embers and smoke. */
    private val schemes = listOf(
        "Fire" to intArrayOf(0xFFF6D8, 0xFF8A1A, 0x9A1E04, 0x3A3532),
        "Napalm" to intArrayOf(0xFFE07A, 0xE8400A, 0x5A0A02, 0x1C1816),
        "Plasma" to intArrayOf(0xF2F8FF, 0x3A8CFF, 0x1A1470, 0x24283A),
        "Toxic" to intArrayOf(0xF4FFD0, 0x7AE81A, 0x1E5A06, 0x2C3326),
        "Magenta" to intArrayOf(0xFFE8FA, 0xFF2EA8, 0x5A0640, 0x2E2230),
        "Custom" to intArrayOf(0, 0, 0, 0),
    )
    private val CUSTOM = schemes.size - 1

    override val params = listOf(
        Param.Choice(
            "style", "Style", styles, 0,
            "Fireball: a billowing ball of fire turning into smoke · Mushroom cloud: rising on a stem from a wide skirt",
        ),
        Param.Slider("progress", "Progress", 0, 10000, 3000, " %", "From the first flash to the last smoke – animate it from 0 to 100 %", decimals = 2),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("centerX", "Center X", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("centerY", "Center Y", -50000, 50000, 0, " px", "From the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true),
        Param.Slider("size", "Size", 100, 10000, 3500, " %", "Radius when fully grown, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Heading("shapeHeading", "Shape"),
        Param.Slider("billows", "Billows", 1, 100, 45, " %", "How many and how small the puffs of the cloud are"),
        Param.Slider("turbulence", "Turbulence", 0, 100, 55, " %", "How ragged and churned the cloud is"),
        Param.Slider("smoke", "Smoke", 0, 100, 60, " %", "How much the fire turns into dark smoke as it cools; without, it burns out into nothing"),
        Param.Slider("shockwave", "Shockwave", 0, 100, 50, " %", "A bright ring racing outwards – flat along the ground under a mushroom cloud"),
        Param.Slider("debris", "Debris", 0, 100, 40, " %", "Glowing fragments flying off and falling"),
        Param.Heading("lightHeading", "Light"),
        Param.Choice("scheme", "Colors", schemes.map { it.first }, 0, "Custom: the four colors below"),
        Param.Color("core", "Core", 0xFFF6D8, "Custom: the white-hot middle"),
        Param.Color("flame", "Flame", 0xFF8A1A, "Custom"),
        Param.Color("embers", "Embers", 0x9A1E04, "Custom: the cooler, darker fire"),
        Param.Color("smokeColor", "Smoke", 0x3A3532, "Custom"),
        Param.Slider("glow", "Glow", 0, 300, 100, " %"),
        Param.Slider("glowSize", "Glow size", 1, 200, 40, " %", "Of the radius"),
        Param.Slider("flash", "Flash", 0, 100, 60, " %", "Everything lit up in the first moments"),
        Param.Toggle("transparent", "Transparent background", false, "Only the explosion and its light, without the base color"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val random = Random(seed)
        val style = v["style"]
        val t = v["progress"] / 10000.0
        val cx = w / 2.0 + v["centerX"] / 10.0
        val cy = h / 2.0 + v["centerY"] / 10.0
        val size = max(1.0, v["size"] / 10000.0 * min(w, h))
        // swelling fast at first, slowly at the end; white hot at first, cooling down
        val grow = 0.06 + 0.94 * (1 - (1 - t).pow(3))
        val heat = exp(-t * 2.6)
        val freq = 1.2 + v["billows"] / 100.0 * 5
        val turb = v["turbulence"] / 100.0
        val amp = 0.25 + 0.3 * turb
        val smoke = v["smoke"] / 100.0
        val glow = v["glow"] / 100.0

        val scheme = v["scheme"].coerceIn(0, CUSTOM)
        val colors = if (scheme == CUSTOM) intArrayOf(v["core"], v["flame"], v["embers"], v["smokeColor"]) else schemes[scheme].second
        fun ch(c: Int, sh: Int) = (c shr sh and 0xFF) / 255.0
        fun rgb(c: Int) = doubleArrayOf(ch(c, 16), ch(c, 8), ch(c, 0))
        val core = rgb(colors[0])
        val flame = rgb(colors[1])
        val embers = rgb(colors[2])
        val smokeC = rgb(colors[3])

        /** Channel [k] of the fire at temperature [temp]: dim embers → embers → flame → core. */
        fun fire(temp: Double, k: Int): Double = when {
            temp <= 0 -> embers[k] * 0.35
            temp < 0.35 -> embers[k] * (0.35 + 0.65 * temp / 0.35)
            temp < 0.7 -> embers[k] + (flame[k] - embers[k]) * (temp - 0.35) / 0.35
            temp < 1 -> flame[k] + (core[k] - flame[k]) * (temp - 0.7) / 0.3
            else -> core[k]
        }

        /** Puffy noise, 0..1: rounded lumps with sharp creases between them, like cauliflower. */
        fun billow(x: Double, y: Double): Double {
            var sum = 0.0
            var a = 1.0
            var f = 1.0
            var norm = 0.0
            repeat(4) { o ->
                sum += abs(noise.perlin(x * f + o * 13.1, y * f - o * 7.7)) * a
                norm += a
                a *= 0.5
                f *= 2.0
            }
            return min(1.0, sum / norm * 2.2)
        }

        // where it starts: the middle of the fireball, the ground under the mushroom cloud
        val ox = cx
        val oy = if (style == 1) cy + size * 0.7 else cy

        // ---- the cloud: colors premultiplied, its opacity, and how much it shines (for the glow) ----
        val cr = FloatArray(w * h)
        val cg = FloatArray(w * h)
        val cb = FloatArray(w * h)
        val ca = FloatArray(w * h)
        val emit = FloatArray(w * h)
        // fading in from nothing at 0 %; the smoke thins out and the last flames go out until nothing is left at 100 %
        val start = smoothstep(0.0, 0.02, t)
        val ending = 1 - smoothstep(0.85, 1.0, t)
        val smokeFade = 1 - smoothstep(0.65, 1.0, t)

        /** Stores the cloud at [i]: [cover] of it there, at [temp], lit by [shade]. */
        fun paint(i: Int, cover: Double, temp: Double, shade: Double) {
            val smoky = min(1.0, smoke * 1.5) * (1 - smoothstep(0.12, 0.5, temp))
            val op = cover * start * (smoke * smokeFade + (1 - smoke) * smoothstep(0.0, 0.25, temp) * ending)
            if (op <= 0.001) return
            val lit = 0.7 + 0.3 * shade
            fun col(k: Int) = (fire(temp, k) * lit + (smokeC[k] * shade - fire(temp, k) * lit) * smoky).coerceIn(0.0, 1.0)
            cr[i] = (col(0) * op).toFloat()
            cg[i] = (col(1) * op).toFloat()
            cb[i] = (col(2) * op).toFloat()
            ca[i] = op.toFloat()
            emit[i] = (op * smoothstep(0.2, 0.9, temp) * (1 - smoky)).toFloat()
        }

        when (style) {
            0 -> {
                // a ball of billows, rising a little and spreading as it cools
                val r0 = size * grow
                val fy = oy - r0 * 0.25 * t
                parallelRows(h) { y ->
                    for (x in 0 until w) {
                        val u = (x + 0.5 - ox) / r0
                        val vv = (y + 0.5 - fy) / r0 * (1 + 0.2 * t)
                        if (hypot(u, vv) > 1.3 + amp + 0.3 * turb) continue
                        // churned by noise, the puffs rolling upwards with time
                        val wx = u + turb * 0.3 * noise.fbm(u * 1.7 + 5.2, vv * 1.7 + t * 1.2, 2)
                        val wy = vv + turb * 0.3 * noise.fbm(u * 1.7 - 9.4, vv * 1.7 - t * 1.2, 2)
                        val b = billow(wx * freq + t * 0.5, wy * freq + t * 0.9)
                        val d = 1 - hypot(wx, wy) + amp * (b - 0.5)
                        val cover = (d * r0 / 1.5 + 0.5).coerceIn(0.0, 1.0)
                        if (cover <= 0) continue
                        val inner = (d * 1.6).coerceIn(0.0, 1.0)
                        // lit from the top left; the creases dark
                        val shade = (0.55 + 0.9 * (b - 0.5) + 0.35 * (-0.5 * u - 0.7 * vv)).coerceIn(0.15, 1.2)
                        val temp = heat * 1.5 * (0.2 + 0.8 * inner) + 0.35 * (b - 0.5) * heat
                        paint(y * w + x, cover, temp, shade)
                    }
                }
            }
            else -> {
                // cap rising on a stem from a skirt on the ground, all in units of the size
                val rise = 0.12 + 1.2 * (1 - (1 - t).pow(2))
                val capR = 0.16 + 0.4 * grow
                val capRy = capR * 0.55
                val sw = 0.05 + 0.07 * grow
                val baseRx = 0.12 + 0.75 * grow
                val baseRy = 0.05 + 0.07 * grow
                // stem and skirt only form after the first fireball
                val stemOff = (1 - smoothstep(0.08, 0.3, t)) * 1.5
                val baseOff = (1 - smoothstep(0.05, 0.25, t)) * 1.5

                /** The three parts' fields at (x, y): 1 at their middle, 0 at their edge. */
                fun fields(x: Double, y: Double, out: DoubleArray) {
                    out[0] = 1 - hypot(x / capR, (y + rise) / capRy)
                    val swy = sw * (1.3 - 0.6 * (-y / rise).coerceIn(0.0, 1.0))
                    val ends = if (y > 0) y / sw * 2 else if (y < -rise) (-rise - y) / sw * 2 else 0.0
                    out[1] = 1 - max(abs(x) / swy, ends) - stemOff
                    out[2] = 1 - hypot(x / baseRx, (y + baseRy * 0.4) / baseRy) - baseOff
                }
                parallelRows(h) { y ->
                    val f = DoubleArray(3)
                    for (x in 0 until w) {
                        val px = (x + 0.5 - ox) / size
                        val py = (y + 0.5 - oy) / size
                        fields(px, py, f)
                        if (max(f[0], max(f[1], f[2])) < -amp - 0.6) continue
                        val wx = px + turb * 0.06 * noise.fbm(px * 6 + 5.2, py * 6 + t * 1.2, 2)
                        val wy = py + turb * 0.06 * noise.fbm(px * 6 - 9.4, py * 6 - t * 1.2, 2)
                        fields(wx, wy, f)
                        val part = if (f[0] >= f[1] && f[0] >= f[2]) 0 else if (f[1] >= f[2]) 1 else 2
                        val b = billow(wx * freq * 2.5 + t * 0.3, (wy + t * 0.4) * freq * 2.5)
                        val d = f[part] + amp * (b - 0.5)
                        // one pixel in the part's field
                        val pxField = 1 / (size * when (part) {
                            0 -> (capR + capRy) / 2
                            1 -> sw
                            else -> baseRy
                        })
                        val cover = (d / (pxField * 1.5) + 0.5).coerceIn(0.0, 1.0)
                        if (cover <= 0) continue
                        val inner = (d * 1.6).coerceIn(0.0, 1.0)
                        val light = when (part) {
                            0 -> -0.5 * wx / capR - 0.7 * (wy + rise) / capRy
                            1 -> -0.5 * wx / sw
                            else -> -0.7 * (wy + baseRy * 0.4) / baseRy
                        }
                        val shade = (0.55 + 0.9 * (b - 0.5) + 0.35 * light).coerceIn(0.15, 1.2)
                        // the underside of the cap burns longest, stem and skirt cool first
                        val temp = when (part) {
                            0 -> heat * 1.5 * (0.2 + 0.8 * inner) + heat * 0.6 * ((wy + rise) / capRy).coerceIn(0.0, 1.0) + 0.1 * (1 - t)
                            1 -> heat * 1.2 * (0.2 + 0.8 * inner)
                            else -> heat * 1.05 * (0.2 + 0.8 * inner)
                        } + 0.35 * (b - 0.5) * heat
                        paint(y * w + x, cover, temp, shade)
                    }
                }
            }
        }

        // ---- debris: streaks flying out and falling, glowing as they cool ----
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val debris = v["debris"] / 100.0
        if (debris > 0 && t > 0) {
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            repeat((debris * 300).toInt()) {
                // all random values first, so every fragment keeps its path through the animation
                val a = if (style == 1) -PI / 2 + (random.nextDouble() - 0.5) * PI * 1.4 else random.nextDouble() * 2 * PI
                val speed = 0.4 + random.nextDouble() * 1.2
                val cool = 1.5 + random.nextDouble() * 2.5
                val width = max(0.6, size * 0.012 * (0.4 + random.nextDouble()))
                val life = exp(-t * cool) * ending
                if (life < 0.02) return@repeat
                fun at(tt: Double): DoubleArray {
                    val d = size * speed * 1.5 * (1 - (1 - tt).pow(2))
                    return doubleArrayOf(ox + cos(a) * d, oy + sin(a) * d + size * 0.6 * speed * tt * tt)
                }
                val p0 = at(max(0.0, t - 0.06))
                val p1 = at(t)
                g.color = Color(1f, 1f, 1f, life.toFloat().coerceIn(0f, 1f))
                g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(p0[0], p0[1], p1[0], p1[1]))
            }
            g.dispose()
        }
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        val debrisCore = FloatArray(w * h) { (pixels[it] ushr 24) / 255f }

        // ---- the light: glow of the fire and the debris, shockwave, flash ----
        val glowSize = v["glowSize"] / 100.0
        val fireGlow = if (glow > 0) Blur.gauss(emit, w, h, max(1.0, glowSize * size * grow * 0.5)) else null
        val debrisGlow = if (glow > 0 && debris > 0) Blur.gauss(debrisCore, w, h, max(1.0, size * 0.025)) else null
        val shock = v["shockwave"] / 100.0 * smoothstep(0.0, 0.04, t) * exp(-t * 3) * ending
        val shockR = size * (0.1 + 2.4 * t.pow(0.7))
        val shockW = size * (0.02 + 0.1 * t)
        // flat along the ground under a mushroom cloud
        val shockAspect = if (style == 1) 0.28 else 1.0
        val flashT = 1 - smoothstep(0.0, 0.2, t)
        val flash = v["flash"] / 100.0 * flashT * flashT * start
        val ring = DoubleArray(3) { core[it] + (flame[it] - core[it]) * 0.35 }
        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = src.data[i]
                var ba = if (transparent) 0.0 else (base ushr 24) / 255.0
                // the background, the cloud over it
                val cover = ca[i].toDouble()
                var pr = ch(base, 16) * ba * (1 - cover) + cr[i]
                var pg = ch(base, 8) * ba * (1 - cover) + cg[i]
                var pb = ch(base, 0) * ba * (1 - cover) + cb[i]
                ba += (1 - ba) * cover

                // light, added on top
                val glowL = if (fireGlow == null) 0.0 else min(1.0, fireGlow[i] * 1.3) * glow * (1 - cover * 0.5)
                val dc = debrisCore[i].toDouble()
                val dg = if (debrisGlow == null) 0.0 else min(1.0, debrisGlow[i] * 2.0) * glow * 0.6
                val dx = x + 0.5 - ox
                val dy = y + 0.5 - oy
                var shockL = 0.0
                if (shock > 0.002) {
                    val dist = hypot(dx, dy / shockAspect)
                    var d = dist - shockR
                    if (abs(d) < shockW * 4 + size * 0.05) {
                        val a = atan2(dy, dx)
                        d += noise.perlin(cos(a) * 3 + 4.4, sin(a) * 3 + t * 2) * size * 0.03
                        // a sharp front, a soft trail behind it
                        val s = d / (if (d > 0) shockW * 0.35 else shockW)
                        shockL = exp(-s * s) * shock
                    }
                }
                val flashL = if (flash > 0) flash * (0.4 + 0.6 * exp(-hypot(dx, dy) / size)) else 0.0
                var lr = flame[0] * (glowL + dg) + fire(0.5 + dc * 0.6, 0) * dc * 1.2 + ring[0] * shockL + core[0] * flashL
                var lg = flame[1] * (glowL + dg) + fire(0.5 + dc * 0.6, 1) * dc * 1.2 + ring[1] * shockL + core[1] * flashL
                var lb = flame[2] * (glowL + dg) + fire(0.5 + dc * 0.6, 2) * dc * 1.2 + ring[2] * shockL + core[2] * flashL
                lr = min(1.0, lr); lg = min(1.0, lg); lb = min(1.0, lb)
                pr = min(1.0, pr + lr); pg = min(1.0, pg + lg); pb = min(1.0, pb + lb)
                val oa = min(1.0, max(ba, max(lr, max(lg, lb))))
                if (oa <= 0.002) continue
                fun c8(p: Double) = (min(1.0, p / oa) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(pr) shl 16) or (c8(pg) shl 8) or c8(pb)
            }
        }
        return out
    }
}
