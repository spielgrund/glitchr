package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.BlockGlitch
import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.JpegArtifacts
import com.spielgrund.glitchr.effects.PixelBleed
import com.spielgrund.glitchr.effects.PixelSort
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffectOptionsTest {
    private val src = testImage(256, 160)
    private val outDir = File("target/test-output").apply { mkdirs() }

    private fun run(effect: Effect, vararg settings: Pair<String, Int>, seed: Long = 11L): Pixels {
        val values = effect.defaults().apply { putAll(settings) }
        return effect.apply(src, Values(values), seed)
    }

    /** Every pixel that differs from the source lies in an aligned n×n block of one color. */
    private fun assertBlocks(out: Pixels, n: Int, name: String) {
        var changedBlocks = 0
        for (by in 0 until src.height / n) for (bx in 0 until src.width / n) {
            val cells = (0 until n * n).map { (by * n + it / n) * src.width + bx * n + it % n }
            if (cells.none { out.data[it] != src.data[it] }) continue
            changedBlocks++
            assertTrue(cells.all { out.data[it] == out.data[cells[0]] }, "$name: Block ($bx, $by) ist nicht einfarbig")
        }
        assertTrue(changedBlocks > 0, "$name ändert nichts")
    }

    @Test
    fun `pixelsort and pixelbleed build their streaks from blocks`() {
        val block8 = 3 // "8 px"
        assertBlocks(run(PixelSort, "block" to block8), 8, "Pixelsort")
        assertBlocks(run(PixelSort, "block" to block8, "angle" to 90), 8, "Pixelsort 90°")
        assertBlocks(run(PixelBleed, "block" to block8, "direction" to 2), 8, "Pixelbleed")
        ImageIO.write(run(PixelSort, "block" to 6).toImage(), "png", File(outDir, "pixelsort-random-blocks.png"))
        ImageIO.write(run(PixelBleed, "block" to 4).toImage(), "png", File(outDir, "pixelbleed-16.png"))
    }

    @Test
    fun `random block size is deterministic`() {
        for (effect in listOf(PixelSort, PixelBleed)) {
            assertContentEquals(run(effect, "block" to 6).data, run(effect, "block" to 6).data, effect.name)
        }
    }

    @Test
    fun `block size 1 gives the old full resolution result`() {
        val oneBlock = run(PixelSort, "block" to 0)
        assertFalse(oneBlock.data.contentEquals(run(PixelSort, "block" to 2).data))
    }

    @Test
    fun `big pixel blocks are one color each, median smooths`() {
        val big = run(BlockGlitch, "mode" to 4, "count" to 1, "minSize" to 40, "maxSize" to 40)
        val changed = big.data.indices.filter { big.data[it] != src.data[it] }
        assertTrue(changed.isNotEmpty())
        assertEquals(1, changed.map { big.data[it] }.toSet().size, "grosser Pixel hat mehrere Farben")

        val median = run(BlockGlitch, "mode" to 5, "count" to 30, "radius" to 8)
        assertFalse(median.data.contentEquals(src.data))
        ImageIO.write(median.toImage(), "png", File(outDir, "blocks-median.png"))
        ImageIO.write(run(BlockGlitch, "mode" to 6).toImage(), "png", File(outDir, "blocks-mixed.png"))
    }

    @Test
    fun `jpeg options change the result`() {
        val base = run(JpegArtifacts, "quality" to 20, "blocks" to 4)
        assertFalse(base.data.contentEquals(run(JpegArtifacts, "quality" to 20, "blocks" to 4, "scaling" to 1).data), "Skalierung")
        assertFalse(base.data.contentEquals(run(JpegArtifacts, "quality" to 20, "blocks" to 4, "fullChroma" to 1).data), "4:4:4")
        assertFalse(base.data.contentEquals(run(JpegArtifacts, "quality" to 20, "blocks" to 4, "sharpen" to 150).data), "Schärfen")
    }
}
