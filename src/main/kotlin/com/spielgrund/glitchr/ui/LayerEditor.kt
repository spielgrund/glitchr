package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.FlowStrokes
import com.spielgrund.glitchr.effects.Param
import com.spielgrund.glitchr.model.AnimKeys
import com.spielgrund.glitchr.model.BlendMode
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.GeneratorLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskMode
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
            is EffectLayer -> layer.effect.name to layer.effect.description
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
    }

    private fun buildParams(layer: ParamLayer, title: String) {
        form.section(title)
        for (p in layer.params) {
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
                    form.row(p.label, SliderField(p.min, max, value, p.default.coerceAtMost(max), p.unit, p.decimals, set), p.tip, anim(p.key))
                }
                is Param.Choice -> form.row(p.label, JComboBox(p.options.toTypedArray()).apply {
                    selectedIndex = value
                    addActionListener { set(selectedIndex) }
                }, p.tip, anim(p.key))
                is Param.Toggle -> form.full(JCheckBox(p.label, value != 0).apply {
                    toolTipText = p.tip
                    addActionListener { set(if (isSelected) 1 else 0) }
                }, anim(p.key))
                is Param.Color -> form.row(p.label, ColorField(value, p.label, set), p.tip, anim(p.key))
                is Param.Heading -> form.section(p.label)
                is Param.Flow -> buildFlow(layer, p)
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
                // texts too (ramps, curves, own text) – but drawn flow strokes stay
                val flows = layer.params.filterIsInstance<Param.Flow>().map { it.key }.toSet()
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
        fun spinner(value: Double, min: Int, max: Int, set: (Int) -> Unit) =
            javax.swing.JSpinner(javax.swing.SpinnerNumberModel(value.roundToInt().coerceIn(min, max), min, max, 1)).apply {
                addChangeListener { set(this.value as Int) }
            }
        val limit = 100_000
        form.row("X", spinner(layer.x, -limit, limit) { if (it != layer.x.roundToInt()) { layer.x = it.toDouble(); host.layerChanged() } }, anim = anim(AnimKeys.X))
        form.row("Y", spinner(layer.y, -limit, limit) { if (it != layer.y.roundToInt()) { layer.y = it.toDouble(); host.layerChanged() } }, anim = anim(AnimKeys.Y))
        form.row("Scale", SliderField(1, 1000, (layer.scale * 100).roundToInt(), 100, "%") { percent ->
            val s = percent / 100.0
            if ((layer.scale * 100).roundToInt() == percent) return@SliderField
            // scale around the center
            val b = layer.bounds
            layer.scale = s
            layer.x = b.centerX - layer.image.width * s / 2
            layer.y = b.centerY - layer.image.height * s / 2
            host.layerChanged()
        }, "Double-click the slider: 100 %", anim(AnimKeys.SCALE))
        form.row("Rotation", SliderField(-1800, 1800, (layer.rotation * 10).roundToInt(), 0, "°", 1) { tenths ->
            if ((layer.rotation * 10).roundToInt() == tenths) return@SliderField
            // around the middle of the picture: position and size stay
            layer.rotation = tenths / 10.0
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
        form.row("Type", JComboBox(MaskMode.entries.toTypedArray()).apply {
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
            form.full(hint("Without a mask the effect works on the whole picture."))
            return
        }
        if (layer is ImageLayer || layer is GeneratorLayer) {
            form.full(hint("The mask cuts out the image before the effects above work – so they can run beyond the mask edge."))
        }
        form.full(JCheckBox("Invert mask", mask.invert).apply {
            addActionListener { mask.invert = isSelected; host.maskChanged() }
        })
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
        form.row("Threshold", thresholdField, "From this mask value the effect works fully")
        form.full(JCheckBox("Show mask (red = no effect)", host.showMask).apply {
            toolTipText = "Shows or hides the red overlay; the mask works either way (Ctrl+M)"
            addActionListener { host.showMask = isSelected }
        })

        when (mask.mode) {
            MaskMode.BRUSH -> buildMaskTools()
            MaskMode.LINEAR, MaskMode.RADIAL -> {
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
            MaskMode.OFF -> {}
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
                form.row("Soft edge", SliderField(0, 200, brush.feather, 0, "px") { brush.feather = it })
            }
            else -> form.row("Soft edge", SliderField(0, 200, brush.feather, 0, "px") { brush.feather = it })
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        buttons.add(JButton("Fill all").apply {
            addActionListener { ensurePainted(); mask.fill(255); host.maskChanged() }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("Clear").apply {
            addActionListener { ensurePainted(); mask.fill(0); host.maskChanged() }
        })
        buttons.add(javax.swing.Box.createHorizontalStrut(6))
        buttons.add(JButton("From picture brightness").apply {
            toolTipText = "Bright parts of the picture get the effect, dark ones don't. With “Hard edge” this gives a sharp selection"
            addActionListener {
                host.maskTarget?.image?.let { mask.fromBrightness(it); host.maskChanged() }
            }
        })
        form.full(buttons)
        form.full(hint(when (brush.tool) {
            MaskTool.BRUSH -> "The left mouse button paints the effect in, the right mouse button (or Alt) erases."
            MaskTool.RECT, MaskTool.ELLIPSE -> "Dragging adds the area, with the right mouse button (or Alt) it is subtracted."
            MaskTool.LASSO -> "Trace freehand; on release the shape is closed. The right mouse button (or Alt) subtracts."
            MaskTool.WAND -> "A click selects similar colors of the original picture, the right mouse button (or Alt) subtracts them."
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
}
