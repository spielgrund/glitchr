package com.spielgrund.glitchr.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The project's one timeline: [frames] frames at [fps] frames per second. With [beats],
 * times are shown in bars and beats at [bpm] with [beatsPerBar] instead of seconds –
 * the frames stay the same, only the time base for length and display changes.
 */
data class Timeline(
    val fps: Int,
    val frames: Int,
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val beats: Boolean = false,
) {
    val seconds get() = frames.toDouble() / fps

    /** How many frames one beat lasts. */
    val framesPerBeat get() = fps * 60.0 / bpm

    /** The length in bars. */
    val bars get() = frames / framesPerBeat / beatsPerBar

    /** Position of [frame] as text: 0:01.24 in seconds, or bar.beat.sixteenth (from 1.1.1) in beats. */
    fun position(frame: Int): String {
        if (!beats) {
            val s = frame.toDouble() / fps
            val m = (s / 60).toInt()
            return "%d:%05.2f".format(java.util.Locale.ROOT, m, s - m * 60)
        }
        // a hair more, so a frame exactly on a sixteenth isn't rounded down to the one before
        val sixteenths = (frame / framesPerBeat * 4 + 1e-6).toInt()
        val beat = sixteenths / 4
        return "%d.%d.%d".format(beat / beatsPerBar + 1, beat % beatsPerBar + 1, sixteenths % 4 + 1)
    }

    /** The whole length as text: 0:04.00, or 2 bars. */
    fun length(): String {
        if (!beats) return position(frames)
        val text = "%.2f".format(java.util.Locale.ROOT, bars).trimEnd('0').trimEnd('.')
        return "$text bar${if (text == "1") "" else "s"}"
    }

    /**
     * [frame] moved to the nearest beat when times are in beats (otherwise unchanged).
     * Beats that fall between frames round to the nearest frame. The last frame always
     * counts too: the beat that ends the timeline usually lies one frame beyond it.
     */
    fun snap(frame: Int): Int {
        if (!beats) return frame
        val last = frames - 1
        val f = frame.coerceIn(0, last)
        val beat = Math.round(f / framesPerBeat)
        val candidates = listOf(beat - 1, beat, beat + 1).map { Math.round(it * framesPerBeat).toInt() }.filter { it in 0..last } + last
        return candidates.minBy { abs(it - f) }
    }

    /** Frames for [bars] bars at this timeline's tempo and frame rate. */
    fun framesForBars(bars: Double) = (bars * beatsPerBar * framesPerBeat).roundToInt().coerceAtLeast(1)

    companion object {
        /** 4 seconds at 25 fps; in beats 120 BPM in 4/4, so the same 4 seconds are 2 bars. */
        val DEFAULT = Timeline(25, 100)
    }
}

/** How a keyframe runs into the next one. */
enum class Easing(val label: String) {
    LINEAR("Linear"),
    EASE("Ease in/out"),
    HOLD("Hold");

    override fun toString() = label
}

/** How the values of a track are blended between keyframes. */
enum class ValueKind {
    /** Numbers, blended smoothly. */
    NUMBER,

    /** 0xRRGGBB colors, blended per channel. */
    COLOR,

    /** Choices and switches: the value jumps at the next keyframe. */
    STEP,
}

data class Keyframe(val frame: Int, val value: Double, val easing: Easing = Easing.EASE)

/** The keyframes of one animated setting, sorted by frame, at most one per frame. Immutable. */
data class Track(val kind: ValueKind, val keys: List<Keyframe>) {
    init {
        require(keys.isNotEmpty()) { "A track needs a keyframe" }
    }

    fun keyAt(frame: Int) = keys.firstOrNull { it.frame == frame }

    /** This track with [key] added, replacing a keyframe on the same frame. */
    fun with(key: Keyframe) = Track(kind, (keys.filter { it.frame != key.frame } + key).sortedBy { it.frame })

    /** This track without the keyframe on [frame]; null if that was the last one. */
    fun without(frame: Int): Track? = keys.filter { it.frame != frame }.takeIf { it.isNotEmpty() }?.let { Track(kind, it) }

    fun valueAt(frame: Double): Double {
        val first = keys.first()
        if (frame <= first.frame) return first.value
        val last = keys.last()
        if (frame >= last.frame) return last.value
        val i = keys.indexOfLast { it.frame <= frame }
        val a = keys[i]
        val b = keys[i + 1]
        if (kind == ValueKind.STEP || a.easing == Easing.HOLD) return a.value
        var t = (frame - a.frame) / (b.frame - a.frame)
        if (a.easing == Easing.EASE) t = t * t * (3 - 2 * t)
        return if (kind == ValueKind.COLOR) blendColor(a.value.toInt(), b.value.toInt(), t).toDouble() else a.value + (b.value - a.value) * t
    }

    fun valueAt(frame: Int) = valueAt(frame.toDouble())

