package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows

/** Moves the picture, by fractions of a pixel too, repeating it at the edges, and can mirror it. */
object Offset : Effect("offset", "Shift", "Shifts the picture in X and Y (it repeats at the edge) and mirrors it") {
    override val params = listOf(
        Param.Slider("shiftX", "Shift X", -300000, 300000, 10000, " px", "The picture repeats at the edge", decimals = 2, transform = true),
        Param.Slider("shiftY", "Shift Y", -300000, 300000, 0, " px", "The picture repeats at the edge", decimals = 2, transform = true),
        Param.Toggle("flipX", "Mirror X", false, "Swap left and right"),
        Param.Toggle("flipY", "Mirror Y", false, "Swap top and bottom"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        val shiftX = (v["shiftX"] / 100.0)
        val shiftY = (v["shiftY"] / 100.0)
        val flipX = v.bool("flipX")
        val flipY = v.bool("flipY")
        val out = Pixels(w, h)
        // mirrored first, then moved – by fractions of a pixel too, blending the neighbours
        fun at(x: Int, y: Int): Int {
            val mx = Math.floorMod(x, w)
            val my = Math.floorMod(y, h)
            return src.data[(if (flipY) h - 1 - my else my) * w + (if (flipX) w - 1 - mx else mx)]
        }
        val fx = Math.floor(shiftX)
        val fy = Math.floor(shiftY)
        val tx = shiftX - fx
        val ty = shiftY - fy
        val ix = fx.toInt()
        val iy = fy.toInt()
        parallelRows(h) { y ->
            for (x in 0 until w) {
                out.data[y * w + x] = if (tx == 0.0 && ty == 0.0) at(x - ix, y - iy) else {
                    // the pixel lies between four source pixels
                    val a = com.spielgrund.glitchr.image.lerpArgb(at(x - ix, y - iy), at(x - ix - 1, y - iy), tx.toFloat())
                    val b = com.spielgrund.glitchr.image.lerpArgb(at(x - ix, y - iy - 1), at(x - ix - 1, y - iy - 1), tx.toFloat())
                    com.spielgrund.glitchr.image.lerpArgb(a, b, ty.toFloat())
                }
            }
        }
        return out
    }
}
