package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.lerpArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Blob echo: the picture is simplified into areas (blobs, as in "Partikel"), and every
 * area – or only a slice of it, a wedge from its middle – is copied again and again in one
 * direction across the canvas, fading if wanted, like a trail it leaves. The areas are copied
 * from the biggest to the smallest, so the small ones end up on top. Very big areas (usually
 * the background) can be left out, so the trails run over them.
 */
object BlobEcho : Effect("blobecho", "Blob-Echo", "Alle Bildflächen werden in eine Richtung immer wieder über das Bild kopiert") {
    override val params = listOf(
        Param.Slider("areas", "Flächen", 0, 100, 50, " %", "Wie grob das Bild in Flächen zerlegt wird"),
        Param.Slider("maxArea", "Grösste Fläche", 1, 100, 15, " %", "Flächen, die mehr als diesen Anteil des Bildes bedecken (meist der Hintergrund), werden nicht kopiert; 100 % = alle"),
        Param.Slider("direction", "Richtung", 0, 359, 0, "°", "In welche Richtung die Flächen kopiert werden; 0° = nach rechts"),
        Param.Slider("slice", "Ausschnitt", 1, 360, 360, "°", "Von jeder Fläche wird nur ein Tortenstück dieses Winkels kopiert, mit der Spitze in ihrer Mitte; 360° = die ganze Fläche"),
        Param.Slider("sliceAngle", "Ausschnitt Richtung", 0, 359, 270, "°", "Wohin das Tortenstück offen ist; 270° = nach oben"),
        Param.Toggle("toEdge", "Bis zum Rand", true, "Die Kopien laufen über die ganze Leinwand; aus: nur so viele wie bei „Anzahl“"),
        Param.Slider("copies", "Anzahl", 1, 200, 8, "", "Wie oft kopiert wird, wenn nicht bis zum Rand"),
        Param.Slider("spacing", "Abstand", 1, 1000, 40, " px", "Abstand von Kopie zu Kopie"),
        Param.Slider("fade", "Verblassen", 0, 100, 40, " %", "Wie stark die Kopien mit der Entfernung durchsichtig werden"),
        Param.Choice("order", "Ebene", listOf("Kopien hinter der eigenen Fläche", "Kopien über allem", "Kopien hinter allen Flächen"),
            tip = "Hinter der eigenen Fläche: eine Kopie verdeckt nie die Fläche, von der sie stammt · über allem: die Kopien liegen auf allem · " +
                "hinter allen Flächen: die Kopien laufen nur über die Flächen, die selbst nicht kopiert werden (meist der Hintergrund)",
        ),
    )

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val blobs = Particles.blobify(src, v["areas"] / 100.0, seed)
        val label = blobs.label
        val maxArea = if (v["maxArea"] >= 100) Long.MAX_VALUE else w.toLong() * h * v["maxArea"] / 100
        val count = blobs.area.size
        val copied = BooleanArray(count) { blobs.area[it] <= maxArea }

        // of every area only the slice (a wedge from its middle) is copied
        val slice = Math.toRadians(v["slice"].toDouble())
        val sliceAxis = Math.toRadians(v["sliceAngle"].toDouble())
        fun inSlice(i: Int, b: Int): Boolean {
            if (v["slice"] >= 360) return true
            val c = blobs.center[b]
            val ox = (i % w - c % w).toDouble()
            val oy = (i / w - c / w).toDouble()
            if (ox == 0.0 && oy == 0.0) return true
            var diff = atan2(oy, ox) - sliceAxis
            diff -= 2 * PI * floor(diff / (2 * PI) + 0.5)
            return abs(diff) <= slice / 2
        }
        // the pixels to copy, grouped by area
        val sizes = IntArray(count)
        val take = BooleanArray(w * h) { i -> label[i] >= 0 && copied[label[i]] && inSlice(i, label[i]) }
        for (i in 0 until w * h) if (take[i]) sizes[label[i]]++
        val groups = Array(count) { IntArray(sizes[it]) }
        val filled = IntArray(count)
        for (i in 0 until w * h) if (take[i]) { val b = label[i]; groups[b][filled[b]++] = i }
        // the biggest area first, the smallest last: small ones end up on top
        val order = (0 until count).filter { sizes[it] > 0 }.sortedByDescending { blobs.area[it] }

        val direction = Math.toRadians(v["direction"].toDouble())
        val dx = cos(direction)
        val dy = sin(direction)
        val spacing = v["spacing"].toDouble()
        // across the whole canvas: as many copies as fit along its diagonal
        val copies = if (v.bool("toEdge")) ceil(hypot(w.toDouble(), h.toDouble()) / spacing).toInt() else v["copies"]
        val fade = v["fade"] / 100.0
        val layer = v["order"]
        val out = src.copy()
        // area by area from the biggest; within an area the farthest copy first, so nearer ones lie over it
        for (b in order) for (k in copies downTo 1) {
            val ox = (dx * spacing * k).roundToInt()
            val oy = (dy * spacing * k).roundToInt()
            val alpha = (1 - fade * k / copies).toFloat().coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            for (i in groups[b]) {
                val x = i % w + ox
                val y = i / w + oy
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val t = y * w + x
                // behind: a copy never covers the area it comes from, so every area keeps its shape
                // while the trails of the others run over it
                if (layer == 0 && label[t] == label[i]) continue
                if (layer == 2 && label[t] >= 0 && copied[label[t]]) continue
                val c = src.data[i]
                out.data[t] = if (alpha >= 1f) c else lerpArgb(out.data[t], c, alpha * (c ushr 24) / 255f)
            }
        }
        return out
    }
}
