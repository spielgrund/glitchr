package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.sampleBilinear
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Monster wave: standing in front of a giant wave. Below a line the picture stays; above it, it runs
 * up the wave's face – leaning back a little at the bottom, then steepening – and curls over towards
 * the viewer in a lip, seen in perspective: the nearer the curl comes, the bigger it looms and the
 * higher it reaches, until it hangs overhead, the picture upside down on its underside. The picture
 * can flow up the wave (animate it), the water can be tinted and the hollow under the lip darkened.
 *
 * The wave's profile is a line in height and depth; it is walked along (the picture's rows follow
 * it), projected through a camera in front, and for every screen row the nearest surface is kept.
 */
object MonsterWave : Effect("monsterwave", "Monster wave", "Above a line the picture runs up a giant wave and curls over the viewer, in perspective – as if standing in front of a monster wave; the picture can flow up it") {
    override val params = listOf(
        Param.Slider("lineY", "Line", -10000, 10000, 2000, " %", "Where the wave rises, from the middle: −100 % = the top edge – the handle drags it", decimals = 2),
        Param.Slider("centerX", "Facing", -10000, 10000, 0, " %", "Where you stand across – the perspective centers here", decimals = 2),
        Param.Heading("waveHeading", "Wave"),
        Param.Slider("height", "Height", 5, 2000, 150, " %", "How tall the wave is, of the space above the line"),
        Param.Slider("curl", "Curl", -36000, 108000, 20000, "°", "How far the wave turns over towards you: 90° = the crest reaches out flat, 180° = the lip points down, 270° = curling back under", decimals = 2),
        Param.Slider("lip", "Lip size", 0, 100, 40, " %", "How low the curl begins: small = a straight face with a tight lip at the top, large = the whole wave one big curve"),
        Param.Slider("lean", "Lean back", -300, 300, 25, " %", "The foot of the wave leans back away from you before it steepens – negative: it leans towards you right away"),
        Param.Slider("distance", "Distance", 1, 1000, 60, " %", "How far you stand in front of it, of the picture's height: near = the curl looms overhead"),
        Param.Slider("lookUp", "Looking up", 500, 17500, 7000, "°", "How far up the view reaches at the top edge: wide = the wall towers and the lip hangs overhead in view", decimals = 2),
        Param.Heading("waterHeading", "Water"),
        Param.Choice("from", "Made of", listOf("The ground below the line", "The picture above the line"), 0, "The ground: what lies in front of the line folds up into the wave and over you · The picture above: the picture itself runs up the wave"),
        Param.Slider("stretch", "Stretch", 1, 1000, 100, " %", "How much of the picture is spread along the wave: small = a little of it drawn out in long streaks"),
        Param.Slider("smear", "Smear", 0, 100, 0, " %", "Draws out what lies near the line into ever longer streaks up the wave, while the far part rushes by"),
        Param.Slider("widen", "Widen", -100, 300, 0, " %", "Pulls the picture apart sideways the higher up the wave – or together (negative)"),
        Param.Slider("flow", "Flow", -1000000, 1000000, 0, " px", "Moves the picture up the wave – animate it and the water rushes up and over", decimals = 1),
        Param.Slider("shading", "Shading", 0, 100, 45, " %", "The hollow under the lip darker, the face lighter"),
        Param.Color("tint", "Water color", 0x1E6F7A),
        Param.Slider("tintAmount", "Tint", 0, 100, 0, " %", "How much the wave takes on the water color"),
        Param.Slider("foam", "Foam", 0, 100, 30, " %", "White water on the lip's edge"),
        Param.Choice("behind", "Behind the wave", listOf("The picture", "Transparent"), 0, "What shows above the wave where it doesn't reach"),
        Param.Choice("edge", "Beyond the picture", listOf("Mirror", "Stretch edge", "Repeat"), 0, "Where the wave needs more picture than there is above the line"),
    )

    override val random = false

    override val canvasHandle = CanvasHandle("centerX", "lineY", percent = true)

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val hy = h / 2.0 * (1 + v["lineY"] / 10000.0)
        val cx = w / 2.0 * (1 + v["centerX"] / 10000.0)
        val room = hy.coerceAtLeast(1.0)
        val height = room * v["height"] / 100.0
        val curl = Math.toRadians(v["curl"] / 100.0)
        val lean = v["lean"] / 100.0
        val dist = h * v["distance"] / 100.0
        // the view upwards: the line is straight ahead, the top edge this far up (equal steps of angle)
        val lookUp = Math.toRadians(v["lookUp"] / 100.0)
        val stretch = v["stretch"] / 100.0
        val smear = 1 + v["smear"] / 100.0 * 6
        val widen = v["widen"] / 100.0
        val fromGround = v["from"] == 0
        val flow = v["flow"] / 10.0
        val shading = v["shading"] / 100.0
        val tintAmount = v["tintAmount"] / 100.0
        val tint = v["tint"]
        val foam = v["foam"] / 100.0
        val behindTransparent = v["behind"] == 1
        val edge = when (v["edge"]) { 1 -> Edge.CLAMP; 2 -> Edge.WRAP; else -> Edge.MIRROR }

