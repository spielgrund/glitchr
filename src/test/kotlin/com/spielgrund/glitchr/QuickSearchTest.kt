package com.spielgrund.glitchr

import com.spielgrund.glitchr.effects.Effects
import com.spielgrund.glitchr.effects.Generators
import com.spielgrund.glitchr.ui.QuickSearchKeywords
import com.spielgrund.glitchr.ui.SearchEntry
import com.spielgrund.glitchr.ui.rankSearch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickSearchTest {
    private val entries = listOf(
        SearchEntry(1, "effect", "pixelsort", "Pixelsort", "Glitch", listOf("sort", "schlieren")),
        SearchEntry(2, "effect", "pixelstretch", "Pixelstretch", "Glitch", listOf("stretch", "ziehen")),
        SearchEntry(3, "effect", "moirefilter", "Moiré filter", "Patterns", listOf("interference")),
        SearchEntry(4, "effect", "blur", "Blur", "Color", listOf("unschärfe", "soft")),
        SearchEntry(5, "effect", "glow", "Glow", "Color", listOf("bloom", "leuchten")),
    )

    private fun first(q: String) = rankSearch(q, entries).first().entry.item

    @Test
    fun `names first, then keywords, then letters in order`() {
        assertEquals(1, first("pix"), "start of the name, in its order")
        assertEquals(2, first("pixelst"))
        assertEquals(5, first("bloom"), "by keyword")
        assertEquals("bloom", rankSearch("bloo", entries).first().keyword, "the keyword is shown")
        assertEquals(3, first("moire"), "accents don't matter")
        assertEquals(4, first("unscharfe"), "umlauts neither")
        assertEquals(2, first("pxst"), "letters in order")
        assertEquals(3, first("filt"), "start of a word in the name")
        assertTrue(rankSearch("zzz", entries).isEmpty())
        assertEquals(entries.size, rankSearch("", entries).size, "nothing typed: everything")
    }

    @Test
    fun `every filter has built-in keywords, and the user's list is kept and completed`() {
        val builtIn = QuickSearchKeywords.parse(QuickSearchKeywords.builtIn)
        for (g in Generators.all) assertTrue((builtIn["generator ${g.id}"] ?: emptyList()).isNotEmpty(), "generator ${g.id}")
        for (e in Effects.all) assertTrue((builtIn["effect ${e.id}"] ?: emptyList()).isNotEmpty(), "effect ${e.id}")

        val file = File.createTempFile("keywords", ".txt").apply { delete() }
        try {
            val filters = listOf("effect blur" to "Blur", "effect glow" to "Glow")
            // written from the built-in list the first time
            assertTrue("soft" in QuickSearchKeywords.load(filters, file)["effect blur"]!!)
            // own edits count; a filter missing from the file is appended with its built-in keywords
            file.writeText("# mine\neffect blur: weich, nebelig   # my words\n")
            val loaded = QuickSearchKeywords.load(filters, file)
            assertEquals(listOf("weich", "nebelig"), loaded["effect blur"])
            assertTrue("bloom" in loaded["effect glow"]!!)
            assertTrue(file.readText().contains("effect glow:"), "appended")
            assertEquals(1, Regex("effect glow:").findAll(file.readText()).count())
            // the next time nothing more is appended
            QuickSearchKeywords.load(filters, file)
            assertEquals(1, Regex("effect glow:").findAll(file.readText()).count())
        } finally {
            file.delete()
        }
    }
}
