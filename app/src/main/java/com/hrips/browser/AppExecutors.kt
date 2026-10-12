package com.hrips.browser

import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Общий ограниченный пул для фоновой работы приложения (копирование файлов, кэш, сеть), которой не владеют Gecko и загрузки. */
object AppExecutors {
    private const val TAG = "AppExecutors"
    private const val THREADS = 3
    private const val QUEUE_SIZE = 32

    private val counter = AtomicInteger()

    private val factory = ThreadFactory { runnable ->
        Thread({
            // Фоновый приоритет системы (а не просто чуть ниже обычного), чтобы не отнимать время у отрисовки
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
        // Пул нужен редко: без этого три потока жили бы весь срок жизни процесса
        allowCoreThreadTimeOut(true)
    }

    /**
     * Запускает [task] в фоне. Возвращает false, если очередь полна (вместо исключения): вызывающий сам решает, что показать.
     * Исключение внутри задачи записывается в лог и не роняет приложение: раньше его подхватывал обработчик потока и процесс завершался.
     */
    fun tryExecute(task: () -> Unit): Boolean = try {
        ioPool.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Фоновая задача завершилась с ошибкой", e)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }
}
