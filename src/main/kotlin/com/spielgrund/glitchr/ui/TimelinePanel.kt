package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.model.Animator
import com.spielgrund.glitchr.model.Easing
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.Timeline
import com.spielgrund.glitchr.model.Track
import com.spielgrund.glitchr.model.ValueKind
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** What the timeline needs from the main window. */
interface TimelineHost {
    val timeline: Timeline?
    val currentFrame: Int
    val isPlaying: Boolean
    var loopPlayback: Boolean

    /** Bottom layer first. */
    val timelineLayers: List<Layer>
    val selectedLayer: Layer?

    fun seek(frame: Int)
    fun togglePlay()
    fun selectLayer(layer: Layer)

    /** The timeline changed keyframes directly: the layers must follow. */
    fun keysEdited()

    fun createAnimation()
    fun animationSettings()

    /** Shows times in bars and beats ([beats]) or in seconds. */
    fun setBeats(beats: Boolean)
    fun exportAnimation()

    /** Whether [frame] is rendered and ready to play. */
    fun isCached(frame: Int): Boolean
}

/** Time display: 0:01.24 */
internal fun timecode(frame: Int, fps: Int) = Timeline(fps, 1).position(frame)

/** 120, or 128.5 – without needless decimals. */
internal fun bpmText(bpm: Double) = "%.2f".format(java.util.Locale.ROOT, bpm).trimEnd('0').trimEnd('.')

/** The frame counter: frame, position and length, and the rate in fps or BPM. */
internal fun counter(t: Timeline, frame: Int): String {
    val rate = if (t.beats) "${bpmText(t.bpm)} BPM · ${t.fps} fps" else "${t.fps} fps"
    // every number as wide as its largest value, so the counter keeps its width and the buttons stay put
    val number = (frame + 1).toString().padStart(t.frames.toString().length, '0')
    val position = t.position(frame).padStart(positionWidth(t))
    return "Frame %s / %d   %s / %s   %s".format(number, t.frames, position, t.length(), rate)
}

private var widthCache: Pair<Timeline, Int>? = null

/** The widest [Timeline.position] text of [t]'s frames (remembered, playback asks every frame). */
private fun positionWidth(t: Timeline): Int {
    widthCache?.let { (cached, width) -> if (cached == t) return width }
    val width = (0 until t.frames).maxOf { t.position(it).length }
    widthCache = t to width
    return width
}

/**
 * The timeline below the canvas: transport buttons, a time ruler with the playhead, and
 * the animated settings of the layers with their keyframes. Without an animation it only
 * offers to create one.
 */
