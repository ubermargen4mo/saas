package com.hrips.browser

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Цвет сайта для шапки на планшете. В GeckoView нет колбэка под `theme-color`, поэтому, как и у [PageText],
 * работает встроенное расширение (`assets/themecolor`): читает `<meta name="theme-color">` (с учётом `media`
 * светлой и тёмной темы), без тега берёт фон body или html, следит за изменениями и присылает цвет.
 * Цвет привязан к вкладке по сессии. Ничего не хранится: цвет живёт только пока открыта страница.
 *
 * Всё, кроме [install], вызывается с главного потока.
 */
object ThemeColor {
    private const val NATIVE_APP = "hripsThemeColor"
    private const val EXTENSION_ID = "themecolor@hrips.app"

    private class Binding(val tabId: String, val onColor: (Color?) -> Unit)

    private val sessions = java.util.WeakHashMap<GeckoSession, Binding>()
    private var extension: WebExtension? = null

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/themecolor/", EXTENSION_ID).accept(
            { ext ->
                if (ext == null) return@accept
                if (ext.metaData.allowedInPrivateBrowsing) {
                    MainThread.handler.post { ready(ext) }
                } else {
                    controller.setAllowedInPrivateBrowsing(ext, true).accept(
                        { updated -> MainThread.handler.post { ready(updated ?: ext) } },
                        { MainThread.handler.post { ready(ext) } },
                    )
                }
            },
            { AppLog.i("ThemeColor") { "расширение не загрузилось" } },
        )
    }

    private fun ready(ext: WebExtension) {
        extension = ext
        sessions.entries.toList().forEach { (s, b) -> attach(s, b, ext) }
    }

    /** Подписывает сессию вкладки; [onColor] получает цвет сайта или null, если цвета нет. */
    fun register(session: GeckoSession, tabId: String, onColor: (Color?) -> Unit) {
        val binding = Binding(tabId, onColor)
        sessions[session] = binding
        extension?.let { attach(session, binding, it) }
    }

    private fun attach(session: GeckoSession, binding: Binding, ext: WebExtension) {
        runCatching {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val hex = (message as? JSONObject)?.optString("color").orEmpty()
                        val color = if (hex.length == 7 && hex[0] == '#') {
                            runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
                        } else null
                        MainThread.handler.post { binding.onColor(color) }
                        return null
                    }
                },
                NATIVE_APP,
            )
        }
    }
}
