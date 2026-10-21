package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.FlowStrokes
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.model.AnimKeys
import com.spielgrund.glitchr.model.BlendMode
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.GeneratorLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskBreakup
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.ScatterStyle
import com.spielgrund.glitchr.model.ParamLayer
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.roundToInt

private const val MASK_MODE_BOX = "maskMode"

/** What the editor needs from the main window. */
interface LayerEditorHost {
    val brush: Brush
    var showMask: Boolean

    /** Whether the drawn flow arrows are shown on the canvas. */
    var showFlow: Boolean

    /** Canvas size, for placing pictures; null without document. */
    val imageSize: Pair<Int, Int>?

    /** Where the edited layer's mask lies, its resolution and picture (for a mask from its brightness). */
    val maskTarget: MaskTarget?

    /** Effect settings, opacity or blend mode changed. */
    fun layerChanged()

    /** Mask changed (the overlay must be redrawn too). */
    fun maskChanged()

    /** Name, visibility or mask mode changed: the layer list must be redrawn. */
    fun layerListChanged()

    /** Animation of a setting of [layer] for the editor row (diamond and right-click menu). */
    fun animHook(layer: Layer, key: String): AnimHook

    /** Values were replaced wholesale: rebuild the editor. */
    fun rebuildEditor(focusMaskMode: Boolean = false)

    /** The point selected in a layer's point list (see [Param.Points]), shown and dragged on the canvas; -1 none. */
    var selectedPoint: Int
}

/** Settings of one layer: effect or generator parameters or picture placement, opacity, blend mode and mask. */
class LayerEditor(private val layer: Layer, private val host: LayerEditorHost) : JPanel(java.awt.BorderLayout()) {
    private val form = Form()

    init {
        isOpaque = false
        add(form)
        form.border = javax.swing.BorderFactory.createEmptyBorder(8, 10, 10, 10)
        buildHeader()
        when (layer) {
            is EffectLayer -> buildParams(layer, "Effect")
            is GeneratorLayer -> buildParams(layer, "Generator")
            is ImageLayer -> buildImage(layer)
        }
        buildLayer()
        buildMask()
        form.end()
    }

