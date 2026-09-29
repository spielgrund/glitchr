package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Generative
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.alpha
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerativeTest {
    private val src = testImage(240, 200)

    private fun run(vararg settings: Pair<String, Int>) =
        Generative.apply(src, Values(Generative.defaults().apply { putAll(settings) }), 3L)

    @Test
    fun `mirror tiles repeat exactly`() {
        val out = run("pattern" to 3, "size" to 60, "count" to 30)
        for (y in 0 until 140) for (x in 0 until 180) assertEquals(out[x, y], out[x + 60, y + 60], "Kachel bei $x,$y")
        // and each tile is mirrored left/right
        // (the mirrored quarters overlap by one pixel at the seam, so allow a level or two there)
        for (y in 0 until 60) for (x in 0 until 30) {
            val a = out[x, y]
            val b = out[59 - x, y]
            assertTrue((0..24 step 8).all { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 2 }, "gespiegelt bei $x,$y")
        }
    }

    @Test
    fun `even filling reaches every part of the picture`() {
        for (field in 0..2) {
            val out = run("pattern" to 1, "fill" to 1, "field" to field, "spacing" to 6, "background" to 3)
            // every 40×40 block gets lines
            for (by in 0 until 5) for (bx in 0 until 6) {
                val drawn = (0 until 40).sumOf { y -> (0 until 40).count { x -> alpha(out[bx * 40 + x, by * 40 + y]) > 0 } }
                assertTrue(drawn > 40, "Strömung $field: Block $bx,$by hat nur $drawn Linienpixel")
            }
        }
    }

    @Test
    fun `lines on a transparent background leave the rest empty`() {
        val dir = File("target/test-output").apply { mkdirs() }
        for (pattern in 0..3) {
            val out = run("pattern" to pattern, "background" to 3)
            val drawn = out.data.count { alpha(it) > 0 }
            assertTrue(drawn in 1 until out.data.size, "Muster $pattern: $drawn gezeichnete Pixel")
            ImageIO.write(out.toImage(), "png", File(dir, "generative-$pattern.png"))
        }
    }
}
