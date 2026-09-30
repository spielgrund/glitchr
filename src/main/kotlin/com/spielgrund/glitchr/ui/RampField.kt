package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.ColorRamp
import com.spielgrund.glitchr.effects.ColorStop
import com.spielgrund.glitchr.effects.RampPalette
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JColorChooser
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs

private const val INSET = 10
private const val BAR_HEIGHT = 30
private const val HANDLE_W = 10
private const val HANDLE_H = 14

/**
 * A color ramp with a "Vorlage…" box and "Umkehren" above its [GradientEditor]; every
 * change goes to [onChange] as the ramp's text.
 */
class RampField(text: String, onChange: (String) -> Unit) : JPanel(BorderLayout(0, 4)) {
    init {
        isOpaque = false
        val editor = GradientEditor(ColorRamp.parse(text)) { onChange(it.format()) }
        val presets = JComboBox<Any>().apply {
            addItem("Vorlage…")
            RampPalette.entries.forEach(::addItem)
            addActionListener {
                // a preset applies once; the box snaps back, later edits make it stale
                val preset = selectedItem as? RampPalette ?: return@addActionListener
                selectedIndex = 0
                editor.gradient = preset.ramp
            }
        }
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(presets)
            add(javax.swing.Box.createHorizontalStrut(6))
            add(JButton("Umkehren").apply { addActionListener { editor.gradient = editor.gradient.reversed() } })
        }, BorderLayout.NORTH)
        add(editor, BorderLayout.CENTER)
    }
}

/**
 * Edits a color gradient (taken over from Noiser): click to add a stop, drag to move it,
 * double-click to pick its color, right-click to remove it.
 */
class GradientEditor(initial: ColorRamp, private val onChange: (ColorRamp) -> Unit) : JComponent() {
    private val stops = initial.stops.toMutableList()
    private var dragging = -1
    private var selected = -1

    var gradient: ColorRamp
        get() = ColorRamp(stops)
        set(value) {
            stops.clear()
            stops += value.stops
            selected = -1
            repaint()
            onChange(value)
        }

    init {
        preferredSize = Dimension(300, BAR_HEIGHT + HANDLE_H + 8)
        minimumSize = Dimension(120, BAR_HEIGHT + HANDLE_H + 8)
        toolTipText = "<html>Klicken: Farbpunkt hinzufügen · Ziehen: verschieben<br>" +
            "Doppelklick: Farbe wählen · Rechtsklick: entfernen</html>"

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                val hit = hitTest(e.x)
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (hit >= 0 && stops.size > 2) {
                        stops.removeAt(hit)
                        selected = -1
                        changed()
                    }
                    return
                }
                if (hit >= 0 && e.clickCount >= 2) {
                    dragging = -1
                    pickColor(hit)
                    return
                }
                if (hit >= 0) {
                    dragging = hit
                    selected = hit
                    repaint()
                    return
                }
                val pos = xToPos(e.x)
                stops += ColorStop(pos, gradient.colorAt(pos))
                dragging = stops.lastIndex
                selected = dragging
                changed()
            }

            override fun mouseDragged(e: MouseEvent) {
                if (dragging < 0 || !isEnabled) return
                stops[dragging] = stops[dragging].copy(pos = xToPos(e.x))
                changed()
            }

            override fun mouseReleased(e: MouseEvent) {
                dragging = -1
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private val barWidth get() = (width - 2 * INSET).coerceAtLeast(1)
    private fun xToPos(x: Int) = ((x - INSET).toDouble() / barWidth).coerceIn(0.0, 1.0)
    private fun posToX(pos: Double) = INSET + (pos * barWidth).toInt()

    private fun hitTest(x: Int): Int {
        // Prefer the selected stop so stacked stops can still be pulled apart.
        if (selected in stops.indices && abs(posToX(stops[selected].pos) - x) <= HANDLE_W / 2 + 2) return selected
        return stops.indices.minByOrNull { abs(posToX(stops[it].pos) - x) }
            ?.takeIf { abs(posToX(stops[it].pos) - x) <= HANDLE_W / 2 + 2 } ?: -1
    }

    private fun pickColor(index: Int) {
        val color = JColorChooser.showDialog(this, "Farbe wählen", Color(stops[index].rgb)) ?: return
        stops[index] = stops[index].copy(rgb = color.rgb and 0xffffff)
        selected = index
        changed()
    }

    private fun changed() {
        repaint()
        onChange(gradient)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (!isEnabled) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f)
        val current = gradient
        for (x in 0 until barWidth) {
            g2.color = Color(current.colorAt(x.toDouble() / barWidth))
            g2.drawLine(INSET + x, 2, INSET + x, 2 + BAR_HEIGHT)
        }
        g2.color = Color(0x55585c)
        g2.drawRect(INSET - 1, 1, barWidth + 1, BAR_HEIGHT + 1)

        val top = BAR_HEIGHT + 4
        stops.forEachIndexed { i, stop ->
            val x = posToX(stop.pos)
            val xs = intArrayOf(x, x + HANDLE_W / 2, x + HANDLE_W / 2, x - HANDLE_W / 2, x - HANDLE_W / 2)
            val ys = intArrayOf(top, top + 4, top + HANDLE_H, top + HANDLE_H, top + 4)
            g2.color = Color(stop.rgb)
            g2.fillPolygon(xs, ys, xs.size)
            g2.stroke = BasicStroke(if (i == selected) 2f else 1f)
            g2.color = if (i == selected) Color.WHITE else Color(0x8a8d91)
            g2.drawPolygon(xs, ys, xs.size)
        }
    }
}
