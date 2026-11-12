package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Depth blur: a camera's shallow depth of field by the estimated depth. Everything in the focal
 * plane stays sharp; in front and behind it blurs the more, the farther away it is and the wider
 * the aperture (f-stop) – and every point of light opens into a disc shaped like the lens's
 * aperture: round, or a polygon of blades, turned and rounded, its rim brighter (soap bubble) or
 * softer. Bright highlights are lifted, so they bloom into clear bokeh balls.
 *
 * Done the way light does it, in linear light: for every pixel the discs of its neighbours are
 * gathered – a neighbour counts if its own disc reaches this far, so the blurred foreground
 * spills over what lies behind it, but the blurred background stays behind a sharp subject.
 */
object DepthBlur : Effect("depthblur", "Depth blur", "Camera depth of field by the estimated depth: a focal plane, the f-stop and an aperture with blades – bokeh discs from the highlights") {
    private val focusModes = listOf("Focal plane", "Point in the picture")

    override val params = listOf(
        Param.Heading("lensHeading", "Lens"),
        Param.Slider("fstop", "F-stop", 7, 220, 20, "", "The aperture: f/1.4 a wide-open portrait lens, very blurred · f/8 and more: almost everything sharp", decimals = 1),
        Param.Slider("blur", "Blur size", 1, 400, 40, " px", "How big the blur gets at f/1.4 for the farthest things – with the f-stop it shrinks"),
        Param.Slider("nearBlur", "In front", 0, 200, 100, " %", "How much what lies in front of the focus blurs, compared to behind"),
        Param.Heading("focusHeading", "Focus"),
        Param.Choice("focusMode", "Focus on", focusModes, 0, "Focal plane: a depth · Point: whatever lies at the handle in the picture is sharp"),
        Param.Slider("focus", "Focal plane", 0, 100, 70, " %", "The depth in focus: 0 % far, 100 % near").shownWhen("focusMode", 0),
        Param.Slider("focusX", "Focus X", -10000, 10000, 0, " %", "Drag the handle onto what should be sharp", decimals = 2).shownWhen("focusMode", 1),
        Param.Slider("focusY", "Focus Y", -10000, 10000, 0, " %", decimals = 2).shownWhen("focusMode", 1),
        Param.Slider("sharpRange", "Depth of field", 0, 100, 8, " %", "How deep the sharp zone around the focus is"),
        Param.Heading("apertureHeading", "Aperture"),
        Param.Slider("blades", "Blades", 0, 12, 6, tip = "The aperture's blades: 0 = round, 5–9 give the polygon discs of real lenses"),
        Param.Slider("bladeRotation", "Blade rotation", 0, 360, 15, "°").shownWhen("blades", *(3..12).toList().toIntArray()),
        Param.Slider("roundness", "Roundness", 0, 100, 25, " %", "Curved blades: the polygon's sides bulge towards a circle").shownWhen("blades", *(3..12).toList().toIntArray()),
        Param.Slider("rim", "Rim", -100, 100, 25, " %", "Positive: the discs' rim brighter (soap bubble, old lenses) · negative: soft discs, brightest in the middle"),
        Param.Heading("highlightHeading", "Highlights"),
        Param.Slider("threshold", "Threshold", 0, 100, 75, " %", "From this brightness, highlights are lifted"),
        Param.Slider("boost", "Boost", 0, 5000, 300, " %", "How much brighter they get – they bloom into bright bokeh balls"),
        Param.Slider("quality", "Quality", 1, 4, 2, tip = "Samples per disc: higher is smoother and slower"),
    ) + Depth.params

    override val random = false

    override val canvasHandle = CanvasHandle("focusX", "focusY", percent = true)

    /** Points evenly over the unit disc (a Vogel spiral): x, y, and their radius 0..1. */
    private fun discPoints(n: Int): Array<DoubleArray> = Array(n) { k ->
        val rad = sqrt((k + 0.5) / n)
        val a = k * 2.399963229728653
        doubleArrayOf(rad * cos(a), rad * sin(a), rad)
    }

