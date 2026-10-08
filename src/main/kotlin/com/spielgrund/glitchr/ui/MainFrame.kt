package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Generator
import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.Animator
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.GeneratorLayer
import com.spielgrund.glitchr.model.History
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskSpace
import com.spielgrund.glitchr.model.groupRange
import com.spielgrund.glitchr.model.startsGroup
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.model.SourceLayer
import com.spielgrund.glitchr.project.ProjectFile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

private val prefs = Preferences.userNodeForPackage(MainFrame::class.java)
private const val PREF_DIR = "lastDir"
private const val PREF_NEW_WIDTH = "newWidth"
private const val PREF_NEW_HEIGHT = "newHeight"
private const val SHORTCUT = InputEvent.CTRL_DOWN_MASK

/** [name] shortened to at most [max] characters, keeping its start and end ("csm_leopard-mas…9b7762679"). */
internal fun shortName(name: String, max: Int = 32): String =
    if (name.length <= max) name else name.take(max - 10) + "…" + name.takeLast(9)

/** Scroll content that always takes the viewport's width, so nothing is cut off on the right. */
private class WidthTrackingPanel : JPanel(BorderLayout()), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: java.awt.Rectangle, orientation: Int, direction: Int) = 16
    override fun getScrollableBlockIncrement(visible: java.awt.Rectangle, orientation: Int, direction: Int) = visible.height - 32
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

/**
 * Rendered frames of the animation, so playback can run at full speed once a frame is done.
 * Holds as many frames as fit into [budget]; the least recently used go first. Any edit
 * clears it ([invalidate]); [version] tells renders that started before an edit apart.
 */
private class FrameCache {
    @Volatile var version = 0
        private set
    @Volatile var budget = 64
    private val frames = object : LinkedHashMap<Int, Pixels>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pixels>) = size > budget
    }

    @Synchronized fun get(frame: Int): Pixels? = frames[frame]
    @Synchronized fun contains(frame: Int) = frames.containsKey(frame)
    @Synchronized fun put(frame: Int, pixels: Pixels, ofVersion: Int) {
        if (ofVersion == version) frames[frame] = pixels
    }

    @Synchronized fun invalidate() {
        version++
        frames.clear()
    }
}

class MainFrame : JFrame("GlitchR"), LayerEditorHost, TimelineHost {
    override val brush = Brush()
    private val canvas = GlitchCanvas(brush)
    private val layerList = LayerList(::select, ::toggleVisible) { renderer.generated(it.id) }
    private val editorBox = WidthTrackingPanel()
    private val status = JLabel(" ")
    private val originalButton = JToggleButton("Original")
    private val showMaskItem = JCheckBoxMenuItem("Show mask")
    private val showMaskButton = JToggleButton("Show mask")
    private val editorScroll = JScrollPane(editorBox)
    private val undoItem = item("Undo", KeyEvent.VK_Z) { undo() }
    private val redoItem = item("Redo", KeyEvent.VK_Y) { redo() }

    private val history = History()

    /** Edits become an undo step once nothing has changed for a moment, so a slider drag is one step. */
    private val commitTimer = javax.swing.Timer(500) { commitHistory() }.apply { isRepeats = false }

    /** Bottom layer first. */
    private val layers = mutableListOf<Layer>()
    private var selected: Layer? = null

    /** Canvas size; 0 while no document is open. */
    private var docWidth = 0
    private var docHeight = 0
    private var docName = "image"
    private val hasDocument get() = docWidth > 0

    private var result: Pixels? = null
    private var resultImage: BufferedImage? = null
    /** Unsaved changes to the project; shown as ● in the title. */
    private var dirty = false
        set(value) {
            field = value
            updateTitle()
        }

    /** The open `.glitchr` file; null = not saved yet. */
    private var projectFile: File? = null

    private val renderer = Renderer()
    private val renderExecutor = Executors.newSingleThreadExecutor { Thread(it, "glitch-render").apply { isDaemon = true } }
    private val generation = AtomicInteger()

    // ---- animation
    /** The animation's length and frame rate; null: no animation. */
    override var timeline: com.spielgrund.glitchr.model.Timeline? = null
        private set
    @Volatile override var currentFrame = 0
        private set
    @Volatile override var isPlaying = false
        private set
    override var loopPlayback = true
    private val timelinePanel = TimelinePanel(this)
    private val frameCache = FrameCache()

    /** Playback renders ahead on its own thread and renderer, so the interactive one keeps its cache. */
    private val animRenderer = Renderer()
    private val animExecutor = Executors.newSingleThreadExecutor { Thread(it, "glitch-animation").apply { isDaemon = true } }
    private var playTimer: javax.swing.Timer? = null

    /** The editor follows the playhead with a short delay, so scrubbing stays smooth. */
    private val editorFollowTimer = javax.swing.Timer(120) { rebuildEditor() }.apply { isRepeats = false }

    override var showMask: Boolean
        get() = canvas.showMask
        set(value) {
            canvas.showMask = value
            showMaskItem.isSelected = value
            showMaskButton.isSelected = value
        }

    override var showFlow: Boolean
        get() = canvas.showFlow
        set(value) {
            canvas.showFlow = value
        }

    override val imageSize get() = if (hasDocument) docWidth to docHeight else null

