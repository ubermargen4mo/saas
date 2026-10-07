package com.hrips.browser

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
    var extension by mutableStateOf<WebExtension?>(null)
        private set
    var enabled by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Вызывается, когда встроенное расширение готово: список расширений в интерфейсе обновляется. */
    var onInstalled: (() -> Unit)? = null

    fun install() {
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/ublock/", "uBlock0@raymondhill.net")
            .accept(
                { ext ->
                    extension = ext
                    enabled = ext?.metaData?.enabled ?: true
                    onInstalled?.invoke()
                    // По умолчанию расширения в приватных вкладках не работают: без этого там не было бы блокировки
                    if (ext != null && !ext.metaData.allowedInPrivateBrowsing) {
                        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                            .accept({ updated -> if (updated != null) extension = updated; onInstalled?.invoke() }, { })
                    }
                },
                { e -> error = e?.message ?: "неизвестная ошибка" },
            )
    }

    fun setBlocking(value: Boolean) {
        val ext = extension ?: return
        val controller = runtime.webExtensionController
        val result = if (value) {
            controller.enable(ext, WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(ext, WebExtensionController.EnableSource.USER)
        }
        result.accept(
            { updated ->
                if (updated != null) extension = updated
                enabled = value
            },
            { e -> error = e?.message },
        )
    }
}
