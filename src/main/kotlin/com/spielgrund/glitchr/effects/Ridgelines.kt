package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.red
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Stacked ridge lines like the cover of "Unknown Pleasures": the picture becomes a pile
 * of horizontal lines that rise where it is bright. The lines are drawn from the back
 * (top) to the front (bottom), and every line hides the ones behind it.
 */
object Ridgelines : Effect("ridgelines", "Spektroskop", "Gestapelte Linien, die sich mit dem Bild auftürmen – wie „Unknown Pleasures“") {
    override val params = listOf(
        Param.Slider("spacing", "Linienabstand", 3, 200, 12, " px"),
        Param.Slider("angle", "Winkel", 0, 359, 0, "°", "Dreht die Linien; die Berge wachsen immer quer zu den Linien"),
        Param.Slider("height", "Höhe", 0, 1000, 80, " px", "Wie hoch sich eine Linie an der hellsten Stelle auftürmt", canvasMax = true),
        Param.Choice("source", "Quelle", listOf("Bildhelligkeit", "Bild dunkel", "Nur Noise"), tip = "Was die Linien anhebt; „Nur Noise“ ergibt das Plattencover ohne Bild"),
        Param.Slider("peaks", "Spitzen", 50, 400, 200, " %", "Höhere Werte lassen nur die hellsten Stellen als spitze Berge stehen"),
        Param.Slider("jag", "Zacken", 0, 100, 30, " %", "Zufällige Zacken, die mit der Höhe wachsen"),
        Param.Slider("step", "Punktabstand", 1, 40, 3, " px", "Abstand der Punkte entlang einer Linie – grösser wird kantiger"),
        Param.Slider("smooth", "Glätten", 0, 20, 1, "", "Glättet die Linie über so viele Punkte"),
        Param.Slider("focus", "Mitte betonen", 0, 100, 0, " %", "Lässt die Berge wie auf dem Cover nur in der Mitte wachsen"),
        Param.Slider("focusWidth", "Breite Mitte", 5, 100, 40, " %"),
        Param.Slider("lineWidth", "Linienstärke", 1, 100, 20, tip = "In Zehntelpixeln: 20 = 2 px"),
        Param.Choice("lineColor", "Linienfarbe", listOf("Eine Farbe", "Bildfarbe")),
        Param.Color("color", "Farbe", 0xFFFFFF),
        Param.Choice("background", "Hintergrund", listOf("Farbe", "Transparent", "Originalbild")),
        Param.Color("bgColor", "Hintergrundfarbe", 0x000000),
        Param.Toggle("occlude", "Linien verdecken", true, "Vordere Linien verdecken die dahinter liegenden"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val spacing = v["spacing"].toDouble()
        val height = v["height"].toDouble()
        val source = v["source"]
        val peaks = v["peaks"] / 100.0
        val jag = v["jag"] / 100.0
        val step = v["step"].toDouble()
        val smooth = v["smooth"]
        val focus = v["focus"] / 100.0
        // the lines are laid out in a turned frame that just covers the picture;
        // at 0° it is the picture itself
        val angle = Math.toRadians(v["angle"].toDouble())
        val cos = cos(angle)
        val sin = sin(angle)
        val frameW = abs(cos) * w + abs(sin) * h
        val frameH = abs(sin) * w + abs(cos) * h
        val frame = AffineTransform().apply {
            translate(w / 2.0, h / 2.0)
            rotate(angle)
            translate(-frameW / 2, -frameH / 2)
        }
        val focusWidth = v["focusWidth"] / 100.0 * frameW / 2
        val lineWidth = v["lineWidth"] / 10f
        val imageColor = v["lineColor"] == 1
        val lineColor = Color(v["color"])
        val background = v["background"]
        val occlude = v.bool("occlude")
        val noise = Noise(seed)

        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        when (background) {
            0 -> data.fill(v["bgColor"] or 0xFF000000.toInt())
            2 -> src.data.copyInto(data)
        }
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val stroke = BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val fill = when (background) {
            0 -> Color(v["bgColor"])
            2 -> TexturePaint(src.toImage(), Rectangle(0, 0, w, h))
            else -> null
        }

        val count = (frameW / step).toInt() + 3
        val xs = DoubleArray(count) { (it - 1) * step }
        var row = 0
        var base = spacing / 2
        while (base < frameH + spacing) {
            // how far the line rises at every point, 0..1
            val lift = DoubleArray(count) { i ->
                val x = xs[i]
                val f = when (source) {
                    2 -> ((noise.fbm(x / (w * 0.07), row * 0.37, 3) + 1) / 2).coerceIn(0.0, 1.0)
                    else -> brightness(src, frame, x, base, step, spacing).let { if (source == 1) 1 - it else it }
                }
                val center = if (focus > 0) {
                    val d = (x - frameW / 2) / focusWidth
                    1 - focus + focus * exp(-d * d * 2)
                } else 1.0
                f.pow(peaks) * center
            }
            val raw = DoubleArray(count) { i ->
                // the jags grow with the mountain, with a little everywhere
                val n = if (jag > 0) noise.white(i, row) * jag * (0.06 + lift[i]) else 0.0
                max(-0.15, lift[i] + n)
            }
            val heights = blur(raw, smooth)
            val ys = DoubleArray(count) { base - heights[it] * height }

            val line = Path2D.Double()
            line.moveTo(xs[0], ys[0])
            for (i in 1 until count) line.lineTo(xs[i], ys[i])
            if (occlude) {
                val outline = Path2D.Double(line)
                val floor = base + lineWidth + 1
                outline.lineTo(xs[count - 1], floor)
                outline.lineTo(xs[0], floor)
                outline.closePath()
                val area = frame.createTransformedShape(outline)
                if (fill == null) {
                    val composite = g.composite
                    g.composite = AlphaComposite.Clear
                    g.fill(area)
                    g.composite = composite
                } else {
                    g.paint = fill
                    g.fill(area)
                }
            }
            g.stroke = stroke
            if (imageColor) {
                val at = Point2D.Double()
                for (i in 1 until count) {
                    frame.transform(Point2D.Double((xs[i - 1] + xs[i]) / 2, base), at)
                    val c = src[at.x.toInt().coerceIn(0, w - 1), at.y.toInt().coerceIn(0, h - 1)]
                    g.color = Color(red(c), green(c), blue(c), alpha(c))
                    g.draw(frame.createTransformedShape(Line2D.Double(xs[i - 1], ys[i - 1], xs[i], ys[i])))
                }
            } else {
                g.color = lineColor
                g.draw(frame.createTransformedShape(line))
            }
            row++
            base += spacing
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /**
     * Average brightness 0..1 of the band around the baseline at [x], in the turned [frame];
     * transparent pixels count as dark, parts outside the picture don't count.
     */
    private fun brightness(src: Pixels, frame: AffineTransform, x: Double, base: Double, step: Double, spacing: Double): Double {
        val x0 = floor(x - step / 2).toInt()
        val x1 = max(x0 + 1, floor(x + step / 2).toInt() + 1)
        val y0 = floor(base - spacing / 2).toInt()
        val y1 = max(y0 + 1, floor(base + spacing / 2).toInt())
        val m = DoubleArray(6).also { frame.getMatrix(it) }
        var sum = 0L
        var n = 0L
        for (y in y0 until y1) for (xx in x0 until x1) {
            val lx = xx + 0.5
            val ly = y + 0.5
            val ix = floor(m[0] * lx + m[2] * ly + m[4]).toInt()
            val iy = floor(m[1] * lx + m[3] * ly + m[5]).toInt()
            if (ix < 0 || iy < 0 || ix >= src.width || iy >= src.height) continue
            val c = src.data[iy * src.width + ix]
            sum += luma(c) * alpha(c) / 255
            n++
        }
        return if (n == 0L) 0.0 else sum.toDouble() / (n * 255)
    }

    private fun blur(a: DoubleArray, r: Int): DoubleArray {
        if (r <= 0) return a
        return DoubleArray(a.size) { i ->
            var sum = 0.0
            var n = 0
            for (j in max(0, i - r)..min(a.size - 1, i + r)) { sum += a[j]; n++ }
            sum / n
        }
    }
}
