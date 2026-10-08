package com.spielgrund.glitchr.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * A [FlowLayout] whose preferred size accounts for wrapping: when the components don't fit
 * the container's width they continue on the next row, and the container grows taller
 * instead of cutting them off.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container) = size(target) { it.preferredSize }

    override fun minimumLayoutSize(target: Container) = size(target) { it.minimumSize }.apply { width -= hgap + 1 }

    private fun size(target: Container, sizeOf: (java.awt.Component) -> Dimension): Dimension = synchronized(target.treeLock) {
        // the width available: the container's own, or its parent's before it was laid out
        var available = target.size.width
        var parent: Container? = target
        while (available == 0 && parent?.parent != null) {
            parent = parent.parent
            available = parent.size.width
        }
        if (available == 0) available = Int.MAX_VALUE
        val insets = target.insets
        val maxWidth = available - (insets.left + insets.right + hgap * 2)

        val result = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        for (c in target.components) {
            if (!c.isVisible) continue
            val d = sizeOf(c)
            if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                result.width = max(result.width, rowWidth)
                result.height += rowHeight + vgap
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += d.width
            rowHeight = max(rowHeight, d.height)
        }
        result.width = max(result.width, rowWidth) + insets.left + insets.right + hgap * 2
        result.height += rowHeight + insets.top + insets.bottom + vgap * 2
        // inside a scroll pane the width must be able to shrink again
        if (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) != null && target.isValid) result.width -= hgap + 1
        result
    }
}
