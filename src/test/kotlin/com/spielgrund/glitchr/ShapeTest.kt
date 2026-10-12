package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.effects.Shape
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeTest {
    private val black = Pixels(200, 200).also { it.data.fill(0xFF000000.toInt()) }

    private fun run(vararg c: Pair<String, Int>) =
        Shape.apply(black, Shape.defaultValues(mapOf("fill" to 0, "color" to 0xFFFFFF, *c)), 0L)

    private fun lit(p: Pixels) = p.data.count { (it and 0xFF) > 127 }

    @Test
    fun `every shape covers about its area`() {
        // radius 60 → circle 11310, ring 40 px wide → 11310 − 1257, hexagon 9353, triangle 4677
        val expected = mapOf(4 to 11310, 5 to 10053, 2 to 9353, 1 to 4677, 0 to 120 * 40 + 1257)
        for ((shape, area) in expected) {
            val n = lit(run("shape" to shape, "size" to 60, "thickness" to 40, "sides" to 6))
            assertTrue(kotlin.math.abs(n - area) < area * 0.03, "shape $shape: $n, expected $area")
        }
        // a star lies inside its circle and is smaller than its polygon
        val star = lit(run("shape" to 3, "size" to 60, "sides" to 5, "inner" to 40))
        assertTrue(star in 2000 until lit(run("shape" to 2, "size" to 60, "sides" to 5)), "star $star")
    }

    @Test
    fun `edges are antialiased and the center is where it is set`() {
        val out = run("shape" to 4, "size" to 30, "centerX" to 500)
        assertEquals(-1, out[150, 100])
        assertEquals(0xFF000000.toInt(), out[100, 100])
        assertTrue(out.data.any { (it and 0xFF) in 30..220 }, "soft edge pixels")
    }

    @Test
    fun `a gradient fills with the ramp colors`() {
        val out = Shape.apply(black, Shape.defaultValues(mapOf("shape" to 4, "size" to 90, "fill" to 1, "gradientType" to 0, "gradientAngle" to 0),
            mapOf("ramp" to "0:#ff0000 1:#0000ff")), 0L)
        assertTrue((out[30, 100] shr 16 and 0xFF) > 200 && (out[170, 100] and 0xFF) > 200, "red left, blue right")
    }

    @Test
    fun `outline only and transparent background`() {
        val out = run("shape" to 4, "size" to 50, "fill" to 2, "outline" to 6, "transparent" to 1)
        assertEquals(0, alpha(out[100, 100]), "inside empty")
        assertEquals(0, alpha(out[5, 5]), "outside transparent")
        assertEquals(-1, out[150, 100], "on the outline")
    }

    @Test
    fun `the generator draws the shape on the base color`() {
        val g = Generators.byId("shape")!!
        val out = g.generate(100, 100, g.defaultValues(mapOf("size" to 30)), 0L)
        assertEquals(0xFF000000.toInt(), out[1, 1])
        assertTrue(out[50, 50] != out[1, 1])
    }
}
