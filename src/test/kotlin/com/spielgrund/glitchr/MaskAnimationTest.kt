package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.model.AnimKeys
import com.spielgrund.glitchr.model.Animator
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.MaskMode
import com.spielgrund.glitchr.model.RelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaskAnimationTest {
    /** A layer with a linear mask from the top (full effect) to the bottom (none), its end point animated from 50 % to 100 %. */
    private fun layer() = EffectLayer(Effects.byId("rgb")).apply {
        mask.mode = MaskMode.LINEAR
        mask.linearStart = RelPoint(0.5, 0.0)
        mask.linearEnd = RelPoint(0.5, 0.5)
        Animator.addKey(this, AnimKeys.MASK_BY, 0)
        mask.linearEnd = RelPoint(0.5, 1.0)
        assertTrue(Animator.captureEdits(listOf(this), 10))
    }

    private fun maskAt(l: EffectLayer, frame: Int, y: Int): Float {
        val out = FloatArray(100)
        l.animated().at(frame).mask.row(y, 100, 100, out)
        return out[50]
    }

    @Test
    fun `the gradient points of a mask are animated`() {
        val l = layer()
        assertEquals(listOf(0, 10), l.tracks[AnimKeys.MASK_BY]!!.keys.map { it.frame })
        assertEquals(50.0, l.tracks[AnimKeys.MASK_BY]!!.keys.first().value)
        // at frame 0 the mask ends at half the height, at frame 10 at the bottom
        assertEquals(0f, maskAt(l, 0, 75))
        assertTrue(maskAt(l, 10, 75) > 0.2f)
        // every frame has its own mask version, so the renderer composites anew
        assertNotEquals(l.animated().at(0).maskVersion, l.animated().at(10).maskVersion)
        // moving along the timeline moves the point in the layer itself
        Animator.syncToFrame(listOf(l), 5)
        assertTrue(l.mask.linearEnd.y in 0.5..1.0)
    }

    @Test
    fun `scrubbing is no edit and saving keeps the frame 0 mask`() {
        val l = layer()
        Animator.syncToFrame(listOf(l), 0)
        val atStart = l.memento()
        Animator.syncToFrame(listOf(l), 7)
        assertEquals(atStart, l.memento())
        assertEquals(0.5, atStart.mask.linearEnd.y, 1e-9)
    }

    @Test
    fun `threshold and invert are animatable`() {
        val l = EffectLayer(Effects.byId("rgb"))
        assertTrue(AnimKeys.MASK_THRESHOLD in l.animatableKeys() && AnimKeys.MASK_INVERT in l.animatableKeys())
        l.mask.mode = MaskMode.LINEAR
        Animator.addKey(l, AnimKeys.MASK_INVERT, 0)
        l.mask.invert = true
        Animator.captureEdits(listOf(l), 10)
        assertTrue(!l.animated().at(9).mask.invert && l.animated().at(10).mask.invert, "invert jumps")
    }
}
