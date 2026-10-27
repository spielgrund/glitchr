package com.spielgrund.glitchr.ui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.text.Normalizer
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Something the quick search offers: a filter of [kind] ("generator" or "effect") with its [id], [name], the [group] it sits in, and its keywords. */
class SearchEntry<T>(val item: T, val kind: String, val id: String, val name: String, val group: String, val keywords: List<String> = emptyList())

/** A found entry and, if it was found by one of its keywords rather than its name, that keyword. */
class SearchHit<T>(val entry: SearchEntry<T>, val keyword: String?)

/**
 * The quick search's keywords per filter. They live in a plain text file in the user's folder
 * ([file]), so they can be edited by hand: made from the built-in list the first time, and
 * filters it doesn't know yet (added to GlitchR later) are appended to it.
 */
object QuickSearchKeywords {
    val file = File(System.getProperty("user.home"), ".glitchr/quicksearch-keywords.txt")

    /** The built-in list. */
    val builtIn: String by lazy {
        QuickSearchKeywords::class.java.getResourceAsStream("/quicksearch-keywords.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    /** Lines "kind id: keyword, keyword, …" as "kind id" → keywords; comments (from #) and blank lines skipped. */
    fun parse(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            out[key] = line.substring(colon + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return out
    }

    /**
     * The keywords for [filters] ("kind id" with the filter's name), read from [file]. The file is
     * written from the built-in list if it doesn't exist; filters missing in it are appended – with
     * their built-in keywords, or none to fill in. If the file can't be read or written, the
     * built-in list is used.
     */
    fun load(filters: List<Pair<String, String>>, file: File = this.file): Map<String, List<String>> {
        val defaults = parse(builtIn)
        return try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(builtIn, Charsets.UTF_8)
            }
            val text = file.readText(Charsets.UTF_8)
            val own = parse(text)
            val missing = filters.filter { it.first !in own }
            if (missing.isNotEmpty()) {
                val added = buildString {
                    if (!text.endsWith("\n")) append('\n')
                    append("\n# added automatically\n")
                    for ((key, name) in missing) append("$key: ${(defaults[key] ?: emptyList()).joinToString(", ")}   # $name\n")
                }
                file.appendText(added, Charsets.UTF_8)
                parse(text + added)
            } else own
        } catch (e: Exception) {
            defaults
        }
    }
}

/**
 * Ranks [entries] for what was typed: the name first (all of it, its start, the start of one of
 * its words, anywhere in it), then the keywords the same way, then letters in order with gaps
 * (“pxst” finds Pixelstretch). Accents and umlauts don't matter (“moire” finds Moiré). Nothing
 * typed: everything, in its order.
 */
fun <T> rankSearch(query: String, entries: List<SearchEntry<T>>): List<SearchHit<T>> {
    val q = plain(query.trim())
    if (q.isEmpty()) return entries.map { SearchHit(it, null) }
    val scored = ArrayList<Triple<Int, Int, SearchHit<T>>>()
    for ((order, e) in entries.withIndex()) {
        var best = score(q, plain(e.name), 0)
        var by: String? = null
        for (k in e.keywords) {
            // a keyword counts a little less than the name
            val s = score(q, plain(k), 60)
            if (s > best) { best = s; by = k }
        }
        if (best > 0) scored += Triple(best, order, SearchHit(e, by))
    }
    return scored.sortedWith(compareByDescending<Triple<Int, Int, SearchHit<T>>> { it.first }.thenBy { it.second }).map { it.third }
}

/** How well [q] matches [text] (both plain), less [penalty]; 0: not at all. */
private fun score(q: String, text: String, penalty: Int): Int {
    if (text.isEmpty()) return 0
    val s = when {
        text == q -> 1000
        text.startsWith(q) -> 900
        text.split(' ', '-', '.', '(', '/').any { it.startsWith(q) } -> 800
        q.length >= 2 && text.contains(q) -> 600
        else -> {
            // letters in order, from the start of a word on: the fewer and shorter the gaps, the better
            if (q.length < 2) return 0
            val start = (text.indices).firstOrNull { (it == 0 || text[it - 1] in " -./(") && text[it] == q[0] } ?: return 0
            var at = start
            var gaps = 0
            for (c in q) {
                val found = text.indexOf(c, at)
                if (found < 0) return 0
                if (found > at && at > 0) gaps += found - at
                at = found + 1
            }
            (300 - gaps * 8).coerceAtLeast(60)
        }
    }
    return s - penalty
}

/** Lower case, without accents or umlaut dots, separators as spaces. */
private fun plain(s: String) =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace('ß', 's').replace('_', ' ')

/**
 * The quick search window: a field to type into and the matching filters below, best first.
 * Up and Down choose, Enter (or a double click) takes the chosen one, Escape or clicking
 * elsewhere closes it.
 */
class QuickSearchDialog<T>(owner: JFrame, title: String, private val entries: List<SearchEntry<T>>, private val pick: (T) -> Unit) :
    JDialog(owner, title, false) {
    private val field = JTextField(24)
    private val list = JList(DefaultListModel<SearchHit<T>>())

    /**
     * While the window is opening, the keys typed don't reach its field yet: they are caught here
     * and written into it, until the field has the focus.
     */
    private val early = KeyEventDispatcher { e ->
        if (field.isFocusOwner || !isDisplayable) return@KeyEventDispatcher false
        if (e.isControlDown || e.isAltDown || e.isMetaDown) return@KeyEventDispatcher false
        when (e.id) {
            KeyEvent.KEY_TYPED -> if (!e.keyChar.isISOControl()) field.replaceSelection(e.keyChar.toString())
            KeyEvent.KEY_PRESSED -> when (e.keyCode) {
                KeyEvent.VK_BACK_SPACE -> if (field.text.isNotEmpty()) field.text = field.text.dropLast(1)
                KeyEvent.VK_ESCAPE -> dispose()
                KeyEvent.VK_ENTER -> take()
            }
        }
        // everything else typed meanwhile stays away from the window behind
        e.keyChar != KeyEvent.CHAR_UNDEFINED || e.keyCode in setOf(KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE, KeyEvent.VK_ENTER)
    }

    init {
        isUndecorated = true
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.accentColor") ?: java.awt.Color(0x4A88C7)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8),
        )
        panel.add(JLabel(title).apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.NORTH)
        field.putClientProperty("JTextField.placeholderText", "Type to search …")
        // the letter typed in a menu stays as it is, not selected (FlatLaf selects all when the field gets the focus)
        field.putClientProperty("JTextField.selectAllOnFocusPolicy", "never")
        panel.add(field, BorderLayout.CENTER)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 12
        list.isFocusable = false
        list.cellRenderer = Renderer()
        // every row as high as the first: the list doesn't measure all its rows (HTML, slow) on every key
        entries.firstOrNull()?.let { list.prototypeCellValue = SearchHit(it, "keyword") }
        list.fixedCellWidth = 400
        panel.add(JScrollPane(list).apply { preferredSize = Dimension(420, 300) }, BorderLayout.SOUTH)
        contentPane = panel

        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> move(1)
                    KeyEvent.VK_UP -> move(-1)
                    KeyEvent.VK_PAGE_DOWN -> move(list.visibleRowCount)
                    KeyEvent.VK_PAGE_UP -> move(-list.visibleRowCount)
                    KeyEvent.VK_ENTER -> take()
                    KeyEvent.VK_ESCAPE -> dispose()
                    else -> return
                }
                e.consume()
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) take()
            }
        })
        // clicking elsewhere closes it
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) = dispose()
        })
        field.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(early)
        })
        update()
        pack()
        // near the top of the window, in the middle
        setLocation(owner.x + (owner.width - width) / 2, owner.y + 90)
    }

    /** The hits for what is typed, swapped in at once (filled one by one, the list would redo itself for each). */
    private fun update() {
        val hits = DefaultListModel<SearchHit<T>>()
        hits.addAll(rankSearch(field.text, entries))
        list.model = hits
        if (!hits.isEmpty) list.selectedIndex = 0
    }

    private fun move(by: Int) {
        val size = list.model.size
        if (size == 0) return
        val i = (list.selectedIndex + by).coerceIn(0, size - 1)
        list.selectedIndex = i
        list.ensureIndexIsVisible(i)
    }

    private fun take() {
        val hit = list.selectedValue ?: return
        dispose()
        pick(hit.entry.item)
    }

    /** Name, and beside it in grey where it sits and the keyword it was found by. */
    private inner class Renderer : ListCellRenderer<SearchHit<T>> {
        private val label = JLabel().apply { border = BorderFactory.createEmptyBorder(3, 6, 3, 6); isOpaque = true }

        override fun getListCellRendererComponent(list: JList<out SearchHit<T>>, value: SearchHit<T>, index: Int, selected: Boolean, focus: Boolean): Component {
            val grey = if (selected) hex(list.selectionForeground, 0.75) else "#8a8f98"
            val extra = listOfNotNull(value.entry.group, value.keyword?.let { "“$it”" }).joinToString(" · ")
            label.text = "<html><b>${esc(value.entry.name)}</b>&nbsp;&nbsp;<span style='color:$grey'>${esc(extra)}</span></html>"
            label.background = if (selected) list.selectionBackground else list.background
            label.foreground = if (selected) list.selectionForeground else list.foreground
            return label
        }

        /** [c] faded [share] of the way towards the selection background, as #rrggbb. */
        private fun hex(c: java.awt.Color, share: Double): String {
            val b = list.selectionBackground
            fun mix(x: Int, y: Int) = (y + (x - y) * share).toInt().coerceIn(0, 255)
            return "#%02x%02x%02x".format(mix(c.red, b.red), mix(c.green, b.green), mix(c.blue, b.blue))
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    override fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(early)
        super.dispose()
    }

    /** Shows it with the field ready to type into, already holding [typed] (the first letters typed in a menu). */
    fun open(typed: String = "") {
        field.text = typed
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(early)
        isVisible = true
        field.requestFocusInWindow()
        field.caretPosition = field.text.length
        // once the focus has arrived: the cursor behind what was typed, nothing selected
        javax.swing.SwingUtilities.invokeLater { field.select(field.text.length, field.text.length) }
    }
}
