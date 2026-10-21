package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Error messages bouncing away like the cards when you've won Solitaire: old-school dialogs
 * (Windows 98, Windows XP, Mac OS 9) launch one after another from their stack, fall, bounce
 * off the bottom and leave a trail of copies behind. Animate the progress and they fly.
 */
object ErrorCascade : Effect("errors", "Error cascade", "Error messages bouncing away like the cards when you've won Solitaire, leaving trails") {
    private val styles = listOf("Windows 98", "Windows XP", "Mac OS 9", "Mixed")

    override val params = listOf(
        Param.Slider("progress", "Progress", 0, 200000, 20000, " steps", "Animate it: the dialogs launch one after another and bounce away", decimals = 2),
        Param.Slider("count", "Dialogs", 1, 80, 12, tip = "How many error messages"),
        Param.Slider("delay", "Launch delay", 0, 20000, 2500, " steps", "Between one dialog launching and the next", decimals = 2),
        Param.Heading("textHeading", "Messages"),
        Param.Choice("style", "Style", styles, 0, "The look of the dialogs; mixed: a random one each"),
        Param.Text("title", "Title", "Error", "The dialogs' title"),
        Param.Text(
            "messages", "Messages",
            "An error has occurred.|Task failed successfully.|This program has performed an illegal operation and will be shut down.|" +
                "Not enough memory to complete this operation.|General protection fault.|File not found.|Abort, Retry, Fail?|" +
                "Sorry, a system error occurred.|Something happened.|Keyboard not found. Press F1 to continue.",
            "The messages, separated by |; used in turn",
        ),
        Param.Slider("size", "Size", 500, 10000, 2800, " %", "Width of a dialog, of the canvas width", decimals = 2, transform = true),
        Param.Heading("startHeading", "Start"),
        Param.Slider("startX", "Start X", 0, 10000, 7000, " %", "Where the stack lies, of the canvas width", decimals = 2, transform = true),
        Param.Slider("startY", "Start Y", 0, 10000, 1500, " %", "Of the canvas height", decimals = 2, transform = true),
        Param.Slider("spread", "Spread", 0, 10000, 3000, " %", "How far apart the dialogs start, of the canvas size", decimals = 2, transform = true),
        Param.Toggle("stack", "Show waiting dialogs", true, "The dialogs not launched yet wait at their start, like the cards on their piles"),
        Param.Heading("motionHeading", "Motion"),
        Param.Slider("speed", "Speed", 0, 400, 100, " %", "How fast they fly sideways"),
        Param.Slider("direction", "Direction", -100, 100, 0, " %", "−100 %: all to the left · 0: either way · 100 %: all to the right"),
        Param.Slider("lift", "Lift", 0, 300, 60, " %", "How high they jump at the launch"),
        Param.Slider("gravity", "Gravity", 0, 400, 100, " %"),
        Param.Slider("bounce", "Bounce", 0, 100, 75, " %", "How much of their speed they keep when bouncing off the bottom"),
        Param.Slider("spin", "Spin", -2000, 2000, 0, "°", "Turning per step while flying – Solitaire's cards don't", decimals = 2),
        Param.Heading("trailHeading", "Trail"),
        Param.Toggle("trail", "Trail", true, "Every step leaves a copy behind, as in Solitaire; off: only the dialogs where they are now"),
        Param.Slider("spacing", "Trail spacing", 100, 2000, 200, " steps", "A copy every so many steps; more = sparser, faster", decimals = 2),
        Param.Toggle("transparent", "Transparent background", false, "Only the dialogs, without the base color"),
    )

