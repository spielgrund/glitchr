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
    BRUSH("Pinsel"), RECT("Rechteck"), ELLIPSE("Ellipse"), LASSO("Lasso"), WAND("Zauberstab");

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
        it != null && it.mask.mode != MaskMode.OFF && (it !is ImageLayer && flowParam(it) == null || showMask)
    }

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
            for (x in r.x until r.x + r.width) {
                val a = ((1f - m[x]) * 150).toInt()
                data[y * w + x] = (a shl 24) or 0xFF2828
            }
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
        selectionOutline()?.let { paintSelectionOutline(g2, it) }
        g2.dispose()
    }

    private fun paintHint(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x8C8F94)
        g.font = font.deriveFont(15f)
        val lines = listOf("Bild öffnen (Strg+O), einfügen (Strg+V)", "oder hierher ziehen")
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

    /** Frame of the selected image layer with its four scale handles. */
    private fun paintLayerFrame(g: Graphics2D, layer: ImageLayer) {
        val r = layerFrame(layer)
        g.stroke = BasicStroke(1f)
        g.color = Color(0, 0, 0, 160)
        g.draw(r)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
        g.color = Color(0x4DA3FF)
        g.draw(r)
        g.stroke = BasicStroke(1f)
        for (c in corners(r)) {
            val box = Rectangle2D.Double(c.x - HANDLE, c.y - HANDLE, 2.0 * HANDLE, 2.0 * HANDLE)
            g.color = Color.WHITE
            g.fill(box)
            g.color = Color(0x4DA3FF)
            g.draw(box)
        }
    }

    /** The layer's placed area in screen coordinates. */
    private fun layerFrame(layer: ImageLayer): Rectangle2D.Double {
        val b = layer.bounds
        return Rectangle2D.Double(originX() + b.x * zoom, originY() + b.y * zoom, b.width * zoom, b.height * zoom)
    }

    /** Corners top-left, top-right, bottom-right, bottom-left. */
    private fun corners(r: Rectangle2D.Double) = listOf(
        Point2D.Double(r.minX, r.minY), Point2D.Double(r.maxX, r.minY),
        Point2D.Double(r.maxX, r.maxY), Point2D.Double(r.minX, r.maxY),
    )

    /** Index of the scale handle under [p], or null. */
    private fun cornerAt(layer: ImageLayer, p: Point): Int? =
        corners(layerFrame(layer)).indexOfFirst { hypot(it.x - p.x, it.y - p.y) <= HANDLE + 4 }.takeIf { it >= 0 }

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

    private fun originX() = max(MARGIN.toDouble(), (width - (image?.width ?: 0) * zoom) / 2)
    private fun originY() = max(MARGIN.toDouble(), (height - (image?.height ?: 0) * zoom) / 2)

    private fun imageBounds(): Rectangle {
        val img = image ?: return Rectangle()
        return Rectangle(originX().toInt(), originY().toInt(), ceil(img.width * zoom).toInt(), ceil(img.height * zoom).toInt())
    }

    private fun toImage(p: Point) = Point2D.Double((p.x - originX()) / zoom, (p.y - originY()) / zoom)

    /** The canvas rectangle the active mask spans (the whole canvas for layers without picture). */
    private fun maskSpace(): MaskSpace = maskTarget()?.space ?: image!!.let { MaskSpace.canvas(it.width, it.height) }

    /** A gradient point (relative to the mask's rectangle) on screen. */
    private fun toScreen(r: RelPoint): Point2D.Double {
        val s = maskSpace()
        return Point2D.Double(originX() + (s.x + r.x * s.width) * zoom, originY() + (s.y + r.y * s.height) * zoom)
    }

    private fun toRel(p: Point): RelPoint {
        val s = maskSpace()
        val i = toImage(p)
        return RelPoint((i.x - s.x) / s.width, (i.y - s.y) / s.height)
    }

    /** Canvas pixels → pixels of the painted mask. */
    private fun toMask(p: Point2D.Double, t: MaskTarget) = Point2D.Double(
        (p.x - t.space.x) * t.width / t.space.width,
        (p.y - t.space.y) * t.height / t.space.height,
    )

    /** Painted-mask pixels → canvas pixels (for redrawing the overlay). */
    private fun toCanvas(r: Rectangle, t: MaskTarget): Rectangle {
        val sx = t.space.width / t.width
        val sy = t.space.height / t.height
        val x0 = floor(t.space.x + r.x * sx).toInt()
        val y0 = floor(t.space.y + r.y * sy).toInt()
        val x1 = ceil(t.space.x + (r.x + r.width) * sx).toInt()
        val y1 = ceil(t.space.y + (r.y + r.height) * sy).toInt()
        return Rectangle(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2)
    }

    // ---------------------------------------------------------------- mouse

    private sealed interface Drag
    private class Pan(val start: Point, val view: Point) : Drag
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

    /** Scaling at [corner] (see [corners]); the opposite corner [anchor] stays in place. */
    private class ScaleLayer(val layer: ImageLayer, val corner: Int, val anchor: Point2D.Double) : Drag

    /** Merges a selection (in painted-mask pixels) into the active layer's painted mask. */
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
            translate(-t.space.x, -t.space.y)
        }
        val feather = (brush.feather * sx).roundToInt()
        applySelection(Selection.shape(toMask.createTransformedShape(shape), t.width, t.height, feather), subtract)
    }

    private fun updateCursor(p: Point?) {
        val mode = layer?.mask?.mode
        cursor = when {
            image == null -> Cursor.getDefaultCursor()
            spaceDown || drag is Pan -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            imageLayerCursor(p) != null -> imageLayerCursor(p)
            flowKey != null -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
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
        if (editsMask && drag !is MoveLayer && drag !is ScaleLayer) return null
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
                if (viewport != null) drag = Pan(e.locationOnScreen, viewport.viewPosition)
                updateCursor(e.point)
                return
            }
            transforms(e)?.let { l ->
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val corner = cornerAt(l, e.point)
                drag = if (corner == null) MoveLayer(l, toImage(e.point), l.x, l.y)
                else {
                    val b = l.bounds
                    val opposite = listOf(Point2D.Double(b.maxX, b.maxY), Point2D.Double(b.minX, b.maxY),
                        Point2D.Double(b.minX, b.minY), Point2D.Double(b.maxX, b.minY))[corner]
                    ScaleLayer(l, corner, opposite)
                }
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
                            val t = maskTarget() ?: return
                            val pixels = t.image ?: return
                            val m = toMask(p, t)
                            val patch = Selection.similarColor(
                                pixels, floor(m.x).toInt(), floor(m.y).toInt(),
                                brush.tolerance, brush.contiguous, (brush.feather * t.width / t.space.width).roundToInt(),
                            )
                            applySelection(patch, erase)
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
                    val viewport = parent as? JViewport ?: return
                    val now = e.locationOnScreen
                    val maxX = max(0, viewport.viewSize.width - viewport.extentSize.width)
                    val maxY = max(0, viewport.viewSize.height - viewport.extentSize.height)
                    viewport.viewPosition = Point(
                        (d.view.x - (now.x - d.start.x)).coerceIn(0, maxX),
                        (d.view.y - (now.y - d.start.y)).coerceIn(0, maxY),
                    )
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
                    val s = max(
                        kotlin.math.abs(p.x - d.anchor.x) / l.image.width,
                        kotlin.math.abs(p.y - d.anchor.y) / l.image.height,
                    ).coerceAtLeast(0.01)
                    val w = l.image.width * s
                    val h = l.image.height * s
                    l.scale = s
                    // corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left
                    l.x = if (d.corner == 0 || d.corner == 3) d.anchor.x - w else d.anchor.x
                    l.y = if (d.corner == 0 || d.corner == 1) d.anchor.y - h else d.anchor.y
                    onTransformEdited(false)
                }
                is FlowDraw -> {
                    val p = toImage(e.point)
                    // a point every few screen pixels is enough
                    if (p.distance(d.points.last()) * zoom >= 4) d.points.add(p)
                }
                is FlowErase -> eraseFlow(e.point, d.key)
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
            if (d is MoveLayer || d is ScaleLayer) onTransformEdited(true)
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
