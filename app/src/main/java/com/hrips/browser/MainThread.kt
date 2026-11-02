package com.hrips.browser

import android.os.Handler
import android.os.Looper

object MainThread {
    val handler = Handler(Looper.getMainLooper())

    val isCurrent: Boolean get() = Looper.myLooper() === Looper.getMainLooper()

    fun run(block: () -> Unit) {
        if (isCurrent) block() else handler.post(block)
    }
}
