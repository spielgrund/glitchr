package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.model.BlendMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fixed pseudo-random number for whole-number coordinates. */
private fun timeHash(a: Int, b: Int, c: Int, seed: Long): Int {
    var h = a * 374761393 + b * 668265263 + c * 1274126177 + seed.toInt() * 1442695041 + (seed ushr 32).toInt()
    h = (h xor (h ushr 13)) * 1274126177
    return h xor (h ushr 16)
}

/** 0..1 from [timeHash]. */
private fun unit(h: Int) = (h and 0xFFFFFF) / 16777215.0

/** The inputs at a set of offsets, fetched once each (before the pixels are worked on in parallel). */
private fun fetch(frames: FrameInput?, src: Pixels, offsets: Collection<Int>, loop: Boolean): Map<Int, Pixels> =
    offsets.toSet().associateWith { frames.offset(src, it, loop) }

/**
 * Time displacement: every pixel shows another frame – chosen by a gradient, by the
 * picture's brightness or by noise – so moving things bend and stretch like rubber.
 */
object TimeDisplace : TimeEffect("timedisplace", "Time displacement", "Every part of the picture shows another moment: moving things bend and stretch through time") {
    private val maps = listOf("Linear gradient", "Radial", "Picture brightness", "Noise")
    private val directions = listOf("Past", "Future", "Both")

    override val params = listOf(
        Param.Choice("map", "Time map", maps, 0, "What decides which frame each pixel shows"),
        Param.Slider("range", "Range", 1, 120, 20, " frames", "The largest time shift"),
        Param.Choice("direction", "Into", directions, 0, "Earlier frames, later ones, or both ways"),
        Param.Slider("angle", "Angle", 0, 35900, 9000, "°", "Linear gradient: from where the delay grows; 90° = from the top down", decimals = 2),
        Param.Slider("noiseSize", "Noise size", 500, 200000, 20000, " px", decimals = 2),
        Param.Slider("levels", "Steps", 0, 64, 0, tip = "Hard bands of the same frame instead of a smooth shift; 0 = smooth"),
        Param.Toggle("invert", "Invert", false),
        Param.Toggle("smooth", "Blend between frames", true, "Pixels between two frames blend them, instead of jumping"),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val w = src.width
        val h = src.height
        val range = v["range"]
        val loop = v["ends"] == 1
        val levels = v["levels"]
        val invert = v.bool("invert")
        val a = Math.toRadians(v["angle"] / 100.0)
        val ux = cos(a)
        val uy = sin(a)
        val extent = abs(w * ux) + abs(h * uy)
        val noise = Noise(seed)
        val noiseScale = 1.0 / (v["noiseSize"] / 100.0)
        val maxR = hypot(w / 2.0, h / 2.0)
        val map = v["map"]
        // 0..1 per pixel
        val t = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var value = when (map) {
                    1 -> hypot(x - w / 2.0, y - h / 2.0) / maxR
                    2 -> luma(src.data[y * w + x]) / 255.0
                    3 -> (noise.fbm(x * noiseScale, y * noiseScale, 3) * 0.75 + 0.5).coerceIn(0.0, 1.0)
                    else -> ((x - w / 2.0) * ux + (y - h / 2.0) * uy) / extent + 0.5
                }
                if (invert) value = 1 - value
                if (levels > 0) value = floor(value * levels).coerceAtMost(levels - 1.0) / max(1, levels - 1)
                t[y * w + x] = value.toFloat()
            }
        }
        val sign = when (v["direction"]) { 1 -> 1.0; 2 -> 0.0; else -> -1.0 }
        fun offsetOf(value: Float) = if (sign == 0.0) (value - 0.5) * 2 * range else sign * value * range
        val smooth = v.bool("smooth") && levels == 0
        val needed = HashSet<Int>()
        for (value in t) {
            val o = offsetOf(value)
            needed += floor(o).toInt()
            if (smooth) needed += floor(o).toInt() + 1
        }
        val pictures = fetch(frames, src, needed, loop)
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val o = offsetOf(t[i])
                val lo = floor(o).toInt()
                out.data[i] = if (!smooth) pictures.getValue(Math.round(o).toInt().let { if (it in pictures) it else lo }).data[i]
                else lerpArgb(pictures.getValue(lo).data[i], pictures.getValue(lo + 1).data[i], (o - lo).toFloat())
            }
        }
        return out
    }
}

