package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.NoiseField
import com.spielgrund.glitchr.image.alpha
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class NoiseFieldTest {
    private val src = testImage(240, 160)

    private fun run(vararg settings: Pair<String, Int>) =
        NoiseField.apply(src, NoiseField.defaultValues(settings.toMap()), 4L)

    /** The bare noise: grey, not shaped by the picture. */
    private fun pure(vararg settings: Pair<String, Int>) = run("colorMode" to 0, "imageShape" to 0, *settings)

    @Test
    fun `mixing strength 0 leaves the picture alone`() {
        for (mix in 0..12) {
            val out = run("mix" to mix, "amount" to 0)
            if (mix in 3..5) {
                // HSL there and back may round by a level
                for (i in src.data.indices) for (shift in 0..16 step 8) {
                    assertTrue(abs((src.data[i] shr shift and 0xFF) - (out.data[i] shr shift and 0xFF)) <= 1, "Mischart $mix")
                }
            } else {
                assertContentEquals(src.data, out.data, "Mischart $mix")
            }
        }
    }

    @Test
    fun `the noise only changes pixel values, it never moves pixels`() {
        // change one pixel: with every mix mode, only that pixel of the result may change
        val changed = src.copy().also { it.data[50 * 240 + 60] = 0xFF123456.toInt() }
        for (mix in 0..12) {
            val settings = mapOf("mix" to mix, "control" to 3, "imageShape" to 0, "colorMode" to 0)
            val a = NoiseField.apply(src, NoiseField.defaultValues(settings), 4L)
            val b = NoiseField.apply(changed, NoiseField.defaultValues(settings), 4L)
            val differing = a.data.indices.filter { a.data[it] != b.data[it] }
            assertTrue(differing.all { it == 50 * 240 + 60 }, "Mischart $mix verändert andere Pixel: ${differing.take(5)}")
        }
    }

    @Test
    fun `size changes along the direction`() {
        // grey noise only, fine at the left (0°: start = left), coarse at the right
        val out = pure("scaleStart" to 6, "scaleEnd" to 200, "amount" to 100, "direction" to 0)
        fun roughness(x0: Int) = (0 until 160).sumOf { y ->
            (x0 until x0 + 40).sumOf { x -> abs((out[x + 1, y] and 0xFF) - (out[x, y] and 0xFF)) }
        }
        assertTrue(roughness(5) > 4 * roughness(190), "links fein ${roughness(5)}, rechts grob ${roughness(190)}")
        // turned by 180°, the fine side is on the right
        val turned = pure("scaleStart" to 6, "scaleEnd" to 200, "amount" to 100, "direction" to 180)
        fun roughnessTurned(x0: Int) = (0 until 160).sumOf { y ->
            (x0 until x0 + 40).sumOf { x -> abs((turned[x + 1, y] and 0xFF) - (turned[x, y] and 0xFF)) }
        }
        assertTrue(roughnessTurned(190) > 4 * roughnessTurned(5), "gedreht: rechts fein")
    }

    @Test
    fun `the picture decides where the noise is mixed in`() {
        // left half dark, right half bright
        val img = com.spielgrund.glitchr.image.Pixels(200, 100)
        for (y in 0 until 100) for (x in 0 until 200) img.data[y * 200 + x] = if (x < 100) 0xFF202020.toInt() else 0xFFE0E0E0.toInt()
        fun run(control: Int) = NoiseField.apply(
            img, NoiseField.defaultValues(mapOf("mix" to 1, "control" to control, "threshold" to 50, "colorMode" to 1, "color1" to 0xFF0000, "color2" to 0xFF0000)), 4L,
        )
        fun red(out: com.spielgrund.glitchr.image.Pixels, x0: Int) = (0 until 100).sumOf { y -> (x0 until x0 + 80).count { x -> out[x, y] == 0xFFFF0000.toInt() } }
        val bright = run(0)
        assertTrue(red(bright, 10) == 0 && red(bright, 110) > 7000, "Bildhelligkeit: Noise nur rechts")
        val dark = run(1)
        assertTrue(red(dark, 110) == 0 && red(dark, 10) > 7000, "Bild dunkel: Noise nur links")
    }

    @Test
    fun `voronoi makes flat cells`() {
        val out = pure("type" to 6, "scaleStart" to 40, "scaleEnd" to 40)
        // flat cells: most neighbouring pixels are equal
        val equal = (0 until 160).sumOf { y -> (0 until 239).count { x -> out[x, y] == out[x + 1, y] } }
        assertTrue(equal > 160 * 239 * 0.9, "flache Zellen: $equal gleiche Nachbarn")
        assertTrue(out.data.toSet().size > 10, "viele verschiedene Zellen")
    }

    @Test
    fun `noise from the picture is made of the picture's colors and follows its forms`() {
        // "Aus dem Bild" is the picture itself, pushed and shaded by the noise: without any noise
        // contrast (and no picture shaping) nothing is pushed or shaded and the picture stays
        val flat = run("colorMode" to 3, "imageShape" to 0, "contrastStart" to 0, "contrastEnd" to 0)
        assertContentEquals(src.data, flat.data, "ohne Noise-Kontrast bleibt das Bild")
        val textured = run("colorMode" to 3)
        assertTrue(!textured.data.contentEquals(src.data), "mit Noise entsteht Struktur")

        // the picture's shape flows into the noise: the grey noise gets brighter where the picture is bright
        val img = com.spielgrund.glitchr.image.Pixels(200, 100)
        for (y in 0 until 100) for (x in 0 until 200) img.data[y * 200 + x] = if (x < 100) 0xFF000000.toInt() else -1
        val shaped = NoiseField.apply(img, NoiseField.defaultValues(mapOf("colorMode" to 0, "imageShape" to 100)), 4L)
        fun mean(x0: Int) = (0 until 100).sumOf { y -> (x0 until x0 + 60).sumOf { x -> shaped[x, y] and 0xFF } } / 6000
        assertTrue(mean(130) > mean(10) + 60, "hell: ${mean(130)}, dunkel: ${mean(10)}")
    }

    @Test
    fun `cut out makes the picture transparent below the threshold`() {
        val out = run("mix" to 2, "threshold" to 50, "softness" to 1)
        val transparent = out.data.count { alpha(it) == 0 }
        assertTrue(transparent in 1 until out.data.size, "teilweise ausgeschnitten: $transparent")
    }
}
