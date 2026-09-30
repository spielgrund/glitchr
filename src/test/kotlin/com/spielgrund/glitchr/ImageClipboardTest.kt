package com.spielgrund.glitchr

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.argb
import com.spielgrund.glitchr.ui.ImageClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageClipboardTest {
    private val pixels = Pixels(4, 2, intArrayOf(argb(255, 255, 0, 0), 0, argb(128, 0, 0, 255), 0, 0, 0, 0, argb(255, 0, 255, 0)))

    @Test
    fun `the copy carries a PNG with transparency first, the flat picture second`() {
        val t = ImageClipboard.transferable(pixels)
        assertEquals(ImageClipboard.pngFlavor, t.transferDataFlavors.first())
        val png = ImageIO.read(t.getTransferData(ImageClipboard.pngFlavor) as InputStream)
        assertEquals(argb(255, 255, 0, 0), png.getRGB(0, 0))
        assertEquals(0, png.getRGB(1, 0) ushr 24)
        assertEquals(128, png.getRGB(2, 0) ushr 24)
        // for programs without PNG: on white
        val flat = t.getTransferData(DataFlavor.imageFlavor) as BufferedImage
        assertEquals(0xFFFFFF, flat.getRGB(1, 0) and 0xFFFFFF)
    }

    @Test
    fun `the PNG flavor is the native clipboard format named PNG`() {
        val natives = (SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap).getNativesForFlavor(ImageClipboard.pngFlavor)
        assertTrue("PNG" in natives, "$natives")
    }
}
