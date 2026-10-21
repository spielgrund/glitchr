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
    /** Without: the generator places its content itself (e.g. the 3D model); the picture stays where it is. */
    withPosition: Boolean = true,
) {
    val params: List<Param> =
        listOfNotNull(Param.Color(BASE, "Base color", defaultBase, "Area the pattern is created on").takeIf { withBase }) +
            (effect?.params?.filter { it.key !in hidden } ?: emptyList()) + (if (withPosition) positionParams else emptyList())

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
    fun generate(width: Int, height: Int, v: Values, seed: Long): Pixels = place(make(width, height, v, seed), v)

    /** The picture as the effect makes it, before its position. */
    fun make(width: Int, height: Int, v: Values, seed: Long): Pixels {
        val base = Pixels(width, height).apply { data.fill(v[BASE] or 0xFF000000.toInt()) }
        return effect?.apply(base, v, seed) ?: base
    }

    /** [made] moved, scaled and turned by the position in [v]. */
    fun place(made: Pixels, v: Values): Pixels {
        val scale = v[POS_SCALE] / 1000.0
        return transformPixels(
            made, offsetX = v[POS_X] / 10.0, offsetY = v[POS_Y] / 10.0, scaleX = scale, scaleY = scale,
            rotation = v[POS_ROTATION] / 100.0, edge = Transform.edgeOf(v[POS_EDGE]),
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
        const val POS_BLUR = "posBlur"
        const val POS_SHUTTER = "posShutter"
        const val POS_SAMPLES = "posSamples"

        /** The settings of the position that motion blur follows. */
        val MOVING = setOf(POS_X, POS_Y, POS_SCALE, POS_ROTATION)

        /** Position of the made picture, for every generator. */
        private val positionParams = listOf(
            Param.Heading("posHeading", "Position"),
            Param.Slider(POS_X, "Shift X", -50000, 50000, 0, " px", decimals = 1, transform = true),
            Param.Slider(POS_Y, "Shift Y", -50000, 50000, 0, " px", decimals = 1, transform = true),
            Param.Slider(POS_SCALE, "Scale", 10, 10000, 1000, " %", decimals = 1, tip = "Around the middle of the canvas", transform = true),
            Param.Slider(POS_ROTATION, "Rotation", -18000, 18000, 0, "°", decimals = 2, tip = "Around the middle of the canvas"),
            Param.Choice(POS_EDGE, "Edge", Transform.edgeOptions, 0, "What comes in from outside the generated picture"),
            Param.Toggle(POS_BLUR, "Motion blur", false, "Smears the picture along its animated movement, scaling and turning, like a camera with an open shutter"),
            Param.Slider(POS_SHUTTER, "Shutter angle", 0, 720, 180, "", "How long the shutter is open: 360 = one whole frame, 180 = half (film look), 720 = two frames"),
            Param.Slider(POS_SAMPLES, "Samples", 2, 64, 12, tip = "Copies blended per frame: more = smoother smear, slower"),
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
            "shape", "Shape", "A geometric shape: line, triangle, polygon, star, circle or ring; filled with a color or a gradient, with outline",
            Shape, defaultBase = 0x000000,
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
                "type" to 1, "scaleStart" to 4000, "scaleEnd" to 16000, "contrastStart" to 220, "contrastEnd" to 220,
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
            "grid", "Grid modules", "Even grid modules: dots, circles, rays, hatching, stripes, moiré grid", GridModules,
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
            "trendshapes", "Trend shapes", "Swooshes, rings, halftone dots, shine stars, blobs and speed lines like early-2000s flyers", TrendShapes,
        ),
        Generator(
            "trendwhore", "Trendwhore 3D", "Glossy early-2000s 3D abstracts – shard bursts, crystal spikes, ribbon twists, rushing slabs, liquid chrome, chrome blades, wireframes – turnable in space", Trendwhore,
        ),
        Generator(
            "chromeshapes", "Chrome shapes", "Y2K chrome symbols – flowers, stars, swirls, planets, hearts … – in polished chrome, turnable, anti-aliased", ChromeSymbols,
            defaultBase = 0x000000,
        ),
        Generator(
            "noisesphere", "Noise sphere", "A sphere of points and fine filaments shooting out by 3D noise – from a calm grainy ball to an exploding, hairy burst; animate energy and time", NoiseSphere,
            defaultBase = 0x05070C,
        ),
        Generator(
            "model3d", "3D model", "A 3D model – pasted from Blender as OBJ (up to 1024 faces) or a built-in shape – turned in space, as plastic, chrome, toon, textured or wireframe", Model3D,
            defaultBase = 0x15131C, withPosition = false,
        ),
        Generator(
            "lightning", "Lightning", "Lightning bolts from one or more points, in a direction or along drawn arrows – forking and glowing", Lightning,
            defaultBase = 0x05060F,
        ),
        Generator(
            "errors", "Error cascade", "Error messages – Windows 98, XP or Mac OS 9 – bouncing away like the cards when you've won Solitaire, leaving trails; animate the progress", ErrorCascade,
            defaultBase = 0x008080,
        ),
        Generator(
            "tribal", "Tribal", "Tribal ornaments – bold 90s neo-tribal, thorny cyber sigilism, Polynesian spirals or flames; generated or drawn with arrows", Tribal,
        ),
        Generator(
            "flowers", "Flowers", "Roses, tulips, daisies, sunflowers, cherry blossoms, lotus and poppies – alone, scattered or as a bouquet", Flowers,
            defaultBase = 0xF3EEE6,
        ),
        Generator(
            "chaostype", "Chaos type", "Chaotic grunge typography in the manner of David Carson, your text or made-up words", CarsonType,
            defaultBase = 0xE9E4DA,
        ),
        Generator(
            "blackhole", "Black hole", "A black hole with a glowing disk among stars – Interstellar, Luminet 1978, EHT 2019 and 2024",
            BlackHole,
            overrides = mapOf("stars" to 35),
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
