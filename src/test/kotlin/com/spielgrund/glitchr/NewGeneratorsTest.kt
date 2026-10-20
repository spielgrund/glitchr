package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.CarsonType
import com.spielgrund.glitchr.effects.Flowers
import com.spielgrund.glitchr.effects.Tribal
import com.spielgrund.glitchr.effects.TrendShapes
import com.spielgrund.glitchr.effects.WaterReflection
import com.spielgrund.glitchr.effects.Values
import com.spielgrund.glitchr.image.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewGeneratorsTest {
    private fun white(w: Int = 200, h: Int = 160) = Pixels(w, h).also { it.data.fill(-1) }
    private fun dark(p: Pixels) = p.data.count { (it and 0xFF) < 128 }

    @Test
    fun `every tribal style draws, mirrored when asked`() {
        for (style in 0..3) {
            val out = Tribal.apply(white(), Tribal.defaultValues(mapOf("style" to style, "mirror" to 1)), 3L)
            assertTrue(dark(out) > 300, "style $style draws: ${dark(out)}")
            // mirrored: left and right halves alike
            val same = (0 until 160).sumOf { y -> (0 until 100).count { x -> (out[x, y] and 0xFF < 128) == (out[199 - x, y] and 0xFF < 128) } }
            assertTrue(same > 160 * 100 * 0.97, "style $style symmetric: $same")
        }
    }

    @Test
    fun `a tribal follows the drawn arrows`() {
        val values = Tribal.defaultValues(mapOf("source" to 1, "mirror" to 0, "barbs" to 0), mapOf("strokes" to "0.1000,0.5000 0.5000,0.5000 0.9000,0.5000"))
        val out = Tribal.apply(white(), values, 1L)
        assertTrue((out[100, 80] and 0xFF) < 128, "ink along the arrow")
        assertTrue((out[100, 10] and 0xFF) > 128, "nothing far from it")
    }

    @Test
    fun `flowers and trend shapes draw every kind`() {
        val flowers = (0..7).map { Flowers.apply(white(), Flowers.defaultValues(mapOf("kind" to it, "size" to 60)), 2L).data }
        for (a in flowers.indices) for (b in a + 1 until flowers.size - 1) assertTrue(!flowers[a].contentEquals(flowers[b]), "flower $a and $b")
        val shapes = TrendShapes.apply(white(), TrendShapes.defaultValues(), 4L)
        assertTrue(shapes.data.toSet().size > 20)
    }

    @Test
    fun `chaos type uses your words`() {
        val out = CarsonType.apply(white(), CarsonType.defaultValues(mapOf("colors" to 0, "words" to 20, "body" to 0), mapOf("text" to "HELLO")), 5L)
        assertTrue(dark(out) > 200, "type drawn: ${dark(out)}")
    }

    @Test
    fun `calm water mirrors the picture below the line, above it nothing changes`() {
        val src = testImage(120, 100)
        val calm = mapOf("line" to 500, "waves" to 0, "ripple" to 0, "perspective" to 0, "blur" to 0, "fade" to 0, "darken" to 0, "edge" to 0)
        val out = WaterReflection.apply(src, WaterReflection.defaultValues(calm), 1L)
        for (y in 0 until 50) for (x in 0 until 120) assertEquals(src[x, y], out[x, y])
        // row 60 (10.5 px below the line at 50) shows row 39.5 above it
        val c = out[30, 60]
        val a = src[30, 39]
        val b = src[30, 40]
        assertTrue(listOf(16, 8, 0).all { s -> (c shr s and 0xFF) in minOf(a shr s and 0xFF, b shr s and 0xFF) - 1..maxOf(a shr s and 0xFF, b shr s and 0xFF) + 1 })
    }

    @Test
    fun `trendwhore mirrors like a creature`() {
        // mirrored: left and right alike
        val mirrored = com.spielgrund.glitchr.effects.Trendwhore.apply(white(240, 180), com.spielgrund.glitchr.effects.Trendwhore.defaultValues(mapOf("twMirror" to 1, "glow" to 0)), 6L)
        assertEquals(mirrored[10, 50], mirrored[229, 50])
    }

    @Test
    fun `chrome symbols shine, turn and are smooth with anti-aliasing`() {
        fun chrome(vararg c: Pair<String, Int>) = com.spielgrund.glitchr.effects.ChromeSymbols.apply(
            Pixels(240, 180).also { it.data.fill(0xFF000000.toInt()) }, com.spielgrund.glitchr.effects.ChromeSymbols.defaultValues(mapOf(*c)), 6L,
        )
        // shiny: bright and dark next to each other inside the symbol
        val one = chrome("symbol" to 6)
        assertTrue(one.data.count { (it and 0xFF) > 220 } > 100 && one.data.count { (it and 0xFF) in 1..60 } > 50)
        // every symbol is its own shape
        val shapes = (1..12).map { chrome("symbol" to it).data }
        for (a in shapes.indices) for (b in a + 1 until shapes.size) assertTrue(!shapes[a].contentEquals(shapes[b]), "symbol ${a + 1} and ${b + 1}")
        // turning changes it – in the picture plane and in space
        assertTrue(!chrome("symbol" to 2, "rotation" to 0).data.contentEquals(chrome("symbol" to 2, "rotation" to 3000).data))
        assertTrue(!chrome("symbol" to 2).data.contentEquals(chrome("symbol" to 2, "turnX" to 4000).data))
        // turned sideways in space it gets narrower
        fun width(p: Pixels) = (0 until p.width).count { x -> (0 until p.height).any { y -> (p[x, y] and 0xFF) > 30 } }
        assertTrue(width(chrome("symbol" to 2, "turnY" to 6000)) < width(chrome("symbol" to 2)) * 0.7)
        // anti-aliasing: more in-between values along the edges
        fun grey(p: Pixels) = p.data.toSet().size
        assertTrue(grey(chrome("symbol" to 2, "antialias" to 2)) > grey(chrome("symbol" to 2, "antialias" to 0)))
    }

    @Test
    fun `a generated tribal is one connected piece`() {
        for (style in 0..3) {
            val out = Tribal.apply(white(300, 400), Tribal.defaultValues(mapOf("style" to style, "shape" to 1)), 11L)
            val w = out.width
            val ink = BooleanArray(out.data.size) { (out.data[it] and 0xFF) < 128 }
            val total = ink.count { it }
            // the largest connected patch of ink
            val seen = BooleanArray(ink.size)
            var largest = 0
            for (start in ink.indices) {
                if (!ink[start] || seen[start]) continue
                var size = 0
                val stack = ArrayDeque<Int>().apply { add(start) }
                seen[start] = true
                while (stack.isNotEmpty()) {
                    val i = stack.removeLast()
                    size++
                    for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                        if (j < 0 || j >= ink.size || seen[j] || !ink[j]) continue
                        if ((j == i - 1 || j == i + 1) && j / w != i / w) continue
                        seen[j] = true
                        stack.add(j)
                    }
                }
                largest = maxOf(largest, size)
            }
            assertTrue(largest > total * 0.97, "style $style: $largest of $total ink pixels in one piece")
        }
    }

    @Test
    fun `polynesian bands draw every pattern`() {
        val outs = (1..7).map { Tribal.apply(white(), Tribal.defaultValues(mapOf("style" to 4, "pattern" to it, "bands" to 3)), 1L).data }
        for (a in outs.indices) for (b in a + 1 until outs.size) assertTrue(!outs[a].contentEquals(outs[b]), "pattern ${a + 1} and ${b + 1}")
    }

    @Test
    fun `a tribal fits onto the canvas, moves, scales and can stand on transparency`() {
        val big = mapOf("shape" to 0, "levels" to 3, "count" to 5, "size" to 160)
        fun ink(p: Pixels, x: Int, y: Int) = (p[x, y] ushr 24) > 0 && (p[x, y] and 0xFF) < 128
        // fitted: nothing touches the canvas border
        val fitted = Tribal.apply(white(), Tribal.defaultValues(big), 3L)
        assertTrue((0 until 200).none { ink(fitted, it, 0) || ink(fitted, it, 159) } && (0 until 160).none { ink(fitted, 0, it) || ink(fitted, 199, it) })
        // transparent ground
        val clear = Tribal.apply(white(), Tribal.defaultValues(big + mapOf("transparent" to 1)), 3L)
        assertEquals(0, clear[1, 1] ushr 24)
        // moved to the right: no ink on the left
        val moved = Tribal.apply(white(), Tribal.defaultValues(big + mapOf("posX" to 300, "scale" to 50)), 3L)
        assertTrue((0 until 160).none { y -> (0 until 70).any { ink(moved, it, y) } })
        // turning a level changes the ornament
        assertTrue(!fitted.data.contentEquals(Tribal.apply(white(), Tribal.defaultValues(big + mapOf("rot2" to 6000)), 3L).data))
    }

    @Test
    fun `chrome symbols with real thickness show their side when turned`() {
        fun chrome(vararg c: Pair<String, Int>) = com.spielgrund.glitchr.effects.ChromeSymbols.apply(
            Pixels(200, 200).also { it.data.fill(0xFF000000.toInt()) },
            com.spielgrund.glitchr.effects.ChromeSymbols.defaultValues(mapOf("symbol" to 7, "antialias" to 0, *c)), 2L,
        )
        fun width(p: Pixels) = (0 until p.width).count { x -> (0 until p.height).any { y -> (p[x, y] and 0xFF) > 30 } }
        // flat, seen exactly from the side: nothing; with thickness: its depth
        assertTrue(width(chrome("turnY" to 9000)) < 4)
        assertTrue(width(chrome("turnY" to 9000, "thickness" to 80)) > 20)
        // crinkles change the surface
        assertTrue(!chrome("thickness" to 60).data.contentEquals(chrome("thickness" to 60, "crinkle" to 80).data))
    }

    @Test
    fun `chrome symbols can stand on transparency`() {
        val out = com.spielgrund.glitchr.effects.ChromeSymbols.apply(
            Pixels(160, 160).also { it.data.fill(0xFF000000.toInt()) },
            com.spielgrund.glitchr.effects.ChromeSymbols.defaultValues(mapOf("symbol" to 1, "transparent" to 1)), 1L,
        )
        assertEquals(0, out[2, 2] ushr 24, "transparent around")
        assertEquals(255, out[80, 80] ushr 24, "the symbol opaque")
    }

    @Test
    fun `chrome crinkles slant and pull the outline in`() {
        val black = Pixels(200, 200).also { it.data.fill(0xFF000000.toInt()) }
        fun run(extra: Map<String, Int>) = com.spielgrund.glitchr.effects.ChromeSymbols.apply(
            black.copy(),
            com.spielgrund.glitchr.effects.ChromeSymbols.defaultValues(mapOf("symbol" to 1, "transparent" to 1, "crinkle" to 100) + extra), 1L,
        )
        val straight = run(mapOf("crinklePull" to 0))
        assertTrue(!run(mapOf("crinklePull" to 0, "crinkleAngle" to 4000)).data.contentEquals(straight.data), "the angle changes the folds")
        fun covered(p: Pixels) = p.data.count { it ushr 24 > 127 }
        assertTrue(covered(run(mapOf("crinklePull" to 100))) < covered(straight), "the folds bite into the outline")
    }

    @Test
    fun `trendwhore has several 3d styles that turn in space`() {
        fun run(extra: Map<String, Int>) = com.spielgrund.glitchr.effects.Trendwhore.apply(
            white(240, 180), com.spielgrund.glitchr.effects.Trendwhore.defaultValues(mapOf("glow" to 0, "hud" to 0) + extra), 4L,
        ).data
        val looks = (0 until com.spielgrund.glitchr.effects.TrendwhoreShapes.STYLES.size).map { run(mapOf("twStyle" to it)) }
        for (a in looks.indices) for (b in a + 1 until looks.size) assertTrue(!looks[a].contentEquals(looks[b]), "style $a differs from $b")
        for (style in looks.indices) for (axis in listOf("twTurnX", "twTurnY", "twRotation")) {
            assertTrue(!run(mapOf("twStyle" to style, axis to 3000)).contentEquals(looks[style]), "$axis turns style $style")
        }
    }

    @Test
    fun `own icons come from svg and black-and-white pictures`() {
        val icons = com.spielgrund.glitchr.effects.CustomIcon
        // svg: a square from a path with relative commands and an arc, a white hole cut by a css class
        val svg = """<svg viewBox="0 0 100 100"><style>.hole { fill: #fff }</style><g transform="translate(10 10)">""" +
            """<path d="M0 0h80v80H0z"/><circle class="hole" cx="40" cy="40" r="20"/><path d="M0 90 a10 10 0 0 1 20 0z"/></g></svg>"""
        val shape = icons.svgShape(svg)!!
        assertTrue(shape.contains(5.0 + 10, 5.0 + 10), "the square is filled")
        assertTrue(!shape.contains(50.0, 50.0), "the white circle cuts a hole")
        assertTrue(shape.contains(20.0, 98.0), "the arc is read")
        assertTrue(icons.fromSvg("<svg><g fill=\"none\"><rect width=\"5\" height=\"5\"/></g></svg>") == null, "nothing filled: no icon")
        // pictures: dark on light and light on dark both become the symbol
        fun picture(back: java.awt.Color, front: java.awt.Color) = java.awt.image.BufferedImage(60, 40, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = back; fillRect(0, 0, 60, 40); color = front; fillRect(20, 10, 20, 20); dispose() }
        }
        for (pic in listOf(picture(java.awt.Color.WHITE, java.awt.Color.BLACK), picture(java.awt.Color.BLACK, java.awt.Color.WHITE))) {
            val icon = icons.decode(icons.fromImage(pic)!!)!!
            assertEquals(1.0, icon.aspect, 0.01, "cut to the square")
        }
        // the chrome generator draws the icon instead of its symbols
        fun chrome(text: String) = com.spielgrund.glitchr.effects.ChromeSymbols.apply(
            Pixels(160, 160).also { it.data.fill(0xFF000000.toInt()) },
            Values(com.spielgrund.glitchr.effects.ChromeSymbols.defaults(), mapOf("icon" to text)), 1L,
        )
        val own = chrome("svg:" + svg)
        assertTrue(!own.data.contentEquals(chrome("").data), "the icon replaces the symbol")
        assertEquals(0xFF000000.toInt(), own[80, 80], "the hole stays empty")
    }

    @Test
    fun `palette from image finds roles and passes them on`() {
        val fx = com.spielgrund.glitchr.effects.PaletteTransfer
        // a reference: 60 % blue, 30 % white, 10 % red
        fun picture(vararg parts: Pair<Int, Int>) = java.awt.image.BufferedImage(100, 50, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            var x = 0
            for ((rgb, width) in parts) { for (xx in x until x + width) for (y in 0 until 50) setRGB(xx, y, rgb); x += width }
        }
        val ref = fx.encode(picture(0x1040C0 to 60, 0xF0F0F0 to 30, 0xD02020 to 10))
        val palette = fx.palette(ref, 3)
        assertEquals(listOf("Primary", "Secondary", "Accent"), palette.indices.map { fx.role(it, palette.size) })
        assertEquals(0.6, palette[0].share, 0.03)
        assertTrue((palette[0].rgb and 0xFF) > 150, "primary is the blue")
        assertTrue((palette[2].rgb shr 16 and 0xFF) > 150 && (palette[2].rgb and 0xFF) < 100, "the accent is the red")
        // a picture of 60 % green, 30 % black, 10 % yellow takes them on by role
        val src = Pixels.of(picture(0x20A040 to 60, 0x101010 to 30, 0xF0E020 to 10))
        val out = fx.apply(src, fx.defaultValues(mapOf("colors" to 3, "softness" to 0), mapOf("reference" to ref)), 1L)
        val main = out[20, 25]
        assertTrue((main and 0xFF) > (main shr 8 and 0xFF) + 40, "the main color became blue")
        val accent = out[95, 25]
        assertTrue((accent shr 16 and 0xFF) > (accent shr 8 and 0xFF) + 60, "the accent became red")
    }

    @Test
    fun `chrome contrast and palette hue and saturation`() {
        fun chrome(contrast: Int, brightness: Int) = com.spielgrund.glitchr.effects.ChromeSymbols.apply(
            Pixels(120, 120).also { it.data.fill(0xFF000000.toInt()) },
            com.spielgrund.glitchr.effects.ChromeSymbols.defaultValues(mapOf("symbol" to 1, "contrast" to contrast, "brightness" to brightness)), 1L,
        ).data
        fun spread(d: IntArray) = d.filter { it and 0xFFFFFF != 0 }.map { it and 0xFF }.let { v -> v.max() - v.min() }
        assertTrue(spread(chrome(-80, 0)) < spread(chrome(0, 0)), "less contrast")
        assertTrue(chrome(0, 50).sumOf { it and 0xFF } > chrome(0, 0).sumOf { it and 0xFF }, "brighter")
        assertEquals(0xFF000000.toInt(), chrome(100, 100)[0], "the ground stays")
        val fx = com.spielgrund.glitchr.effects.PaletteTransfer
        val src = Pixels(40, 40).also { p -> for (i in p.data.indices) p.data[i] = if (i % 40 < 20) 0xFF20A040.toInt() else 0xFFC02020.toInt() }
        val grey = fx.apply(src, fx.defaultValues(mapOf("saturation" to 0)), 1L).data
        assertTrue(grey.all { c -> listOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF).let { it.max() - it.min() } <= 3 }, "0 % saturation is grey")
        assertTrue(!fx.apply(src, fx.defaultValues(mapOf("hue" to 120)), 1L).data.contentEquals(fx.apply(src, fx.defaultValues(), 1L).data), "the hue turns")
    }

    @Test
    fun `pasted icons follow parts, brightness and line width`() {
        val icons = com.spielgrund.glitchr.effects.CustomIcon
        // a small picture: a light ring and a mid-grey bar on a dark grey ground
        val pic = java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = java.awt.Color(70, 70, 70); fillRect(0, 0, 40, 40)
                color = java.awt.Color(240, 240, 240); stroke = java.awt.BasicStroke(2f); drawOval(5, 5, 30, 30)
                color = java.awt.Color(120, 120, 120); fillRect(12, 19, 16, 2)
                dispose()
            }
        }
        val text = icons.fromImage(pic)!!
        fun covered(look: com.spielgrund.glitchr.effects.CustomIcon.Look): Int {
            val img = java.awt.image.BufferedImage(200, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.createGraphics().apply { color = java.awt.Color.WHITE; icons.decode(text, look)!!.fill(this, 100.0, 100.0, 200.0); dispose() }
            var n = 0
            for (y in 0 until 200) for (x in 0 until 200) if (img.getRGB(x, y) ushr 24 > 127) n++
            return n
        }
        val plain = com.spielgrund.glitchr.effects.CustomIcon.Look(contrast = 1.5)
        assertTrue(covered(plain.copy(brightness = 0.35)) > covered(plain), "brighter takes in the mid-grey bar")
        assertTrue(covered(plain.copy(width = 0.3)) > covered(plain) * 1.3, "wider lines")
        assertTrue(covered(plain.copy(width = -0.2)) < covered(plain), "thinner lines")
        assertTrue(covered(plain.copy(smooth = 0.5)) != covered(plain), "smoothing changes the outline")
        // svg grows too
        val svg = icons.fromSvg("<svg><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\"/></svg>")!!
        assertTrue(icons.decode(svg, com.spielgrund.glitchr.effects.CustomIcon.Look(width = 0.5))!!.aspect == 1.0)
    }

    @Test
    fun `palettes come from text and any data`() {
        val fx = com.spielgrund.glitchr.effects.PaletteTransfer
        // written colors become the palette, in their order, the last one the accent
        val written = fx.palette(fx.fromText("brand: #ff6600 #003366 #f0f0f0 rgb(20, 200, 120)"), 4).map { it.rgb }
        assertEquals(listOf(0xFF6600, 0x003366, 0xF0F0F0, 0x14C878), written)
        // other text: made up, always the same for the same text, different for other text
        fun colors(text: String) = fx.palette(fx.fromText(text), 5).map { it.rgb }
        assertEquals(colors("Hallo Welt"), colors("Hallo Welt"))
        assertTrue(colors("Hallo Welt") != colors("Hallo Welt!"))
        assertEquals(5, colors("ocean").size)
        // a mood word sets the hue: the forest is green
        val forest = colors("forest")[0]
        assertTrue((forest shr 8 and 0xFF) > (forest shr 16 and 0xFF) && (forest shr 8 and 0xFF) > (forest and 0xFF), "forest is green")
        // dark and light moods
        fun light(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
        assertTrue(light(colors("dark night")[0]) < light(colors("pastel cream")[0]))
        // any bytes
        assertEquals(5, fx.palette(fx.fromBytes(byteArrayOf(1, 2, 3)), 5).size)
    }

    @Test
    fun `tiling repeats the picture and stochastic tiling breaks the repetition`() {
        val fx = com.spielgrund.glitchr.effects.Tiling
        // a picture with a gradient, so every place differs
        val src = Pixels(120, 60).also { p -> for (y in 0 until 60) for (x in 0 until 120) p.data[y * 120 + x] = (0xFF shl 24) or ((x * 2) shl 16) or ((y * 4) shl 8) or 80 }
        val plain = fx.apply(src, fx.defaultValues(mapOf("repeats" to 2, "seamless" to 0, "stochastic" to 0)), 1L)
        // two repeats across: the left half comes again on the right
        assertEquals(plain[10, 10], plain[70, 10])
        val stochastic = fx.apply(src, fx.defaultValues(mapOf("repeats" to 2)), 1L)
        var same = 0
        for (y in 0 until 30) for (x in 0 until 60) if (stochastic[x, y] == stochastic[x + 60, y]) same++
        assertTrue(same < 30 * 60 / 2, "the repeats differ")
        assertTrue(!fx.apply(src, fx.defaultValues(mapOf("repeats" to 2)), 2L).data.contentEquals(stochastic.data), "another seed, another arrangement")
    }
}
