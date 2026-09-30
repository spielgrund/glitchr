package com.spielgrund.glitchr.model

import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class MaskMode(val label: String) {
    OFF("Keine"), BRUSH("Pinsel / Auswahl"), LINEAR("Linearer Verlauf"), RADIAL("Radialer Verlauf");

    val isGradient get() = this == LINEAR || this == RADIAL

    override fun toString() = label
}

/** Source of mask versions; global, so a version number never means two different masks. */
private val versions = AtomicLong()

/** A point in image coordinates relative to the image size (0..1), so it survives a change of image. */
data class RelPoint(val x: Double, val y: Double)

/**
 * Where a layer's effect shows: 1 = full effect, 0 = the image below.
 * Either painted with a brush, or a linear or radial gradient between two points.
 * The painted mask is kept when switching to a gradient and back.
 */
class Mask {
    /** Increases with every change; the renderer uses it to see whether it must recomposite. */
    var version = versions.incrementAndGet()
        private set

    /** Changes only when the painted pixels change; lets [memento] reuse its last copy. */
    private var paintedVersion = 0L
    private var paintedCopy: ByteArray? = null
    private var paintedCopyVersion = -1L

    var mode = MaskMode.OFF
        set(value) { field = value; touch() }

    var invert = false
        set(value) { field = value; touch() }

    /** Hard edge: every mask value becomes 0 or 1, split at [threshold] (0..100 %). */
    var hardEdge = false
        set(value) { field = value; touch() }
    var threshold = 50
        set(value) { field = value; touch() }

    /** Linear: full effect at the start point, none at the end point. */
    var linearStart = RelPoint(0.5, 0.0)
        set(value) { field = value; touch() }
    var linearEnd = RelPoint(0.5, 1.0)
        set(value) { field = value; touch() }

    /** Radial: full effect in the center, none from the edge point's distance on. */
    var radialCenter = RelPoint(0.5, 0.5)
        set(value) { field = value; touch() }
    var radialEdge = RelPoint(0.5, 0.05)
        set(value) { field = value; touch() }

    /** Painted mask values 0..255, row by row; null until the brush is first used. */
    var painted: ByteArray? = null
        private set
    var paintedWidth = 0
        private set
    var paintedHeight = 0
        private set

    /** The two gradient handles of the current mode (start/end or center/edge). */
    var handleA: RelPoint
        get() = if (mode == MaskMode.RADIAL) radialCenter else linearStart
        set(value) {
            if (mode == MaskMode.RADIAL) radialCenter = value else linearStart = value
        }
    var handleB: RelPoint
        get() = if (mode == MaskMode.RADIAL) radialEdge else linearEnd
        set(value) {
            if (mode == MaskMode.RADIAL) radialEdge = value else linearEnd = value
        }

    fun resetGradient() {
        linearStart = RelPoint(0.5, 0.0)
        linearEnd = RelPoint(0.5, 1.0)
        radialCenter = RelPoint(0.5, 0.5)
        radialEdge = RelPoint(0.5, 0.05)
    }

    /** Makes sure a painted mask of the image size exists; a new one is empty (no effect). */
    /**
     * Makes sure a painted mask of [width]×[height] exists. A new one is empty (no effect);
     * one of another size (e.g. from an older project) is resampled, not lost.
     */
    fun ensurePainted(width: Int, height: Int) {
        val old = painted
        if (old != null && paintedWidth == width && paintedHeight == height) return
        val new = ByteArray(width * height)
        if (old != null && paintedWidth > 0 && paintedHeight > 0) {
            for (y in 0 until height) {
                val sy = (y * paintedHeight / height).coerceIn(0, paintedHeight - 1)
                for (x in 0 until width) new[y * width + x] = old[sy * paintedWidth + (x * paintedWidth / width).coerceIn(0, paintedWidth - 1)]
            }
        }
        painted = new
        paintedWidth = width
        paintedHeight = height
        paintChanged()
    }

    /** Sets the whole painted mask to [value] (0..255). */
    fun fill(value: Int) {
        painted?.fill(value.toByte())
        paintChanged()
    }