    private fun blendColor(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Keys of the animatable settings that every layer or every image layer has; parameter keys never start with "@". */
object AnimKeys {
    const val OPACITY = "@opacity"
    const val X = "@x"
    const val Y = "@y"
    const val SCALE = "@scale"
    const val ROTATION = "@rotation"
}

/** Seed of a "new random value every frame" layer at [frame]; frame 0 keeps the layer's own seed. */
fun seedAt(seed: Long, frame: Int) = seed + frame * -0x61c8864680b583ebL

/**
 * Keeps the editable settings of the layers in step with the timeline: the fields of a
 * layer (values, opacity, position …) always show the value at the current frame, so the
 * editor and the canvas need not know about animation. Edits of an animated setting are
 * then written back as keyframes.
 */
object Animator {
    /** Sets every animated setting of [layers] to its value at [frame]. */
    fun syncToFrame(layers: List<Layer>, frame: Int) {
        for (layer in layers) for ((key, track) in layer.tracks) layer.setProperty(key, track.valueAt(frame))
    }

    /**
     * Animated settings whose field no longer matches their track at [frame] were edited:
     * the new value becomes a keyframe there. Returns whether a keyframe was written.
     */
    fun captureEdits(layers: List<Layer>, frame: Int): Boolean {
        var any = false
        for (layer in layers) {
            for ((key, track) in layer.tracks.toList()) {
                val current = layer.property(key) ?: continue
                val expected = layer.quantize(key, track.valueAt(frame))
                if (abs(current - expected) < 1e-9) continue
                val easing = track.keyAt(frame)?.easing ?: Easing.EASE
                layer.tracks[key] = track.with(Keyframe(frame, current, easing))
                any = true
            }
        }
        return any
    }

    /** Adds a keyframe with the setting's current value at [frame]; the setting becomes animated. */
    fun addKey(layer: Layer, key: String, frame: Int) {
        val value = layer.property(key) ?: return
        val track = layer.tracks[key]
        layer.tracks[key] = track?.with(Keyframe(frame, value, track.keyAt(frame)?.easing ?: Easing.EASE))
            ?: Track(layer.kindOf(key), listOf(Keyframe(frame, value)))
    }

    /** Removes the keyframe at [frame]; without keyframes left the setting keeps its value and is no longer animated. */
    fun removeKey(layer: Layer, key: String, frame: Int) {
        val track = layer.tracks[key] ?: return
        val rest = track.without(frame)
        if (rest == null) layer.tracks.remove(key) else layer.tracks[key] = rest
    }

    /** Gives every keyframe of all [layers] the same [easing]; false if there was nothing to change. */
    fun setEasingAll(layers: List<Layer>, easing: Easing): Boolean {
        var changed = false
        for (layer in layers) for ((key, track) in layer.tracks.toList()) {
            if (track.keys.all { it.easing == easing }) continue
            layer.tracks[key] = Track(track.kind, track.keys.map { it.copy(easing = easing) })
            changed = true
        }
        return changed
    }

    /** The keyframes of all layers moved to a new frame rate, so they keep their time. */
    fun rescale(layers: List<Layer>, from: Int, to: Int) {
        if (from == to) return
        for (layer in layers) for ((key, track) in layer.tracks.toList()) {
            val moved = track.keys.map { it.copy(frame = (it.frame.toLong() * to / from.toDouble()).roundToInt()) }
            layer.tracks[key] = Track(track.kind, moved.groupBy { it.frame }.map { it.value.last() }.sortedBy { it.frame })
        }
    }
}

/**
 * What the renderer needs of a layer at any frame: its settings at the current frame
 * ([state]) and its tracks. Taken on the UI thread, used on render threads.
 */
data class AnimatedLayer(val state: LayerState, val tracks: Map<String, Track>, val seedPerFrame: Boolean) {
    /** The layer's settings at [frame]. */
    fun at(frame: Int): LayerState {
        if (tracks.isEmpty() && !seedPerFrame) return state
        fun num(key: String, base: Double) = tracks[key]?.valueAt(frame) ?: base
        val opacity = num(AnimKeys.OPACITY, state.opacity.toDouble()).roundToInt().coerceIn(0, 100)
        fun values(base: Map<String, Int>): Map<String, Int> {
            if (tracks.keys.none { !it.startsWith("@") }) return base
            return base.toMutableMap().apply {
                for ((key, track) in tracks) if (!key.startsWith("@") && key in base) put(key, track.valueAt(frame).roundToInt())
            }
        }
        return when (val s = state) {
            is EffectState -> s.copy(opacity = opacity, values = values(s.values), seed = if (seedPerFrame) seedAt(s.seed, frame) else s.seed)
            is GeneratorState -> s.copy(opacity = opacity, values = values(s.values), seed = if (seedPerFrame) seedAt(s.seed, frame) else s.seed)
            is ImageState -> {
                val scale = num(AnimKeys.SCALE, s.scale).coerceAtLeast(0.001)
                // like the editor, scaling keeps the middle of the picture where it is – unless the position is animated itself
                val dx = (s.scale - scale) * s.image.width / 2
                val dy = (s.scale - scale) * s.image.height / 2
                s.copy(
                    opacity = opacity,
                    x = tracks[AnimKeys.X]?.valueAt(frame) ?: (s.x + dx),
                    y = tracks[AnimKeys.Y]?.valueAt(frame) ?: (s.y + dy),
                    scale = scale,
                    rotation = num(AnimKeys.ROTATION, s.rotation),
                )
            }
        }
    }
}
