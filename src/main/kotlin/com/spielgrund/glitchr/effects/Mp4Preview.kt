package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.export.AnimationExport
import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import org.jcodec.codecs.h264.H264Decoder
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MP4 preview: the picture as it will look in the exported MP4 – actually sent through the same
 * H.264 encoder the export uses (Rec. 709, TV range, 4:2:0) and decoded again the way a player
 * does, so its colors, the halved color resolution and the compression artifacts all show.
 * Transparent parts lie on black or white, as in the export. Best as an adjustment layer on top;
 * switch it off before exporting (or the video is compressed twice).
 */
object Mp4Preview : Effect("mp4preview", "MP4 preview", "Shows the picture as it will look in the exported MP4 – sent through the real H.264 encoder and decoded like a player: colors, 4:2:0 color resolution, compression") {
    private val shows = listOf("As exported", "Colors only (no compression)", "Difference")

    override val params = listOf(
        Param.Choice(
            "show", "Show", shows, 0,
            "As exported: encoded and decoded with the real H.264 encoder · Colors only: just the color conversion and the halved color resolution, no compression · Difference: what the export changes, brightened",
        ),
        Param.Slider("boost", "Difference boost", 1, 50, 8, "×", "How much the differences are brightened").shownWhen("show", 2),
        Param.Choice("background", "Background", listOf("Black", "White"), 0, "What transparent parts lie on – as set in the export"),
        Param.Choice("encoder", "Encoder", listOf("FFmpeg x264", "JCodec"), 0, "As set in the export: FFmpeg if it is installed (otherwise JCodec is used here too), or JCodec"),
        Param.Choice("levels", "Levels", listOf("TV range (standard)", "Full range"), 0, "As set in the export – a player that reads the video's tag shows either right"),
    )

    override val random = false

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val w = src.width
        val h = src.height
        // the export's frame: on the background, sides rounded up to even (the last row and column repeated)
        val ew = w + (w and 1)
        val eh = h + (h and 1)
        val bg = if (v["background"] == 1) 0xFFFFFF else 0x000000
        val image = BufferedImage(ew, eh, BufferedImage.TYPE_INT_RGB)
        val rgb = IntArray(ew * eh)
        for (y in 0 until eh) for (x in 0 until ew) {
            val c = src.data[min(y, h - 1) * w + min(x, w - 1)]
            val a = (c ushr 24) / 255.0
            fun ch(s: Int) = ((c shr s and 0xFF) * a + (bg shr s and 0xFF) * (1 - a)).roundToInt()
            rgb[y * ew + x] = (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        image.setRGB(0, 0, ew, eh, rgb, 0, ew)
        val full = v["levels"] == 1
        val picture = AnimationExport.toRec709(image, full)

        // the planes a player gets: luma full size, color at half size each way (stored − 128)
        // FFmpeg: the frame through its encoder and back, as the export makes it
        if (v["show"] != 1 && v["encoder"] == 0) {
            val back = com.spielgrund.glitchr.export.Ffmpeg.roundTrip(image, full)
            if (back != null) {
                val got = back.getRGB(0, 0, ew, eh, null, 0, ew)
                val boost = v["boost"].toDouble()
                val result = Pixels(w, h)
                for (y in 0 until h) for (x in 0 until w) {
                    val c = got[y * ew + x]
                    result.data[y * w + x] = if (v["show"] == 2) {
                        val o = rgb[y * ew + x]
                        fun d(s: Int) = (abs((c shr s and 0xFF) - (o shr s and 0xFF)) * boost).roundToInt().coerceAtMost(255)
                        (0xFF shl 24) or (d(16) shl 16) or (d(8) shl 8) or d(0)
                    } else c or (0xFF shl 24)
                }
                return result
            }
        }
        val luma: ByteArray
        val cb: ByteArray
        val cr: ByteArray
        val stride: Int
        if (v["show"] == 1) {
            luma = picture.getPlaneData(0); cb = picture.getPlaneData(1); cr = picture.getPlaneData(2); stride = ew
        } else {
            val encoder = AnimationExport.TaggedH264Encoder(full)
            val out = ByteBuffer.allocate(encoder.estimateBufferSize(picture))
            val encoded = encoder.encodeFrame(picture, out)
            // the decoder works on whole 16 × 16 blocks
            val cw = (ew + 15) / 16 * 16
            val chh = (eh + 15) / 16 * 16
            val buffer = arrayOf(ByteArray(cw * chh), ByteArray(cw * chh / 4), ByteArray(cw * chh / 4))
            val frame = H264Decoder().decodeFrame(encoded.data.duplicate(), buffer)
            luma = frame.getPlaneData(0); cb = frame.getPlaneData(1); cr = frame.getPlaneData(2); stride = frame.width
        }
        val cStride = stride / 2

        val boost = v["boost"].toDouble()
        val difference = v["show"] == 2
        val out = Pixels(w, h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                // color between the four nearest half-size samples (as players scale it up)
                val fx = ((x + 0.5) / 2 - 0.5).coerceIn(0.0, ew / 2 - 1.0)
                val fy = ((y + 0.5) / 2 - 0.5).coerceIn(0.0, eh / 2 - 1.0)
                val x0 = fx.toInt(); val y0 = fy.toInt()
                val x1 = min(ew / 2 - 1, x0 + 1); val y1 = min(eh / 2 - 1, y0 + 1)
                val tx = fx - x0; val ty = fy - y0
                fun at(p: ByteArray): Double {
                    fun s(xx: Int, yy: Int) = p[yy * cStride + xx] + 128.0
                    return (s(x0, y0) * (1 - tx) + s(x1, y0) * tx) * (1 - ty) + (s(x0, y1) * (1 - tx) + s(x1, y1) * tx) * ty
                }
                val yy = if (full) (luma[y * stride + x] + 128) / 255.0 else (luma[y * stride + x] + 128 - 16) / 219.0
                val pb = (at(cb) - 128) / (if (full) 255.0 else 224.0)
                val pr = (at(cr) - 128) / (if (full) 255.0 else 224.0)
                val r = yy + 1.5748 * pr
                val b = yy + 1.8556 * pb
                val g = (yy - 0.2126 * r - 0.0722 * b) / 0.7152
                var or = (r * 255).roundToInt().coerceIn(0, 255)
                var og = (g * 255).roundToInt().coerceIn(0, 255)
                var ob = (b * 255).roundToInt().coerceIn(0, 255)
                if (difference) {
                    val c = rgb[y * ew + x]
                    or = (abs(or - (c shr 16 and 0xFF)) * boost).roundToInt().coerceAtMost(255)
                    og = (abs(og - (c shr 8 and 0xFF)) * boost).roundToInt().coerceAtMost(255)
                    ob = (abs(ob - (c and 0xFF)) * boost).roundToInt().coerceAtMost(255)
                }
                out.data[y * w + x] = (0xFF shl 24) or (or shl 16) or (og shl 8) or ob
            }
        }
        return out
    }
}