        // the profile: walked along its length, its direction turning ever faster towards you – upright (or leaning
        // back) at the foot, steeper, then curling over: the turn so far is curl · (how far along)^shape
        val length = height * 1.35
        val shape = 0.7 + (1 - v["lip"] / 100.0) * 3.0
        val startAngle = -Math.toRadians(25.0) * lean

        // every screen row above the line: the nearest surface there – where along the wave, how near, how far round
        val rowS = DoubleArray(h) { Double.NaN }
        val rowDepth = DoubleArray(h) { Double.POSITIVE_INFINITY }
        val rowFacing = DoubleArray(h)
        val rowCurl = DoubleArray(h)
        var s = 0.0
        var d = 0.0
        var z = 0.0
        var prevY = Double.NaN; var prevS = 0.0; var prevD = 0.0; var prevF = 0.0; var prevC = 0.0
        while (s <= length) {
            val along = s / length
            // its direction: 0 = straight up, 90° = towards you, 180° = down
            val turn = startAngle + (curl - startAngle) * along.pow(shape)
            // how far it is from you (it may pass over your head and behind you), and how far up you look to see it
            val ahead = dist + d
            val depth = kotlin.math.sqrt(ahead * ahead + z * z)
            if (depth > dist * 0.02) {
                val y = hy - hy * kotlin.math.atan2(z, ahead) / lookUp
                // facing: how far it has turned over – past 90° you see its underside
                val facing = -sin(turn)
                if (!prevY.isNaN()) {
                    // fill every row between the last point and this one
                    val y0 = min(prevY, y); val y1 = max(prevY, y)
                    for (r in max(0, floor(y0).toInt())..min(h - 1, floor(y1).toInt())) {
                        if (r >= hy) continue
                        val f = if (y1 - y0 < 1e-9) 0.0 else ((r + 0.5 - prevY) / (y - prevY)).coerceIn(0.0, 1.0)
                        val dd = prevD + (depth - prevD) * f
                        if (dd < rowDepth[r]) {
                            rowDepth[r] = dd; rowS[r] = prevS + (s - prevS) * f
                            rowFacing[r] = prevF + (facing - prevF) * f; rowCurl[r] = prevC + (along - prevC) * f
                        }
                    }
                }
                prevY = y; prevS = s; prevD = depth; prevF = facing; prevC = along
            } else prevY = Double.NaN
            // finer steps where the wave comes near
            val ds = 0.4 * (depth / dist).coerceIn(0.03, 1.0)
            z += cos(turn) * ds
            d -= sin(turn) * ds
            s += ds
        }

        val tr = (tint shr 16 and 0xFF).toDouble(); val tg = (tint shr 8 and 0xFF).toDouble(); val tb = (tint and 0xFF).toDouble()
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                if (y >= hy || rowS[y].isNaN()) {
                    out.data[i] = if (y < hy && behindTransparent) 0 else src.data[i]
                    continue
                }
                // nearer surfaces are bigger: the picture's columns spread out from where you stand
                val sx = cx + (x + 0.5 - cx) * rowDepth[y] / dist / (1 + widen * rowCurl[y]).coerceAtLeast(0.02)
                // smeared: what lies near the line is drawn out, the far part rushes by
                val along = if (smear > 1) length * (rowS[y] / length).coerceAtLeast(0.0).pow(smear) else rowS[y]
                val sy = if (fromGround) hy + along * stretch + flow else hy - along * stretch - flow
                var c = sampleBilinear(src, sx, sy, edge)
                // light: the hollow under the lip darker, the face lighter; tint and foam
                // past the crest you see the underside of the lip: the hollow
                val hollow = (-rowFacing[y]).coerceAtLeast(0.0)
                val shade = (1 - shading * (0.6 * hollow + 0.15 * rowCurl[y]) + shading * 0.12 * (1 - rowCurl[y])).coerceIn(0.1, 1.5)
                val lipEdge = if (curl > 0) ((rowCurl[y] - 0.88) / 0.12).coerceIn(0.0, 1.0) else 0.0
                val froth = foam * lipEdge
                fun ch(sh: Int, tc: Double): Int {
                    var cc = (c shr sh and 0xFF) * (1 - tintAmount) + tc * tintAmount
                    cc *= shade
                    cc += (255 - cc) * froth
                    return cc.roundToInt().coerceIn(0, 255)
                }
                c = (c and 0xFF000000.toInt()) or (ch(16, tr) shl 16) or (ch(8, tg) shl 8) or ch(0, tb)
                out.data[i] = c
            }
        }
        return out
    }
}
