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
import kotlin.math.roundToInt
import kotlin.random.Random

/** How a layer is combined with what lies below it. */
enum class BlendMode(val label: String, private val f: (Int, Int) -> Int) {
    NORMAL("Normal", { _, t -> t }),
    LIGHTEN("Lighten", { b, t -> max(b, t) }),
    DARKEN("Darken", { b, t -> min(b, t) }),
    SCREEN("Screen", { b, t -> 255 - (255 - b) * (255 - t) / 255 }),
    MULTIPLY("Multiply", { b, t -> b * t / 255 }),
    ADD("Add", { b, t -> min(255, b + t) }),
    DIFFERENCE("Difference", { b, t -> abs(b - t) });

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
 * An adjustment layer (an [EffectLayer] with [EffectLayer.adjustment]) instead takes
 * everything below it as its picture, like an adjustment layer in Photoshop.
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

    /** Animated settings by key (see [property]); the fields always hold the value at the current frame. */
    val tracks = LinkedHashMap<String, Track>()

    /** New random value every frame (only for layers that use randomness). */
    open var seedPerFrame = false

    abstract fun duplicate(): Layer

    /** Everything about the layer, for undo and saving. */
    abstract fun memento(): LayerMemento

    /** Immutable copy of the current settings for the render thread. */
    abstract fun state(): LayerState

    /** The current settings with the tracks, to render any frame. */
    fun animated() = AnimatedLayer(state(), tracks.toMap(), seedPerFrame)

    /** The keys of the settings that can be animated, in editor order. */
    open fun animatableKeys(): List<String> = listOf(AnimKeys.OPACITY)

    /** Current value of an animatable setting; null if the layer has no such setting. */
    open fun property(key: String): Double? = if (key == AnimKeys.OPACITY) opacity.toDouble() else null

    /** Sets an animatable setting (rounded the way its field stores it). */
    open fun setProperty(key: String, value: Double) {
        if (key == AnimKeys.OPACITY) opacity = value.roundToInt().coerceIn(0, 100)
    }

    /** [value] the way [setProperty] would store it, to compare it with the field. */
    open fun quantize(key: String, value: Double): Double = value.roundToInt().toDouble()

    open fun kindOf(key: String) = ValueKind.NUMBER

    /** Name of a setting for the timeline. */
    open fun propertyLabel(key: String) = if (key == AnimKeys.OPACITY) "Opacity" else key

    /** The value of an animated setting that undo and saving see: the one at frame 0, so moving along the timeline is no edit. */
    protected fun canonical(key: String, current: Double) = tracks[key]?.let { quantize(key, it.valueAt(0)) } ?: current

    protected fun canonicalOpacity() = canonical(AnimKeys.OPACITY, opacity.toDouble()).roundToInt()

    protected fun copyCommonTo(copy: Layer) {
        copy.name = "$name copy"
        copy.visible = visible
        copy.opacity = opacity
        copy.blend = blend
        copy.mask = mask.copy()
        copy.tracks.putAll(tracks)
        copy.seedPerFrame = seedPerFrame
    }

    internal fun restoreCommon(m: LayerMemento) {
        tracks.putAll(m.tracks)
        seedPerFrame = m.seedPerFrame
    }
}

/** The animatable parameters of an effect or generator: sliders, choices, switches and colors. */
private fun paramAnimatable(p: Param) = p is Param.Slider || p is Param.Choice || p is Param.Toggle || p is Param.Color

private fun paramKind(params: List<Param>, key: String) = when (params.firstOrNull { it.key == key }) {
    is Param.Color -> ValueKind.COLOR
    is Param.Choice, is Param.Toggle -> ValueKind.STEP
    else -> ValueKind.NUMBER
}

/** "Heading › Label", so equally named settings under different headings can be told apart. */
private fun paramLabel(params: List<Param>, key: String): String {
    var heading: String? = null
    for (p in params) {
        if (p is Param.Heading) heading = p.label
        if (p.key == key) return if (heading != null) "$heading › ${p.label}" else p.label
    }
    return key
}