    private fun buildHeader() {
        val (title, description) = when (layer) {
            is EffectLayer -> layer.effect.name to layer.effect.description +
                if (layer.adjustment) ". Adjustment layer: works on all layers below it." else ""
            is ImageLayer -> "Image layer" to "Effects above work on this image. The result lies over the layers below."
            is GeneratorLayer -> "${layer.generator.name} (Generator)" to
                "${layer.generator.description}. Creates an image the size of the canvas – effects above work on it like on an image layer."
        }
        form.section(title)
        form.full(JLabel("<html><body style='width:230px'>$description</body></html>").apply {
            foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        })
        // fixed base width: a long name must not make the whole panel wider than the sidebar
        val name = JTextField(layer.name, 8)
        name.document.addDocumentListener(object : DocumentListener {
            fun update() {
                layer.name = name.text
                host.layerListChanged()
            }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        form.row("Name", name)
        if (layer is EffectLayer) form.full(JCheckBox("Adjustment layer", layer.adjustment).apply {
            toolTipText = "On: works on all layers below, like an adjustment layer in Photoshop – effects above it refine it · " +
                "off: works only on the image or generator below it"
            addActionListener {
                layer.adjustment = isSelected
                host.layerListChanged()
                host.layerChanged()
                host.rebuildEditor()
            }
        })
    }

    private fun buildParams(layer: ParamLayer, title: String) {
        valueChanged = null
        form.section(title)
        for (p in layer.params) {
            // points or arrows of another mode: not shown
            if (!active(layer, p)) continue
            val value = layer.values.getValue(p.key)
            val set = { v: Int ->
                if (layer.values[p.key] != v) {
                    layer.values[p.key] = v
                    host.layerChanged()
                    valueChanged?.invoke()
                }
            }
            when (p) {
                is Param.Slider -> {
                    val max = host.imageSize?.let { (w, h) -> p.maxFor(w, h) } ?: p.max
                    form.row(p.label, SliderField(p.min, max, value, p.default.coerceAtMost(max), p.unit, p.decimals, set), p.tip, anim(p.key))
                }
                is Param.Choice -> form.row(p.label, JComboBox(p.options.toTypedArray()).apply {
                    selectedIndex = value
                    addActionListener {
                        set(selectedIndex)
                        // settings that only belong to some choices (points, arrows) come and go
                        if (layer.params.any { activeKey(it) == p.key }) host.rebuildEditor()
                    }
                }, p.tip, anim(p.key))
                is Param.Toggle -> form.full(JCheckBox(p.label, value != 0).apply {
                    toolTipText = p.tip
                    addActionListener { set(if (isSelected) 1 else 0) }
                }, anim(p.key))
                is Param.Color -> form.row(p.label, ColorField(value, p.label, set), p.tip, anim(p.key))
                is Param.Heading -> form.section(p.label)
                is Param.Flow -> buildFlow(layer, p)
                is Param.Icon -> buildIcon(layer, p)
                is Param.Points -> buildPoints(layer, p)
                is Param.Picture -> buildPicture(layer, p)
                is Param.Model -> buildModel(layer, p)
                is Param.Ramp -> form.full(RampField(layer.texts[p.key] ?: p.defaultText) { text ->
                    if (layer.texts[p.key] != text) {
                        layer.texts[p.key] = text
                        host.layerChanged()
                    }
                }.apply { toolTipText = p.tip })
                is Param.Curve -> form.full(CurveField(layer.texts[p.key] ?: "") { text ->
                    if (layer.texts[p.key] != text) {
                        layer.texts[p.key] = text
                        host.layerChanged()
                    }
                }.apply { toolTipText = p.tip })
                is Param.Text -> form.row(p.label, JTextField(layer.texts[p.key] ?: p.defaultText, 8).apply {
                    toolTipText = p.tip
                    document.addDocumentListener(object : DocumentListener {
                        fun update() {
                            if (layer.texts[p.key] != text) {
                                layer.texts[p.key] = text
                                host.layerChanged()
                            }
                        }
                        override fun insertUpdate(e: DocumentEvent) = update()
                        override fun removeUpdate(e: DocumentEvent) = update()
                        override fun changedUpdate(e: DocumentEvent) = update()
                    })
                }, p.tip)
            }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        if (layer.random) {
            buttons.add(JButton("Reroll").apply {
                toolTipText = "New random value: same settings, different result"
                addActionListener { layer.reseed(); host.layerChanged() }
            })
            buttons.add(javax.swing.Box.createHorizontalStrut(6))
        }
        buttons.add(JButton("Defaults").apply {
            addActionListener {
                layer.values.putAll(layer.defaults())
                // texts too (ramps, curves, own text) – but drawn flow strokes and pasted icons stay
                val flows = layer.params.filter { it is Param.Flow || it is Param.Icon || it is Param.Picture || it is Param.Points || it is Param.Model }.map { it.key }.toSet()
                layer.texts.putAll(layer.textDefaults().filterKeys { it !in flows })
                host.layerChanged()
                host.rebuildEditor()
            }
        })
        form.full(buttons)
        if (layer.random) {
            val l = this.layer
            form.full(JCheckBox("New random value every frame", l.seedPerFrame).apply {
                toolTipText = "In the animation every frame gets its own random value – the glitch flickers by itself, without keyframes"
                addActionListener { l.seedPerFrame = isSelected; host.layerChanged() }
            })
        }
    }

    /** The choice that decides whether [p] is in use (see [Param.Points], [Param.Flow]), or null. */
    private fun activeKey(p: Param) = when (p) {
        is Param.Points -> p.activeWhen?.first
        is Param.Flow -> p.activeWhen?.first
        else -> null
    }

    /** Whether [p] is in use with the layer's current choices. */
    private fun active(layer: ParamLayer, p: Param): Boolean {
        val rule = when (p) {
            is Param.Points -> p.activeWhen
            is Param.Flow -> p.activeWhen
            else -> null
        } ?: return true
        return layer.values[rule.first] == rule.second
    }

    /** Called when a setting of the shown layer changes, for parts of the editor that show results (the palette swatches). */
    private var valueChanged: (() -> Unit)? = null

    /**
     * A reference picture: pasted or opened, shown small with the colors found in it – their
     * roles and shares, following the number of colors as it changes.
     */
    private fun buildPicture(layer: ParamLayer, p: Param.Picture) {
        val text = layer.texts[p.key] ?: ""
        val image = com.spielgrund.glitchr.effects.PaletteTransfer.decode(text)
        val thumb = image?.let { img ->
            val k = minOf(1.0, 150.0 / maxOf(img.width, img.height))
            javax.swing.ImageIcon(img.getScaledInstance(maxOf(1, (img.width * k).toInt()), maxOf(1, (img.height * k).toInt()), java.awt.Image.SCALE_SMOOTH))
        }
        form.row(p.label, JLabel(if (thumb == null) "none – sample palette" else "").apply { icon = thumb }, p.tip)
        // the colors found, as swatches with their roles; repainted when the number of colors changes
        val swatches = object : JPanel() {
            override fun getPreferredSize() = java.awt.Dimension(230, 8 * 20)
            override fun paintComponent(g: java.awt.Graphics) {
                super.paintComponent(g)
                val count = layer.values["colors"] ?: 5
                val palette = com.spielgrund.glitchr.effects.PaletteTransfer.palette(layer.texts[p.key] ?: "", count)
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                palette.forEachIndexed { i, s ->
                    val y = i * 20
                    g2.color = java.awt.Color(s.rgb)
                    g2.fillRect(0, y + 2, 34, 16)
                    g2.color = foreground
                    val hex = Integer.toHexString(s.rgb and 0xFFFFFF).uppercase().padStart(6, '0')
                    g2.drawString("${com.spielgrund.glitchr.effects.PaletteTransfer.role(i, palette.size)}  ·  ${(s.share * 100).roundToInt()} %  ·  #$hex", 44, y + 15)
                }
            }
        }.apply { isOpaque = false }
        valueChanged = { swatches.repaint() }
        form.full(swatches)
        fun store(value: String?) {
            if (value == null) {
                javax.swing.JOptionPane.showMessageDialog(form, "The clipboard is empty. Copy a picture, a text or anything else first.", p.label, javax.swing.JOptionPane.INFORMATION_MESSAGE)
                return
            }
            layer.texts[p.key] = value
            host.layerChanged()
            host.rebuildEditor()
        }
        fun fromFile(file: java.io.File) = try {
            javax.imageio.ImageIO.read(file)?.let { com.spielgrund.glitchr.effects.PaletteTransfer.encode(it) }
        } catch (e: Exception) {
            null
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Paste").apply {
            toolTipText = "Takes what is on the clipboard (and leaves it there): a picture or picture file is analysed; color codes in a text become the palette; " +
                "other text (mood words like ocean, Wald, sunset, neon, pastell) or any other data gives colors made up to fit"
            addActionListener {
                // the clipboard is read only here, on the click
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val value = try {
                    pastedReference(clipboard, ::fromFile)
                } catch (e: Exception) {
                    null
                }
                store(value)
            }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Open…").apply {
            addActionListener {
                val chooser = javax.swing.JFileChooser().apply {
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Pictures", "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff")
                }
                if (chooser.showOpenDialog(form) == javax.swing.JFileChooser.APPROVE_OPTION) store(fromFile(chooser.selectedFile))
            }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Remove").apply {
            isEnabled = text.isNotBlank()
            addActionListener { layer.texts[p.key] = ""; host.layerChanged(); host.rebuildEditor() }
        })
        form.full(buttons)
    }

    /**
     * Whatever is on the clipboard as a reference for [com.spielgrund.glitchr.effects.PaletteTransfer]:
     * picture files and pictures as they are; text by its color codes or its words; anything
     * else – other files, other formats – by its content. Null only if the clipboard is empty.
     */
    private fun pastedReference(clipboard: java.awt.datatransfer.Clipboard, fromFile: (java.io.File) -> String?): String? {
        val palette = com.spielgrund.glitchr.effects.PaletteTransfer
        val files = java.awt.datatransfer.DataFlavor.javaFileListFlavor
        if (clipboard.isDataFlavorAvailable(files)) {
            val list = (clipboard.getData(files) as List<*>).filterIsInstance<java.io.File>()
            list.firstNotNullOfOrNull { fromFile(it) }?.let { return it }
            // not pictures: their content (the first megabyte) makes the colors
            list.firstOrNull { it.isFile }?.let { f -> return palette.fromBytes(f.inputStream().use { it.readNBytes(1 shl 20) } + f.name.toByteArray()) }
            list.firstOrNull()?.let { return palette.fromText(it.name) }
        }
        ImageClipboard.read(clipboard)?.let { return palette.encode(it) }
        if (clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
            (clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotBlank() }?.let { return palette.fromText(it) }
        }
        // anything else: the bytes of the first format that can be read
        for (flavor in clipboard.availableDataFlavors) {
            val data = try { clipboard.getData(flavor) } catch (e: Exception) { continue }
            val bytes = when (data) {
                is java.io.InputStream -> data.use { it.readNBytes(1 shl 20) }
                is java.io.Reader -> data.use { it.readText().toByteArray() }
                is ByteArray -> data
                is java.nio.ByteBuffer -> ByteArray(data.remaining()).also { data.get(it) }
                is String -> data.toByteArray()
                else -> data.toString().toByteArray()
            }
            if (bytes.isNotEmpty()) return palette.fromBytes(bytes)
        }
        return null
    }

    /**
     * Points on the canvas: a list to add and delete them; the selected one is marked in the
     * picture and can be dragged there – as can any other, which then becomes selected.
     */
    private fun buildPoints(layer: ParamLayer, p: Param.Points) {
        val points = com.spielgrund.glitchr.effects.PointList.parse(layer.texts[p.key] ?: p.defaultText)
        val model = javax.swing.DefaultListModel<String>()
        points.forEachIndexed { i, pt -> model.addElement("Point ${i + 1}     ${(pt.x * 100).roundToInt()} %, ${(pt.y * 100).roundToInt()} %") }
        val list = javax.swing.JList(model).apply {
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            // as many rows as points, up to eight – then it scrolls
            visibleRowCount = points.size.coerceIn(3, 8)
            if (host.selectedPoint in points.indices) selectedIndex = host.selectedPoint
        }
        form.full(JLabel(p.label).apply { toolTipText = p.tip; border = javax.swing.BorderFactory.createEmptyBorder(4, 16, 2, 0) })
        form.full(javax.swing.JScrollPane(list).apply {
            // as high as its rows, even where the editor is narrow (the layout then falls back to minimum sizes)
            val height = list.preferredScrollableViewportSize.height + 6
            preferredSize = java.awt.Dimension(60, height)
            minimumSize = java.awt.Dimension(60, height)
        })
        // the selected point's place, in pixels from the middle of the canvas
        val (cw, ch) = host.imageSize ?: (1000 to 1000)
        val sel = points.getOrNull(list.selectedIndex)
        fun moveSelected(x: Double?, y: Double?) {
            val i = list.selectedIndex
            if (i !in points.indices) return
            val now = com.spielgrund.glitchr.effects.PointList.parse(layer.texts[p.key] ?: "").toMutableList()
            if (i !in now.indices) return
            now[i] = java.awt.geom.Point2D.Double(x ?: now[i].x, y ?: now[i].y)
            layer.texts[p.key] = com.spielgrund.glitchr.effects.PointList.format(now)
            model.set(i, "Point ${i + 1}     ${(now[i].x * 100).roundToInt()} %, ${(now[i].y * 100).roundToInt()} %")
            host.layerChanged()
        }
        if (sel != null) {
            form.row("Point X", SliderField(-cw * 100, cw * 100, ((sel.x - 0.5) * cw * 100).roundToInt(), 0, " px", 2) { v ->
                moveSelected(0.5 + v / 100.0 / cw, null)
            }, "Of the selected point, from the middle of the canvas")
            form.row("Point Y", SliderField(-ch * 100, ch * 100, ((sel.y - 0.5) * ch * 100).roundToInt(), 0, " px", 2) { v ->
                moveSelected(null, 0.5 + v / 100.0 / ch)
            }, "Of the selected point, from the middle of the canvas")
        }
        // another point picked: the sliders show it
        list.addListSelectionListener { if (!it.valueIsAdjusting && host.selectedPoint != list.selectedIndex) { host.selectedPoint = list.selectedIndex; host.rebuildEditor() } }
        fun store(changed: List<java.awt.geom.Point2D.Double>, select: Int) {
            layer.texts[p.key] = com.spielgrund.glitchr.effects.PointList.format(changed)
            host.selectedPoint = select
            host.layerChanged()
            host.rebuildEditor()
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Add").apply {
            toolTipText = "A new point – next to the selected one, or in the middle"
            addActionListener {
                val at = points.getOrNull(list.selectedIndex)?.let { java.awt.geom.Point2D.Double((it.x + 0.08).coerceIn(0.0, 1.0), (it.y + 0.08).coerceIn(0.0, 1.0)) }
                    ?: java.awt.geom.Point2D.Double(0.5, 0.5)
                store(points + at, points.size)
            }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Delete").apply {
            isEnabled = list.selectedIndex in points.indices
            addActionListener {
                val i = list.selectedIndex
                if (i in points.indices) store(points.filterIndexed { k, _ -> k != i }, minOf(i, points.size - 2))
            }
        })
        form.full(buttons)
        form.full(hint("Drag a point in the picture to move it; the selected one is marked."))
    }

    /**
     * A 3D model: OBJ pasted (text or a copied file) or opened, with how many faces it has; the
     * script for Blender that puts the selected object on the clipboard as OBJ text.
     */
    private fun buildModel(layer: ParamLayer, p: Param.Model) {
        val text = layer.texts[p.key] ?: ""
        val mesh = com.spielgrund.glitchr.effects.Mesh.of(text)
        form.row(p.label, JLabel(when {
            text.isBlank() -> "none – built-in shape"
            mesh == null -> "can't be read"
            else -> "${mesh.faces} faces, ${mesh.triangles} triangles" + if (mesh.uv != null) ", with UVs" else ""
        }), p.tip)
        fun store(value: String?) {
            if (value == null) return
            // the model is read here already, to say what's wrong
            val problem = try {
                if (com.spielgrund.glitchr.effects.Mesh.parseObj(value) == null) "No faces found – paste OBJ text or an .obj file." else null
            } catch (e: com.spielgrund.glitchr.effects.Mesh.Companion.TooBig) {
                "This model has ${e.faces} faces – at most ${com.spielgrund.glitchr.effects.Mesh.MAX_FACES}. In Blender, a Decimate modifier brings it down."
            }
            if (problem != null) {
                javax.swing.JOptionPane.showMessageDialog(form, problem, p.label, javax.swing.JOptionPane.INFORMATION_MESSAGE)
                return
            }
            // only what the model needs: positions, texture places, normals and faces
            layer.texts[p.key] = value.lineSequence().map { it.trim() }
                .filter { it.startsWith("v ") || it.startsWith("vt ") || it.startsWith("vn ") || it.startsWith("f ") }
                .joinToString("\n")
            host.layerChanged()
            host.rebuildEditor()
        }
        fun fromFile(file: java.io.File) = try { file.readText() } catch (e: Exception) { null }
        val buttons = JPanel(java.awt.GridLayout(2, 2, 6, 6)).apply { isOpaque = false }
        buttons.add(JButton("Paste").apply {
            toolTipText = "OBJ text from the clipboard (e.g. from the Blender script), or a copied .obj file"
            addActionListener {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val value = try {
                    when {
                        clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor) ->
                            (clipboard.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<java.io.File>().firstNotNullOfOrNull { fromFile(it) }
                        clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor) -> clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
                if (value == null) javax.swing.JOptionPane.showMessageDialog(form, "The clipboard holds no OBJ text or file.", p.label, javax.swing.JOptionPane.INFORMATION_MESSAGE)
                store(value)
            }
        })
        buttons.add(JButton("Open…").apply {
            addActionListener {
                val chooser = javax.swing.JFileChooser().apply { fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Wavefront OBJ", "obj") }
                if (chooser.showOpenDialog(form) == javax.swing.JFileChooser.APPROVE_OPTION) store(fromFile(chooser.selectedFile))
            }
        })
        buttons.add(JButton("Remove").apply {
            isEnabled = text.isNotBlank()
            addActionListener { layer.texts[p.key] = ""; host.layerChanged(); host.rebuildEditor() }
        })
        buttons.add(JButton("Blender script").apply {
            toolTipText = "Copies a script for Blender: run it there and the selected object is on the clipboard as OBJ – then press Paste here"
            addActionListener {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(com.spielgrund.glitchr.effects.Mesh.BLENDER_SCRIPT), null)
                javax.swing.JOptionPane.showMessageDialog(
                    form,
                    "The script is on the clipboard.\n\nIn Blender: select the object, open the Scripting tab, make a new text, paste the script and press Run Script\n" +
                        "(or paste it into the Python console). Then press Paste here.",
                    "Blender script", javax.swing.JOptionPane.INFORMATION_MESSAGE,
                )
            }
        })
        form.full(buttons)
    }


    /** An own icon: pasted from the clipboard or opened from a file, shown small, removable. */
    private fun buildIcon(layer: ParamLayer, p: Param.Icon) {
        val text = layer.texts[p.key] ?: ""
        // the icon as it will be used, white on dark grey; repainted when the icon settings change
        fun look() = com.spielgrund.glitchr.effects.CustomIcon.Look(
            layer.values["iconParts"] ?: 0, (layer.values["iconContrast"] ?: 50) / 100.0,
            (layer.values["iconBrightness"] ?: 0) / 100.0, (layer.values["iconWidth"] ?: 0) / 100.0, (layer.values["iconSmooth"] ?: 0) / 100.0,
        )
        val preview = object : JPanel() {
            override fun getPreferredSize() = java.awt.Dimension(64, 64)
            override fun paintComponent(g: java.awt.Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = java.awt.Color(0x3A3A3A)
                g2.fillRect(0, 0, 64, 64)
                val icon = com.spielgrund.glitchr.effects.CustomIcon.decode(layer.texts[p.key] ?: "", look())
                g2.color = java.awt.Color.WHITE
                if (icon != null) icon.fill(g2, 32.0, 32.0, 56.0)
                else {
                    g2.font = g2.font.deriveFont(10f)
                    g2.drawString(if ((layer.texts[p.key] ?: "").isBlank()) "none" else "empty", 6, 36)
                }
                g2.dispose()
            }
        }
        valueChanged = { preview.repaint() }
        form.row(p.label, JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(preview) }, p.tip)
        fun store(value: String?) {
            if (value == null) {
                javax.swing.JOptionPane.showMessageDialog(
                    form, "No usable icon found. Copy SVG code, an .svg / .png file or a black-and-white picture first.",
                    "Own icon", javax.swing.JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }
            layer.texts[p.key] = value
            host.layerChanged()
            host.rebuildEditor()
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Paste").apply {
            toolTipText = "Takes the icon from the clipboard: SVG code, a copied .svg or picture file, or a picture"
            addActionListener { store(IconClipboard.read(java.awt.Toolkit.getDefaultToolkit().systemClipboard)) }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Open…").apply {
            addActionListener {
                val chooser = javax.swing.JFileChooser().apply {
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("SVG and pictures", "svg", "png", "jpg", "jpeg", "gif", "bmp", "webp")
                }
                if (chooser.showOpenDialog(form) == javax.swing.JFileChooser.APPROVE_OPTION) store(IconClipboard.fromFile(chooser.selectedFile))
            }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Remove").apply {
            isEnabled = text.isNotBlank()
            addActionListener { layer.texts[p.key] = ""; host.layerChanged(); host.rebuildEditor() }
        })
        form.full(buttons)
        form.full(hint("Without an icon the built-in symbols are used. Which parts of a picture become the symbol: see Icon parts, contrast and brightness below; in SVG, white fills cut holes."))
    }

    /** Drawn flow strokes: they are edited on the canvas, here they can be undone or cleared. */
    private fun buildFlow(layer: ParamLayer, p: Param.Flow) {
        val strokes = FlowStrokes.parse(layer.texts[p.key] ?: "")
        form.row(p.label, JLabel(when (strokes.size) {
            0 -> "no strokes"
            1 -> "1 stroke"
            else -> "${strokes.size} strokes"
        }), p.tip)
        fun store(changed: List<List<java.awt.geom.Point2D.Double>>) {
            layer.texts[p.key] = FlowStrokes.format(changed)
            host.layerChanged()
            host.rebuildEditor()
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Remove last stroke").apply {
            isEnabled = strokes.isNotEmpty()
            addActionListener { store(strokes.dropLast(1)) }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Delete all").apply {
            isEnabled = strokes.isNotEmpty()
            addActionListener { store(emptyList()) }
        })
        form.full(buttons)
        form.full(JCheckBox("Show arrows", host.showFlow).apply {
            toolTipText = "Shows or hides the drawn arrows on the canvas; they work either way"
            addActionListener { host.showFlow = isSelected }
        })
        form.full(hint(
            (p.tip?.let { "$it. " } ?: "") +
                "The right mouse button (or Alt) wipes arrows away. While the mask is shown, the mask is edited instead.",
        ))
    }