    /** The aperture's edge in direction [a]: 1 for a circle, less between a polygon's corners. */
    private fun apertureEdge(a: Double, blades: Int, rot: Double, roundness: Double): Double {
        if (blades < 3) return 1.0
        val seg = 2 * PI / blades
        var t = (a - rot) % seg
        if (t < 0) t += seg
        val poly = cos(PI / blades) / cos(t - seg / 2)
        return poly + (1 - poly) * roundness
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val depth = Depth.estimate(src, v)
        if (v.bool("depthShow")) return Depth.show(src, depth)

        // how big the blur circle is: with the distance from the focus, scaled by the aperture
        val maxR = v["blur"] * 1.4 / (v["fstop"] / 10.0)
        val focus = if (v["focusMode"] == 1) {
            val fx = (w / 2.0 * (1 + v["focusX"] / 10000.0)).toInt().coerceIn(0, w - 1)
            val fy = (h / 2.0 * (1 + v["focusY"] / 10000.0)).toInt().coerceIn(0, h - 1)
            depth[fy * w + fx].toDouble()
        } else v["focus"] / 100.0
        val halfRange = v["sharpRange"] / 200.0
        val near = v["nearBlur"] / 100.0
        val coc = FloatArray(w * h) { i ->
            val d = depth[i] - focus
            val off = (abs(d) - halfRange).coerceAtLeast(0.0)
            val reach = if (d > 0) max(1e-3, 1 - focus - halfRange) else max(1e-3, focus - halfRange)
            (maxR * min(1.0, off / reach) * (if (d > 0) near else 1.0)).toFloat()
        }
        if (maxR < 0.5) return src.copy()

        // linear light, premultiplied; what the highlights are lifted by is kept apart (it is stamped as crisp discs)
        val threshold = v["threshold"] / 100.0
        val boost = v["boost"] / 100.0
        val lr = FloatArray(w * h); val lg = FloatArray(w * h); val lb = FloatArray(w * h); val la = FloatArray(w * h)
        val er = FloatArray(w * h); val eg = FloatArray(w * h); val eb = FloatArray(w * h)
        for (i in 0 until w * h) {
            val c = src.data[i]
            val a = (c ushr 24) / 255.0
            val r = ((c shr 16 and 0xFF) / 255.0).pow(2.2)
            val g = ((c shr 8 and 0xFF) / 255.0).pow(2.2)
            val b = ((c and 0xFF) / 255.0).pow(2.2)
            val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b).pow(1 / 2.2)
            val lift = if (lum > threshold && threshold < 1) 1 + boost * ((lum - threshold) / (1 - threshold)).pow(2) * 25 else 1.0
            lr[i] = (r * a).toFloat(); lg[i] = (g * a).toFloat(); lb[i] = (b * a).toFloat(); la[i] = a.toFloat()
            if (lift > 1) { er[i] = (r * (lift - 1) * a).toFloat(); eg[i] = (g * (lift - 1) * a).toFloat(); eb[i] = (b * (lift - 1) * a).toFloat() }
        }

        // the aperture's sample points, shaped
        val count = intArrayOf(64, 128, 192, 320)[v["quality"].coerceIn(1, 4) - 1]
        val blades = v["blades"]
        val rot = Math.toRadians(v["bladeRotation"].toDouble() - 90)
        val roundness = v["roundness"] / 100.0
        val rim = v["rim"] / 100.0
        val points = discPoints(count).map { p ->
            val a = kotlin.math.atan2(p[1], p[0])
            val e = apertureEdge(a, blades, rot, roundness)
            // rim brighter or softer: by how far out the point is
            val weight = max(0.05, 1 + rim * (p[2].pow(4) * 2 - 0.66) * 1.5)
            doubleArrayOf(p[0] * e, p[1] * e, p[2], weight)
        }

