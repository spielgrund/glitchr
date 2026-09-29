package com.spielgrund.glitchr.model

import com.spielgrund.glitchr.effects.Effect
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
 * An entry of the layer stack: either a picture ([ImageLayer]) or an effect
 * ([EffectLayer]). Effects work on the nearest image layer below them; that image
 * with its effects is then laid over everything further down.
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

/** A destructive effect applied to the image layer below it. */
class EffectLayer(val effect: Effect, id: Int = newId()) : Layer(id) {
    override var name = effect.name
    val values = effect.defaults()
    val texts = effect.textDefaults()
    var seed = Random.nextLong()

    fun reseed() {
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

/**
 * A picture placed on the canvas: its top-left corner at ([x], [y]) canvas pixels,
 * drawn [scale] times its own size.
 */
class ImageLayer(val image: Pixels, id: Int = newId()) : Layer(id) {
    override var name = "Bild"
    var x = 0.0
    var y = 0.0
    var scale = 1.0

    /** Smooth (bilinear) scaling; off gives hard nearest-neighbour pixels. */
    var smooth = true

    /** Placed area in canvas pixels. */
    val bounds get() = Rectangle2D.Double(x, y, image.width * scale, image.height * scale)

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
        copy.smooth = smooth
    }

    override fun memento() = ImageMemento(id, name, visible, opacity, blend, mask.memento(), image, x, y, scale, smooth)

    override fun state() = ImageState(id, visible, opacity, blend, mask.shape(), mask.version, image, x, y, scale, smooth)

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
) : LayerMemento {
    override fun toLayer() = ImageLayer(image, id).also { l ->
        l.name = name
        l.visible = visible
        l.opacity = opacity
        l.blend = blend
        l.x = x
        l.y = y
        l.scale = scale
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
) : LayerState

/** The layers that belong to the image layer at [index]: itself and the effects above it. */
fun groupRange(layers: List<Layer>, index: Int): IntRange {
    var start = index
    while (start > 0 && layers[start] !is ImageLayer) start--
    var end = start
    while (end + 1 < layers.size && layers[end + 1] !is ImageLayer) end++
    return start..end
}
