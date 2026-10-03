package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows

/** Moves the picture by whole pixels, repeating it at the edges, and can mirror it. */
object Offset : Effect("offset", "Shift", "Shifts the picture in X and Y (it repeats at the edge) and mirrors it") {
    override val params = listOf(
        Param.Slider("shiftX", "Shift X", -3000, 3000, 100, " px", "The picture repeats at the edge"),
        Param.Slider("shiftY", "Shift Y", -3000, 3000, 0, " px", "The picture repeats at the edge"),
        Param.Toggle("flipX", "Mirror X", false, "Swap left and right"),
        Param.Toggle("flipY", "Mirror Y", false, "Swap top and bottom"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val shiftX = v["shiftX"]
        val shiftY = v["shiftY"]
        val flipX = v.bool("flipX")
        val flipY = v.bool("flipY")
        val out = Pixels(w, h)
        // mirrored first, then moved
        parallelRows(h) { y ->
            val my = Math.floorMod(y - shiftY, h)
            val sy = if (flipY) h - 1 - my else my
            for (x in 0 until w) {
                val mx = Math.floorMod(x - shiftX, w)
                val sx = if (flipX) w - 1 - mx else mx
                out.data[y * w + x] = src.data[sy * w + sx]
            }
        }
        return out
    }
}
