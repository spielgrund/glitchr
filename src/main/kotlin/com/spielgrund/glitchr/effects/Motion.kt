package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where the content of a picture came from in the frame before: one vector per block of
 * [block] px ([vx], [vy] in px, pointing into the earlier frame), [cols] × [rows] blocks.
 */
internal class MotionField(val block: Int, val cols: Int, val rows: Int, val vx: FloatArray, val vy: FloatArray) {
    /** The vector of the block containing ([x], [y]): blocky, as video codecs move things. */
    fun blockAt(x: Int, y: Int, out: FloatArray) {
        val k = min(rows - 1, y / block) * cols + min(cols - 1, x / block)
        out[0] = vx[k]
        out[1] = vy[k]
    }

    /** The vector at ([x], [y]) blended between the neighbouring block middles: smooth. */
    fun smoothAt(x: Double, y: Double, out: FloatArray) {
        val fx = (x / block - 0.5).coerceIn(0.0, cols - 1.0)
        val fy = (y / block - 0.5).coerceIn(0.0, rows - 1.0)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(cols - 1, x0 + 1)
        val y1 = min(rows - 1, y0 + 1)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        fun mix(a: FloatArray): Float {
            val top = a[y0 * cols + x0] * (1 - tx) + a[y0 * cols + x1] * tx
            val bottom = a[y1 * cols + x0] * (1 - tx) + a[y1 * cols + x1] * tx
            return top * (1 - ty) + bottom * ty
        }
        out[0] = mix(vx)
        out[1] = mix(vy)
    }

    companion object {
        /** Estimates already made, by the later picture (which is kept by the renders that made it). */
        private val cache = WeakHashMap<Pixels, Triple<java.lang.ref.WeakReference<Pixels>, List<Int>, MotionField>>()

        /**
         * Block matching: for every block of [next], the shift within ±[range] px at which the
         * frame [prev] looks most alike. It is searched on a smaller copy of the brightness
         * (a block becomes about 4 × 4), so even large frames are quick; still parts prefer
         * no movement, so noise doesn't make them drift.
         */
        fun estimate(prev: Pixels, next: Pixels, block: Int, range: Int): MotionField {
            val key = listOf(block, range)
            synchronized(cache) {
                cache[next]?.let { (p, k, field) -> if (p.get() === prev && k == key) return field }
            }
            val w = next.width
            val h = next.height
            val q = max(1, block / 4)
            val sw = max(1, w / q)
            val sh = max(1, h / q)
            fun small(p: Pixels) = FloatArray(sw * sh).also { s ->
                parallelRows(sh) { y ->
                    for (x in 0 until sw) {
                        var sum = 0f
                        for (yy in y * q until min(h, y * q + q)) for (xx in x * q until min(w, x * q + q)) {
                            val c = p.data[yy * w + xx]
                            sum += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10f
                        }
                        s[y * sw + x] = sum / (q * q)
                    }
                }
            }
            val a = small(prev)
            val b = small(next)
            val sb = max(1, block / q)
            val sr = max(1, range / q)
            val cols = (w + block - 1) / block
            val rows = (h + block - 1) / block
            val vx = FloatArray(cols * rows)
            val vy = FloatArray(cols * rows)
            parallelRows(rows) { by ->
                for (bx in 0 until cols) {
                    val x0 = bx * sb
                    val y0 = by * sb
                    var best = Float.MAX_VALUE
                    var bestX = 0
                    var bestY = 0
                    for (dy in -sr..sr) for (dx in -sr..sr) {
                        var sad = 0f
                        for (y in y0 until min(sh, y0 + sb)) for (x in x0 until min(sw, x0 + sb)) {
                            val px = (x + dx).coerceIn(0, sw - 1)
                            val py = (y + dy).coerceIn(0, sh - 1)
                            sad += abs(b[y * sw + x] - a[py * sw + px])
                        }
                        // a little in favour of standing still
                        sad += (abs(dx) + abs(dy)) * 0.5f
                        if (sad < best) { best = sad; bestX = dx; bestY = dy }
                    }
                    vx[by * cols + bx] = (bestX * q).toFloat()
                    vy[by * cols + bx] = (bestY * q).toFloat()
                }
            }
            return MotionField(block, cols, rows, vx, vy).also { field ->
                synchronized(cache) { cache[next] = Triple(java.lang.ref.WeakReference(prev), key, field) }
            }
        }
    }
}

private val blockSizes = listOf(8, 16, 32, 64)

/**
 * Datamosh by motion, like a video whose keyframes were cut out: one frame stays as the
 * picture, and only the movement of the following frames pushes its pixels on – blocks
 * smear and bleed where things move. Every keyframe interval the picture is clean again.
 */
