package com.hrips.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Блокировка рекламы = расширение uBlock Origin, встроенное в приложение.
 * Файлы расширения кладёт в assets/ublock шаг "Fetch uBlock Origin" в GitHub Actions.
 */
class AdBlock(private val runtime: GeckoRuntime) {
    private companion object {
        const val TAG = "AdBlock"
        const val ASSETS_URI = "resource://android/assets/ublock/"
        const val EXTENSION_ID = "uBlock0@raymondhill.net"
    }

    private val main = Handler(Looper.getMainLooper())

    /** Состояние читает интерфейс (Compose), поэтому пишем его только из главного потока. */
    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    var extension by mutableStateOf<WebExtension?>(null)
        private set
    var enabled by mutableStateOf(true)
        private set

    /** Причина, по которой расширение не установилось. Ошибки переключения сюда не попадают: они показываются уведомлением. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Вызывается, когда встроенное расширение готово: список расширений в интерфейсе обновляется. */
    var onInstalled: (() -> Unit)? = null

    private var installing = false

    /** Ставит встроенное расширение. Повторный вызов во время установки или после неё ничего не делает. */
    fun install() {
        if (installing || extension != null) return
        installing = true
        error = null
        runtime.webExtensionController
            .ensureBuiltIn(ASSETS_URI, EXTENSION_ID)
            .accept(
                { ext ->
                    ui {
                        installing = false
                        extension = ext
                        enabled = ext?.metaData?.enabled ?: true
                        if (ext == null) error = "расширение не найдено"
                        onInstalled?.invoke()
                    }
                    // По умолчанию расширения в приватных вкладках не работают: без этого там не было бы блокировки
                    if (ext != null && !ext.metaData.allowedInPrivateBrowsing) allowInPrivate(ext)
                },
                { e ->
                    Log.w(TAG, "uBlock Origin не установился", e)
                    ui {
                        installing = false
                        error = e?.message ?: "неизвестная ошибка"
                    }
                },
            )
    }

    private fun allowInPrivate(ext: WebExtension) {
        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true).accept(
            { updated ->
                ui {
                    if (updated != null) extension = updated
                    onInstalled?.invoke()
                }
            },
            // Без этого блокировка в приватных вкладках не работает, но обычные вкладки она защищает: просто фиксируем
            { e -> Log.w(TAG, "Не удалось включить uBlock Origin в приватных вкладках", e) },
        )
    }

    /**
     * Включает или выключает блокировку. Состояние в интерфейсе меняется только после ответа движка;
     * [onDone] вызывается тогда же (например, чтобы перезагрузить страницу уже с новым состоянием).
     */
    fun setBlocking(value: Boolean, onDone: (() -> Unit)? = null) {
        val ext = extension ?: return
        val controller = runtime.webExtensionController
        val result = if (value) {
            controller.enable(ext, WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(ext, WebExtensionController.EnableSource.USER)
        }
        result.accept(
            { updated ->
                ui {
                    if (updated != null) extension = updated
                    enabled = value
                    onDone?.invoke()
                }
            },
            { e ->
                Log.w(TAG, "Не удалось переключить блокировку", e)
                Notices.show("Не удалось переключить блокировку рекламы")
            },
        )
    }
}
