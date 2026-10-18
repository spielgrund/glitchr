package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.FlowStrokes
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.image.parallelRows
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.MaskPatch
import com.spielgrund.glitchr.model.MaskSpace
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Selection
import com.spielgrund.glitchr.image.Pixels
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Brush settings for painting masks; size in image pixels, the others in percent. */
/** Tools for the painted mask. */
enum class MaskTool(val label: String) {
    BRUSH("Brush"), RECT("Rectangle"), ELLIPSE("Ellipse"), LASSO("Lasso"), WAND("Magic wand");

    override fun toString() = label
}

/**
 * What the active layer's mask is edited against: the canvas rectangle it spans
 * ([space]), its resolution ([width]×[height], the picture's own size) and the
 * picture itself for the magic wand ([image], null for layers without picture).
 */
class MaskTarget(val space: MaskSpace, val width: Int, val height: Int, val image: Pixels?)

/** Settings of the mask tools; sizes in image pixels, the others in percent unless noted. */
class Brush {
    var tool = MaskTool.BRUSH
    var size = 120
    var hardness = 40
    var strength = 60

    /** Soft edge of selections in px. */
    var feather = 0

    /** Magic wand: allowed difference per channel (0..255), and whether only connected pixels count. */
    var tolerance = 32
    var contiguous = true

    /** Magic wand: grow along soft gradients (light falling across a surface), stopping at hard edges. */
    var gradients = true

    /** The last selection (magic wand or “Find”), while it can still be refined; null after any other mask edit. */
    var lastSelection: SelectionOp? = null
}

/**
 * The last selection made on a painted [mask], kept so it can be refined afterwards:
 * [compute] gives its raw values (0..1, [width] × [height]) for a tolerance; refining puts
 * back the painted pixels from [before] it and lays the refined values in again – replacing
 * ([mode] 0), added (1) or taken away (2).
 */
class SelectionOp(
    val mask: com.spielgrund.glitchr.model.Mask,
    private val before: ByteArray?,
    val width: Int,
    val height: Int,
    val mode: Int,
    /** Whether it came from the magic wand: then its tolerance can change. */
    val wand: Boolean,
    var tolerance: Int,
    /** The color clicked with the wand, to notice clicks on the same color again. */
    val seedColor: Int,
    private val compute: (Int) -> FloatArray,
) {
    /** −100..100: less or more selected. */
    var amount = 0

    /** Smoothing of the outline, in mask pixels. */
    var smooth = 0

    /** Soft edge, in mask pixels. */
    var feather = 0

    /** For a selection from “Find”: which kind, so its settings can recompute it while it is live. */
    var findKind: com.spielgrund.glitchr.effects.AutoSelect.Kind? = null

    private var raw: FloatArray? = null
    private var rawTolerance = -1

    /** New raw values, e.g. recomputed with other settings of “Find”. */
    fun setRaw(values: FloatArray) {
        raw = values
        rawTolerance = tolerance
    }

    fun apply() {
        if (raw == null || rawTolerance != tolerance) {
            raw = compute(tolerance)
            rawTolerance = tolerance
        }
        val values = refine(raw!!, width, height, amount, smooth, feather)
        mask.restorePainted(before, width, height)
        mask.fromValues(values, width, height, mode)
    }

    companion object {
        /**
         * [values] grown or shrunk ([amount] −100..100: blurred, then cut at a lower or higher
         * level), with a smoothed outline ([smooth]) and a soft edge ([feather]).
         */
        fun refine(values: FloatArray, w: Int, h: Int, amount: Int, smooth: Int, feather: Int): FloatArray {
            var out = values
            if (amount != 0 || smooth > 0) {
                // at ±100 % the outline moves by about a fortieth of the picture
                val reach = kotlin.math.max(2.0, kotlin.math.min(w, h) / 40.0)
                val sigma = kotlin.math.max(smooth.toDouble(), kotlin.math.abs(amount) / 100.0 * reach)
                val blurred = if (sigma > 0.3) com.spielgrund.glitchr.effects.Blur.gauss(out, w, h, sigma) else out
                val level = 0.5f - amount / 100f * 0.45f
                out = FloatArray(blurred.size) { ((blurred[it] - level) / 0.08f + 0.5f).coerceIn(0f, 1f) }
            }
            if (feather > 0) out = com.spielgrund.glitchr.effects.Blur.gauss(out, w, h, feather / 2.0)
            return out
        }
    }
}

/**
 * Shows the rendered image at a zoom level. Depending on the mask mode of the active
 * [layer], dragging paints the mask (right button or Alt erases) or moves the gradient
 * handles. Space + drag or the middle button pans. Meant to live in a JScrollPane.
 */
class GlitchCanvas(private val brush: Brush) : JComponent(), Scrollable {

    /** The image shown; same size as the source, so the zoom stays when it's replaced. */
    var image: BufferedImage? = null
        set(value) {
            val resized = value?.width != field?.width || value?.height != field?.height
            field = value
            if (resized) {
                revalidate()
                refreshOverlay()
            }
            repaint()
        }

    /** Layer whose mask is edited and shown. */
    var layer: Layer? = null
        set(value) {
            field = value
            refreshOverlay()
            updateCursor(null)
        }

    /** Tint the parts of the image where the active layer's effect doesn't show. */
    var showMask = false
        set(value) {
            field = value
            refreshOverlay()
            // for image layers this switches between editing the mask and moving the picture
            updateCursor(mouse)
        }

    /**
     * Whether dragging edits the active layer's mask. For image layers only while the
     * mask is shown; with the mask hidden, dragging moves and scales the picture.
     */
    private val editsMask get() = layer.let {
        it != null && it.mask.mode != MaskMode.OFF && (it !is ImageLayer && flowParam(it) == null && handleOf(it) == null || showMask)
    }

    /** The handle the active effect layer puts on the canvas, if any (see [CanvasHandle]). */
    private fun handleOf(l: Layer?) = (l as? EffectLayer)?.effect?.canvasHandle

    /** The handle that dragging moves, or null when dragging does something else. */
    private val activeHandle get() = if (editsMask || layer is ImageLayer) null else handleOf(layer)

    /** Called while the effect's handle is dragged; [finished] on mouse release. */
    var onHandleEdited: (finished: Boolean) -> Unit = {}

