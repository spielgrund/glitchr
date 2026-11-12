package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.Color
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fireworks: rockets climb from the bottom on a trail of falling sparks and burst high up – into a
 * peony (a ball of stars), a chrysanthemum (the same with trails), a willow (long drooping golden
 * trails), a ring, a palm (a few thick arms), glitter (crackling at the end) or a crossette (stars
 * that split once more); or each rocket something else. The stars fly out, slowed by the air,
 * sinking with gravity, fading, cooling towards orange; a flash lights the sky at every burst.
 * Animate the time; after the loop length everything repeats seamlessly.
 */
object Fireworks : Effect("fireworks", "Fireworks", "Rockets climb and burst into peonies, chrysanthemums, willows, rings, palms, glitter and crossettes – animate the time") {
    private val types = listOf("Mixed", "Peony", "Chrysanthemum", "Willow", "Ring", "Palm", "Glitter", "Crossette")
    private const val MIXED = 0
    private const val PEONY = 1
    private const val CHRYSANTHEMUM = 2
    private const val WILLOW = 3
    private const val RING = 4
    private const val PALM = 5
    private const val GLITTER = 6
    private const val CROSSETTE = 7
    private val colorings = listOf("Colorful", "Own colors", "Gold & silver")

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 2500, " s", "Animate it and the fireworks go off", decimals = 3),
        Param.Slider("loop", "Loop length", 10, 6000, 100, " s", "After this everything is as at the start, so the animation can repeat seamlessly", decimals = 1),
        Param.Choice("background", "Background", listOf("Base color", "Transparent"), 0, "Transparent: only the light, to lay over a picture (e.g. with Screen or Add)"),
        Param.Heading("rocketHeading", "Rockets"),
        Param.Slider("rate", "Rockets", 1, 2000, 120, " /s", "How many go up per second", decimals = 2),
        Param.Choice("type", "Burst", types, 0, "Mixed: every rocket something else · Peony: a ball of stars · Chrysanthemum: with trails · Willow: long drooping golden trails · Ring · Palm: a few thick arms · Glitter: crackling at the end · Crossette: stars that split once more"),
        Param.Slider("centerX", "Launch X", -20000, 20000, 0, " %", "Where they go up, from the middle: 100 % = the right edge – can also be dragged with the handle", decimals = 2),
        Param.Slider("centerY", "Burst height", -20000, 20000, -3000, " %", "Where they burst on average, from the middle: −100 % = the top edge – the handle drags it", decimals = 2),
        Param.Slider("width", "Launch width", 0, 200, 60, " %", "Over how much of the canvas width the rockets spread"),
        Param.Slider("heightRange", "Height range", 0, 100, 25, " %", "How differently high they burst, of the canvas height"),
        Param.Slider("lean", "Lean", 0, 45, 6, "°", "How much the rockets lean to the sides"),
        Param.Slider("riseTime", "Rise time", 2, 60, 13, " s", "How long a rocket climbs", decimals = 1),
        Param.Toggle("showRocket", "Rocket trail", true, "The rockets climbing on a trail of sparks – off: the bursts appear out of the dark"),
        Param.Heading("burstHeading", "Burst"),
        Param.Slider("size", "Burst size", 1, 200, 22, " %", "Radius of a burst, of the canvas height"),
        Param.Slider("sizeVariation", "Size variation", 0, 100, 35, " %"),
        Param.Slider("stars", "Stars", 5, 1500, 140, tip = "How many stars one burst throws"),
        Param.Slider("life", "Burn time", 5, 100, 22, " s", "How long the stars burn", decimals = 1),
        Param.Slider("gravity", "Gravity", 0, 300, 100, " %", "How fast they sink"),
        Param.Slider("trail", "Trails", 0, 300, 100, " %", "How long the stars' trails are"),
        Param.Slider("twinkle", "Twinkle", 0, 100, 30, " %", "How much they flicker as they burn out"),
        Param.Heading("lookHeading", "Look"),
        Param.Choice("coloring", "Colors", colorings, 0),
        Param.Color("color1", "Color 1", 0xFF3050).shownWhen("coloring", 1),
        Param.Color("color2", "Color 2", 0x40A0FF).shownWhen("coloring", 1),
        Param.Slider("saturation", "Saturation", 0, 100, 85, " %").shownWhen("coloring", 0),
        Param.Slider("starSize", "Star size", 30, 1000, 170, " px", decimals = 2),
        Param.Slider("brightness", "Brightness", 10, 400, 100, " %"),
        Param.Slider("glow", "Glow", 0, 300, 70, " %", "A soft halo around the stars"),
        Param.Slider("flash", "Flash", 0, 300, 100, " %", "How brightly every burst lights up the sky"),
        Param.Slider("antialias", "Smoothness", 1, 3, 2, tip = "Finer placing of the stars: 1 fast, 3 smoothest"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY", percent = true)

    /** A random number 0..1 from the numbers given. */
    private fun rnd(a: Int, b: Int, c: Int): Double {
        var h = a * 374761393 + b * 668265263 + c * 1274126177
        h = (h xor (h ushr 16)) * -2048144789
        h = (h xor (h ushr 13)) * -1028477387
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216.0
    }

    private class Light(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        /** A soft dot of [radius] at ([x], [y]), [c] times [amount]. */
        fun dot(x: Double, y: Double, radius: Double, c: Int, amount: Double) {
            if (amount <= 0.002) return
            val cr = (c shr 16 and 0xFF) / 255.0 * amount
            val cg = (c shr 8 and 0xFF) / 255.0 * amount
            val cb = (c and 0xFF) / 255.0 * amount
            if (radius <= 0.75) {
                // tiny: shared out between the four nearest pixels
                val fx = x - 0.5; val fy = y - 0.5
                val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
                val tx = fx - x0; val ty = fy - y0
                val k = radius / 0.75 * radius / 0.75 * 1.0
                put(x0, y0, (1 - tx) * (1 - ty) * k, cr, cg, cb)
                put(x0 + 1, y0, tx * (1 - ty) * k, cr, cg, cb)
                put(x0, y0 + 1, (1 - tx) * ty * k, cr, cg, cb)
                put(x0 + 1, y0 + 1, tx * ty * k, cr, cg, cb)
                return
            }
            val r = radius + 0.5
            val norm = 1.0 / (radius * radius * 1.3)
            for (yy in floor(y - r).toInt()..floor(y + r).toInt()) {
                for (xx in floor(x - r).toInt()..floor(x + r).toInt()) {
                    val d = hypot(xx + 0.5 - x, yy + 0.5 - y) / r
                    if (d >= 1) continue
                    put(xx, yy, (1 - d * d) * norm, cr, cg, cb)
                }
            }
        }

        private fun put(x: Int, y: Int, k: Double, cr: Double, cg: Double, cb: Double) {
            if (x < 0 || y < 0 || x >= w || y >= h || k <= 0) return
            val i = y * w + x
            r[i] += (cr * k).toFloat(); g[i] += (cg * k).toFloat(); b[i] += (cb * k).toFloat()
        }
    }

    /** [c] cooled towards orange-red by [t] 0..1, as a burning star dims. */
    private fun cool(c: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun mix(sh: Int, to: Int) = ((c shr sh and 0xFF) * (1 - k) + to * k).roundToInt()
        return (mix(16, 255) shl 16) or (mix(8, 110) shl 8) or mix(0, 30)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val ss = v["antialias"].coerceIn(1, 3).let { var s = it; while (s > 1 && src.width.toLong() * src.height * s * s > 30_000_000L) s--; s }
        val w = src.width
        val h = src.height
        val fw = w * ss
        val fh = h * ss
        val seedInt = seed.toInt() xor (seed ushr 32).toInt()
        val loop = v["loop"] / 10.0
        val t = ((v["time"] / 1000.0) % loop + loop) % loop
        val rate = v["rate"] / 100.0
        val count = max(1, (rate * loop).roundToInt())
        val unit = fh.toDouble()
        val launchX = fw / 2.0 * (1 + v["centerX"] / 10000.0)
        val burstY = fh / 2.0 * (1 + v["centerY"] / 10000.0)
        val spreadW = fw * v["width"] / 100.0
        val heightRange = fh * v["heightRange"] / 100.0
        val lean = Math.toRadians(v["lean"].toDouble())
        val riseTime = v["riseTime"] / 10.0
        val showRocket = v.bool("showRocket")
        val baseSize = unit * v["size"] / 100.0
        val sizeVar = v["sizeVariation"] / 100.0
        val stars = v["stars"]
        val baseLife = v["life"] / 10.0
        val gravityK = v["gravity"] / 100.0
        val trailK = v["trail"] / 100.0
        val twinkle = v["twinkle"] / 100.0
        val coloring = v["coloring"]
        val saturation = v["saturation"] / 100f
        val starRadius = v["starSize"] / 200.0 * ss
        val bright = v["brightness"] / 100.0
        val flashK = v["flash"] / 100.0
        val chosenType = v["type"]

        val light = Light(fw, fh)
        val flashes = Light(fw, fh)
        // the longest a rocket is visible: climbing plus its stars burning (a willow's longer)
        val span = riseTime + baseLife * 2.2 + 0.5

        for (k in 0 until count) {
            val launch = (k + rnd(k, 1, seedInt) * 0.9) * loop / count
            // this loop's rocket, and the one of the loop before still burning
            for (back in 0..ceil(span / loop).toInt()) {
                val age = t + back * loop - launch
                if (age < 0 || age > span) continue
                val type = if (chosenType == MIXED) 1 + (rnd(k, 2, seedInt) * 7).toInt().coerceIn(0, 6) else chosenType
                // where it starts and bursts
                val sx = launchX + (rnd(k, 3, seedInt) - 0.5) * spreadW
                val sy = fh + 4.0 * ss
                val tilt = (rnd(k, 4, seedInt) - 0.5) * 2 * lean
                val by = burstY + (rnd(k, 5, seedInt) - 0.5) * heightRange
                val bx = sx + (sy - by) * kotlin.math.tan(tilt)
                // the colors of this one
                val hue = rnd(k, 6, seedInt).toFloat()
                val main = when (coloring) {
                    1 -> if (rnd(k, 7, seedInt) < 0.5) v["color1"] else v["color2"]
                    2 -> if (rnd(k, 7, seedInt) < 0.6) 0xFFC860 else 0xE8F0FF
                    else -> Color.HSBtoRGB(hue, saturation, 1f) and 0xFFFFFF
                }
                val second = when (coloring) {
                    1 -> if (main == v["color1"]) v["color2"] else v["color1"]
                    2 -> if (main == 0xFFC860) 0xE8F0FF else 0xFFC860
                    else -> Color.HSBtoRGB(hue + 0.08f + rnd(k, 8, seedInt).toFloat() * 0.5f, saturation, 1f) and 0xFFFFFF
                }
                val twoColors = rnd(k, 9, seedInt) < 0.35

                fun path(tt: Double): Pair<Double, Double> {
                    val u = (tt / riseTime).coerceIn(0.0, 1.0)
                    val e = 1 - (1 - u) * (1 - u)
                    return Pair(sx + (bx - sx) * e, sy + (by - sy) * e)
                }

                // climbing: a bright head, a trail behind it, sparks falling off
                if (showRocket) {
                    if (age < riseTime) {
                        val (hx, hy) = path(age)
                        light.dot(hx, hy, starRadius * 1.2, 0xFFE0B0, 1.6 * bright)
                        val steps = 14
                        for (j in 1..steps) {
                            val (px, py) = path(age - 0.22 * j / steps)
                            light.dot(px, py, starRadius * 0.8, 0xFFA040, 0.5 * (1 - j / (steps + 1.0)) * bright)
                        }
                    }
                    val sparks = (riseTime * 40).toInt()
                    for (j in 0 until sparks) {
                        val te = riseTime * (j + rnd(k * 131 + j, 10, seedInt)) / sparks
                        val sa = age - te
                        if (sa < 0 || sa > 0.6 || te > riseTime) continue
                        val (px, py) = path(te)
                        val vx = (rnd(k * 131 + j, 11, seedInt) - 0.5) * unit * 0.05
                        val g = unit * 0.25 * gravityK
                        light.dot(px + vx * sa, py + 0.5 * g * sa * sa, starRadius * 0.6, 0xFF9A40, 0.45 * (1 - sa / 0.6) * bright)
                    }
                }

                val since = age - riseTime
                if (since < 0) continue

                // the flash, lighting the sky
                if (flashK > 0 && since < 0.35) {
                    val f = (1 - since / 0.35).pow(2)
                    flashes.dot(bx, by, ss * 3.0, cool(main, -0.0), f * flashK * 60 * bright)
                    light.dot(bx, by, starRadius * 4, 0xFFFFFF, f * 1.5 * bright)
                }

                val radius = baseSize * (1 + (rnd(k, 12, seedInt) - 0.5) * 2 * sizeVar).coerceAtLeast(0.15)
                // the air's drag, how long they burn and how long their trails are, by kind
                val drag = when (type) { WILLOW -> 1.6; PALM -> 1.5; else -> 2.6 }
                val life = baseLife * when (type) { WILLOW -> 2.2; PALM -> 1.3; GLITTER -> 1.2; else -> 1.0 }
                if (since > life * 1.15) continue
                val trailTime = trailK * when (type) { PEONY -> 0.08; CHRYSANTHEMUM -> 0.45; WILLOW -> 1.4; PALM -> 1.1; RING -> 0.12; else -> 0.25 }
                val g = unit * 0.12 * gravityK * if (type == WILLOW) 1.6 else 1.0
                val n = when (type) { PALM -> max(5, stars / 14); WILLOW -> max(5, stars * 2 / 3); else -> stars }
                val size = starRadius * when (type) { PALM -> 2.6; WILLOW -> 0.9; else -> 1.0 }
                val v0 = radius * drag / (1 - exp(-drag * life))
                // a ring lies in a tilted plane
                val ringTilt = rnd(k, 13, seedInt) * PI
                val ringRoll = rnd(k, 14, seedInt) * PI

                fun pos(dx: Double, dy: Double, tt: Double, ox: Double, oy: Double, speed: Double): Pair<Double, Double> {
                    val travel = speed * (1 - exp(-drag * tt)) / drag
                    // sinking: slowly at first, towards a steady fall
                    val sink = g / drag * (tt - (1 - exp(-drag * tt)) / drag)
                    return Pair(ox + dx * travel, oy + dy * travel + sink)
                }

                for (j in 0 until n) {
                    val q = k * 7919 + j
                    var dx: Double
                    var dy: Double
                    when (type) {
                        RING -> {
                            val a = j * 2 * PI / n + rnd(q, 15, seedInt) * 0.05
                            val x3 = cos(a); val y3 = sin(a) * cos(ringTilt)
                            dx = x3 * cos(ringRoll) - y3 * sin(ringRoll); dy = x3 * sin(ringRoll) + y3 * cos(ringRoll)
                        }
                        PALM -> {
                            val a = j * 2 * PI / n + rnd(q, 15, seedInt) * 0.3
                            dx = cos(a); dy = sin(a) * 0.9 - 0.15
                        }
                        else -> {
                            // evenly over a ball, seen from the side
                            val zz = rnd(q, 15, seedInt) * 2 - 1
                            val a = rnd(q, 16, seedInt) * 2 * PI
                            val rr = sqrt(1 - zz * zz)
                            dx = rr * cos(a); dy = rr * sin(a)
                        }
                    }
                    val speed = v0 * (0.92 + rnd(q, 17, seedInt) * 0.16)
                    val ownLife = life * (0.8 + rnd(q, 18, seedInt) * 0.35)
                    val starColor = if (twoColors && j % 2 == 1) second else if (type == WILLOW) 0xFFB050 else main

                    fun burn(tt: Double, ox: Double, oy: Double, ddx: Double, ddy: Double, sp: Double, lifeLeft: Double, c: Int, sz: Double, salt: Int) {
                        if (tt < 0 || tt > lifeLeft) return
                        val u = tt / lifeLeft
                        var amount = (1 - u * u).pow(1.5) * bright * 2.2
                        if (twinkle > 0 && u > 0.45) {
                            val flick = rnd(salt, floor((since) * 18).toInt(), seedInt)
                            amount *= 1 - twinkle * (if (flick < 0.5) 1.0 else 0.0) * ((u - 0.45) / 0.55 + 0.3).coerceAtMost(1.0)
                        }
                        val glitter = type == GLITTER && u > 0.55
                        val col = cool(c, ((u - 0.5) * 0.8).coerceAtLeast(0.0))
                        val (x, y) = pos(ddx, ddy, tt, ox, oy, sp)
                        if (glitter) {
                            // crackling: brief white sparks around it
                            for (m in 0 until 3) {
                                val on = rnd(salt * 3 + m, floor(since * 25).toInt(), seedInt)
                                if (on > 0.35) continue
                                val ox2 = (rnd(salt * 3 + m, 21, seedInt) - 0.5) * radius * 0.12
                                val oy2 = (rnd(salt * 3 + m, 22, seedInt) - 0.5) * radius * 0.12
                                light.dot(x + ox2, y + oy2, sz * 0.7, 0xFFF4D0, 1.6 * bright)
                            }
                            light.dot(x, y, sz * 0.6, col, amount * 0.4)
                        } else {
                            light.dot(x, y, sz * (1 - u * 0.4), col, amount * 1.3)
                        }
                        // the trail: where it was a moment ago, fading
                        if (trailTime > 0) {
                            val back = min(trailTime, tt)
                            val (px, py) = pos(ddx, ddy, tt - back, ox, oy, sp)
                            val len = hypot(x - px, y - py)
                            val steps = min(80, ceil(len / (0.8 * ss)).toInt())
                            for (s in 1..steps) {
                                val tb = tt - back * s / steps
                                val (qx, qy) = pos(ddx, ddy, tb, ox, oy, sp)
                                val f = 1 - s / (steps + 1.0)
                                light.dot(qx, qy, sz * 0.7, cool(c, 0.15 + u * 0.45), amount * 0.6 * f * f)
                            }
                        }
                    }

                    if (type == CROSSETTE) {
                        // flies out, then splits into four
                        val split = ownLife * 0.4
                        if (since < split) burn(since, bx, by, dx, dy, speed, ownLife, starColor, size, q)
                        else {
                            val (ox, oy) = pos(dx, dy, split, bx, by, speed)
                            val base = Math.atan2(dy, dx) + PI / 4
                            for (m in 0 until 4) {
                                val a = base + m * PI / 2
                                burn(since - split, ox, oy + 0.0, cos(a), sin(a), speed * 0.35, ownLife * 0.6, starColor, size * 0.8, q * 4 + m)
                            }
                        }
                    } else burn(since, bx, by, dx, dy, speed, ownLife, starColor, size, q)
                }
            }
        }

        // halos and the sky lit by the flashes
        val glow = v["glow"] / 100.0
        val halo = if (glow > 0) arrayOf(light.r, light.g, light.b).map { Blur.gauss(it, fw, fh, max(1.5, starRadius * 3.5)) } else null
        val sky = if (flashK > 0) arrayOf(flashes.r, flashes.g, flashes.b).map { Blur.gauss(it, fw, fh, max(4.0, baseSize * 0.6)) } else null
        val transparent = v["background"] == 1
        val out = Pixels(w, h)
        val n = ss * ss
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var lr = 0.0; var lg = 0.0; var lb = 0.0
                for (sy in 0 until ss) for (sx in 0 until ss) {
                    val i = (y * ss + sy) * fw + x * ss + sx
                    lr += light.r[i] + (halo?.get(0)?.get(i) ?: 0f) * glow * 2.5 + (sky?.get(0)?.get(i) ?: 0f)
                    lg += light.g[i] + (halo?.get(1)?.get(i) ?: 0f) * glow * 2.5 + (sky?.get(1)?.get(i) ?: 0f)
                    lb += light.b[i] + (halo?.get(2)?.get(i) ?: 0f) * glow * 2.5 + (sky?.get(2)?.get(i) ?: 0f)
                }
                lr /= n; lg /= n; lb /= n
                // very bright turns white-hot
                val hot = max(0.0, (lr + lg + lb) / 3 - 0.8) * 0.6
                fun tone(l: Double) = (1 - exp(-(l + hot) * 1.6))
                val r = tone(lr); val g = tone(lg); val b = tone(lb)
                val i = y * w + x
                if (transparent) {
                    val a = max(r, max(g, b))
                    out.data[i] = if (a <= 0.002) 0 else {
                        fun ch(c: Double) = (c / a * 255).roundToInt().coerceIn(0, 255)
                        ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
                    }
                } else {
                    val c = src.data[i]
                    fun add(sh: Int, l: Double) = ((c shr sh and 0xFF) + l * 255 * (1 - (c shr sh and 0xFF) / 255.0)).roundToInt().coerceIn(0, 255)
                    out.data[i] = (c and 0xFF000000.toInt()) or (add(16, r) shl 16) or (add(8, g) shl 8) or add(0, b)
                }
            }
        }
        return out
    }
}
