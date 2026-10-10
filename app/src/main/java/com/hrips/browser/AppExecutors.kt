package com.hrips.browser

import java.util.concurrent.ThreadFactory
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide bounded executors for background work that is not owned by Gecko or Downloads. */
object AppExecutors {
    private val counter = AtomicInteger()

    private fun factory(prefix: String) = ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val ioPool = java.util.concurrent.ThreadPoolExecutor(
        3, 3,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(32),
        factory("hrips-io"),
    ).apply {
        // Пул нужен редко (копирование файлов, кэш): без этого три потока жили бы весь срок жизни процесса
        allowCoreThreadTimeOut(true)
    }

    /** Small bounded pool for file/network/cache work triggered by UI callbacks. */
    val io = ioPool

    /** Returns false instead of throwing when the bounded queue is full. */
    fun tryExecute(task: () -> Unit): Boolean = try {
        ioPool.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }
}
