package com.spielgrund.glitchr

import com.spielgrund.glitchr.image.Workload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkloadTest {
    @Test
    fun `work runs on at most the allowed cores, at low priority, every index once`() {
        val before = Workload.cores to Workload.lowPriority
        try {
            Workload.configure(2, true)
            val threads = ConcurrentHashMap.newKeySet<Thread>()
            val count = AtomicInteger()
            val seen = IntArray(5000)
            Workload.forEach(5000) { i ->
                threads += Thread.currentThread()
                seen[i]++
                count.incrementAndGet()
                // nested work stays in the same pool
                Workload.forEach(3) { threads += Thread.currentThread() }
            }
            assertEquals(5000, count.get())
            assertTrue(seen.all { it == 1 })
            assertTrue(threads.size <= 2, "threads: ${threads.size}")
            assertTrue(threads.all { it.priority < Thread.NORM_PRIORITY })
            // errors come through
            val error = runCatching { Workload.forEach(10) { if (it == 7) error("boom") } }.exceptionOrNull()
            assertTrue(error?.message?.contains("boom") == true, "$error")
        } finally {
            Workload.configure(before.first, before.second)
        }
    }
}
