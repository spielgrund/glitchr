package com.spielgrund.glitchr.model

import com.spielgrund.glitchr.effects.FrameInput
import com.spielgrund.glitchr.effects.Generator
import com.spielgrund.glitchr.effects.TimeEffect
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
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders the layer stack onto a transparent canvas. The stack is split into groups:
 * a source layer and the effect layers above it. Each group's picture is placed on the
 * canvas (or generated in canvas size), cut out by the source layer's mask, run through
 * its effects and then laid over the canvas below. An adjustment layer starts a group
 * too: its picture is the canvas so far, and its result replaces the canvas through its
 * mask, opacity and blend mode.
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

    private class AdjustmentEntry {
        var below: Pixels? = null
        var effectKey: Any? = null
        var effect: Pixels? = null
        var group: Pixels? = null
        var compositeKey: Any? = null
        var output: Pixels? = null
    }

    private val effects = HashMap<Int, EffectEntry>()
    private val adjustments = HashMap<Int, AdjustmentEntry>()
    private val images = HashMap<Int, ImageEntry>()
    private var empty: Pixels? = null

    /** The last pictures of the generator layers, by layer id; readable from any thread. */
    private val generatedPictures = ConcurrentHashMap<Int, Pixels>()

    /** The picture the generator layer [id] made in the last render; null if it hasn't been rendered yet. */
    fun generated(id: Int): Pixels? = generatedPictures[id]

    /** The animation of the render running now, for time effects; null without one. */
    private var time: TimeSession? = null
    private var frame = 0

    /**
     * [layers] bottom first; effect layers below the first source layer have nothing to work on and are skipped.
     * With [time], time effects can see the other frames of the animation; [frame] is the one rendered.
     * With [stopAt] the render ends at that effect layer and returns the picture it would receive.
     */
    @Synchronized
    fun render(
        width: Int, height: Int, layers: List<LayerState>, time: TimeSession? = null, frame: Int = 0, stopAt: Int? = null,
    ): Pixels {
        this.time = time
        this.frame = frame
        val ids = layers.map { it.id }.toSet()
        effects.keys.retainAll(ids)
        images.keys.retainAll(ids)
        adjustments.keys.retainAll(ids)
        generatedPictures.keys.retainAll(ids)

        var canvas = empty?.takeIf { it.width == width && it.height == height } ?: Pixels(width, height).also { empty = it }
        val blank = canvas
        var i = 0
        while (i < layers.size) {
            val first = layers[i]
            var end = i + 1
            while (end < layers.size && !layers[end].startsGroup) end++
            // the input of an adjustment layer is everything below it
            if (first.id == stopAt) return canvas
            val all = layers.subList(i + 1, end).filterIsInstance<EffectState>()
            val stop = all.indexOfFirst { it.id == stopAt }
            if (first.visible && first.opacity > 0) {
                val groupEffects = if (stop >= 0) all.subList(0, stop) else all
                when {
                    first is SourceState -> canvas = renderGroup(first, groupEffects, canvas, blank, width, height, inputOnly = stop >= 0)
                    first is EffectState && first.adjustment -> canvas = renderAdjustment(first, groupEffects, canvas, inputOnly = stop >= 0)
                }
            }
            if (stop >= 0) return canvas
            i = end
        }
        return canvas
    }

    private fun renderGroup(
        image: SourceState, groupEffects: List<EffectState>, below: Pixels, blank: Pixels, width: Int, height: Int,
        inputOnly: Boolean = false,
    ): Pixels {
        val entry = images.getOrPut(image.id) { ImageEntry() }
        val placeKey = when (image) {
            is ImageState -> listOf(image.image, image.x, image.y, image.scale, image.smooth, image.rotation, width, height)
            is GeneratorState -> listOf(image.generator.id, image.values, image.seed, image.texts, width, height)
        }
        if (entry.placeKey != placeKey || entry.placed == null) {
            entry.placed = when (image) {
                is ImageState -> place(image, width, height)
                is GeneratorState -> image.generator.generate(width, height, Values(image.values, image.texts), image.seed)
            }
            entry.placeKey = placeKey
            entry.cut = null
        }
        if (image is GeneratorState) generatedPictures[image.id] = entry.placed!!
        // masks of this group lie on the placed picture, so they move and scale with it
        val space = spaceOf(image, width, height)

        // the image layer's own mask cuts the picture out first, so the effects can reach
        // beyond the cut edge instead of being clipped by it afterwards
        val cutKey = listOf(image.maskVersion, space)
        if (entry.cutKey != cutKey || entry.cut == null) {
            entry.cut = cutOut(entry.placed!!, image, space)
            entry.cutKey = cutKey
        }
        var group = entry.cut!!
        for (fx in groupEffects) group = applyEffect(fx, group, space)
        if (inputOnly) return group

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

    /**
     * An adjustment layer: its effect works on everything below ([below]), the effects
     * above it refine that, and the result is mixed back over [below] through the
     * adjustment layer's mask, opacity and blend mode.
     */
    private fun renderAdjustment(layer: EffectState, groupEffects: List<EffectState>, below: Pixels, inputOnly: Boolean = false): Pixels {
        val entry = adjustments.getOrPut(layer.id) { AdjustmentEntry() }
        val space = MaskSpace.canvas(below.width, below.height)
        val effectKey = listOf(layer.effect.id, layer.values, layer.seed, layer.texts, timeKey(layer))
        if (entry.below !== below || entry.effectKey != effectKey || entry.effect == null) {
            entry.effect = runEffect(layer, below)
            entry.effectKey = effectKey
            entry.output = null
        }
        var group = entry.effect!!
        for (fx in groupEffects) group = applyEffect(fx, group, space)
        if (inputOnly) return group

        val compositeKey = listOf(layer.maskVersion, layer.opacity, layer.blend)
        if (entry.below !== below || entry.group !== group || entry.compositeKey != compositeKey || entry.output == null) {
            entry.output = composite(below, group, layer, space)
            entry.group = group
            entry.compositeKey = compositeKey
        }
        entry.below = below
        return entry.output!!
    }

    private fun applyEffect(layer: EffectState, input: Pixels, space: MaskSpace): Pixels {
        if (!layer.visible || layer.opacity == 0) return input
        val entry = effects.getOrPut(layer.id) { EffectEntry() }
        val sameInput = entry.input === input

        val effectKey = listOf(layer.effect.id, layer.values, layer.seed, layer.texts, timeKey(layer))
        if (!sameInput || entry.effectKey != effectKey || entry.effect == null) {
            entry.effect = runEffect(layer, input)
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

    /**
     * What else a time effect's result depends on besides its input: the frame and the
     * layers below it over the whole animation (their other frames are its other inputs).
     */
    private fun timeKey(layer: EffectState): Any? {
        val t = time ?: return null
        if (layer.effect !is TimeEffect) return null
        return listOf(frame, t.frameCount, t.below(layer.id))
    }

    private fun runEffect(layer: EffectState, input: Pixels): Pixels {
        val values = Values(layer.values, layer.texts)
        val effect = layer.effect
        if (effect !is TimeEffect) return effect.apply(input, values, layer.seed)
        val t = time
        val current = frame
        val frames = t?.let {
            object : FrameInput {
                override val frame = current
                override val frameCount = it.frameCount
                override fun at(f: Int) = if (f == current) input else it.input(layer.id, f)
            }
        }
        return effect.apply(input, values, layer.seed, frames)
    }

    companion object {
        /** The canvas rectangle covered by the picture of [image]; its group's masks span it. */
        fun spaceOf(image: ImageState) =
            MaskSpace(image.x, image.y, image.image.width * image.scale, image.image.height * image.scale, Math.toRadians(image.rotation))

        /** Like [spaceOf]: for a generated picture the canvas, moved, scaled and turned by its position. */
        fun spaceOf(source: SourceState, width: Int, height: Int) = when (source) {
            is ImageState -> spaceOf(source)
            is GeneratorState -> {
                fun value(key: String, default: Int) = source.values[key] ?: default
                val scale = value(Generator.POS_SCALE, 1000) / 1000.0
                val w = width * scale
                val h = height * scale
                val cx = width / 2.0 + value(Generator.POS_X, 0) / 10.0
                val cy = height / 2.0 + value(Generator.POS_Y, 0) / 10.0
                MaskSpace(cx - w / 2, cy - h / 2, w, h, Math.toRadians(value(Generator.POS_ROTATION, 0) / 100.0))
            }
        }

        /** The picture of [layer] drawn onto a transparent canvas; the picture itself if it already fits exactly. */
        fun place(layer: ImageState, width: Int, height: Int): Pixels {
            val img = layer.image
            if (layer.x == 0.0 && layer.y == 0.0 && layer.scale == 1.0 && layer.rotation == 0.0 && img.width == width && img.height == height) return img
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            canvas.createGraphics().apply {
                setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    if (layer.smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
                drawImage(img.toImage(), placement(img.width, img.height, layer.x, layer.y, layer.scale, layer.rotation), null)
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

/**
 * One animation as the time effects see it: the [layers] with their tracks, so any frame
 * can be rendered. The inputs of time effects at other frames are rendered on demand –
 * each with a renderer of its own, so renders running at the same time don't disturb
 * each other – and kept for a while, so playing or exporting computes each only once,
 * even when several threads render frames at the same time.
 */
class TimeSession(val layers: List<AnimatedLayer>, val frameCount: Int, private val width: Int, private val height: Int) {
    /** How many inputs are kept: about a fifth of the memory Java may use. */
    private val budget = (Runtime.getRuntime().maxMemory() * 0.2 / (width.toLong() * height * 4).coerceAtLeast(1)).toInt().coerceIn(4, 1000)

    private val inputs = object : LinkedHashMap<Long, java.util.concurrent.FutureTask<Pixels>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, java.util.concurrent.FutureTask<Pixels>>) = size > budget
    }

    /** The picture the effect layer [layerId] receives at [frame]. */
    fun input(layerId: Int, frame: Int): Pixels {
        val key = (layerId.toLong() shl 32) or (frame.toLong() and 0xFFFFFFFFL)
        var mine: java.util.concurrent.FutureTask<Pixels>? = null
        val task = synchronized(inputs) {
            inputs.getOrPut(key) {
                java.util.concurrent.FutureTask {
                    Renderer().render(width, height, layers.map { it.at(frame) }, this, frame, stopAt = layerId)
                }.also { mine = it }
            }
        }
        // the thread that asked first renders it, the others wait for that
        mine?.run()
        return try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Everything the inputs of [layerId] at other frames depend on: the layers below it with
     * their tracks – comparable between renders (masks by their version, not their shape object).
     */
    fun below(layerId: Int) = layers.takeWhile { it.state.id != layerId }.map { l ->
        val common = listOf(l.state.id, l.state.visible, l.state.opacity, l.state.blend, l.state.maskVersion, l.tracks, l.seedPerFrame)
        when (val st = l.state) {
            is EffectState -> listOf(common, st.effect.id, st.values, st.seed, st.texts, st.adjustment)
            is GeneratorState -> listOf(common, st.generator.id, st.values, st.seed, st.texts)
            is ImageState -> listOf(common, st.image, st.x, st.y, st.scale, st.smooth, st.rotation)
        }
    }
}
