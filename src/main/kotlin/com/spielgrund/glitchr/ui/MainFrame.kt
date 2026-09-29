package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.History
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskSpace
import com.spielgrund.glitchr.model.groupRange
import com.spielgrund.glitchr.model.Renderer
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

class MainFrame : JFrame("GlitchR"), LayerEditorHost {
    override val brush = Brush()
    private val canvas = GlitchCanvas(brush)
    private val layerList = LayerList(::select, ::toggleVisible)
    private val editorBox = WidthTrackingPanel()
    private val status = JLabel(" ")
    private val originalButton = JToggleButton("Original")
    private val showMaskItem = JCheckBoxMenuItem("Maske anzeigen")
    private val showMaskButton = JToggleButton("Maske zeigen")
    private val editorScroll = JScrollPane(editorBox)
    private val undoItem = item("Rückgängig", KeyEvent.VK_Z) { undo() }
    private val redoItem = item("Wiederholen", KeyEvent.VK_Y) { redo() }

    private val history = History()

    /** Edits become an undo step once nothing has changed for a moment, so a slider drag is one step. */
    private val commitTimer = javax.swing.Timer(500) { commitHistory() }.apply { isRepeats = false }

    /** Bottom layer first. */
    private val layers = mutableListOf<Layer>()
    private var selected: Layer? = null

    /** Canvas size; 0 while no document is open. */
    private var docWidth = 0
    private var docHeight = 0
    private var docName = "bild"
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
     * picture and has the picture's resolution. Layers without picture use the canvas.
     */
    override val maskTarget: MaskTarget?
        get() {
            if (!hasDocument) return null
            val index = layers.indexOf(selected ?: return null)
            if (index < 0) return null
            val image = layers[groupRange(layers, index).first] as? ImageLayer
                ?: return MaskTarget(MaskSpace.canvas(docWidth, docHeight), docWidth, docHeight, null)
            return MaskTarget(Renderer.spaceOf(image.state()), image.image.width, image.image.height, image.image)
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

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, sidebar).apply {
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
    private fun toolbar() = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        add(small("Öffnen…", "Bild oder Projekt öffnen (Strg+O)") { openDialog() })
        add(small("+ Bild…", "Bild als neue Ebene einfügen (Strg+I) – oder einfach ins Fenster ziehen") { insertImageDialog() })
        add(small("Speichern", "Projekt mit allen Ebenen und Masken speichern (Strg+S)") { save() })
        add(small("Exportieren…", "Ergebnis als Bild speichern (Strg+E)") { exportDialog() })
        add(small("Einpassen", "Ganzes Bild zeigen (Strg+0)") { canvas.setZoom(canvas.fitZoom()) })
        add(originalButton.apply {
            isFocusable = false
            toolTipText = "Zeigt die Bildebenen ohne Effekte (Strg+B)"
            addActionListener { requestRender() }
        })
        add(showMaskButton.apply {
            isFocusable = false
            toolTipText = "Rote Markierung der Maske ein-/ausblenden; die Maske wirkt trotzdem (Strg+M)"
            addActionListener { showMask = isSelected; rebuildEditor() }
        })
    }

