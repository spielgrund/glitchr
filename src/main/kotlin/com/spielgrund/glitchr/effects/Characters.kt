package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.alpha
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.image.blue
import com.spielgrund.glitchr.image.green
import com.spielgrund.glitchr.image.luma
import com.spielgrund.glitchr.image.red
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A character shader: the picture is cut into cells and every cell is drawn as one
 * character. With a character set, the cell's brightness picks the character; with
 * your own text, the letters run through the cells in reading order and the brightness
 * sets their color and, if wanted, size. On a black background bright areas get the
 * dense characters, on a light one dark areas ("Umkehren" swaps that). The brightness
 * is stretched to the picture's own range, and picture colors are lifted accordingly,
 * so dark photos stay readable.
 */
object Characters : Effect("chars", "Zeichen", "Baut das Bild aus ASCII-Zeichen, Glyphen oder eigenem Text auf") {
    /** Character sets from light (sparse) to dark (dense). */
    private val sets = listOf(
        "ASCII fein" to " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$",
        "ASCII einfach" to " .:-=+*#%@",
        "Blöcke" to " ░▒▓█",
        "Punkte" to " ·∙•●",
        "Striche" to " .-/|\\+x#",
        "Binär" to " 01",
        "Matrix" to " ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜﾂｵﾘｱﾎﾃﾏｹﾒｴｶｷﾑﾕﾗｾﾈｽﾀﾇﾍ",
        "Eigener Text" to "",
    )
    internal const val OWN_TEXT = 7
    private val fonts = listOf(Font.MONOSPACED, Font.SANS_SERIF, Font.SERIF)

