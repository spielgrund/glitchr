package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.BlobEcho
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertTrue

class BlobEchoTest {
    /** Black with a white square near the left edge (x 20..49, y 40..69). */
    private val square = Pixels(200, 110).also { p ->
        p.data.fill(0xFF000000.toInt())
        for (y in 40 until 70) for (x in 20 until 50) p.data[y * 200 + x] = -1
    }

    private fun run(vararg c: Pair<String, Int>) =
        BlobEcho.apply(square, BlobEcho.defaultValues(mapOf("areas" to 0, "fade" to 0, "toEdge" to 0) + mapOf(*c)), 1L)

    private fun white(p: Pixels, x: Int, y: Int) = (p[x, y] shr 8 and 0xFF) > 200

    @Test
    fun `every area is copied in the direction`() {
        val out = run("direction" to 0, "copies" to 3, "spacing" to 40)
        // copies at +40, +80, +120 px to the right
        for (k in 1..3) assertTrue(white(out, 35 + 40 * k, 55), "copy $k")
        assertTrue(!white(out, 35 + 40 * 4, 55), "no more than three")
        assertTrue(!white(out, 35, 20) && !white(out, 35, 95), "nothing above or below")
        val down = run("direction" to 90, "copies" to 1, "spacing" to 35)
        assertTrue(white(down, 35, 55 + 35))
    }

    @Test
    fun `to the edge the copies run across the whole canvas`() {
        val out = run("direction" to 0, "spacing" to 40, "toEdge" to 1)
        assertTrue(white(out, 35 + 40 * 4, 55), "up to the right edge")
    }

    @Test
    fun `the background is left out unless allowed`() {
        // the square stays white: the black background (above the size limit) is not copied over it
        val out = run("direction" to 0, "copies" to 3, "spacing" to 10)
        assertTrue(white(out, 30, 55))
        // with every area copied, the background trail runs over the square too
        val all = run("direction" to 0, "copies" to 3, "spacing" to 10, "maxArea" to 100, "order" to 1)
        assertTrue(!white(all, 25, 55))
    }

    @Test
    fun `only the slice of each area is copied`() {
        // a quarter slice opening upwards: the copy has the square's upper middle, not its lower half
        val out = run("direction" to 0, "copies" to 1, "spacing" to 60, "slice" to 90, "sliceAngle" to 270)
        assertTrue(white(out, 35 + 60, 45), "upper part copied")
        assertTrue(!white(out, 35 + 60, 65), "lower part not")
        assertTrue(!white(out, 22 + 60, 55), "left edge not")
        // the original itself stays whole
        assertTrue(white(out, 35, 65))
    }

    @Test
    fun `the biggest areas are copied first, the small ones end up on top`() {
        // a big red square with a small blue square inside it
        val nested = Pixels(200, 110).also { p ->
            p.data.fill(0xFF000000.toInt())
            for (y in 20 until 70) for (x in 10 until 60) p.data[y * 200 + x] = 0xFFFF0000.toInt()
            for (y in 40 until 50) for (x in 30 until 40) p.data[y * 200 + x] = 0xFF0000FF.toInt()
        }
        val out = BlobEcho.apply(
            nested, BlobEcho.defaultValues(mapOf("areas" to 0, "fade" to 0, "toEdge" to 0, "copies" to 1, "spacing" to 30, "order" to 1)), 1L,
        )
        // the red copy lies at x 40..89, the blue one at 60..69 – on top of the red
        assertTrue(out[65, 45] == 0xFF0000FF.toInt(), "small lies on top")
        assertTrue(out[80, 45] == 0xFFFF0000.toInt())
        // behind all areas: the red square stays whole, the trails only run over the black background
        val behind = BlobEcho.apply(
            nested, BlobEcho.defaultValues(mapOf("areas" to 0, "fade" to 0, "toEdge" to 0, "copies" to 1, "spacing" to 30, "order" to 2)), 1L,
        )
        assertTrue(behind[45, 45] == 0xFFFF0000.toInt() && behind[35, 45] == 0xFF0000FF.toInt(), "original uncovered")
        assertTrue(behind[80, 45] == 0xFFFF0000.toInt(), "trail over the background")
    }

    @Test
    fun `copies fade with distance`() {
        val out = run("direction" to 0, "copies" to 3, "spacing" to 40, "fade" to 90)
        val near = out[75, 55] shr 8 and 0xFF
        val far = out[155, 55] shr 8 and 0xFF
        assertTrue(near > far && far > 0, "near $near, far $far")
    }
}
