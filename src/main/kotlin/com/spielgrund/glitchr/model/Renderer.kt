package com.spielgrund.glitchr.model

import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.lerpArgb
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.image.red
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Renders the layer stack onto a transparent canvas. The stack is split into groups:
 * an image layer and the effect layers above it. Each group's picture is placed on
 * the canvas, cut out by the image layer's mask, run through its effects and then laid
 * over the canvas below.
 *
 * Every step is cached and only recomputed when its input or its own settings
 * changed; inputs are compared by identity, which works because cached results are
 * reused as the same objects. Changing a mask or the opacity therefore skips the
 * (possibly slow) effects, and editing an upper group leaves the lower ones alone.
 */
class Renderer {
    private class EffectEntry {
        var input: Pixels? = null
        var effectKey: Any? = null
        var effect: Pixels? = null
        var compositeKey: Any? = null
        var output: Pixels? = null
    }

    private class ImageEntry {
        var placeKey: Any? = null
        var placed: Pixels? = null
        var cutKey: Any? = null
        var cut: Pixels? = null
        var below: Pixels? = null
        var group: Pixels? = null
        var compositeKey: Any? = null
        var output: Pixels? = null
    }

    private val effects = HashMap<Int, EffectEntry>()
    private val images = HashMap<Int, ImageEntry>()
    private var empty: Pixels? = null

    /** [layers] bottom first; effect layers below the first image layer have nothing to work on and are skipped. */
    @Synchronized
    fun render(width: Int, height: Int, layers: List<LayerState>): Pixels {
        val ids = layers.map { it.id }.toSet()
        effects.keys.retainAll(ids)
        images.keys.retainAll(ids)

        var canvas = empty?.takeIf { it.width == width && it.height == height } ?: Pixels(width, height).also { empty = it }
        val blank = canvas
        var i = 0
        while (i < layers.size) {
            val image = layers[i] as? ImageState
            var end = i + 1
            while (end < layers.size && layers[end] !is ImageState) end++
            if (image != null && image.visible && image.opacity > 0) {
                val groupEffects = layers.subList(i + 1, end).filterIsInstance<EffectState>()
                canvas = renderGroup(image, groupEffects, canvas, blank, width, height)
            }
            i = end
        }
        return canvas
    }

    private fun renderGroup(
        image: ImageState, groupEffects: List<EffectState>, below: Pixels, blank: Pixels, width: Int, height: Int,
    ): Pixels {
        val entry = images.getOrPut(image.id) { ImageEntry() }
        val placeKey = listOf(image.image, image.x, image.y, image.scale, image.smooth, width, height)
        if (entry.placeKey != placeKey || entry.placed == null) {
            entry.placed = place(image, width, height)
            entry.placeKey = placeKey
            entry.cut = null
        }
        // masks of this group lie on the placed picture, so they move and scale with it
        val space = spaceOf(image)

        // the image layer's own mask cuts the picture out first, so the effects can reach
        // beyond the cut edge instead of being clipped by it afterwards
        val cutKey = listOf(image.maskVersion, space)
        if (entry.cutKey != cutKey || entry.cut == null) {
            entry.cut = cutOut(entry.placed!!, image, space)
            entry.cutKey = cutKey
        }
        var group = entry.cut!!
        for (fx in groupEffects) group = applyEffect(fx, group, space)

        val compositeKey = listOf(image.opacity, image.blend)
        if (entry.below !== below || entry.group !== group || entry.compositeKey != compositeKey || entry.output == null) {
            entry.output = if (below === blank && image.opacity >= 100) group
            else over(below, group, image, space, applyMask = false)
            entry.below = below
            entry.group = group
            entry.compositeKey = compositeKey
        }
        return entry.output!!
    }

    private fun applyEffect(layer: EffectState, input: Pixels, space: MaskSpace): Pixels {
        if (!layer.visible || layer.opacity == 0) return input
        val entry = effects.getOrPut(layer.id) { EffectEntry() }
        val sameInput = entry.input === input

        val effectKey = listOf(layer.effect.id, layer.values, layer.seed, layer.texts)
        if (!sameInput || entry.effectKey != effectKey || entry.effect == null) {
            entry.effect = layer.effect.apply(input, Values(layer.values, layer.texts), layer.seed)
            entry.effectKey = effectKey
            entry.compositeKey = null
        }
        val compositeKey = listOf(layer.maskVersion, layer.opacity, layer.blend, space)
        if (!sameInput || entry.compositeKey != compositeKey || entry.output == null) {
            entry.output = composite(input, entry.effect!!, layer, space)
            entry.compositeKey = compositeKey
        }
        entry.input = input
        return entry.output!!
    }

