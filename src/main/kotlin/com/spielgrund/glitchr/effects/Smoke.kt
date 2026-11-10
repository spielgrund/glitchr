package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
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
 * Smoke and fog out of particles: many soft puffs ("smokelets"), each a little billowing cloud of
 * one of several shapes, are emitted – rising as a plume from a point, creeping along the ground as
 * fog, hanging as haze or bursting out of a point – and carried along: slowing down, drifting with
 * the wind, swirled by turbulence (strays held back towards the flow if wanted), growing, turning
 * and thinning out until they fade. Laid over each other they make up the smoke, which is lit from
 * one side – thick smoke shadows itself, a light behind it shines through the thin parts – and, if
 * wanted, by a second light on the whole smoke's shape, worked out as a normal map. Their speeds
 * give a motion blur along the flow. The time runs the simulation, the same for every frame.
 */
object Smoke : Effect("smoke", "Smoke & fog", "Smoke and fog out of thousands of soft smokelets – a rising plume, ground fog, haze or a burst – swirling, drifting, lit from one side") {
    private val kinds = listOf("Plume", "Ground fog", "Haze", "Burst")
    private const val PLUME = 0
    private const val GROUND = 1
    private const val HAZE = 2
    private const val BURST = 3
    private const val SPRITE = 128

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Animate it: the smokelets are emitted, rise, swirl and fade", decimals = 3),
        Param.Choice("kind", "Kind", kinds, PLUME, "Plume: rising from a point · Ground fog: creeping along the bottom · Haze: hanging all over · Burst: pouring out of a point to all sides"),
        Param.Slider("centerX", "Source X", -50000, 50000, 0, " px", "Where the smoke comes from, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true).shownWhen("kind", PLUME, BURST),
        Param.Slider("centerY", "Source Y", -50000, 50000, 1000, " px", "Where the smoke comes from, from the middle of the canvas – can also be dragged with the handle in the picture", decimals = 1, transform = true).shownWhen("kind", PLUME, BURST),
        Param.Slider("sourceSize", "Source size", 0, 100, 4, " %", "How big the opening is, of the shorter side").shownWhen("kind", PLUME, BURST),
        Param.Slider("direction", "Direction", -18000, 18000, 0, "°", "Where the plume goes: 0° straight up", decimals = 2).shownWhen("kind", PLUME),
        Param.Slider("spread", "Spread", 0, 18000, 2500, "°", "How widely the smokelets fan out", decimals = 2).shownWhen("kind", PLUME),
        Param.Heading("particleHeading", "Smokelets"),
        Param.Slider("count", "Smokelets", 10, 5000, 900, "", "How many are under way at once"),
        Param.Slider("life", "Lifetime", 5, 300, 50, " s", "How long one lasts before it has faded", decimals = 1),
        Param.Slider("speed", "Speed", 0, 200, 25, " %", "Of the shorter side per second"),
        Param.Slider("slowdown", "Slowdown", 0, 100, 50, " %", "How quickly they lose their speed"),
        Param.Slider("size", "Size", 1, 50, 3, " %", "At the start, of the shorter side"),
        Param.Slider("growth", "Growth", 0, 1000, 250, " %", "How much they grow until they fade"),
        Param.Slider("randomTurn", "Random rotation", 0, 180, 17, "°", "Each starts turned at random up to this much either way – high values also turn their lit side away from the light"),
        Param.Slider("spin", "Spin", 0, 360, 8, "°/s", "How fast they turn"),
        Param.Slider("variation", "Variation", 0, 100, 40, " %", "Some bigger, thicker, faster or brighter than others"),
        Param.Slider("texture", "Wispiness", 0, 100, 65, " %", "Smooth puffs or wispy, torn little clouds"),
        Param.Slider("shapes", "Shapes", 1, 32, 8, "", "How many different smokelet shapes there are"),
        Param.Toggle("mirror", "Mirror shapes", true, "Some mirrored left to right at random – twice the variety, still lit from the light"),
        Param.Heading("motionHeading", "Motion"),
        Param.Slider("turbulence", "Turbulence", 0, 200, 35, " %", "Swirls that carry them along"),
        Param.Slider("turbulenceSize", "Swirl size", 5, 400, 100, " %"),
        Param.Slider("tame", "Tame strays", 0, 100, 30, " %", "Holds back smokelets the swirls carry far off the flow, and fades the farthest – a tidier outline"),
        Param.Slider("wind", "Wind direction", -18000, 18000, 0, "°", "0° to the right, 90° up", decimals = 2),
        Param.Slider("windStrength", "Wind", 0, 200, 0, " %", "Of the shorter side per second"),
        Param.Slider("steps", "Simulation steps", 12, 400, 48, "", "Steps over a smokelet's lifetime – more: smoother, finer paths through the swirls, more computing"),
        Param.Slider("motionBlur", "Motion blur", 0, 400, 0, " %", "Blurs the smoke along its flow, from the smokelets' speeds – 100 %: as long as it moves in 1/24 s"),
        Param.Heading("lookHeading", "Look"),
        Param.Color("color", "Color", 0xDCE2EA),
        Param.Color("shadowColor", "Shadow color", 0x3A4250),
        Param.Slider("opacity", "Density", 0, 100, 30, " %", "How thick one smokelet is"),
        Param.Slider("densityLow", "Density cut", 0, 95, 0, " %", "Levels on the smoke's opacity: thinner than this vanishes – faint smokelets and haze – the rest is stretched"),
        Param.Slider("densityHigh", "Density full", 5, 100, 100, " %", "Levels on the smoke's opacity: from this on it is fully opaque"),
        Param.Slider("shading", "Shading", 0, 100, 60, " %", "Thick smoke shadows itself: the more smoke between a spot and the light, the darker it is"),
        Param.Slider("lightDepth", "Light depth", 10, 400, 100, " %", "How deep the light gets into the smoke before it is in the shadow"),
        Param.Slider("lightAngle", "Light from", 0, 359, 90, "°", "0° from the right, 90° from above – also lights the smokelets themselves"),
        Param.Slider("backlight", "Backlight", 0, 200, 0, " %", "A light behind the smoke shining through its thin parts"),
        Param.Color("backlightColor", "Backlight color", 0x5AA8FF),
        Param.Slider("shoulder", "Highlight shoulder", 0, 100, 50, " %", "The lights add up; the shoulder rolls the brightest parts off softly towards white instead of clipping them – 0 %: hard"),
        Param.Heading("light2Heading", "Second light"),
        Param.Slider("light2", "Strength", 0, 200, 0, " %", "A second light on the shape of the whole smoke, independent of the smokelets' own light"),
        Param.Slider("light2Angle", "Light from", 0, 359, 20, "°", "0° from the right, 90° from above"),
        Param.Slider("light2Height", "Height", 0, 90, 20, "°", "Low: grazing, only the edges towards it light up · high: from the front"),
        Param.Color("light2Color", "Color", 0xFFC890),
        Param.Slider("light2Soft", "Softness", 1, 100, 30, " %", "How smoothly the shape is rounded for this light"),
        Param.Slider("light2Back", "Backlight", 0, 200, 0, " %", "A light behind the smoke: a glowing rim on the edges towards it, through the thin parts"),
        Param.Slider("light2BackAngle", "Backlight from", 0, 359, 160, "°", "Where the light behind stands: 0° right, 90° above"),
        Param.Color("light2BackColor", "Backlight color", 0x8CC8FF),
        Param.Heading("dropHeading", "Drop shadow"),
        Param.Slider("dropShadow", "Drop shadow", 0, 100, 0, " %", "Every smokelet casts a soft shadow on the smokelets behind it and on the ground"),
        Param.Slider("dropAngle", "Falls to", 0, 359, 300, "°", "Where the shadows fall: 0° right, 270° down"),
        Param.Slider("dropDistance", "Distance", 0, 200, 30, " %", "Of a smokelet's size"),
        Param.Slider("dropSoftness", "Softness", 0, 100, 40, " %", "Of a smokelet's size"),
        Param.Color("dropColor", "Shadow color", 0x000000, "Of the shadows on the ground; on the smoke they go towards its shadow color"),
        Param.Toggle("transparent", "Transparent background", false, "Only the smoke (and its drop shadow), to lay over a picture"),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY")

    /** 0..1 for particle [i] and [salt]. */
    private fun rnd(i: Long, salt: Int, seed: Long): Double {
        var z = seed * -0x61c8864680b583ebL + i * 0x5851F42D4C957F2DL + salt * 0xC2B2AE3DL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return ((z xor (z ushr 31)) ushr 11) * (1.0 / (1L shl 53))
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * A smokelet on screen: where, how big, turned how far, mirrored or not, how thick and bright,
     * which shape, and how fast it moves (pixels per second).
     */
    private class Puff(
        val x: Double, val y: Double, val r: Double, val cos: Double, val sin: Double, val mirrored: Boolean,
        val alpha: Double, val tint: Double, val sprite: Int, val vx: Double, val vy: Double,
    )

    /** A little cloud: its coverage and how lit it is, [SPRITE]² each. */
    private class Sprite(val alpha: FloatArray, val shade: FloatArray, val shadeMirrored: FloatArray)

    /**
     * Little billowing clouds, [count] of them: a flattish body with round lobes heaped up on top
     * like a cauliflower, smaller ones along the flatter bottom and a few torn-off shreds, the edges
     * frayed by noise ([texture]); lit from [lightAngle] – bright on the lobes facing the light,
     * grey in the folds and underneath.
     */
    private fun sprites(count: Int, texture: Double, lightAngle: Double, seed: Long): Array<Sprite> {
        val noise = Noise(seed + 909)
        val lx0 = cos(lightAngle)
        val ly0 = -sin(lightAngle)
        return Array(count) { k ->
            val random = kotlin.random.Random(seed * 31 + k)
            // the lobes: x, y, radius in sprite units (-1..1, y down)
            val lobes = ArrayList<DoubleArray>()
            lobes += doubleArrayOf(0.0, 0.18, 0.5)
            lobes += doubleArrayOf(-0.32, 0.22, 0.36)
            lobes += doubleArrayOf(0.34, 0.24, 0.34)
            val top = 5 + random.nextInt(4)
            for (j in 0 until top) {
                val x = -0.58 + 1.16 * (j + 0.2 + random.nextDouble() * 0.6) / top
                val r = (0.2 + 0.2 * random.nextDouble()) * (1.15 - x * x * 0.9)
                val y = 0.12 - 0.42 * sqrt(max(0.0, 1 - (x / 0.8) * (x / 0.8))) * (0.6 + 0.4 * random.nextDouble()) + r * 0.35
                lobes += doubleArrayOf(x, y, r)
            }
            repeat(3 + random.nextInt(3)) {
                val x = -0.6 + 1.2 * random.nextDouble()
                lobes += doubleArrayOf(x * 0.85, 0.3 + 0.06 * random.nextDouble(), 0.16 + 0.08 * random.nextDouble())
            }
            // shreds torn off at the rim
            repeat(random.nextInt(3)) {
                val a = random.nextDouble() * 2 * PI
                lobes += doubleArrayOf(cos(a) * 0.72, sin(a) * 0.5 + 0.1, 0.07 + 0.06 * random.nextDouble())
            }
            val n = SPRITE * SPRITE
            val density = FloatArray(n) { i ->
                val u = (i % SPRITE + 0.5) / SPRITE * 2 - 1
                val v = (i / SPRITE + 0.5) / SPRITE * 2 - 1
                var d = 0.0
                for (l in lobes) {
                    val dx = u - l[0]
                    val dy = v - l[1]
                    val q = 1 - (dx * dx + dy * dy) / (l[2] * l[2])
                    if (q > 0) d += q * q
                }
                // frayed: the noise eats into the edges and leaves fine billows inside
                val f = noise.fbm(u * 3.2 + k * 7.3, v * 3.2 + k * 3.1, 5)
                (d + f * (0.25 + 0.6 * texture)).toFloat()
            }
            val alpha = FloatArray(n)
            val shade = FloatArray(n)
            val shadeMirrored = FloatArray(n)
            for (i in 0 until n) {
                val x = i % SPRITE
                val y = i / SPRITE
                val u = (x + 0.5) / SPRITE * 2 - 1
                val v = (y + 0.5) / SPRITE * 2 - 1
                val d = density[i].toDouble()
                val edge = smoothstep(0.06, 0.7 - 0.3 * texture, d)
                // nothing at the sprite's own rim
                val rim = smoothstep(1.0, 0.86, sqrt(u * u + v * v))
                alpha[i] = (edge * rim * (0.8 + 0.2 * min(1.0, d))).toFloat()
                // the billows' slope, lit from the side of the light, darker underneath
                fun at(xx: Int, yy: Int) = density[yy.coerceIn(0, SPRITE - 1) * SPRITE + xx.coerceIn(0, SPRITE - 1)].toDouble()
                val gx = (at(x + 1, y) - at(x - 1, y)) * SPRITE * 0.06
                val gy = (at(x, y + 1) - at(x, y - 1)) * SPRITE * 0.06
                val nl = sqrt(gx * gx + gy * gy + 1)
                fun lit(lx: Double, ly: Double): Float {
                    val facing = (-gx * lx - gy * ly + 0.7) / nl / 1.22
                    val under = (v * -ly + u * -lx) * 0.5 + 0.5
                    return (0.55 + 0.5 * facing.coerceIn(0.0, 1.0) - 0.2 * under * min(1.0, d)).coerceIn(0.25, 1.1).toFloat()
                }
                shade[i] = lit(lx0, ly0)
                // for the mirrored copy (flipped left to right) the light comes from the mirrored side,
                // so that on screen it is lit from the light again
                shadeMirrored[i] = lit(-lx0, ly0)
            }
            Sprite(alpha, shade, shadeMirrored)
        }
    }

    /** [plane] at (x, y), bilinear, clamped at the edges. */
    private fun sample(plane: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
        val fx0 = x - 0.5
        val fy0 = y - 0.5
        val x0 = floor(fx0).toInt()
        val y0 = floor(fy0).toInt()
        val fx = (fx0 - x0).toFloat()
        val fy = (fy0 - y0).toFloat()
        fun at(xx: Int, yy: Int) = plane[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val n = w * h
        val short = min(w, h).toDouble()
        val kind = v["kind"]
        val life = max(0.1, v["life"] / 10.0)
        val rate = v["count"] / life
        // warmed up: at time 0 the smoke is already there
        val now = v["time"] / 1000.0 + life
        val speed = short * v["speed"] / 100.0
        val slowdown = v["slowdown"] / 100.0 * 2.5
        val size = short * v["size"] / 100.0
        val growth = v["growth"] / 100.0
        val spin = Math.toRadians(v["spin"].toDouble())
        val randomTurn = Math.toRadians(v["randomTurn"].toDouble())
        val stepTime = life / v["steps"].coerceAtLeast(1)
        val variation = v["variation"] / 100.0
        val turbulence = v["turbulence"] / 100.0
        val swirl = short * 0.18 * v["turbulenceSize"] / 100.0
        val tame = v["tame"] / 100.0
        val wind = Math.toRadians(v["wind"] / 100.0)
        val windX = cos(wind) * short * v["windStrength"] / 100.0
        val windY = -sin(wind) * short * v["windStrength"] / 100.0
        val sx = w / 2.0 + v["centerX"] / 10.0
        val sy = h / 2.0 + v["centerY"] / 10.0
        val sourceR = short * v["sourceSize"] / 100.0
        val direction = Math.toRadians(v["direction"] / 100.0)
        val spread = Math.toRadians(v["spread"] / 100.0)
        val opacity = v["opacity"] / 100.0
        val shapes = v["shapes"].coerceAtLeast(1)
        val mirror = v.bool("mirror")
        val noise = Noise(seed)

        /** The swirl at (x, y) at time [t]: the curl of a noise field, in pixels per second. */
        fun curl(x: Double, y: Double, t: Double): DoubleArray {
            val e = 0.02
            val qx = x / swirl
            val qy = y / swirl
            fun f(a: Double, b: Double) = noise.fbm(a + t * 0.05, b - t * 0.04, 2)
            val dx = (f(qx + e, qy) - f(qx - e, qy)) / (2 * e)
            val dy = (f(qx, qy + e) - f(qx, qy - e)) / (2 * e)
            val k = speed * 0.35 + short * 0.04
            return doubleArrayOf(dy * k, -dx * k)
        }

        // every smokelet born within the last lifetime, oldest first – drawn behind the younger ones
        val first = ceil((now - life) * rate).toLong() - 1
        val last = floor(now * rate).toLong()
        val puffs = ArrayList<Puff>()
        for (i in first..last) {
            val born = (i + rnd(i, 1, seed) * 0.9) / rate
            val age = now - born
            if (age < 0 || age > life) continue
            val u = age / life
            fun vary(salt: Int) = 1 + (rnd(i, salt, seed) - 0.5) * 2 * variation
            // where it starts and where it heads
            var x: Double
            var y: Double
            val vx: Double
            val vy: Double
            var scale = 1.0
            // a plume keeps rising, the others coast to a halt
            var lift = 0.0
            when (kind) {
                GROUND -> {
                    x = -0.15 * w + rnd(i, 2, seed) * w * 1.3
                    y = h * (0.88 + rnd(i, 3, seed) * 0.2)
                    val a = (rnd(i, 4, seed) - 0.5) * PI * 0.9
                    vx = sin(a) * speed * 0.5
                    vy = -cos(a) * speed * 0.15
                    scale = 2.2
                }
                HAZE -> {
                    x = -0.1 * w + rnd(i, 2, seed) * w * 1.2
                    y = -0.1 * h + rnd(i, 3, seed) * h * 1.2
                    val a = rnd(i, 4, seed) * 2 * PI
                    vx = cos(a) * speed * 0.2
                    vy = sin(a) * speed * 0.2
                    scale = 3.0
                }
                else -> {
                    val ra = rnd(i, 2, seed) * 2 * PI
                    val rr = sqrt(rnd(i, 3, seed)) * sourceR
                    x = sx + cos(ra) * rr
                    y = sy + sin(ra) * rr
                    val a = if (kind == BURST) rnd(i, 4, seed) * 2 * PI else direction + (rnd(i, 4, seed) - 0.5) * spread
                    val sp = speed * vary(5)
                    vx = sin(a) * sp
                    vy = -cos(a) * sp
                    if (kind == PLUME) lift = 0.45
                }
            }
            // carried along: slowing down, the wind taking over, swirled – in fixed steps from its birth,
            // so every frame follows the same path a little further (steps growing with the age would
            // sample the swirls elsewhere each frame and make old smokelets jump). Alongside, where it
            // would be without the swirls, to hold back the strays.
            var calmX = x
            var calmY = y
            var flowX = vx
            var flowY = vy
            var swirlX = 0.0
            var swirlY = 0.0
            val full = floor(age / stepTime).toInt()
            for (s in 0..full) {
                val dt = if (s < full) stepTime else age - full * stepTime
                if (dt <= 0) break
                val tau = s * stepTime + dt * 0.5
                val keep = lift + (1 - lift) * exp(-slowdown * tau)
                val c = if (turbulence > 0) curl(x, y, born + tau) else doubleArrayOf(0.0, 0.0)
                flowX = vx * keep + windX * (1 - keep)
                flowY = vy * keep + windY * (1 - keep)
                swirlX = c[0] * turbulence
                swirlY = c[1] * turbulence
                x += (flowX + swirlX) * dt
                y += (flowY + swirlY) * dt
                calmX += flowX * dt
                calmY += flowY * dt
            }
            // growing fast at first, then slowly; thinning out as it grows; fading in and out
            val grown = 1 + growth * (1 - exp(-2.5 * u)) / (1 - exp(-2.5))
            val r = size * scale * vary(6) * grown
            val fade = smoothstep(0.0, 0.08, u) * (1 - u).pow(1.3)
            var a = opacity * fade * grown.pow(-0.7) * vary(7).coerceAtLeast(0.1) * (if (kind == HAZE) 0.35 else if (kind == GROUND) 0.6 else 1.0)
            // strays: the farther the swirls carried it off, the more it is held back, the farthest fade
            var held = 1.0
            if (tame > 0) {
                val off = hypot(x - calmX, y - calmY)
                val reach = swirl * 0.5 + r * 0.5
                held = 1 / (1 + tame * 2.5 * off / reach)
                x = calmX + (x - calmX) * held
                y = calmY + (y - calmY) * held
                a *= 1 - tame * smoothstep(reach * 0.6, reach * 1.6, off * held)
            }
            if (a <= 0.002 || r < 0.5) continue
            // turned at random to start with, then slowly spinning
            val turn = (rnd(i, 8, seed) - 0.5) * 2 * randomTurn + spin * age * (rnd(i, 9, seed) - 0.5) * 2
            puffs += Puff(
                x, y, r, cos(turn), sin(turn), mirror && rnd(i, 12, seed) < 0.5,
                a, 1 + (rnd(i, 10, seed) - 0.5) * 0.5 * variation, (rnd(i, 11, seed) * shapes).toInt().coerceAtMost(shapes - 1),
                flowX + swirlX * held, flowY + swirlY * held,
            )
        }

        val lightAngle = Math.toRadians(v["lightAngle"].toDouble())
        val sprites = sprites(shapes, v["texture"] / 100.0, lightAngle, seed)
        val motionBlur = v["motionBlur"] / 100.0
        // laid over each other: coverage, brightness (with the smokelets' own light), the summed
        // thickness for the shading and the speed for the motion blur
        val cover = FloatArray(n)
        val tint = FloatArray(n)
        val thick = FloatArray(n)
        val moveX = if (motionBlur > 0) FloatArray(n) else null
        val moveY = if (motionBlur > 0) FloatArray(n) else null
        // every smokelet casts a soft shadow on the smokelets behind it and on the ground
        val dropShadow = v["dropShadow"] / 100.0
        val ground = if (dropShadow > 0) FloatArray(n) else null
        val softSprites = if (ground == null) null else {
            val sigma = max(0.5, v["dropSoftness"] / 100.0 * SPRITE * 0.1)
            Array(shapes) { Blur.gauss(sprites[it].alpha, SPRITE, SPRITE, sigma) }
        }
        val dropAngle = Math.toRadians(v["dropAngle"].toDouble())
        val dropReach = v["dropDistance"] / 100.0
        val dropX = cos(dropAngle) * dropReach
        val dropY = -sin(dropAngle) * dropReach

        /** [plane] of the sprite at (lu, lv) in its own units (-1..1), 0 beyond it. */
        fun spriteAt(plane: FloatArray, lu: Double, lv: Double): Double {
            val su = (lu * 0.5 + 0.5) * SPRITE - 0.5
            val sv = (lv * 0.5 + 0.5) * SPRITE - 0.5
            val ix = floor(su).toInt()
            val iy = floor(sv).toInt()
            if (ix < 0 || iy < 0 || ix >= SPRITE - 1 || iy >= SPRITE - 1) return 0.0
            val fx = su - ix
            val fy = sv - iy
            val o = iy * SPRITE + ix
            return (plane[o] * (1 - fx) + plane[o + 1] * fx) * (1 - fy) + (plane[o + SPRITE] * (1 - fx) + plane[o + SPRITE + 1] * fx) * fy
        }

        parallelRows(h) { y ->
            val py = y + 0.5
            for (p in puffs) {
                if (ground != null) {
                    // its shadow first, so it falls on what was drawn before – behind it
                    val cx = p.x + dropX * p.r
                    val cy = p.y + dropY * p.r
                    if (py >= cy - p.r && py <= cy + p.r) {
                        val sdy = py - cy
                        val half = sqrt(max(0.0, p.r * p.r - sdy * sdy))
                        val soft = softSprites!![p.sprite]
                        for (x in max(0, floor(cx - half).toInt())..min(w - 1, ceil(cx + half).toInt())) {
                            val sdx = x + 0.5 - cx
                            var lu = (sdx * p.cos + sdy * p.sin) / p.r
                            val lv = (-sdx * p.sin + sdy * p.cos) / p.r
                            if (p.mirrored) lu = -lu
                            val s = (spriteAt(soft, lu, lv) * p.alpha * dropShadow * 1.5).coerceAtMost(1.0).toFloat()
                            if (s <= 0f) continue
                            val i = y * w + x
                            // on the smoke a little lighter than on the ground, since it adds up over many smokelets
                            tint[i] *= 1 - s * 0.45f
                            ground[i] = 1 - (1 - ground[i]) * (1 - s)
                        }
                    }
                }
                if (py < p.y - p.r || py > p.y + p.r) continue
                val dy = py - p.y
                val half = sqrt(max(0.0, p.r * p.r - dy * dy))
                val x0 = max(0, floor(p.x - half).toInt())
                val x1 = min(w - 1, ceil(p.x + half).toInt())
                val sprite = sprites[p.sprite].alpha
                // mirrored left to right, with its light baked for that – the shape stays put when the light turns
                val shade = if (p.mirrored) sprites[p.sprite].shadeMirrored else sprites[p.sprite].shade
                for (x in x0..x1) {
                    val dx = x + 0.5 - p.x
                    // into the sprite, turned (and mirrored)
                    var lu = (dx * p.cos + dy * p.sin) / p.r
                    val lv = (-dx * p.sin + dy * p.cos) / p.r
                    if (p.mirrored) lu = -lu
                    val su = (lu * 0.5 + 0.5) * SPRITE - 0.5
                    val sv = (lv * 0.5 + 0.5) * SPRITE - 0.5
                    val ix = floor(su).toInt()
                    val iy = floor(sv).toInt()
                    if (ix < 0 || iy < 0 || ix >= SPRITE - 1 || iy >= SPRITE - 1) continue
                    val fx = su - ix
                    val fy = sv - iy
                    val o = iy * SPRITE + ix
                    val s = (sprite[o] * (1 - fx) + sprite[o + 1] * fx) * (1 - fy) + (sprite[o + SPRITE] * (1 - fx) + sprite[o + SPRITE + 1] * fx) * fy
                    val a = (s * p.alpha).toFloat()
                    if (a <= 0f) continue
                    val i = y * w + x
                    cover[i] = a + cover[i] * (1 - a)
                    val lit = (shade[o] * (1 - fx) + shade[o + 1] * fx) * (1 - fy) + (shade[o + SPRITE] * (1 - fx) + shade[o + SPRITE + 1] * fx) * fy
                    tint[i] = (p.tint * lit * a + tint[i] * (1 - a)).toFloat()
                    thick[i] += a
                    if (moveX != null) {
                        moveX[i] += (p.vx * a).toFloat()
                        moveY!![i] += (p.vy * a).toFloat()
                    }
                }
            }
        }

        // the second light: the whole smoke's shape as a height, rounded off, and its normals
        val light2 = v["light2"] / 100.0
        val light2Back = v["light2Back"] / 100.0
        val shape = if (light2 <= 0 && light2Back <= 0) null else {
            val sigma = max(1.0, short * v["light2Soft"] / 100.0 * 0.03)
            Blur.gauss(FloatArray(n) { (1 - exp(-thick[it] * 0.8)).toFloat() }, w, h, sigma) to sigma
        }
        val a2 = Math.toRadians(v["light2Angle"].toDouble())
        val e2 = Math.toRadians(v["light2Height"].toDouble())
        val l2x = cos(a2) * cos(e2)
        val l2y = -sin(a2) * cos(e2)
        val l2z = sin(e2)
        val light2Color = v["light2Color"]
        val light2BackColor = v["light2BackColor"]
        val backAngle = Math.toRadians(v["light2BackAngle"].toDouble())
        val densityLow = v["densityLow"] / 100.0
        val densityHigh = v["densityHigh"] / 100.0

        // lit from one side: the thicker the smoke towards the light, the darker
        val color = v["color"]
        val shadowColor = v["shadowColor"]
        val shading = v["shading"] / 100.0
        val lightDepth = v["lightDepth"] / 100.0
        val step = short * 0.025
        val lx = cos(lightAngle) * step
        val ly = -sin(lightAngle) * step
        val backlight = v["backlight"] / 100.0
        val back = v["backlightColor"]
        // the lights add up; the shoulder rolls the brightest off softly instead of clipping them
        val knee = 1 - v["shoulder"] / 100.0 * 0.8
        fun tone(x: Double) = if (x <= knee) x else knee + (1 - knee) * (1 - exp(-(x - knee) / (1 - knee)))
        // the smoke alone, premultiplied
        val sr = FloatArray(n)
        val sg = FloatArray(n)
        val sb = FloatArray(n)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val a = cover[i].toDouble()
                if (a <= 0.001) continue
                var between = 0.0
                if (shading > 0) for (k in 1..6) {
                    // beyond the edge the smoke goes on as it is here
                    val xx = (x + lx * k).roundToInt().coerceIn(0, w - 1)
                    val yy = (y + ly * k).roundToInt().coerceIn(0, h - 1)
                    between += thick[yy * w + xx] * (1 - k / 7.0)
                }
                // how much of the light reaches here through the smoke in front of it
                val reached = 1 - shading * (1 - exp(-between * 0.25 / lightDepth))
                // the smokelets' own billows stay readable even deep in the shadow
                val bright = tint[i] / a * (0.3 + 0.7 * reached)
                // how much light from behind gets through here: most where the smoke is thin
                val through = exp(-thick[i] * 0.9)
                val glow = backlight * through * 2.0
                var diffuse2 = 0.0
                var rim2 = 0.0
                if (shape != null) {
                    val (height, sigma) = shape
                    fun at(xx: Int, yy: Int) = height[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)].toDouble()
                    val bump = sigma * 5.0
                    val gx = (at(x + 1, y) - at(x - 1, y)) * 0.5 * bump
                    val gy = (at(x, y + 1) - at(x, y - 1)) * 0.5 * bump
                    val nl = sqrt(gx * gx + gy * gy + 1)
                    val nx = -gx / nl
                    val ny = -gy / nl
                    val nz = 1 / nl
                    diffuse2 = max(0.0, nx * l2x + ny * l2y + nz * l2z)
                    if (light2Back > 0) {
                        // behind: the edges turned towards the light glow, more where the smoke is thin
                        val side = hypot(nx, ny)
                        val towards = if (side < 1e-6) 0.0 else max(0.0, (nx * cos(backAngle) - ny * sin(backAngle)) / side)
                        rim2 = light2Back * (1 - nz).pow(0.6) * (0.15 + 0.85 * towards) * (0.4 + 0.6 * through) * 2.2
                    }
                }
                // levels on the opacity: thinner than the cut vanishes, from "full" on it is opaque
                val level = ((a - densityLow) / max(0.01, densityHigh - densityLow)).coerceIn(0.0, 1.0)
                // light from behind is glowing light of its own, not bound to how opaque the smoke is:
                // at the thin edges it can get brighter than the smoke would be
                val emit = level * (2 - level)
                fun ch(sh: Int): Float {
                    val l = bright.coerceIn(0.0, 1.15)
                    var c = ((shadowColor shr sh and 0xFF) * (1 - l) + (color shr sh and 0xFF) * l) / 255.0
                    if (light2 > 0) {
                        // lit where the shape faces it, a little darker where it turns away
                        val lc = (light2Color shr sh and 0xFF) / 255.0
                        val facing = max(0.0, diffuse2 - l2z * 0.6) / (1 - l2z * 0.6)
                        c = c * (1 - light2 * 0.3 * (1 - diffuse2)) + lc * light2 * facing * 0.8
                    }
                    val glowing = (back shr sh and 0xFF) / 255.0 * glow + (light2BackColor shr sh and 0xFF) / 255.0 * rim2
                    // premultiplied: the smoke as opaque as it is, the glow on top, both through the shoulder
                    return tone(c * level + glowing * emit).coerceIn(0.0, 1.0).toFloat()
                }
                sr[i] = ch(16)
                sg[i] = ch(8)
                sb[i] = ch(0)
                cover[i] = level.toFloat()
            }
        }

        // motion blur: along the speeds of the smokelets, spread a little beyond the smoke's edge
        var layers = arrayOf(sr, sg, sb, cover)
        if (moveX != null) {
            val reach = max(1.0, short * 0.015)
            val weight = Blur.gauss(thick, w, h, reach)
            val bx = Blur.gauss(moveX, w, h, reach)
            val by = Blur.gauss(moveY!!, w, h, reach)
            val shutter = motionBlur / 24.0
            val blurred = Array(4) { FloatArray(n) }
            parallelRows(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val wt = weight[i]
                    if (wt < 1e-4f) { for (k in 0..3) blurred[k][i] = layers[k][i]; continue }
                    val dx = bx[i] / wt * shutter
                    val dy = by[i] / wt * shutter
                    val len = hypot(dx, dy)
                    val taps = ceil(len / 1.5).toInt().coerceIn(1, 32)
                    if (taps == 1) { for (k in 0..3) blurred[k][i] = layers[k][i]; continue }
                    for (k in 0..3) {
                        var sum = 0f
                        for (t in 0 until taps) {
                            val f = t / (taps - 1.0) - 0.5
                            sum += sample(layers[k], w, h, x + 0.5 + dx * f, y + 0.5 + dy * f)
                        }
                        blurred[k][i] = sum / taps
                    }
                }
            }
            layers = blurred
        }

