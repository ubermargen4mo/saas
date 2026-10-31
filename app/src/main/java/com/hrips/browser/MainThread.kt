package com.hrips.browser

import android.os.Handler
import android.os.Looper

/**
 * Общий [Handler] главного потока. Отложенные задачи снимать по своему Runnable или токену, но не через
 * `removeCallbacksAndMessages(null)`: это сняло бы задачи всех остальных.
 */
object MainThread {
    val handler = Handler(Looper.getMainLooper())

    val isCurrent: Boolean get() = Looper.myLooper() === Looper.getMainLooper()

    /** Выполняет сразу, если вызвано на главном потоке, иначе ставит в очередь главного потока. */
    fun run(block: () -> Unit) {
        if (isCurrent) block() else handler.post(block)
    }
}
