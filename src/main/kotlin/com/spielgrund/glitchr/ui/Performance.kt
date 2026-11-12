package com.spielgrund.glitchr.ui

import com.spielgrund.glitchr.image.Workload
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.util.prefs.Preferences
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * How much of the computer GlitchR may take: processor cores and priority for rendering (in
 * effect at once) and the most memory (from the next start – Java fixes it when it starts, so
 * GlitchR starts itself again with that limit).
 */
object Performance {
    private val prefs = Preferences.userNodeForPackage(MainFrame::class.java)
    private const val CORES = "perfCores"
    private const val LOW_PRIORITY = "perfLowPriority"
    private const val MEMORY_GB = "perfMemoryGb"

    /** Set when GlitchR was started again by itself with the memory limit. */
    private const val RELAUNCHED = "glitchr.relaunched"

    /** The most memory wanted, in GB; 0 = as Java decides by itself. */
    val memoryGb: Int get() = prefs.getInt(MEMORY_GB, 0)

    /** The installed memory of the computer, in GB, if it can be told. */
    private val physicalGb: Int? = try {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        os?.let { (it.totalMemorySize / (1L shl 30)).toInt() }
    } catch (e: Throwable) {
        null
    }

    /** Applies the stored processor settings. */
    fun applyStored() {
        Workload.configure(prefs.getInt(CORES, Workload.defaultCores), prefs.getBoolean(LOW_PRIORITY, true))
    }

    /**
     * At start: if a memory limit is set and this Java runs with another one, starts GlitchR
     * again with that limit and returns true (this one should then end). Never twice in a row, and
     * not when the start command can't be found.
     */
    fun relaunchForMemory(args: Array<String>): Boolean {
        val gb = memoryGb
        if (gb <= 0 || System.getProperty(RELAUNCHED) != null) return false
        val current = Runtime.getRuntime().maxMemory() / (1L shl 20)
        // already about right (Java reports a little less than the -Xmx it was given)
        if (kotlin.math.abs(current - gb * 1024L) < 200) return false
        return try {
            val java = ProcessHandle.current().info().command().orElse(null) ?: return false
            val classPath = System.getProperty("java.class.path") ?: return false
            val command = mutableListOf(java, "-Xmx${gb}g", "-D$RELAUNCHED=true", "-cp", classPath, "com.spielgrund.glitchr.MainKt") + args
            ProcessBuilder(command).inheritIO().directory(File(System.getProperty("user.dir"))).start()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The performance settings as a dialog. */
    fun showDialog(parent: java.awt.Component) {
        val cores = JSpinner(SpinnerNumberModel(Workload.cores, 1, Workload.available, 1))
        val low = JCheckBox("Render at low priority", Workload.lowPriority).apply {
            toolTipText = "Other programs – music, video, the browser – come first, so they don't stutter while GlitchR renders"
        }
        val maxGb = maxOf(2, physicalGb ?: 64)
        val memory = JSpinner(SpinnerNumberModel(memoryGb.coerceIn(0, maxGb), 0, maxGb, 1))
        val nowGb = Runtime.getRuntime().maxMemory() / (1L shl 20) / 1024.0
        val panel = JPanel(GridBagLayout())
        var row = 0
        fun add(label: String, field: JComponent, hint: String) {
            panel.add(JLabel(label), GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(4, 0, 0, 8) })
            panel.add(field, GridBagConstraints().apply { gridx = 1; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(4, 0, 0, 0) })
            row++
            panel.add(JLabel("<html><div style='width:340px;color:#9aa0a6'>$hint</div></html>"), GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = Insets(0, 0, 8, 0) })
            row++
        }
        add("Processor cores for rendering", cores, "Of ${Workload.available}. Fewer keep the computer cooler and quieter and leave room for other programs; rendering takes longer. In effect at once.")
        panel.add(low, GridBagConstraints().apply { gridx = 0; gridy = row++; gridwidth = 2; anchor = GridBagConstraints.WEST })
        panel.add(JLabel(" "), GridBagConstraints().apply { gridx = 0; gridy = row++ })
        add(
            "Most memory (GB)", memory,
            "0 = as Java decides (now ${String.format(java.util.Locale.ROOT, "%.1f", nowGb)} GB)" +
                (physicalGb?.let { " · this computer has $it GB" } ?: "") +
                ". Takes effect after GlitchR starts again.",
        )
        val ok = JOptionPane.showConfirmDialog(parent, panel, "Performance", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
        if (!ok) return
        prefs.putInt(CORES, cores.value as Int)
        prefs.putBoolean(LOW_PRIORITY, low.isSelected)
        Workload.configure(cores.value as Int, low.isSelected)
        val gb = memory.value as Int
        if (gb != memoryGb) {
            prefs.putInt(MEMORY_GB, gb)
            JOptionPane.showMessageDialog(parent, "The memory limit takes effect the next time GlitchR starts.", "Performance", JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