    override val params = listOf(
        Param.Choice("set", "Zeichensatz", sets.map { it.first }),
        Param.Text("text", "Eigener Text", "GLITCH", "Für „Eigener Text“: die Buchstaben laufen der Reihe nach über das Bild"),
        Param.Slider("cell", "Zellgrösse", 3, 120, 10, " px", "Höhe einer Zeichenzeile"),
        Param.Slider("letterSpacing", "Zeichenabstand", -60, 400, 0, " %", "Abstand zwischen den Zeichen (Kerning), in Prozent der Zeichenbreite; negativ rücken sie zusammen"),
        Param.Slider("lineSpacing", "Zeilenabstand", 40, 500, 100, " %", "Abstand der Zeilen in Prozent der Zellgrösse; unter 100 % überlappen sie"),
        Param.Toggle("invert", "Umkehren", false, "Auf schwarzem Hintergrund bekommen helle Stellen die dichten Zeichen, sonst dunkle – das hier dreht es um"),
        Param.Toggle("scaleByBrightness", "Grösse nach Helligkeit", false, "Stellen mit viel „Tinte“ bekommen grössere Zeichen"),
        Param.Choice("font", "Schrift", listOf("Monospace", "Serifenlos", "Serif")),
        Param.Toggle("bold", "Fett", true),
        Param.Choice("colorMode", "Farbe", listOf("Aus dem Bild", "Eine Farbe")),
        Param.Color("color", "Zeichenfarbe", 0x33FF66, "Für „Eine Farbe“"),
        Param.Choice("background", "Hintergrund", listOf("Schwarz", "Weiss", "Transparent", "Originalbild")),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val cellH = v["cell"]
        val ownText = v["set"] == OWN_TEXT
        val text = v.text("text").filter { !it.isWhitespace() }.ifEmpty { "GLITCH" }
        val chars = sets[v["set"]].second
        // on black, bright areas carry the ink; on light backgrounds dark areas do
        val inkForBright = (v["background"] == 0) != v.bool("invert")
        val scaleByBrightness = v.bool("scaleByBrightness")
        val single = if (v["colorMode"] == 1) (v["color"] or 0xFF000000.toInt()) else null

        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (canvas.raster.dataBuffer as DataBufferInt).data
        when (v["background"]) {
            0 -> data.fill(0xFF000000.toInt())
            1 -> data.fill(-1)
            3 -> src.data.copyInto(data)
        }
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        val font = Font(fonts[v["font"]], if (v.bool("bold")) Font.BOLD else Font.PLAIN, 12).let {
            // size the font so a line is as high as a cell
            val metrics = g.getFontMetrics(it)
            it.deriveFont(12f * cellH / max(1, metrics.ascent + metrics.descent))
        }
        g.font = font
        val metrics = g.fontMetrics
        val charW = if (ownText) cellH * 0.62 else max(1.0, metrics.charWidth('M').toDouble())
        // a cell is the slot one character takes, including the spacing around it
        val cellW = max(1.0, charW * (1 + v["letterSpacing"] / 100.0))
        val rowH = max(1.0, cellH * v["lineSpacing"] / 100.0)
        val columns = (w / cellW).toInt() + 1
        val rows = (h / rowH).toInt() + 1

        // cell averages first, to stretch the brightness to the picture's own range
        val averages = Array(rows) { row ->
            arrayOfNulls<Int>(columns).also { line ->
                for (col in 0 until columns) {
                    val x0 = (col * cellW).toInt()
                    val y0 = (row * rowH).toInt()
                    if (x0 < w && y0 < h) line[col] = average(src, x0, y0, max(x0 + 1, min(w, (x0 + cellW).roundToInt())), max(y0 + 1, min(h, (y0 + rowH).roundToInt())))
                }
            }
        }
        val lumas = averages.flatMap { it.filterNotNull() }.map { luma(it) }.sorted()
        val low = lumas.getOrNull((lumas.size * 0.02).toInt()) ?: 0
        val high = lumas.getOrNull(((lumas.size - 1) * 0.98).toInt()) ?: 255
        val span = if (high - low < 8) 255.0 else (high - low).toDouble()
        val base = if (high - low < 8) 0 else low

        var letter = 0
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val x0 = col * cellW
                // the character sits in the middle of its row
                val y0 = row * rowH + (rowH - cellH) / 2
                val cell = averages[row][col] ?: continue
                val l = luma(cell)
                val bright = ((l - base) / span).coerceIn(0.0, 1.0)
                // "ink" 0..1: how dense (or big) the character here is
                val ink = if (inkForBright) bright else 1 - bright
                val ch = if (ownText) text[letter++ % text.length]
                else chars[(ink * (chars.length - 1) + 0.5).toInt().coerceIn(0, chars.length - 1)]
                if (ch == ' ') continue
                val rgb = single ?: lifted(cell, bright * 255 / max(1, l))
                g.color = Color(red(rgb), green(rgb), blue(rgb), alpha(cell))
                val scale = if (scaleByBrightness) 0.25 + 0.95 * ink else 1.0
                val cw = metrics.charWidth(ch)
                if (scale == 1.0) {
                    g.drawString(ch.toString(), (x0 + (cellW - cw) / 2).toFloat(), (y0 + metrics.ascent).toFloat())
                } else {
                    val t = g.transform
                    g.translate(x0 + cellW / 2, y0 + cellH / 2.0)
                    g.scale(scale, scale)
                    g.drawString(ch.toString(), (-cw / 2.0).toFloat(), (metrics.ascent - cellH / 2.0).toFloat())
                    g.transform = t
                }
            }
        }
        g.dispose()
        return Pixels(w, h, data)
    }

    /** [c] brightened (or darkened) by [factor], at most so far that no channel clips hard. */
    private fun lifted(c: Int, factor: Double): Int {
        val f = factor.coerceIn(0.5, 4.0)
        return argb(alpha(c), (red(c) * f).toInt().coerceIn(0, 255), (green(c) * f).toInt().coerceIn(0, 255), (blue(c) * f).toInt().coerceIn(0, 255))
    }

    /** Average color of the visible pixels in the rectangle; null if all are transparent. */
    private fun average(src: Pixels, x0: Int, y0: Int, x1: Int, y1: Int): Int? {
        var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = src.data[y * src.width + x]
            val ca = alpha(c)
            if (ca == 0) continue
            a += ca; r += red(c); g += green(c); b += blue(c); n++
        }
        if (n == 0L) return null
        // cells only partly covered by the picture fade out accordingly
        val coverage = n.toDouble() / ((x1 - x0) * (y1 - y0))
        return argb((a / n * coverage).toInt(), (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
