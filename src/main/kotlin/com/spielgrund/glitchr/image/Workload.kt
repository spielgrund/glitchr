package com.spielgrund.glitchr.image

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.stream.IntStream

/**
 * How much of the computer rendering may take: all work spread over several cores runs in one
 * pool of at most [cores] threads, at a lower priority than normal programs if [lowPriority] –
 * so music, video and the rest of the system stay smooth while GlitchR renders, and the machine
 * isn't driven to full load on every core.
 */
object Workload {
    /** All the processor's cores. */
    val available: Int = Runtime.getRuntime().availableProcessors()

    /** By default three quarters of the cores, leaving the rest to the system. */
    val defaultCores: Int = maxOf(1, available * 3 / 4)

    @Volatile var cores: Int = defaultCores
        private set

    @Volatile var lowPriority: Boolean = true
        private set

    @Volatile private var pool: ForkJoinPool = makePool()

    /** Sets how many cores rendering may use and whether it runs below normal priority. */
    @Synchronized
    fun configure(cores: Int, lowPriority: Boolean) {
        val n = cores.coerceIn(1, available)
        if (n == this.cores && lowPriority == this.lowPriority) return
        this.cores = n
        this.lowPriority = lowPriority
        val old = pool
        pool = makePool()
        old.shutdown()
    }

    /** The priority render threads run at. */
    val priority: Int get() = if (lowPriority) Thread.NORM_PRIORITY - 2 else Thread.NORM_PRIORITY

    private fun makePool(): ForkJoinPool {
        val factory = ForkJoinPool.ForkJoinWorkerThreadFactory { p ->
            object : ForkJoinWorkerThread(p) {}.apply {
                name = "glitch-worker-$poolIndex"
                isDaemon = true
                priority = this@Workload.priority
            }
        }
        return ForkJoinPool(cores, factory, null, false)
    }

    /** Runs [body] for 0 until [n], spread over the pool's threads; returns when all are done. */
    fun forEach(n: Int, body: (Int) -> Unit) {
        val current = Thread.currentThread()
        // already inside the pool (nested): the parallel stream uses it by itself
        if (current is ForkJoinWorkerThread && current.pool === pool) {
            IntStream.range(0, n).parallel().forEach { body(it) }
            return
        }
        val p = pool
        try {
            p.submit { IntStream.range(0, n).parallel().forEach { body(it) } }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // the pool was just replaced: run in the new one
            pool.submit { IntStream.range(0, n).parallel().forEach { body(it) } }.get()
        }
    }

    /** A thread factory for the render threads, at the render priority. */
    fun threads(name: String) = java.util.concurrent.ThreadFactory { r ->
        Thread(r, name).apply {
            isDaemon = true
            priority = this@Workload.priority
        }
    }
}
