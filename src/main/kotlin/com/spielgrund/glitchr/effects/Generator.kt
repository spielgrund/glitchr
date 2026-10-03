package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels

/**
 * Makes a picture from nothing, for a generator layer: one of the pattern or noise
 * effects run on a canvas filled with the base color ("Base color"). Settings that only
 * make sense with a picture below are fixed by [overrides] and left out of [params];
 * where an effect still asks for the picture (its colors, "Original picture" as background),
 * it gets the base color.
 *
 * [effect] null gives a plain color area. Without [withBase] the effect covers the
 * canvas itself and has no base color setting.
 *
 * Every generator also has a position: the made picture can be moved, scaled and turned
 * around the canvas center; what comes in from beyond its edge is transparent, repeated,
 * mirrored or the stretched edge. The layer's masks move with it.
 */
class Generator(
    val id: String,
    val name: String,
    val description: String,
    val effect: Effect?,
    private val overrides: Map<String, Int> = emptyMap(),
    private val textOverrides: Map<String, String> = emptyMap(),
    hidden: Set<String> = emptySet(),
    private val defaultBase: Int = 0xFFFFFF,
    withBase: Boolean = true,
) {
    val params: List<Param> =
        listOfNotNull(Param.Color(BASE, "Base color", defaultBase, "Area the pattern is created on").takeIf { withBase }) +
            (effect?.params?.filter { it.key !in hidden } ?: emptyList()) + positionParams

    /** Whether "Reroll" makes sense. */
    val random get() = effect?.random ?: false

    /** All values, including the fixed ones of hidden settings. */
    fun defaults(): MutableMap<String, Int> =
        (effect?.defaults() ?: LinkedHashMap()).apply {
            putAll(overrides)
            put(BASE, defaultBase and 0xFFFFFF)
            for (p in positionParams) put(p.key, p.default)
        }

    fun textDefaults(): MutableMap<String, String> =
        (effect?.textDefaults() ?: LinkedHashMap()).apply { putAll(textOverrides) }

    /** The picture in canvas size. The same values and seed always give the same result. */
    fun generate(width: Int, height: Int, v: Values, seed: Long): Pixels {
        val base = Pixels(width, height).apply { data.fill(v[BASE] or 0xFF000000.toInt()) }
        val made = effect?.apply(base, v, seed) ?: base
        val scale = v[POS_SCALE] / 1000.0
        return transformPixels(
            made, offsetX = v[POS_X] / 10.0, offsetY = v[POS_Y] / 10.0, scaleX = scale, scaleY = scale,
            rotation = v[POS_ROTATION] / 10.0, edge = Transform.edgeOf(v[POS_EDGE]),
        )
    }

    /** Default settings as [Values], e.g. for tests. */
    fun defaultValues(changes: Map<String, Int> = emptyMap()) =
        Values(defaults().apply { putAll(changes) }, textDefaults())

    override fun toString() = name

    companion object {
        const val BASE = "base"
        const val POS_X = "posX"
        const val POS_Y = "posY"
        const val POS_SCALE = "posScale"
        const val POS_ROTATION = "posRotation"
        const val POS_EDGE = "posEdge"

        /** Position of the made picture, for every generator. */
        private val positionParams = listOf(
            Param.Heading("posHeading", "Position"),
            Param.Slider(POS_X, "Shift X", -50000, 50000, 0, " px", decimals = 1),
            Param.Slider(POS_Y, "Shift Y", -50000, 50000, 0, " px", decimals = 1),
            Param.Slider(POS_SCALE, "Scale", 10, 10000, 1000, " %", decimals = 1, tip = "Around the middle of the canvas"),
            Param.Slider(POS_ROTATION, "Rotation", -1800, 1800, 0, "°", decimals = 1, tip = "Around the middle of the canvas"),
            Param.Choice(POS_EDGE, "Edge", Transform.edgeOptions, 0, "What comes in from outside the generated picture"),
        )
    }
}

/** All generators, in menu order. */
object Generators {
    val all: List<Generator> = listOf(
        Generator(
            "color", "Color fill", "A single-color area – a base for effects", null,
            defaultBase = 0x808080,
        ),
        Generator(
            "gradient", "Gradient", "Color gradient: linear, mirrored, radial, angle, diamond or square; with repeats and steps",
            Gradient, withBase = false,
        ),
        Generator(
            "moire", "Moiré", "Two line patterns (lines, rings, rays, spiral, grid, dots …) over each other that form moiré patterns",
            Moire, withBase = false,
        ),
        Generator(
            "mandala", "Mandala", "Rings of symmetrically repeated motifs – “Reroll” gives a new mandala",
            Mandala, withBase = false,
        ),
        Generator(
            "spirograph", "Spirograph", "Like the stencil: a gear rolls inside a ring, the pen in the hole draws spiral patterns",
            Spirograph, withBase = false,
        ),
        Generator(
            "noise", "Noise", "Directional noise, black and white or in two colors: Perlin, fractal, ridged, Worley, value, white, Voronoi", NoiseField,
            overrides = mapOf(
                "type" to 1, "scaleStart" to 40, "scaleEnd" to 160, "contrastStart" to 220, "contrastEnd" to 220,
                // black and white to start with; "Two colors", so other colors are one click away
                "colorMode" to 1, "color1" to 0x000000, "color2" to 0xFFFFFF, "mix" to 0, "control" to 3, "imageShape" to 0,
            ),
            hidden = setOf("imageShape", "control", "noiseInfluence"),
            defaultBase = 0x000000,
        ),
        Generator(
            "pattern", "Color pattern", "Geometric patterns as a color gradient: stripes, checkerboard, hexagons, rings, Truchet …",
            ColorPattern,
            overrides = mapOf("mapping" to 4),
            hidden = setOf("colors"),
        ),
        Generator(
            "geometric", "Geometric", "Op-art patterns of bands and lines, black and white or as a gradient", Geometric,
            hidden = setOf("colors"),
        ),
        Generator(
            "generative", "Generative", "Generative line patterns: bands, flow lines, moiré, mirror tiles", Generative,
            overrides = mapOf("field" to 0, "colorMode" to 2),
            hidden = setOf("field", "darkDense"),
        ),
        Generator(
            "grid", "Grid", "Even grid modules: dots, circles, rays, hatching, stripes, moiré grid", GridModules,
            overrides = mapOf("influence" to 0),
            hidden = setOf("influence", "invert", "colorMode"),
        ),
        Generator(
            "ridgelines", "Spectroscope", "Stacked noise lines like a spectrogram – or the cover of “Unknown Pleasures”", Ridgelines,
            overrides = mapOf("source" to 2, "focus" to 70),
            hidden = setOf("source"),
            defaultBase = 0x000000,
        ),
        Generator(
            "chars", "Characters", "Text or characters as a pattern across the whole area", Characters,
            overrides = mapOf("set" to Characters.OWN_TEXT, "colorMode" to 1),
            hidden = setOf("invert", "scaleByBrightness"),
            defaultBase = 0x000000,
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
