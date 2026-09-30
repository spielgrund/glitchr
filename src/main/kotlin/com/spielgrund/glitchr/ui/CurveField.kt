package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.Curves
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editor for the tone curves (see [Curves]): a channel choice (RGB, red, green, blue) and
 * a square to drag points in. Clicking adds a point, dragging moves it, right click (or
 * dragging it far off the square) removes it; the end points stay at the left and right
 * edge. Every change goes to [onChange] as the curves' text.
 */
class CurveField(text: String, private val onChange: (String) -> Unit) : JPanel(BorderLayout(0, 4)) {
    private val curves = Curves.parse(text).map { it.toMutableList() }.toMutableList()
    private var channel = 0
    private val area = Area()

    init {
        isOpaque = false
        val channelBox = JComboBox(arrayOf("RGB", "Rot", "Grün", "Blau")).apply {
            addActionListener { channel = selectedIndex; area.repaint() }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(channelBox)
            add(javax.swing.Box.createHorizontalStrut(6))
            add(JButton("Zurücksetzen").apply {
                toolTipText = "Diese Kurve wieder gerade"
                addActionListener { curves[channel] = mutableListOf(0 to 0, 255 to 255); changed() }
            })
            add(javax.swing.Box.createHorizontalStrut(6))
            add(JButton("Alle").apply {
                toolTipText = "Alle Kurven wieder gerade"
                addActionListener { for (k in curves.indices) curves[k] = mutableListOf(0 to 0, 255 to 255); changed() }
            })
        }
        add(buttons, BorderLayout.NORTH)
        add(area, BorderLayout.CENTER)
    }

    private fun changed() {
        area.repaint()
        onChange(Curves.format(curves))
    }

    private inner class Area : JComponent() {
        private var dragged: Int? = null

        init {
            preferredSize = Dimension(220, 220)
            minimumSize = Dimension(120, 120)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val points = curves[channel]
                    val hit = points.indexOfFirst { p -> toScreen(p).let { abs(it.first - e.x) <= 7 && abs(it.second - e.y) <= 7 } }
                    if (SwingUtilities.isRightMouseButton(e)) {
                        // the end points can't be removed
                        if (hit > 0 && hit < points.size - 1) { points.removeAt(hit); changed() }
                        return
                    }
                    if (hit >= 0) { dragged = hit; return }
                    val (x, y) = toCurve(e.x, e.y)
                    if (x <= points.first().first || x >= points.last().first) return
                    val at = points.indexOfFirst { it.first > x }
                    points.add(at, x to y)
                    dragged = at
                    changed()
                }

                override fun mouseDragged(e: MouseEvent) {
                    val i = dragged ?: return
                    val points = curves[channel]
                    val (x, y) = toCurve(e.x, e.y)
                    val inner = i > 0 && i < points.size - 1
                    // dragged far off the square: remove (not the end points)
                    if (inner && (e.y < -30 || e.y > height + 30)) {
                        points.removeAt(i)
                        dragged = null
                        changed()
                        return
                    }
                    // the ends stay at the left and right edge, the others between their neighbours
                    val nx = when (i) {
                        0 -> 0
                        points.size - 1 -> 255
                        else -> x.coerceIn(points[i - 1].first + 1, points[i + 1].first - 1)
                    }
                    points[i] = nx to y
                    changed()
                }

                override fun mouseReleased(e: MouseEvent) {
                    dragged = null
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            toolTipText = "Klicken setzt einen Punkt, Ziehen verschiebt ihn, Rechtsklick entfernt ihn"
        }

        private val pad = 6

        private fun toScreen(p: Pair<Int, Int>): Pair<Int, Int> {
            val s = side() - 2 * pad
            return pad + (p.first * s / 255.0).roundToInt() to pad + ((255 - p.second) * s / 255.0).roundToInt()
        }

        private fun toCurve(x: Int, y: Int): Pair<Int, Int> {
            val s = side() - 2 * pad
            return ((x - pad) * 255.0 / s).roundToInt().coerceIn(0, 255) to (255 - (y - pad) * 255.0 / s).roundToInt().coerceIn(0, 255)
        }

        /** Side of the square. */
        private fun side() = minOf(width, height)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = side() - 2 * pad
            g2.color = Color(0x1E1F22)
            g2.fillRect(pad, pad, s, s)
            // quarter grid and the straight line
            g2.color = Color(0x3A3C40)
            for (k in 1..3) {
                val q = pad + s * k / 4
                g2.drawLine(q, pad, q, pad + s)
                g2.drawLine(pad, q, pad + s, q)
            }
            g2.drawLine(pad, pad + s, pad + s, pad)
            val colors = listOf(Color(0xE6E6E6), Color(0xFF5555), Color(0x55DD66), Color(0x5599FF))
            // the other curves faint, the edited one on top
            for (k in (curves.indices.filter { it != channel }) + channel) {
                val lut = Curves.lut(curves[k])
                val path = Path2D.Double()
                for (i in 0..256) {
                    val px = pad + s * i / 256.0
                    val py = pad + s * (1 - lut[i])
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                val c = colors[k]
                g2.color = if (k == channel) c else Color(c.red, c.green, c.blue, 70)
                g2.stroke = BasicStroke(if (k == channel) 1.8f else 1f)
                g2.draw(path)
            }
            g2.stroke = BasicStroke(1f)
            for (p in curves[channel]) {
                val (x, y) = toScreen(p)
                g2.color = Color.WHITE
                g2.fillRect(x - 3, y - 3, 7, 7)
                g2.color = Color.BLACK
                g2.drawRect(x - 3, y - 3, 7, 7)
            }
            g2.dispose()
        }
    }
}