    private fun layerPanel(): JPanel {
        val addButton = JButton("+ Effekt ▾").apply { isFocusable = false }
        val popup = JPopupMenu().apply {
            for (effect in Effects.all) add(JMenuItem(effect.name).apply {
                toolTipText = effect.description
                addActionListener { addLayer(effect) }
            })
        }
        addButton.addActionListener { popup.show(addButton, 0, addButton.height) }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(addButton)
            add(small("▲", "Ebene nach oben (Strg+Bild↑)") { moveSelected(1) })
            add(small("▼", "Ebene nach unten (Strg+Bild↓)") { moveSelected(-1) })
            add(small("⧉", "Ebene duplizieren (Strg+J)") { duplicateSelected() })
            add(small("✕", "Ebene löschen (Entf)") { deleteSelected() })
        }
        return JPanel(BorderLayout()).apply {
            add(JLabel("Ebenen").apply {
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
        add(JMenu("Datei").apply {
            add(item("Öffnen…", KeyEvent.VK_O) { openDialog() })
            add(item("Bild als Ebene einfügen…", KeyEvent.VK_I) { insertImageDialog() })
            add(item("Aus Zwischenablage einfügen", KeyEvent.VK_V) { paste() })
            addSeparator()
            add(item("Projekt speichern", KeyEvent.VK_S) { save() })
            add(item("Projekt speichern unter…", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK) { saveAs() })
            addSeparator()
            add(item("Bild exportieren…", KeyEvent.VK_E) { exportDialog() })
            add(item("Ergebnis kopieren", KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK) { copy() })
            addSeparator()
            add(JMenuItem("Beenden").apply { addActionListener { if (confirmDiscard()) exitProcess(0) } })
        })
        add(JMenu("Bearbeiten").apply {
            add(undoItem)
            add(redoItem)
        })
        add(JMenu("Ebene").apply {
            add(JMenuItem("Bild als Ebene einfügen…").apply { addActionListener { insertImageDialog() } })
            add(JMenu("Effekt hinzufügen").apply {
                for (effect in Effects.all) add(JMenuItem(effect.name).apply { addActionListener { addLayer(effect) } })
            })
            add(item("Duplizieren", KeyEvent.VK_J) { duplicateSelected() })
            add(JMenuItem("Löschen").apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
                addActionListener { deleteSelected() }
            })
            addSeparator()
            add(item("Nach oben", KeyEvent.VK_PAGE_UP) { moveSelected(1) })
            add(item("Nach unten", KeyEvent.VK_PAGE_DOWN) { moveSelected(-1) })
            addSeparator()
            add(JMenuItem("Alle Ebenen aufs Bild anwenden").apply {
                toolTipText = "Das Ergebnis wird zu einer einzigen Bildebene, alle Ebenen werden ersetzt"
                addActionListener { flatten() }
            })
        })
        add(JMenu("Ansicht").apply {
            add(item("Einpassen", KeyEvent.VK_0) { canvas.setZoom(canvas.fitZoom()) })
            add(item("100 %", KeyEvent.VK_1) { canvas.setZoom(1.0) })
            add(item("Vergrössern", KeyEvent.VK_PLUS) { canvas.zoomIn() })
            add(item("Verkleinern", KeyEvent.VK_MINUS) { canvas.zoomOut() })
            addSeparator()
            add(showMaskItem.apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_M, SHORTCUT)
                addActionListener { showMask = isSelected; rebuildEditor() }
            })
            add(item("Original zeigen", KeyEvent.VK_B) {
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

    private fun addLayer(effect: Effect) {
        val layer = EffectLayer(effect)
        val index = selected?.let { layers.indexOf(it) + 1 } ?: layers.size
        layers.add(index, layer)
        select(layer)
        changed()
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
        status.text = "Bildebene „${shortName(name)}“ eingefügt"
    }

    /** The selected layer's index range: for an image layer its whole group (with its effects). */
    private fun selectedRange(): IntRange? {
        val layer = selected ?: return null
        val index = layers.indexOf(layer)
        return if (layer is ImageLayer) groupRange(layers, index) else index..index
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
     * +1 = up (towards the top of the stack). Effect layers move one step; image layers
     * move together with their effects past the neighbouring group.
     */
    private fun moveSelected(delta: Int) {
        val layer = selected ?: return
        val range = selectedRange() ?: return
        if (layer is EffectLayer) {
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

    private fun refreshLayers() = layerList.update(layers, selected)

    /** Replaces all layers by one image layer holding the current result. */
    private fun flatten() {
        val r = result ?: return
        if (layers.size <= 1 || originalButton.isSelected) return
        layers.clear()
        val merged = ImageLayer(r).apply { name = "Zusammengefügt" }
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

    private fun changed() {
        dirty = true
        requestRender()
        commitTimer.restart()
    }

    // ---------------------------------------------------------------- undo

    private fun capture() = DocState(docWidth, docHeight, docName, layers.map { it.memento() }).apply { selectedId = selected?.id }

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
        layers.clear()
        layers.addAll(state.layers.map { it.toLayer() })
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
    private fun requestRender() {
        if (!hasDocument) return
        val width = docWidth
        val height = docHeight
        val gen = generation.incrementAndGet()
        val states = (if (originalButton.isSelected) layers.filterIsInstance<ImageLayer>() else layers).map { it.state() }
        status.text = "Berechne…"
        renderExecutor.execute {
            if (generation.get() != gen) return@execute
            val start = System.nanoTime()
            try {
                val out = renderer.render(width, height, states)
                val image = out.toImage()
                val ms = (System.nanoTime() - start) / 1_000_000
                SwingUtilities.invokeLater {
                    result = out
                    resultImage = image
                    showCurrentImage()
                    updateStatus(if (generation.get() == gen) "$ms ms" else "Berechne…")
                }
            } catch (e: OutOfMemoryError) {
                SwingUtilities.invokeLater { status.text = "Zu wenig Speicher – GlitchR mit mehr Speicher starten (java -Xmx8g -jar …)" }
            } catch (e: Exception) {
                e.printStackTrace()
                SwingUtilities.invokeLater { status.text = "Fehler beim Berechnen: ${e.message}" }
            }
        }
    }

    private fun showCurrentImage() {
        canvas.image = resultImage
    }

    private fun updateStatus(extra: String? = null) {
        status.text = if (!hasDocument) " " else buildString {
            append("${shortName(docName, 48)}  ·  $docWidth × $docHeight px  ·  ${(canvas.zoom * 100).toInt()} %")
            append("  ·  ${layers.size} Ebene${if (layers.size == 1) "" else "n"}")
            if (extra != null) append("  ·  $extra")
        }
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
        (ImageIO.read(file) ?: throw IllegalArgumentException("Unbekanntes Bildformat")).also {
            prefs.put(PREF_DIR, file.parent ?: "")
        }
    } catch (e: Exception) {
        JOptionPane.showMessageDialog(this, "„${file.name}“ konnte nicht geöffnet werden:\n${e.message}", "Öffnen", JOptionPane.ERROR_MESSAGE)
        null
    }

    /** Starts a new document with [img] as its only layer; the canvas gets the picture's size. */
    private fun newDocument(img: BufferedImage, name: String) {
        commitTimer.stop()
        history.clear()
        layers.clear()
        docWidth = img.width
        docHeight = img.height
        docName = name
        projectFile = null
        result = null
        resultImage = null
        originalButton.isSelected = false
        val base = ImageLayer(Pixels.of(img)).apply { this.name = shortName(name) }
        layers.add(base)
        select(base)
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

    private fun imageChooser(title: String, withProjects: Boolean) = JFileChooser(prefs.get(PREF_DIR, null)).apply {
        dialogTitle = title
        val suffixes = ImageIO.getReaderFileSuffixes()
        if (withProjects) {
            addChoosableFileFilter(FileNameExtensionFilter("Bilder und GlitchR-Projekte", ProjectFile.EXTENSION, *suffixes))
            addChoosableFileFilter(FileNameExtensionFilter("GlitchR-Projekt (.${ProjectFile.EXTENSION})", ProjectFile.EXTENSION))
        }
        addChoosableFileFilter(FileNameExtensionFilter("Bilder", *suffixes))
        fileFilter = choosableFileFilters[1]
        isMultiSelectionEnabled = !withProjects
    }

    /** Opens a project, or an image as a new document. */
    private fun openDialog() {
        val chooser = imageChooser("Öffnen", withProjects = true)
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
        val chooser = imageChooser("Bild als Ebene einfügen", withProjects = false)
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
        if (file.exists() && JOptionPane.showConfirmDialog(this, "\"${file.name}\" überschreiben?", "Exportieren", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        try {
            if (file.extension.lowercase() == "png") ImageIO.write(out.toImage(), "png", file)
            else writeJpeg(onWhite(out), file)
            prefs.put(PREF_DIR, file.parent ?: "")
            status.text = "Exportiert: ${file.absolutePath}"
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Speichern fehlgeschlagen:\n${e.message}", "Exportieren", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** The picture over white, for formats and programs without transparency. */
    private fun onWhite(p: Pixels): BufferedImage {
        val img = BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = java.awt.Color.WHITE
            fillRect(0, 0, p.width, p.height)
            drawImage(p.toImage(), 0, 0, null)
            dispose()
        }
        return img
    }

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
                clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                    val image = clipboard.getData(DataFlavor.imageFlavor) as Image
                    val img = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                    img.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                    if (hasDocument) addImageLayer(img, "Eingefügt") else newDocument(img, "eingefügt")
                }
                else -> status.text = "Kein Bild in der Zwischenablage"
            }
        } catch (e: Exception) {
            status.text = "Einfügen fehlgeschlagen: ${e.message}"
        }
    }

    private fun copy() {
        val img = result?.let(::onWhite) ?: return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
                return img
            }
        }, null)
        status.text = "Ergebnis in die Zwischenablage kopiert"
    }

    /** Asks to save unsaved changes; false means the user cancelled. */
    private fun confirmDiscard(): Boolean {
        if (!dirty || !hasDocument) return true
        val answer = JOptionPane.showConfirmDialog(
            this, "Änderungen an „${projectName()}“ speichern?", "GlitchR",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
        )
        return when (answer) {
            JOptionPane.YES_OPTION -> save()
            JOptionPane.NO_OPTION -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------- projects

    private fun projectName() = projectFile?.name ?: "Unbenannt"

    private fun updateTitle() {
        title = "${if (dirty) "● " else ""}${projectName()} – ${shortName(docName, 48)} – GlitchR"
    }

    private fun openProject(file: File) {
        if (!confirmDiscard()) return
        val state = try {
            ProjectFile.load(file)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "„${file.name}“ konnte nicht geöffnet werden:\n${e.message}", "Öffnen", JOptionPane.ERROR_MESSAGE)
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
        status.text = "Geöffnet: ${file.absolutePath}"
    }

    private fun save(): Boolean = projectFile?.let(::writeProject) ?: saveAs()

    private fun saveAs(): Boolean {
        if (!hasDocument) return false
        val chooser = JFileChooser(projectFile?.parentFile ?: prefs.get(PREF_DIR, null)?.let(::File)).apply {
            dialogTitle = "Projekt speichern unter"
            fileFilter = FileNameExtensionFilter("GlitchR-Projekt (.${ProjectFile.EXTENSION})", ProjectFile.EXTENSION)
            selectedFile = projectFile ?: File(currentDirectory, "$docName.${ProjectFile.EXTENSION}")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false
        val chosen = chooser.selectedFile
        val file = if (ProjectFile.isProject(chosen)) chosen else File(chosen.parentFile, "${chosen.name}.${ProjectFile.EXTENSION}")
        if (file.exists() && file != projectFile) {
            val answer = JOptionPane.showConfirmDialog(this, "„${file.name}“ existiert bereits. Ersetzen?", "GlitchR", JOptionPane.YES_NO_OPTION)
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
            status.text = "Gespeichert: ${file.absolutePath}"
            true
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Speichern fehlgeschlagen:\n${e.message}", "GlitchR", JOptionPane.ERROR_MESSAGE)
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