    /** One dialog drawn at [scale] (1 = 300 px wide). */
    private fun dialog(style: Int, title: String, message: String, scale: Double): BufferedImage {
        val base = 300
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        val textFont = when (style) {
            1 -> Font("Tahoma", Font.PLAIN, 12)
            2 -> Font("Geneva", Font.PLAIN, 12)
            else -> Font("Tahoma", Font.PLAIN, 11)
        }
        val fm = probe.getFontMetrics(textFont)
        // the message wrapped into lines beside the icon
        val textLeft = if (style == 2) 70 else 62
        val textWidth = base - textLeft - 16
        val lines = ArrayList<String>()
        for (para in message.split("\\n")) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(tryLine) > textWidth && line.isNotEmpty()) { lines += line; line = word } else line = tryLine
            }
            lines += line
        }
        probe.dispose()
        val titleH = when (style) { 1 -> 28; 2 -> 20; else -> 20 }
        val textH = max(36, lines.size * fm.height)
        val bodyH = 16 + textH + 14 + 24 + 12
        val dh = titleH + bodyH + if (style == 1) 3 else 2
        val img = BufferedImage(ceil(base * scale).toInt() + 1, ceil(dh * scale).toInt() + 1, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(scale, scale)
        val w = base
        val textTop = titleH + 16
        fun text(color: Color) {
            g.font = textFont
            g.color = color
            val top = textTop + (textH - lines.size * fm.height) / 2
            for ((i, l) in lines.withIndex()) g.drawString(l, textLeft, top + i * fm.height + fm.ascent)
        }
        val buttonY = textTop + textH + 14
        when (style) {
            1 -> {
                // Windows XP: a blue rounded title bar, a beige body, a red close button
                g.paint = GradientPaint(0f, 0f, Color(0x0A5FE8), 0f, titleH.toFloat(), Color(0x0042D6))
                g.fill(RoundRectangle2D.Double(0.0, 0.0, w.toDouble(), dh.toDouble(), 14.0, 14.0))
                g.paint = GradientPaint(0f, 0f, Color(0x3D95FF), 0f, 8f, Color(0x0A5FE8))
                g.fill(RoundRectangle2D.Double(0.0, 0.0, w.toDouble(), 10.0, 14.0, 14.0))
                g.color = Color(0xECE9D8)
                g.fillRect(3, titleH, w - 6, dh - titleH - 3)
                g.font = Font("Trebuchet MS", Font.BOLD, 13)
                g.color = Color(0x0A1E6A)
                g.drawString(title, 9, 19)
                g.color = Color.WHITE
                g.drawString(title, 8, 18)
                // the close button
                val bx = w - 27.0; val by = 4.0
                g.paint = GradientPaint(0f, by.toFloat(), Color(0xF0A08A), 0f, (by + 21).toFloat(), Color(0xD13C17))
                g.fill(RoundRectangle2D.Double(bx, by, 21.0, 21.0, 5.0, 5.0))
                g.color = Color.WHITE
                g.stroke = BasicStroke(1.2f)
                g.draw(RoundRectangle2D.Double(bx, by, 21.0, 21.0, 5.0, 5.0))
                g.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine((bx + 6).toInt(), (by + 6).toInt(), (bx + 15).toInt(), (by + 15).toInt())
                g.drawLine((bx + 15).toInt(), (by + 6).toInt(), (bx + 6).toInt(), (by + 15).toInt())
                // the icon: a red ball with a white cross
                val ix = 16.0; val iy = textTop + textH / 2.0 - 16
                g.paint = GradientPaint(ix.toFloat(), iy.toFloat(), Color(0xFF8A6E), (ix + 32).toFloat(), (iy + 32).toFloat(), Color(0xC80F00))
                g.fill(Ellipse2D.Double(ix, iy, 32.0, 32.0))
                g.color = Color(0x8A0A00)
                g.stroke = BasicStroke(1f)
                g.draw(Ellipse2D.Double(ix, iy, 32.0, 32.0))
                g.color = Color.WHITE
                g.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine((ix + 10).toInt(), (iy + 10).toInt(), (ix + 22).toInt(), (iy + 22).toInt())
                g.drawLine((ix + 22).toInt(), (iy + 10).toInt(), (ix + 10).toInt(), (iy + 22).toInt())
                text(Color.BLACK)
                // OK
                val okX = (w - 75) / 2.0
                g.paint = GradientPaint(0f, buttonY.toFloat(), Color.WHITE, 0f, (buttonY + 23).toFloat(), Color(0xE3E1D6))
                g.fill(RoundRectangle2D.Double(okX, buttonY.toDouble(), 75.0, 23.0, 6.0, 6.0))
                g.color = Color(0x003C74)
                g.stroke = BasicStroke(1f)
                g.draw(RoundRectangle2D.Double(okX, buttonY.toDouble(), 75.0, 23.0, 6.0, 6.0))
                g.color = Color(0x7DA2CE)
                g.draw(RoundRectangle2D.Double(okX + 1.5, buttonY + 1.5, 72.0, 20.0, 4.0, 4.0))
                g.font = Font("Tahoma", Font.PLAIN, 12)
                g.color = Color.BLACK
                val ow = g.fontMetrics.stringWidth("OK")
                g.drawString("OK", (okX + (75 - ow) / 2).toFloat(), (buttonY + 16).toFloat())
            }
            2 -> {
                // Mac OS 9: platinum, a pinstriped title bar, the bomb
                g.color = Color(0xDDDDDD)
                g.fillRect(0, 0, w, dh)
                g.color = Color(0xEEEEEE)
                g.fillRect(1, 1, w - 2, titleH - 1)
                g.color = Color(0xAAAAAA)
                for (y in 4 until titleH - 3 step 2) g.drawLine(4, y, w - 5, y)
                g.font = Font("Charcoal", Font.BOLD, 12)
                val tw = g.fontMetrics.stringWidth(title)
                g.color = Color(0xEEEEEE)
                g.fillRect((w - tw) / 2 - 6, 2, tw + 12, titleH - 4)
                g.color = Color.BLACK
                g.drawString(title, (w - tw) / 2, 15)
                // the close box
                g.color = Color(0xEEEEEE)
                g.fillRect(8, 4, 12, 12)
                g.color = Color(0x555555)
                g.drawRect(8, 4, 11, 11)
                g.color = Color.WHITE
                g.drawLine(1, titleH + 1, w - 2, titleH + 1)
                g.drawLine(1, titleH + 1, 1, dh - 2)
                g.color = Color(0x999999)
                g.drawLine(w - 2, titleH + 1, w - 2, dh - 2)
                g.drawLine(1, dh - 2, w - 2, dh - 2)
                g.color = Color.BLACK
                g.drawRect(0, 0, w - 1, dh - 1)
                g.drawLine(0, titleH, w - 1, titleH)
                // the bomb
                val ix = 16.0; val iy = textTop + textH / 2.0 - 14
                g.color = Color.BLACK
                g.fill(Ellipse2D.Double(ix, iy + 4, 32.0, 32.0))
                g.fillRect((ix + 20).toInt(), (iy + 2).toInt(), 8, 7)
                g.color = Color.WHITE
                g.fill(Ellipse2D.Double(ix + 7, iy + 11, 7.0, 7.0))
                g.color = Color.BLACK
                g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(java.awt.geom.QuadCurve2D.Double(ix + 24, iy + 2, ix + 28, iy - 6, ix + 36, iy - 3))
                g.color = Color(0xFFB000)
                g.fill(Ellipse2D.Double(ix + 34, iy - 6, 5.0, 5.0))
                text(Color.BLACK)
                // the default button: rounded, a thick ring around it
                val okX = w - 92.0
                g.color = Color.BLACK
                g.stroke = BasicStroke(3f)
                g.draw(RoundRectangle2D.Double(okX - 4, buttonY - 4.0, 82.0, 31.0, 14.0, 14.0))
                g.paint = GradientPaint(0f, buttonY.toFloat(), Color(0xF4F4F4), 0f, (buttonY + 23).toFloat(), Color(0xC8C8C8))
                g.fill(RoundRectangle2D.Double(okX, buttonY.toDouble(), 74.0, 23.0, 9.0, 9.0))
                g.color = Color.BLACK
                g.stroke = BasicStroke(1f)
                g.draw(RoundRectangle2D.Double(okX, buttonY.toDouble(), 74.0, 23.0, 9.0, 9.0))
                g.font = Font("Charcoal", Font.BOLD, 12)
                val ow = g.fontMetrics.stringWidth("Restart")
                g.drawString("Restart", (okX + (74 - ow) / 2).toFloat(), (buttonY + 16).toFloat())
            }
            else -> {
                // Windows 98: grey, bevelled, a navy title bar fading to blue
                g.color = Color(0xC0C0C0)
                g.fillRect(0, 0, w, dh)
                fun bevel(x: Int, y: Int, bw: Int, bh: Int, light: Color, dark: Color) {
                    g.color = light
                    g.drawLine(x, y, x + bw - 1, y); g.drawLine(x, y, x, y + bh - 1)
                    g.color = dark
                    g.drawLine(x, y + bh - 1, x + bw - 1, y + bh - 1); g.drawLine(x + bw - 1, y, x + bw - 1, y + bh - 1)
                }
                bevel(0, 0, w, dh, Color(0xDFDFDF), Color.BLACK)
                bevel(1, 1, w - 2, dh - 2, Color.WHITE, Color(0x808080))
                g.paint = GradientPaint(3f, 0f, Color(0x000080), (w - 3).toFloat(), 0f, Color(0x1084D0))
                g.fillRect(3, 3, w - 6, 18)
                g.font = Font("Tahoma", Font.BOLD, 11)
                g.color = Color.WHITE
                g.drawString(title, 7, 16)
                // the close button
                val bx = w - 21; val by = 5
                g.color = Color(0xC0C0C0)
                g.fillRect(bx, by, 16, 14)
                bevel(bx, by, 16, 14, Color.WHITE, Color.BLACK)
                bevel(bx + 1, by + 1, 14, 12, Color(0xDFDFDF), Color(0x808080))
                g.color = Color.BLACK
                g.stroke = BasicStroke(1.6f)
                g.drawLine(bx + 4, by + 3, bx + 11, by + 10)
                g.drawLine(bx + 11, by + 3, bx + 4, by + 10)
                g.stroke = BasicStroke(1f)
                // the icon: a red ball with a white cross
                val ix = 16.0; val iy = textTop + textH / 2.0 - 16
                g.color = Color(0xFF0000)
                g.fill(Ellipse2D.Double(ix, iy, 32.0, 32.0))
                g.color = Color(0x800000)
                g.draw(Ellipse2D.Double(ix, iy, 32.0, 32.0))
                g.color = Color.WHITE
                g.stroke = BasicStroke(3.5f)
                g.drawLine((ix + 10).toInt(), (iy + 10).toInt(), (ix + 22).toInt(), (iy + 22).toInt())
                g.drawLine((ix + 22).toInt(), (iy + 10).toInt(), (ix + 10).toInt(), (iy + 22).toInt())
                g.stroke = BasicStroke(1f)
                text(Color.BLACK)
                // OK, with the focus ring
                val okX = (w - 75) / 2
                bevel(okX, buttonY, 75, 23, Color.WHITE, Color.BLACK)
                bevel(okX + 1, buttonY + 1, 73, 21, Color(0xDFDFDF), Color(0x808080))
                g.color = Color.BLACK
                g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(1f, 1f), 0f)
                g.drawRect(okX + 4, buttonY + 4, 66, 14)
                g.stroke = BasicStroke(1f)
                g.font = Font("Tahoma", Font.PLAIN, 11)
                val ow = g.fontMetrics.stringWidth("OK")
                g.drawString("OK", okX + (75 - ow) / 2, buttonY + 15)
            }
        }
        g.dispose()
        return img
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val random = Random(seed)
        val unit = min(w, h) / 100.0
        val progress = v["progress"] / 100.0
        val count = v["count"]
        val delay = v["delay"] / 100.0
        val style = v["style"]
        val title = v.text("title")
        val messages = v.text("messages").split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("An error has occurred.") }
        val scale = w * v["size"] / 10000.0 / 300.0
        val speed = v["speed"] / 100.0
        val direction = v["direction"] / 100.0
        val lift = v["lift"] / 100.0
        val gravity = unit * 0.12 * v["gravity"] / 100.0
        val bounce = v["bounce"] / 100.0
        val spin = Math.toRadians(v["spin"] / 100.0)
        val trail = v.bool("trail")
        val spacing = v["spacing"] / 100.0
        val spread = v["spread"] / 10000.0 * min(w, h)

        // the dialogs as pictures – one for each look and message in use
        val looks = HashMap<Long, BufferedImage>()
        class Card(val img: BufferedImage, val x0: Double, val y0: Double, val vx: Double, val vy0: Double, val launch: Double)
        val cards = List(count) { i ->
            val st = if (style == 3) random.nextInt(3) else style
            val msg = i % messages.size
            val img = looks.getOrPut(st * 100000L + msg) { dialog(st, title, messages[msg], scale) }
            // the stack: around the start, the dialog's middle there
            val x0 = w * v["startX"] / 10000.0 + (random.nextDouble() - 0.5) * spread - img.width / 2.0
            val y0 = h * v["startY"] / 10000.0 + (random.nextDouble() - 0.5) * spread * 0.4 - img.height / 2.0
            val side = if (random.nextDouble() * 2 - 1 < direction) 1.0 else -1.0
            val vx = side * unit * 0.9 * speed * (0.45 + random.nextDouble() * 0.8)
            val vy0 = -unit * 1.6 * lift * random.nextDouble()
            Card(img, x0, y0, vx, vy0, i * delay)
        }

        val out = if (v.bool("transparent")) BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB) else src.toImage().let { im ->
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(im, 0, 0, null); dispose() } }
        }
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        fun draw(c: Card, x: Double, y: Double, angle: Double) {
            if (angle == 0.0) {
                g.drawImage(c.img, floor(x).toInt(), floor(y).toInt(), null)
            } else {
                val t = g.transform
                g.rotate(angle, x + c.img.width / 2.0, y + c.img.height / 2.0)
                g.drawImage(c.img, floor(x).toInt(), floor(y).toInt(), null)
                g.transform = t
            }
        }

        // the ones still waiting lie on their piles, the last one on top
        if (v.bool("stack")) for (c in cards.reversed()) if (c.launch >= progress) draw(c, c.x0, c.y0, 0.0)

        // all flying at once, step by step, so later steps lie on top of earlier ones
        class Flight(val card: Card) { var x = card.x0; var y = card.y0; var vy = card.vy0; var angle = 0.0; var gone = false }
        val flights = cards.map { Flight(it) }
        fun step(f: Flight, dt: Double) {
            val floorY = h - f.card.img.height.toDouble()
            f.x += f.card.vx * dt
            f.vy += gravity * dt
            f.y += f.vy * dt
            f.angle += spin * dt
            if (f.y > floorY) {
                f.y = floorY
                f.vy = if (abs(f.vy) * bounce < gravity * 2) 0.0 else -abs(f.vy) * bounce
            }
            if (f.x + f.card.img.width < 0 || f.x > w) f.gone = true
        }
        var t = 0.0
        var sinceCopy = spacing
        while (t < progress) {
            val dt = min(1.0, progress - t)
            for (f in flights) {
                if (f.gone || t < f.card.launch) continue
                // a launch between two steps flies only its share of this one
                step(f, min(dt, t + dt - f.card.launch))
            }
            t += dt
            sinceCopy += dt
            if (trail && sinceCopy >= spacing - 1e-9) {
                sinceCopy = 0.0
                for (f in flights) if (!f.gone && t > f.card.launch) draw(f.card, f.x, f.y, f.angle)
            }
        }
        // where they are now, on top
        for (f in flights) if (!f.gone && progress > f.card.launch) draw(f.card, f.x, f.y, f.angle)
        g.dispose()
        return Pixels.of(out)
    }
}
