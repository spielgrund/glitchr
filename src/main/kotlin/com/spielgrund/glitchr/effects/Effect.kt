package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels

/** A setting of an effect. All values are stored as Int; the editor is built from these definitions. */
sealed class Param(val key: String, val label: String, val default: Int, val tip: String?) {
    /**
     * [max] is the upper end of the slider; with [canvasMax] the editor uses the larger
     * side of the canvas instead (for lengths that should be able to span the whole picture).
     * With [decimals], the stored whole number is shown with that many decimal places
     * (decimals = 1: 125 is 12.5).
     */
    class Slider(
        key: String, label: String, val min: Int, val max: Int, default: Int,
        val unit: String = "", tip: String? = null, val canvasMax: Boolean = false, val decimals: Int = 0,
    ) : Param(key, label, default, tip) {
        /** Upper end of the slider on a [width]×[height] canvas. */
        fun maxFor(width: Int, height: Int) = if (canvasMax) kotlin.math.max(min + 1, kotlin.math.max(width, height)) else max
    }

    class Choice(key: String, label: String, val options: List<String>, default: Int = 0, tip: String? = null) :
        Param(key, label, default, tip)

    class Toggle(key: String, label: String, default: Boolean, tip: String? = null) :
        Param(key, label, if (default) 1 else 0, tip)

    /** A line of text; stored separately from the numbers (see [Values.text]). */
    open class Text(key: String, label: String, val defaultText: String, tip: String? = null) : Param(key, label, 0, tip)

    /** A color ramp (see [ColorRamp]); stored as text, edited in a gradient editor. */
    class Ramp(key: String, label: String, defaultText: String, tip: String? = null) : Text(key, label, defaultText, tip)

    /** Tone curves (see [Curves]); stored as text, edited in a curve editor. */
    class Curve(key: String, label: String, tip: String? = null) : Text(key, label, "", tip)

    /** Strokes drawn on the canvas (see [FlowStrokes]); stored as text, edited by dragging in the picture. */
    class Flow(key: String, label: String, tip: String? = null) : Text(key, label, "", tip)

    /** A heading that starts a new group of settings in the editor; stores nothing meaningful. */
    class Heading(key: String, label: String) : Param(key, label, 0, null)

    /** An RGB color, stored as 0xRRGGBB. */
    class Color(key: String, label: String, default: Int, tip: String? = null) :
        Param(key, label, default and 0xFFFFFF, tip)
}

/** Read access to the settings of one layer: numbers, and texts for [Param.Text]. */
class Values(private val map: Map<String, Int>, private val texts: Map<String, String> = emptyMap()) {
    operator fun get(key: String): Int = map[key] ?: error("Unbekannter Parameter $key")
    fun bool(key: String) = get(key) != 0
    fun text(key: String): String = texts[key] ?: ""
}

/**
 * A destructive image effect. [apply] never modifies its input and returns new pixels
 * of the same size. The same input, values and seed always give the same result.
 */
abstract class Effect(val id: String, val name: String, val description: String) {
    abstract val params: List<Param>

    /** Whether [apply] uses the seed, i.e. whether "Neu würfeln" makes sense. */
    open val random = true

    abstract fun apply(src: Pixels, v: Values, seed: Long): Pixels

    fun defaults(): MutableMap<String, Int> = params.associateTo(LinkedHashMap()) { it.key to it.default }

    fun textDefaults(): MutableMap<String, String> =
        params.filterIsInstance<Param.Text>().associateTo(LinkedHashMap()) { it.key to it.defaultText }

    /** Default settings as [Values], e.g. for tests. */
    fun defaultValues(changes: Map<String, Int> = emptyMap(), texts: Map<String, String> = emptyMap()) =
        Values(defaults().apply { putAll(changes) }, textDefaults().apply { putAll(texts) })

    override fun toString() = name
}

/** All available effects, in menu order. */
object Effects {
    val all: List<Effect> = listOf(
        PixelSort, PixelBleed, PixelStretch, JpegArtifacts, Datamosh, RgbDistort, SliceShift, SlitScan, Offset, Transform, Blur, Sharpen, BlockGlitch, Bitcrush, ColorCorrect, Ramp, LabColor,
        Particles, Displace, Flow, Erosion, ErosionFast, Grow, NoiseField, ColorPattern, Geometric, MoireFilter, Kaleidoscope, Feedback, Generative, GridModules, Characters, Ridgelines, Lens, Hologram, Television,
    )

    fun byId(id: String) = all.first { it.id == id }
}

/** Channel choice shared by several effects. */
internal val channelOptions = listOf("Alle Kanäle", "Rot", "Grün", "Blau", "Zufälliger Kanal")

/**
 * Writes the [bits] (see [channelBits]) of [moved] into [target]. All channels copy the
 * whole pixel including transparency; single channels make the pixel at least as opaque
 * as the moved one, so shifted channels also show outside the picture.
 */
internal fun mergeChannels(target: Int, moved: Int, bits: Int): Int {
    if (bits == 0x00FFFFFF) return moved
    val alpha = kotlin.math.max(target ushr 24, moved ushr 24)
    return (alpha shl 24) or (((target and bits.inv()) or (moved and bits)) and 0x00FFFFFF)
}

/** Bit mask of the RGB bits to write for a channel choice (see [channelOptions]). */
internal fun channelBits(choice: Int, random: kotlin.random.Random): Int = when (if (choice == 4) 1 + random.nextInt(3) else choice) {
    1 -> 0x00FF0000
    2 -> 0x0000FF00
    3 -> 0x000000FF
    else -> 0x00FFFFFF
}