/** RGB time split: red, green and blue come from different frames – moving things get colored fringes. */
object RgbTimeSplit : TimeEffect("rgbtime", "RGB time split", "Red, green and blue come from different frames: motion gets colored fringes, still parts stay as they are") {
    override val params = listOf(
        Param.Slider("red", "Red", -60, 60, -2, " frames"),
        Param.Slider("green", "Green", -60, 60, 0, " frames"),
        Param.Slider("blue", "Blue", -60, 60, 2, " frames"),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val loop = v["ends"] == 1
        val p = fetch(frames, src, listOf(v["red"], v["green"], v["blue"]), loop)
        val r = p.getValue(v["red"])
        val g = p.getValue(v["green"])
        val b = p.getValue(v["blue"])
        val out = Pixels(src.width, src.height)
        for (i in out.data.indices) {
            val a = max(r.data[i] ushr 24, max(g.data[i] ushr 24, b.data[i] ushr 24))
            out.data[i] = (a shl 24) or (r.data[i] and 0xFF0000) or (g.data[i] and 0xFF00) or (b.data[i] and 0xFF)
        }
        return out
    }
}

/** Block time glitch: blocks of the picture freeze or come from other frames, like a broken video stream. */
object BlockTimeGlitch : TimeEffect("blocktime", "Block time glitch", "Blocks of the picture freeze or come from other frames – a broken video stream") {
    private val modes = listOf("Earlier frames", "Earlier and later", "Freeze")

    override val params = listOf(
        Param.Slider("blockSize", "Block size", 2, 400, 32, " px"),
        Param.Toggle("varied", "Varied sizes", true, "Some blocks are wider or taller"),
        Param.Slider("amount", "Amount", 0, 100, 20, " %", "Share of glitching blocks"),
        Param.Choice("mode", "Blocks show", modes, 0, "Freeze: a block keeps the frame from when its glitch began"),
        Param.Slider("delay", "Max. delay", 1, 60, 8, " frames"),
        Param.Slider("hold", "Duration", 1, 60, 4, " frames", "How long a glitch lasts before the blocks change"),
        Param.Slider("shift", "Block shift", 0, 200, 0, " px", "Glitching blocks are also pushed sideways"),
        endsParam(),
    )

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val w = src.width
        val h = src.height
        val size = v["blockSize"]
        val varied = v.bool("varied")
        val amount = v["amount"] / 100.0
        val mode = v["mode"]
        val maxDelay = v["delay"]
        val hold = v["hold"]
        val shift = v["shift"]
        val loop = v["ends"] == 1
        val frame = frames?.frame ?: 0
        val slot = Math.floorDiv(frame, hold)
        val bh = size
        val rows = (h + bh - 1) / bh
        // every row of blocks has its own block width when varied
        val widths = IntArray(rows) { r -> if (varied) size * (1 + Math.floorMod(timeHash(r, 0, slot, seed + 4), 4)) else size }
        val cols = (w + size - 1) / size
        // per block: its time offset (0 = not glitching) and sideways shift
        val offsets = IntArray(cols * rows)
        val shifts = IntArray(cols * rows)
        for (by in 0 until rows) for (bx in 0 until (w + widths[by] - 1) / widths[by]) {
            val r = timeHash(bx, by, slot, seed)
            if (unit(r) >= amount) continue
            val d = 1 + (unit(timeHash(bx, by, slot, seed + 1)) * maxDelay).toInt().coerceAtMost(maxDelay - 1)
            offsets[by * cols + bx] = when (mode) {
                1 -> if (r and 1 == 0) -d else d
                // frozen at the frame where this glitch began
                2 -> slot * hold - frame
                else -> -d
            }
            if (shift > 0) shifts[by * cols + bx] = ((unit(timeHash(bx, by, slot, seed + 2)) * 2 - 1) * shift).roundToInt()
        }
        val pictures = fetch(frames, src, offsets.toList(), loop)
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val by = y / bh
            for (x in 0 until w) {
                val k = by * cols + x / widths[by]
                val o = offsets[k]
                if (o == 0 && shifts[k] == 0) {
                    out.data[y * w + x] = src.data[y * w + x]
                    continue
                }
                val sx = Math.floorMod(x - shifts[k], w)
                out.data[y * w + x] = pictures.getValue(o).data[y * w + sx]
            }
        }
        return out
    }
}

