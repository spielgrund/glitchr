package com.spielgrund.glitchr.export

import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException

/**
 * FFmpeg, if it is installed: its x264 encoder makes MP4s with exactly the colors of the picture
 * (JCodec's own encoder drifts towards red and magenta the more it compresses) and much smaller
 * files. GlitchR hands it the finished frames as raw RGB through a pipe; FFmpeg converts them to
 * Rec. 709 in the TV range (or the full range) and tags the video so.
 */
object Ffmpeg {
    /** The ffmpeg program: on the PATH, or in a usual place; null if there is none. */
    val path: String? by lazy { find() }

    private fun find(): String? {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val name = if (windows) "ffmpeg.exe" else "ffmpeg"
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() } +
            if (windows) listOf("C:\\ffmpeg\\bin", "C:\\Program Files\\ffmpeg\\bin", System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links" } ?: "")
            else listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin")
        val found = dirs.map { File(it.trim('"'), name) }.firstOrNull { it.isFile } ?: return null
        // it must be able to make H.264
        return try {
            val p = ProcessBuilder(found.path, "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if ("libx264" in text) found.path else null
        } catch (e: IOException) {
            null
        }
    }

    /** The color settings for the video: Rec. 709, TV or full range, converted with care. */
    private fun colorArgs(fullRange: Boolean): List<String> {
        val range = if (fullRange) "pc" else "tv"
        return listOf(
            "-vf", "scale=out_color_matrix=bt709:out_range=${if (fullRange) "full" else "limited"}:flags=accurate_rnd+full_chroma_int+bicubic",
            "-pix_fmt", "yuv420p",
            "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", range,
        )
    }

    /** A running encode: frames go in as they come, [finish] closes the file. */
    class Encoder(out: File, width: Int, height: Int, fps: Int, fullRange: Boolean, crf: Int = 17) {
        private val process: Process
        private val input: java.io.OutputStream
        private val errors = StringBuilder()
        private val reader: Thread
        private val bytes = ByteArray(width * height * 3)

        init {
            val exe = path ?: error("FFmpeg not found")
            val command = listOf(
                exe, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${width}x$height", "-r", fps.toString(), "-i", "-",
                "-c:v", "libx264", "-preset", "medium", "-crf", crf.toString(),
            ) + colorArgs(fullRange) + listOf("-movflags", "+faststart", out.path)
            process = ProcessBuilder(command).start()
            input = process.outputStream.buffered(1 shl 20)
            // read what it says, so it never blocks on a full pipe
            reader = Thread { process.errorStream.bufferedReader().forEachLine { synchronized(errors) { errors.appendLine(it) } } }.apply { isDaemon = true; start() }
        }

        fun frame(image: BufferedImage) {
            val w = image.width
            val h = image.height
            val rgb = image.getRGB(0, 0, w, h, null, 0, w)
            var k = 0
            for (c in rgb) {
                bytes[k++] = (c shr 16).toByte(); bytes[k++] = (c shr 8).toByte(); bytes[k++] = c.toByte()
            }
            try {
                input.write(bytes)
            } catch (e: IOException) {
                throw IOException("FFmpeg stopped: " + synchronized(errors) { errors.toString().trim() }, e)
            }
        }

        /** Ends the video; throws if FFmpeg failed. */
        fun finish() {
            try { input.close() } catch (_: IOException) {}
            val code = process.waitFor()
            reader.join(2000)
            if (code != 0) throw IOException("FFmpeg failed: " + synchronized(errors) { errors.toString().trim() })
        }

        /** Stops without a finished file. */
        fun cancel() {
            try { input.close() } catch (_: IOException) {}
            process.destroy()
        }
    }

    /**
     * [image] through the encoder and back, as a player shows it: encoded as a one-frame MP4 the way
     * the export does it, then decoded to RGB honoring the video's tags. Null if FFmpeg can't.
     */
    fun roundTrip(image: BufferedImage, fullRange: Boolean): BufferedImage? {
        val exe = path ?: return null
        val tmp = File.createTempFile("glitchr-preview", ".mp4")
        try {
            val enc = Encoder(tmp, image.width, image.height, 25, fullRange)
            enc.frame(image)
            enc.finish()
            val w = image.width
            val h = image.height
            val p = ProcessBuilder(exe, "-hide_banner", "-loglevel", "error", "-i", tmp.path, "-frames:v", "1",
                "-vf", "scale=flags=accurate_rnd+full_chroma_int+bicubic", "-f", "rawvideo", "-pix_fmt", "rgb24", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val data = p.inputStream.readBytes()
            p.waitFor()
            if (data.size < w * h * 3) return null
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val rgb = IntArray(w * h) { i -> ((data[i * 3].toInt() and 0xFF) shl 16) or ((data[i * 3 + 1].toInt() and 0xFF) shl 8) or (data[i * 3 + 2].toInt() and 0xFF) }
            out.setRGB(0, 0, w, h, rgb, 0, w)
            return out
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
    }
}
