package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Chaotic typography in the manner of David Carson's grunge layouts: words in mixed
 * typefaces and wildly different sizes thrown across the picture – stretched, squeezed,
 * turned on their side or upside down, cut into slices that slip apart, tracked out or
 * crammed together, run off the edge – plus columns of small body copy, laid on in a
 * blend mode. The text is your own or made up.
 */
object CarsonType : Effect("carsontype", "Chaos type", "Chaotic grunge typography in the manner of David Carson – your text or made-up words thrown across the picture") {
    private val words = (
        "noise signal surface error lost found form void echo static nowhere always never between " +
            "edge light dark fast slow loud quiet broken still moving here there now later begin end " +
            "fragment layer pulse drift fade distort open closed inside outside random order chaos " +
            "print paper ink sound wave"
        ).split(" ")

    private val blends = listOf("Normal", "Multiply", "Screen", "Difference", "Overlay")

    override val params = listOf(
        Param.Text("text", "Text", "", "Your words; empty = made-up words"),
        Param.Slider("words", "Words", 1, 300, 40),
        Param.Slider("body", "Body copy", 0, 20, 3, tip = "Columns of small text"),
        Param.Slider("minSize", "Smallest", 4, 500, 12, " px", canvasMax = true),
        Param.Slider("maxSize", "Largest", 4, 2000, 220, " px", canvasMax = true),
        Param.Slider("rotation", "Turned", 0, 100, 35, " %", "Share of words turned – on their side, upside down or tilted"),
        Param.Slider("stretch", "Stretch", 0, 100, 40, " %", "Words squeezed or pulled wide"),
        Param.Slider("slices", "Slices", 0, 100, 25, " %", "Share of words cut into slices that slip apart"),
        Param.Slider("tracking", "Tracking", 0, 100, 40, " %", "Letters crammed together or spread far apart"),
        Param.Choice("fonts", "Typefaces", listOf("All mixed", "Serif", "Sans", "Mono", "Bold display"), 0),
        Param.Choice("colors", "Colors", listOf("Black", "White", "Black and white", "Picture colors", "Accent"), 2),
        Param.Color("accent", "Accent color", 0xE63946),
        Param.Choice("blend", "Blend mode", blends, 0),
        Param.Slider("opacity", "Opacity", 10, 100, 100, " %"),
    )

