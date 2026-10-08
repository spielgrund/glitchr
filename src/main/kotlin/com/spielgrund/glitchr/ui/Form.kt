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

/** Whether an animatable setting has keyframes, and one at the current frame. */
enum class KeyState { NONE, ANIMATED, KEY }

/**
 * Animation of one setting in the editor: a diamond before its label shows [state]
 * (◆ keyframe here, ◇ animated), clicking it calls [toggle], right-clicking the row opens [menu].
 */
class AnimHook(val state: () -> KeyState, val toggle: () -> Unit, val menu: () -> javax.swing.JPopupMenu)

/** The diamond before an animatable setting; [refresh] after the frame or the keyframes changed. */
class KeyMarker(private val hook: AnimHook) : JLabel() {
    init {
        preferredSize = Dimension(14, 14)
        minimumSize = preferredSize
        horizontalAlignment = CENTER
        foreground = javax.swing.UIManager.getColor("Component.accentColor") ?: java.awt.Color(0x4C9AFF)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) hook.toggle()
            }
        })
        refresh()
    }

    fun refresh() {
        val s = hook.state()
        text = when (s) {
            KeyState.NONE -> ""
            KeyState.ANIMATED -> "◇"
            KeyState.KEY -> "◆"
        }
        toolTipText = when (s) {
            KeyState.NONE -> null
            KeyState.ANIMATED -> "Animated – click: keyframe at this frame"
            KeyState.KEY -> "Keyframe at this frame – click: remove it"
        }
    }
}

/** Two-column form (label | control) with section headings. */
class Form : JPanel(GridBagLayout()) {
    private var row = 0

    /** The diamonds of the animatable rows, to update them when the frame changes. */
    val markers = mutableListOf<KeyMarker>()

    fun section(title: String) {
        val label = JLabel(title).apply { font = font.deriveFont(Font.BOLD) }
        add(label, gbc(0, 2).apply { insets = Insets(if (row == 0) 4 else 14, 0, 2, 0) })
        add(JSeparator(), gbc(0, 2).apply { insets = Insets(0, 0, 4, 0) })
    }

    fun row(label: String, control: JComponent, tip: String? = null, anim: AnimHook? = null) {
        val l = JLabel(label)
        tip?.let { l.toolTipText = it; control.toolTipText = it }
        // labels without a diamond keep its place free, so all labels line up
        if (anim == null) l.border = javax.swing.BorderFactory.createEmptyBorder(0, 16, 0, 0)
        add(withMarker(l, anim, control), gbc(0, 1).apply { weightx = 0.0; insets = Insets(2, 0, 2, 8) })
        add(control, gbc(1, 1))
    }

    fun full(control: JComponent, anim: AnimHook? = null) = add(withMarker(control, anim, control), gbc(0, 2))

    /** [label] with the diamond before it; right-clicking the label or [control] opens the animation menu. */
    private fun withMarker(label: JComponent, anim: AnimHook?, control: JComponent): JComponent {
        if (anim == null) return label
        val marker = KeyMarker(anim).also(markers::add)
        val popup = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = show(e)
            override fun mouseReleased(e: MouseEvent) = show(e)
            private fun show(e: MouseEvent) {
                if (e.isPopupTrigger) anim.menu().show(e.component, e.x, e.y)
            }
        }
        fun install(c: java.awt.Component) {
            c.addMouseListener(popup)
            (c as? java.awt.Container)?.components?.forEach(::install)
        }
        install(label)
        if (control !== label) install(control)
        install(marker)
        return JPanel(java.awt.BorderLayout(2, 0)).apply {
            isOpaque = false
            add(marker, java.awt.BorderLayout.WEST)
            add(label, java.awt.BorderLayout.CENTER)
        }
    }

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
        toolTipText = "Click to choose the color"
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
/**
 * Slider with a number field for a whole-number value. With [decimals], the field shows
 * the value divided by 10^decimals (and steps by the smallest decimal place).
 */
class SliderField(
    min: Int, max: Int, value: Int, private val default: Int, unit: String = "", decimals: Int = 0, onChange: (Int) -> Unit,
) : JPanel(GridBagLayout()) {
    private val factor = Math.pow(10.0, decimals.toDouble())
    private val slider = JSlider(min, max, value.coerceIn(min, max))
    private val spinner = if (decimals == 0) JSpinner(SpinnerNumberModel(value.coerceIn(min, max), min, max, 1))
    else JSpinner(SpinnerNumberModel(value.coerceIn(min, max) / factor, min / factor, max / factor, 1 / factor)).apply {
        editor = JSpinner.NumberEditor(this, "0." + "0".repeat(decimals))
    }

    /** The spinner's value as stored whole number. */
    private val spinnerValue get() = ((spinner.value as Number).toDouble() * factor).let { Math.round(it).toInt() }

    init {
        isOpaque = false
        slider.preferredSize = Dimension(80, slider.preferredSize.height)
        (spinner.editor as JSpinner.DefaultEditor).textField.columns = 4 + decimals
        slider.addChangeListener {
            if (spinnerValue != slider.value) spinner.value = if (decimals == 0) slider.value else slider.value / factor
        }
        spinner.addChangeListener {
            val v = spinnerValue
            if (slider.value != v) slider.value = v
            onChange(v)
        }
        slider.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) slider.value = default
            }
        })
        slider.toolTipText = "Double-click: default value"
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