/** Stutter: frames are held (a lower frame rate), repeated at random or played backwards in short bursts. */
object Stutter : TimeEffect("stutter", "Stutter", "Holds frames, repeats them at random or plays short bursts backwards") {
    override val params = listOf(
        Param.Slider("hold", "Hold", 1, 60, 3, " frames", "Every frame is shown this long – a lower frame rate"),
        Param.Slider("repeat", "Repeats", 0, 100, 20, " %", "Chance that a held frame comes back again instead of moving on"),
        Param.Slider("reverse", "Backwards", 0, 100, 10, " %", "Chance that a stretch plays the frames before it backwards"),
        Param.Slider("phase", "Phase", 0, 59, 0, " frames", "Shifts where the held stretches begin"),
        endsParam(),
    )

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        if (frames == null) return src
        val hold = v["hold"]
        val f = frames.frame + v["phase"]
        val slot = Math.floorDiv(f, hold)
        val within = f - slot * hold
        var start = slot * hold
        // a repeat shows the stretch before once more
        if (unit(timeHash(slot, 0, 0, seed)) < v["repeat"] / 100.0) start -= hold
        // held: the stretch shows its first frame; backwards: the frames before it, one by one in reverse
        val target = if (unit(timeHash(slot, 1, 0, seed)) < v["reverse"] / 100.0) start - 1 - within else start
        return frames.offset(src, target - v["phase"] - frames.frame, v["ends"] == 1)
    }
}

/** Time grid: the picture in cells, every cell a little later than the one before – waves run through the grid. */
object TimeGrid : TimeEffect("timegrid", "Time grid", "Every cell of a grid shows another moment – time waves run through it") {
    private val patterns = listOf("Reading order", "Diagonal", "From the middle", "Checkerboard", "Random")

    override val params = listOf(
        Param.Slider("columns", "Columns", 1, 64, 4),
        Param.Slider("rows", "Rows", 1, 64, 4),
        Param.Choice("content", "Cells show", listOf("Their part of the picture", "The whole picture"), 0),
        Param.Choice("pattern", "Order", patterns, 1, "Which cell is how late"),
        Param.Slider("delay", "Delay per cell", -30, 30, 2, " frames"),
        Param.Slider("gap", "Gap", 0, 100, 0, " px"),
        endsParam(),
    )

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val w = src.width
        val h = src.height
        val cols = v["columns"]
        val rows = v["rows"]
        val delay = v["delay"]
        val gap = v["gap"]
        val loop = v["ends"] == 1
        fun step(c: Int, r: Int) = when (v["pattern"]) {
            0 -> r * cols + c
            2 -> max(abs(c - (cols - 1) / 2.0), abs(r - (rows - 1) / 2.0)).roundToInt()
            3 -> (c + r) % 2
            4 -> (unit(timeHash(c, r, 0, seed)) * cols * rows).toInt()
            else -> c + r
        }
        val offsetOf = IntArray(cols * rows) { -step(it % cols, it / cols) * delay }
        val whole = v["content"] == 1
        val pictures = fetch(frames, src, offsetOf.toList(), loop).mapValues { (_, p) ->
            if (whole) Grid.apply(p, Grid.defaultValues(mapOf("columns" to cols, "rows" to rows)), seed) else p
        }
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val r = min(rows - 1, y * rows / h)
            val y0 = r * h / rows
            val y1 = (r + 1) * h / rows
            for (x in 0 until w) {
                val c = min(cols - 1, x * cols / w)
                val x0 = c * w / cols
                val x1 = (c + 1) * w / cols
                val inGap = gap > 0 && (x - x0 < gap / 2 || x1 - x <= gap - gap / 2 || y - y0 < gap / 2 || y1 - y <= gap - gap / 2)
                out.data[y * w + x] = if (inGap) 0 else pictures.getValue(offsetOf[r * cols + c]).data[y * w + x]
            }
        }
        return out
    }
}

