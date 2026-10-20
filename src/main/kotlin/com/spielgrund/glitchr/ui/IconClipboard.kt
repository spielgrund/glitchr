package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.effects.CustomIcon
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.io.File
import java.io.InputStream
import java.io.Reader
import javax.imageio.ImageIO

/**
 * Own icons from the clipboard or a file: SVG code (as text or in the "image/svg+xml" format
 * of Inkscape, Illustrator and browsers), copied .svg and picture files, or a picture.
 * Returns the icon as text for a [com.spielgrund.glitchr.effects.Param.Icon], or null.
 */
object IconClipboard {
    private val svgFlavor = DataFlavor("image/svg+xml;class=java.io.InputStream", "SVG").also { flavor ->
        (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)?.let { map ->
            map.addUnencodedNativeForFlavor(flavor, "image/svg+xml")
            map.addFlavorForUnencodedNative("image/svg+xml", flavor)
        }
    }

    fun read(clipboard: Clipboard): String? = try {
        when {
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ->
                (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().firstNotNullOfOrNull { fromFile(it) }
            clipboard.isDataFlavorAvailable(svgFlavor) -> CustomIcon.fromSvg(text(clipboard.getData(svgFlavor)))
            else -> null
        } ?: textSvg(clipboard) ?: ImageClipboard.read(clipboard)?.let { CustomIcon.fromImage(it) }
    } catch (e: Exception) {
        null
    }

    /** SVG code copied as plain text. */
    private fun textSvg(clipboard: Clipboard): String? {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        val s = clipboard.getData(DataFlavor.stringFlavor) as? String ?: return null
        return if (s.contains("<svg", ignoreCase = true)) CustomIcon.fromSvg(s.substring(s.indexOf("<svg", ignoreCase = true).let { i -> s.lastIndexOf("<?xml", i).takeIf { it >= 0 } ?: i })) else null
    }

    fun fromFile(file: File): String? = try {
        if (file.extension.equals("svg", ignoreCase = true)) CustomIcon.fromSvg(file.readText())
        else ImageIO.read(file)?.let { CustomIcon.fromImage(it) }
    } catch (e: Exception) {
        null
    }

    private fun text(data: Any): String = when (data) {
        is InputStream -> data.use { it.readBytes().toString(Charsets.UTF_8) }
        is Reader -> data.use { it.readText() }
        is ByteArray -> data.toString(Charsets.UTF_8)
        else -> data.toString()
    }
}