/** Values with each animated parameter at its frame-0 value. */
private fun Layer.canonicalValues(values: Map<String, Int>) =
    if (tracks.isEmpty()) values.toMap() else values.mapValues { (k, v) -> tracks[k]?.let { quantize(k, it.valueAt(0)).roundToInt() } ?: v }

/** A layer whose settings are built from [Param]s: an effect or a generator. */
interface ParamLayer {
    val params: List<Param>
    val values: MutableMap<String, Int>
    val texts: MutableMap<String, String>

    /** Whether the result depends on the seed, i.e. whether "Reroll" makes sense. */
    val random: Boolean

    fun defaults(): Map<String, Int>
    fun textDefaults(): Map<String, String>
    fun reseed()
}

private fun ParamLayer.paramKeys() = params.filter(::paramAnimatable).map { it.key }

private fun ParamLayer.paramProperty(key: String) = values[key]?.toDouble()

private fun ParamLayer.setParamProperty(key: String, value: Double) {
    values[key] = when (params.firstOrNull { it.key == key }) {
        is Param.Toggle -> if (value.roundToInt() != 0) 1 else 0
        is Param.Choice -> value.roundToInt().coerceIn(0, (params.first { it.key == key } as Param.Choice).options.size - 1)
        is Param.Color -> value.roundToInt() and 0xFFFFFF
        else -> value.roundToInt()
    }
}

/**
 * A destructive effect applied to the source layer below it – or, as an [adjustment]
 * layer, to everything below it: then it starts a group of its own like a source layer,
 * and effects above it refine its result.
 */
class EffectLayer(val effect: Effect, id: Int = newId()) : Layer(id), ParamLayer {
    override var name = effect.name
    var adjustment = false
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
        copy.adjustment = adjustment
    }

    override fun animatableKeys() = paramKeys() + AnimKeys.OPACITY
    override fun property(key: String) = paramProperty(key) ?: super.property(key)
    override fun setProperty(key: String, value: Double) = if (key in values) setParamProperty(key, value) else super.setProperty(key, value)
    override fun kindOf(key: String) = paramKind(params, key)
    override fun propertyLabel(key: String) = if (key in values) paramLabel(params, key) else super.propertyLabel(key)

    override fun memento() = EffectMemento(
        id, name, visible, canonicalOpacity(), blend, mask.memento(), effect, canonicalValues(values), seed, texts.toMap(), tracks.toMap(), seedPerFrame,
        adjustment,
    )

    override fun state() = EffectState(id, visible, opacity, blend, mask.shape(), mask.version, effect, values.toMap(), seed, texts.toMap(), adjustment)
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

    override fun animatableKeys() = paramKeys() + AnimKeys.OPACITY
    override fun property(key: String) = paramProperty(key) ?: super.property(key)
    override fun setProperty(key: String, value: Double) = if (key in values) setParamProperty(key, value) else super.setProperty(key, value)
    override fun kindOf(key: String) = paramKind(params, key)
    override fun propertyLabel(key: String) = if (key in values) paramLabel(params, key) else super.propertyLabel(key)

    override fun memento() = GeneratorMemento(
        id, name, visible, canonicalOpacity(), blend, mask.memento(), generator, canonicalValues(values), seed, texts.toMap(), tracks.toMap(), seedPerFrame,
    )

    override fun state() = GeneratorState(id, visible, opacity, blend, mask.shape(), mask.version, generator, values.toMap(), seed, texts.toMap())
}

/**
 * A picture placed on the canvas: its top-left corner at ([x], [y]) canvas pixels,
 * drawn [scale] times its own size and turned by [rotation] degrees around its middle.
 */
class ImageLayer(val image: Pixels, id: Int = newId()) : SourceLayer(id) {
    override var name = "Picture"
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

