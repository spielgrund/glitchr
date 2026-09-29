package com.spielgrund.glitchr.ui

import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

/** Two-column form (label | control) with section headings. */
class Form : JPanel(GridBagLayout()) {
    private var row = 0

    fun section(title: String) {
        val label = JLabel(title).apply { font = font.deriveFont(Font.BOLD) }
        add(label, gbc(0, 2).apply { insets = Insets(if (row == 0) 4 else 14, 0, 2, 0) })
        add(JSeparator(), gbc(0, 2).apply { insets = Insets(0, 0, 4, 0) })
    }

    fun row(label: String, control: JComponent, tip: String? = null) {
        val l = JLabel(label)
        tip?.let { l.toolTipText = it; control.toolTipText = it }
        add(l, gbc(0, 1).apply { weightx = 0.0; insets = Insets(2, 0, 2, 8) })
        add(control, gbc(1, 1))
    }

    fun full(control: JComponent) = add(control, gbc(0, 2))

    /** Pushes the rows to the top. */
    fun end() = add(JPanel().apply { isOpaque = false }, gbc(0, 2).apply { weighty = 1.0; fill = GridBagConstraints.BOTH })

    private fun gbc(x: Int, span: Int) = GridBagConstraints().apply {
        gridx = x
        gridy = if (x + span >= 2) row++ else row
        gridwidth = span
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.WEST
        insets = Insets(2, 0, 2, 0)
    }
}

/** A color swatch with its hex value; clicking it opens a color chooser. [onChange] gets 0xRRGGBB. */
class ColorField(rgb: Int, private val title: String, private val onChange: (Int) -> Unit) : javax.swing.JButton() {
    private var rgb = rgb and 0xFFFFFF

    init {
        horizontalAlignment = LEFT
        toolTipText = "Klicken, um die Farbe zu wählen"
        update()
        addActionListener {
            val chosen = javax.swing.JColorChooser.showDialog(this, title, java.awt.Color(this.rgb)) ?: return@addActionListener
            this.rgb = chosen.rgb and 0xFFFFFF
            update()
            onChange(this.rgb)
        }
    }

    private fun update() {
        text = "#%06X".format(rgb)
        icon = object : javax.swing.Icon {
            override fun getIconWidth() = 28
            override fun getIconHeight() = 14
            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
                g.color = java.awt.Color(rgb)
                g.fillRect(x, y, iconWidth, iconHeight)
                g.color = java.awt.Color.GRAY
                g.drawRect(x, y, iconWidth - 1, iconHeight - 1)
            }
        }
    }
}

/**
 * Slider with an editable number field next to it; double-clicking the slider resets it
 * to [default]. [onChange] is called with every new value.
 */
class SliderField(min: Int, max: Int, value: Int, private val default: Int, unit: String = "", onChange: (Int) -> Unit) :
    JPanel(GridBagLayout()) {
    private val slider = JSlider(min, max, value.coerceIn(min, max))
    private val spinner = JSpinner(SpinnerNumberModel(value.coerceIn(min, max), min, max, 1))

    init {
        isOpaque = false
        slider.preferredSize = Dimension(80, slider.preferredSize.height)
        (spinner.editor as JSpinner.DefaultEditor).textField.columns = 4
        slider.addChangeListener {
            if (spinner.value != slider.value) spinner.value = slider.value
        }
        spinner.addChangeListener {
            val v = spinner.value as Int
            if (slider.value != v) slider.value = v
            onChange(v)
        }
        slider.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) slider.value = default
            }
        })
        slider.toolTipText = "Doppelklick: Standardwert"
        add(slider, GridBagConstraints().apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
        add(spinner, GridBagConstraints().apply { insets = Insets(0, 4, 0, 0) })
        if (unit.isNotBlank()) add(JLabel(unit.trim()), GridBagConstraints().apply { insets = Insets(0, 3, 0, 0) })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        slider.isEnabled = enabled
        spinner.isEnabled = enabled
    }

    override fun setToolTipText(text: String?) {
        super.setToolTipText(text)
        spinner.toolTipText = text
    }
}
