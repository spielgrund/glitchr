package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Metal
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.red
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MetalTest {
    private val src = testImage(240, 160)
    private val outDir = File("target/test-output").apply { mkdirs() }

    @Test
    fun `every metal, surface and environment gives a reproducible metal picture`() {
        for (metal in 0..8) for (env in 0..3) {
            val v = Metal.defaultValues(mapOf("metal" to metal, "env" to env, "surface" to (metal + env) % 5))
            val out = Metal.apply(src, v, 5L)
            assertContentEquals(out.data, Metal.apply(src, v, 5L).data, "metal $metal, environment $env")
            val changed = (0 until src.data.size).count { out.data[it] != src.data[it] }
            assertTrue(changed > src.data.size / 2, "metal $metal, environment $env: $changed")
        }
        // a contact sheet of lettering and shapes, to look at
        val img = java.awt.image.BufferedImage(240, 160, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        with(img.createGraphics()) {
            setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(40, 40, 60), 240f, 160f, java.awt.Color(90, 70, 50))
            fillRect(0, 0, 240, 160)
            color = java.awt.Color(230, 200, 60)
            fillOval(150, 70, 70, 70)
            color = java.awt.Color.WHITE
            font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 64)
            drawString("Glitch", 14, 70)
            dispose()
        }
        val pic = Pixels.of(img)
        val sheet = Pixels(240 * 3, 160 * 3)
        listOf(0, 2, 3, 7, 5, 6, 1, 4, 8).forEachIndexed { k, metal ->
            val out = Metal.apply(pic, Metal.defaultValues(mapOf("metal" to metal, "env" to k % 4, "surface" to k % 5)), 5L)
            for (y in 0 until 160) for (x in 0 until 240) sheet.data[((k / 3) * 160 + y) * 720 + (k % 3) * 240 + x] = out[x, y]
        }
        ImageIO.write(sheet.toImage(), "png", File(outDir, "metal.png"))
    }

    @Test
    fun `chrome is grey and gold is warm`() {
        val flat = Pixels(60, 60, IntArray(3600) { argb(255, 200, 30, 30) })
        val base = mapOf("surface" to 0, "gloss" to 0)
        val chrome = Metal.apply(flat, Metal.defaultValues(base + ("metal" to 0)), 1L)[30, 30]
        val gold = Metal.apply(flat, Metal.defaultValues(base + ("metal" to 2)), 1L)[30, 30]
        assertTrue(maxOf(red(chrome), green(chrome), blue(chrome)) - minOf(red(chrome), green(chrome), blue(chrome)) < 60, Integer.toHexString(chrome))
        assertTrue(red(gold) > blue(gold) + 60, Integer.toHexString(gold))
    }

    @Test
    fun `anti-aliasing softens the hard steps`() {
        // a hard-edged disc without rounding: the reflection jumps from pixel to pixel
        val disc = Pixels(120, 120, IntArray(120 * 120) { i ->
            val x = i % 120 - 60.0
            val y = i / 120 - 60.0
            if (x * x + y * y < 40.0 * 40.0) argb(255, 255, 255, 255) else argb(255, 0, 0, 0)
        })
        val base = mapOf("smooth" to 2, "surface" to 0, "relief" to 300)
        fun run(aa: Int) = Metal.apply(disc, Metal.defaultValues(base + ("antialias" to aa)), 1L)

        /** The summed brightness jump between neighbouring pixels – smaller is smoother. */
        fun jumps(p: Pixels): Long {
            var s = 0L
            for (y in 0 until 120) for (x in 1 until 120) {
                val a = p[x, y]
                val b = p[x - 1, y]
                s += kotlin.math.abs((red(a) + green(a) + blue(a)) - (red(b) + green(b) + blue(b)))
            }
            return s
        }
        val hard = run(0)
        val soft = run(3)
        assertTrue(jumps(soft) < jumps(hard), "smooth ${jumps(soft)}, hard ${jumps(hard)}")
        assertContentEquals(soft.data, run(3).data)
        val sheet = Pixels(240, 120)
        for (y in 0 until 120) for (x in 0 until 120) {
            sheet.data[y * 240 + x] = hard[x, y]
            sheet.data[y * 240 + 120 + x] = soft[x, y]
        }
        ImageIO.write(sheet.toImage(), "png", File(outDir, "metal-aa.png"))
    }

    @Test
    fun `no strength leaves the picture as it is`() {
        assertContentEquals(src.data, Metal.apply(src, Metal.defaultValues(mapOf("amount" to 0)), 1L).data)
    }
}
