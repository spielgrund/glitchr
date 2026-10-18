package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.FlowSmooth
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class FlowSmoothTest {
    private val src = testImage(120, 90)

    private fun run(vararg c: Pair<String, Int>, strokes: String = "") =
        FlowSmooth.apply(src, FlowSmooth.defaultValues(mapOf(*c), mapOf("strokes" to strokes)), 3L)

    @Test
    fun `one full cycle looks the same again`() {
        for (layers in listOf(2, 3, 5)) {
            assertContentEquals(run("layers" to layers, "time" to 0).data, run("layers" to layers, "time" to 36000).data, "$layers layers")
            assertContentEquals(run("layers" to layers, "time" to 4500).data, run("layers" to layers, "time" to 4500 + 72000).data)
        }
    }

    @Test
    fun `time moves the picture along the flow`() {
        val a = run("time" to 0)
        val b = run("time" to 9000)
        assertTrue(!a.data.contentEquals(b.data))
        // along drawn strokes too
        val stroke = "0.1000,0.5000 0.9000,0.5000"
        assertTrue(!run("time" to 0, strokes = stroke).data.contentEquals(run("time" to 9000, strokes = stroke).data))
    }

    @Test
    fun `layers, crossfade and segments change the result`() {
        val plain = run("time" to 5000).data
        for (c in listOf("layers" to 4, "fade" to 30, "variation" to 0, "segment" to 40, "segmentSoftness" to 0)) {
            assertTrue(!run("time" to 5000, c).data.contentEquals(plain), c.first)
        }
    }

    @Test
    fun `where nothing flows the picture stays`() {
        // no strokes and standstill outside them: every copy is the picture itself
        assertContentEquals(src.data, run("outside" to 1).data)
    }
}
