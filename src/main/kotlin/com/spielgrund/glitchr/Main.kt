package com.spielgrund.glitchr

import com.formdev.flatlaf.FlatDarkLaf
import com.spielgrund.glitchr.ui.MainFrame
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Starts GlitchR; the first argument (image or project) is opened, further images become image layers. */
fun main(args: Array<String>) {
    ImageIO.scanForPlugins()
    ImageIO.setUseCache(false)
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val frame = MainFrame()
        frame.isVisible = true
        args.forEach { frame.open(File(it)) }
    }
}