    companion object {
        /** The canvas rectangle covered by the picture of [image]; its group's masks span it. */
        fun spaceOf(image: ImageState) =
            MaskSpace(image.x, image.y, image.image.width * image.scale, image.image.height * image.scale)

        /** The picture of [layer] drawn onto a transparent canvas; the picture itself if it already fits exactly. */
        fun place(layer: ImageState, width: Int, height: Int): Pixels {
            val img = layer.image
            if (layer.x == 0.0 && layer.y == 0.0 && layer.scale == 1.0 && img.width == width && img.height == height) return img
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            canvas.createGraphics().apply {
                setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    if (layer.smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
                drawImage(img.toImage(), AffineTransform(layer.scale, 0.0, 0.0, layer.scale, layer.x, layer.y), null)
                dispose()
            }
            return Pixels(width, height, (canvas.raster.dataBuffer as DataBufferInt).data)
        }

        /** [placed] with the layer's mask multiplied into its transparency; [placed] itself without mask. */
        fun cutOut(placed: Pixels, layer: LayerState, space: MaskSpace): Pixels {
            if (layer.mask.isOff) return placed
            val w = placed.width
            val h = placed.height
            val out = Pixels(w, h)
            parallelRows(h) { y ->
                val m = FloatArray(w)
                layer.mask.row(y, w, h, m, space)
                for (x in 0 until w) {
                    val c = placed.data[y * w + x]
                    val a = (alpha(c) * m[x] + 0.5f).toInt()
                    out.data[y * w + x] = (a shl 24) or (c and 0x00FFFFFF)
                }
            }
            return out
        }

        /** Mixes the effect result [fx] over [base] through the layer's blend mode, mask and opacity. */
        fun composite(base: Pixels, fx: Pixels, layer: LayerState, space: MaskSpace = MaskSpace.canvas(base.width, base.height)): Pixels {
            if (layer.mask.isOff && layer.opacity >= 100 && layer.blend == BlendMode.NORMAL) return fx
            val w = base.width
            val h = base.height
            val opacity = layer.opacity / 100f
            val out = Pixels(w, h)
            parallelRows(h) { y ->
                val m = FloatArray(w)
                layer.mask.row(y, w, h, m, space)
                for (x in 0 until w) {
                    val i = y * w + x
                    val b = base.data[i]
                    out.data[i] = lerpArgb(b, layer.blend.apply(b, fx.data[i]), m[x] * opacity)
                }
            }
            return out
        }

        /**
         * Lays [top] over [base] with transparency ("over"): where both are opaque the
         * blend mode decides the color, where only one is, that one shows.
         */
        fun over(
            base: Pixels, top: Pixels, layer: LayerState,
            space: MaskSpace = MaskSpace.canvas(base.width, base.height), applyMask: Boolean = true,
        ): Pixels {
            val w = base.width
            val h = base.height
            val opacity = layer.opacity / 100f
            val out = Pixels(w, h)
            parallelRows(h) { y ->
                val m = FloatArray(w)
                if (applyMask) layer.mask.row(y, w, h, m, space) else m.fill(1f)
                for (x in 0 until w) {
                    val i = y * w + x
                    val b = base.data[i]
                    val t = top.data[i]
                    val ta = alpha(t) / 255f * m[x] * opacity
                    if (ta <= 0f) {
                        out.data[i] = b
                        continue
                    }
                    val ba = alpha(b) / 255f
                    // top color where it covers the base: blended by how much base is there
                    val blended = if (ba > 0f && layer.blend != BlendMode.NORMAL) lerpArgb(t, layer.blend.apply(b, t), ba) else t
                    val oa = ta + ba * (1 - ta)
                    fun ch(tc: Int, bc: Int) = ((tc * ta + bc * ba * (1 - ta)) / oa + 0.5f).toInt().coerceIn(0, 255)
                    out.data[i] = argb(
                        (oa * 255 + 0.5f).toInt().coerceIn(0, 255),
                        ch(red(blended), red(b)), ch(green(blended), green(b)), ch(blue(blended), blue(b)),
                    )
                }
            }
            return out
        }
    }
}