object MotionDatamosh : TimeEffect("timedatamosh", "Datamosh (motion)", "The movement of the following frames pushes the pixels of one frame on – things bleed and smear like in a broken video") {
    override val params = listOf(
        Param.Slider("interval", "Keyframe every", 2, 600, 50, " frames", "Here the picture is clean again; in between the mosh grows"),
        Param.Slider("phase", "Keyframe phase", 0, 599, 0, " frames", "Shifts where the keyframes lie"),
        Param.Choice("block", "Block size", blockSizes.map { "$it px" }, 1, "Size of the moving blocks"),
        Param.Slider("range", "Search range", 2, 128, 16, " px", "The largest movement per frame that is found"),
        Param.Slider("strength", "Strength", 0, 400, 100, " %", "Above 100 % the movement is exaggerated"),
        Param.Slider("refresh", "Refresh", 0, 50, 0, " %", "Mixes a bit of the real frame back in every frame, so the mosh slowly heals"),
        Param.Toggle("smooth", "Smooth motion", false, "Blends the movement between the blocks: flowing instead of blocky"),
    )

    override val random = false

    /** Moshed frames by keyframe picture: the newest few, so the next frame continues from them. */
    private val made = WeakHashMap<Pixels, LinkedHashMap<Pair<List<Int>, Int>, Pixels>>()

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        if (frames == null) return src
        val interval = v["interval"]
        val f = frames.frame
        val key = Math.floorDiv(f - v["phase"], interval) * interval + v["phase"]
        val start = key.coerceIn(0, frames.frameCount - 1)
        if (start >= f) return src
        val block = blockSizes[v["block"]]
        val range = v["range"]
        val strength = v["strength"] / 100f
        val refresh = v["refresh"] / 100f
        val smooth = v.bool("smooth")
        val settings = listOf(interval, v["phase"], block, range, v["strength"], v["refresh"], if (smooth) 1 else 0)
        val keyPicture = frames.at(start)
        fun input(g: Int) = if (g == f) src else frames.at(g)

        // continue from the latest moshed frame before this one, if there is one
        var g = start
        var moshed = keyPicture
        synchronized(made) {
            made[keyPicture]?.entries?.filter { it.key.first == settings && it.key.second in start + 1 until f }?.maxByOrNull { it.key.second }?.let {
                g = it.key.second
                moshed = it.value
            }
        }
        val w = src.width
        val h = src.height
        while (g < f) {
            val next = input(g + 1)
            val field = MotionField.estimate(input(g), next, block, range)
            val from = moshed
            val out = Pixels(w, h)
            parallelRows(h) { y ->
                val d = FloatArray(2)
                for (x in 0 until w) {
                    if (smooth) field.smoothAt(x + 0.5, y + 0.5, d) else field.blockAt(x, y, d)
                    val sx = x + d[0] * strength
                    val sy = y + d[1] * strength
                    var c = if (smooth) sampleBilinear(from, sx + 0.5, sy + 0.5, Edge.CLAMP)
                    else from.data[(sy.toInt()).coerceIn(0, h - 1) * w + (sx.toInt()).coerceIn(0, w - 1)]
                    if (refresh > 0) c = lerpArgb(c, next.data[y * w + x], refresh)
                    out.data[y * w + x] = c
                }
            }
            moshed = out
            g++
            synchronized(made) {
                val list = made.getOrPut(keyPicture) { LinkedHashMap() }
                list[settings to g] = out
                // keep only the newest few
                while (list.size > 6) list.remove(list.keys.first())
            }
        }
        return moshed
    }
}

/** Motion blur along the movement: things are smeared in the direction they move, still parts stay sharp. */
object MotionBlur : TimeEffect("motionblur", "Motion blur", "Smears things along the direction they move, like a long camera exposure – still parts stay sharp") {
    override val params = listOf(
        Param.Slider("shutter", "Shutter", 0, 400, 100, " %", "Length of the smear against the movement of one frame"),
        Param.Slider("samples", "Quality", 2, 48, 12, tip = "Samples along the smear"),
        Param.Toggle("centered", "Centered", true, "Smears both ways around the current position; off: only back along the way it came"),
        Param.Choice("block", "Block size", blockSizes.map { "$it px" }, 1, "Size of the blocks whose movement is measured"),
        Param.Slider("range", "Search range", 2, 128, 24, " px", "The largest movement per frame that is found"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long, frames: FrameInput?): Pixels {
        if (frames == null || frames.frameCount <= 1) return src
        val prev = frames.offset(src, -1, false)
        if (prev === src) return src
        val field = MotionField.estimate(prev, src, blockSizes[v["block"]], v["range"])
        val shutter = v["shutter"] / 100.0
        val n = v["samples"]
        val centered = v.bool("centered")
        val w = src.width
        val h = src.height
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            val d = FloatArray(2)
            val samples = IntArray(n)
            for (x in 0 until w) {
                field.smoothAt(x + 0.5, y + 0.5, d)
                if (abs(d[0]) + abs(d[1]) < 0.25f) {
                    out.data[y * w + x] = src.data[y * w + x]
                    continue
                }
                for (k in 0 until n) {
                    // along the way it came (the vector points back to where it was)
                    var t = k / (n - 1.0) * shutter
                    if (centered) t -= shutter / 2
                    samples[k] = sampleBilinear(src, x + 0.5 + d[0] * t, y + 0.5 + d[1] * t, Edge.CLAMP)
                }
                out.data[y * w + x] = averageArgb(samples)
            }
        }
        return out
    }
}
