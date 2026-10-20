package com.hrips.browser

import android.app.Application
import java.io.File

/**
 * Приложение. Вся тяжёлая работа — в [Components]; здесь только запуск в главном процессе.
 *
 * GeckoView запускает свои процессы (:tab0, :gpu и т.д.) с тем же Application, поэтому
 * в них мы не должны ни создавать движок, ни восстанавливать вкладки.
 */
class HripsApp : Application() {
    val components: Components by lazy { Components(this) }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
        components.start()
    }

    private fun isMainProcess(): Boolean = try {
        val name = File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        name == packageName
    } catch (e: Exception) {
        true
    }
}