    /**
     * Paints one round brush dab at ([cx], [cy]) in image pixels. [hardness] 0..1 is the
     * part of the radius at full strength, [strength] 0..1 how far a dab moves the mask
     * towards white (or black when [erase]). Returns the changed pixel area.
     */
    fun dab(cx: Double, cy: Double, radius: Double, hardness: Float, strength: Float, erase: Boolean): Rectangle? {
        val data = painted ?: return null
        val w = paintedWidth
        val h = paintedHeight
        val x0 = max(0, floor(cx - radius).toInt())
        val y0 = max(0, floor(cy - radius).toInt())
        val x1 = min(w - 1, ceil(cx + radius).toInt())
        val y1 = min(h - 1, ceil(cy + radius).toInt())
        if (x0 > x1 || y0 > y1) return null
        val target = if (erase) 0 else 255
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / radius
                if (d >= 1.0) continue
                val falloff = if (d <= hardness) 1f else smooth(((1.0 - d) / (1.0 - hardness)).toFloat())
                val i = y * w + x
                val old = data[i].toInt() and 0xFF
                val new = old + ((target - old) * falloff * strength).let { if (it > 0) ceil(it) else floor(it) }.toInt()
                data[i] = new.coerceIn(0, 255).toByte()
            }
        }
        paintChanged()
        return Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /**
     * Adds a selection to the painted mask (keeps the larger value), or with [subtract]
     * removes it (keeps at most the inverse). Returns the changed area.
     */
    fun applyPatch(patch: MaskPatch, subtract: Boolean): Rectangle? {
        val data = painted ?: return null
        val r = patch.rect.intersection(Rectangle(0, 0, paintedWidth, paintedHeight))
        if (r.isEmpty) return null
        for (y in r.y until r.y + r.height) {
            for (x in r.x until r.x + r.width) {
                val v = patch.data[(y - patch.rect.y) * patch.rect.width + x - patch.rect.x].toInt() and 0xFF
                if (v == 0) continue
                val i = y * paintedWidth + x
                val old = data[i].toInt() and 0xFF
                data[i] = (if (subtract) min(old, 255 - v) else max(old, v)).toByte()
            }
        }
        paintChanged()
        return r
    }

    /** Replaces the painted mask with the brightness of [image]: light parts get the effect. */
    fun fromBrightness(image: com.spielgrund.glitchr.image.Pixels) {
        ensurePainted(image.width, image.height)
        val data = painted!!
        for (i in data.indices) data[i] = com.spielgrund.glitchr.image.luma(image.data[i]).toByte()
        paintChanged()
    }

    private fun touch() {
        version = versions.incrementAndGet()
    }

    private fun paintChanged() {
        paintedVersion++
        touch()
    }

    /** Immutable copy of all settings for undo; the painted pixels are copied only when they changed. */
    fun memento(): MaskMemento {
        val p = painted
        if (p != null && paintedCopyVersion != paintedVersion) {
            paintedCopy = p.copyOf()
            paintedCopyVersion = paintedVersion
        }
        return MaskMemento(
            mode, invert, linearStart, linearEnd, radialCenter, radialEdge,
            if (p == null) null else paintedCopy, paintedWidth, paintedHeight, hardEdge, threshold,
        )
    }

    fun restore(m: MaskMemento) {
        mode = m.mode
        invert = m.invert
        linearStart = m.linearStart
        linearEnd = m.linearEnd
        radialCenter = m.radialCenter
        radialEdge = m.radialEdge
        hardEdge = m.hardEdge
        threshold = m.threshold
        painted = m.painted?.copyOf()
        paintedWidth = m.paintedWidth
        paintedHeight = m.paintedHeight
        paintChanged()
        // the memento's array equals the restored pixels, so the next memento can share it
        paintedCopy = m.painted
        paintedCopyVersion = paintedVersion
    }

    fun copy() = Mask().also { m ->
        m.mode = mode
        m.invert = invert
        m.linearStart = linearStart
        m.linearEnd = linearEnd
        m.radialCenter = radialCenter
        m.radialEdge = radialEdge
        m.hardEdge = hardEdge
        m.threshold = threshold
        m.painted = painted?.copyOf()
        m.paintedWidth = paintedWidth
        m.paintedHeight = paintedHeight
    }

    /**
     * The current shape for rendering. The painted array is shared, not copied: brush
     * strokes during a render only mean the frame shows part of the newest stroke.
     */
    fun shape() = MaskShape(
        mode, invert, handleA, handleB,
        painted.takeIf { mode == MaskMode.BRUSH }, paintedWidth, paintedHeight,
        if (hardEdge) threshold / 100f else null,
    )

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
}

/** Saved mask state. [painted] is never modified, so comparing it by identity is enough. */
data class MaskMemento(
    val mode: MaskMode,
    val invert: Boolean,
    val linearStart: RelPoint,
    val linearEnd: RelPoint,
    val radialCenter: RelPoint,
    val radialEdge: RelPoint,
    val painted: ByteArray?,
    val paintedWidth: Int,
    val paintedHeight: Int,
    val hardEdge: Boolean = false,
    val threshold: Int = 50,
)

/** A piece of mask (0..255) covering [rect] of the image, e.g. a selection. */
class MaskPatch(val rect: Rectangle, val data: ByteArray)

/**
 * Where a mask lies on the canvas: its painted pixels and gradient points span this
 * rectangle (canvas pixels), turned by [rotation] (radians) around its center. For the
 * layers of an image layer it is the placed picture, so the mask moves, scales and turns
 * with it; outside the rectangle the mask continues with its edge values (gradients
 * simply go on).
 */