    /**
     * The selected layer's mask belongs to the picture of its group: it spans the placed
     * picture and has the picture's resolution. Generated pictures and layers without
     * picture use the canvas.
     */
    override val maskTarget: MaskTarget?
        get() {
            if (!hasDocument) return null
            val index = layers.indexOf(selected ?: return null)
            if (index < 0) return null
            return when (val source = layers[groupRange(layers, index).first]) {
                is ImageLayer -> MaskTarget(Renderer.spaceOf(source.state()), source.image.width, source.image.height, source.image)
                // the mask moves, scales and turns with the generator's position; its resolution stays the canvas'
                is GeneratorLayer -> MaskTarget(Renderer.spaceOf(source.state(), docWidth, docHeight), docWidth, docHeight, renderer.generated(source.id))
                else -> MaskTarget(MaskSpace.canvas(docWidth, docHeight), docWidth, docHeight, null)
            }
        }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (confirmDiscard()) exitProcess(0)
            }
        })
        jMenuBar = menus()

        canvas.onMaskEdited = { changed() }
        canvas.onFlowEdited = { finished ->
            changed()
            // the editor shows the number of strokes
            if (finished) rebuildEditor()
        }
        canvas.onHandleEdited = { finished ->
            changed()
            // the sliders show the new place
            if (finished) rebuildEditor()
        }
        canvas.onZoom = ::updateStatus
        canvas.maskTarget = { maskTarget }
        canvas.onTransformEdited = { finished ->
            changed()
            canvas.refreshOverlay()
            if (finished) rebuildEditor()
        }
        val scroll = JScrollPane(canvas).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = canvas.background
        }

        val sidebar = JPanel(BorderLayout()).apply {
            add(layerPanel(), BorderLayout.NORTH)
            add(editorScroll.apply {
                border = BorderFactory.createMatteBorder(1, 0, 0, 0, javax.swing.UIManager.getColor("Component.borderColor"))
                verticalScrollBar.unitIncrement = 16
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            minimumSize = Dimension(300, 0)
            preferredSize = Dimension(360, 0)
        }

        // the timeline lies below the canvas
        val left = JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, timelinePanel).apply {
            resizeWeight = 1.0
            border = BorderFactory.createEmptyBorder()
            isContinuousLayout = true
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, sidebar).apply {
            resizeWeight = 1.0
            border = BorderFactory.createEmptyBorder()
        }

        contentPane.add(toolbar(), BorderLayout.NORTH)
        contentPane.add(split, BorderLayout.CENTER)
        contentPane.add(status.apply { border = BorderFactory.createEmptyBorder(3, 8, 3, 8) }, BorderLayout.SOUTH)

        val drop = FileDropHandler()
        canvas.transferHandler = drop
        (contentPane as JComponent).transferHandler = drop

        rootPane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, SHORTCUT or InputEvent.SHIFT_DOWN_MASK), "redo")
        rootPane.actionMap.put("redo", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = redo()
        })
        updateUndoItems()

        refreshLayers()
        size = Dimension(1400, 900)
        setLocationRelativeTo(null)
    }

    // ---------------------------------------------------------------- UI construction

    // Toolbar and layer buttons don't take the focus, so Space (pan) and Enter can't trigger them.
    private fun toolbar() = JPanel(BorderLayout()).apply {
        add(toolbarLeft(), BorderLayout.CENTER)
        // copying sits apart on the far right
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
            add(small("Copy", "Copy the current picture with transparency to the clipboard (Ctrl+Shift+C)") { copy() })
        }, BorderLayout.EAST)
    }

    private fun toolbarLeft() = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        add(small("New…", "Empty canvas of any size, without an image (Ctrl+N)") { newDialog() })
        add(small("Open…", "Open an image or project (Ctrl+O)") { openDialog() })
        add(small("Close", "Close the picture and start empty – then paste or drop an image (Ctrl+W)") { closeDocument() })
        add(small("+ Image…", "Insert an image as a new layer (Ctrl+I) – or just drag it into the window") { insertImageDialog() })
        add(small("Save", "Save the project with all layers and masks (Ctrl+S)") { save() })
        add(small("Export…", "Save the result as an image (Ctrl+E)") { exportDialog() })
        add(small("Fit", "Show the whole picture (Ctrl+0)") { canvas.setZoom(canvas.fitZoom()) })
        add(originalButton.apply {
            isFocusable = false
            toolTipText = "Shows the image layers without effects (Ctrl+B)"
            addActionListener { requestRender() }
        })
        add(showMaskButton.apply {
            isFocusable = false
            toolTipText = "Show/hide the mask's red overlay; the mask works either way (Ctrl+M)"
            addActionListener { showMask = isSelected; rebuildEditor() }
        })
    }

    private fun layerPanel(): JPanel {
        val addButton = JButton("+ Effect ▾").apply { isFocusable = false }
        val popup = JPopupMenu().apply {
            for (menu in effectMenus { addLayer(it) }) add(menu)
        }
        addButton.addActionListener { popup.show(addButton, 0, addButton.height) }
        val adjustmentButton = JButton("+ Adjustment ▾").apply {
            isFocusable = false
            toolTipText = "An effect that works on all layers below it, like an adjustment layer in Photoshop"
        }
        val adjustmentPopup = JPopupMenu().apply {
            for (menu in effectMenus { addLayer(it, adjustment = true) }) add(menu)
        }
        adjustmentButton.addActionListener { adjustmentPopup.show(adjustmentButton, 0, adjustmentButton.height) }
        val generatorButton = JButton("+ Generator ▾").apply {
            isFocusable = false
            toolTipText = "New layer that creates a pattern or noise without an image – effects above work on it"
        }
        val generatorPopup = JPopupMenu().apply {
            for (generator in Generators.all) add(JMenuItem(generator.name).apply {
                toolTipText = generator.description
                addActionListener { addGenerator(generator) }
            })
        }
        generatorButton.addActionListener { generatorPopup.show(generatorButton, 0, generatorButton.height) }

        // wraps onto a second row when the sidebar is too narrow for all buttons
        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(generatorButton)
            add(addButton)
            add(adjustmentButton)
            // the four layer buttons stay together when the row wraps
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, -4, 0, -4)
                add(small("▲", "Layer up (Ctrl+PgUp)") { moveSelected(1) })
                add(small("▼", "Layer down (Ctrl+PgDn)") { moveSelected(-1) })
                add(small("⧉", "Duplicate layer (Ctrl+J)") { duplicateSelected() })
                add(small("✕", "Delete layer (Del)") { deleteSelected() })
            })
        }
        return JPanel(BorderLayout()).apply {
            add(JLabel("Layers").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = BorderFactory.createEmptyBorder(8, 10, 4, 10)
            }, BorderLayout.NORTH)
            add(JScrollPane(layerList).apply {
                preferredSize = Dimension(0, 220)
                verticalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun small(text: String, tip: String, action: () -> Unit) = JButton(text).apply {
        isFocusable = false
        toolTipText = tip
        addActionListener { action() }
    }

    private fun menus() = JMenuBar().apply {
        add(JMenu("File").apply {
            add(item("New…", KeyEvent.VK_N) { newDialog() })
            add(item("Open…", KeyEvent.VK_O) { openDialog() })
            add(item("Insert image as layer…", KeyEvent.VK_I) { insertImageDialog() })
            add(item("Paste from clipboard", KeyEvent.VK_V) { paste() })
            addSeparator()
            add(item("Save project", KeyEvent.VK_S) { save() })
            add(item("Save project as…", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK) { saveAs() })
            addSeparator()
            add(item("Export image…", KeyEvent.VK_E) { exportDialog() })
            add(item("Copy result", KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK) { copy() })
            addSeparator()
            add(item("Close", KeyEvent.VK_W) { closeDocument() })
            add(JMenuItem("Quit").apply { addActionListener { if (confirmDiscard()) exitProcess(0) } })
        })
        add(JMenu("Edit").apply {
            add(undoItem)
            add(redoItem)
        })
        add(JMenu("Layer").apply {
            add(JMenuItem("Insert image as layer…").apply { addActionListener { insertImageDialog() } })
            add(JMenu("Add generator").apply {
                for (generator in Generators.all) add(JMenuItem(generator.name).apply {
                    toolTipText = generator.description
                    addActionListener { addGenerator(generator) }
                })
            })
            add(JMenu("Add effect").apply {
                for (menu in effectMenus { addLayer(it) }) add(menu)
            })
            add(adjustmentMenu())
            add(item("Duplicate", KeyEvent.VK_J) { duplicateSelected() })
            add(JMenuItem("Delete").apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
                addActionListener { deleteSelected() }
            })
            addSeparator()
            add(item("Up", KeyEvent.VK_PAGE_UP) { moveSelected(1) })
            add(item("Down", KeyEvent.VK_PAGE_DOWN) { moveSelected(-1) })
            addSeparator()
            add(JMenuItem("Apply all layers to the image").apply {
                toolTipText = "The result becomes a single image layer, all layers are replaced"
                addActionListener { flatten() }
            })
        })
        add(JMenu("Animation").apply {
            add(JMenuItem("Create animation…").apply { addActionListener { createAnimation() } })
            add(JMenuItem("Animation settings…").apply { addActionListener { animationSettings() } })
            addSeparator()
            add(item("Play / pause", KeyEvent.VK_P) { togglePlay() })
            add(item("Previous frame", KeyEvent.VK_LEFT) { seek(currentFrame - 1) })
            add(item("Next frame", KeyEvent.VK_RIGHT) { seek(currentFrame + 1) })
            addSeparator()
            add(item("Export animation…", KeyEvent.VK_E, InputEvent.SHIFT_DOWN_MASK) { exportAnimation() })
            addSeparator()
            add(JMenuItem("Remove animation").apply {
                toolTipText = "Deletes the timeline and all keyframes; every setting keeps its value at the current frame"
                addActionListener { removeAnimation() }
            })
        })
        add(JMenu("View").apply {
            add(item("Fit", KeyEvent.VK_0) { canvas.setZoom(canvas.fitZoom()) })
            add(item("100 %", KeyEvent.VK_1) { canvas.setZoom(1.0) })
            add(item("Zoom in", KeyEvent.VK_PLUS) { canvas.zoomIn() })
            add(item("Zoom out", KeyEvent.VK_MINUS) { canvas.zoomOut() })
            addSeparator()
            add(showMaskItem.apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_M, SHORTCUT)
                addActionListener { showMask = isSelected; rebuildEditor() }
            })
            add(item("Show original", KeyEvent.VK_B) {
                originalButton.isSelected = !originalButton.isSelected
                requestRender()
            })
        })
    }

    private fun item(text: String, key: Int, extraMask: Int = 0, action: () -> Unit) = JMenuItem(text).apply {
        accelerator = KeyStroke.getKeyStroke(key, SHORTCUT or extraMask)
        addActionListener { action() }
    }

    // ---------------------------------------------------------------- layers

    /** The effects by category as submenus; [pick] gets the chosen effect. */
    private fun effectMenus(pick: (Effect) -> Unit) = Effects.categories.map { category ->
        JMenu(category.name).apply {
            for (effect in category.effects) add(JMenuItem(effect.name).apply {
                toolTipText = effect.description
                addActionListener { pick(effect) }
            })
        }
    }

    private fun adjustmentMenu() = JMenu("Add adjustment layer").apply {
        toolTipText = "An effect that works on all layers below it, like an adjustment layer in Photoshop"
        for (menu in effectMenus { addLayer(it, adjustment = true) }) add(menu)
    }

    /**
     * Adds an effect layer above the selected layer. An [adjustment] layer works on
     * everything below it, so it goes above the selected layer's whole group.
     */
    private fun addLayer(effect: Effect, adjustment: Boolean = false) {
        val layer = EffectLayer(effect).also { it.adjustment = adjustment }
        val index = selected?.let { if (adjustment) groupRange(layers, layers.indexOf(it)).last + 1 else layers.indexOf(it) + 1 } ?: layers.size
        layers.add(index, layer)
        select(layer)
        changed()
        if (adjustment) status.text = "Adjustment layer “${effect.name}” inserted – it works on all layers below"
    }

    /** Adds a picture as a new image layer above the selected layer's group, shrunk to fit and centered. */
    private fun addImageLayer(img: BufferedImage, name: String) {
        val layer = ImageLayer(Pixels.of(img)).apply {
            this.name = shortName(name)
            fitInto(docWidth, docHeight, onlyShrink = true)
        }
        val index = selected?.let { groupRange(layers, layers.indexOf(it)).last + 1 } ?: layers.size
        layers.add(index, layer)
        select(layer)
        changed()
        status.text = "Image layer “${shortName(name)}” inserted"
    }

    /**
     * Adds a generator layer above the selected layer's group. Without a document, a new
     * empty canvas is asked for first.
     */
    private fun addGenerator(generator: Generator) {
        if (!hasDocument) {
            newDialog(generator)
            return
        }
        val layer = GeneratorLayer(generator)
        val index = selected?.let { groupRange(layers, layers.indexOf(it)).last + 1 } ?: layers.size
        layers.add(index, layer)
        select(layer)
        changed()
        status.text = "Generator “${generator.name}” inserted"
    }

    /** The selected layer's index range: for a source or adjustment layer its whole group (with its effects). */
    private fun selectedRange(): IntRange? {
        val layer = selected ?: return null
        val index = layers.indexOf(layer)
        return if (layer.startsGroup) groupRange(layers, index) else index..index
    }

    private fun duplicateSelected() {
        val range = selectedRange() ?: return
        val copies = range.map { layers[it].duplicate() }
        layers.addAll(range.last + 1, copies)
        select(copies.first())
        changed()
    }

    private fun deleteSelected() {
        val range = selectedRange() ?: return
        repeat(range.count()) { layers.removeAt(range.first) }
        select(layers.getOrNull(range.first) ?: layers.getOrNull(range.first - 1))
        changed()
    }

    /**
     * +1 = up (towards the top of the stack). Effect layers move one step; image and
     * adjustment layers move together with their effects past the neighbouring group.
     */
    private fun moveSelected(delta: Int) {
        val layer = selected ?: return
        val range = selectedRange() ?: return
        if (!layer.startsGroup) {
            val to = range.first + delta
            if (to !in layers.indices) return
            layers.removeAt(range.first)
            layers.add(to, layer)
        } else {
            val block = layers.subList(range.first, range.last + 1).toList()
            val insertAt = if (delta > 0) {
                if (range.last + 1 >= layers.size) return
                groupRange(layers, range.last + 1).last + 1 - block.size
            } else {
                if (range.first == 0) return
                groupRange(layers, range.first - 1).first
            }
            repeat(block.size) { layers.removeAt(range.first) }
            layers.addAll(insertAt, block)
        }
        refreshLayers()
        canvas.refreshOverlay()
        changed()
    }

    private fun toggleVisible(layer: Layer) {
        layer.visible = !layer.visible
        refreshLayers()
        changed()
    }

    private fun select(layer: Layer?) {
        selected = layer
        canvas.layer = layer
        refreshLayers()
        rebuildEditor()
    }

    private fun refreshLayers() {
        layerList.update(layers, selected)
        timelinePanel.refresh()
    }

    /** Replaces all layers by one image layer holding the current result. */
    private fun flatten() {
        val r = result ?: return
        if (layers.size <= 1 || originalButton.isSelected) return
        layers.clear()
        val merged = ImageLayer(r).apply { name = "Merged" }
        layers.add(merged)
        select(merged)
        changed()
    }

    // ---------------------------------------------------------------- LayerEditorHost

    override fun layerChanged() {
        refreshLayers()
        // position or size of a picture moves the masks of its group
        canvas.refreshOverlay()
        changed()
    }

    override fun maskChanged() {
        canvas.refreshOverlay()
        changed()
    }

    override fun layerListChanged() = refreshLayers()

    /** Layer the editor was last built for, to keep the scroll position when it's rebuilt. */
    private var editorLayer: Layer? = null

    override fun rebuildEditor(focusMaskMode: Boolean) {
        if (editorLayer === selected) {
            val scroll = editorScroll.viewport.viewPosition
            SwingUtilities.invokeLater { editorScroll.viewport.viewPosition = scroll }
        }
        editorLayer = selected
        editorBox.removeAll()
        val editor = selected?.let { LayerEditor(it, this) }
        editor?.let { editorBox.add(it, BorderLayout.NORTH) }
        editorBox.revalidate()
        editorBox.repaint()
        if (focusMaskMode) SwingUtilities.invokeLater { editor?.focusMaskMode() }
    }

    /**
     * Something in the document changed. With [captureKeys], edits of animated settings
     * become keyframes at the current frame (not when the timeline itself moved keyframes).
     */
    private fun changed(captureKeys: Boolean = true) {
        // playback stops, but the editor isn't rebuilt: the edit may be a slider drag in progress
        if (isPlaying) stopPlayback(rebuildEditor = false)
        if (captureKeys && timeline != null && Animator.captureEdits(layers, currentFrame)) editor()?.refreshMarkers()
        frameCache.invalidate()
        timelinePanel.refresh()
        dirty = true
        requestRender()
        commitTimer.restart()
    }

    private fun editor() = editorBox.components.firstOrNull() as? LayerEditor

    // ---------------------------------------------------------------- undo

    private fun capture() =
        DocState(docWidth, docHeight, docName, layers.map { it.memento() }, timeline).apply { selectedId = selected?.id }

    private fun commitHistory() {
        // a brush stroke or handle drag becomes one step when the mouse is released
        if (canvas.isEditing) {
            commitTimer.restart()
            return
        }
        commitTimer.stop()
        if (hasDocument) history.commit(capture())
        updateUndoItems()
    }

    private fun undo() {
        if (canvas.isEditing) return
        commitHistory()
        history.undo()?.let(::restore)
    }

    private fun redo() {
        if (canvas.isEditing) return
        commitHistory()
        history.redo()?.let(::restore)
    }

    private fun restore(state: DocState) {
        val resized = state.width != docWidth || state.height != docHeight
        docWidth = state.width
        docHeight = state.height
        docName = state.name
        stopPlayback(rebuildEditor = false)
        layers.clear()
        layers.addAll(state.layers.map { it.toLayer() })
        timeline = state.timeline
        currentFrame = currentFrame.coerceIn(0, (timeline?.frames ?: 1) - 1)
        Animator.syncToFrame(layers, currentFrame)
        frameCache.invalidate()
        if (resized) {
            result = null
            resultImage = null
            showCurrentImage()
            SwingUtilities.invokeLater { canvas.setZoom(canvas.fitZoom()) }
        }
        select(layers.firstOrNull { it.id == state.selectedId } ?: layers.lastOrNull())
        dirty = true
        requestRender()
        updateUndoItems()
    }

    private fun updateUndoItems() {
        undoItem.isEnabled = history.canUndo
        redoItem.isEnabled = history.canRedo
    }

    // ---------------------------------------------------------------- rendering

    /**
     * Renders on a background thread. Requests that pile up while a render runs are
     * collapsed: only the newest one is rendered next.
     */
    /** The newest render request whose picture is on screen; older results that arrive late are not shown. */
    private var shownGeneration = 0

    private fun requestRender() {
        if (!hasDocument) return
        val width = docWidth
        val height = docHeight
        val gen = generation.incrementAndGet()
        val frame = currentFrame
        val original = originalButton.isSelected
        val states = (if (original) layers.filterIsInstance<SourceLayer>() else layers).map { it.animated().at(frame) }
        val cacheVersion = frameCache.version
        val animated = timeline != null && !original
        status.text = "Rendering…"
        renderExecutor.execute {
            if (generation.get() != gen) return@execute
            val start = System.nanoTime()
            try {
                val out = renderer.render(width, height, states)
                if (animated) frameCache.put(frame, out, cacheVersion)
                val image = out.toImage()
                val ms = (System.nanoTime() - start) / 1_000_000
                SwingUtilities.invokeLater {
                    if (gen < shownGeneration) return@invokeLater
                    shownGeneration = gen
                    result = out
                    resultImage = image
                    showCurrentImage()
                    refreshGeneratorThumbnails()
                    if (animated) timelinePanel.refreshPlayhead()
                    updateStatus(if (generation.get() == gen) "$ms ms" else "Rendering…")
                }
            } catch (e: OutOfMemoryError) {
                SwingUtilities.invokeLater { status.text = "Out of memory – start GlitchR with more memory (java -Xmx8g -jar …)" }
            } catch (e: Exception) {
                e.printStackTrace()
                SwingUtilities.invokeLater { status.text = "Error while rendering: ${e.message}" }
            }
        }
    }

    /** Pictures of the generator layers the layer list last showed, to redraw it only when one changed. */
    private var shownGenerated = emptyList<Pixels?>()

    private fun refreshGeneratorThumbnails() {
        val generated = layers.filterIsInstance<GeneratorLayer>().map { renderer.generated(it.id) }
        if (generated.size == shownGenerated.size && generated.indices.all { generated[it] === shownGenerated[it] }) return
        shownGenerated = generated
        refreshLayers()
    }

    private fun showCurrentImage() {
        canvas.image = resultImage
    }

    private fun updateStatus(extra: String? = null) {
        status.text = if (!hasDocument) " " else buildString {
            append("${shortName(docName, 48)}  ·  $docWidth × $docHeight px  ·  ${(canvas.zoom * 100).toInt()} %")
            append("  ·  ${layers.size} layer${if (layers.size == 1) "" else "s"}")
            if (extra != null) append("  ·  $extra")
        }
    }

    // ---------------------------------------------------------------- animation

    override val timelineLayers: List<Layer> get() = layers
    override val selectedLayer get() = selected
    override fun selectLayer(layer: Layer) = select(layer)
    override fun isCached(frame: Int) = frameCache.contains(frame)

    /** Moves the playhead; the layers show their settings at that frame. */
    override fun seek(frame: Int) {
        val t = timeline ?: return
        if (isPlaying) stopPlayback()
        val f = frame.coerceIn(0, t.frames - 1)
        if (f == currentFrame) return
        showFrame(f)
        editorFollowTimer.restart()
    }

    private fun showFrame(frame: Int) {
        currentFrame = frame
        Animator.syncToFrame(layers, frame)
        canvas.refreshOverlay()
        val cached = if (originalButton.isSelected) null else frameCache.get(frame)
        if (cached != null) display(cached) else requestRender()
        timelinePanel.refreshPlayhead()
    }

    /** Shows an already rendered frame; renders still running for older requests won't replace it. */
    private fun display(frame: Pixels) {
        shownGeneration = generation.incrementAndGet()
        result = frame
        resultImage = frame.toImage()
        showCurrentImage()
    }

    override fun animHook(layer: Layer, key: String) = AnimHook(
        state = {
            val track = layer.tracks[key]
            when {
                track == null -> KeyState.NONE
                track.keyAt(currentFrame) != null -> KeyState.KEY
                else -> KeyState.ANIMATED
            }
        },
        toggle = {
            if (layer.tracks[key]?.keyAt(currentFrame) != null) Animator.removeKey(layer, key, currentFrame) else addKeyframe(layer, key)
            keysEdited()
        },
        menu = { keyMenu(layer, key) },
    )

    /** Adds a keyframe with the setting's current value; without an animation, a default one is created first. */
    private fun addKeyframe(layer: Layer, key: String) {
        if (!hasDocument) return
        if (timeline == null) {
            timeline = com.spielgrund.glitchr.model.Timeline.DEFAULT
            currentFrame = 0
            status.text = "Animation created: ${timeline!!.length()} at ${timeline!!.fps} fps – change it under Settings… in the timeline"
        }
        Animator.addKey(layer, key, currentFrame)
    }

    private fun keyMenu(layer: Layer, key: String) = JPopupMenu().apply {
        val track = layer.tracks[key]
        val here = track?.keyAt(currentFrame)
        val label = layer.propertyLabel(key)
        if (here == null) {
            add(JMenuItem("Add keyframe").apply {
                toolTipText = if (timeline == null) "Creates an animation (5 s, 25 fps) and animates “$label”" else "“$label” at frame ${currentFrame + 1}"
                addActionListener { addKeyframe(layer, key); keysEdited() }
            })
        } else {
            add(JMenuItem("Remove keyframe").apply {
                addActionListener { Animator.removeKey(layer, key, currentFrame); keysEdited() }
            })
        }
        if (track != null) {
            add(JMenuItem("Remove animation").apply {
                toolTipText = "Deletes all keyframes of “$label”; it keeps its current value"
                addActionListener { layer.tracks.remove(key); keysEdited() }
            })
            addSeparator()
            val previous = track.keys.lastOrNull { it.frame < currentFrame }
            val next = track.keys.firstOrNull { it.frame > currentFrame }
            add(JMenuItem("Previous keyframe").apply {
                isEnabled = previous != null
                addActionListener { previous?.let { seek(it.frame) } }
            })
            add(JMenuItem("Next keyframe").apply {
                isEnabled = next != null
                addActionListener { next?.let { seek(it.frame) } }
            })
        }
    }

    override fun keysEdited() {
        Animator.syncToFrame(layers, currentFrame)
        canvas.refreshOverlay()
        rebuildEditor()
        changed(captureKeys = false)
    }

    override fun togglePlay() = if (isPlaying) stopPlayback() else startPlayback()

    /** How many full-size frames the cache may hold: about a third of the memory Java may use. */
    private fun cacheBudget(): Int {
        val bytes = docWidth.toLong() * docHeight * 4
        return (Runtime.getRuntime().maxMemory() * 0.35 / bytes).toInt().coerceIn(4, 5000)
    }

    /**
     * Plays the animation. Frames are rendered ahead on their own thread; playback waits for a
     * frame that isn't ready yet, so the first run is as fast as the effects allow and every
     * further loop plays at the full frame rate.
     */
    private fun startPlayback() {
        val t = timeline ?: return
        if (!hasDocument || isPlaying) return
        if (originalButton.isSelected) {
            originalButton.isSelected = false
            requestRender()
        }
        commitHistory()
        if (!loopPlayback && currentFrame >= t.frames - 1) showFrame(0)
        isPlaying = true
        frameCache.budget = cacheBudget()
        val snapshots = layers.map { it.animated() }
        val version = frameCache.version
        val width = docWidth
        val height = docHeight
        animExecutor.execute { prerender(snapshots, width, height, t, version) }
        playTimer = javax.swing.Timer(maxOf(1, 1000 / t.fps)) { tick() }.apply {
            initialDelay = 0
            start()
        }
        timelinePanel.refreshPlayhead()
    }

    private fun prerender(snapshots: List<com.spielgrund.glitchr.model.AnimatedLayer>, width: Int, height: Int, t: com.spielgrund.glitchr.model.Timeline, version: Int) {
        try {
            while (isPlaying && frameCache.version == version) {
                val from = currentFrame
                val next = (0 until t.frames).map { (from + it) % t.frames }.firstOrNull { !frameCache.contains(it) } ?: break
                // never further ahead than the cache holds, or it would push out the frames about to be played
                if ((next - from + t.frames) % t.frames >= frameCache.budget - 1) {
                    Thread.sleep(15)
                    continue
                }
                val out = animRenderer.render(width, height, snapshots.map { it.at(next) })
                frameCache.put(next, out, version)
                SwingUtilities.invokeLater { timelinePanel.refreshPlayhead() }
            }
        } catch (e: OutOfMemoryError) {
            SwingUtilities.invokeLater {
                stopPlayback()
                status.text = "Out of memory – start GlitchR with more memory (java -Xmx8g -jar …)"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            SwingUtilities.invokeLater {
                stopPlayback()
                status.text = "Error while rendering: ${e.message}"
            }
        }
    }

    private fun tick() {
        val t = timeline ?: return stopPlayback()
        val next = when {
            currentFrame + 1 < t.frames -> currentFrame + 1
            loopPlayback -> 0
            else -> return stopPlayback()
        }
        val frame = frameCache.get(next)
        if (frame == null) {
            status.text = "Rendering frame ${next + 1} of ${t.frames}…"
            return
        }
        currentFrame = next
        // the fields follow, so an edit during playback is compared with the right frame
        Animator.syncToFrame(layers, next)
        display(frame)
        timelinePanel.refreshPlayhead()
        updateStatus("▶ ${t.fps} fps")
    }

    private fun stopPlayback(rebuildEditor: Boolean = true) {
        if (!isPlaying) return
        isPlaying = false
        playTimer?.stop()
        playTimer = null
        Animator.syncToFrame(layers, currentFrame)
        canvas.refreshOverlay()
        if (rebuildEditor) rebuildEditor()
        timelinePanel.refreshPlayhead()
        updateStatus()
    }

    override fun createAnimation() {
        if (!hasDocument) {
            status.text = "Open an image or create a canvas first"
            return
        }
        if (timeline != null) return animationSettings()
        val t = timelineDialog("Create animation", com.spielgrund.glitchr.model.Timeline.DEFAULT) ?: return
        timeline = t
        currentFrame = 0
        Animator.syncToFrame(layers, 0)
        rebuildEditor()
        changed(captureKeys = false)
        status.text = "Animation created – right-click any setting → Add keyframe"
    }

    override fun animationSettings() {
        val old = timeline ?: return createAnimation()
        val t = timelineDialog("Animation settings", old) ?: return
        stopPlayback()
        Animator.rescale(layers, old.fps, t.fps)
        timeline = t
        currentFrame = (currentFrame.toLong() * t.fps / old.fps).toInt().coerceIn(0, t.frames - 1)
        keysEdited()
    }

    private fun removeAnimation() {
        if (timeline == null) return
        val answer = JOptionPane.showConfirmDialog(
            this, "Remove the timeline and all keyframes? Every setting keeps its value at the current frame.",
            "Remove animation", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.OK_OPTION) return
        stopPlayback()
        for (layer in layers) layer.tracks.clear()
        timeline = null
        currentFrame = 0
        rebuildEditor()
        changed(captureKeys = false)
    }

    override fun setBeats(beats: Boolean) {
        val t = timeline ?: return
        if (t.beats == beats) return
        // only the display changes: the frames and everything rendered stay valid
        timeline = t.copy(beats = beats)
        timelinePanel.refresh()
        dirty = true
        commitTimer.restart()
    }

    /**
     * Asks for length, frame rate and time base; null if cancelled. In seconds the length
     * is given in seconds, in beats in bars at the tempo (BPM) and time signature.
     */
    private fun timelineDialog(title: String, current: com.spielgrund.glitchr.model.Timeline): com.spielgrund.glitchr.model.Timeline? {
        val timeBase = javax.swing.JComboBox(arrayOf("Seconds (fps)", "Bars & beats (BPM)")).apply {
            selectedIndex = if (current.beats) 1 else 0
        }
        val length = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(if (current.beats) current.bars else current.seconds, 0.01, 10_000.0, 0.5)).apply {
            editor = javax.swing.JSpinner.NumberEditor(this, "0.00")
        }
        val bpm = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(current.bpm, 1.0, 999.0, 1.0)).apply {
            editor = javax.swing.JSpinner.NumberEditor(this, "0.##")
        }
        val beatsPerBar = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(current.beatsPerBar, 1, 32, 1))
        val fpsBox = javax.swing.JComboBox(arrayOf(8, 10, 12, 15, 20, 24, 25, 30, 50, 60)).apply {
            isEditable = true
            selectedItem = current.fps
        }
        val lengthLabel = JLabel()
        val framesLabel = JLabel()
        fun beats() = timeBase.selectedIndex == 1
        fun fps() = (fpsBox.editor.item?.toString() ?: fpsBox.selectedItem?.toString())?.trim()?.toIntOrNull()?.coerceIn(1, 240) ?: current.fps
        fun result() = com.spielgrund.glitchr.model.Timeline(fps(), 1, bpm.value as Double, beatsPerBar.value as Int, beats()).let { t ->
            val l = length.value as Double
            t.copy(frames = if (t.beats) t.framesForBars(l) else Math.round(l * t.fps).toInt().coerceAtLeast(1))
        }
        fun update() {
            val t = result()
            lengthLabel.text = if (beats()) "Length (bars)" else "Length (seconds)"
            bpm.isEnabled = beats()
            beatsPerBar.isEnabled = beats()
            framesLabel.text = if (beats()) "= ${t.frames} frames · ${timecode(t.frames, t.fps)}" else "= ${t.frames} frames"
        }
        // switching the time base keeps the length and converts the number
        var wasBeats = beats()
        timeBase.addActionListener {
            if (beats() != wasBeats) {
                val l = length.value as Double
                val t = result().let { it.copy(frames = if (wasBeats) it.framesForBars(l) else Math.round(l * it.fps).toInt().coerceAtLeast(1)) }
                wasBeats = beats()
                length.value = (if (wasBeats) t.bars else t.seconds).coerceAtLeast(0.01)
            }
            update()
        }
        length.addChangeListener { update() }
        bpm.addChangeListener { update() }
        beatsPerBar.addChangeListener { update() }
        fpsBox.addActionListener { update() }
        update()
        val form = JPanel(java.awt.GridBagLayout())
        val c = java.awt.GridBagConstraints().apply {
            insets = java.awt.Insets(3, 3, 3, 3)
            anchor = java.awt.GridBagConstraints.WEST
            fill = java.awt.GridBagConstraints.HORIZONTAL
        }
        val rows = listOf(
            JLabel("Time in") to timeBase, lengthLabel to length, JLabel("Tempo (BPM)") to bpm,
            JLabel("Beats per bar") to beatsPerBar, JLabel("Frames per second") to fpsBox, JLabel() to framesLabel,
        )
        for ((row, pair) in rows.withIndex()) {
            c.gridy = row
            c.gridx = 0
            form.add(pair.first, c)
            c.gridx = 1
            form.add(pair.second, c)
        }
        val answer = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (answer != JOptionPane.OK_OPTION) return null
        return result()
    }

    override fun exportAnimation() {
        val t = timeline
        if (t == null || !hasDocument) {
            status.text = "No animation yet – create one in the timeline first"
            return
        }
        stopPlayback()
        commitHistory()
        val settings = exportDialog(t) ?: return
        val ext = settings.format.extension
        val chooser = JFileChooser(prefs.get(PREF_DIR, null)).apply {
            dialogTitle = "Export animation as ${settings.format.label}"
            fileFilter = FileNameExtensionFilter(settings.format.label, ext)
            selectedFile = File(currentDirectory, "${docName}_glitch.$ext")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.extension.equals(ext, ignoreCase = true)) file = File(file.parentFile, "${file.name}.$ext")
        val check = if (settings.format == com.spielgrund.glitchr.export.AnimFormat.PNG)
            File(file.parentFile, "${file.nameWithoutExtension}_${"1".padStart(maxOf(4, t.frames.toString().length), '0')}.png") else file
        if (check.exists() && JOptionPane.showConfirmDialog(this, "Overwrite \"${check.name}\"?", "Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        prefs.put(PREF_DIR, file.parent ?: "")

        val monitor = javax.swing.ProgressMonitor(this, "Exporting animation…", "", 0, t.frames).apply {
            millisToDecideToPopup = 0
            millisToPopup = 0
        }
        val snapshots = layers.map { it.animated() }
        val version = frameCache.version
        val width = docWidth
        val height = docHeight
        val target = file
        Thread({
            val exportRenderer = Renderer()
            var cancelled = false
            try {
                val written = com.spielgrund.glitchr.export.AnimationExport.export(
                    t.frames, t.fps, settings, target,
                    render = { f ->
                        // frames played before are reused, as long as nothing changed since
                        frameCache.get(f)?.takeIf { frameCache.version == version } ?: exportRenderer.render(width, height, snapshots.map { it.at(f) })
                    },
                    progress = { n ->
                        SwingUtilities.invokeLater {
                            monitor.setProgress(n)
                            monitor.note = "Frame $n of ${t.frames}"
                        }
                        cancelled = monitor.isCanceled
                        !cancelled
                    },
                )
                SwingUtilities.invokeLater {
                    monitor.close()
                    if (cancelled) {
                        // a half-written GIF or MP4 is of no use
                        if (settings.format != com.spielgrund.glitchr.export.AnimFormat.PNG) written.forEach { it.delete() }
                        status.text = "Export cancelled"
                    } else {
                        status.text = "Exported: ${written.first().absolutePath}" + if (written.size > 1) " … (${written.size} files)" else ""
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                SwingUtilities.invokeLater {
                    monitor.close()
                    JOptionPane.showMessageDialog(this, "Export failed:\n${e.message ?: e.javaClass.simpleName}", "Export", JOptionPane.ERROR_MESSAGE)
                }
            }
        }, "glitch-export").apply { isDaemon = true }.start()
    }

    /** Asks for format, size and the format's options; null if cancelled. */
    private fun exportDialog(t: com.spielgrund.glitchr.model.Timeline): com.spielgrund.glitchr.export.ExportSettings? {
        val formats = com.spielgrund.glitchr.export.AnimFormat.entries.toTypedArray()
        val formatBox = javax.swing.JComboBox(formats).apply {
            selectedItem = formats.firstOrNull { it.name == prefs.get("animFormat", "") } ?: formats[0]
        }
        val scales = listOf(100, 75, 50, 33, 25)
        val sizeBox = javax.swing.JComboBox(scales.map { s ->
            "$s %  (${Math.round(docWidth * s / 100.0)} × ${Math.round(docHeight * s / 100.0)} px)"
        }.toTypedArray()).apply { selectedIndex = scales.indexOf(prefs.getInt("animScale", 100)).coerceAtLeast(0) }
        val backgroundBox = javax.swing.JComboBox(arrayOf("Black", "White")).apply { selectedIndex = prefs.getInt("animBackground", 0) }
        val dither = javax.swing.JCheckBox("Dithering", prefs.getBoolean("animDither", true)).apply {
            toolTipText = "GIF has only 256 colors: dithering mixes them into finer gradients (larger files)"
        }
        val loop = javax.swing.JCheckBox("Loop forever", prefs.getBoolean("animLoop", true))
        fun update() {
            val f = formatBox.selectedItem
            backgroundBox.isEnabled = f != com.spielgrund.glitchr.export.AnimFormat.PNG
            dither.isEnabled = f == com.spielgrund.glitchr.export.AnimFormat.GIF
            loop.isEnabled = f == com.spielgrund.glitchr.export.AnimFormat.GIF
        }
        formatBox.addActionListener { update() }
        update()
        val form = JPanel(java.awt.GridBagLayout())
        val c = java.awt.GridBagConstraints().apply {
            insets = java.awt.Insets(3, 3, 3, 3)
            anchor = java.awt.GridBagConstraints.WEST
            fill = java.awt.GridBagConstraints.HORIZONTAL
        }
        val rows = listOf(
            "Format" to formatBox, "Size" to sizeBox, "Background" to backgroundBox, "" to dither, "" to loop,
            "" to JLabel("<html><span style='color:gray'>${t.frames} frames · ${t.fps} fps · ${timecode(t.frames, t.fps)}${if (t.beats) " · " + t.length() + " at " + bpmText(t.bpm) + " BPM" else ""}<br>" +
                "Background: under transparent parts (GIF and MP4 have none; PNG keeps it)</span></html>"),
        )
        for ((row, pair) in rows.withIndex()) {
            c.gridy = row
            c.gridx = 0
            form.add(JLabel(pair.first), c)
            c.gridx = 1
            form.add(pair.second, c)
        }
        val answer = JOptionPane.showConfirmDialog(this, form, "Export animation", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (answer != JOptionPane.OK_OPTION) return null
        val format = formatBox.selectedItem as com.spielgrund.glitchr.export.AnimFormat
        prefs.put("animFormat", format.name)
        prefs.putInt("animScale", scales[sizeBox.selectedIndex])
        prefs.putInt("animBackground", backgroundBox.selectedIndex)
        prefs.putBoolean("animDither", dither.isSelected)
        prefs.putBoolean("animLoop", loop.isSelected)
        return com.spielgrund.glitchr.export.ExportSettings(
            format = format,
            scale = scales[sizeBox.selectedIndex] / 100.0,
            background = if (backgroundBox.selectedIndex == 1) 0xFFFFFF else 0x000000,
            dither = dither.isSelected,
            loop = loop.isSelected,
        )
    }

    // ---------------------------------------------------------------- files

    /**
     * Opens a `.glitchr` project; an image becomes a new document when none is open,
     * otherwise a new image layer (drag & drop, command line).
     */
    fun open(file: File) {
        if (ProjectFile.isProject(file)) {
            openProject(file)
            return
        }
        val img = readImage(file) ?: return
        if (hasDocument) addImageLayer(img, file.nameWithoutExtension) else newDocument(img, file.nameWithoutExtension)
    }

    private fun readImage(file: File): BufferedImage? = try {
        (ImageIO.read(file) ?: throw IllegalArgumentException("Unknown image format")).also {
            prefs.put(PREF_DIR, file.parent ?: "")
        }
    } catch (e: Exception) {
        JOptionPane.showMessageDialog(this, "“${file.name}” could not be opened:\n${e.message}", "Open", JOptionPane.ERROR_MESSAGE)
        null
    }

    /** Starts a new document with [img] as its only layer; the canvas gets the picture's size. */
    private fun newDocument(img: BufferedImage, name: String) =
        newDocument(img.width, img.height, name, ImageLayer(Pixels.of(img)).apply { this.name = shortName(name) })

    /** Starts a new document of the given canvas size with [first] as its only layer, or none. */
    private fun newDocument(width: Int, height: Int, name: String, first: Layer?) {
        commitTimer.stop()
        history.clear()
        stopPlayback(rebuildEditor = false)
        layers.clear()
        timeline = null
        currentFrame = 0
        frameCache.invalidate()
        docWidth = width
        docHeight = height
        docName = name
        projectFile = null
        result = null
        resultImage = null
        originalButton.isSelected = false
        first?.let(layers::add)
        select(first)
        showCurrentImage()
        SwingUtilities.invokeLater {
            canvas.setZoom(canvas.fitZoom())
            canvas.refreshOverlay()
        }
        requestRender()
        history.commit(capture())
        updateUndoItems()
        dirty = false
        updateStatus()
    }

    /**
     * Closes the document and returns to the empty start: the next image opened, dropped
     * or pasted starts a new document of its own size.
     */
    private fun closeDocument() {
        if (!hasDocument || !confirmDiscard()) return
        commitTimer.stop()
        generation.incrementAndGet() // renders still running won't show up anymore
        history.clear()
        stopPlayback(rebuildEditor = false)
        layers.clear()
        timeline = null
        currentFrame = 0
        frameCache.invalidate()
        docWidth = 0
        docHeight = 0
        docName = "image"
        projectFile = null
        result = null
        resultImage = null
        originalButton.isSelected = false
        select(null)
        showCurrentImage()
        canvas.refreshOverlay()
        updateUndoItems()
        dirty = false
        updateStatus()
        status.text = "Closed – paste (Ctrl+V), open or drop an image to start"
    }

    /**
     * Asks for the size of a new, empty canvas and what to start it with (nothing or a
     * generator; [preset] preselects one). The size is remembered for next time.
     */
    private fun newDialog(preset: Generator? = null) {
        val limit = 30_000
        fun spinner(value: Int) = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(value.coerceIn(1, limit), 1, limit, 1))
        val width = spinner(prefs.getInt(PREF_NEW_WIDTH, 1920))
        val height = spinner(prefs.getInt(PREF_NEW_HEIGHT, 1080))
        val sizes = listOf(
            "Custom size" to null, "HD 1920 × 1080" to (1920 to 1080), "4K 3840 × 2160" to (3840 to 2160),
            "Square 1080 × 1080" to (1080 to 1080), "Square 2048 × 2048" to (2048 to 2048),
            "Portrait 1080 × 1920" to (1080 to 1920), "A4 300 dpi 2480 × 3508" to (2480 to 3508),
        )
        val sizeBox = javax.swing.JComboBox(sizes.map { it.first }.toTypedArray()).apply {
            addActionListener {
                sizes[selectedIndex].second?.let { (w, h) -> width.value = w; height.value = h }
            }
        }
        val contents = listOf("Empty (transparent)") + Generators.all.map { it.name }
        val contentBox = javax.swing.JComboBox(contents.toTypedArray()).apply {
            selectedIndex = preset?.let { Generators.all.indexOf(it) + 1 } ?: 0
        }
        val form = JPanel(java.awt.GridBagLayout())
        val c = java.awt.GridBagConstraints().apply {
            insets = java.awt.Insets(3, 3, 3, 3)
            anchor = java.awt.GridBagConstraints.WEST
            fill = java.awt.GridBagConstraints.HORIZONTAL
        }
        val rows = listOf("Preset" to sizeBox, "Width (px)" to width, "Height (px)" to height, "Content" to contentBox)
        for ((row, pair) in rows.withIndex()) {
            c.gridy = row
            c.gridx = 0
            c.weightx = 0.0
            form.add(JLabel(pair.first), c)
            c.gridx = 1
            c.weightx = 1.0
            form.add(pair.second, c)
        }
        val answer = JOptionPane.showConfirmDialog(this, form, "New canvas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (answer != JOptionPane.OK_OPTION || !confirmDiscard()) return
        val w = width.value as Int
        val h = height.value as Int
        prefs.putInt(PREF_NEW_WIDTH, w)
        prefs.putInt(PREF_NEW_HEIGHT, h)
        val generator = Generators.all.getOrNull(contentBox.selectedIndex - 1)
        newDocument(w, h, generator?.name?.lowercase() ?: "new", generator?.let(::GeneratorLayer))
    }

    private fun imageChooser(title: String, withProjects: Boolean) = JFileChooser(prefs.get(PREF_DIR, null)).apply {
        dialogTitle = title
        val suffixes = ImageIO.getReaderFileSuffixes()
        if (withProjects) {
            addChoosableFileFilter(FileNameExtensionFilter("Images and GlitchR projects", ProjectFile.EXTENSION, *suffixes))
            addChoosableFileFilter(FileNameExtensionFilter("GlitchR project (.${ProjectFile.EXTENSION})", ProjectFile.EXTENSION))
        }
        addChoosableFileFilter(FileNameExtensionFilter("Images", *suffixes))
        fileFilter = choosableFileFilters[1]
        isMultiSelectionEnabled = !withProjects
    }

    /** Opens a project, or an image as a new document. */
    private fun openDialog() {
        val chooser = imageChooser("Open", withProjects = true)
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        if (ProjectFile.isProject(file)) {
            openProject(file)
            return
        }
        if (!confirmDiscard()) return
        readImage(file)?.let { newDocument(it, file.nameWithoutExtension) }
    }

    /** Adds one or more pictures as image layers (or starts a document with the first). */
    private fun insertImageDialog() {
        val chooser = imageChooser("Insert image as layer", withProjects = false)
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        chooser.selectedFiles.ifEmpty { arrayOf(chooser.selectedFile) }.forEach(::open)
    }

    private fun exportDialog() {
        val out = result ?: return
        val png = FileNameExtensionFilter("PNG", "png")
        val jpg = FileNameExtensionFilter("JPEG (95 %)", "jpg", "jpeg")
        val chooser = JFileChooser(prefs.get(PREF_DIR, null)).apply {
            addChoosableFileFilter(png)
            addChoosableFileFilter(jpg)
            fileFilter = png
            selectedFile = File(currentDirectory, "${docName}_glitch.png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        val ext = file.extension.lowercase()
        if (ext !in setOf("png", "jpg", "jpeg")) {
            file = File(file.path + if (chooser.fileFilter == jpg) ".jpg" else ".png")
        }
        if (file.exists() && JOptionPane.showConfirmDialog(this, "Overwrite \"${file.name}\"?", "Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        try {
            if (file.extension.lowercase() == "png") ImageIO.write(out.toImage(), "png", file)
            else writeJpeg(onWhite(out), file)
            prefs.put(PREF_DIR, file.parent ?: "")
            status.text = "Exported: ${file.absolutePath}"
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Saving failed:\n${e.message}", "Export", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun onWhite(p: Pixels) = ImageClipboard.onWhite(p)

    private fun writeJpeg(img: BufferedImage, file: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(file).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.95f
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
    }

    private fun paste() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        try {
            when {
                clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ->
                    (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().forEach(::open)
                else -> {
                    // PNG first, so transparency from other programs survives
                    val img = ImageClipboard.read(clipboard)
                    if (img == null) status.text = "No image on the clipboard"
                    else if (hasDocument) addImageLayer(img, "Pasted") else newDocument(img, "pasted")
                }
            }
        } catch (e: Exception) {
            status.text = "Paste failed: ${e.message}"
        }
    }

    /** The result to the clipboard: as PNG with transparency, and on white for programs without PNG. */
    private fun copy() {
        val out = result ?: return
        ImageClipboard.copy(out, Toolkit.getDefaultToolkit().systemClipboard)
        status.text = "Result copied to the clipboard (with transparency)"
    }

    /** Asks to save unsaved changes; false means the user cancelled. */
    private fun confirmDiscard(): Boolean {
        if (!dirty || !hasDocument) return true
        val answer = JOptionPane.showConfirmDialog(
            this, "Save changes to “${projectName()}”?", "GlitchR",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
        )
        return when (answer) {
            JOptionPane.YES_OPTION -> save()
            JOptionPane.NO_OPTION -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------- projects

    private fun projectName() = projectFile?.name ?: "Untitled"

    private fun updateTitle() {
        title = "${if (dirty) "● " else ""}${projectName()} – ${shortName(docName, 48)} – GlitchR"
    }

    private fun openProject(file: File) {
        if (!confirmDiscard()) return
        val state = try {
            ProjectFile.load(file)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "“${file.name}” could not be opened:\n${e.message}", "Open", JOptionPane.ERROR_MESSAGE)
            return
        }
        prefs.put(PREF_DIR, file.parent ?: "")
        commitTimer.stop()
        history.clear()
        originalButton.isSelected = false
        restore(state)
        history.commit(capture())
        updateUndoItems()
        projectFile = file
        dirty = false
        SwingUtilities.invokeLater {
            canvas.setZoom(canvas.fitZoom())
            canvas.refreshOverlay()
        }
        status.text = "Opened: ${file.absolutePath}"
    }

    private fun save(): Boolean = projectFile?.let(::writeProject) ?: saveAs()

    private fun saveAs(): Boolean {
        if (!hasDocument) return false
        val chooser = JFileChooser(projectFile?.parentFile ?: prefs.get(PREF_DIR, null)?.let(::File)).apply {
            dialogTitle = "Save project as"
            fileFilter = FileNameExtensionFilter("GlitchR project (.${ProjectFile.EXTENSION})", ProjectFile.EXTENSION)
            selectedFile = projectFile ?: File(currentDirectory, "$docName.${ProjectFile.EXTENSION}")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false
        val chosen = chooser.selectedFile
        val file = if (ProjectFile.isProject(chosen)) chosen else File(chosen.parentFile, "${chosen.name}.${ProjectFile.EXTENSION}")
        if (file.exists() && file != projectFile) {
            val answer = JOptionPane.showConfirmDialog(this, "“${file.name}” already exists. Replace it?", "GlitchR", JOptionPane.YES_NO_OPTION)
            if (answer != JOptionPane.YES_OPTION) return false
        }
        return writeProject(file)
    }

    private fun writeProject(file: File): Boolean {
        if (!hasDocument) return false
        commitHistory()
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR)
        return try {
            ProjectFile.save(capture(), file)
            projectFile = file
            dirty = false
            prefs.put(PREF_DIR, file.parent ?: "")
            status.text = "Saved: ${file.absolutePath}"
            true
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Saving failed:\n${e.message}", "GlitchR", JOptionPane.ERROR_MESSAGE)
            false
        } finally {
            cursor = java.awt.Cursor.getDefaultCursor()
        }
    }

    private inner class FileDropHandler : TransferHandler() {
        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val files = (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
            if (files.isEmpty()) return false
            // a project replaces everything; pictures are all added as layers
            SwingUtilities.invokeLater { files.forEach(::open) }
            return true
        }
    }
}
