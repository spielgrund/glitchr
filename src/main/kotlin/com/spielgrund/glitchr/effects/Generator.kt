package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels

/**
 * Makes a picture from nothing, for a generator layer: one of the pattern or noise
 * effects run on a canvas filled with the base color ("Grundfarbe"). Settings that only
 * make sense with a picture below are fixed by [overrides] and left out of [params];
 * where an effect still asks for the picture (its colors, "Originalbild" as background),
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
        listOfNotNull(Param.Color(BASE, "Grundfarbe", defaultBase, "Fläche, auf der das Muster entsteht").takeIf { withBase }) +
            (effect?.params?.filter { it.key !in hidden } ?: emptyList()) + positionParams

    /** Whether "Neu würfeln" makes sense. */
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
            Param.Slider(POS_X, "Verschieben X", -50000, 50000, 0, " px", decimals = 1),
            Param.Slider(POS_Y, "Verschieben Y", -50000, 50000, 0, " px", decimals = 1),
            Param.Slider(POS_SCALE, "Skalierung", 10, 10000, 1000, " %", decimals = 1, tip = "Um die Mitte der Leinwand"),
            Param.Slider(POS_ROTATION, "Drehung", -1800, 1800, 0, "°", decimals = 1, tip = "Um die Mitte der Leinwand"),
            Param.Choice(POS_EDGE, "Rand", Transform.edgeOptions, 0, "Was ausserhalb des erzeugten Bilds hereinkommt"),
        )
    }
}

/** All generators, in menu order. */
object Generators {
    val all: List<Generator> = listOf(
        Generator(
            "color", "Farbfläche", "Eine einfarbige Fläche – Grundlage für Effekte", null,
            defaultBase = 0x808080,
        ),
        Generator(
            "gradient", "Gradient", "Farbverlauf: linear, gespiegelt, radial, Winkel, Raute oder Quadrat; mit Wiederholungen und Stufen",
            Gradient, withBase = false,
        ),
        Generator(
            "moire", "Moiré", "Zwei Linienmuster (Linien, Ringe, Strahlen, Spirale, Gitter, Punkte …) übereinander, die Moiré-Muster bilden",
            Moire, withBase = false,
        ),
        Generator(
            "mandala", "Mandala", "Ringe aus symmetrisch wiederholten Motiven – „Neu würfeln“ gibt ein neues Mandala",
            Mandala, withBase = false,
        ),
        Generator(
            "spirograph", "Spirograph", "Wie die Schablone: ein Zahnrad rollt in einem Ring, der Stift im Loch zeichnet Spiralmuster",
            Spirograph, withBase = false,
        ),
        Generator(
            "noise", "Noise", "Gerichteter Noise, schwarzweiss oder in zwei Farben: Perlin, Fraktal, Ridged, Worley, Wert, Weiss, Voronoi", NoiseField,
            overrides = mapOf(
                "type" to 1, "scaleStart" to 40, "scaleEnd" to 160, "contrastStart" to 220, "contrastEnd" to 220,
                // black and white to start with; "Zwei Farben", so other colors are one click away
                "colorMode" to 1, "color1" to 0x000000, "color2" to 0xFFFFFF, "mix" to 0, "control" to 3, "imageShape" to 0,
            ),
            hidden = setOf("imageShape", "control", "noiseInfluence"),
            defaultBase = 0x000000,
        ),
        Generator(
            "pattern", "Farbmuster", "Geometrische Muster als Farbverlauf: Streifen, Schachbrett, Sechsecke, Ringe, Truchet …",
            ColorPattern,
            overrides = mapOf("mapping" to 4),
            hidden = setOf("colors"),
        ),
        Generator(
            "geometric", "Geometrisch", "Op-Art-Muster aus Bändern und Linien, schwarzweiss oder als Verlauf", Geometric,
            hidden = setOf("colors"),
        ),
        Generator(
            "generative", "Generativ", "Generative Linienmuster: Bänder, Fliesslinien, Moiré, Spiegelkacheln", Generative,
            overrides = mapOf("field" to 0, "colorMode" to 2),
            hidden = setOf("field", "darkDense"),
        ),
        Generator(
            "grid", "Raster", "Gleichmässige Rastermodule: Punkte, Kreise, Strahlen, Schraffur, Streifen, Moiré-Gitter", GridModules,
            overrides = mapOf("influence" to 0),
            hidden = setOf("influence", "invert", "colorMode"),
        ),
        Generator(
            "ridgelines", "Spektroskop", "Gestapelte Noise-Linien wie ein Spektrogramm – oder das Cover von „Unknown Pleasures“", Ridgelines,
            overrides = mapOf("source" to 2, "focus" to 70),
            hidden = setOf("source"),
            defaultBase = 0x000000,
        ),
        Generator(
            "chars", "Zeichen", "Text oder Zeichen als Muster über die ganze Fläche", Characters,
            overrides = mapOf("set" to Characters.OWN_TEXT, "colorMode" to 1),
            hidden = setOf("invert", "scaleByBrightness"),
            defaultBase = 0x000000,
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
