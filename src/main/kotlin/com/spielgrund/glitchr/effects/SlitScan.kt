package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Edge
import com.spielgrund.glitchr.image.Noise
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Slit scan: the picture is cut into lines of [Param.Slider] "size" pixels, and every line
 * slides along itself. How far grows across the picture – evenly, exponentially or at random.
 * At 0° the lines are the picture's rows and they slide sideways.
 */
object SlitScan : Effect("slitscan", "Slitscan", "Schneidet das Bild in Zeilen und verschiebt sie zunehmend gegeneinander") {
    private val curves = listOf("Linear", "Exponentiell", "Zufall")

    override val params = listOf(
        Param.Slider("angle", "Winkel", 0, 359, 0, "°", "Richtung der Zeilen; 0° = waagrechte Bildzeilen"),
        Param.Slider("size", "Zeilengrösse", 1, 500, 4, " px", "Breite einer Zeile quer zu ihrer Richtung"),
        Param.Slider("offset", "Versatz", -3000, 3000, 300, " px", "Verschiebung der letzten Zeile; negativ in die Gegenrichtung"),
        Param.Slider("offsetY", "Versatz Y", -3000, 3000, 0, " px", "Verschiebung quer zu den Zeilen: das Bild läuft durch die Zeilen durch (bei 0° auf der Y-Achse); negativ in die Gegenrichtung"),
        Param.Choice("curve", "Verlauf", curves, tip = "Wie der Versatz von der ersten zur letzten Zeile zunimmt"),
        Param.Slider("exponent", "Kurve", 110, 800, 300, " %", "Für „Exponentiell“: je höher, desto länger bleiben die ersten Zeilen ruhig"),
        Param.Choice("edge", "Rand", Edge.labels),
        Param.Slider("shiftX", "Bild verschieben X", -3000, 3000, 0, " px", "Verschiebt das Bild vor dem Slitscan; am Rand wiederholt es sich"),
        Param.Slider("shiftY", "Bild verschieben Y", -3000, 3000, 0, " px", "Verschiebt das Bild vor dem Slitscan; am Rand wiederholt es sich"),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val angle = Math.toRadians(v["angle"].toDouble())
        val cos = cos(angle)
        val sin = sin(angle)
        val size = v["size"].toDouble()
        val offset = v["offset"].toDouble()
        val offsetY = v["offsetY"].toDouble()
        val curve = v["curve"]
        val exponent = v["exponent"] / 100.0
        val edge = Edge.entries[v["edge"]]
        val shiftX = v["shiftX"]
        val shiftY = v["shiftY"]
        val noise = Noise(seed)

        // the lines run along (cos, sin); they are stacked across the picture
        val across = abs(sin) * w + abs(cos) * h
        val lines = max(1, ceil(across / size).toInt())
        // how far along the curve each line is, 0..1
        val amounts = DoubleArray(lines) { k ->
            val t = if (lines == 1) 1.0 else k.toDouble() / (lines - 1)
            when (curve) {
                1 -> t.pow(exponent)
                2 -> (noise.white(k, 3) + 1) / 2
                else -> t
            }
        }

        val out = Pixels(w, h)
        val cx = w / 2.0
        val cy = h / 2.0
        parallelRows(h) { y ->
            val py = y + 0.5 - cy
            for (x in 0 until w) {
                val px = x + 0.5 - cx
                val b = -px * sin + py * cos + across / 2
                val k = floor(b / size).toInt().coerceIn(0, lines - 1)
                // along the line by the offset, across it (-sin, cos) by the Y offset
                val s = amounts[k] * offset
                val q = amounts[k] * offsetY
                // resolved on the shifted picture, which repeats the source
                val sx = Math.floorMod(edge.resolve(floor(x + 0.5 - s * cos + q * sin).toInt(), w) - shiftX, w)
                val sy = Math.floorMod(edge.resolve(floor(y + 0.5 - s * sin - q * cos).toInt(), h) - shiftY, h)
                out.data[y * w + x] = src.data[sy * w + sx]
            }
        }
        return out
    }
}