        // the smokelets' shadows on the ground, in the shadow color
        val dropColor = v["dropColor"]

        val transparent = v.bool("transparent")
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = src.data[i]
                val a = layers[3][i].toDouble()
                val sa = ground?.get(i)?.toDouble() ?: 0.0
                if (a <= 0.001 && sa <= 0.001) { out.data[i] = if (transparent) 0 else c; continue }
                fun smoke(k: Int) = layers[k][i].toDouble()
                fun shade(sh: Int) = (dropColor shr sh and 0xFF) / 255.0
                out.data[i] = if (transparent) {
                    // the shadow below the smoke, both over nothing; glowing light at least as opaque as it is bright
                    val total = max(a + sa * (1 - a), max(smoke(0), max(smoke(1), smoke(2)))).coerceAtMost(1.0)
                    fun ch(k: Int, sh: Int) = ((smoke(k) + shade(sh) * sa * (1 - a)) / total * 255).roundToInt().coerceIn(0, 255)
                    argb((total * 255).roundToInt(), ch(0, 16), ch(1, 8), ch(2, 0))
                } else {
                    fun ch(base: Int, k: Int, sh: Int) = (((base / 255.0 * (1 - sa) + shade(sh) * sa) * (1 - a) + smoke(k)) * 255).roundToInt().coerceIn(0, 255)
                    argb(alpha(c), ch(red(c), 0, 16), ch(green(c), 1, 8), ch(blue(c), 2, 0))
                }
            }
        }
        return out
    }
}