/** Persistence: bright, moving things burn in and fade slowly, like phosphor on an old screen or light painting. */
object Persistence : TimeEffect("persistence", "Persistence", "Bright moving things burn in and fade slowly – phosphor glow, light painting, long exposure") {
    override val params = listOf(
        Param.Slider("length", "Length", 1, 120, 15, " frames", "How far back the glow reaches"),
        Param.Slider("decay", "Afterglow", 1, 99, 85, " %", "How much of the glow is left after one frame"),
        Param.Slider("threshold", "Threshold", 0, 100, 30, " %", "Only parts brighter than this glow on"),
        Param.Choice("mode", "Mode", listOf("Lighten", "Add (glow builds up)"), 0),
        Param.Toggle("movingOnly", "Only where it moves", true, "Still parts don't build up a glow"),
        Param.Slider("strength", "Strength", 0, 200, 100, " %"),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val n = v["length"]
        val decay = v["decay"] / 100.0
        val threshold = v["threshold"] / 100.0 * 255
        val add = v["mode"] == 1
        val movingOnly = v.bool("movingOnly")
        val strength = v["strength"] / 100.0
        val pictures = fetch(frames, src, (1..n).map { -it }, v["ends"] == 1)
        val w = src.width
        val out = Pixels(w, src.height)
        parallelRows(src.height) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                var r = (c shr 16 and 0xFF).toDouble()
                var g = (c shr 8 and 0xFF).toDouble()
                var b = (c and 0xFF).toDouble()
                for (k in 1..n) {
                    val p = pictures.getValue(-k).data[i]
                    if (p == c && movingOnly) continue
                    val l = luma(p)
                    if (l <= threshold) continue
                    // only the part above the threshold glows, fading with every frame
                    val f = decay.pow(k) * strength * ((l - threshold) / (255 - threshold).coerceAtLeast(1.0))
                    val pr = (p shr 16 and 0xFF) * f
                    val pg = (p shr 8 and 0xFF) * f
                    val pb = (p and 0xFF) * f
                    if (add) { r += pr * (1 - decay); g += pg * (1 - decay); b += pb * (1 - decay) }
                    else { r = max(r, pr); g = max(g, pg); b = max(b, pb) }
                }
                out.data[i] = argb(c ushr 24, r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
            }
        }
        return out
    }
}

/** Stroboscope / onion skin: single copies at fixed distances in time, each in its own color if wanted. */
object Stroboscope : TimeEffect("strobe", "Stroboscope", "Single copies of earlier (or later) frames at fixed distances – onion skin, each in its own color if you like") {
    override val params = listOf(
        Param.Slider("copies", "Copies", 1, 20, 4),
        Param.Slider("spacing", "Spacing", 1, 60, 4, " frames"),
        Param.Choice("direction", "From", listOf("Past", "Future", "Both"), 0),
        Param.Choice("blend", "Blend mode", BlendMode.entries.map { it.label }, BlendMode.SCREEN.ordinal, order = BlendMode.menuOrder),
        Param.Slider("opacity", "Opacity", 0, 100, 70, " %", "Of the nearest copy"),
        Param.Slider("fade", "Fade", 0, 100, 30, " %", "Each further copy becomes this much weaker"),
        Param.Slider("tint", "Tint", 0, 100, 0, " %", "Colors the copies along the gradient from near to far"),
        Param.Color("nearColor", "Near color", 0x00E5FF),
        Param.Color("farColor", "Far color", 0xFF2D95),
        endsParam(),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        val n = v["copies"]
        val spacing = v["spacing"]
        val offsets = (1..n).flatMap { k ->
            when (v["direction"]) {
                1 -> listOf(k * spacing)
                2 -> listOf(-k * spacing, k * spacing)
                else -> listOf(-k * spacing)
            }
        }
        val pictures = fetch(frames, src, offsets, v["ends"] == 1)
        val blend = BlendMode.entries[v["blend"]]
        val opacity = v["opacity"] / 100.0
        val fade = 1 - v["fade"] / 100.0
        val tint = v["tint"] / 100f
        val near = v["nearColor"] or 0xFF000000.toInt()
        val far = v["farColor"] or 0xFF000000.toInt()
        val w = src.width
        val out = Pixels(w, src.height)
        // the furthest first, so nearer copies lie over them
        val order = offsets.sortedByDescending { abs(it) }
        parallelRows(src.height) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var c = src.data[i]
                for (o in order) {
                    val k = abs(o) / spacing
                    var p = pictures.getValue(o).data[i]
                    if (tint > 0) {
                        val color = lerpArgb(near, far, if (n <= 1) 0f else (k - 1f) / (n - 1))
                        // the copy's brightness carried by the tint color
                        val l = luma(p) / 255f
                        val tinted = argb(p ushr 24, ((color shr 16 and 0xFF) * l).toInt(), ((color shr 8 and 0xFF) * l).toInt(), ((color and 0xFF) * l).toInt())
                        p = lerpArgb(p, tinted, tint)
                    }
                    val strength = (opacity * fade.pow(k - 1)).toFloat() * (p ushr 24) / 255f
                    c = lerpArgb(c, blend.apply(c, p), strength)
                }
                out.data[i] = c
            }
        }
        return out
    }
}