    /** The flow strokes of the active layer, if it has some to draw (see [Param.Flow]). */
    private fun flowParam(l: Layer?) = (l as? EffectLayer)?.effect?.params?.firstOrNull { it is Param.Flow }

    /** Key of the flow strokes that dragging draws, or null when dragging does something else. */
    private val flowKey get() = if (editsMask || layer is ImageLayer) null else flowParam(layer)?.key

    /** Where the active layer's mask lies and which picture it belongs to. */
    var maskTarget: () -> MaskTarget? = { null }

    /** Called while an image layer is moved or scaled on the canvas; [finished] on mouse release. */
    var onTransformEdited: (finished: Boolean) -> Unit = {}

    /** Show the drawn flow strokes as arrows; the stroke being drawn always shows. */
    var showFlow = true
        set(value) {
            field = value
            repaint()
        }

    /** Called while flow strokes are drawn or wiped; [finished] on mouse release. */
    var onFlowEdited: (finished: Boolean) -> Unit = {}

    /** Called after every mask edit on the canvas. */
    var onMaskEdited: () -> Unit = {}

    /** Called when the zoom changes. */
    var onZoom: () -> Unit = {}

    /**
     * Cropping: the part of the canvas to keep (canvas pixels), shown with handles to move
     * and resize it; null when not cropping. Enter or a double-click inside crops
     * ([onCropFinish] with true), Escape cancels.
     */
    var cropRect: Rectangle2D.Double? = null
        set(value) {
            field = value
            updateCursor(mouse)
        }
    var onCropFinish: (apply: Boolean) -> Unit = {}

    var zoom = 1.0
        private set

    private var overlay: BufferedImage? = null
    private var mouse: Point? = null
    private var spaceDown = false
    private var drag: Drag? = null

    /** Whether a brush stroke or handle drag is in progress. */
    val isEditing get() = drag != null && drag !is Pan

