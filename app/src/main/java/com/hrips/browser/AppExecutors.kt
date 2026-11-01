package com.hrips.browser

import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AppExecutors {
    private const val TAG = "AppExecutors"
    private const val THREADS = 3
    private const val QUEUE_SIZE = 32

    private val counter = AtomicInteger()

    private val factory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "hrips-io-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private val ioPool = ThreadPoolExecutor(
        THREADS, THREADS,
        30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(QUEUE_SIZE),
        factory,
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun startThread(name: String, onError: ((Exception) -> Unit)? = null, task: () -> Unit) {
        Thread({
            try {
                task()
            } catch (e: Exception) {
                AppLog.e(TAG, "Поток $name завершился с ошибкой", e)
                onError?.invoke(e)
            }
        }, name).apply { isDaemon = true }.start()
    }

    fun tryExecute(task: () -> Unit): Boolean = try {
        ioPool.execute {
            try {
                task()
            } catch (e: Exception) {
                AppLog.e(TAG, "Фоновая задача завершилась с ошибкой", e)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }
}
