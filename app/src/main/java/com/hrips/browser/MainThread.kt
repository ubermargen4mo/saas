package com.hrips.browser

import android.os.Handler
import android.os.Looper

/**
 * Единая точка доступа к главному потоку: один общий [Handler] и «выполнить на главном потоке» вместо копий
 * `Handler(Looper.getMainLooper())` и самописных `ui { }` в каждом классе.
 *
 * Общий Handler безопасен: все Handler главного Looper работают с одной очередью, а снимать отложенные задачи
 * нужно по своему Runnable или токену (`removeCallbacks(r)`, `removeCallbacksAndMessages(token)`), но не
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
