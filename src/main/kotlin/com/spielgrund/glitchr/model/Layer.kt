package com.spielgrund.glitchr.model

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Generator
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import java.awt.geom.Rectangle2D
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** How a layer is combined with what lies below it. */
enum class BlendMode(val label: String, private val f: (Int, Int) -> Int) {
    NORMAL("Normal", { _, t -> t }),
    LIGHTEN("Aufhellen", { b, t -> max(b, t) }),
    DARKEN("Abdunkeln", { b, t -> min(b, t) }),
    SCREEN("Negativ multiplizieren", { b, t -> 255 - (255 - b) * (255 - t) / 255 }),
    MULTIPLY("Multiplizieren", { b, t -> b * t / 255 }),
    ADD("Addieren", { b, t -> min(255, b + t) }),
    DIFFERENCE("Differenz", { b, t -> abs(b - t) });

    fun apply(base: Int, top: Int): Int =
        if (this == NORMAL) top
        else argb(alpha(top), f(red(base), red(top)), f(green(base), green(top)), f(blue(base), blue(top)))

    override fun toString() = label
}

private val nextId = AtomicInteger()

/**
 * An entry of the layer stack: a picture source ([SourceLayer]: a loaded picture or a
 * generated one) or an effect ([EffectLayer]). Effects work on the nearest source layer
 * below them; that picture with its effects is then laid over everything further down.
 */
sealed class Layer(val id: Int) {
    companion object {
        fun newId() = nextId.incrementAndGet()
    }

    abstract var name: String
    var visible = true
    var opacity = 100
    var blend = BlendMode.NORMAL
    var mask = Mask()
        protected set

    abstract fun duplicate(): Layer

    /** Everything about the layer, for undo and saving. */
    abstract fun memento(): LayerMemento

    /** Immutable copy of the current settings for the render thread. */
    abstract fun state(): LayerState

    protected fun copyCommonTo(copy: Layer) {
        copy.name = "$name Kopie"
        copy.visible = visible
        copy.opacity = opacity
        copy.blend = blend
        copy.mask = mask.copy()
    }
}

/** A layer whose settings are built from [Param]s: an effect or a generator. */
interface ParamLayer {
    val params: List<Param>
    val values: MutableMap<String, Int>
    val texts: MutableMap<String, String>

    /** Whether the result depends on the seed, i.e. whether "Neu würfeln" makes sense. */
    val random: Boolean

    fun defaults(): Map<String, Int>
    fun textDefaults(): Map<String, String>
    fun reseed()
}

/** A destructive effect applied to the source layer below it. */
class EffectLayer(val effect: Effect, id: Int = newId()) : Layer(id), ParamLayer {
    override var name = effect.name
    override val values = effect.defaults()
    override val texts = effect.textDefaults()
    var seed = Random.nextLong()

    override val params get() = effect.params
    override val random get() = effect.random
    override fun defaults() = effect.defaults()
    override fun textDefaults() = effect.textDefaults()

    override fun reseed() {
        seed = Random.nextLong()
    }

    override fun duplicate() = EffectLayer(effect).also { copy ->
        copyCommonTo(copy)
        copy.values.putAll(values)
        copy.texts.putAll(texts)
        copy.seed = seed
    }

    override fun memento() = EffectMemento(id, name, visible, opacity, blend, mask.memento(), effect, values.toMap(), seed, texts.toMap())

    override fun state() = EffectState(id, visible, opacity, blend, mask.shape(), mask.version, effect, values.toMap(), seed, texts.toMap())
}

/** A layer that brings a picture: effects above it work on it, up to the next source layer. */
sealed class SourceLayer(id: Int) : Layer(id)

/**
 * A picture made from nothing by a [Generator] (noise, patterns …), always as large as
 * the canvas. Like an image layer, the effects above it work on it.
 */
class GeneratorLayer(val generator: Generator, id: Int = newId()) : SourceLayer(id), ParamLayer {
    override var name = generator.name
    override val values = generator.defaults()
    override val texts = generator.textDefaults()
    var seed = Random.nextLong()

    override val params get() = generator.params
    override val random get() = generator.random
    override fun defaults() = generator.defaults()
    override fun textDefaults() = generator.textDefaults()

    override fun reseed() {
        seed = Random.nextLong()
    }

    override fun duplicate() = GeneratorLayer(generator).also { copy ->
        copyCommonTo(copy)
        copy.values.putAll(values)
        copy.texts.putAll(texts)
        copy.seed = seed
    }

    override fun memento() = GeneratorMemento(id, name, visible, opacity, blend, mask.memento(), generator, values.toMap(), seed, texts.toMap())

    override fun state() = GeneratorState(id, visible, opacity, blend, mask.shape(), mask.version, generator, values.toMap(), seed, texts.toMap())
}

/**
 * A picture placed on the canvas: its top-left corner at ([x], [y]) canvas pixels,
 * drawn [scale] times its own size and turned by [rotation] degrees around its middle.
 */
class ImageLayer(val image: Pixels, id: Int = newId()) : SourceLayer(id) {
    override var name = "Bild"
    var x = 0.0
    var y = 0.0
    var scale = 1.0

    /** Degrees, clockwise, around the middle of the placed picture. */
    var rotation = 0.0

    /** Smooth (bilinear) scaling; off gives hard nearest-neighbour pixels. */
    var smooth = true

    /** Placed area in canvas pixels, before turning. */
    val bounds get() = Rectangle2D.Double(x, y, image.width * scale, image.height * scale)

    /** Picture pixels → canvas pixels. */
    fun toCanvas() = placement(image.width, image.height, x, y, scale, rotation)