    private fun buildImage(layer: ImageLayer) {
        form.section("Position & size")
        val (cw, ch) = host.imageSize ?: (layer.image.width to layer.image.height)
        form.full(hint("Original ${layer.image.width} × ${layer.image.height} px, canvas $cw × $ch px"))
        fun changed() {
            host.layerChanged()
            host.rebuildEditor()
        }
        // positions in pixels with fractions: steps of one pixel, two decimals
        fun spinner(value: Double, limit: Double, set: (Double) -> Unit) =
            javax.swing.JSpinner(javax.swing.SpinnerNumberModel(value.coerceIn(-limit, limit), -limit, limit, 1.0)).apply {
                editor = javax.swing.JSpinner.NumberEditor(this, "0.##")
                addChangeListener { set(Math.round((this.value as Number).toDouble() * 100) / 100.0) }
            }
        val limit = 100_000.0
        form.row("X", spinner(layer.x, limit) { if (Math.abs(it - layer.x) > 1e-9) { layer.x = it; host.layerChanged() } }, anim = anim(AnimKeys.X))
        form.row("Y", spinner(layer.y, limit) { if (Math.abs(it - layer.y) > 1e-9) { layer.y = it; host.layerChanged() } }, anim = anim(AnimKeys.Y))
        // scale in hundredths of a percent, rotation in hundredths of a degree
        form.row("Scale", SliderField(100, 100_000, (layer.scale * 10_000).roundToInt(), 10_000, "%", 2) { steps ->
            val s = steps / 10_000.0
            if ((layer.scale * 10_000).roundToInt() == steps) return@SliderField
            // scale around the center
            val b = layer.bounds
            layer.scale = s
            layer.x = b.centerX - layer.image.width * s / 2
            layer.y = b.centerY - layer.image.height * s / 2
            host.layerChanged()
        }, "Double-click the slider: 100 %", anim(AnimKeys.SCALE))
        form.row("Rotation", SliderField(-18000, 18000, (layer.rotation * 100).roundToInt(), 0, "°", 2) { hundredths ->
            if ((layer.rotation * 100).roundToInt() == hundredths) return@SliderField
            // around the middle of the picture: position and size stay
            layer.rotation = hundredths / 100.0
            host.layerChanged()
        }, "Around the middle of the image. In the picture: Shift + drag a corner", anim(AnimKeys.ROTATION))
        form.full(JCheckBox("Smooth scaling", layer.smooth).apply {
            toolTipText = "Off: hard pixels (nearest neighbor) when enlarging"
            addActionListener { layer.smooth = isSelected; host.layerChanged() }
        })
        val buttons = JPanel(java.awt.GridLayout(3, 2, 6, 6)).apply { isOpaque = false }
        buttons.add(JButton("Fit").apply {
            toolTipText = "Show the whole image on the canvas"
            addActionListener { layer.fitInto(cw, ch); changed() }
        })
        buttons.add(JButton("Fill").apply {
            toolTipText = "Cover the whole canvas (borders are cropped)"
            addActionListener { layer.fitInto(cw, ch, cover = true); changed() }
        })
        buttons.add(JButton("Original size").apply {
            addActionListener { layer.scale = 1.0; layer.center(cw, ch); changed() }
        })
        buttons.add(JButton("Center").apply {
            addActionListener { layer.center(cw, ch); changed() }
        })
        buttons.add(JButton("Reset rotation").apply {
            addActionListener { layer.rotation = 0.0; changed() }
        })
        form.full(buttons)
        form.full(hint("Dragging in the picture moves the layer, dragging the corners scales it, with Shift it rotates it. While the mask is shown (Ctrl+M), the mask is edited instead – hide it or hold Ctrl to move."))
    }

