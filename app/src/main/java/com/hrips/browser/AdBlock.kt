package com.hrips.browser

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class AdBlock(private val runtime: GeckoRuntime) {
    private companion object {
        const val TAG = "AdBlock"
        const val ASSETS_URI = "resource://android/assets/ublock/"
        const val EXTENSION_ID = "uBlock0@raymondhill.net"
    }

    private val main = MainThread.handler

    private fun ui(block: () -> Unit) = MainThread.run(block)

    var extension by mutableStateOf<WebExtension?>(null)
        private set
    var enabled by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var onInstalled: (() -> Unit)? = null

    private var installing = false

    fun install() {
        if (installing || extension != null) return
        installing = true
        error = null
        runtime.webExtensionController
            .ensureBuiltIn(ASSETS_URI, EXTENSION_ID)
            .accept(
                { ext ->
                    AppLog.i(TAG, "uBlock Origin загружен: расширение=${ext != null}, включено=${ext?.metaData?.enabled}, в приватных=${ext?.metaData?.allowedInPrivateBrowsing}")
                    ui {
                        installing = false
                        extension = ext
                        enabled = ext?.metaData?.enabled ?: true
                        if (ext == null) error = "расширение не найдено"
                        onInstalled?.invoke()
                    }
                    if (ext != null && !ext.metaData.allowedInPrivateBrowsing) allowInPrivate(ext)
                },
                { e ->
                    AppLog.w(TAG, "uBlock Origin не установился", e)
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
            { e -> Log.w(TAG, "Не удалось включить uBlock Origin в приватных вкладках", e) },
        )
    }

    fun setBlocking(value: Boolean, onDone: (() -> Unit)? = null) {
        val ext = extension ?: return
        AppLog.i(TAG, "переключение блокировки рекламы: $value")
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