    init {
        isOpaque = true
        background = Color(0x1E1F22)
        isFocusable = true
        val handler = Mouse()
        addMouseListener(handler)
        addMouseMotionListener(handler)
        addMouseWheelListener(handler)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (cropRect != null && e.id == KeyEvent.KEY_PRESSED && (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_ESCAPE) &&
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner !is JTextComponent
            ) {
                onCropFinish(e.keyCode == KeyEvent.VK_ENTER)
                return@addKeyEventDispatcher true
            }
            if (e.keyCode == KeyEvent.VK_SPACE && KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner !is JTextComponent) {
                val down = e.id == KeyEvent.KEY_PRESSED
                if (down != spaceDown && (e.id == KeyEvent.KEY_PRESSED || e.id == KeyEvent.KEY_RELEASED)) {
                    spaceDown = down
                    updateCursor(mouse)
                }
            }
            false
        }
    }

    // ---------------------------------------------------------------- zoom

    /** Sets the zoom, keeping the image point under [anchor] (canvas coordinates) in place. */
    fun setZoom(newZoom: Double, anchor: Point? = null) {
        val z = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val viewport = parent as? JViewport
        if (viewport == null || image == null) {
            zoom = z
            revalidate(); repaint(); onZoom()
            return
        }
        val view = viewport.viewRect
        val a = anchor ?: Point(view.x + view.width / 2, view.y + view.height / 2)
        val imgX = (a.x - originX()) / zoom
        val imgY = (a.y - originY()) / zoom
        val offsetX = a.x - view.x
        val offsetY = a.y - view.y

        zoom = z
        invalidate()
        (viewport.parent ?: viewport).validate()
        val newX = (imgX * zoom + originX() - offsetX).roundToInt()
        val newY = (imgY * zoom + originY() - offsetY).roundToInt()
        val maxX = max(0, viewport.viewSize.width - viewport.extentSize.width)
        val maxY = max(0, viewport.viewSize.height - viewport.extentSize.height)
        viewport.viewPosition = Point(newX.coerceIn(0, maxX), newY.coerceIn(0, maxY))
        repaint()
        onZoom()
    }

    /** Zoom that fits the whole image into the visible area, never above 100 %. */
    fun fitZoom(): Double {
        val img = image ?: return 1.0
        val view = (parent as? JViewport)?.extentSize ?: return 1.0
        return min(1.0, min((view.width - 2.0 * MARGIN) / img.width, (view.height - 2.0 * MARGIN) / img.height))
            .coerceAtLeast(MIN_ZOOM)
    }

    fun zoomIn(anchor: Point? = null) = setZoom(nextZoom(zoom, up = true), anchor)
    fun zoomOut(anchor: Point? = null) = setZoom(nextZoom(zoom, up = false), anchor)

    // ---------------------------------------------------------------- mask overlay

    /** Recomputes the red mask tint, in [region] (image pixels) or everywhere. */
    fun refreshOverlay(region: Rectangle? = null) {
        val img = image
        val mask = layer?.mask
        if (!showMask || img == null || mask == null || mask.mode == MaskMode.OFF) {
            overlay = null
            repaint()
            return
        }
        val w = img.width
        val h = img.height
        var ov = overlay
        val full = ov == null || ov.width != w || ov.height != h || region == null
        if (ov == null || ov.width != w || ov.height != h) {
            ov = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            overlay = ov
        }
        val data = (ov.raster.dataBuffer as DataBufferInt).data
        val shape = mask.shape()
        val space = maskSpace()
        val r = if (full) Rectangle(0, 0, w, h) else region!!.intersection(Rectangle(0, 0, w, h))
        if (r.isEmpty) return
        parallelRows(r.height) { dy ->
            val y = r.y + dy
            val m = FloatArray(w)
            shape.row(y, w, h, m, space)
            // a light tint where the effect doesn't work, so the picture stays visible
            for (x in r.x until r.x + r.width) data[y * w + x] = (((1f - m[x]) * 95).toInt() shl 24) or 0xFF2828
        }
        repaint()
    }

    // ---------------------------------------------------------------- painting

    override fun getPreferredSize(): Dimension {
        val img = image ?: return Dimension(0, 0)
        return Dimension(ceil(img.width * zoom).toInt() + 2 * MARGIN, ceil(img.height * zoom).toInt() + 2 * MARGIN)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.color = background
        g2.fillRect(0, 0, width, height)
        val img = image
        if (img == null) {
            paintHint(g2)
            g2.dispose()
            return
        }
        val bounds = imageBounds()
        paintChecker(g2, bounds)
        g2.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            if (zoom >= 2.0) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g2.drawImage(img, bounds.x, bounds.y, bounds.width, bounds.height, null)
        overlay?.let { g2.drawImage(it, bounds.x, bounds.y, bounds.width, bounds.height, null) }

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val mode = layer?.mask?.mode
        (layer as? ImageLayer)?.let { paintLayerFrame(g2, it) }
        if (editsMask && mode != null && mode.isGradient) paintGradientHandles(g2)
        if (editsMask && mode == MaskMode.BRUSH && brush.tool == MaskTool.BRUSH && !spaceDown) mouse?.let { paintBrushCursor(g2, it) }
        flowKey?.let { paintFlow(g2, it) }
        activeHandle?.let { paintHandle(g2, it) }
        selectionOutline()?.let { paintSelectionOutline(g2, it) }
        cropRect?.let { paintCrop(g2, it) }
        g2.dispose()
    }

    /** The crop area: everything outside darkened, a frame with thirds, handles and its size. */
    private fun paintCrop(g: Graphics2D, r: Rectangle2D.Double) {
        val s = cropScreen(r)
        val outside = java.awt.geom.Area(imageBounds()).apply { subtract(java.awt.geom.Area(s)) }
        g.color = Color(0, 0, 0, 150)
        g.fill(outside)
        g.stroke = BasicStroke(1f)
        g.color = Color(255, 255, 255, 90)
        for (k in 1..2) {
            val x = s.x + s.width * k / 3
            val y = s.y + s.height * k / 3
            g.draw(Line2D.Double(x, s.y, x, s.y + s.height))
            g.draw(Line2D.Double(s.x, y, s.x + s.width, y))
        }
        g.color = Color.WHITE
        g.stroke = BasicStroke(1.5f)
        g.draw(s)
        for ((hx, hy) in cropHandles(s)) {
            val h = Rectangle2D.Double(hx - 4, hy - 4, 8.0, 8.0)
            g.color = Color.WHITE
            g.fill(h)
            g.color = Color(0x1E1F22)
            g.draw(h)
        }
        val label = "${r.width.roundToInt()} × ${r.height.roundToInt()} px   ·   Enter: crop   ·   Esc: cancel"
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val fm = g.fontMetrics
        val tx = s.x.toInt() + 4
        val ty = (s.y + s.height).toInt() + fm.ascent + 6
        g.color = Color(0, 0, 0, 170)
        g.fillRect(tx - 4, ty - fm.ascent - 2, fm.stringWidth(label) + 8, fm.height + 4)
        g.color = Color.WHITE
        g.drawString(label, tx, ty)
    }

    private fun cropScreen(r: Rectangle2D.Double) =
        Rectangle2D.Double(originX() + r.x * zoom, originY() + r.y * zoom, r.width * zoom, r.height * zoom)

    /** Corners and edge middles of the crop frame on screen. */
    private fun cropHandles(s: Rectangle2D.Double) = listOf(
        s.x to s.y, s.centerX to s.y, s.x + s.width to s.y, s.x + s.width to s.centerY,
        s.x + s.width to s.y + s.height, s.centerX to s.y + s.height, s.x to s.y + s.height, s.x to s.centerY,
    )

    /** Which edges of the crop frame are under screen point [p]: bits left 1, right 2, top 4, bottom 8; 16 inside, 0 outside. */
    private fun cropPart(p: Point): Int {
        val r = cropRect ?: return 0
        val s = cropScreen(r)
        val near = 7.0
        var edges = 0
        val inY = p.y >= s.y - near && p.y <= s.y + s.height + near
        val inX = p.x >= s.x - near && p.x <= s.x + s.width + near
        if (inY && kotlin.math.abs(p.x - s.x) <= near) edges = edges or 1
        if (inY && kotlin.math.abs(p.x - (s.x + s.width)) <= near) edges = edges or 2
        if (inX && kotlin.math.abs(p.y - s.y) <= near) edges = edges or 4
        if (inX && kotlin.math.abs(p.y - (s.y + s.height)) <= near) edges = edges or 8
        if (edges != 0) return edges
        return if (s.contains(p.x.toDouble(), p.y.toDouble())) 16 else 0
    }

    private fun paintHint(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x8C8F94)
        g.font = font.deriveFont(15f)
        val lines = listOf("Open an image (Ctrl+O), paste one (Ctrl+V)", "or drag it here")
        val fm = g.fontMetrics
        var y = height / 2 - fm.height * lines.size / 2 + fm.ascent
        for (line in lines) {
            g.drawString(line, (width - fm.stringWidth(line)) / 2, y)
            y += fm.height
        }
    }

    private fun paintChecker(graphics: Graphics2D, r: Rectangle) {
        val clip = r.intersection(graphics.clipBounds ?: r)
        if (clip.isEmpty) return
        val g = graphics.create() as Graphics2D
        g.clip(clip)
        g.color = Color(0xCCCCCC)
        g.fill(clip)
        g.color = Color(0x999999)
        val s = CHECKER
        var y = r.y + (clip.y - r.y) / s * s
        while (y < clip.maxY) {
            var x = r.x + (clip.x - r.x) / s * s
            while (x < clip.maxX) {
                if (((x - r.x) / s + (y - r.y) / s) % 2 == 1) g.fillRect(x, y, s, s)
                x += s
            }
            y += s
        }
        g.dispose()
    }

    private fun paintGradientHandles(g: Graphics2D) {
        val mask = layer!!.mask
        val a = toScreen(mask.handleA)
        val b = toScreen(mask.handleB)
        fun outlined(shape: java.awt.Shape, fill: Color? = null) {
            fill?.let { g.color = it; g.fill(shape) }
            g.stroke = BasicStroke(3f)
            g.color = Color(0, 0, 0, 160)
            g.draw(shape)
            g.stroke = BasicStroke(1.5f)
            g.color = Color.WHITE
            g.draw(shape)
        }
        if (mask.mode == MaskMode.RADIAL) {
            val r = a.distance(b)
            outlined(Ellipse2D.Double(a.x - r, a.y - r, 2 * r, 2 * r))
        }
        outlined(Line2D.Double(a, b))
        val s = HANDLE.toDouble()
        outlined(Ellipse2D.Double(a.x - s, a.y - s, 2 * s, 2 * s), Color.WHITE)
        outlined(Ellipse2D.Double(b.x - s, b.y - s, 2 * s, 2 * s), Color.BLACK)
    }

    /** The drawn flow strokes as arrows, and the stroke being drawn. */
    /** The effect's handle: a ring where the point lies, joined to the canvas middle. */
    private fun paintHandle(g: Graphics2D, h: com.spielgrund.glitchr.effects.CanvasHandle) {
        val img = image ?: return
        val l = layer as? EffectLayer ?: return
        val cx = originX() + img.width / 2.0 * zoom
        val cy = originY() + img.height / 2.0 * zoom
        val px = cx + (l.values[h.xKey] ?: 0) * zoom
        val py = cy + (l.values[h.yKey] ?: 0) * zoom
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for ((color, width) in listOf(Color(0, 0, 0, 160) to 3f, Color.WHITE to 1.5f)) {
            g.color = color
            g.stroke = BasicStroke(width)
            g.draw(java.awt.geom.Line2D.Double(cx, cy, px, py))
            g.draw(Ellipse2D.Double(px - HANDLE - 2, py - HANDLE - 2, 2.0 * (HANDLE + 2), 2.0 * (HANDLE + 2)))
            g.draw(Ellipse2D.Double(cx - 2.5, cy - 2.5, 5.0, 5.0))
        }
    }

    private fun paintFlow(g: Graphics2D, key: String) {
        val img = image ?: return
        val l = layer as? EffectLayer ?: return
        val drawn = if (showFlow) FlowStrokes.parse(l.texts[key] ?: "") else emptyList()
        val strokes = drawn.map { stroke ->
            stroke.map { Point2D.Double(originX() + it.x * img.width * zoom, originY() + it.y * img.height * zoom) }
        }.toMutableList()
        (drag as? FlowDraw)?.let { d ->
            if (d.points.size >= 2) strokes.add(d.points.map { Point2D.Double(originX() + it.x * zoom, originY() + it.y * zoom) })
        }
        for (stroke in strokes) {
            val path = Path2D.Double()
            path.moveTo(stroke[0].x, stroke[0].y)
            for (p in stroke.drop(1)) path.lineTo(p.x, p.y)
            // arrow heads along the stroke and at its end
            var travelled = 0.0
            for (i in 1 until stroke.size) {
                travelled += stroke[i].distance(stroke[i - 1])
                if (travelled >= 70 || i == stroke.size - 1) {
                    travelled = 0.0
                    val a = stroke[i]
                    val b = stroke[i - 1]
                    val len = a.distance(b).coerceAtLeast(1e-6)
                    val ux = (a.x - b.x) / len
                    val uy = (a.y - b.y) / len
                    path.moveTo(a.x - ux * 9 - uy * 5, a.y - uy * 9 + ux * 5)
                    path.lineTo(a.x, a.y)
                    path.lineTo(a.x - ux * 9 + uy * 5, a.y - uy * 9 - ux * 5)
                }
            }
            g.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Color(0, 0, 0, 150)
            g.draw(path)
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Color(0x7CE0FF)
            g.draw(path)
        }
        if (drag is FlowErase || (drag == null && eraseHeld)) mouse?.let { p ->
            val r = ERASE_RADIUS.toDouble()
            g.stroke = BasicStroke(1f)
            g.color = Color.WHITE
            g.draw(Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r))
        }
    }

    /** Whether Alt is held (erasing flow strokes). */
    private var eraseHeld = false

    /** Removes the flow strokes passing within [ERASE_RADIUS] screen pixels of [p]. */
    private fun eraseFlow(p: Point, key: String) {
        val img = image ?: return
        val l = layer as? EffectLayer ?: return
        val strokes = FlowStrokes.parse(l.texts[key] ?: "")
        val r = ERASE_RADIUS / zoom
        val i = toImage(p)
        val kept = strokes.filter { stroke ->
            stroke.zipWithNext().none { (a, b) ->
                Line2D.ptSegDist(a.x * img.width, a.y * img.height, b.x * img.width, b.y * img.height, i.x, i.y) <= r
            }
        }
        if (kept.size != strokes.size) {
            l.texts[key] = FlowStrokes.format(kept)
            onFlowEdited(false)
        }
    }

    /** Frame of the selected image layer (turned with it) with its four scale handles. */
    private fun paintLayerFrame(g: Graphics2D, layer: ImageLayer) {
        val corners = screenCorners(layer)
        val r = Path2D.Double().apply {
            moveTo(corners[0].x, corners[0].y)
            for (c in corners.drop(1)) lineTo(c.x, c.y)
            closePath()
        }
        g.stroke = BasicStroke(1f)
        g.color = Color(0, 0, 0, 160)
        g.draw(r)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
        g.color = Color(0x4DA3FF)
        g.draw(r)
        g.stroke = BasicStroke(1f)
        for (c in corners) {
            val box = Rectangle2D.Double(c.x - HANDLE, c.y - HANDLE, 2.0 * HANDLE, 2.0 * HANDLE)
            g.color = Color.WHITE
            g.fill(box)
            g.color = Color(0x4DA3FF)
            g.draw(box)
        }
    }

    /** The placed picture's corners on the canvas: top-left, top-right, bottom-right, bottom-left (before turning). */
    private fun canvasCorners(layer: ImageLayer): List<Point2D.Double> {
        val t = layer.toCanvas()
        val w = layer.image.width.toDouble()
        val h = layer.image.height.toDouble()
        return listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h).map { (x, y) ->
            Point2D.Double().also { t.transform(Point2D.Double(x, y), it) }
        }
    }

    private fun screenCorners(layer: ImageLayer) =
        canvasCorners(layer).map { Point2D.Double(originX() + it.x * zoom, originY() + it.y * zoom) }

    /** Index of the scale handle under [p], or null. */
    private fun cornerAt(layer: ImageLayer, p: Point): Int? =
        screenCorners(layer).indexOfFirst { hypot(it.x - p.x, it.y - p.y) <= HANDLE + 4 }.takeIf { it >= 0 }

    /** Whether dragging moves/scales the selected image layer instead of editing its mask. */
    private fun transforms(e: java.awt.event.InputEvent): ImageLayer? {
        val l = layer as? ImageLayer ?: return null
        return l.takeIf { !editsMask || e.isControlDown }
    }

    /** Outline of the selection being drawn, in screen coordinates. */
    private fun selectionOutline(): Shape? {
        val shape = when (val d = drag) {
            is ShapeDrag -> d.shape()
            is LassoDrag -> d.path
            else -> return null
        }
        return AffineTransform(zoom, 0.0, 0.0, zoom, originX(), originY()).createTransformedShape(shape)
    }

    private fun paintSelectionOutline(g: Graphics2D, outline: Shape) {
        g.stroke = BasicStroke(1f)
        g.color = Color.BLACK
        g.draw(outline)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f, 5f), 0f)
        g.color = Color.WHITE
        g.draw(outline)
    }

    private fun paintBrushCursor(g: Graphics2D, p: Point) {
        val r = brush.size / 2.0 * zoom
        val inner = r * brush.hardness / 100.0
        g.stroke = BasicStroke(1f)
        for ((radius, color) in listOf(r to Color.WHITE, inner to Color(255, 255, 255, 110))) {
            if (radius < 1) continue
            val circle = Ellipse2D.Double(p.x - radius, p.y - radius, 2 * radius, 2 * radius)
            g.color = Color(0, 0, 0, 140)
            g.draw(Ellipse2D.Double(p.x - radius - 1, p.y - radius - 1, 2 * radius + 2, 2 * radius + 2))
            g.color = color
            g.draw(circle)
        }
    }

    // ---------------------------------------------------------------- coordinates

    private fun originX() = max(MARGIN.toDouble(), (width - (image?.width ?: 0) * zoom) / 2) + panX
    private fun originY() = max(MARGIN.toDouble(), (height - (image?.height ?: 0) * zoom) / 2) + panY

    /** How far the picture was moved freely with the middle mouse button or space, beyond scrolling (screen pixels). */
    private var panX = 0.0
    private var panY = 0.0

    /** Shows the whole picture, centred again. */
    fun fit() {
        panX = 0.0
        panY = 0.0
        setZoom(fitZoom())
    }

    private fun imageBounds(): Rectangle {
        val img = image ?: return Rectangle()
        return Rectangle(originX().toInt(), originY().toInt(), ceil(img.width * zoom).toInt(), ceil(img.height * zoom).toInt())
    }

    private fun toImage(p: Point) = Point2D.Double((p.x - originX()) / zoom, (p.y - originY()) / zoom)

    /** The canvas rectangle the active mask spans (the whole canvas for layers without picture). */
    private fun maskSpace(): MaskSpace = maskTarget()?.space ?: image!!.let { MaskSpace.canvas(it.width, it.height) }

    /** A gradient point (relative to the mask's rectangle, which may be turned) on screen. */
    private fun toScreen(r: RelPoint): Point2D.Double {
        val s = maskSpace()
        val c = s.toCanvas(r.x * s.width, r.y * s.height)
        return Point2D.Double(originX() + c.x * zoom, originY() + c.y * zoom)
    }

    private fun toRel(p: Point): RelPoint {
        val s = maskSpace()
        val i = toImage(p)
        val local = DoubleArray(2)
        s.toLocal(i.x, i.y, local)
        return RelPoint(local[0] / s.width, local[1] / s.height)
    }

    /** Canvas pixels → pixels of the painted mask (through the picture's turn). */
    private fun toMask(p: Point2D.Double, t: MaskTarget): Point2D.Double {
        val local = DoubleArray(2)
        t.space.toLocal(p.x, p.y, local)
        return Point2D.Double(local[0] * t.width / t.space.width, local[1] * t.height / t.space.height)
    }

    /** Painted-mask pixels → the canvas rectangle they cover (for redrawing the overlay). */
    private fun toCanvas(r: Rectangle, t: MaskTarget): Rectangle {
        val sx = t.space.width / t.width
        val sy = t.space.height / t.height
        val corners = listOf(r.x to r.y, r.x + r.width to r.y, r.x to r.y + r.height, r.x + r.width to r.y + r.height)
            .map { (x, y) -> t.space.toCanvas(x * sx, y * sy) }
        val x0 = floor(corners.minOf { it.x }).toInt()
        val y0 = floor(corners.minOf { it.y }).toInt()
        val x1 = ceil(corners.maxOf { it.x }).toInt()
        val y1 = ceil(corners.maxOf { it.y }).toInt()
        return Rectangle(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2)
    }

    // ---------------------------------------------------------------- mouse

    private sealed interface Drag
    /** Panning: [last] is the previous mouse position on screen. */
    private class Pan(var last: Point) : Drag

    /** Changing the crop frame: [part] as in [cropPart] (0 = a new frame from [start]), from the frame [from]. */
    private class CropDrag(val part: Int, val start: Point2D.Double, val from: Rectangle2D.Double) : Drag
    private class Paint(var last: Point2D.Double, val erase: Boolean) : Drag
    private class MoveHandle(val first: Boolean) : Drag

    /** Rectangle or ellipse selection from [start] to [end] (image pixels). */
    private class ShapeDrag(val start: Point2D.Double, var end: Point2D.Double, val ellipse: Boolean, val subtract: Boolean) : Drag {
        fun shape(): Shape {
            val x = min(start.x, end.x)
            val y = min(start.y, end.y)
            val w = kotlin.math.abs(end.x - start.x)
            val h = kotlin.math.abs(end.y - start.y)
            return if (ellipse) Ellipse2D.Double(x, y, w, h) else Rectangle2D.Double(x, y, w, h)
        }
    }

    private class LassoDrag(val path: Path2D.Double, val subtract: Boolean) : Drag

    /** A flow stroke being drawn, in canvas pixels. */
    private class FlowDraw(val key: String, val points: MutableList<Point2D.Double>) : Drag
    private class FlowErase(val key: String) : Drag

    private class MoveLayer(val layer: ImageLayer, val start: Point2D.Double, val x: Double, val y: Double) : Drag

    /** Moving the effect's handle from [start] (canvas pixels), where it stood at ([x], [y]). */
    private class MoveHandlePoint(val handle: com.spielgrund.glitchr.effects.CanvasHandle, val start: Point2D.Double, val x: Int, val y: Int) : Drag

    /** Scaling at [corner] (see [canvasCorners]); the opposite corner [anchor] stays in place. */
    private class ScaleLayer(val layer: ImageLayer, val corner: Int, val anchor: Point2D.Double) : Drag

    /** Turning around the picture's middle ([cx], [cy]), from the mouse angle [startAngle] and the turn [startRotation]. */
    private class RotateLayer(
        val layer: ImageLayer, val cx: Double, val cy: Double, val startAngle: Double, val startRotation: Double,
    ) : Drag

    /** Merges a selection (in painted-mask pixels) into the active layer's painted mask. */
    /**
     * The magic wand at canvas point [p]: selects similar colors (taking them away with
     * [subtract]). Clicking the same color again widens the tolerance step by step, from the
     * mask as it was before the first click; a click on another color starts afresh.
     */
    private fun wand(p: Point2D.Double, subtract: Boolean) {
        val t = maskTarget() ?: return
        val pixels = t.image ?: return
        val mask = layer?.mask ?: return
        val m = toMask(p, t)
        val x = floor(m.x).toInt()
        val y = floor(m.y).toInt()
        if (x !in 0 until pixels.width || y !in 0 until pixels.height) return
        val color = pixels[x, y]
        mask.ensurePainted(t.width, t.height)
        val mode = if (subtract) 2 else 1
        val last = brush.lastSelection
        fun close(a: Int, b: Int) = listOf(16, 8, 0).all { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 6 }
        val op = if (last != null && last.wand && last.mask === mask && last.mode == mode && close(last.seedColor, color)) {
            last.tolerance = min(255, last.tolerance + 16)
            last
        } else {
            val contiguous = brush.contiguous
            val gradients = brush.gradients
            SelectionOp(mask, mask.painted?.copyOf(), t.width, t.height, mode, true, brush.tolerance, color) { tolerance ->
                val values = FloatArray(t.width * t.height)
                Selection.similarColor(pixels, x, y, tolerance, contiguous, 0, gradients)?.let { patch ->
                    val r = patch.rect
                    for (yy in 0 until r.height) for (xx in 0 until r.width) {
                        val tx = r.x + xx
                        val ty = r.y + yy
                        if (tx in 0 until t.width && ty in 0 until t.height) {
                            values[ty * t.width + tx] = (patch.data[yy * r.width + xx].toInt() and 0xFF) / 255f
                        }
                    }
                }
                values
            }.also {
                it.feather = (brush.feather * t.width / t.space.width).roundToInt()
                brush.lastSelection = it
            }
        }
        op.apply()
        refreshOverlay()
        onMaskEdited()
    }

    private fun applySelection(patch: MaskPatch?, subtract: Boolean) {
        val t = maskTarget() ?: return
        val mask = layer?.mask ?: return
        if (patch == null) return
        mask.ensurePainted(t.width, t.height)
        mask.applyPatch(patch, subtract)?.let { refreshOverlay(toCanvas(it, t)) }
        onMaskEdited()
    }

    /** A selection shape drawn in canvas pixels, rasterized into the painted mask. */
    private fun applyShape(shape: Shape, subtract: Boolean) {
        val t = maskTarget() ?: return
        val sx = t.width / t.space.width
        val toMask = AffineTransform().apply {
            scale(sx, t.height / t.space.height)
            concatenate(t.space.canvasToLocal())
        }
        val feather = (brush.feather * sx).roundToInt()
        applySelection(Selection.shape(toMask.createTransformedShape(shape), t.width, t.height, feather), subtract)
    }

    private fun updateCursor(p: Point?) {
        val mode = layer?.mask?.mode
        cursor = when {
            image == null -> Cursor.getDefaultCursor()
            spaceDown || drag is Pan -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            cropRect != null -> when ((drag as? CropDrag)?.part ?: p?.let(::cropPart) ?: 0) {
                16 -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                1, 2 -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                4, 8 -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                5, 10 -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                6, 9 -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
                else -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            }
            imageLayerCursor(p) != null -> imageLayerCursor(p)
            flowKey != null -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            activeHandle != null -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            mode == MaskMode.BRUSH -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            mode != null && mode.isGradient && p != null && handleAt(p) != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            mode != null && mode.isGradient -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            else -> Cursor.getDefaultCursor()
        }
        repaint()
    }

    /** Move or resize cursor when the selected image layer would be transformed (mask off). */
    private fun imageLayerCursor(p: Point?): Cursor? {
        val l = layer as? ImageLayer ?: return null
        if (editsMask && drag !is MoveLayer && drag !is ScaleLayer && drag !is RotateLayer) return null
        if (drag is RotateLayer) return Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val corner = (drag as? ScaleLayer)?.corner ?: p?.let { cornerAt(l, it) }
        return Cursor.getPredefinedCursor(
            when (corner) {
                0 -> Cursor.NW_RESIZE_CURSOR
                1 -> Cursor.NE_RESIZE_CURSOR
                2 -> Cursor.SE_RESIZE_CURSOR
                3 -> Cursor.SW_RESIZE_CURSOR
                else -> Cursor.MOVE_CURSOR
            },
        )
    }

    /** true = handle A, false = handle B, null = none under [p]. */
    private fun handleAt(p: Point): Boolean? {
        val mask = layer?.mask ?: return null
        if (!mask.mode.isGradient || image == null) return null
        val b = toScreen(mask.handleB)
        if (hypot(b.x - p.x, b.y - p.y) <= HANDLE + 4) return false
        val a = toScreen(mask.handleA)
        if (hypot(a.x - p.x, a.y - p.y) <= HANDLE + 4) return true
        return null
    }

    /** Paints from [from] to [to] (canvas pixels); the brush size stays in canvas pixels however the mask is scaled. */
    private fun paintStroke(canvasFrom: Point2D.Double, canvasTo: Point2D.Double, erase: Boolean) {
        val t = maskTarget() ?: return
        val mask = layer?.mask ?: return
        mask.ensurePainted(t.width, t.height)
        val from = toMask(canvasFrom, t)
        val to = toMask(canvasTo, t)
        val radius = brush.size / 2.0 * t.width / t.space.width
        val spacing = max(1.0, radius * 0.2)
        val dist = from.distance(to)
        val steps = max(1, ceil(dist / spacing).toInt())
        var dirty: Rectangle? = null
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            val x = from.x + (to.x - from.x) * t
            val y = from.y + (to.y - from.y) * t
            val r = mask.dab(x, y, radius, brush.hardness / 100f, brush.strength / 100f, erase) ?: continue
            dirty = dirty?.union(r) ?: r
        }
        dirty?.let { refreshOverlay(toCanvas(it, t)) }
        onMaskEdited()
    }

    private inner class Mouse : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            requestFocusInWindow()
            if (image == null) return
            val viewport = parent as? JViewport
            if (SwingUtilities.isMiddleMouseButton(e) || spaceDown) {
                drag = Pan(e.locationOnScreen)
                updateCursor(e.point)
                return
            }
            cropRect?.let { r ->
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val part = cropPart(e.point)
                if (e.clickCount == 2 && part == 16) {
                    onCropFinish(true)
                    return
                }
                drag = CropDrag(part, toImage(e.point), r.clone() as Rectangle2D.Double)
                return
            }
            transforms(e)?.let { l ->
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val corner = cornerAt(l, e.point)
                drag = when {
                    corner == null -> MoveLayer(l, toImage(e.point), l.x, l.y)
                    e.isShiftDown -> {
                        val b = l.bounds
                        val p = toImage(e.point)
                        RotateLayer(l, b.centerX, b.centerY, kotlin.math.atan2(p.y - b.centerY, p.x - b.centerX), l.rotation)
                    }
                    // the opposite corner stays where it is
                    else -> ScaleLayer(l, corner, canvasCorners(l)[(corner + 2) % 4])
                }
                return
            }
            activeHandle?.let { h ->
                val l = layer as? EffectLayer ?: return
                if (SwingUtilities.isLeftMouseButton(e)) drag = MoveHandlePoint(h, toImage(e.point), l.values[h.xKey] ?: 0, l.values[h.yKey] ?: 0)
                return
            }
            flowKey?.let { key ->
                if (SwingUtilities.isRightMouseButton(e) || e.isAltDown) {
                    drag = FlowErase(key)
                    eraseFlow(e.point, key)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    drag = FlowDraw(key, mutableListOf(toImage(e.point)))
                }
                return
            }
            val mask = layer?.mask ?: return
            // any other edit of the mask ends the refinable selection
            if (!(mask.mode == MaskMode.BRUSH && brush.tool == MaskTool.WAND)) brush.lastSelection = null
            when {
                mask.mode == MaskMode.BRUSH -> {
                    val erase = SwingUtilities.isRightMouseButton(e) || e.isAltDown
                    val p = toImage(e.point)
                    when (brush.tool) {
                        MaskTool.BRUSH -> {
                            drag = Paint(p, erase)
                            paintStroke(p, p, erase)
                        }
                        MaskTool.RECT, MaskTool.ELLIPSE -> drag = ShapeDrag(p, p, brush.tool == MaskTool.ELLIPSE, erase)
                        MaskTool.LASSO -> drag = LassoDrag(Path2D.Double().apply { moveTo(p.x, p.y) }, erase)
                        MaskTool.WAND -> {
                            wand(p, erase)
                            return
                        }
                    }
                }
                mask.mode.isGradient && SwingUtilities.isLeftMouseButton(e) -> {
                    val handle = handleAt(e.point)
                    if (handle == null) {
                        mask.handleA = toRel(e.point)
                        mask.handleB = toRel(e.point)
                    }
                    drag = MoveHandle(handle ?: false)
                }
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            mouse = e.point
            when (val d = drag) {
                is Pan -> {
                    val now = e.locationOnScreen
                    var dx = now.x - d.last.x
                    var dy = now.y - d.last.y
                    d.last = now
                    // scroll as far as the window can; what is left moves the picture itself
                    (parent as? JViewport)?.let { viewport ->
                        val maxX = max(0, viewport.viewSize.width - viewport.extentSize.width)
                        val maxY = max(0, viewport.viewSize.height - viewport.extentSize.height)
                        val view = viewport.viewPosition
                        val x = (view.x - dx).coerceIn(0, maxX)
                        val y = (view.y - dy).coerceIn(0, maxY)
                        dx -= view.x - x
                        dy -= view.y - y
                        viewport.viewPosition = Point(x, y)
                    }
                    if (dx != 0 || dy != 0) {
                        panX += dx
                        panY += dy
                        repaint()
                    }
                }
                is CropDrag -> {
                    val img = image ?: return
                    val w = img.width.toDouble()
                    val h = img.height.toDouble()
                    val p = toImage(e.point).let { Point2D.Double(it.x.coerceIn(0.0, w), it.y.coerceIn(0.0, h)) }
                    val f = d.from
                    cropRect = when (d.part) {
                        // a new frame between where the drag began and the mouse
                        0 -> Rectangle2D.Double(min(d.start.x, p.x), min(d.start.y, p.y), kotlin.math.abs(p.x - d.start.x), kotlin.math.abs(p.y - d.start.y))
                        16 -> Rectangle2D.Double(
                            (f.x + p.x - d.start.x).coerceIn(0.0, w - f.width), (f.y + p.y - d.start.y).coerceIn(0.0, h - f.height), f.width, f.height,
                        )
                        else -> {
                            var x0 = f.x
                            var y0 = f.y
                            var x1 = f.x + f.width
                            var y1 = f.y + f.height
                            if (d.part and 1 != 0) x0 = p.x
                            if (d.part and 2 != 0) x1 = p.x
                            if (d.part and 4 != 0) y0 = p.y
                            if (d.part and 8 != 0) y1 = p.y
                            Rectangle2D.Double(min(x0, x1), min(y0, y1), kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
                        }
                    }
                    repaint()
                }
                is Paint -> {
                    val p = toImage(e.point)
                    paintStroke(d.last, p, d.erase)
                    d.last = p
                }
                is MoveHandle -> {
                    val mask = layer?.mask ?: return
                    if (d.first) mask.handleA = toRel(e.point) else mask.handleB = toRel(e.point)
                    refreshOverlay()
                    onMaskEdited()
                }
                is MoveLayer -> {
                    val p = toImage(e.point)
                    d.layer.x = d.x + p.x - d.start.x
                    d.layer.y = d.y + p.y - d.start.y
                    onTransformEdited(false)
                }
                is ScaleLayer -> {
                    val p = toImage(e.point)
                    val l = d.layer
                    // the mouse relative to the fixed corner, in the picture's own (unturned) directions
                    val a = Math.toRadians(l.rotation)
                    val ca = kotlin.math.cos(a)
                    val sa = kotlin.math.sin(a)
                    val dx = p.x - d.anchor.x
                    val dy = p.y - d.anchor.y
                    val u = dx * ca + dy * sa
                    val v = -dx * sa + dy * ca
                    val s = max(kotlin.math.abs(u) / l.image.width, kotlin.math.abs(v) / l.image.height).coerceAtLeast(0.01)
                    val w = l.image.width * s
                    val h = l.image.height * s
                    // corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left; the dragged one lies this way from the anchor
                    val signX = if (d.corner == 1 || d.corner == 2) 1 else -1
                    val signY = if (d.corner == 2 || d.corner == 3) 1 else -1
                    val hx = signX * w / 2
                    val hy = signY * h / 2
                    val cx = d.anchor.x + hx * ca - hy * sa
                    val cy = d.anchor.y + hx * sa + hy * ca
                    l.scale = s
                    l.x = cx - w / 2
                    l.y = cy - h / 2
                    onTransformEdited(false)
                }
                is RotateLayer -> {
                    val p = toImage(e.point)
                    val angle = kotlin.math.atan2(p.y - d.cy, p.x - d.cx)
                    var r = d.startRotation + Math.toDegrees(angle - d.startAngle)
                    r = ((r + 180) % 360 + 360) % 360 - 180
                    // Ctrl snaps to 15° steps
                    if (e.isControlDown) r = kotlin.math.round(r / 15) * 15
                    d.layer.rotation = kotlin.math.round(r * 10) / 10
                    onTransformEdited(false)
                }
                is FlowDraw -> {
                    val p = toImage(e.point)
                    // a point every few screen pixels is enough
                    if (p.distance(d.points.last()) * zoom >= 4) d.points.add(p)
                }
                is FlowErase -> eraseFlow(e.point, d.key)
                is MoveHandlePoint -> {
                    val l = layer as? EffectLayer ?: return
                    val p = toImage(e.point)
                    // the drag moves the point, within its sliders' range
                    fun limit(key: String, value: Double): Int {
                        val s = l.params.firstOrNull { it.key == key } as? com.spielgrund.glitchr.effects.Param.Slider
                        val img = image
                        val hi = if (s != null && img != null) s.maxFor(img.width, img.height) else Int.MAX_VALUE
                        return value.roundToInt().coerceIn(s?.min ?: Int.MIN_VALUE, hi)
                    }
                    l.values[d.handle.xKey] = limit(d.handle.xKey, d.x + p.x - d.start.x)
                    l.values[d.handle.yKey] = limit(d.handle.yKey, d.y + p.y - d.start.y)
                    onHandleEdited(false)
                }
                is ShapeDrag -> d.end = toImage(e.point)
                is LassoDrag -> toImage(e.point).let { d.path.lineTo(it.x, it.y) }
                null -> {}
            }
            repaint()
        }

        override fun mouseReleased(e: MouseEvent) {
            val d = drag
            val wasHandle = d is MoveHandle
            drag = null
            if (d is MoveLayer || d is ScaleLayer || d is RotateLayer) onTransformEdited(true)
            if (d is FlowDraw) {
                d.points.add(toImage(e.point))
                val img = image
                val l = layer as? EffectLayer
                val stroke = d.points.filterIndexed { i, p -> i == 0 || p.distance(d.points[i - 1]) > 1e-6 }
                if (img != null && l != null && stroke.size >= 2) {
                    val added = stroke.map { Point2D.Double(it.x / img.width, it.y / img.height) }
                    l.texts[d.key] = FlowStrokes.format(FlowStrokes.parse(l.texts[d.key] ?: "") + listOf(added))
                }
                onFlowEdited(true)
            }
            if (d is FlowErase) onFlowEdited(true)
            if (d is MoveHandlePoint) onHandleEdited(true)
            when (d) {
                is ShapeDrag -> applyShape(d.shape(), d.subtract)
                is LassoDrag -> {
                    d.path.closePath()
                    applyShape(d.path, d.subtract)
                }
                else -> {}
            }
            updateCursor(e.point)
            if (wasHandle) {
                refreshOverlay()
                onMaskEdited()
            }
        }

        override fun mouseMoved(e: MouseEvent) {
            mouse = e.point
            eraseHeld = e.isAltDown
            updateCursor(e.point)
        }

        override fun mouseExited(e: MouseEvent) {
            mouse = null
            repaint()
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            if (e.isControlDown && image != null) {
                if (e.preciseWheelRotation < 0) zoomIn(e.point) else zoomOut(e.point)
            } else {
                // let the scroll pane scroll
                val scroll = parent?.parent ?: return
                scroll.dispatchEvent(SwingUtilities.convertMouseEvent(this@GlitchCanvas, e, scroll))
            }
        }
    }

    // ---------------------------------------------------------------- Scrollable

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = 24
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height - 24 else visible.width - 24

    // Stretch to the viewport when the image is smaller, so it can be centered.
    override fun getScrollableTracksViewportWidth() = (parent as? JViewport)?.let { it.width > preferredSize.width } ?: false
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false

    companion object {
        const val MIN_ZOOM = 0.02
        const val MAX_ZOOM = 32.0
        private const val MARGIN = 16
        private const val CHECKER = 8
        private const val HANDLE = 6
        private const val ERASE_RADIUS = 12
        private val zoomSteps = listOf(
            0.02, 0.03, 0.05, 0.0625, 0.08, 0.1, 0.125, 0.16, 0.2, 0.25, 0.33, 0.5, 0.67, 0.75,
            1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0,
        )

        fun nextZoom(current: Double, up: Boolean): Double =
            if (up) zoomSteps.firstOrNull { it > current * 1.001 } ?: MAX_ZOOM
            else zoomSteps.lastOrNull { it < current / 1.001 } ?: MIN_ZOOM
    }
}
