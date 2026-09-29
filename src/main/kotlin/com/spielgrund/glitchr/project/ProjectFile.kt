package com.spielgrund.glitchr.project

import com.google.gson.GsonBuilder
import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.BlendMode
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectMemento
import com.spielgrund.glitchr.model.ImageMemento
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.LayerMemento
import com.spielgrund.glitchr.model.MaskMemento
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * `.glitchr` files are ZIP archives: `project.json` with all layer settings, every picture
 * of an image layer as PNG under `images/` (stored once, even when several layers share
 * it) and every painted mask as a grayscale PNG under `masks/`, so a project stays
 * complete when the original pictures are moved or deleted.
 *
 * Version 1 files had a single source picture (`source.png`) and only effect layers;
 * they load with that picture as the bottom image layer.
 */
object ProjectFile {
    const val EXTENSION = "glitchr"
    private const val JSON_ENTRY = "project.json"
    private const val V1_SOURCE_ENTRY = "source.png"
    private const val FORMAT_VERSION = 2
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun isProject(file: File) = file.extension.equals(EXTENSION, ignoreCase = true)

    fun save(state: DocState, file: File) {
        val images = IdentityHashMap<Pixels, String>()
        val masks = mutableMapOf<String, MaskMemento>()
        val dto = ProjectDto(
            version = FORMAT_VERSION,
            sourceName = state.name,
            width = state.width,
            height = state.height,
            selected = state.layers.indexOfFirst { it.id == state.selectedId }.takeIf { it >= 0 },
            layers = state.layers.mapIndexed { i, layer ->
                val maskPath = layer.mask.painted?.let { "masks/$i.png".also { path -> masks[path] = layer.mask } }
                val imagePath = (layer as? ImageMemento)?.let { images.getOrPut(it.image) { "images/${images.size}.png" } }
                layer.toDto(maskPath, imagePath)
            },
        )
        // Write to a temporary file first so a failed save never destroys the previous version.
        val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(JSON_ENTRY))
                zip.write(gson.toJson(dto).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((image, path) in images) {
                    zip.putNextEntry(ZipEntry(path))
                    ImageIO.write(image.toImage(), "png", zip)
                    zip.closeEntry()
                }
                for ((path, mask) in masks) {
                    zip.putNextEntry(ZipEntry(path))
                    ImageIO.write(maskImage(mask), "png", zip)
                    zip.closeEntry()
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    fun load(file: File): DocState {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        val json = entries[JSON_ENTRY] ?: error("Keine GlitchR-Projektdatei")
        val dto = gson.fromJson(json.toString(Charsets.UTF_8), ProjectDto::class.java)
        if (dto.format != "glitchr") error("Keine GlitchR-Projektdatei")
        if (dto.version > FORMAT_VERSION) error("Datei stammt von einer neueren GlitchR-Version")

        val images = mutableMapOf<String, Pixels>()
        fun image(path: String): Pixels = images.getOrPut(path) {
            val bytes = entries[path] ?: error("Bild $path fehlt in der Datei")
            Pixels.of(ImageIO.read(bytes.inputStream()) ?: error("Bild $path ist beschädigt"))
        }

        // version 1: one source picture below all layers
        val v1Source = if (dto.version < 2) image(V1_SOURCE_ENTRY) else null
        val width = v1Source?.width ?: dto.width
        val height = v1Source?.height ?: dto.height
        if (width <= 0 || height <= 0) error("Die Leinwandgrösse fehlt in der Datei")

        val layers = mutableListOf<LayerMemento>()
        if (v1Source != null) layers += baseLayer(v1Source)
        for (layerDto in dto.layers) {
            val painted = layerDto.mask.painted?.let { path ->
                readMask(entries[path] ?: error("Maske $path fehlt in der Datei"))
            }
            layers += layerDto.toMemento(painted, ::image)
        }
        val offset = if (v1Source != null) 1 else 0
        return DocState(width, height, dto.sourceName, layers).apply {
            selectedId = dto.selected?.let { layers.getOrNull(it + offset)?.id } ?: layers.lastOrNull()?.id
        }
    }

    private fun baseLayer(image: Pixels) = ImageMemento(
        Layer.newId(), "Bild", true, 100, BlendMode.NORMAL, emptyMask(), image, 0.0, 0.0, 1.0, true,
    )

    private fun maskImage(mask: MaskMemento): BufferedImage {
        val img = BufferedImage(mask.paintedWidth, mask.paintedHeight, BufferedImage.TYPE_BYTE_GRAY)
        val data = (img.raster.dataBuffer as DataBufferByte).data
        mask.painted!!.copyInto(data)
        return img
    }

    /** A painted mask in its own resolution (the picture's, or the canvas' for older files); null if unreadable. */
    private fun readMask(bytes: ByteArray): PaintedMask? {
        val img = ImageIO.read(bytes.inputStream()) ?: return null
        val gray = if (img.type == BufferedImage.TYPE_BYTE_GRAY) img
        else BufferedImage(img.width, img.height, BufferedImage.TYPE_BYTE_GRAY).also {
            it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        }
        return PaintedMask((gray.raster.dataBuffer as DataBufferByte).data.copyOf(), img.width, img.height)
    }
}

private class PaintedMask(val data: ByteArray, val width: Int, val height: Int)

// Plain data mirrors of the model. All fields have defaults, so Gson can create them and
// files written by older versions (with fewer fields) still load. Enums are stored by name.

private data class ProjectDto(
    val format: String = "glitchr",
    val version: Int = 1,
    val sourceName: String = "bild",
    val width: Int = 0,
    val height: Int = 0,
    val selected: Int? = null,
    val layers: List<LayerDto> = emptyList(),
)

private data class LayerDto(
    /** "effect" or "image"; version 1 only had effects. */
    val type: String = "effect",
    val effect: String = "",
    val name: String? = null,
    val visible: Boolean = true,
    val opacity: Int = 100,
    val blend: String = BlendMode.NORMAL.name,
    val values: Map<String, Int> = emptyMap(),
    val texts: Map<String, String> = emptyMap(),
    val seed: Long = 0,
    val image: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val scale: Double = 1.0,
    val smooth: Boolean = true,
    val mask: MaskDto = MaskDto(),
)

private data class MaskDto(
    val mode: String = MaskMode.OFF.name,
    val invert: Boolean = false,
    val linearStart: List<Double> = listOf(0.5, 0.0),
    val linearEnd: List<Double> = listOf(0.5, 1.0),
    val radialCenter: List<Double> = listOf(0.5, 0.5),
    val radialEdge: List<Double> = listOf(0.5, 0.05),
    val painted: String? = null,
    val hardEdge: Boolean = false,
    val threshold: Int = 50,
)

private fun LayerMemento.toDto(maskPath: String?, imagePath: String?): LayerDto {
    val maskDto = MaskDto(
        mode = mask.mode.name,
        invert = mask.invert,
        linearStart = mask.linearStart.toList(),
        linearEnd = mask.linearEnd.toList(),
        radialCenter = mask.radialCenter.toList(),
        radialEdge = mask.radialEdge.toList(),
        painted = maskPath,
        hardEdge = mask.hardEdge,
        threshold = mask.threshold,
    )
    return when (this) {
        is EffectMemento -> LayerDto(
            type = "effect", effect = effect.id, name = name, visible = visible, opacity = opacity,
            blend = blend.name, values = values, texts = texts, seed = seed, mask = maskDto,
        )
        is ImageMemento -> LayerDto(
            type = "image", name = name, visible = visible, opacity = opacity, blend = blend.name,
            image = imagePath, x = x, y = y, scale = scale, smooth = smooth, mask = maskDto,
        )
    }
}

private fun LayerDto.toMemento(painted: PaintedMask?, image: (String) -> Pixels): LayerMemento {
    val maskMemento = MaskMemento(
        mode = enumOr(mask.mode, MaskMode.OFF),
        invert = mask.invert,
        linearStart = mask.linearStart.toRel(0.5, 0.0),
        linearEnd = mask.linearEnd.toRel(0.5, 1.0),
        radialCenter = mask.radialCenter.toRel(0.5, 0.5),
        radialEdge = mask.radialEdge.toRel(0.5, 0.05),
        painted = painted?.data,
        paintedWidth = painted?.width ?: 0,
        paintedHeight = painted?.height ?: 0,
        hardEdge = mask.hardEdge,
        threshold = mask.threshold.coerceIn(0, 100),
    )
    if (type == "image") {
        return ImageMemento(
            id = Layer.newId(),
            name = name ?: "Bild",
            visible = visible,
            opacity = opacity.coerceIn(0, 100),
            blend = enumOr(blend, BlendMode.NORMAL),
            mask = maskMemento,
            image = image(this.image ?: error("Bildebene ohne Bild in der Datei")),
            x = x,
            y = y,
            scale = scale.takeIf { it > 0 } ?: 1.0,
            smooth = smooth,
        )
    }
    val effect = Effects.all.firstOrNull { it.id == effect }
        ?: error("Unbekannter Effekt „$effect“ – stammt die Datei von einer neueren GlitchR-Version?")
    return EffectMemento(
        id = Layer.newId(),
        name = name ?: effect.name,
        visible = visible,
        opacity = opacity.coerceIn(0, 100),
        blend = enumOr(blend, BlendMode.NORMAL),
        mask = maskMemento,
        effect = effect,
        values = checkedValues(effect, values),
        seed = seed,
        texts = effect.textDefaults().apply { putAll(texts.filterKeys { it in keys }) },
    )
}

private fun emptyMask() = MaskMemento(
    MaskMode.OFF, false, RelPoint(0.5, 0.0), RelPoint(0.5, 1.0), RelPoint(0.5, 0.5), RelPoint(0.5, 0.05), null, 0, 0,
)

/** The effect's defaults, overridden by the stored values that still exist and are in range. */
private fun checkedValues(effect: Effect, stored: Map<String, Int>): Map<String, Int> {
    val values = effect.defaults()
    for (p in effect.params) {
        val v = stored[p.key] ?: continue
        values[p.key] = when (p) {
            // canvas-sized lengths may exceed the default range on big canvases
            is Param.Slider -> if (p.canvasMax) v.coerceAtLeast(p.min) else v.coerceIn(p.min, p.max)
            is Param.Choice -> v.coerceIn(0, p.options.size - 1)
            is Param.Toggle -> if (v != 0) 1 else 0
            is Param.Color -> v and 0xFFFFFF
            is Param.Text -> 0
        }
    }
    return values
}

private fun RelPoint.toList() = listOf(x, y)

private fun List<Double>.toRel(defaultX: Double, defaultY: Double) =
    RelPoint(getOrNull(0) ?: defaultX, getOrNull(1) ?: defaultY)

private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
