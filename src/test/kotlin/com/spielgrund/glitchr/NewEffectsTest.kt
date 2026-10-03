package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.ColorPattern
import com.spielgrund.glitchr.effects.Effect
import com.spielgrund.glitchr.effects.Geometric
import com.spielgrund.glitchr.effects.Kaleidoscope
import com.spielgrund.glitchr.effects.Displace
import com.spielgrund.glitchr.effects.Particles
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.dominantColors
import com.spielgrund.glitchr.model.ImageLayer
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewEffectsTest {
    private val src = testImage(200, 200)
    private val dir = File("target/test-output").apply { mkdirs() }

    private fun run(effect: Effect, vararg settings: Pair<String, Int>, image: Pixels = src): Pixels =
        effect.apply(image, Values(effect.defaults().apply { putAll(settings) }), 5L)

    private fun close(a: Int, b: Int) = (0..24 step 8).all { abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 3 }

    @Test
    fun `kaleidoscope with mirrored segments is symmetric`() {
        val out = run(Kaleidoscope, "segments" to 4)
        // 4 mirrored segments around the center: symmetric left/right and top/bottom
        var mismatches = 0
        for (y in 0 until 200) for (x in 0 until 100) if (!close(out[x, y], out[199 - x, y])) mismatches++
        for (y in 0 until 100) for (x in 0 until 200) if (!close(out[x, y], out[x, 199 - y])) mismatches++
        assertTrue(mismatches < 400, "$mismatches pixels asymmetric")
        ImageIO.write(out.toImage(), "png", File(dir, "kaleido.png"))
    }

    @Test
    fun `nested folds change the picture and keep the outer symmetry`() {
        val single = run(Kaleidoscope, "segments" to 4)
        val nested = run(Kaleidoscope, "segments" to 4, "levels" to 3, "innerSegments" to 5, "innerDistance" to 40)
        assertTrue(!single.data.contentEquals(nested.data), "inner fold changes the picture")
        var mismatches = 0
        for (y in 0 until 200) for (x in 0 until 100) if (!close(nested[x, y], nested[199 - x, y])) mismatches++
        assertTrue(mismatches < 400, "outer symmetry stays: $mismatches differences")
        ImageIO.write(nested.toImage(), "png", File(dir, "kaleido-nested.png"))
    }

    @Test
    fun `pattern uses only the prominent colors`() {
        for ((effect, pattern) in (0..8).map { ColorPattern to it } + (0..9).map { Geometric to it }) {
            val out = run(effect, "pattern" to pattern, "colors" to 4, "size" to 20, "mapping" to 0, "smoothing" to 0)
            val palette = dominantColors(src, 4, 5L).toSet()
            assertTrue(out.data.all { it in palette }, "${effect.name} $pattern uses only palette colors")
            ImageIO.write(out.toImage(), "png", File(dir, "${effect.id}-$pattern.png"))
        }
    }

    @Test
    fun `black and white patterns use only black, white and the gingham grey`() {
        val allowed = setOf(0xFF000000.toInt(), -1, 0xFF8C8C8C.toInt())
        for ((effect, pattern) in (0..8).map { ColorPattern to it } + (0..9).map { Geometric to it }) {
            val out = run(effect, "pattern" to pattern, "mapping" to 3, "size" to 20, "smoothing" to 0)
            assertTrue(out.data.all { it in allowed }, "${effect.name} $pattern in black and white")
            assertTrue(out.data.toSet().size >= 2, "${effect.name} $pattern has more than one color")
        }
    }

    @Test
    fun `smoothing softens the edges`() {
        val hard = run(Geometric, "pattern" to 3, "mapping" to 3, "smoothing" to 0).data.toSet().size
        val soft = run(Geometric, "pattern" to 3, "mapping" to 3, "smoothing" to 2).data.toSet().size
        assertEquals(2, hard, "without anti-aliasing only black and white")
        assertTrue(soft > 10, "with anti-aliasing intermediate tones at the edges: $soft")
    }

    @Test
    fun `gradients run between the two colors`() {
        val c1 = 0xFF0000
        val c2 = 0x0000FF
        for (mapping in 4..5) {
            val out = run(Geometric, "pattern" to 4, "mapping" to mapping, "smoothing" to 0, "color1" to c1, "color2" to c2, "background" to 0x00FF00)
            val lines = out.data.filter { (it shr 8 and 0xFF) == 0 }
            assertTrue(lines.any { (it shr 16 and 0xFF) > 200 } && lines.any { (it and 0xFF) > 200 }, "gradient $mapping has both ends")
            assertTrue(lines.any { (it shr 16 and 0xFF) in 60..190 }, "gradient $mapping has intermediate steps")
            assertTrue(out.data.any { it == 0xFF00FF00.toInt() }, "background color between the lines")
            ImageIO.write(out.toImage(), "png", File(dir, "geometric-gradient-$mapping.png"))
        }
    }

    @Test
    fun `stripe gradient ramps from color 1 at the dark stripe to color 2 at the end of the light one`() {
        // plain stripes of 20 px: a dark and a light stripe make a 40 px ramp from black to white
        val out = run(ColorPattern, "pattern" to 0, "mapping" to 6, "size" to 20, "smoothing" to 0, "color1" to 0x000000, "color2" to 0xFFFFFF)
        val row = (0 until out.width).map { out[it, 10] and 0xFF }
        val start = (1 until out.width - 40).first { row[it] < row[it - 1] - 100 } // where a ramp begins again
        assertTrue(row[start] < 8, "top edge of the dark stripe is color 1: ${row[start]}")
        assertTrue(row[start + 10] in 50..90, "a quarter of the pair: ${row[start + 10]}")
        assertTrue(row[start + 30] in 170..210, "three quarters of the pair: ${row[start + 30]}")
        assertTrue(row[start + 39] > 240, "bottom edge of the light stripe is almost color 2: ${row[start + 39]}")
        for (pattern in 0..9) {
            val g = run(Geometric, "pattern" to pattern, "mapping" to 6)
            ImageIO.write(g.toImage(), "png", File(dir, "geometric-ramp-$pattern.png"))
        }
    }

    @Test
    fun `dominant colors find the main colors of a two-color picture`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val img = Pixels(100, 100, IntArray(10000) { if (it < 7000) red else blue })
        assertEquals(listOf(red, blue), dominantColors(img, 2, 1L).toList(), "most frequent color first")
    }

    @Test
    fun `displace with strength 0 leaves the picture alone`() {
        assertContentEquals(src.data, run(Displace, "strength" to 0).data)
        for (type in 0..5) {
            val out = run(Displace, "type" to type)
            ImageIO.write(out.toImage(), "png", File(dir, "noise-$type.png"))
        }
    }

    @Test
    fun `particles are big on a big color area and tiny on tiny ones`() {
        fun longestRun(out: Pixels) = (0 until out.height).maxOf { y ->
            var best = 0
            var run = 0
            for (x in 0 until out.width) {
                run = if (alpha(out[x, y]) == 255) run + 1 else 0
                best = maxOf(best, run)
            }
            best
        }
        val flat = Pixels(200, 200, IntArray(40000) { 0xFFCC3333.toInt() })
        val checker = Pixels(200, 200, IntArray(40000) { i -> if ((i % 200 / 2 + i / 200 / 2) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        fun shapes(img: Pixels, density: Int) =
            run(Particles, "background" to 2, "abstraction" to 0, "density" to density, "detail" to 0, "opacity" to 100, image = img)
        val big = longestRun(shapes(flat, 100))
        val tiny = longestRun(shapes(checker, 100))
        assertTrue(big > 50, "large area, large particles: $big")
        assertTrue(tiny < 12, "tiny areas, tiny particles: $tiny")
    }

    @Test
    fun `color range picks original colors from the blob`() {
        // reds scattered between 190 and 230: blob means lie in between, the originals reach the ends
        val noise = kotlin.random.Random(1)
        val img = Pixels(120, 120, IntArray(14400) { com.spielgrund.glitchr.image.argb(255, 190 + noise.nextInt(41), 0, 0) })
        fun spread(range: Int): Int {
            val reds = run(Particles, "background" to 2, "abstraction" to 100, "colorRange" to range, "opacity" to 100, image = img)
                .data.filter { alpha(it) == 255 }.map { it shr 16 and 0xFF }
            return reds.max() - reds.min()
        }
        val means = spread(0)
        val originals = spread(100)
        assertTrue(originals > means + 15, "color range without variety $means, with full variety $originals")
    }

    @Test
    fun `coverage does not drop with bigger particles`() {
        // several flat areas of different sizes; transparent background shows every gap
        val img = Pixels(240, 240)
        for (y in 0 until 240) for (x in 0 until 240) {
            img.data[y * 240 + x] = when {
                x < 120 -> 0xFF3366AA.toInt()
                y < 60 -> 0xFFAA3333.toInt()
                (x / 20 + y / 20) % 2 == 0 -> 0xFF33AA33.toInt()
                else -> 0xFFDDDD33.toInt()
            }
        }
        fun coverage(size: Int) = run(
            Particles, "background" to 2, "abstraction" to 0, "particleSize" to size, "sizeJitter" to 0, "opacity" to 100, image = img,
        ).data.count { alpha(it) > 0 } / 57600.0
        val small = coverage(50)
        val large = coverage(300)
        assertTrue(small > 0.85, "small particles cover: $small")
        assertTrue(large >= small - 0.05, "large particles don't cover less: small $small, large $large")
    }

    @Test
    fun `shapes on a transparent background stay near the picture`() {
        val placed = ImageLayer(testImage(100, 100)).apply { x = 50.0; y = 50.0 }.placed(200, 200)
        val out = run(Particles, "background" to 2, "abstraction" to 10, image = placed)
        assertEquals(0, alpha(out[5, 5]), "far from the picture it stays transparent")
        assertTrue(alpha(out[100, 100]) > 0, "shapes inside the picture")
        for (form in 0..5) ImageIO.write(run(Particles, "form" to form).toImage(), "png", File(dir, "shapes-$form.png"))
    }
}
