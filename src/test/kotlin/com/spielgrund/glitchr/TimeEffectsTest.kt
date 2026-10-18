package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Feedback
import com.spielgrund.glitchr.effects.TimeBlend
import com.spielgrund.glitchr.effects.TimeDifference
import com.spielgrund.glitchr.effects.TimeFeedback
import com.spielgrund.glitchr.model.AnimKeys
import com.spielgrund.glitchr.model.Animator
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.Layer
import com.spielgrund.glitchr.model.Renderer
import com.spielgrund.glitchr.model.TimeSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class TimeEffectsTest {
    private val src = testImage(80, 60)

    /** The picture, moving 4 px to the right per frame when [moving], and [effect] above it. */
    private fun layers(moving: Boolean, effect: EffectLayer): List<Layer> {
        val picture = ImageLayer(src)
        if (moving) {
            Animator.addKey(picture, AnimKeys.X, 0)
            picture.x = 40.0
            Animator.captureEdits(listOf(picture), 10)
            picture.tracks[AnimKeys.X] = picture.tracks[AnimKeys.X]!!.let { t -> t.copy(keys = t.keys.map { it.copy(easing = com.spielgrund.glitchr.model.Easing.LINEAR) }) }
        }
        return listOf(picture, effect)
    }

    private fun render(layers: List<Layer>, frame: Int, withTime: Boolean = true): com.spielgrund.glitchr.image.Pixels {
        val snapshots = layers.map { it.animated() }
        val session = if (withTime) TimeSession(snapshots, 11, 80, 60) else null
        return Renderer().render(80, 60, snapshots.map { it.at(frame) }, session, frame)
    }

    private fun picture(moving: Boolean, frame: Int) = render(listOf(layers(moving, EffectLayer(TimeBlend))[0]), frame)

    @Test
    fun `the difference is black where nothing moves and lights up where something does`() {
        val still = render(layers(false, EffectLayer(TimeDifference)), 5)
        assertTrue(still.data.all { it == 0xFF000000.toInt() }, "still: black")
        val moving = render(layers(true, EffectLayer(TimeDifference)), 5)
        assertTrue(moving.data.count { (it and 0xFFFFFF) != 0 } > 500, "moving: lit")
    }

    @Test
    fun `blending frames smears motion and leaves a still picture alone`() {
        assertContentEquals(picture(false, 5).data, render(layers(false, EffectLayer(TimeBlend)), 5).data)
        assertTrue(!picture(true, 5).data.contentEquals(render(layers(true, EffectLayer(TimeBlend)), 5).data))
    }

    @Test
    fun `time feedback is feedback, but its copies come from earlier frames`() {
        fun fx() = EffectLayer(TimeFeedback).apply { values["steps"] = 3; values["frameStep"] = 2 }
        // nothing moves: exactly the normal feedback
        val plain = Feedback.apply(picture(false, 5), Feedback.defaultValues(mapOf("steps" to 3)), 0)
        assertContentEquals(plain.data, render(layers(false, fx()), 5).data)
        // moving: the copies show where the picture was
        val now = Feedback.apply(picture(true, 5), Feedback.defaultValues(mapOf("steps" to 3)), 0)
        assertTrue(!now.data.contentEquals(render(layers(true, fx()), 5).data))
    }

    @Test
    fun `without an animation every other frame is the current one`() {
        assertContentEquals(picture(true, 5).data, render(layers(true, EffectLayer(TimeBlend)), 5, withTime = false).data)
    }

    private val moving = listOf(
        com.spielgrund.glitchr.effects.TimeDisplace, com.spielgrund.glitchr.effects.RgbTimeSplit, com.spielgrund.glitchr.effects.BlockTimeGlitch,
        com.spielgrund.glitchr.effects.Stutter, com.spielgrund.glitchr.effects.TimeGrid, com.spielgrund.glitchr.effects.Persistence,
        com.spielgrund.glitchr.effects.Stroboscope, com.spielgrund.glitchr.effects.MotionDatamosh, com.spielgrund.glitchr.effects.MotionBlur,
    )

    @Test
    fun `every new time effect does something when the picture moves`() {
        for (fx in moving) {
            val layer = EffectLayer(fx).apply {
                if (fx === com.spielgrund.glitchr.effects.BlockTimeGlitch) values["amount"] = 100
                if (fx === com.spielgrund.glitchr.effects.Persistence) values["threshold"] = 0
            }
            assertTrue(!picture(true, 5).data.contentEquals(render(layers(true, layer), 5).data), fx.name)
        }
    }

    @Test
    fun `time effects that only move through time leave a still picture alone`() {
        for (fx in moving - com.spielgrund.glitchr.effects.Stroboscope) {
            assertContentEquals(picture(false, 5).data, render(layers(false, EffectLayer(fx)), 5).data, fx.name)
        }
    }

    @Test
    fun `stutter holds frames`() {
        val fx = EffectLayer(com.spielgrund.glitchr.effects.Stutter).apply { values["repeat"] = 0; values["reverse"] = 0; values["hold"] = 3 }
        assertContentEquals(picture(true, 3).data, render(layers(true, fx), 5).data)
    }

    @Test
    fun `the motion estimate finds a shift`() {
        val prev = testImage(128, 96)
        val next = com.spielgrund.glitchr.image.Pixels(128, 96).also { p ->
            for (y in 0 until 96) for (x in 0 until 128) p.data[y * 128 + x] = prev[(x - 8).coerceAtLeast(0), y]
        }
        val field = com.spielgrund.glitchr.effects.MotionField.estimate(prev, next, 16, 16)
        val d = FloatArray(2)
        field.blockAt(64, 48, d)
        assertTrue(d[0] == -8f && d[1] == 0f, "found ${d[0]}, ${d[1]}")
    }
}