    /** Scales the picture to fit into (or, with [cover], to cover) a canvas and centers it. */
    fun fitInto(width: Int, height: Int, cover: Boolean = false, onlyShrink: Boolean = false) {
        val sx = width.toDouble() / image.width
        val sy = height.toDouble() / image.height
        var s = if (cover) max(sx, sy) else min(sx, sy)
        if (onlyShrink) s = min(s, 1.0)
        scale = s
        center(width, height)
    }

    fun center(width: Int, height: Int) {
        x = (width - image.width * scale) / 2
        y = (height - image.height * scale) / 2
    }

    override fun duplicate() = ImageLayer(image).also { copy ->
        copyCommonTo(copy)
        copy.x = x
        copy.y = y
        copy.scale = scale
        copy.rotation = rotation
        copy.smooth = smooth
    }

    override fun memento() = ImageMemento(id, name, visible, opacity, blend, mask.memento(), image, x, y, scale, smooth, rotation)

    override fun state() = ImageState(id, visible, opacity, blend, mask.shape(), mask.version, image, x, y, scale, smooth, rotation)

    /** The picture drawn onto a transparent canvas of the given size. */
    fun placed(width: Int, height: Int) = Renderer.place(state(), width, height)
}

/** Saved layer, for undo and project files. Pixels and painted masks are compared by identity. */
sealed interface LayerMemento {
    val id: Int
    val name: String
    val visible: Boolean
    val opacity: Int
    val blend: BlendMode
    val mask: MaskMemento

    /** A new layer object with these settings and the same id, so the renderer can reuse its cache. */
    fun toLayer(): Layer
}

data class EffectMemento(
    override val id: Int,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Int,
    override val blend: BlendMode,
    override val mask: MaskMemento,
    val effect: Effect,
    val values: Map<String, Int>,
    val seed: Long,
    val texts: Map<String, String> = emptyMap(),
) : LayerMemento {
    override fun toLayer() = EffectLayer(effect, id).also { l ->
        l.name = name
        l.visible = visible
        l.opacity = opacity
        l.blend = blend
        l.values.putAll(values)
        l.texts.putAll(texts)
        l.seed = seed
        l.mask.restore(mask)
    }
}

data class GeneratorMemento(
    override val id: Int,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Int,
    override val blend: BlendMode,
    override val mask: MaskMemento,
    val generator: Generator,
    val values: Map<String, Int>,
    val seed: Long,
    val texts: Map<String, String> = emptyMap(),
) : LayerMemento {
    override fun toLayer() = GeneratorLayer(generator, id).also { l ->
        l.name = name
        l.visible = visible
        l.opacity = opacity
        l.blend = blend
        l.values.putAll(values)
        l.texts.putAll(texts)
        l.seed = seed
        l.mask.restore(mask)
    }
}

data class ImageMemento(
    override val id: Int,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Int,
    override val blend: BlendMode,
    override val mask: MaskMemento,
    val image: Pixels,
    val x: Double,
    val y: Double,
    val scale: Double,
    val smooth: Boolean,
    val rotation: Double = 0.0,
) : LayerMemento {
    override fun toLayer() = ImageLayer(image, id).also { l ->
        l.name = name
        l.visible = visible
        l.opacity = opacity
        l.blend = blend
        l.x = x
        l.y = y
        l.scale = scale
        l.rotation = rotation
        l.smooth = smooth
        l.mask.restore(mask)
    }
}

/** What the renderer needs of a layer. */
sealed interface LayerState {
    val id: Int
    val visible: Boolean
    val opacity: Int
    val blend: BlendMode
    val mask: MaskShape
    val maskVersion: Long
}

data class EffectState(
    override val id: Int,
    override val visible: Boolean,
    override val opacity: Int,
    override val blend: BlendMode,
    override val mask: MaskShape,
    override val maskVersion: Long,
    val effect: Effect,
    val values: Map<String, Int>,
    val seed: Long,
    val texts: Map<String, String> = emptyMap(),
) : LayerState

/** What the renderer needs of a source layer. */
sealed interface SourceState : LayerState

data class GeneratorState(
    override val id: Int,
    override val visible: Boolean,
    override val opacity: Int,
    override val blend: BlendMode,
    override val mask: MaskShape,
    override val maskVersion: Long,
    val generator: Generator,
    val values: Map<String, Int>,
    val seed: Long,
    val texts: Map<String, String> = emptyMap(),
) : SourceState

data class ImageState(
    override val id: Int,
    override val visible: Boolean,
    override val opacity: Int,
    override val blend: BlendMode,
    override val mask: MaskShape,
    override val maskVersion: Long,
    val image: Pixels,
    val x: Double,
    val y: Double,
    val scale: Double,
    val smooth: Boolean,
    val rotation: Double = 0.0,
) : SourceState

/**
 * Picture pixels → canvas pixels for a [width]×[height] picture placed at ([x], [y]),
 * [scale] times its size, turned by [rotation] degrees around the middle of its placed area.
 */
fun placement(width: Int, height: Int, x: Double, y: Double, scale: Double, rotation: Double) =
    java.awt.geom.AffineTransform().apply {
        val w = width * scale
        val h = height * scale
        translate(x + w / 2, y + h / 2)
        rotate(Math.toRadians(rotation))
        translate(-w / 2, -h / 2)
        scale(scale, scale)
    }

/** The layers that belong to the source layer at [index]: itself and the effects above it. */
fun groupRange(layers: List<Layer>, index: Int): IntRange {
    var start = index
    while (start > 0 && layers[start] !is SourceLayer) start--
    var end = start
    while (end + 1 < layers.size && layers[end + 1] !is SourceLayer) end++
    return start..end
}