class TimelinePanel(private val host: TimelineHost) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val tracks = TrackView()
    private val frameLabel = JLabel()
    private val playButton = button("▶", "Play / pause (Ctrl+P)") { host.togglePlay() }
    private val loopBox = JCheckBox("Loop", host.loopPlayback).apply {
        isFocusable = false
        toolTipText = "Playback starts over at the end"
        addActionListener { host.loopPlayback = isSelected }
    }
    private val beatsButton = javax.swing.JToggleButton("♩ BPM").apply {
        isFocusable = false
        toolTipText = "Times in bars and beats instead of seconds – tempo and time signature under Settings…"
        addActionListener { host.setBeats(isSelected) }
    }
    private val snapButton = javax.swing.JToggleButton("⇥ Snap", true).apply {
        isFocusable = false
        toolTipText = "Keyframes and the playhead snap to beats (bars & beats only) – Shift while dragging snaps the other way"
    }
    private val controls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 3))

    init {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))
        controls.add(button("|◀", "First frame") { host.seek(0) })
        controls.add(button("◆◀", "Previous keyframe") { jumpKey(-1) })
        controls.add(button("◀", "Previous frame (Ctrl+Left)") { host.seek(host.currentFrame - 1) })
        controls.add(playButton)
        controls.add(button("▶", "Next frame (Ctrl+Right)") { host.seek(host.currentFrame + 1) })
        controls.add(button("▶◆", "Next keyframe") { jumpKey(1) })
        controls.add(button("▶|", "Last frame") { host.timeline?.let { host.seek(it.frames - 1) } })
        controls.add(loopBox)
        controls.add(javax.swing.Box.createHorizontalStrut(8))
        controls.add(frameLabel.apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size) })
        controls.add(javax.swing.Box.createHorizontalStrut(8))
        controls.add(button("All linear", "Sets every keyframe of all layers to linear: even speed to the next keyframe") { setEasingAll(Easing.LINEAR) })
        controls.add(button("All ease", "Sets every keyframe of all layers to ease in/out: starts and ends slowly") { setEasingAll(Easing.EASE) })
        controls.add(javax.swing.Box.createHorizontalStrut(8))
        controls.add(beatsButton)
        controls.add(snapButton)
        controls.add(button("Settings…", "Length, frame rate and tempo of the animation") { host.animationSettings() })
        controls.add(button("Export…", "Save the animation as GIF, MP4 or PNG sequence (Ctrl+Shift+E)") { host.exportAnimation() })

        val empty = JPanel(FlowLayout(FlowLayout.LEFT, 10, 10)).apply {
            add(JLabel("No animation yet."))
            add(button("Create animation…", "Give the picture a length; then right-click any setting → Add keyframe") { host.createAnimation() })
            add(JLabel("<html><span style='color:gray'>Or right-click a setting → Add keyframe</span></html>"))
        }
        body.add(empty, "empty")
        body.add(JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(JScrollPane(tracks).apply {
                border = BorderFactory.createEmptyBorder()
                verticalScrollBar.unitIncrement = 16
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }, "tracks")
        add(body, BorderLayout.CENTER)
        preferredSize = Dimension(0, 190)
        refresh()
    }

    private fun setEasingAll(easing: Easing) {
        if (Animator.setEasingAll(host.timelineLayers, easing)) host.keysEdited()
    }

    /** After the frame, the keyframes, the layers or the animation changed. */
    fun refresh() {
        val t = host.timeline
        cards.show(body, if (t == null) "empty" else "tracks")
        if (t != null) {
            frameLabel.text = counter(t, host.currentFrame)
            beatsButton.isSelected = t.beats
            snapButton.isEnabled = t.beats
        }
        playButton.text = if (host.isPlaying) "❚❚" else "▶"
        loopBox.isSelected = host.loopPlayback
        tracks.rebuild()
    }

    /** Only the playhead and the rendered-frames bar moved. */
    fun refreshPlayhead() {
        host.timeline?.let { t ->
            frameLabel.text = counter(t, host.currentFrame)
            beatsButton.isSelected = t.beats
            snapButton.isEnabled = t.beats
        }
        playButton.text = if (host.isPlaying) "❚❚" else "▶"
        tracks.repaint()
    }

    /** Jumps to the previous (-1) or next (+1) keyframe of the selected layer, or of all layers. */
    private fun jumpKey(direction: Int) {
        val layers = host.selectedLayer?.takeIf { it.tracks.isNotEmpty() }?.let(::listOf) ?: host.timelineLayers
        val frames = layers.flatMap { l -> l.tracks.values.flatMap { t -> t.keys.map { it.frame } } }.distinct().sorted()
        val now = host.currentFrame
        val target = if (direction < 0) frames.lastOrNull { it < now } else frames.firstOrNull { it > now }
        target?.let(host::seek)
    }

    private fun button(text: String, tip: String, action: () -> Unit) = JButton(text).apply {
        isFocusable = false
        toolTipText = tip
        margin = java.awt.Insets(1, 6, 1, 6)
        addActionListener { action() }
    }

    // ------------------------------------------------------------------ tracks

    private sealed class Row(val layer: Layer) {
        class LayerRow(layer: Layer) : Row(layer)
        class PropRow(layer: Layer, val key: String) : Row(layer)
    }

    private data class Sel(val layerId: Int, val key: String, val frame: Int)

    /** The ruler and the rows of keyframes; a fixed label column on the left, the time axis fills the rest. */
    private inner class TrackView : JComponent(), javax.swing.Scrollable {
        private val labelWidth = 200
        private val rulerHeight = 26
        private val rowHeight = 20
        private val pad = 10
        private var rows = emptyList<Row>()
        private val selection = mutableSetOf<Sel>()

        private var scrubbing = false
        private var dragStart: Int? = null
        private var dragDelta = 0

        private val accent get() = UIManager.getColor("Component.accentColor") ?: Color(0x4C9AFF)

        init {
            isFocusable = true
            toolTipText = ""
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = pressed(e)
                override fun mouseDragged(e: MouseEvent) = dragged(e)
                override fun mouseReleased(e: MouseEvent) = released(e)
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) keyAt(e.x, e.y)?.let { host.seek(it.frame) }
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            fun bind(key: Int, mods: Int, name: String, action: () -> Unit) {
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, mods), name)
                actionMap.put(name, object : javax.swing.AbstractAction() {
                    override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
                })
            }
            bind(KeyEvent.VK_DELETE, 0, "delete") { deleteSelected() }
            bind(KeyEvent.VK_BACK_SPACE, 0, "delete2") { deleteSelected() }
            bind(KeyEvent.VK_A, java.awt.event.InputEvent.CTRL_DOWN_MASK, "all") { selectAll() }
        }

        fun rebuild() {
            val list = mutableListOf<Row>()
            for (layer in host.timelineLayers.asReversed()) {
                if (layer.tracks.isEmpty() && layer !== host.selectedLayer) continue
                list += Row.LayerRow(layer)
                for (key in layer.animatableKeys()) if (key in layer.tracks) list += Row.PropRow(layer, key)
            }
            rows = list
            // keyframes that no longer exist are no longer selected
            val existing = list.filterIsInstance<Row.PropRow>().flatMap { r -> r.layer.tracks[r.key]!!.keys.map { Sel(r.layer.id, r.key, it.frame) } }.toSet()
            selection.retainAll(existing)
            revalidate()
            repaint()
        }

        override fun getPreferredSize() = Dimension(400, rulerHeight + max(rows.size, 1) * rowHeight + 8)
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = rowHeight
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height - rowHeight
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false

        private val frames get() = host.timeline?.frames ?: 1
        private val trackLeft get() = labelWidth + pad
        private val trackWidth get() = max(1, width - trackLeft - pad)
        private fun xOf(frame: Int) = trackLeft + frame.toDouble() / max(1, frames - 1) * trackWidth
        private fun frameAt(x: Int) = ((x - trackLeft).toDouble() / trackWidth * max(1, frames - 1)).roundToInt().coerceIn(0, frames - 1)

        /** The frame under the mouse; in beats with snapping on the nearest beat. Shift flips snapping. */
        private fun snappedAt(e: MouseEvent): Int {
            val frame = frameAt(e.x)
            return if (snapButton.isSelected != e.isShiftDown) host.timeline?.snap(frame) ?: frame else frame
        }

        private fun rowAt(y: Int) = ((y - rulerHeight) / rowHeight).takeIf { y >= rulerHeight }?.let(rows::getOrNull)

        /** The keyframe under the mouse, in a property row. */
        private fun keyAt(x: Int, y: Int): Sel? {
            val row = rowAt(y) as? Row.PropRow ?: return null
            val track = row.layer.tracks[row.key] ?: return null
            val hit = track.keys.minByOrNull { abs(xOf(it.frame) - x) } ?: return null
            return if (abs(xOf(hit.frame) - x) <= 6) Sel(row.layer.id, row.key, hit.frame) else null
        }

        private fun pressed(e: MouseEvent) {
            requestFocusInWindow()
            val row = rowAt(e.y)
            if (e.x < labelWidth) {
                if (row != null) {
                    host.selectLayer(row.layer)
                    if (e.isPopupTrigger && row is Row.PropRow) propertyMenu(row).show(this, e.x, e.y)
                }
                return
            }
            val key = keyAt(e.x, e.y)
            if (key != null) {
                if (e.isShiftDown || e.isControlDown) {
                    if (!selection.remove(key)) selection += key
                } else if (key !in selection) {
                    selection.clear()
                    selection += key
                }
                if (e.isPopupTrigger) {
                    keyMenu().show(this, e.x, e.y)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStart = key.frame
                    dragDelta = 0
                }
                repaint()
                return
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return
            if (!e.isShiftDown) selection.clear()
            scrubbing = true
            host.seek(snappedAt(e))
            repaint()
        }

        private fun dragged(e: MouseEvent) {
            val start = dragStart
            when {
                scrubbing -> host.seek(snappedAt(e))
                start != null -> {
                    // the grabbed keyframe lands on a beat, the others move along; the selection
                    // may move only as far as the first and last frame allow
                    val lo = selection.minOf { it.frame }
                    val hi = selection.maxOf { it.frame }
                    dragDelta = (snappedAt(e) - start).coerceIn(-lo, frames - 1 - hi)
                    repaint()
                }
            }
        }

        private fun released(e: MouseEvent) {
            if (e.isPopupTrigger) {
                val key = keyAt(e.x, e.y)
                val row = rowAt(e.y)
                if (key != null) {
                    if (key !in selection) { selection.clear(); selection += key }
                    keyMenu().show(this, e.x, e.y)
                } else if (e.x < labelWidth && row is Row.PropRow) propertyMenu(row).show(this, e.x, e.y)
            }
            if (dragStart != null && dragDelta != 0) moveSelected(dragDelta)
            dragStart = null
            dragDelta = 0
            scrubbing = false
            repaint()
        }

        private fun layerOf(id: Int) = host.timelineLayers.firstOrNull { it.id == id }

        /** Moves the selected keyframes; keyframes they land on are replaced. */
        private fun moveSelected(delta: Int) {
            val moved = mutableSetOf<Sel>()
            for ((group, sels) in selection.groupBy { it.layerId to it.key }) {
                val layer = layerOf(group.first) ?: continue
                val track = layer.tracks[group.second] ?: continue
                val frames = sels.map { it.frame }.toSet()
                val movedKeys = track.keys.filter { it.frame in frames }.map { it.copy(frame = it.frame + delta) }
                val targets = movedKeys.map { it.frame }.toSet()
                val rest = track.keys.filter { it.frame !in frames && it.frame !in targets }
                layer.tracks[group.second] = Track(track.kind, (rest + movedKeys).sortedBy { it.frame })
                moved += movedKeys.map { Sel(layer.id, group.second, it.frame) }
            }
            selection.clear()
            selection += moved
            host.keysEdited()
        }

        private fun deleteSelected() {
            if (selection.isEmpty()) return
            for (sel in selection) {
                val layer = layerOf(sel.layerId) ?: continue
                val track = layer.tracks[sel.key] ?: continue
                val rest = track.without(sel.frame)
                if (rest == null) layer.tracks.remove(sel.key) else layer.tracks[sel.key] = rest
            }
            selection.clear()
            host.keysEdited()
        }

        private fun selectAll() {
            selection.clear()
            for (r in rows.filterIsInstance<Row.PropRow>()) r.layer.tracks[r.key]?.keys?.forEach { selection += Sel(r.layer.id, r.key, it.frame) }
            repaint()
        }

        private fun setEasing(easing: Easing) {
            for (sel in selection) {
                val layer = layerOf(sel.layerId) ?: continue
                val track = layer.tracks[sel.key] ?: continue
                val key = track.keyAt(sel.frame) ?: continue
                layer.tracks[sel.key] = track.with(key.copy(easing = easing))
            }
            host.keysEdited()
        }

        private fun keyMenu() = JPopupMenu().apply {
            val n = selection.size
            for (easing in Easing.entries) add(JMenuItem(easing.label).apply {
                toolTipText = when (easing) {
                    Easing.LINEAR -> "Even speed to the next keyframe"
                    Easing.EASE -> "Starts and ends slowly"
                    Easing.HOLD -> "Keeps the value until the next keyframe, then jumps"
                }
                addActionListener { setEasing(easing) }
            })
            addSeparator()
            add(JMenuItem(if (n == 1) "Delete keyframe" else "Delete $n keyframes").apply { addActionListener { deleteSelected() } })
        }

        private fun propertyMenu(row: Row.PropRow) = JPopupMenu().apply {
            add(JMenuItem("Select all keyframes").apply {
                addActionListener {
                    selection.clear()
                    row.layer.tracks[row.key]?.keys?.forEach { selection += Sel(row.layer.id, row.key, it.frame) }
                    repaint()
                }
            })
            add(JMenuItem("Remove animation").apply {
                toolTipText = "Deletes all keyframes of this setting; it keeps its current value"
                addActionListener {
                    row.layer.tracks.remove(row.key)
                    host.keysEdited()
                }
            })
        }

        override fun getToolTipText(e: MouseEvent): String? {
            val sel = keyAt(e.x, e.y) ?: return if (e.y < rulerHeight && e.x >= labelWidth) host.timeline?.let { "Frame ${frameAt(e.x) + 1} · ${it.position(frameAt(e.x))}" } else null
            val layer = layerOf(sel.layerId) ?: return null
            val track = layer.tracks[sel.key] ?: return null
            val key = track.keyAt(sel.frame) ?: return null
            val value = if (track.kind == ValueKind.COLOR) "#%06X".format(key.value.toInt()) else formatValue(key.value)
            return "<html><b>${layer.propertyLabel(sel.key)}</b><br>Frame ${key.frame + 1} · $value · ${key.easing.label}<br>" +
                "<span style='color:gray'>Drag to move${if (host.timeline?.beats == true) (if (snapButton.isSelected) " – snaps to beats, Shift: free" else " – Shift: snap to beats") else ""} · right-click: easing, delete · double-click: go there</span></html>"
        }

        private fun formatValue(v: Double) = if (abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString() else "%.2f".format(java.util.Locale.ROOT, v)

        private fun secondRuler(g: Graphics2D, t: Timeline, perFrame: Double, line: Color, dim: Color) {
            val minorStep = when {
                perFrame >= 5 -> 1
                perFrame * 5 >= 5 -> 5
                else -> t.fps
            }
            g.color = line
            var f = 0
            while (f < t.frames) {
                val x = xOf(f).toInt()
                g.drawLine(x, rulerHeight - 5, x, rulerHeight - 2)
                f += minorStep
            }
            val labelEvery = max(1, (40 / (perFrame * t.fps)).toInt() + 1)
            var s = 0
            while (s * t.fps < t.frames) {
                val x = xOf(s * t.fps).toInt()
                if (s % labelEvery == 0) {
                    g.color = dim
                    g.drawLine(x, 4, x, rulerHeight - 2)
                    g.drawString("${s}s", x + 3, 13)
                }
                s++
            }
        }

        /** Bars numbered from 1, beats as ticks, frames as finer ticks when there is room. */
        private fun beatRuler(g: Graphics2D, t: Timeline, perFrame: Double, line: Color, dim: Color) {
            val perBeat = perFrame * t.framesPerBeat
            g.color = line
            if (perFrame >= 5) for (f in 0 until t.frames) {
                val x = xOf(f).toInt()
                g.drawLine(x, rulerHeight - 3, x, rulerHeight - 2)
            }
            val labelEvery = max(1, (40 / (perBeat * t.beatsPerBar)).toInt() + 1)
            var beat = 0
            while (true) {
                val frame = beat * t.framesPerBeat
                if (frame >= t.frames - 1e-6) break
                val x = xOf(0) + frame * perFrame
                val bar = beat / t.beatsPerBar
                if (beat % t.beatsPerBar == 0 && bar % labelEvery == 0) {
                    g.color = dim
                    g.drawLine(x.toInt(), 4, x.toInt(), rulerHeight - 2)
                    g.drawString("${bar + 1}", x.toInt() + 3, 13)
                } else if (perBeat >= 4) {
                    g.color = line
                    g.drawLine(x.toInt(), rulerHeight - 7, x.toInt(), rulerHeight - 2)
                }
                beat++
            }
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
            val fg = UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY
            val dim = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val line = UIManager.getColor("Component.borderColor") ?: Color.GRAY
            g.color = bg
            g.fillRect(0, 0, width, height)
            val t = host.timeline ?: return

            // ruler: seconds (or bars) labelled, frames (or beats) as small ticks when there is room
            g.color = bg.darker()
            g.fillRect(0, 0, width, rulerHeight)
            g.font = font.deriveFont(10f)
            val perFrame = trackWidth.toDouble() / max(1, t.frames - 1)
            if (t.beats) beatRuler(g, t, perFrame, line, dim) else secondRuler(g, t, perFrame, line, dim)
            // the frames already rendered and ready to play
            g.color = Color(0x3FAF5A)
            for (frame in 0 until t.frames) if (host.isCached(frame)) {
                val x0 = xOf(frame) - perFrame / 2
                g.fillRect(x0.toInt().coerceAtLeast(trackLeft), rulerHeight - 3, max(1, perFrame.roundToInt()), 3)
            }

            // rows
            g.font = font.deriveFont(Font.PLAIN, 11f)
            val bold = font.deriveFont(Font.BOLD, 11f)
            if (rows.isEmpty()) {
                g.color = dim
                g.drawString("Right-click a setting of a layer → Add keyframe. Animated settings appear here.", 12, rulerHeight + 15)
            }
            for ((i, row) in rows.withIndex()) {
                val y = rulerHeight + i * rowHeight
                val selected = row.layer === host.selectedLayer
                if (row is Row.LayerRow) {
                    g.color = if (selected) accent.darker().darker() else bg.brighter()
                    g.fillRect(0, y, width, rowHeight)
                }
                g.color = line
                g.drawLine(0, y + rowHeight - 1, width, y + rowHeight - 1)
                g.color = if (row is Row.LayerRow) fg else dim
                g.font = if (row is Row.LayerRow) bold else font.deriveFont(11f)
                val label = when (row) {
                    is Row.LayerRow -> row.layer.name
                    is Row.PropRow -> "   " + row.layer.propertyLabel(row.key)
                }
                g.drawString(clip(g, label, labelWidth - 8), 6, y + 14)
                when (row) {
                    is Row.LayerRow -> {
                        // all keyframes of the layer at a glance
                        val all = row.layer.tracks.values.flatMap { tr -> tr.keys.map { it.frame } }.toSortedSet()
                        g.color = dim
                        for (frame in all) drawKey(g, xOf(frame), y + rowHeight / 2.0, Easing.LINEAR, 3.5, filled = true)
                    }
                    is Row.PropRow -> {
                        val track = row.layer.tracks[row.key] ?: continue
                        // the stretch between the first and the last keyframe
                        g.color = Color(accent.red, accent.green, accent.blue, 60)
                        val x0 = xOf(track.keys.first().frame)
                        val x1 = xOf(track.keys.last().frame)
                        g.fillRect(x0.toInt(), y + rowHeight / 2 - 1, (x1 - x0).toInt(), 3)
                        for (key in track.keys) {
                            val sel = Sel(row.layer.id, row.key, key.frame) in selection
                            val frame = if (sel && dragStart != null) key.frame + dragDelta else key.frame
                            g.color = if (sel) Color.WHITE else accent
                            drawKey(g, xOf(frame), y + rowHeight / 2.0, key.easing, 5.5, filled = true)
                            if (sel) {
                                g.color = accent
                                g.stroke = BasicStroke(1.5f)
                                drawKey(g, xOf(frame), y + rowHeight / 2.0, key.easing, 5.5, filled = false)
                                g.stroke = BasicStroke(1f)
                            }
                        }
                    }
                }
            }
            // the label column ends here
            g.color = line
            g.drawLine(labelWidth, 0, labelWidth, height)

            // playhead
            val px = xOf(host.currentFrame).toInt()
            g.color = Color(0xFF4D4D)
            g.drawLine(px, 0, px, height)
            g.fillPolygon(Polygon(intArrayOf(px - 6, px + 6, px), intArrayOf(0, 0, 9), 3))
        }

        /** A keyframe: diamond (linear), circle (ease), square (hold). */
        private fun drawKey(g: Graphics2D, x: Double, y: Double, easing: Easing, r: Double, filled: Boolean) {
            val shape: java.awt.Shape = when (easing) {
                Easing.LINEAR -> java.awt.geom.Path2D.Double().apply {
                    moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); closePath()
                }
                Easing.EASE -> java.awt.geom.Ellipse2D.Double(x - r * 0.85, y - r * 0.85, r * 1.7, r * 1.7)
                Easing.HOLD -> java.awt.geom.Rectangle2D.Double(x - r * 0.75, y - r * 0.75, r * 1.5, r * 1.5)
            }
            if (filled) g.fill(shape) else g.draw(shape)
        }

        private fun clip(g: Graphics2D, text: String, w: Int): String {
            val fm = g.fontMetrics
            if (fm.stringWidth(text) <= w) return text
            var s = text
            while (s.isNotEmpty() && fm.stringWidth("$s…") > w) s = s.dropLast(1)
            return "$s…"
        }
    }
}
