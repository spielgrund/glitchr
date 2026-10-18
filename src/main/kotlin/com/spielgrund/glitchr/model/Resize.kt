package com.spielgrund.glitchr.model

import com.spielgrund.glitchr.effects.FlowStrokes
import com.spielgrund.glitchr.effects.Generator
import com.spielgrund.glitchr.effects.Param
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Changes the canvas of a document: every canvas point p goes to p · [factor] + ([dx], [dy])
 * on a new canvas of [newWidth] × [newHeight]. Cropping and enlarging the canvas only move
 * (factor 1), scaling the image also scales. Everything keeps its place on the picture:
 * picture layers, generator positions, the effects' handles, drawn flow strokes and masks
 * (painted ones are resampled), keyframes included. With [scaleSizes], pixel sizes in the
 * effects (radii, widths, distances) are scaled too.
 */
class Resize(
    private val oldWidth: Int, private val oldHeight: Int,
    val newWidth: Int, val newHeight: Int,
    private val factor: Double, private val dx: Double, private val dy: Double,
    private val scaleSizes: Boolean = true,
) {
    private fun mapX(x: Double) = x * factor + dx
    private fun mapY(y: Double) = y * factor + dy

    /** A position stored as pixels from the canvas middle ([div] per pixel). */
    private fun fromMiddleX(stored: Double, div: Double) = (mapX(oldWidth / 2.0 + stored / div) - newWidth / 2.0) * div
    private fun fromMiddleY(stored: Double, div: Double) = (mapY(oldHeight / 2.0 + stored / div) - newHeight / 2.0) * div

    /** Applies the change to [layers] (bottom first), in place. */
    fun apply(layers: List<Layer>) {
        // the masks lie in their group's space: remember it before anything moves
        val oldSpaces = spaces(layers, oldWidth, oldHeight)
        for (layer in layers) {
            when (layer) {
                is ImageLayer -> moveImage(layer)
                is GeneratorLayer -> {
                    moveParams(layer, layer.generator.params, layer.generator.effect?.canvasHandle?.let { listOf(it.xKey, it.yKey) } ?: emptyList())
                    moveMiddle(layer, Generator.POS_X, Generator.POS_Y, 10.0)
                }
                is EffectLayer -> moveParams(layer, layer.effect.params, layer.effect.canvasHandle?.let { listOf(it.xKey, it.yKey) } ?: emptyList())
            }
        }
        val newSpaces = spaces(layers, newWidth, newHeight)
        for ((i, layer) in layers.withIndex()) {
            moveMask(layer, oldSpaces[i], newSpaces[i])
            if (layer is EffectLayer) moveFlows(layer)
        }
    }

    /** For every layer the space its mask lies in: its group's picture, or the canvas. */
    private fun spaces(layers: List<Layer>, width: Int, height: Int): List<MaskSpace> {
        var current = MaskSpace.canvas(width, height)
        return layers.map { l ->
            if (l.startsGroup) current = when (val s = l.state()) {
                is SourceState -> Renderer.spaceOf(s, width, height)
                else -> MaskSpace.canvas(width, height)
            }
            current
        }
    }

    private fun moveImage(l: ImageLayer) {
        l.x = mapX(l.x)
        l.y = mapY(l.y)
        l.scale *= factor
        l.tracks[AnimKeys.X]?.let { t -> l.tracks[AnimKeys.X] = t.map(::mapX) }
        l.tracks[AnimKeys.Y]?.let { t -> l.tracks[AnimKeys.Y] = t.map(::mapY) }
        l.tracks[AnimKeys.SCALE]?.let { t -> l.tracks[AnimKeys.SCALE] = t.map { it * factor } }
    }

    /** A pair of settings in pixels from the canvas middle (with [div] steps per pixel). */
    private fun moveMiddle(l: Layer, xKey: String, yKey: String, div: Double) {
        val p = l as ParamLayer
        p.values[xKey]?.let { p.values[xKey] = fromMiddleX(it.toDouble(), div).roundToInt() }
        p.values[yKey]?.let { p.values[yKey] = fromMiddleY(it.toDouble(), div).roundToInt() }
        l.tracks[xKey]?.let { t -> l.tracks[xKey] = t.map { fromMiddleX(it, div) } }
        l.tracks[yKey]?.let { t -> l.tracks[yKey] = t.map { fromMiddleY(it, div) } }
    }

    private fun moveParams(l: Layer, params: List<Param>, handleKeys: List<String>) {
        val p = l as ParamLayer
        if (handleKeys.size == 2) {
            val slider = params.firstOrNull { it.key == handleKeys[0] } as? Param.Slider
            moveMiddle(l, handleKeys[0], handleKeys[1], 10.0.pow(slider?.decimals ?: 0))
        }
        if (!scaleSizes || factor == 1.0) return
        for (param in params) {
            if (param !is Param.Slider || param.unit.trim() != "px") continue
            if (param.key in handleKeys || param.key == Generator.POS_X || param.key == Generator.POS_Y) continue
            val max = param.maxFor(newWidth, newHeight)
            fun scaled(v: Double) = (v * factor).roundToInt().coerceIn(param.min, max(max, param.max)).toDouble()
            p.values[param.key]?.let { p.values[param.key] = scaled(it.toDouble()).toInt() }
            l.tracks[param.key]?.let { t -> l.tracks[param.key] = t.map(::scaled) }
        }
    }

    /** Flow strokes are stored relative to the canvas. */
    private fun moveFlows(l: EffectLayer) {
        for (param in l.effect.params.filterIsInstance<Param.Flow>()) {
            val text = l.texts[param.key] ?: continue
            if (text.isBlank()) continue
            l.texts[param.key] = FlowStrokes.format(FlowStrokes.parse(text).map { stroke ->
                stroke.map { java.awt.geom.Point2D.Double(mapX(it.x * oldWidth) / newWidth, mapY(it.y * oldHeight) / newHeight) }
            })
        }
    }

    /** A point of a mask from the old to the new space: through the canvas, where the change happens. */
    private fun movePoint(r: RelPoint, from: MaskSpace, to: MaskSpace): RelPoint {
        val c = from.toCanvas(r.x * from.width, r.y * from.height)
        val local = DoubleArray(2)
        to.toLocal(mapX(c.x), mapY(c.y), local)
        return RelPoint(local[0] / to.width, local[1] / to.height)
    }

    private fun moveMask(l: Layer, from: MaskSpace, to: MaskSpace) {
        val m = l.mask.memento()
        // a picture's own space moves along with it: nothing to do
        val same = movePoint(RelPoint(0.0, 0.0), from, to).let { kotlin.math.abs(it.x) < 1e-9 && kotlin.math.abs(it.y) < 1e-9 } &&
            movePoint(RelPoint(1.0, 1.0), from, to).let { kotlin.math.abs(it.x - 1) < 1e-9 && kotlin.math.abs(it.y - 1) < 1e-9 }
        if (same) return
        var painted = m.painted
        var pw = m.paintedWidth
        var ph = m.paintedHeight
        if (painted != null && pw > 0 && ph > 0) {
            // as many mask pixels per canvas pixel as before
            val nw = max(1, (pw * to.width / from.width / factor).roundToInt())
            val nh = max(1, (ph * to.height / from.height / factor).roundToInt())
            val old = painted
            val local = DoubleArray(2)
            painted = ByteArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                // the new mask pixel on the canvas, back to the old canvas and into the old mask
                val c = to.toCanvas((x + 0.5) / nw * to.width, (y + 0.5) / nh * to.height)
                from.toLocal((c.x - dx) / factor, (c.y - dy) / factor, local)
                val ox = (local[0] / from.width * pw).toInt().coerceIn(0, pw - 1)
                val oy = (local[1] / from.height * ph).toInt().coerceIn(0, ph - 1)
                painted[y * nw + x] = old[oy * pw + ox]
            }
            pw = nw
            ph = nh
        }
        l.mask.restore(
            m.copy(
                linearStart = movePoint(m.linearStart, from, to), linearEnd = movePoint(m.linearEnd, from, to),
                radialCenter = movePoint(m.radialCenter, from, to), radialEdge = movePoint(m.radialEdge, from, to),
                painted = painted, paintedWidth = pw, paintedHeight = ph,
            ),
        )
        // animated gradient points (in % of the old space), each with the other coordinate as it was
        val radial = m.mode == MaskMode.RADIAL
        val oldA = if (radial) m.radialCenter else m.linearStart
        val oldB = if (radial) m.radialEdge else m.linearEnd
        fun moveTrack(key: String, x: Boolean, old: RelPoint) {
            val t = l.tracks[key] ?: return
            l.tracks[key] = t.map { v ->
                val moved = movePoint(if (x) old.copy(x = v / 100) else old.copy(y = v / 100), from, to)
                (if (x) moved.x else moved.y) * 100
            }
        }
        moveTrack(AnimKeys.MASK_AX, true, oldA)
        moveTrack(AnimKeys.MASK_AY, false, oldA)
        moveTrack(AnimKeys.MASK_BX, true, oldB)
        moveTrack(AnimKeys.MASK_BY, false, oldB)
    }
}

/** This track with every keyframe value changed by [f]. */
private fun Track.map(f: (Double) -> Double) = Track(kind, keys.map { it.copy(value = f(it.value)) })
