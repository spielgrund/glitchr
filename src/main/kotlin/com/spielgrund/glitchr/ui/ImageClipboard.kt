package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.image.Pixels
import java.awt.Color
import java.awt.Image
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Pictures on the system clipboard with their transparency. Java's own image format
 * reaches other programs as a bitmap without alpha, so the picture also goes on the
 * clipboard as PNG – the native "PNG" format that browsers, Affinity, Photoshop and GIMP
 * exchange with transparency. Programs without PNG support still get the picture, laid
 * on white.
 */
object ImageClipboard {
    /** PNG bytes, mapped to the clipboard format named "PNG". */
    val pngFlavor = DataFlavor("image/png", "PNG").also { flavor ->
        (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)?.let { map ->
            map.addUnencodedNativeForFlavor(flavor, "PNG")
            map.addFlavorForUnencodedNative("PNG", flavor)
        }
    }

    /** [pixels] as clipboard contents: PNG with alpha first, then the flat image. */
    fun transferable(pixels: Pixels): Transferable {
        val png = ByteArrayOutputStream().also { ImageIO.write(pixels.toImage(), "png", it) }.toByteArray()
        val flat by lazy { onWhite(pixels) }
        val flavors = arrayOf(pngFlavor, DataFlavor.imageFlavor)
        return object : Transferable {
            override fun getTransferDataFlavors() = flavors.copyOf()
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavors.any { it.equals(flavor) }
            override fun getTransferData(flavor: DataFlavor): Any = when {
                flavor.equals(pngFlavor) -> ByteArrayInputStream(png)
                flavor.equals(DataFlavor.imageFlavor) -> flat
                else -> throw UnsupportedFlavorException(flavor)
            }
        }
    }

    fun copy(pixels: Pixels, clipboard: Clipboard) = clipboard.setContents(transferable(pixels), null)

    /** A picture from the clipboard, with transparency when it came as PNG; null if there is none. */
    fun read(clipboard: Clipboard): BufferedImage? {
        if (clipboard.isDataFlavorAvailable(pngFlavor)) {
            val data = clipboard.getData(pngFlavor)
            val image = when (data) {
                is InputStream -> data.use { ImageIO.read(it) }
                is ByteArray -> ImageIO.read(ByteArrayInputStream(data))
                else -> null
            }
            if (image != null) return toArgb(image)
        }
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            return toArgb(clipboard.getData(DataFlavor.imageFlavor) as Image)
        }
        return null
    }

    private fun toArgb(image: Image): BufferedImage {
        val img = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return img
    }

    /** The picture over white, for formats and programs without transparency. */
    fun onWhite(p: Pixels): BufferedImage {
        val img = BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, p.width, p.height)
            drawImage(p.toImage(), 0, 0, null)
            dispose()
        }
        return img
    }
}
