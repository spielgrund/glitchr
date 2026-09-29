package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.FlowStrokes
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.model.BlendMode
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskMode
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

    /** Values were replaced wholesale: rebuild the editor. */
    fun rebuildEditor(focusMaskMode: Boolean = false)
}

/** Settings of one layer: effect parameters or picture placement, opacity, blend mode and mask. */
class LayerEditor(private val layer: Layer, private val host: LayerEditorHost) : JPanel(java.awt.BorderLayout()) {
    private val form = Form()

    init {
        isOpaque = false
        add(form)
        form.border = javax.swing.BorderFactory.createEmptyBorder(8, 10, 10, 10)
        buildHeader()
        when (layer) {
            is EffectLayer -> buildEffect(layer)
            is ImageLayer -> buildImage(layer)
        }
        buildLayer()
        buildMask()
        form.end()
    }

    private fun buildHeader() {
        val (title, description) = when (layer) {
            is EffectLayer -> layer.effect.name to layer.effect.description
            is ImageLayer -> "Bildebene" to "Effekte darüber wirken auf dieses Bild. Das Ergebnis liegt über den Ebenen darunter."
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
    }

    private fun buildEffect(layer: EffectLayer) {
        form.section("Effekt")
        for (p in layer.effect.params) {
            val value = layer.values.getValue(p.key)
            val set = { v: Int ->
                if (layer.values[p.key] != v) {
                    layer.values[p.key] = v
                    host.layerChanged()
                }
            }
            when (p) {
                is Param.Slider -> {
                    val max = host.imageSize?.let { (w, h) -> p.maxFor(w, h) } ?: p.max
                    form.row(p.label, SliderField(p.min, max, value, p.default.coerceAtMost(max), p.unit, set), p.tip)
                }
                is Param.Choice -> form.row(p.label, JComboBox(p.options.toTypedArray()).apply {
                    selectedIndex = value
                    addActionListener { set(selectedIndex) }
                }, p.tip)
                is Param.Toggle -> form.full(JCheckBox(p.label, value != 0).apply {
                    toolTipText = p.tip
                    addActionListener { set(if (isSelected) 1 else 0) }
                })
                is Param.Color -> form.row(p.label, ColorField(value, p.label, set), p.tip)
                is Param.Flow -> buildFlow(layer, p)
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
        if (layer.effect.random) {
            buttons.add(JButton("Neu würfeln").apply {
                toolTipText = "Neuer Zufallswert: gleiche Einstellungen, anderes Ergebnis"
                addActionListener { layer.reseed(); host.layerChanged() }
            })
            buttons.add(javax.swing.Box.createHorizontalStrut(6))
        }
        buttons.add(JButton("Standardwerte").apply {
            addActionListener {
                layer.values.putAll(layer.effect.defaults())
                host.layerChanged()
                host.rebuildEditor()
            }
        })
        form.full(buttons)
    }

    /** Drawn flow strokes: they are edited on the canvas, here they can be undone or cleared. */
    private fun buildFlow(layer: EffectLayer, p: Param.Flow) {
        val strokes = FlowStrokes.parse(layer.texts[p.key] ?: "")
        form.row(p.label, JLabel(when (strokes.size) {
            0 -> "keine Striche"
            1 -> "1 Strich"
            else -> "${strokes.size} Striche"
        }), p.tip)
        fun store(changed: List<List<java.awt.geom.Point2D.Double>>) {
            layer.texts[p.key] = FlowStrokes.format(changed)
            host.layerChanged()
            host.rebuildEditor()
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Letzten Strich entfernen").apply {
            isEnabled = strokes.isNotEmpty()
            addActionListener { store(strokes.dropLast(1)) }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Alle löschen").apply {
            isEnabled = strokes.isNotEmpty()
            addActionListener { store(emptyList()) }
        })
        form.full(buttons)
        form.full(JCheckBox("Pfeile anzeigen", host.showFlow).apply {
            toolTipText = "Blendet die gezeichneten Pfeile auf der Leinwand ein oder aus; sie wirken trotzdem"
            addActionListener { host.showFlow = isSelected }
        })
        form.full(hint(
            (p.tip?.let { "$it. " } ?: "") +
                "Rechte Maustaste (oder Alt) wischt Pfeile weg. Ist die Maske eingeblendet, wird stattdessen die Maske bearbeitet.",
        ))
    }

    private fun buildImage(layer: ImageLayer) {
        form.section("Position & Grösse")
        val (cw, ch) = host.imageSize ?: (layer.image.width to layer.image.height)
        form.full(hint("Original ${layer.image.width} × ${layer.image.height} px, Leinwand $cw × $ch px"))
        fun changed() {
            host.layerChanged()
            host.rebuildEditor()
        }
        fun spinner(value: Double, min: Int, max: Int, set: (Int) -> Unit) =
            javax.swing.JSpinner(javax.swing.SpinnerNumberModel(value.roundToInt().coerceIn(min, max), min, max, 1)).apply {
                addChangeListener { set(this.value as Int) }
            }
        val limit = 100_000
        form.row("X", spinner(layer.x, -limit, limit) { if (it != layer.x.roundToInt()) { layer.x = it.toDouble(); host.layerChanged() } })
        form.row("Y", spinner(layer.y, -limit, limit) { if (it != layer.y.roundToInt()) { layer.y = it.toDouble(); host.layerChanged() } })
        form.row("Skalierung", SliderField(1, 1000, (layer.scale * 100).roundToInt(), 100, "%") { percent ->
            val s = percent / 100.0
            if ((layer.scale * 100).roundToInt() == percent) return@SliderField
            // scale around the center
            val b = layer.bounds
            layer.scale = s
            layer.x = b.centerX - layer.image.width * s / 2
            layer.y = b.centerY - layer.image.height * s / 2
            host.layerChanged()
        }, "Doppelklick auf den Regler: 100 %")
        form.full(JCheckBox("Glatt skalieren", layer.smooth).apply {
            toolTipText = "Aus: harte Pixel (Nearest Neighbour) beim Vergrössern"
            addActionListener { layer.smooth = isSelected; host.layerChanged() }
        })
        val buttons = JPanel(java.awt.GridLayout(2, 2, 6, 6)).apply { isOpaque = false }
        buttons.add(JButton("Einpassen").apply {
            toolTipText = "Ganzes Bild auf der Leinwand zeigen"
            addActionListener { layer.fitInto(cw, ch); changed() }
        })
        buttons.add(JButton("Füllen").apply {
            toolTipText = "Leinwand ganz bedecken (Ränder werden abgeschnitten)"
            addActionListener { layer.fitInto(cw, ch, cover = true); changed() }
        })
        buttons.add(JButton("Originalgrösse").apply {
            addActionListener { layer.scale = 1.0; layer.center(cw, ch); changed() }
        })
        buttons.add(JButton("Zentrieren").apply {
            addActionListener { layer.center(cw, ch); changed() }
        })
        form.full(buttons)
        form.full(hint("Im Bild ziehen verschiebt die Ebene, an den Ecken ziehen skaliert sie. Ist die Maske eingeblendet (Strg+M), wird stattdessen die Maske bearbeitet – zum Verschieben dann ausblenden oder Strg halten."))
    }

    private fun buildLayer() {
        form.section("Ebene")
        form.row("Deckkraft", SliderField(0, 100, layer.opacity, 100, "%") {
            layer.opacity = it
            host.layerChanged()
        })
        form.row("Mischmodus", JComboBox(BlendMode.entries.toTypedArray()).apply {
            selectedItem = layer.blend
            addActionListener {
                layer.blend = selectedItem as BlendMode
                host.layerChanged()
            }
        }, "Wie die Ebene mit dem darunter verrechnet wird")
    }

    private fun buildMask() {
        val mask = layer.mask
        form.section("Maske")
        form.row("Art", JComboBox(MaskMode.entries.toTypedArray()).apply {
            selectedItem = mask.mode
            name = MASK_MODE_BOX
            // arrow keys only move through the list; the choice is made with Enter or a click,
            // because every change rebuilds this editor
            putClientProperty("JComboBox.isTableCellEditor", true)
            addActionListener {
                val mode = selectedItem as MaskMode
                if (mode == mask.mode) return@addActionListener
                if (mode == MaskMode.BRUSH) ensurePainted()
                mask.mode = mode
                if (mode != MaskMode.OFF) host.showMask = true
                host.maskChanged()
                host.layerListChanged()
                host.rebuildEditor(focusMaskMode = true)
            }
        })
        if (mask.mode == MaskMode.OFF) {
            form.full(hint("Ohne Maske wirkt der Effekt auf das ganze Bild."))
            return
        }
        if (layer is ImageLayer) {
            form.full(hint("Die Maske schneidet das Bild aus, bevor die Effekte darüber wirken – sie können also über die Maskenkante hinaus laufen."))
        }
        form.full(JCheckBox("Maske umkehren", mask.invert).apply {
            addActionListener { mask.invert = isSelected; host.maskChanged() }
        })
        val thresholdField = SliderField(0, 100, mask.threshold, 50, "%") {
            mask.threshold = it
            host.maskChanged()
        }.apply { isEnabled = mask.hardEdge }
        form.full(JCheckBox("Harte Kante (nur Schwarz/Weiss)", mask.hardEdge).apply {
            toolTipText = "Alles ab der Schwelle bekommt den vollen Effekt, alles darunter keinen"
            addActionListener {
                mask.hardEdge = isSelected
                thresholdField.isEnabled = isSelected
                host.maskChanged()
            }
        })
        form.row("Schwelle", thresholdField, "Ab diesem Maskenwert wirkt der Effekt voll")
        form.full(JCheckBox("Maske anzeigen (rot = kein Effekt)", host.showMask).apply {
            toolTipText = "Blendet die rote Markierung ein oder aus; die Maske wirkt trotzdem (Strg+M)"
            addActionListener { host.showMask = isSelected }
        })

        when (mask.mode) {
            MaskMode.BRUSH -> buildMaskTools()
            MaskMode.LINEAR, MaskMode.RADIAL -> {
                form.full(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                    isOpaque = false
                    add(JButton("Verlauf zurücksetzen").apply {
                        addActionListener { mask.resetGradient(); host.maskChanged() }
                    })
                })
                form.full(hint(
                    if (mask.mode == MaskMode.LINEAR)
                        "Im Bild ziehen setzt den Verlauf neu: voller Effekt am weissen Punkt, keiner am schwarzen. Die Punkte lassen sich einzeln verschieben."
                    else
                        "Im Bild ziehen setzt den Kreis neu: voller Effekt in der Mitte (weiss), keiner ab dem Rand (schwarz)."
                ))
            }
            MaskMode.OFF -> {}
        }
    }

    /** Tool choice and settings for the painted mask. */
    private fun buildMaskTools() {
        val mask = layer.mask
        val brush = host.brush
        form.row("Werkzeug", JComboBox(MaskTool.entries.toTypedArray()).apply {
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
                form.row("Pinselgrösse", SliderField(1, 1500, brush.size, 120, "px") { brush.size = it })
                form.row("Härte", SliderField(0, 100, brush.hardness, 40, "%") { brush.hardness = it })
                form.row("Stärke", SliderField(1, 100, brush.strength, 60, "%") { brush.strength = it })
            }
            MaskTool.WAND -> {
                form.row("Toleranz", SliderField(0, 255, brush.tolerance, 32) { brush.tolerance = it },
                    "Wie stark eine Farbe von der angeklickten abweichen darf (je Kanal)")
                form.full(JCheckBox("Nur zusammenhängende Flächen", brush.contiguous).apply {
                    toolTipText = "Aus: wählt die ähnlichen Farben im ganzen Bild"
                    addActionListener { brush.contiguous = isSelected }
                })
                form.row("Weiche Kante", SliderField(0, 200, brush.feather, 0, "px") { brush.feather = it })
            }
            else -> form.row("Weiche Kante", SliderField(0, 200, brush.feather, 0, "px") { brush.feather = it })
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Alles füllen").apply {
            addActionListener { ensurePainted(); mask.fill(255); host.maskChanged() }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Leeren").apply {
            addActionListener { ensurePainted(); mask.fill(0); host.maskChanged() }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Aus Bildhelligkeit").apply {
            toolTipText = "Helle Bildteile bekommen den Effekt, dunkle nicht. Mit „Harte Kante“ entsteht eine scharfe Auswahl"
            addActionListener {
                host.maskTarget?.image?.let { mask.fromBrightness(it); host.maskChanged() }
            }
        })
        form.full(buttons)
        form.full(hint(when (brush.tool) {
            MaskTool.BRUSH -> "Linke Maustaste malt den Effekt hinein, rechte Maustaste (oder Alt) radiert."
            MaskTool.RECT, MaskTool.ELLIPSE -> "Aufziehen fügt den Bereich hinzu, mit rechter Maustaste (oder Alt) wird er abgezogen."
            MaskTool.LASSO -> "Freihand umfahren; beim Loslassen wird die Form geschlossen. Rechte Maustaste (oder Alt) zieht ab."
            MaskTool.WAND -> "Klick wählt ähnliche Farben des Originalbilds aus, rechte Maustaste (oder Alt) zieht sie ab."
        } + " Leertaste + Ziehen verschiebt die Ansicht."))
    }

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
}
