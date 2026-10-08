package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.GeneratorLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.startsGroup
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The layer stack, top layer first as in other image editors. Source layers (pictures
 * and generators) show a thumbnail; the effects that work on them are indented above
 * them. Clicking a row selects the layer, the checkbox shows or hides it.
 */
class LayerList(
    private val onSelect: (Layer) -> Unit,
    private val onToggleVisible: (Layer) -> Unit,
    /** The last picture of a generator layer, for its thumbnail. */
    private val generated: (GeneratorLayer) -> Pixels? = { null },
) : JPanel(), Scrollable {
    private val thumbnails = WeakHashMap<Pixels, ImageIcon>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    /** Redraws the rows; [layers] bottom first. */
    fun update(layers: List<Layer>, selected: Layer?) {
        removeAll()
        if (layers.isEmpty()) {
            add(JLabel("<html>No layers yet.<br>Open an image or drag it here,<br>or start without an image via “New…”.</html>").apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            })
        }
        // effects need a group below them to work on; adjustment layers work on whatever is below
        val firstGroup = layers.indexOfFirst { it.startsGroup }
        for ((index, layer) in layers.withIndex().reversed()) {
            val orphan = layer is EffectLayer && !layer.adjustment && (firstGroup < 0 || index < firstGroup)
            add(row(layer, layer === selected, orphan))
        }
        add(Box.createVerticalGlue())
        revalidate()
        repaint()
    }

    private fun row(layer: Layer, selected: Boolean, orphan: Boolean): JPanel {
        val row = JPanel(BorderLayout(6, 0))
        // source and adjustment layers head a group: bold and not indented
        val source = layer.startsGroup
        val indent = if (source) 6 else 22
        row.border = BorderFactory.createEmptyBorder(if (source) 4 else 2, indent, if (source) 4 else 2, 8)
        row.maximumSize = Dimension(Int.MAX_VALUE, if (source) 44 else 30)
        row.alignmentX = LEFT_ALIGNMENT
        if (selected) row.background = UIManager.getColor("List.selectionBackground")
        val dim = UIManager.getColor("Label.disabledForeground")
        val fg = if (selected) UIManager.getColor("List.selectionForeground") else UIManager.getColor("List.foreground")

        val west = JPanel(BorderLayout(4, 0)).apply { isOpaque = false }
        west.add(JCheckBox().apply {
            isSelected = layer.visible
            isOpaque = false
            toolTipText = "Show/hide layer"
            addActionListener { onToggleVisible(layer) }
        }, BorderLayout.WEST)
        when (layer) {
            is ImageLayer -> west.add(JLabel(thumbnail(layer.image)), BorderLayout.CENTER)
            is GeneratorLayer -> west.add(JLabel(generated(layer)?.let(::thumbnail) ?: emptyThumbnail), BorderLayout.CENTER)
            is EffectLayer -> {}
        }
        row.add(west, BorderLayout.WEST)

        val title = when (layer) {
            is ImageLayer -> layer.name
            is GeneratorLayer -> if (layer.name == layer.generator.name) layer.name else "${layer.name}  ·  ${layer.generator.name}"
            is EffectLayer -> (if (layer.adjustment) "◑ " else "↳ ") +
                if (layer.name == layer.effect.name) layer.name else "${layer.name}  ·  ${layer.effect.name}"
        }
        row.add(JLabel(title).apply {
            foreground = if (!layer.visible || orphan) dim else fg
            if (source) font = font.deriveFont(java.awt.Font.BOLD)
            if (layer is GeneratorLayer) toolTipText = "Generator: ${layer.generator.description}"
            if (layer is EffectLayer && layer.adjustment) toolTipText = "Adjustment layer: ${layer.effect.name} works on all layers below"
            if (orphan) toolTipText = "No image or generator layer below – this effect has nothing to work on"
        }, BorderLayout.CENTER)

        val info = buildList {
            if (layer.opacity < 100) add("${layer.opacity} %")
            when (layer.mask.mode) {
                MaskMode.OFF -> {}
                MaskMode.BRUSH -> add("◐ Brush")
                MaskMode.LINEAR -> add("◐ Gradient")
                MaskMode.RADIAL -> add("◐ Radial")
            }
        }.joinToString("  ")
        if (info.isNotEmpty()) row.add(JLabel(info).apply {
            foreground = dim
            toolTipText = "Mask: ${layer.mask.mode.label}"
        }, BorderLayout.EAST)

        row.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = onSelect(layer)
        })
        return row
    }

    // Always as wide as the sidebar, so long names are shortened with "…" instead of scrolling sideways.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = 16
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height - 32
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false

    /** Placeholder while a generator's picture is still being made. */
    private val emptyThumbnail = ImageIcon(BufferedImage(36, 36, BufferedImage.TYPE_INT_ARGB))

    private fun thumbnail(image: Pixels): ImageIcon = thumbnails.getOrPut(image) {
        val s = 36.0 / max(image.width, image.height)
        val w = max(1, (image.width * s).roundToInt())
        val h = max(1, (image.height * s).roundToInt())
        val thumb = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        thumb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image.toImage().getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }
        ImageIcon(thumb)
    }
}
