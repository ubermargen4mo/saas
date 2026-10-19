package com.hrips.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.geckoview.GeckoView

/**
 * Страховка от чёрной или прозрачной страницы после возврата в приложение (свёртывание, выбор файла, смена приложения).
 *
 * GeckoView рисует в SurfaceView; если после возврата поверхность не получила кадр, на экране чёрное или «дырка».
 * Проверка снимает кадр и, если он пустой, не пришёл за [TIMEOUT_MS] или снять не удалось, просит GeckoView
 * пересоздать поверхность (тот же приём, что у самого GeckoView для таких случаев). Исправный экран не трогается.
 */
internal object SurfaceGuard {
    private const val TAG = "HripsSurface"
    private const val TIMEOUT_MS = 1500L

    private val main = Handler(Looper.getMainLooper())

    fun check(view: GeckoView) {
        var finished = false
        val rescue = Runnable {
            if (!finished) {
                finished = true
                recreate(view, "кадр не пришёл")
            }
        }
        main.postDelayed(rescue, TIMEOUT_MS)
        try {
            view.capturePixels().accept({ bmp ->
                main.post {
                    if (finished) {
                        if (bmp != null && !bmp.isRecycled) bmp.recycle()
                        return@post
                    }
                    finished = true
                    main.removeCallbacks(rescue)
                    val bad = bmp == null || TabThumbs.isLikelyFailed(bmp)
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                    if (bad) recreate(view, "пустой кадр") else Log.d(TAG, "Поверхность в порядке")
                }
            }, {
                main.post {
                    if (!finished) {
                        finished = true
                        main.removeCallbacks(rescue)
                        recreate(view, "ошибка снимка")
                    }
                }
            })
        } catch (e: Exception) {
            finished = true
            main.removeCallbacks(rescue)
            Log.w(TAG, "Проверка поверхности не удалась", e)
        }
    }

    private fun recreate(view: GeckoView, why: String) {
        if (!view.isAttachedToWindow || !view.isShown) return
        Log.w(TAG, "Пересоздаю поверхность страницы: $why")
        view.requestNewSurface()
    }
}
