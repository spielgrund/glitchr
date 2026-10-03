package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.model.DocState
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.Mask
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import com.spielgrund.glitchr.model.Selection
import com.spielgrund.glitchr.project.ProjectFile
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaskSelectionTest {
    private fun row(mask: Mask, y: Int, w: Int, h: Int) = FloatArray(w).also { mask.shape().row(y, w, h, it) }

    @Test
    fun `hard edge turns a gradient into black and white at the threshold`() {
        val mask = Mask().apply {
            mode = MaskMode.LINEAR
            linearStart = RelPoint(0.0, 0.5)
            linearEnd = RelPoint(1.0, 0.5)
            hardEdge = true
            threshold = 25
        }
        val values = row(mask, 0, 100, 10)
        assertTrue(values.all { it == 0f || it == 1f })
        // full effect where the gradient is at least 25 %, i.e. the left 75 %
        assertEquals(75, values.count { it == 1f })

        mask.invert = true
        assertEquals(25, row(mask, 0, 100, 10).count { it == 1f })
    }

    @Test
    fun `hard edge with threshold 0 still leaves empty paint empty`() {
        val mask = Mask().apply { mode = MaskMode.BRUSH; ensurePainted(10, 1); hardEdge = true; threshold = 0 }
        mask.dab(2.0, 0.5, 1.0, 1f, 0.2f, erase = false)
        val values = row(mask, 0, 10, 1)
        assertEquals(1f, values[2])
        assertEquals(0f, values[8])
    }

    @Test
    fun `selections add and subtract`() {
        val mask = Mask().apply { mode = MaskMode.BRUSH; ensurePainted(40, 40) }
        mask.applyPatch(Selection.shape(Rectangle2D.Double(0.0, 0.0, 20.0, 40.0), 40, 40, 0)!!, subtract = false)
        mask.applyPatch(Selection.shape(Rectangle2D.Double(0.0, 0.0, 40.0, 10.0), 40, 40, 0)!!, subtract = true)
        val p = mask.painted!!
        fun at(x: Int, y: Int) = p[y * 40 + x].toInt() and 0xFF
        assertEquals(255, at(5, 20), "added")
        assertEquals(0, at(5, 5), "abgezogen")
        assertEquals(0, at(30, 20), "never selected")
    }

    @Test
    fun `feathered ellipse is soft at the edge`() {
        val patch = Selection.shape(Ellipse2D.Double(20.0, 20.0, 60.0, 60.0), 100, 100, 12)!!
        fun at(x: Int, y: Int) = patch.data[(y - patch.rect.y) * patch.rect.width + x - patch.rect.x].toInt() and 0xFF
        assertEquals(255, at(50, 50))
        assertTrue(at(20, 50) in 40..220, "edge is soft: ${at(20, 50)}")
    }

    @Test
    fun `magic wand selects the connected similar area only when contiguous`() {
        // two red squares separated by a blue stripe
        val img = Pixels(30, 10)
        for (y in 0 until 10) for (x in 0 until 30) {
            img.data[y * 30 + x] = if (x in 10 until 20) argb(255, 0, 0, 255) else argb(255, 250 - x, 0, 0)
        }
        val connected = Selection.similarColor(img, 2, 2, 40, contiguous = true, feather = 0)
        assertNotNull(connected)
        assertEquals(java.awt.Rectangle(0, 0, 10, 10), connected.rect)

        val everywhere = Selection.similarColor(img, 2, 2, 40, contiguous = false, feather = 0)!!
        assertEquals(java.awt.Rectangle(0, 0, 30, 10), everywhere.rect)
        assertEquals(0, everywhere.data[5 * 30 + 15].toInt(), "blue doesn't belong to it")
    }

    @Test
    fun `mask from brightness and hard edge survive saving`() {
        val src = testImage(40, 30)
        val layer = EffectLayer(Effects.byId("rgb")).apply {
            mask.mode = MaskMode.BRUSH
            mask.fromBrightness(src)
            mask.hardEdge = true
            mask.threshold = 70
        }
        val file = File("target/test-output/mask.glitchr")
        ProjectFile.save(docState(src, "m", listOf(layer.memento())), file)
        val loaded = ProjectFile.load(file).layers.last().mask
        assertEquals(true, loaded.hardEdge)
        assertEquals(70, loaded.threshold)
        assertEquals(com.spielgrund.glitchr.image.luma(src.data[123]), loaded.painted!![123].toInt() and 0xFF)
    }
}
