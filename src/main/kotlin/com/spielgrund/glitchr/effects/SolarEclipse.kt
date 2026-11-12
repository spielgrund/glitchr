package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A solar eclipse: the moon in front of the sun. In totality only the corona shows – bright at
 * the limb, falling off steeply, combed into fine rays, with a few long helmet streamers
 * (stretched along the sun's equator near solar minimum) and pink prominences peeking past the
 * moon's edge. Shifted, the moon leaves a sliver of the sun's surface: a diamond ring with
 * Baily's beads, flaring and spiking in the lens; smaller than the sun, a ring of fire
 * (annular); far off, a partial eclipse whose glare drowns the corona. The moon itself is dark,
 * faintly lit by the earth.
 *
 * Dark sun: the same eclipse burning like fire – a glowing ring hugging the black moon, a red
 * corona, and a beam of light falling from it straight down to the ground.
 */
object SolarEclipse : Effect("eclipse", "Solar eclipse", "A total solar eclipse with a glowing corona, rays, streamers and prominences – or a diamond ring, a ring of fire or partial; animate the moon") {
    private val moments = listOf("Totality", "Diamond ring", "Ring of fire", "Partial", "Dark sun", "Custom")
    private const val RING = 2
    private const val DARK_SUN = 4
    private const val CUSTOM = 5
    private val NATURAL = intArrayOf(0, 1, 2, 3, CUSTOM)

    /** The moon of each moment: size (of the sun), offset across and down (of the sun's radius). */
    private val presets = listOf(
        doubleArrayOf(1.03, 0.0, 0.0),
        doubleArrayOf(1.03, -0.034, 0.034),
        doubleArrayOf(0.93, 0.012, -0.008),
        doubleArrayOf(1.03, 0.62, 0.38),
        // the dark sun: in totality
        doubleArrayOf(1.03, 0.0, 0.0),
    )

    override val params = listOf(
        Param.Slider("time", "Time", 0, 1000000, 0, " s", "Lets the corona's rays shimmer slowly and the beam flicker – animate it", decimals = 3),
        Param.Heading("placeHeading", "Place"),
        Param.Slider("centerX", "Center X", -20000, 20000, 0, " %", "From the middle of the canvas: 100 % = the right edge, −100 % the left – can also be dragged with the handle in the picture", decimals = 2),
        Param.Slider("centerY", "Center Y", -20000, 20000, 0, " %", "From the middle of the canvas: 100 % = the bottom edge, −100 % the top", decimals = 2),
        Param.Slider("size", "Size", 100, 10000, 1600, " %", "The sun's radius, of the shorter side of the canvas", decimals = 2, transform = true),
        Param.Heading("moonHeading", "Moon"),
        Param.Choice(
            "moment", "Moment", moments, 0,
            "Totality: the moon covers the sun, the corona shows · Diamond ring: a last sliver of sun flares at the edge · Ring of fire: the moon too small to cover the sun (annular) · Partial: the moon only partly in front · Dark sun: burning like fire, a glowing ring around the black moon and a beam falling to the ground · Custom: size and place below – animate them to let the moon pass",
        ),
        Param.Slider("moonSize", "Moon size", 500, 2000, 1030, " %", "Of the sun: over 100 % it can cover the sun", decimals = 1).shownWhen("moment", CUSTOM),
        Param.Slider("moonX", "Moon X", -3000, 3000, 0, " %", "How far the moon sits from the sun's middle, of the sun's radius: right, or left with minus", decimals = 1).shownWhen("moment", CUSTOM),
        Param.Slider("moonY", "Moon Y", -3000, 3000, 0, " %", "Down, or up with minus", decimals = 1).shownWhen("moment", CUSTOM),
        Param.Slider("earthshine", "Earthshine", 0, 100, 6, " %", "The moon's dark side, faintly lit by the earth"),
        Param.Slider("beads", "Baily's beads", 0, 100, 50, " %", "Valleys on the moon's edge the last sunlight shines through"),
        Param.Heading("coronaHeading", "Corona"),
        Param.Slider("corona", "Brightness", 0, 300, 100, " %"),
        Param.Slider("reach", "Reach", 20, 400, 100, " %", "How far out the corona fades"),
        Param.Slider("rays", "Rays", 0, 100, 60, " %", "Fine rays combing the corona"),
        Param.Slider("streamers", "Streamers", 0, 16, 6, tip = "Long, pointed helmet streamers"),
        Param.Slider("streamerLength", "Streamer length", 10, 400, 100, " %"),
        Param.Slider("flatten", "Solar minimum", 0, 100, 35, " %", "The corona stretched along the sun's equator and the streamers gathered there, with short plumes at the poles – 0 %: round all around, like at solar maximum"),
        Param.Slider("axis", "Equator", -18000, 18000, 2000, "°", "Which way the sun's equator lies", decimals = 2),
        Param.Slider("prominences", "Prominences", 0, 300, 35, " %", "Tongues and fine streaks of glowing gas peeking past the moon's edge – over 100 % more and taller, and an arch or two"),
        Param.Heading("lightHeading", "Light"),
        Param.Color("coronaColor", "Corona", 0xF2F4FF).shownWhen("moment", 0, 1, 3, CUSTOM),
        Param.Color("sunColor", "Sun", 0xFFF3DC, "The sun's surface where the moon leaves it bare").shownWhen("moment", *NATURAL),
        Param.Color("prominenceColor", "Prominences", 0xFFF6F2, "White as seen with the eye – in photos often pink").shownWhen("moment", 0, 1, 3, CUSTOM),
        Param.Color("ringColor", "Ring", 0xFF6A14, "The ring of fire's color: its flames, the glowing sky and the moon, white-hot towards the ring").shownWhen("moment", RING),
        Param.Slider("flames", "Flames", 0, 300, 100, " %", "How tall the flames lick out of the ring – they flicker with the time").shownWhen("moment", RING),
        Param.Slider("skyGlow", "Glowing sky", 0, 300, 100, " %", "How far the sky around glows red").shownWhen("moment", RING),
        Param.Color("fireColor", "Fire", 0xFF4A12, "The dark sun's color: its ring, corona and beam, white-hot towards the middle").shownWhen("moment", DARK_SUN),
        Param.Slider("haze", "Haze", 0, 300, 25, " %", "A soft, hazy glow around the sun's edge, as through thin cloud or a dewy lens – it veils the moon's edge too"),
        Param.Slider("hazeSize", "Haze size", 5, 300, 40, " %", "How far the haze spreads, of the sun's radius"),
        Param.Slider("fringe", "Color fringe", 0, 2000, 0, " %", "Red and blue edges like a cheap lens: the colors drift apart towards the outside – 100 % = 4 % of the sun's radius at its edge", decimals = 1),
        Param.Slider("glare", "Glare", 0, 300, 100, " %", "How the bare sun flares in the lens – the diamond ring's spikes, the partial eclipse's glare"),
        Param.Slider("stars", "Stars", 0, 100, 10, " %"),
        Param.Toggle("transparent", "Transparent background", false, "Only the eclipse, without the base color"),
        Param.Choice("antialias", "Antialiasing", listOf("Off", "2 × 2", "3 × 3", "4 × 4"), 1, "Renders finer and averages it down: smooth edges at the moon, the flames and the streaks – but 4, 9 or 16 times the work"),
        Param.Heading("beamHeading", "Beam").shownWhen("moment", DARK_SUN),
        Param.Slider("ring", "Ring", 0, 300, 100, " %", "The glowing ring hugging the moon").shownWhen("moment", DARK_SUN),
        Param.Slider("beamReach", "Beam reach", 0, 100, 100, " %", "How far the beam has fallen towards the ground – animate it from 0 % to let it shoot down; at 100 % it hits the ground and splashes").shownWhen("moment", DARK_SUN),
        Param.Slider("beam", "Beam", 0, 300, 100, " %", "How bright the beam is – 0 %: none").shownWhen("moment", DARK_SUN),
        Param.Slider("beamWidth", "Beam width", 1, 100, 14, " %", "Of the sun's radius").shownWhen("moment", DARK_SUN),
        Param.Slider("ground", "Ground", 0, 100, 78, " %", "Where the beam hits the ground, of the canvas height from the top – it splashes into a glow there").shownWhen("moment", DARK_SUN),
        Param.Slider("beamGlow", "Beam glow", 0, 300, 100, " %").shownWhen("moment", DARK_SUN),
        Param.Slider("flicker", "Flicker", 0, 100, 35, " %", "The beam pulsing and wavering with the time").shownWhen("moment", DARK_SUN),
    )

    override val canvasHandle = CanvasHandle("centerX", "centerY", percent = true)

    /** Steps around the circle of the tables and the corona grid, and rings of the grid outwards. */
    private const val ANGLES = 4096
    private const val RINGS = 192

    /** How many times finer each way the antialiasing renders. */
    private val SUPERSAMPLING = intArrayOf(1, 2, 3, 4)

    /** Points along a prominence loop when looking for the nearest. */
    private const val ARCH_STEPS = 40

    /** [table] (one value per step around the circle, the first repeated at the end) at angle [a], between its steps. */
    private fun lookup(table: DoubleArray, a: Double): Double {
        var t = a / (2 * PI) * ANGLES
        if (t < 0) t += ANGLES
        val k = t.toInt().coerceIn(0, ANGLES - 1)
        return table[k] + (table[k + 1] - table[k]) * (t - k)
    }

    /** A prominence loop: its angle, how wide its feet stand (radians), its height and thickness (sun radii). */
    private class Loop(val angle: Double, val span: Double, val height: Double, val thick: Double, val phase: Double)

    /** A helmet streamer: its angle, strength, width (radians at the base), length (sun radii) and bend. */
    private class Streamer(val angle: Double, val strength: Double, val width: Double, val length: Double, val bend: Double)

    /**
     * Rendered [SUPERSAMPLING] times finer each way and averaged down (with the transparency
     * weighing in): every edge – the moon's, the flames', the streaks' – comes out smooth.
     */
    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        var ss = SUPERSAMPLING[v["antialias"].coerceIn(0, SUPERSAMPLING.size - 1)]
        // never more than about 40 million pixels at once
        while (ss > 1 && w.toLong() * h * ss * ss > 40_000_000L) ss--
        if (ss == 1) return stars(render(src, v, seed, 1), v, seed)
        val bw = w * ss
        val big = Pixels(bw, h * ss)
        parallelRows(h * ss) { y ->
            val row = y / ss * w
            for (x in 0 until bw) big.data[y * bw + x] = src.data[row + x / ss]
        }
        val fine = render(big, v, seed, ss)
        val out = Pixels(w, h)
        val n = ss * ss
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var a = 0; var r = 0L; var g = 0L; var b = 0L
                for (sy in 0 until ss) {
                    val row = (y * ss + sy) * bw + x * ss
                    for (sx in 0 until ss) {
                        val c = fine.data[row + sx]
                        val ca = c ushr 24
                        a += ca
                        r += (c shr 16 and 0xFF) * ca; g += (c shr 8 and 0xFF) * ca; b += (c and 0xFF) * ca
                    }
                }
                if (a == 0) continue
                out.data[y * w + x] = ((a + n / 2) / n shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
            }
        }
        return stars(out, v, seed)
    }

    /** The moon of [v]: its size (of the sun) and offset (of the sun's radius). */
    private fun moonOf(v: Values): DoubleArray {
        val moment = v["moment"]
        return if (moment == CUSTOM) doubleArrayOf(v["moonSize"] / 1000.0, v["moonX"] / 1000.0, v["moonY"] / 1000.0) else presets[moment.coerceIn(0, presets.size - 1)]
    }

    /**
     * A few stars, drawn on the finished picture (after the antialiasing, so they stay sharp single
     * points): hidden behind the moon, outshone where the picture is bright.
     */
    private fun stars(out: Pixels, v: Values, seed: Long): Pixels {
        val amount = v["stars"] / 100.0
        if (amount <= 0) return out
        val w = out.width
        val h = out.height
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val cy = h / 2.0 * (1 + v["centerY"] / 10000.0)
        val radius = max(1.0, v["size"] / 10000.0 * min(w, h))
        val moon = moonOf(v)
        val mcx = cx + moon[1] * radius
        val mcy = cy + moon[2] * radius
        val mr = moon[0] * radius
        parallelRows(h) { yy ->
            for (xx in 0 until w) {
                var hsh = xx * 374761393 + yy * 668265263 + seed.toInt() * 1274126177
                hsh = (hsh xor (hsh ushr 13)) * 1274126177
                hsh = hsh xor (hsh ushr 16)
                if ((hsh and 0xFFFFFF) / 16777215.0 >= amount * 0.0015) continue
                val i = yy * w + xx
                val c = out.data[i]
                val cover = ((mr - hypot(xx + 0.5 - mcx, yy + 0.5 - mcy)) + 0.5).coerceIn(0.0, 1.0)
                // the brighter the sky there, the less a star shows
                val sky = ((c shr 16 and 0xFF) * 0.3 + (c shr 8 and 0xFF) * 0.59 + (c and 0xFF) * 0.11) / 255.0 * (c ushr 24) / 255.0
                val b = (0.25 + 0.75 * ((hsh ushr 24) and 0xFF) / 255.0) * (1 - cover) * (1 - sky * 3).coerceIn(0.0, 1.0)
                if (b <= 0.01) continue
                val add = (b * 255).toInt()
                val a = c ushr 24
                // over transparency the star brings its own coverage
                val na = max(a, add)
                fun ch(sh: Int) = (((c shr sh and 0xFF) * a / max(1, na)) + add).coerceAtMost(255)
                out.data[i] = (na shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }

    private fun render(src: Pixels, v: Values, seed: Long, ss: Int): Pixels {
        val w = src.width
        val h = src.height
        val noise = Noise(seed)
        val random = Random(seed)
        val time = v["time"] / 1000.0 * 0.03
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val cy = h / 2.0 * (1 + v["centerY"] / 10000.0)
        val radius = max(1.0, v["size"] / 10000.0 * min(w, h))
        val moment = v["moment"]
        val moon = moonOf(v)
        val moonR = moon[0]
        val mx = moon[1]
        val my = moon[2]
        val earthshine = v["earthshine"] / 100.0
        val beads = v["beads"] / 100.0
        val coronaGain = v["corona"] / 100.0
        val reach = v["reach"] / 100.0
        val rays = v["rays"] / 100.0
        val flatten = v["flatten"] / 100.0
        val axis = Math.toRadians(v["axis"] / 100.0)
        val prom = v["prominences"] / 100.0
        val glareGain = v["glare"] / 100.0
        val transparent = v.bool("transparent")
        val dark = moment == DARK_SUN
        val fire = v["fireColor"]
        /** [c] mixed [t] of the way towards white. */
        fun whiter(c: Int, t: Double): Int {
            fun m(sh: Int) = ((c shr sh and 0xFF) + (255 - (c shr sh and 0xFF)) * t).toInt()
            return (m(16) shl 16) or (m(8) shl 8) or m(0)
        }
        val corona = if (dark) fire else if (moment == RING) v["ringColor"] else v["coronaColor"]
        val sun = if (dark) whiter(fire, 0.6) else v["sunColor"]
        val ringFire = moment == RING
        val ringColor = v["ringColor"]
        // the ring white-hot, but still golden, towards the moon
        val ringHot = whiter(ringColor, 0.62)
        // the flames' deep red, where they fade
        val ringDeep = (((ringColor shr 16 and 0xFF) * 0.8).toInt() shl 16) or (((ringColor shr 8 and 0xFF) * 0.3).toInt() shl 8) or ((ringColor and 0xFF) * 0.3).toInt()
        val flames = v["flames"] / 100.0
        val skyGlow = v["skyGlow"] / 100.0
        val flameTime = v["time"] / 1000.0 * 0.5
        val promColor = if (dark) whiter(fire, 0.45) else if (ringFire) whiter(ringColor, 0.3) else v["prominenceColor"]
        val hot = whiter(fire, 0.85)
        val ringGain = v["ring"] / 100.0
        val beamGain = v["beam"] / 100.0
        val beamW = v["beamWidth"] / 100.0
        val ground = (v["ground"] / 100.0 * h - cy) / radius
        val beamGlow = v["beamGlow"] / 100.0
        val flicker = v["flicker"] / 100.0
        // where the falling beam's front is: from the moon's lower edge down to the ground
        val beamReach = v["beamReach"] / 100.0
        val front = my + moonR + (ground - my - moonR) * beamReach
        // the splash comes as the beam arrives
        val impact = ((beamReach - 0.9) / 0.1).coerceIn(0.0, 1.0)
        val beamTime = v["time"] / 1000.0
        val haze = v["haze"] / 100.0
        val hazeSize = v["hazeSize"] / 100.0
        val fringe = v["fringe"] / 1000.0 * 0.04
        val base = src
        // one pixel in sun radii
        val px = 1.0 / radius

        // the moon's edge, a little rough (the valleys let the beads through): worked out once around the circle
        val edgeTable = DoubleArray(ANGLES + 1) { k ->
            val a = k * 2 * PI / ANGLES
            moonR * (1 - beads * 0.006 * (0.5 + 0.5 * noise.fbm(cos(a) * 14 + 3.1, sin(a) * 14 - 7.7, 3)))
        }

        /** The moon's edge at angle [a] (around its own middle), in sun radii. */
        fun moonEdge(a: Double): Double = lookup(edgeTable, a)

        /** How much of the sun's surface at (x, y) (sun radii from its middle) the moon leaves bare, 0..1. */
        fun bare(x: Double, y: Double): Double {
            val dx = x - mx
            val dy = y - my
            val d = hypot(dx, dy)
            return ((d - moonEdge(atan2(dy, dx))) / px + 0.5).coerceIn(0.0, 1.0)
        }

        // how much of the sun is bare, and where: the glare comes from there
        var bareSum = 0.0
        var bareX = 0.0
        var bareY = 0.0
        var total = 0
        val grid = 120
        for (j in 0 until grid) for (i in 0 until grid) {
            val x = (i + 0.5) / grid * 2 - 1
            val y = (j + 0.5) / grid * 2 - 1
            if (x * x + y * y > 1) continue
            total++
            val b = bare(x, y)
            bareSum += b; bareX += b * x; bareY += b * y
        }
        val fraction = if (total == 0) 0.0 else bareSum / total
        val gx = if (bareSum > 0) bareX / bareSum else 0.0
        val gy = if (bareSum > 0) bareY / bareSum else 0.0
        // the bare sun outshines the corona: a sliver dims it a little, a partial eclipse drowns it
        val coronaShows = exp(-fraction * 40)
        // the ring of fire keeps a fiery corona of rays around its flames
        val coronaVis = if (ringFire) 0.2 else coronaShows
        // the diamond's spikes: strongest for a small sliver
        val spikes = glareGain * min(1.0, fraction * 400) * exp(-fraction * 70)
        val glare = glareGain * sqrt(fraction)
        // the diamond sits on the sun's edge, on the side the moon has moved away from
        val md0 = hypot(mx, my)
        val sx = if (md0 > 1e-9) -mx / md0 else gx
        val sy = if (md0 > 1e-9) -my / md0 else gy

        // the streamers, gathered towards the equator near solar minimum
        val streamers = List(v["streamers"]) {
            val side = if (random.nextBoolean()) 0.0 else PI
            val spread = PI / 2 * (1 - 0.75 * flatten)
            val angle = if (flatten > 0) axis + side + (random.nextDouble() * 2 - 1) * spread else random.nextDouble() * 2 * PI
            Streamer(angle, 0.5 + random.nextDouble() * 0.7, 0.10 + random.nextDouble() * 0.12, (1.2 + random.nextDouble() * 2.2) * v["streamerLength"] / 100.0, (random.nextDouble() * 2 - 1) * 0.12)
        }

        fun ch(c: Int, s: Int) = (c shr s and 0xFF) / 255.0

        // the ring of fire shows its prominences (as through a filter), bigger and more of them
        val promShows = if (ringFire) 0.0 else coronaShows
        val promAmount = prom
        // prominence loops: arches of glowing gas standing on the edge
        val loopRandom = Random(seed xor 0x5EEDL)
        // only a few arches: the tongues and streaks carry the prominences
        // they sway, swell and shrink and wriggle along with the time
        val loopTime = v["time"] / 1000.0
        val loops = List(((promAmount - 1) * 1.2).toInt().coerceIn(0, 2)) {
            val phase = loopRandom.nextDouble() * 2 * PI
            Loop(
                loopRandom.nextDouble() * 2 * PI + 0.025 * sin(loopTime * 0.5 + phase),
                (0.09 + loopRandom.nextDouble() * 0.2) * (1 + 0.1 * sin(loopTime * 0.7 + phase * 2.3)),
                (0.035 + loopRandom.nextDouble() * 0.07) * (0.5 + promAmount) * (1 + 0.18 * sin(loopTime * 0.9 + phase * 1.7)),
                0.005 + loopRandom.nextDouble() * 0.006, phase,
            )
        }

        /** The corona's strength at [rr] (sun radii, from 1 out) and angle [a]. */
        fun coronaAt(rr: Double, a: Double): Double {
            // angle from the equator: further out along it, nearer in at the poles
            val fromEq = a - axis
            val polar = abs(sin(fromEq))
            val stretch = 1 + flatten * 0.9 * (1 - polar * polar)
            val re = 1 + (rr - 1) / (reach * stretch)
            var k = 0.9 * re.pow(-9.0) + 0.32 * re.pow(-3.2)
            // fine rays: noise around the circle, drifting slowly outwards
            val lr0 = ln(rr)
            val ray = noise.fbm(cos(a) * 26 + lr0 * 0.8 + time, sin(a) * 26 - lr0 * 0.5, 4) * 0.5 + 0.5
            val fine = noise.perlin(cos(a) * 90 + lr0 * 2 - time * 2, sin(a) * 90 + 5.3) * 0.5 + 0.5
            // polar plumes: short, straight, close-set rays near the poles
            val plume = flatten * polar.pow(6.0) * (0.5 + 0.5 * sin(fromEq * 60 + noise.perlin(a * 3, 1.7) * 3)) * exp(-(rr - 1) / (0.5 * reach))
            k *= 1 - rays * 0.7 + rays * (ray * 1.1 + fine * 0.3)
            k += 0.25 * plume * re.pow(-2.0)
            // helmet streamers: wide and bulging at the base, drawn out to a point
            for (st in streamers) {
                var da = a - st.angle - st.bend * (1 - 1 / rr)
                da = (da + PI).mod(2 * PI) - PI
                val width = st.width * (0.35 + 0.9 / (rr * rr))
                val along = exp(-(rr - 1) / (st.length * reach))
                k += st.strength * 0.22 * exp(-(da / width).pow(2.0)) * along * re.pow(-1.2)
            }
            // the dark sun burns hotter close in
            return coronaGain * coronaVis * k * (if (dark || ringFire) 1 + 2.5 * exp(-(rr - 1) / 0.12) else 1.0)
        }

        // the corona changes slowly outwards: worked out once on a grid around the sun (angle × log of the
        // distance) instead of for every pixel – the same work whatever the canvas size
        val showCorona = coronaGain > 0 && coronaVis > 0.001
        val farthest = max(1.01, maxOf(hypot(cx, cy), hypot(w - cx, cy), hypot(cx, h - cy), hypot(w - cx, h - cy)) / radius)
        val logStep = ln(farthest) / (RINGS - 1)
        val coronaGrid = if (!showCorona) FloatArray(0) else FloatArray(RINGS * (ANGLES + 1)).also { grid ->
            parallelRows(RINGS) { j ->
                val rr = exp(j * logStep)
                for (k in 0..ANGLES) grid[j * (ANGLES + 1) + k] = coronaAt(rr, k * 2 * PI / ANGLES).toFloat()
            }
        }

        /** The corona from the grid, between its points. */
        fun corona(rr: Double, a: Double): Double {
            val u = (ln(rr) / logStep).coerceIn(0.0, RINGS - 1.000001)
            val j = u.toInt()
            val fu = u - j
            var t = a / (2 * PI) * ANGLES
            if (t < 0) t += ANGLES
            val k = t.toInt().coerceIn(0, ANGLES - 1)
            val ft = t - k
            val row0 = j * (ANGLES + 1) + k
            val row1 = row0 + ANGLES + 1
            val c0 = coronaGrid[row0] + (coronaGrid[row0 + 1] - coronaGrid[row0]) * ft
            val c1 = coronaGrid[row1] + (coronaGrid[row1 + 1] - coronaGrid[row1]) * ft
            return c0 + (c1 - c0) * fu
        }

        // how tall the ring of fire's flames lick at each angle
        val flameTable = DoubleArray(ANGLES + 1) { k ->
            val a = k * 2 * PI / ANGLES
            val n = noise.fbm(cos(a) * 2.5 + 5.0, sin(a) * 2.5 + flameTime * 0.15, 3) * 0.5 + 0.5
            flames * (0.08 + 0.62 * n * n)
        }

        // how tall the prominences stand at each angle
        val tallTable = DoubleArray(ANGLES + 1) { k ->
            val a = k * 2 * PI / ANGLES
            // over 100 % more of the circle grows tongues
            ((noise.fbm(cos(a) * 9 + 21.4, sin(a) * 9 - 3.3 + time * 0.2, 4) + 0.02 + 0.12 * max(0.0, prom - 1)) / 0.35).coerceIn(0.0, 1.0)
        }

        // the beam wavers and pulses the same across a row: worked out once per row
        val waverRow = DoubleArray(h)
        val pulseRow = DoubleArray(h)
        if (dark && beamGain > 0) for (yy in 0 until h) {
            val y = (yy + 0.5 - cy) / radius
            waverRow[yy] = flicker * 0.25 * beamW * noise.perlin(y * 1.3 - beamTime * 2.1, beamTime * 0.7 + 4.2)
            pulseRow[yy] = 1 - flicker * 0.45 * (0.5 + 0.5 * noise.perlin(y * 0.8 - beamTime * 5.0, beamTime * 1.7 + 9.1))
        }

        val out = Pixels(w, h)
        parallelRows(h) { yy ->
            for (xx in 0 until w) {
                val i = yy * w + xx
                // sun radii from the sun's middle
                val x = (xx + 0.5 - cx) / radius
                val y = (yy + 0.5 - cy) / radius
                val r = hypot(x, y)
                val a = atan2(y, x)
                var lr = 0.0; var lg = 0.0; var lb = 0.0

                // ---- the corona ----
                if (r > 0.9 && showCorona) {
                    val e = corona(max(r, 1.0), a)
                    val lit = 1 - exp(-e * 2.2)
                    if (dark || ringFire) {
                        // fire, white-hot where it is brightest
                        val white = lit.pow(4.0) * 0.8
                        val hc = if (ringFire) ringHot else hot
                        lr += ch(corona, 16) * lit + ch(hc, 16) * white; lg += ch(corona, 8) * lit + ch(hc, 8) * white; lb += ch(corona, 0) * lit + ch(hc, 0) * white
                    } else {
                        lr += ch(corona, 16) * lit; lg += ch(corona, 8) * lit; lb += ch(corona, 0) * lit
                    }
                }

                // ---- the chromosphere's pink rim and the prominences ----
                // how far outside the sun's edge, smoothed over a pixel (no hard, jagged step)
                val outside = ((r - 1) / px + 0.5).coerceIn(0.0, 1.0)
                if (r > 0.98 && r < 1.5 && prom > 0 && promShows > 0.001) {
                    val chromo = exp(-max(0.0, r - 1) / 0.012) * 0.8
                    // tongues: where noise around the circle is high
                    val tall = lookup(tallTable, a)
                    val wisp = noise.fbm(cos(a) * 40 + (r - 1) * 30, sin(a) * 40 + 9.1, 3) * 0.5 + 0.5
                    val height = 0.13 * sqrt(promAmount) * tall * (0.6 + 0.8 * wisp)
                    // soft at its top edge, so it doesn't fray into jagged pixels
                    val tongue = if (height > 0) ((1 - max(0.0, r - 1) / height).coerceIn(0.0, 1.0)).pow(0.6) * 1.2 * ((height - (r - 1)) / (2 * px)).coerceIn(0.0, 1.0) * outside else 0.0
                    // fine streaks: thin rays of gas shooting out of the edge (spicules), changing fast around
                    // the circle and slowly outwards
                    val sn = noise.perlin(cos(a) * 170 + (r - 1) * 5, sin(a) * 170 - 4.4) * 0.5 + 0.5
                    val streakReach = (0.02 + 0.045 * min(prom, 3.0)) * (0.4 + 1.2 * wisp)
                    val streak = ((sn - 0.45) / 0.3).coerceIn(0.0, 1.0) * exp(-max(0.0, r - 1) / streakReach) * outside * min(1.0, prom * 1.5)
                    // the loops: bright where the point lies on an arch
                    var arch = 0.0
                    if (r >= 0.995) for (l in loops) {
                        var da = a - l.angle
                        da = (da + PI).mod(2 * PI) - PI
                        val half = l.span / 2
                        if (abs(da) > half + l.thick * 8 || r - 1 > l.height + l.thick * 8) continue
                        // the nearest point of the arch (a half ellipse from foot to foot), searched along it
                        var best = Double.MAX_VALUE
                        for (k in 0..ARCH_STEPS) {
                            val th = PI * k / ARCH_STEPS
                            // a wriggle running along the arch
                            val wx = 0.07 * half * sin(th * 2 - loopTime * 1.7 + l.phase)
                            val wy = 0.1 * l.height * sin(th * 3 + loopTime * 2.3 + l.phase) * sin(th)
                            val ex = da - cos(th) * half - wx
                            val ey = (r - 1) - sin(th) * l.height - wy
                            best = min(best, ex * ex + ey * ey)
                        }
                        val dist = sqrt(best)
                        // never thinner than a pixel, or it breaks up into dots
                        val thick = max(l.thick, px * 0.9)
                        arch += (exp(-(dist / thick).pow(2.0)) + 0.25 * exp(-dist / (thick * 3))) * (l.thick / thick).coerceAtMost(1.0) * outside
                    }
                    val p = (min(1.0, promAmount) * chromo * outside + tongue + streak * 0.9 + arch * min(1.0, promAmount)) * promShows
                    // laid over the corona in their own color (added, the white corona would swallow it)
                    val q = min(1.0, p)
                    lr += (ch(promColor, 16) * 1.1 - lr) * q; lg += (ch(promColor, 8) * 1.1 - lg) * q; lb += (ch(promColor, 0) * 1.1 - lb) * q
                }

                if (ringFire) {
                    val u = max(0.0, r - 1)
                    // ---- the sky glowing red all around, brightest near the ring ----
                    if (skyGlow > 0) {
                        val gl = skyGlow * (0.55 * exp(-u / 0.25) + 0.4 * exp(-u / 1.1) + 0.16 * exp(-u / 5))
                        lr += (ch(ringDeep, 16) * 0.45 + ch(ringColor, 16) * 0.55) * gl
                        lg += (ch(ringDeep, 8) * 0.45 + ch(ringColor, 8) * 0.55) * gl
                        lb += (ch(ringDeep, 0) * 0.45 + ch(ringColor, 0) * 0.55) * gl
                    }
                    // ---- flames licking out of the ring: turbulent tongues streaming outwards ----
                    val tall = lookup(flameTable, a)
                    if (flames > 0 && r > 0.95 && u < tall * 1.8 + 0.04) {
                        val warp = noise.fbm(cos(a) * 3 + 10.0, sin(a) * 3 - u * 3 + flameTime * 0.4, 3)
                        // ridged noise: distinct tongues with dark gaps between them
                        val n1 = noise.fbm(cos(a) * 5.5 + warp * 2.0, sin(a) * 5.5 + u * 4.5 - flameTime * 1.3, 5)
                        val ridge = 1 - abs(n1) * 2.2
                        var f = (1 - u / tall) * 0.85 + ridge * 0.55 - 0.25
                        // rising from just inside the sun's edge, no hard start
                        f = f.coerceIn(0.0, 1.0) * ((r - 0.96) / 0.04).coerceIn(0.0, 1.0)
                        f = f.pow(1.4)
                        // deep red where thin, orange, white-hot where dense
                        val c0: Int
                        val c1: Int
                        val t: Double
                        if (f < 0.5) { c0 = ringDeep; c1 = ringColor; t = f / 0.5 } else { c0 = ringColor; c1 = ringHot; t = (f - 0.5) / 0.5 }
                        val b = f * 1.15
                        lr += (ch(c0, 16) + (ch(c1, 16) - ch(c0, 16)) * t) * b
                        lg += (ch(c0, 8) + (ch(c1, 8) - ch(c0, 8)) * t) * b
                        lb += (ch(c0, 0) + (ch(c1, 0) - ch(c0, 0)) * t) * b
                    }
                }

                // ---- the sun's surface, where it is bare ----
                if (r < 1 + px) {
                    val edgeCover = ((1 - r) / px + 0.5).coerceIn(0.0, 1.0)
                    val mu = sqrt(max(0.0, 1 - min(1.0, r) * min(1.0, r)))
                    if (ringFire) {
                        // seen as the thin ring it is: dark and red at the limb, white-hot further in, mottled
                        val grain = 0.88 + 0.24 * (noise.fbm(x * 38 + 7.0, y * 38 - 2.0, 3) * 0.5 + 0.5)
                        val s = edgeCover * (0.4 + 1.8 * mu.pow(0.7)) * grain
                        val t = mu.pow(0.5).coerceIn(0.0, 1.0)
                        lr += (ch(ringColor, 16) + (ch(ringHot, 16) - ch(ringColor, 16)) * t) * s
                        lg += (ch(ringColor, 8) + (ch(ringHot, 8) - ch(ringColor, 8)) * t) * s
                        lb += (ch(ringColor, 0) + (ch(ringHot, 0) - ch(ringColor, 0)) * t) * s
                    } else {
                        val s = edgeCover * (0.45 + 0.55 * mu) * 3
                        lr += ch(sun, 16) * s; lg += ch(sun, 8) * s; lb += ch(sun, 0) * s
                    }
                }

                val mdx = x - mx
                val mdy = y - my
                val md = hypot(mdx, mdy)
                val edge = moonEdge(atan2(mdy, mdx))

                if (dark) {
                    // ---- the burning ring hugging the moon ----
                    if (ringGain > 0) {
                        val gap = max(0.0, md - edge)
                        val ring = ringGain * (1.3 * exp(-gap / 0.018) + 0.5 * exp(-gap / 0.09))
                        val white = ringGain * exp(-gap / 0.01)
                        lr += ch(fire, 16) * ring + ch(hot, 16) * white; lg += ch(fire, 8) * ring + ch(hot, 8) * white; lb += ch(fire, 0) * ring + ch(hot, 0) * white
                    }
                    // ---- the beam, from behind the moon down to the ground, splashing there ----
                    if (beamGain > 0 && beamReach > 0 && y > my && y < front + beamW * 6) {
                        val dx = abs(x - mx - waverRow[yy])
                        val pulse = pulseRow[yy]
                        // ending softly at its front: the ground, or as far as it has fallen
                        val endFade = ((front - y) / (beamW * 1.5) + 0.5).coerceIn(0.0, 1.0)
                        // still falling: a hot head at the front
                        val head = if (impact < 1) (1 - impact) * 1.5 * exp(-((front - y) / (beamW * 2.5)).pow(2.0)) else 0.0
                        val along = pulse * (endFade + head)
                        val core = exp(-(dx / (beamW * 0.4)).pow(2.0)) * along
                        val glow = beamGlow * along * (0.6 * exp(-dx / (beamW * 1.2)) + 0.2 * exp(-dx / (beamW * 6)))
                        val b = beamGain
                        lr += (ch(hot, 16) * core * 1.4 + ch(fire, 16) * (glow + core)) * b
                        lg += (ch(hot, 8) * core * 1.4 + ch(fire, 8) * (glow + core)) * b
                        lb += (ch(hot, 0) * core * 1.4 + ch(fire, 0) * (glow + core)) * b
                    }
                    if (beamGain > 0 && beamGlow > 0 && impact > 0) {
                        // the splash where it hits the ground: a flat glow
                        val ex = (x - mx) / (beamW * 14)
                        val ey = (y - ground) / (beamW * 3.5)
                        val splash = impact * beamGain * beamGlow * (0.7 * exp(-(ex * ex + ey * ey)) + 0.25 * exp(-sqrt(ex * ex + ey * ey) / 2.5))
                        lr += ch(fire, 16) * splash; lg += ch(fire, 8) * splash; lb += ch(fire, 0) * splash
                    }
                }

                // ---- the moon in front: dark, faintly lit by the earth ----
                val cover = ((edge - md) / px + 0.5).coerceIn(0.0, 1.0)
                if (cover > 0) {
                    val texture = 0.9 + 0.1 * noise.fbm(mdx * 2 + 50, mdy * 2, 4)
                    if (ringFire) {
                        // in the ring of fire the moon glows a dark red, brighter towards its edge
                        val e = (md / edge).coerceIn(0.0, 1.0)
                        val shine = (0.22 + earthshine * 0.8 + 0.45 * e.pow(5.0)) * texture * skyGlow.coerceAtMost(1.5)
                        lr += (shine * ch(ringDeep, 16) - lr) * cover
                        lg += (shine * ch(ringDeep, 8) - lg) * cover
                        lb += (shine * ch(ringDeep, 0) - lb) * cover
                    } else {
                        val shine = earthshine * 0.18 * texture
                        lr += (shine * ch(corona, 16) - lr) * cover
                        lg += (shine * ch(corona, 8) - lg) * cover
                        lb += (shine * ch(corona, 0) - lb) * cover
                    }
                }

                // ---- the lens: glare around the bare sun, spikes from a small sliver ----
                if (glare > 0 || spikes > 0) {
                    val d = hypot(x - gx, y - gy)
                    var g = glare * (0.35 / (1 + (d / 0.35).pow(2.0)) + 0.25 * exp(-d / 1.6))
                    if (spikes > 0) {
                        val dx = x - sx
                        val dy = y - sy
                        val d = hypot(dx, dy)
                        // a bright star, and four rays across
                        g += spikes * (0.9 * exp(-(d / 0.06).pow(2.0)) + 0.6 / (1 + (d / 0.12).pow(2.0)))
                        for (k in 0 until 4) {
                            val ang = PI / 4 + k * PI / 2
                            val along = dx * cos(ang) + dy * sin(ang)
                            if (along <= 0) continue
                            val across = abs(-dx * sin(ang) + dy * cos(ang))
                            g += spikes * 0.7 * exp(-across / (px * 1.6 + along * 0.008)) * exp(-along / 1.0)
                        }
                    }
                    val gc = if (ringFire) ringColor else sun
                    if (ringFire) g *= 0.12
                    lr += ch(gc, 16) * g; lg += ch(gc, 8) * g; lb += ch(gc, 0) * g
                }

                // ---- haze around the sun's edge, over everything (the moon's edge too) ----
                if (haze > 0) {
                    val d = abs(r - 1)
                    // outside it spreads wide; over the moon only a thin veil at its edge
                    val hz = haze * max(coronaShows, 0.3) *
                        if (r >= 1) 0.35 * exp(-d / (hazeSize * 0.25)) + 0.25 * exp(-d / hazeSize) else 0.3 * exp(-d / (hazeSize * 0.1))
                    lr += ch(corona, 16) * hz; lg += ch(corona, 8) * hz; lb += ch(corona, 0) * hz
                }

                // compose, premultiplied: the background (the moon covers it), the light on top
                val c = base.data[i]
                var ba = if (transparent) 0.0 else (c ushr 24) / 255.0
                var pr = ch(c, 16) * ba
                var pg = ch(c, 8) * ba
                var pb = ch(c, 0) * ba
                if (cover > 0) {
                    pr *= 1 - cover; pg *= 1 - cover; pb *= 1 - cover; ba += (1 - ba) * cover
                }
                pr = min(1.0, pr + lr); pg = min(1.0, pg + lg); pb = min(1.0, pb + lb)
                val oa = min(1.0, max(ba, max(pr, max(pg, pb))))
                if (oa <= 0.002) continue
                fun c8(p: Double) = (min(1.0, p / oa) * 255 + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = ((oa * 255 + 0.5).toInt() shl 24) or (c8(pr) shl 16) or (c8(pg) shl 8) or c8(pb)
            }
        }
        if (fringe <= 0) return out
        // the color fringe: red drawn a little larger around the sun, blue a little smaller
        val fringed = Pixels(w, h)
        parallelRows(h) { yy ->
            for (xx in 0 until w) {
                val i = yy * w + xx
                val c = out.data[i]
                val dx = xx + 0.5 - cx
                val dy = yy + 0.5 - cy
                val red = sampleBilinear(out, cx + dx / (1 + fringe), cy + dy / (1 + fringe), Edge.CLAMP)
                val blue = sampleBilinear(out, cx + dx / max(0.05, 1 - fringe), cy + dy / max(0.05, 1 - fringe), Edge.CLAMP)
                // on transparency the shifted colors bring their own coverage along
                val a = max(c ushr 24, max(red ushr 24, blue ushr 24))
                fun part(p: Int, sh: Int) = ((p shr sh and 0xFF) * (p ushr 24) / max(1, a)).coerceIn(0, 255)
                fringed.data[i] = (a shl 24) or (part(red, 16) shl 16) or (part(c, 8) shl 8) or part(blue, 0)
            }
        }
        return fringed
    }
}