    /** Font families present on this machine, sorted, so the same settings give the same picture. */
    private val families: List<String> by lazy {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fontsFor(choice: Int): List<String> {
        val wanted = when (choice) {
            1 -> listOf("Times New Roman", "Georgia", "Garamond", "Bodoni MT", "Book Antiqua", "Palatino Linotype", "Cambria", "Serif")
            2 -> listOf("Arial", "Helvetica", "Verdana", "Tahoma", "Segoe UI", "Trebuchet MS", "Franklin Gothic Medium", "SansSerif")
            3 -> listOf("Courier New", "Consolas", "Lucida Console", "Monospaced")
            4 -> listOf("Impact", "Arial Black", "Franklin Gothic Heavy", "Bauhaus 93", "Stencil", "Haettenschweiler", "Dialog")
            else -> listOf(
                "Times New Roman", "Georgia", "Bodoni MT", "Arial", "Helvetica", "Verdana", "Courier New", "Consolas",
                "Impact", "Arial Black", "Stencil", "Trebuchet MS", "Garamond", "Franklin Gothic Medium", "Serif", "SansSerif", "Monospaced",
            )
        }
        val present = wanted.filter { it in families || it in listOf("Serif", "SansSerif", "Monospaced", "Dialog") }
        return present.ifEmpty { listOf("SansSerif") }
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val own = v.text("text").split(Regex("\\s+")).filter { it.isNotBlank() }
        val pool = own.ifEmpty { words }
        fun word() = pool[random.nextInt(pool.size)].let { if (random.nextDouble() < 0.5 && own.isEmpty()) it.uppercase() else it }
        val fonts = fontsFor(v["fonts"])
        val minSize = v["minSize"].toDouble()
        val maxSize = max(minSize, v["maxSize"].toDouble())
        val turned = v["rotation"] / 100.0
        val stretch = v["stretch"] / 100.0
        val slices = v["slices"] / 100.0
        val tracking = v["tracking"] / 100.0
        val colors = v["colors"]
        val accent = v["accent"]
        val blend = v["blend"]
        val opacity = v["opacity"] / 100.0
        val frc = FontRenderContext(null, true, true)
        val out = src.copy()

        /** A shape of [text] in a random face at [size], with tracking. */
        fun outline(text: String, size: Double): Shape {
            val style = listOf(Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD or Font.ITALIC)[random.nextInt(4)]
            val attrs = HashMap<TextAttribute, Any>()
            attrs[TextAttribute.FAMILY] = fonts[random.nextInt(fonts.size)]
            attrs[TextAttribute.SIZE] = size.toFloat()
            attrs[TextAttribute.WEIGHT] = if (style and Font.BOLD != 0) TextAttribute.WEIGHT_BOLD else TextAttribute.WEIGHT_REGULAR
            attrs[TextAttribute.POSTURE] = if (style and Font.ITALIC != 0) TextAttribute.POSTURE_OBLIQUE else TextAttribute.POSTURE_REGULAR
            // crammed (letters overlapping) or spread wide
            attrs[TextAttribute.TRACKING] = ((random.nextDouble() * 2 - 1) * tracking * 0.35).toFloat()
            return TextLayout(text, Font(attrs), frc).getOutline(null)
        }

        fun pickColor(): Int = when (colors) {
            0 -> 0x111111
            1 -> 0xFFFFFF
            2 -> if (random.nextBoolean()) 0x111111 else 0xF2F2F2
            3 -> {
                // the picture's color somewhere else, so the type stands apart
                val c = src.data[(random.nextInt(h)) * w + random.nextInt(w)]
                c and 0xFFFFFF
            }
            else -> if (random.nextDouble() < 0.35) accent else if (random.nextBoolean()) 0x111111 else 0xF2F2F2
        }

        /** Lays [shape] onto the result in [rgb], by the blend mode, with its own opacity. */
        fun lay(shape: Shape, rgb: Int, alpha: Double) {
            val b = shape.bounds2D.createIntersection(Rectangle2D.Double(0.0, 0.0, w.toDouble(), h.toDouble()))
            if (b.isEmpty) return
            val x0 = b.x.toInt()
            val y0 = b.y.toInt()
            val bw = (b.x + b.width).toInt().coerceAtMost(w - 1) - x0 + 1
            val bh = (b.y + b.height).toInt().coerceAtMost(h - 1) - y0 + 1
            if (bw <= 0 || bh <= 0) return
            val img = BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.translate(-x0.toDouble(), -y0.toDouble())
            g.color = java.awt.Color.WHITE
            g.fill(shape)
            g.dispose()
            val cover = (img.raster.dataBuffer as DataBufferInt).data
            val tr = rgb shr 16 and 0xFF
            val tg = rgb shr 8 and 0xFF
            val tb = rgb and 0xFF
            for (yy in 0 until bh) for (xx in 0 until bw) {
                val a = (cover[yy * bw + xx] ushr 24) / 255.0 * alpha
                if (a <= 0) continue
                val i = (y0 + yy) * w + x0 + xx
                val base = out.data[i]
                val br = base shr 16 and 0xFF
                val bg = base shr 8 and 0xFF
                val bb = base and 0xFF
                fun mode(t: Int, u: Int) = when (blend) {
                    1 -> t * u / 255
                    2 -> 255 - (255 - t) * (255 - u) / 255
                    3 -> kotlin.math.abs(u - t)
                    4 -> if (u < 128) 2 * t * u / 255 else 255 - 2 * (255 - t) * (255 - u) / 255
                    else -> t
                }
                fun mix(t: Int, u: Int) = (u + (mode(t, u) - u) * a + 0.5).toInt().coerceIn(0, 255)
                out.data[i] = argb(max(base ushr 24, (a * 255).toInt()), mix(tr, br), mix(tg, bg), mix(tb, bb))
            }
        }

        // body copy: narrow columns of small text, tightly set
        repeat(v["body"]) {
            val size = max(4.0, minSize * (0.7 + random.nextDouble() * 0.5))
            val colWidth = w * (0.12 + random.nextDouble() * 0.2)
            val lines = 6 + random.nextInt(18)
            val x = random.nextDouble() * w
            val y = random.nextDouble() * h
            val leading = size * (0.85 + random.nextDouble() * 0.4)
            val angle = if (random.nextDouble() < turned) (if (random.nextBoolean()) PI / 2 else -PI / 2) else (random.nextDouble() - 0.5) * 0.08
            val rgb = pickColor()
            val alpha = opacity * (0.7 + random.nextDouble() * 0.3)
            for (line in 0 until lines) {
                val text = StringBuilder()
                while (text.length * size * 0.5 < colWidth) text.append(word().lowercase()).append(' ')
                val shape = outline(text.toString().trim(), size)
                val t = AffineTransform()
                t.translate(x, y)
                t.rotate(angle)
                t.translate(0.0, line * leading)
                lay(t.createTransformedShape(shape), rgb, alpha)
            }
        }

        // the words: sizes spread evenly on a log scale, so big and small both appear
        repeat(v["words"]) {
            val size = exp(ln(minSize) + random.nextDouble() * (ln(maxSize) - ln(minSize)))
            var shape = outline(word(), size)
            val b = shape.bounds2D
            val t = AffineTransform()
            // anywhere, even half off the edge
            t.translate(random.nextDouble() * (w + b.width * 0.6) - b.width * 0.3, random.nextDouble() * (h + b.height) - b.height * 0.2)
            if (random.nextDouble() < turned) {
                t.rotate(when (random.nextInt(4)) {
                    0 -> PI / 2
                    1 -> -PI / 2
                    2 -> PI
                    else -> (random.nextDouble() - 0.5) * 0.9
                })
            }
            if (random.nextDouble() < stretch) t.scale(0.45 + random.nextDouble() * 1.8, 1.0)
            if (random.nextDouble() < stretch * 0.3) t.scale(-1.0, 1.0)
            shape = t.createTransformedShape(shape)
            if (random.nextDouble() < slices) {
                // cut into horizontal slices that slip sideways
                val sb = shape.bounds2D
                val n = 2 + random.nextInt(6)
                val pieces = Area()
                for (k in 0 until n) {
                    val band = Area(Rectangle2D.Double(sb.x - 10, sb.y + sb.height * k / n, sb.width + 20, sb.height / n + 0.5))
                    band.intersect(Area(shape))
                    band.transform(AffineTransform.getTranslateInstance((random.nextDouble() - 0.5) * size * 0.6, 0.0))
                    pieces.add(band)
                }
                shape = pieces
            }
            lay(shape, pickColor(), opacity * (0.55 + random.nextDouble() * 0.45))
        }
        return out
    }
}