    override fun animatableKeys() = listOf(AnimKeys.X, AnimKeys.Y, AnimKeys.SCALE, AnimKeys.ROTATION, AnimKeys.OPACITY)

    override fun property(key: String) = when (key) {
        AnimKeys.X -> x
        AnimKeys.Y -> y
        AnimKeys.SCALE -> scale
        AnimKeys.ROTATION -> rotation
        else -> super.property(key)
    }

    override fun setProperty(key: String, value: Double) {
        when (key) {
            AnimKeys.X -> x = value
            AnimKeys.Y -> y = value
            AnimKeys.SCALE -> {
                // like the editor: around the middle, unless the position is animated itself
                val s = value.coerceAtLeast(0.001)
                if (AnimKeys.X !in tracks) x += (scale - s) * image.width / 2
                if (AnimKeys.Y !in tracks) y += (scale - s) * image.height / 2
                scale = s
            }
            AnimKeys.ROTATION -> rotation = value
            else -> super.setProperty(key, value)
        }
    }

    override fun quantize(key: String, value: Double) = if (key == AnimKeys.OPACITY) super.quantize(key, value) else value

    override fun propertyLabel(key: String) = when (key) {
        AnimKeys.X -> "X"
        AnimKeys.Y -> "Y"
        AnimKeys.SCALE -> "Scale"
        AnimKeys.ROTATION -> "Rotation"
        else -> super.propertyLabel(key)
    }

    override fun memento(): ImageMemento {
        // the placement at frame 0; an animated scale moves an unanimated position along (see setProperty)
        val s0 = canonical(AnimKeys.SCALE, scale)
        // rounded, so the small float errors of moving back and forth on the timeline are no edit
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        val x0 = tracks[AnimKeys.X]?.valueAt(0) ?: r(x + (scale - s0) * image.width / 2)
        val y0 = tracks[AnimKeys.Y]?.valueAt(0) ?: r(y + (scale - s0) * image.height / 2)
        return ImageMemento(
            id, name, visible, canonicalOpacity(), blend, mask.memento(), image, x0, y0, s0, smooth,
            canonical(AnimKeys.ROTATION, rotation), tracks.toMap(),
        )
    }

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
    val tracks: Map<String, Track>
    val seedPerFrame: Boolean

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
    override val tracks: Map<String, Track> = emptyMap(),
    override val seedPerFrame: Boolean = false,
    val adjustment: Boolean = false,
) : LayerMemento {
    override fun toLayer() = EffectLayer(effect, id).also { l ->
        l.adjustment = adjustment
        l.name = name
        l.visible = visible
        l.opacity = opacity
        l.blend = blend
        l.values.putAll(values)
        l.texts.putAll(texts)
        l.seed = seed
        l.mask.restore(mask)
        l.restoreCommon(this)
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
    override val tracks: Map<String, Track> = emptyMap(),
    override val seedPerFrame: Boolean = false,
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
        l.restoreCommon(this)
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
    override val tracks: Map<String, Track> = emptyMap(),
) : LayerMemento {
    override val seedPerFrame get() = false

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
        l.restoreCommon(this)
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
    /** Works on everything below instead of the source layer below. */
    val adjustment: Boolean = false,
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

/** Whether the layer begins a group: a source layer, or an adjustment layer working on everything below. */
val Layer.startsGroup get() = this is SourceLayer || (this is EffectLayer && adjustment)

/** Like [Layer.startsGroup], for the renderer. */
val LayerState.startsGroup get() = this is SourceState || (this is EffectState && adjustment)

/** The layers that belong to the group at [index]: its source or adjustment layer and the effects above it. */
fun groupRange(layers: List<Layer>, index: Int): IntRange {
    var start = index
    while (start > 0 && !layers[start].startsGroup) start--
    var end = start
    while (end + 1 < layers.size && !layers[end + 1].startsGroup) end++
    return start..end
}