data class MaskSpace(val x: Double, val y: Double, val width: Double, val height: Double, val rotation: Double = 0.0) {
    private val cos = kotlin.math.cos(rotation)
    private val sin = kotlin.math.sin(rotation)
    private val centerX = x + width / 2
    private val centerY = y + height / 2

    /** Canvas point ([px], [py]) in the rectangle's own coordinates (0..width, 0..height before turning), into [out]. */
    fun toLocal(px: Double, py: Double, out: DoubleArray) {
        if (rotation == 0.0) {
            out[0] = px - x
            out[1] = py - y
            return
        }
        val dx = px - centerX
        val dy = py - centerY
        out[0] = dx * cos + dy * sin + width / 2
        out[1] = -dx * sin + dy * cos + height / 2
    }

    /** A point in the rectangle's own coordinates on the canvas. */
    fun toCanvas(lx: Double, ly: Double): java.awt.geom.Point2D.Double {
        val dx = lx - width / 2
        val dy = ly - height / 2
        return java.awt.geom.Point2D.Double(centerX + dx * cos - dy * sin, centerY + dx * sin + dy * cos)
    }

    /** Canvas → the rectangle's own coordinates, as a transform (for shapes). */
    fun canvasToLocal() = java.awt.geom.AffineTransform().apply {
        translate(width / 2, height / 2)
        rotate(-rotation)
        translate(-centerX, -centerY)
    }

    companion object {
        fun canvas(width: Int, height: Int) = MaskSpace(0.0, 0.0, width.toDouble(), height.toDouble())
    }
}

/** Read-only view of a [Mask] that computes the mask value of every pixel. */
class MaskShape(
    val mode: MaskMode,
    val invert: Boolean,
    private val a: RelPoint,
    private val b: RelPoint,
    private val painted: ByteArray?,
    private val paintedWidth: Int,
    private val paintedHeight: Int,
    /** When set, values become 1 from this threshold on and 0 below (before inverting). */
    private val threshold: Float? = null,
) {
    val isOff get() = mode == MaskMode.OFF

    /**
     * Fills [out] with the mask values 0..1 of row [y] of a [width]×[height] canvas,
     * the mask lying at [space] (the whole canvas unless given).
     */
    fun row(y: Int, width: Int, height: Int, out: FloatArray, space: MaskSpace = MaskSpace.canvas(width, height)) {
        val sw = space.width
        val sh = space.height
        // every pixel's position in the mask's own (possibly turned) rectangle
        val local = DoubleArray(2)
        when (mode) {
            MaskMode.OFF -> out.fill(1f, 0, width)
            MaskMode.BRUSH -> {
                if (painted == null || paintedWidth == 0 || sw <= 0 || sh <= 0) out.fill(0f, 0, width)
                else {
                    val fx = paintedWidth / sw
                    val fy = paintedHeight / sh
                    for (x in 0 until width) {
                        space.toLocal(x + 0.5, y + 0.5, local)
                        val px = floor(local[0] * fx).toInt().coerceIn(0, paintedWidth - 1)
                        val py = floor(local[1] * fy).toInt().coerceIn(0, paintedHeight - 1)
                        out[x] = (painted[py * paintedWidth + px].toInt() and 0xFF) / 255f
                    }
                }
            }
            MaskMode.LINEAR -> {
                val ax = a.x * sw
                val ay = a.y * sh
                val dx = b.x * sw - ax
                val dy = b.y * sh - ay
                val len2 = dx * dx + dy * dy
                for (x in 0 until width) {
                    space.toLocal(x + 0.5, y + 0.5, local)
                    val t = if (len2 < 1e-9) 0.0 else ((local[0] - ax) * dx + (local[1] - ay) * dy) / len2
                    out[x] = (1.0 - t.coerceIn(0.0, 1.0)).toFloat()
                }
            }
            MaskMode.RADIAL -> {
                val cx = a.x * sw
                val cy = a.y * sh
                val r = hypot(b.x * sw - cx, b.y * sh - cy)
                for (x in 0 until width) {
                    space.toLocal(x + 0.5, y + 0.5, local)
                    val t = if (r < 1e-9) 1.0 else hypot(local[0] - cx, local[1] - cy) / r
                    out[x] = (1.0 - t.coerceIn(0.0, 1.0)).toFloat()
                }
            }
        }
        threshold?.let { t ->
            // a threshold of 0 would make empty areas count; start just above zero instead
            val limit = if (t <= 0f) 1e-4f else t
            for (x in 0 until width) out[x] = if (out[x] >= limit) 1f else 0f
        }
        if (invert) for (x in 0 until width) out[x] = 1f - out[x]
    }
}
