package com.spielgrund.glitchr

import com.formdev.flatlaf.FlatDarkLaf
import com.spielgrund.glitchr.ui.MainFrame
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Starts GlitchR; the first argument (image or project) is opened, further images become image layers. */
fun main(args: Array<String>) {
    // English throughout: dialog buttons, file chooser and decimal points, whatever the system language
    Locale.setDefault(Locale.ENGLISH)
    JComponent.setDefaultLocale(Locale.ENGLISH)
    ImageIO.scanForPlugins()
    ImageIO.setUseCache(false)
    FlatDarkLaf.setup()
    acceptDecimalComma()
    SwingUtilities.invokeLater {
        val frame = MainFrame()
        frame.isVisible = true
        args.forEach { frame.open(File(it)) }
    }
}

/**
 * Number fields take a decimal comma too (German keyboards): a comma typed into a field
 * that holds a number with decimals becomes the decimal point.
 */
private fun acceptDecimalComma() {
    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
        if (e.id != java.awt.event.KeyEvent.KEY_TYPED || e.keyChar != ',') return@addKeyEventDispatcher false
        val field = e.component as? javax.swing.JFormattedTextField ?: return@addKeyEventDispatcher false
        val format = (field.formatter as? javax.swing.text.InternationalFormatter)?.format as? java.text.DecimalFormat
            ?: return@addKeyEventDispatcher false
        // whole-number fields keep the comma as it is (it does nothing there)
        if (format.maximumFractionDigits == 0 || field.value is Int || field.value is Long) return@addKeyEventDispatcher false
        field.replaceSelection(format.decimalFormatSymbols.decimalSeparator.toString())
        true
    }
}
