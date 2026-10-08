package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Glass
import com.spielgrund.glitchr.model.AnimKeys
import com.spielgrund.glitchr.model.Animator
import com.spielgrund.glitchr.model.Easing
import com.spielgrund.glitchr.model.EffectLayer
import com.spielgrund.glitchr.model.EffectMemento
import com.spielgrund.glitchr.model.EffectState
import com.spielgrund.glitchr.model.ImageLayer
import com.spielgrund.glitchr.model.ImageMemento
import com.spielgrund.glitchr.model.ImageState
import com.spielgrund.glitchr.model.Keyframe
import com.spielgrund.glitchr.model.Timeline
import com.spielgrund.glitchr.model.Track
import com.spielgrund.glitchr.model.ValueKind
import com.spielgrund.glitchr.model.seedAt
import com.spielgrund.glitchr.project.ProjectFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimationTest {
    private val src = testImage(120, 80)

    @Test
    fun `tracks blend linearly, eased, held, per color channel and in steps`() {
        val linear = Track(ValueKind.NUMBER, listOf(Keyframe(10, 0.0, Easing.LINEAR), Keyframe(20, 100.0)))
        assertEquals(0.0, linear.valueAt(0))
        assertEquals(25.0, linear.valueAt(12.5))
        assertEquals(100.0, linear.valueAt(30))
        val eased = linear.with(Keyframe(10, 0.0, Easing.EASE))
        assertTrue(eased.valueAt(11) < linear.valueAt(11), "ease starts slowly")
        assertEquals(50.0, eased.valueAt(15), 1e-9)
        val held = linear.with(Keyframe(10, 0.0, Easing.HOLD))
        assertEquals(0.0, held.valueAt(19))
        assertEquals(100.0, held.valueAt(20))
        val color = Track(ValueKind.COLOR, listOf(Keyframe(0, 0xFF0000.toDouble(), Easing.LINEAR), Keyframe(10, 0x0000FF.toDouble())))
        assertEquals(0x800080, color.valueAt(5).toInt(), "half red, half blue")
        val step = Track(ValueKind.STEP, listOf(Keyframe(0, 1.0, Easing.LINEAR), Keyframe(10, 3.0)))
        assertEquals(1.0, step.valueAt(9))
        assertEquals(3.0, step.valueAt(10))
        assertNull(step.without(0)!!.without(10))
    }

    @Test
    fun `editing an animated setting writes a keyframe, moving on the timeline shows the value in between`() {
        val layer = EffectLayer(Effects.byId("rgb"))
        layer.values["rx"] = 0
        Animator.addKey(layer, "rx", 0)
        // at frame 50 the user drags the slider to 100: a keyframe appears there
        layer.values["rx"] = 100
        assertTrue(Animator.captureEdits(listOf(layer), 50))
        assertEquals(listOf(0, 50), layer.tracks["rx"]!!.keys.map { it.frame })
        // nothing changed: no new keyframe
        assertTrue(!Animator.captureEdits(listOf(layer), 50))
        Animator.syncToFrame(listOf(layer), 25)
        assertEquals(50, layer.values["rx"])
        // a setting without keyframes is not touched
        layer.values["ry"] = 7
        Animator.captureEdits(listOf(layer), 25)
        assertNull(layer.tracks["ry"])
    }

    @Test
    fun `undo and saving see the same layer at every frame`() {
        val layer = EffectLayer(Glass)
        Animator.addKey(layer, "strength1", 0)
        layer.values["strength1"] = 250
        Animator.captureEdits(listOf(layer), 40)
        layer.opacity = 30
        Animator.addKey(layer, AnimKeys.OPACITY, 40)
        Animator.syncToFrame(listOf(layer), 0)
        val atStart = layer.memento()
        for (f in listOf(13, 40, 77)) {
            Animator.syncToFrame(listOf(layer), f)
            assertEquals(atStart, layer.memento(), "frame $f")
        }
        val restored = atStart.toLayer()
        Animator.syncToFrame(listOf(restored), 20)
        Animator.syncToFrame(listOf(layer), 20)
        assertEquals(layer.values, restored.values)
    }

    @Test
    fun `the render state follows the tracks, and a new seed every frame can be switched on`() {
        val layer = EffectLayer(Glass)
        layer.values["tint"] = 0x000000
        Animator.addKey(layer, "tint", 0)
        layer.values["tint"] = 0xFFFFFF
        Animator.captureEdits(listOf(layer), 10)
        layer.values["pattern1"] = 2
        Animator.addKey(layer, "pattern1", 0)
        layer.values["pattern1"] = 5
        Animator.captureEdits(listOf(layer), 10)
        val anim = layer.animated()
        val mid = anim.at(5) as EffectState
        assertTrue((mid.values["tint"]!! and 0xFF) in 100..155, Integer.toHexString(mid.values["tint"]!!))
        assertEquals(2, mid.values["pattern1"], "choices jump at the keyframe")
        assertEquals(5, (anim.at(10) as EffectState).values["pattern1"])
        assertEquals(layer.seed, (anim.at(3) as EffectState).seed)
        layer.seedPerFrame = true
        val perFrame = layer.animated()
        assertEquals(layer.seed, (perFrame.at(0) as EffectState).seed)
        assertEquals(seedAt(layer.seed, 3), (perFrame.at(3) as EffectState).seed)
        assertNotEquals((perFrame.at(3) as EffectState).seed, (perFrame.at(4) as EffectState).seed)
    }

    @Test
    fun `an animated scale keeps the middle of the picture`() {
        val layer = ImageLayer(src).apply { x = 10.0; y = 20.0; scale = 1.0 }
        val cx = 10.0 + src.width / 2.0
        val cy = 20.0 + src.height / 2.0
        Animator.addKey(layer, AnimKeys.SCALE, 0)
        layer.setProperty(AnimKeys.SCALE, 2.0)
        Animator.captureEdits(listOf(layer), 20)
        val half = layer.animated().at(10) as ImageState
        assertEquals(1.5, half.scale, 1e-9)
        assertEquals(cx, half.x + src.width * half.scale / 2, 1e-9)
        assertEquals(cy, half.y + src.height * half.scale / 2, 1e-9)
        // the fields at a frame match what is rendered there, so the handles sit on the picture
        Animator.syncToFrame(listOf(layer), 10)
        assertEquals(half.x, layer.x, 1e-9)
        val m = layer.memento() as ImageMemento
        assertEquals(10.0, m.x, 1e-6)
        assertEquals(1.0, m.scale, 1e-9)
    }

    @Test
    fun `timeline, keyframes and seeds are saved in the project`() {
        val fx = EffectLayer(Glass)
        fx.values["strength1"] = -50
        Animator.addKey(fx, "strength1", 0)
        fx.values["strength1"] = 200
        Animator.captureEdits(listOf(fx), 30)
        fx.tracks["strength1"] = fx.tracks["strength1"]!!.with(Keyframe(0, -50.0, Easing.HOLD))
        fx.seedPerFrame = true
        val picture = ImageLayer(src)
        Animator.addKey(picture, AnimKeys.ROTATION, 0)
        picture.rotation = 90.0
        Animator.captureEdits(listOf(picture), 30)
        val state = com.spielgrund.glitchr.model.DocState(120, 80, "anim", listOf(picture.memento(), fx.memento()), Timeline(30, 60, 128.5, 3, beats = true))
        val file = File("target/test-output/anim.glitchr").apply { parentFile.mkdirs() }
        ProjectFile.save(state, file)
        val loaded = ProjectFile.load(file)
        assertEquals(Timeline(30, 60, 128.5, 3, beats = true), loaded.timeline)
        val lfx = loaded.layers[1] as EffectMemento
        assertEquals(fx.tracks, lfx.tracks)
        assertTrue(lfx.seedPerFrame)
        assertEquals(picture.tracks, (loaded.layers[0] as ImageMemento).tracks)
    }

    @Test
    fun `the default timeline is 4 seconds at 25 fps, 2 bars at 120 BPM`() {
        val t = Timeline.DEFAULT
        assertEquals(25, t.fps)
        assertEquals(100, t.frames)
        assertEquals(4.0, t.seconds)
        assertEquals(2.0, t.bars, 1e-9)
        assertEquals(100, t.framesForBars(2.0))
    }

    @Test
    fun `positions read as seconds or as bar, beat and sixteenth`() {
        val seconds = Timeline(25, 100)
        assertEquals("0:01.24", seconds.position(31))
        assertEquals("0:04.00", seconds.length())
        // 120 BPM at 24 fps: a beat lasts 12 frames, a sixteenth 3
        val beats = Timeline(24, 96, 120.0, 4, beats = true)
        assertEquals("1.1.1", beats.position(0))
        assertEquals("1.1.2", beats.position(3))
        assertEquals("1.2.1", beats.position(12))
        assertEquals("2.1.1", beats.position(48))
        assertEquals("2 bars", beats.length())
        assertEquals("1.5 bars", Timeline(24, 72, 120.0, 4, beats = true).length())
        assertEquals("1 bar", Timeline(24, 36, 120.0, 3, beats = true).length())
    }

    @Test
    fun `the frame counter keeps its width while the playhead moves`() {
        for (t in listOf(Timeline(25, 100), Timeline(25, 3000), Timeline(24, 1000, 120.0, 4, beats = true))) {
            val widths = (0 until t.frames).map { com.spielgrund.glitchr.ui.counter(t, it).length }.toSet()
            assertEquals(1, widths.size, "$t")
        }
        assertTrue(com.spielgrund.glitchr.ui.counter(Timeline(25, 100), 0).startsWith("Frame 001 / 100"))
    }

    @Test
    fun `frames snap to the nearest beat only in beats`() {
        // 120 BPM at 24 fps: a beat every 12 frames
        val beats = Timeline(24, 96, 120.0, 4, beats = true)
        assertEquals(0, beats.snap(5))
        assertEquals(12, beats.snap(7))
        assertEquals(48, beats.snap(50))
        // the beat that ends the timeline lies past the last frame: the last frame snaps instead
        assertEquals(95, beats.snap(95))
        assertEquals(95, beats.snap(92))
        assertEquals(84, beats.snap(88))
        assertEquals(50, beats.copy(beats = false).snap(50))
        // beats between frames: 25 fps at 120 BPM puts one every 12.5 frames
        val odd = Timeline(25, 100, 120.0, 4, beats = true)
        assertEquals(25, odd.snap(24))
        assertEquals(38, odd.snap(36))
        // the default timeline: frame 99 is reachable although the next beat would be frame 100
        assertEquals(99, odd.snap(99))
        assertEquals(99, odd.snap(95))
        assertEquals(88, odd.snap(92))
    }

    @Test
    fun `all keyframes of all layers get one easing`() {
        val a = EffectLayer(Effects.byId("rgb"))
        Animator.addKey(a, "rx", 0)
        a.values["rx"] = 30
        Animator.captureEdits(listOf(a), 20)
        val b = ImageLayer(testImage(20, 20))
        Animator.addKey(b, AnimKeys.ROTATION, 0)
        b.tracks[AnimKeys.ROTATION] = b.tracks[AnimKeys.ROTATION]!!.with(Keyframe(10, 45.0, Easing.HOLD))
        assertTrue(Animator.setEasingAll(listOf(a, b), Easing.LINEAR))
        val all = (a.tracks.values + b.tracks.values).flatMap { it.keys }
        assertEquals(4, all.size)
        assertTrue(all.all { it.easing == Easing.LINEAR })
        assertEquals(30.0, a.tracks["rx"]!!.keyAt(20)!!.value, "values and frames stay")
        assertEquals(false, Animator.setEasingAll(listOf(a, b), Easing.LINEAR), "nothing left to change")
    }

    @Test
    fun `a new frame rate keeps the keyframes at their time`() {
        val layer = EffectLayer(Effects.byId("rgb"))
        Animator.addKey(layer, "rx", 0)
        layer.values["rx"] = 50
        Animator.captureEdits(listOf(layer), 50)
        Animator.rescale(listOf(layer), 25, 50)
        assertEquals(listOf(0, 100), layer.tracks["rx"]!!.keys.map { it.frame })
    }
}
