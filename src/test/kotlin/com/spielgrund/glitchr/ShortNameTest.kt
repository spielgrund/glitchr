package com.spielgrund.glitchr

import com.spielgrund.glitchr.ui.shortName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortNameTest {
    @Test
    fun `long names keep start and end, short ones stay`() {
        assertEquals("Lenna_", shortName("Lenna_"))
        val long = "csm_leopard-massai-mara-kenia-WW22416-c-naturepl-com-Anup-Shah-WWF_79b7762679"
        val short = shortName(long)
        assertEquals(32, short.length)
        assertTrue(short.startsWith("csm_leopard-massai-mar"))
        assertTrue(short.endsWith("…9b7762679"))
    }
}