    private fun buildLayer() {
        form.section("Layer")
        form.row("Opacity", SliderField(0, 100, layer.opacity, 100, "%") {
            layer.opacity = it
            host.layerChanged()
        }, anim = anim(AnimKeys.OPACITY))
        form.row("Blend mode", JComboBox(BlendMode.entries.toTypedArray()).apply {
            selectedItem = layer.blend
            addActionListener {
                layer.blend = selectedItem as BlendMode
                host.layerChanged()
            }
        }, "How the layer is combined with what lies below")
    }

    private fun buildMask() {
        val mask = layer.mask
        form.section("Mask")
        form.row("Type", JComboBox(MaskMode.entries.toTypedArray()).also { typeBox = it }.apply {
            selectedItem = mask.mode
            name = MASK_MODE_BOX
            // arrow keys only move through the list; the choice is made with Enter or a click,
            // because every change rebuilds this editor
            putClientProperty("JComboBox.isTableCellEditor", true)
            addActionListener {
                val mode = selectedItem as MaskMode
                if (mode == mask.mode) return@addActionListener
                if (mode == MaskMode.BRUSH) ensurePainted()
                // a new brush or gradient mask starts inverted, so the picture keeps its effect until
                // the mask takes it away; a noise mask starts white, not inverted. Between brush and
                // gradients the user's choice stays.
                when {
                    mode == MaskMode.NOISE -> mask.invert = false
                    mask.mode == MaskMode.OFF || mask.mode == MaskMode.NOISE -> mask.invert = true
                }
                mask.mode = mode
                host.brush.lastSelection = null
                if (mode != MaskMode.OFF) host.showMask = true
                host.maskChanged()
                host.layerListChanged()
                host.rebuildEditor(focusMaskMode = true)
            }
        })
        when (mask.mode) {
            MaskMode.OFF -> form.full(hint("Without a mask the effect works on the whole picture. “Find” below starts a painted mask from the picture, the mask noise sliders a noise mask."))
            MaskMode.NOISE -> form.full(hint("The mask starts white (full effect); “Mask noise” below scatters noise specks onto it, and scatter or streaks move and smear the noise itself."))
            MaskMode.BRUSH -> buildMaskTools()
            MaskMode.LINEAR, MaskMode.RADIAL -> {
                // the two points as numbers too, so they can be animated
                val radial = mask.mode == MaskMode.RADIAL
                fun point(label: String, key: String, get: () -> Double, set: (Double) -> Unit) =
                    form.row(label, SliderField(-1000, 2000, (get() * 1000).roundToInt(), 0, "%", 1) { tenths ->
                        if ((get() * 1000).roundToInt() == tenths) return@SliderField
                        set(tenths / 1000.0)
                        host.maskChanged()
                    }, "In % of the picture", anim(key))
                val a = if (radial) "Center" else "Start"
                val b = if (radial) "Edge" else "End"
                point("$a X", AnimKeys.MASK_AX, { mask.handleA.x }) { mask.handleA = mask.handleA.copy(x = it) }
                point("$a Y", AnimKeys.MASK_AY, { mask.handleA.y }) { mask.handleA = mask.handleA.copy(y = it) }
                point("$b X", AnimKeys.MASK_BX, { mask.handleB.x }) { mask.handleB = mask.handleB.copy(x = it) }
                point("$b Y", AnimKeys.MASK_BY, { mask.handleB.y }) { mask.handleB = mask.handleB.copy(y = it) }
                form.full(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                    isOpaque = false
                    add(JButton("Reset gradient").apply {
                        addActionListener { mask.resetGradient(); host.maskChanged() }
                    })
                })
                form.full(hint(
                    if (mask.mode == MaskMode.LINEAR)
                        "Dragging in the picture sets the gradient anew: full effect at the white point, none at the black one. The points can be moved one by one."
                    else
                        "Dragging in the picture sets the circle anew: full effect in the middle (white), none from the edge on (black)."
                ))
            }
        }
        if (mask.mode != MaskMode.OFF) {
            if (layer is ImageLayer || layer is GeneratorLayer) {
                form.full(hint("The mask cuts out the image before the effects above work – so they can run beyond the mask edge."))
            }
            form.full(JCheckBox("Invert mask", mask.invert).apply {
                addActionListener { mask.invert = isSelected; host.maskChanged() }
            }, anim(AnimKeys.MASK_INVERT))
            val thresholdField = SliderField(0, 100, mask.threshold, 50, "%") {
                mask.threshold = it
                host.maskChanged()
            }.apply { isEnabled = mask.hardEdge }
            form.full(JCheckBox("Hard edge (black/white only)", mask.hardEdge).apply {
                toolTipText = "Everything from the threshold up gets the full effect, everything below none"
                addActionListener {
                    mask.hardEdge = isSelected
                    thresholdField.isEnabled = isSelected
                    host.maskChanged()
                }
            })
            form.row("Threshold", thresholdField, "From this mask value the effect works fully", anim(AnimKeys.MASK_THRESHOLD))
            form.full(JCheckBox("Show mask (red = no effect)", host.showMask).apply {
                toolTipText = "Shows or hides the overlay; the mask works either way (Ctrl+M)"
                addActionListener { host.showMask = isSelected }
            })
        }

