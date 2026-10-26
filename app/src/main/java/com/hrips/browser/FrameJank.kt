package com.hrips.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/**
 * Учёт времени кадров для диагностического журнала (Настройки -> О программе -> Диагностика).
 *
 * Работает только пока журнал включён и окно на экране: слушатель кадров вешается в [sync] и снимается в [stop],
 * при выключенном журнале накладных расходов нет. Слушатель получает метрики кадров в фоновом потоке, поэтому
 * сам интерфейс не замедляет.
 *
 * Что пишется:
 *  - при запуске: частота обновления экрана, размер, плотность, режим нескольких окон (бюджет кадра зависит от Гц);
 *  - раз в 5 секунд, если были медленные кадры: сколько кадров, сколько медленных, худший кадр и его разбивка;
 *  - сразу, если кадр длился дольше 100 мс: полная разбивка по этапам.
 * Разбивка отвечает на вопрос «где потеряно время»: `layout` (измерение и раскладка, сюда попадает смена размера
 * страницы), `draw`, `sync` (передача на GPU), `cmd` и `swap` (GPU), `anim`, `input`, `delay` (поток не успел начать кадр).
 */
object FrameJank {
    private const val WINDOW_MS = 5_000L
    private const val SEVERE_MS = 100.0
    private const val SLOW_FACTOR = 1.2

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var attachedWindow: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    // Агрегаты окна наблюдения: трогаются только из потока слушателя.
    private var windowStart = 0L
    private var frames = 0
    private var slow = 0
    private var worstMs = 0.0
    private var worstParts = ""

    /** Приводит слушатель в соответствие с журналом: включён - слушаем, выключен - снимаем. Вызывать на главном потоке. */
    fun sync(context: Context) {
        if (AppLog.enabled) start(context) else stop()
    }

    fun start(context: Context) {
        val activity = unwrap(context) ?: return
        val window = activity.window ?: return
        if (attachedWindow === window) return
        stop()
        val t = HandlerThread("hrips-frames").apply { start() }
        val h = Handler(t.looper)
        val budget = budgetMs(activity)
        windowStart = 0L
        frames = 0
        slow = 0
        worstMs = 0.0
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> onFrame(metrics, budget) }
        try {
            window.addOnFrameMetricsAvailableListener(l, h)
        } catch (e: Exception) {
            AppLog.w("Frames", "не удалось включить учёт кадров", e)
            t.quit()
            return
        }
        thread = t
        handler = h
        attachedWindow = window
        listener = l
        AppLog.i("Frames", describeDisplay(activity, budget))
    }

    fun stop() {
        val window = attachedWindow
        val l = listener
        if (window != null && l != null) runCatching { window.removeOnFrameMetricsAvailableListener(l) }
        thread?.quitSafely()
        thread = null
        handler = null
        attachedWindow = null
        listener = null
    }

    private fun onFrame(m: FrameMetrics, budget: Double) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
            // Первый кадр окна всегда долгий и в статистику медленных не входит, но для запуска это главное число.
            AppLog.i("Frames", "первый кадр окна: ${ms(m, FrameMetrics.TOTAL_DURATION).toInt()} мс, с запуска процесса прошло ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} мс")
            return
        }
        val total = ms(m, FrameMetrics.TOTAL_DURATION)
        val now = System.currentTimeMillis()
        if (windowStart == 0L) windowStart = now
        frames++
        if (total > budget * SLOW_FACTOR) {
            slow++
            if (total > worstMs) {
                worstMs = total
                worstParts = parts(m)
            }
        }
        if (total > SEVERE_MS) AppLog.w("Frames", "кадр ${total.toInt()} мс (бюджет ${budget.toInt()} мс): ${parts(m)}")
        if (now - windowStart >= WINDOW_MS) {
            if (slow > 0) {
                AppLog.i("Frames", "за ${(now - windowStart) / 1000} с кадров $frames, медленных $slow, худший ${worstMs.toInt()} мс: $worstParts")
            }
            windowStart = now
            frames = 0
            slow = 0
            worstMs = 0.0
        }
    }

    private fun ms(m: FrameMetrics, id: Int): Double = m.getMetric(id) / 1_000_000.0

    private fun parts(m: FrameMetrics): String =
        "input ${ms(m, FrameMetrics.INPUT_HANDLING_DURATION).toInt()}, anim ${ms(m, FrameMetrics.ANIMATION_DURATION).toInt()}, " +
            "layout ${ms(m, FrameMetrics.LAYOUT_MEASURE_DURATION).toInt()}, draw ${ms(m, FrameMetrics.DRAW_DURATION).toInt()}, " +
            "sync ${ms(m, FrameMetrics.SYNC_DURATION).toInt()}, cmd ${ms(m, FrameMetrics.COMMAND_ISSUE_DURATION).toInt()}, " +
            "swap ${ms(m, FrameMetrics.SWAP_BUFFERS_DURATION).toInt()}, delay ${ms(m, FrameMetrics.UNKNOWN_DELAY_DURATION).toInt()} мс"

    @Suppress("DEPRECATION")
    private fun refreshRate(activity: Activity): Float =
        activity.windowManager.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f

    private fun budgetMs(activity: Activity): Double = 1000.0 / refreshRate(activity)

    private fun describeDisplay(activity: Activity, budget: Double): String {
        val dm = activity.resources.displayMetrics
        val multi = activity.isInMultiWindowMode
        return "дисплей ${refreshRate(activity).toInt()} Гц (бюджет кадра ${"%.1f".format(budget)} мс), окно ${dm.widthPixels}x${dm.heightPixels}, " +
            "плотность ${dm.densityDpi} dpi, несколько окон=$multi"
    }

    private fun unwrap(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