        // big blurs are gathered on a coarser grid (at most about 10 of its pixels across a disc's radius), then laid over the sharp picture
        val f = max(1, (maxR / 10).toInt())
        val lw = (w + f - 1) / f
        val lh = (h + f - 1) / f
        val low = if (f == 1) arrayOf(lr, lg, lb, la, coc, depth) else {
            val arrays = Array(6) { FloatArray(lw * lh) }
            val n = IntArray(lw * lh)
            val full = arrayOf(lr, lg, lb, la, coc, depth)
            for (i in 0 until w * h) {
                val k = (i / w / f) * lw + (i % w / f)
                n[k]++
                for (c in 0 until 6) arrays[c][k] += full[c][i]
            }
            for (k in 0 until lw * lh) for (c in 0 until 6) arrays[c][k] /= max(1, n[k])
            for (k in 0 until lw * lh) arrays[4][k] /= f.toFloat()
            arrays
        }
        val blurred = gather(lw, lh, low[0], low[1], low[2], low[3], low[4], low[5], points, maxR / f)

        val bokeh = stamp(w, h, er, eg, eb, coc, depth, blades, rot, roundness, rim, f)

        fun bilinear(a: FloatArray, aw: Int, ah: Int, x: Double, y: Double): Double {
            val fx = (x - 0.5).coerceIn(0.0, aw - 1.0); val fy = (y - 0.5).coerceIn(0.0, ah - 1.0)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(aw - 1, x0 + 1); val y1 = min(ah - 1, y0 + 1)
            val tx = fx - x0; val ty = fy - y0
            return (a[y0 * aw + x0] * (1 - tx) + a[y0 * aw + x1] * tx) * (1 - ty) + (a[y1 * aw + x0] * (1 - tx) + a[y1 * aw + x1] * tx) * ty
        }

        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var br: Double; var bg: Double; var bb: Double; var ba: Double
                if (f == 1) {
                    br = blurred[0][i].toDouble(); bg = blurred[1][i].toDouble(); bb = blurred[2][i].toDouble(); ba = blurred[3][i].toDouble()
                } else {
                    val gx = (x + 0.5) / f; val gy = (y + 0.5) / f
                    br = bilinear(blurred[0], lw, lh, gx, gy); bg = bilinear(blurred[1], lw, lh, gx, gy)
                    bb = bilinear(blurred[2], lw, lh, gx, gy); ba = bilinear(blurred[3], lw, lh, gx, gy)
                    // sharp where the pixel is (nearly) in focus – unless blurred foreground spills over it
                    val t = max(((coc[i] - 0.5 * f) / f).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }, bilinear(blurred[4], lw, lh, gx, gy))
                    br = lr[i] + (br - lr[i]) * t; bg = lg[i] + (bg - lg[i]) * t; bb = lb[i] + (bb - lb[i]) * t; ba = la[i] + (ba - la[i]) * t
                }
                // the bokeh discs on top, as light
                val hr = bokeh[0][i]; val hg = bokeh[1][i]; val hb = bokeh[2][i]
                val lit = hr + hg + hb > 0
                if (lit) { br += hr; bg += hg; bb += hb; ba = min(1.0, ba + (0.2126 * hr + 0.7152 * hg + 0.0722 * hb)) }
                if (ba <= 1e-4) { out.data[i] = 0; continue }
                if (f == 1 && blurred[5][i] == 0f && !lit) { out.data[i] = src.data[i]; continue }
                fun ch(c: Double) = ((c / ba).coerceAtLeast(0.0).pow(1 / 2.2) * 255).roundToInt().coerceIn(0, 255)
                out.data[i] = ((ba.coerceAtMost(1.0) * 255).roundToInt() shl 24) or (ch(br) shl 16) or (ch(bg) shl 8) or ch(bb)
            }
        }
        return out
    }

    /**
     * Every pixel gathers the discs of its neighbours (premultiplied linear light, its blur radius
     * [coc] and [depth]): red, green, blue, alpha, the share of the light from in front of it, and
     * whether it was blurred at all (0 = untouched).
     */
    private fun gather(
        w: Int, h: Int, lr: FloatArray, lg: FloatArray, lb: FloatArray, la: FloatArray, coc: FloatArray, depth: FloatArray,
        points: List<DoubleArray>, maxR: Double,
    ): Array<FloatArray> {
        // how far to gather around every pixel: the biggest disc nearby (in tiles)
        val tile = 16
        val tw = (w + tile - 1) / tile
        val th = (h + tile - 1) / tile
        val tileMax = FloatArray(tw * th)
        for (i in 0 until w * h) { val k = (i / w / tile) * tw + (i % w / tile); tileMax[k] = max(tileMax[k], coc[i]) }
        val reachTiles = ceil(maxR / tile).toInt()
        val reachOf = FloatArray(tw * th) { k ->
            val tx = k % tw; val ty = k / tw
            var m = 0f
            for (yy in max(0, ty - reachTiles)..min(th - 1, ty + reachTiles)) for (xx in max(0, tx - reachTiles)..min(tw - 1, tx + reachTiles)) m = max(m, tileMax[yy * tw + xx])
            m
        }
        // the gaps between the samples smoothed over: a copy blurred about as far as the samples lie apart
        val spacing = maxR * sqrt(PI / points.size)
        val soft = arrayOf(lr, lg, lb, la).map { Blur.gauss(it, w, h, max(0.6, spacing * 0.5)) }
        fun bilinear(a: FloatArray, x: Double, y: Double): Double {
            val fx = (x - 0.5).coerceIn(0.0, w - 1.0); val fy = (y - 0.5).coerceIn(0.0, h - 1.0)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
            val tx = fx - x0; val ty = fy - y0
            return (a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx) * (1 - ty) + (a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx) * ty
        }
        val out = Array(6) { FloatArray(w * h) }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val reach = reachOf[(y / tile) * tw + x / tile].toDouble()
                if (reach < 0.5) {
                    out[0][i] = lr[i]; out[1][i] = lg[i]; out[2][i] = lb[i]; out[3][i] = la[i]
                    continue
                }
                val own = coc[i]
                val ownDepth = depth[i]
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var sa = 0.0; var sw = 0.0; var front = 0.0
                // the pattern turned a little differently for every pixel: fine grain instead of streaks
                var hsh = x * 374761393 + y * 668265263
                hsh = (hsh xor (hsh ushr 13)) * 1274126177
                val turn = ((hsh xor (hsh ushr 16)) and 0xFFFF) / 65536.0 * 2 * PI
                val tc = cos(turn); val ts = sin(turn)
                for (p in points) {
                    val qx = x + 0.5 + (p[0] * tc - p[1] * ts) * reach
                    val qy = y + 0.5 + (p[0] * ts + p[1] * tc) * reach
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy.toInt() * w + qx.toInt()
                    val qc = coc[q].toDouble()
                    val dist = p[2] * reach
                    // its disc must reach here; what lies behind a sharper pixel only within that pixel's own blur
                    if (qc < dist) continue
                    if (depth[q] < ownDepth - 0.02 && own < dist) continue
                    // the light of a disc spreads over its area
                    val wt = p[3] / max(1.0, qc * qc)
                    sr += bilinear(soft[0], qx, qy) * wt; sg += bilinear(soft[1], qx, qy) * wt; sb += bilinear(soft[2], qx, qy) * wt
                    sa += bilinear(soft[3], qx, qy) * wt; sw += wt
                    if (depth[q] > ownDepth + 0.02) front += wt
                }
                if (sw <= 0) {
                    out[0][i] = lr[i]; out[1][i] = lg[i]; out[2][i] = lb[i]; out[3][i] = la[i]
                    continue
                }
                out[0][i] = (sr / sw).toFloat(); out[1][i] = (sg / sw).toFloat(); out[2][i] = (sb / sw).toFloat(); out[3][i] = (sa / sw).toFloat()
                out[4][i] = (front / sw).toFloat()
                out[5][i] = 1f
            }
        }
        return out
    }

    /** How bright the disc is [k] of the way out (0 middle, 1 edge): the rim brighter or softer. */
    private fun rimWeight(k: Double, rim: Double) = max(0.05, 1 + rim * (k.pow(4) * 2 - 0.66) * 1.5)

    /**
     * The highlights' extra light stamped as discs shaped like the aperture, with crisp antialiased
     * edges: gathered per cell (so a bright area is a few big stamps, not thousands), each disc as
     * big as the blur there, its light spread over its area. A sharp pixel in front hides it.
     */
    private fun stamp(
        w: Int, h: Int, er: FloatArray, eg: FloatArray, eb: FloatArray, coc: FloatArray, depth: FloatArray,
        blades: Int, rot: Double, roundness: Double, rim: Double, startCell: Int,
    ): Array<FloatArray> {
        val out = Array(3) { FloatArray(w * h) }
        // the unit aperture's area, weighted by the rim
        var unit = 0.0
        val grid = 96
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val ox = (gx + 0.5) / grid * 2 - 1; val oy = (gy + 0.5) / grid * 2 - 1
            val rr = hypot(ox, oy)
            val e = apertureEdge(kotlin.math.atan2(oy, ox), blades, rot, roundness)
            if (rr <= e) unit += rimWeight(rr / e, rim) * 4.0 / (grid * grid)
        }
        // the highlights gathered per cell: their light, and where (and how blurred, how deep) it sits on average
        class Spot(val x: Double, val y: Double, val r: Double, val d: Double, val cr: Double, val cg: Double, val cb: Double)
        var cell = max(1, startCell)
        var spots: List<Spot>
        while (true) {
            val cw = (w + cell - 1) / cell
            val ch = (h + cell - 1) / cell
            val sum = Array(8) { DoubleArray(cw * ch) }
            for (i in 0 until w * h) {
                val e = er[i] + eg[i] + eb[i]
                if (e <= 0f) continue
                val k = (i / w / cell) * cw + (i % w / cell)
                sum[0][k] += er[i]; sum[1][k] += eg[i]; sum[2][k] += eb[i]
                sum[3][k] += e; sum[4][k] += (i % w + 0.5) * e; sum[5][k] += (i / w + 0.5) * e
                sum[6][k] += coc[i] * e; sum[7][k] += depth[i] * e
            }
            spots = (0 until cw * ch).filter { sum[3][it] > 1e-5 }.map { k ->
                val e = sum[3][k]
                Spot(sum[4][k] / e, sum[5][k] / e, sum[6][k] / e, sum[7][k] / e, sum[0][k], sum[1][k], sum[2][k])
            }
            val cost = spots.sumOf { PI * (it.r + 1.5) * (it.r + 1.5) }
            if (cost < 40_000_000 || cell >= 64) break
            cell *= 2
        }
        for (sp in spots) {
            if (sp.r < 0.75) {
                // in focus: the light stays a point
                val i = sp.y.toInt().coerceIn(0, h - 1) * w + sp.x.toInt().coerceIn(0, w - 1)
                out[0][i] += sp.cr.toFloat(); out[1][i] += sp.cg.toFloat(); out[2][i] += sp.cb.toFloat()
                continue
            }
            val norm = 1.0 / (unit * sp.r * sp.r)
            val x0 = max(0, (sp.x - sp.r - 1).toInt()); val x1 = min(w - 1, (sp.x + sp.r + 1).toInt())
            val y0 = max(0, (sp.y - sp.r - 1).toInt()); val y1 = min(h - 1, (sp.y + sp.r + 1).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val ox = x + 0.5 - sp.x; val oy = y + 0.5 - sp.y
                val rr = hypot(ox, oy)
                if (rr > sp.r + 1) continue
                val edge = (if (blades < 3) 1.0 else apertureEdge(kotlin.math.atan2(oy, ox), blades, rot, roundness)) * sp.r
                val cover = (edge - rr + 0.5).coerceIn(0.0, 1.0)
                if (cover <= 0) continue
                val i = y * w + x
                // a sharp pixel in front of it hides it
                if (depth[i] > sp.d + 0.02 && coc[i] < rr) continue
                val k = cover * rimWeight(min(1.0, rr / max(1e-6, edge)), rim) * norm
                out[0][i] += (sp.cr * k).toFloat(); out[1][i] += (sp.cg * k).toFloat(); out[2][i] += (sp.cb * k).toFloat()
            }
        }
        return out
    }
}