        // quick selections, always at hand; they switch to a painted mask
        buildAutoSelect()
        buildRefine()

        form.section("Mask noise")
        buildBreakup()

        form.separator()
        val clipboard = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        clipboard.add(JButton("Copy mask").apply {
            toolTipText = "Copies this layer's mask (type, gradient, painted pixels, edge, noise and scatter) to paste it onto another layer"
            addActionListener {
                com.spielgrund.glitchr.model.MaskClipboard.content = mask.memento()
                host.rebuildEditor()
            }
        })
        clipboard.add(javax.swing.Box.createHorizontalStrut(6))
        clipboard.add(JButton("Paste mask").apply {
            isEnabled = com.spielgrund.glitchr.model.MaskClipboard.content != null
            toolTipText = "Replaces this layer's mask with the copied one"
            addActionListener {
                val copied = com.spielgrund.glitchr.model.MaskClipboard.content ?: return@addActionListener
                mask.restore(copied)
                host.brush.lastSelection = null
                if (mask.mode != MaskMode.OFF) host.showMask = true
                host.maskChanged()
                host.layerListChanged()
                host.rebuildEditor()
            }
        })
        form.full(clipboard)
    }

    /** Sliders that refine the last selection (magic wand or “Find”) on this layer's mask, while nothing else changed it. */
    private fun buildRefine() {
        val op = host.brush.lastSelection?.takeIf { it.mask === layer.mask } ?: return
        // its own indented block under the selection buttons, so it reads as part of the selection
        val refine = Form()
        refine.isOpaque = false
        refine.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 2, 0, 0, javax.swing.UIManager.getColor("Component.accentColor") ?: java.awt.Color(0x4A88C7)),
            javax.swing.BorderFactory.createEmptyBorder(2, 10, 2, 0),
        )
        refine.full(JLabel("Refine selection").apply { font = font.deriveFont(java.awt.Font.BOLD) })
        fun redo() {
            op.apply()
            host.maskChanged()
        }
        refine.row("More / less", SliderField(-100, 100, op.amount, 0, "%") { op.amount = it; redo() },
            "Grows or shrinks the selection")
        refine.row("Smooth", SliderField(0, 100, op.smooth, 0, "px") { op.smooth = it; redo() },
            "Rounds off the outline and closes small holes and specks")
        refine.row("Soft edge", SliderField(0, 200, op.feather, 0, "px") { op.feather = it; redo() })
        if (op.wand) refine.row("Tolerance", SliderField(0, 255, op.tolerance, host.brush.tolerance) { op.tolerance = it; redo() },
            "How different the colors may be; clicking the same color again also widens it")
        refine.full(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            isOpaque = false
            add(JButton("Convert to brush mask").apply {
                toolTipText = "Keeps the selection as it is now, as an ordinary painted mask; the sliders no longer change it"
                addActionListener {
                    host.brush.lastSelection = null
                    host.rebuildEditor()
                }
            })
        })
        refine.full(hint("Live: the settings change this selection until you convert it or edit the mask otherwise."))
        form.full(refine)
    }

    /** Noise and scattering that roughen the mask and break up its hard edge. */
    private fun buildBreakup() {
        val mask = layer.mask
        fun set(change: (MaskBreakup) -> MaskBreakup) {
            val next = change(mask.breakup)
            if (next == mask.breakup) return
            mask.breakup = next
            // without a mask the sliders start a noise mask; any other mask they roughen
            if (mask.mode == MaskMode.OFF && !next.isOff) {
                mask.mode = MaskMode.NOISE
                host.brush.lastSelection = null
                host.showMask = true
                // the type box follows without rebuilding the editor, so the slider drag goes on
                typeBox?.selectedItem = MaskMode.NOISE
                host.layerListChanged()
            }
            host.maskChanged()
        }
        form.row("Noise", SliderField(0, 100, mask.breakup.noise, 0, "%") { n -> set { it.copy(noise = n) } },
            "White noise added to the mask; with a hard edge it becomes grain", anim(AnimKeys.MASK_NOISE))
        form.row("Grain", SliderField(1, 64, mask.breakup.grain, 1, "px") { g -> set { it.copy(grain = g) } }, "Size of the noise dots")
        form.row("Scatter", SliderField(0, 500, mask.breakup.scatter, 0, "px") { s -> set { it.copy(scatter = s) } },
            "Every pixel reads the mask from a random point up to this far away – tears up the edge", anim(AnimKeys.MASK_SCATTER))
        form.row("Scatter style", JComboBox(ScatterStyle.entries.toTypedArray()).apply {
            selectedItem = mask.breakup.style
            putClientProperty("JComboBox.isTableCellEditor", true)
            addActionListener { set { it.copy(style = selectedItem as ScatterStyle) } }
        }, "Single pixels, whole rows or columns, or streaks that smear the mask like pixel sorting")
        form.row("Streak length", SliderField(1, 1000, mask.breakup.runLength, 40, "px") { r -> set { it.copy(runLength = r) } },
            "Average length of the streaks")
        val bottom = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        bottom.add(JCheckBox("New every frame", mask.breakup.moving).apply {
            isOpaque = false
            toolTipText = "In an animation, noise and scattering change from frame to frame"
            addActionListener { set { it.copy(moving = isSelected) } }
        })
        bottom.add(javax.swing.Box.createHorizontalStrut(8))
        bottom.add(JButton("Reroll").apply {
            toolTipText = "New random noise and scattering"
            addActionListener { set { it.copy(seed = kotlin.random.Random.nextLong()) } }
        })
        form.full(bottom)
    }

    /** Quick selections found by picture analysis: subject, background, sky, depth. */
    private fun buildAutoSelect() {
        val mask = layer.mask
        val kinds = com.spielgrund.glitchr.effects.AutoSelect.Kind.entries.toTypedArray()
        val chosen = lastAutoSelect
        form.section("Find")
        form.row("Find", JComboBox(kinds).apply {
            selectedItem = chosen
            putClientProperty("JComboBox.isTableCellEditor", true)
            addActionListener {
                lastAutoSelect = selectedItem as com.spielgrund.glitchr.effects.AutoSelect.Kind
                host.rebuildEditor()
            }
        }, "Picks out parts of the picture by simple analysis – a quick start; its settings below steer it")
        val values = findValues.getOrPut(chosen) { com.spielgrund.glitchr.effects.AutoSelect.defaults(chosen) }
        val live = host.brush.lastSelection?.takeIf { it.mask === mask && it.findKind == chosen }

        /** Recomputes a live selection with the new settings, in the background; only the newest result counts. */
        fun recompute() {
            val op = host.brush.lastSelection?.takeIf { it.mask === mask && it.findKind == chosen } ?: return
            val image = host.maskTarget?.image ?: return
            val settings = values.toMap()
            val generation = ++findGeneration
            findExecutor.execute {
                if (generation != findGeneration) return@execute
                val result = com.spielgrund.glitchr.effects.AutoSelect.select(image, chosen, settings)
                javax.swing.SwingUtilities.invokeLater {
                    if (generation != findGeneration || host.brush.lastSelection !== op) return@invokeLater
                    op.setRaw(result)
                    op.apply()
                    host.maskChanged()
                }
            }
        }

        // the settings of this kind of analysis
        for (param in com.spielgrund.glitchr.effects.AutoSelect.params(chosen)) {
            when (param) {
                is com.spielgrund.glitchr.effects.Param.Slider -> form.row(
                    param.label,
                    SliderField(param.min, param.max, values[param.key] ?: param.default, param.default, param.unit.trim(), param.decimals) {
                        values[param.key] = it
                        recompute()
                    },
                    param.tip,
                )
                is com.spielgrund.glitchr.effects.Param.Toggle -> form.full(JCheckBox(param.label, (values[param.key] ?: param.default) != 0).apply {
                    toolTipText = param.tip
                    addActionListener {
                        values[param.key] = if (isSelected) 1 else 0
                        recompute()
                    }
                })
                else -> {}
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        fun run(mode: Int, button: JButton) {
            val target = host.maskTarget ?: return
            val image = target.image ?: return
            val settings = values.toMap()
            button.isEnabled = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR)
            // the analysis can take a moment on large pictures: off the UI thread
            findExecutor.execute {
                val result = com.spielgrund.glitchr.effects.AutoSelect.select(image, chosen, settings)
                javax.swing.SwingUtilities.invokeLater {
                    mask.ensurePainted(image.width, image.height)
                    if (mask.mode != MaskMode.BRUSH) {
                        // “Select” starts from nothing; adding or taking away from what another type showed makes no sense
                        mask.fill(0)
                        mask.mode = MaskMode.BRUSH
                    }
                    val op = SelectionOp(mask, mask.painted?.copyOf(), image.width, image.height, mode, false, 0, 0) { result }
                    op.findKind = chosen
                    host.brush.lastSelection = op
                    op.apply()
                    host.showMask = true
                    host.maskChanged()
                    host.layerListChanged()
                    cursor = java.awt.Cursor.getDefaultCursor()
                    button.isEnabled = true
                    host.rebuildEditor()
                }
            }
        }
        for ((label, mode, tip) in listOf(
            Triple("Select", 0, "Replaces the mask with what was found"),
            Triple("Add", 1, "Adds what was found to the mask"),
            Triple("Subtract", 2, "Takes what was found away from the mask"),
        )) {
            if (mode > 0) buttons.add(javax.swing.Box.createHorizontalStrut(6))
            buttons.add(JButton(label).apply {
                toolTipText = "$tip. It stays live: the settings above change it until you convert it to a brush mask"
                addActionListener { run(mode, this) }
            })
        }
        form.full(buttons)
        if (live != null) {
            form.full(hint("Live preview: the settings above and the refine sliders below change this selection right away."))
        }
    }

    /** Tool choice and settings for the painted mask. */
    private fun buildMaskTools() {
        val mask = layer.mask
        val brush = host.brush
        form.row("Tool", JComboBox(MaskTool.entries.toTypedArray()).apply {
            selectedItem = brush.tool
            putClientProperty("JComboBox.isTableCellEditor", true)
            addActionListener {
                val tool = selectedItem as MaskTool
                if (tool == brush.tool) return@addActionListener
                brush.tool = tool
                host.rebuildEditor()
            }
        })
        when (brush.tool) {
            MaskTool.BRUSH -> {
                form.row("Brush size", SliderField(1, 1500, brush.size, 120, "px") { brush.size = it })
                form.row("Hardness", SliderField(0, 100, brush.hardness, 40, "%") { brush.hardness = it })
                form.row("Strength", SliderField(1, 100, brush.strength, 60, "%") { brush.strength = it })
            }
            MaskTool.WAND -> {
                form.row("Tolerance", SliderField(0, 255, brush.tolerance, 32) { brush.tolerance = it },
                    "How much a color may differ from the clicked one (per channel)")
                form.full(JCheckBox("Contiguous areas only", brush.contiguous).apply {
                    toolTipText = "Off: selects similar colors in the whole picture"
                    addActionListener { brush.contiguous = isSelected }
                })
                form.full(JCheckBox("Follow soft gradients", brush.gradients).apply {
                    toolTipText = "Contiguous only: the selection grows from pixel to pixel along soft gradients (light across a wall) and stops at hard edges"
                    addActionListener { brush.gradients = isSelected }
                })
                form.row("Soft edge", SliderField(0, 200, brush.feather, 0, "px") { brush.feather = it })
            }
            else -> form.row("Soft edge", SliderField(0, 200, brush.feather, 0, "px") { brush.feather = it })
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Fill all").apply {
            addActionListener { ensurePainted(); mask.fill(255); host.brush.lastSelection = null; host.maskChanged() }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Clear").apply {
            addActionListener { ensurePainted(); mask.fill(0); host.brush.lastSelection = null; host.maskChanged() }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("From picture brightness").apply {
            toolTipText = "Bright parts of the picture get the effect, dark ones don't. With “Hard edge” this gives a sharp selection"
            addActionListener {
                host.maskTarget?.image?.let { mask.fromBrightness(it); host.brush.lastSelection = null; host.maskChanged() }
            }
        })
        form.full(buttons)
        form.full(hint(when (brush.tool) {
            MaskTool.BRUSH -> "The left mouse button paints the effect in, the right mouse button (or Alt) erases."
            MaskTool.RECT, MaskTool.ELLIPSE -> "Dragging adds the area, with the right mouse button (or Alt) it is subtracted."
            MaskTool.LASSO -> "Trace freehand; on release the shape is closed. The right mouse button (or Alt) subtracts."
            MaskTool.WAND -> "A click selects similar colors of the original picture, the right mouse button (or Alt) subtracts them. Clicking the same color again widens the tolerance."
        } + " Space + drag pans the view."))
    }

    /** The animation hook of a setting of this layer. */
    private fun anim(key: String) = host.animHook(layer, key)

    /** Updates the diamonds after the frame or the keyframes changed. */
    fun refreshMarkers() = form.markers.forEach { it.refresh() }

    /** Focuses the mask mode box, so keyboard users stay where they were after a rebuild. */
    fun focusMaskMode() {
        fun find(c: java.awt.Component): java.awt.Component? =
            if (c.name == MASK_MODE_BOX) c else (c as? java.awt.Container)?.components?.firstNotNullOfOrNull(::find)
        find(this)?.requestFocusInWindow()
    }

    private fun ensurePainted() {
        host.maskTarget?.let { layer.mask.ensurePainted(it.width, it.height) }
    }

    private fun hint(text: String) = JLabel("<html><body style='width:230px'>$text</body></html>").apply {
        foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
    }

    /** The mask type box, so the noise sliders can switch it to “Noise”. */
    private var typeBox: JComboBox<MaskMode>? = null

    companion object {
        /** The settings of each kind of “Find”, kept between layers. */
        private val findValues = HashMap<com.spielgrund.glitchr.effects.AutoSelect.Kind, MutableMap<String, Int>>()

        /** Runs the analyses one after another, off the UI thread; only the newest live result is used. */
        private val findExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "glitch-find").apply { isDaemon = true } }
        @Volatile private var findGeneration = 0

        /** The last chosen kind of quick selection, kept between layers. */
        private var lastAutoSelect = com.spielgrund.glitchr.effects.AutoSelect.Kind.SUBJECT
    }
}
