package com.hrips.browser

import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Режим чтения. В GeckoView нет готового экрана чтения (он есть только в десктопном Firefox), поэтому, как и у
 * [ThemeColor], работает встроенное расширение (`assets/reader`): выделяет статью библиотекой Readability
 * от Mozilla и рисует её поверх страницы (закрытый shadow DOM). Адрес, история и сама страница не меняются:
 * «назад» закрывает читалку, перезагрузка и переход возвращают обычный вид.
 *
 * В отличие от остальных расширений здесь нужна связь в обе стороны. Порты (`connectNative`) в содержимом страницы
 * ненадёжны, поэтому всё идёт через `sendNativeMessage`: страница присылает состояние («читалка
 * открыта») и ошибку разбора, а команды `toggle` / `open` / `close` / `ping` мы отдаём ответом на её висящий
 * запрос `poll`.
 * Текст статьи в приложение не отправляется и нигде не хранится; расширение помнит только размер шрифта,
 * гарнитуру и тему читалки.
 *
 * Всё, кроме [install], вызывается с главного потока.
 */
object ReaderMode {
    private const val NATIVE_APP = "hripsReader"
    private const val EXTENSION_ID = "reader@hrips.app"

    private class Binding(
        val onState: (readerable: Boolean, active: Boolean) -> Unit,
        val onFail: () -> Unit,
    ) {
        /** Висящий ответ на «poll» страницы: им отдаём команду. */
        var pending: GeckoResult<Any>? = null
    }

    private val sessions = java.util.WeakHashMap<GeckoSession, Binding>()
    private var extension: WebExtension? = null

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/reader/", EXTENSION_ID).accept(
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
            { AppLog.i("Reader") { "расширение не загрузилось" } },
        )
    }

    private fun ready(ext: WebExtension) {
        extension = ext
        sessions.entries.toList().forEach { (s, b) -> attach(s, b, ext) }
    }

    /**
     * Подписывает сессию вкладки. [onState] получает, похожа ли страница на статью и открыта ли читалка;
     * [onFail] вызывается, если по просьбе открыть читалку статью выделить не удалось.
     */
    fun register(
        session: GeckoSession,
        onState: (readerable: Boolean, active: Boolean) -> Unit,
        onFail: () -> Unit,
    ) {
        val binding = Binding(onState, onFail)
        sessions[session] = binding
        extension?.let { attach(session, binding, it) }
    }

    private fun attach(session: GeckoSession, binding: Binding, ext: WebExtension) {
        runCatching {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val json = message as? JSONObject ?: return null
                        return when (json.optString("t")) {
                            "state" -> {
                                val readerable = json.optBoolean("readerable")
                                val active = json.optBoolean("active")
                                MainThread.handler.post { binding.onState(readerable, active) }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "fail" -> {
                                MainThread.handler.post { binding.onFail() }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "poll" -> {
                                // Новый запрос заменяет старый (страница перезагрузилась).
                                val r = GeckoResult<Any>()
                                MainThread.handler.post {
                                    binding.pending?.complete(JSONObject())
                                    binding.pending = r
                                }
                                r
                            }
                            else -> null
                        }
                    }
                },
                NATIVE_APP,
            )
        }
    }

    private fun post(binding: Binding, cmd: String): Boolean {
        val pending = binding.pending ?: return false
        binding.pending = null
        return runCatching { pending.complete(JSONObject().put("cmd", cmd)) }.isSuccess
    }

    /** Открывает читалку, а если она открыта, закрывает. false, если страница не на связи (расширение не загрузилось, страница не http/https). */
    fun toggle(session: GeckoSession): Boolean = sessions[session]?.let { post(it, "toggle") } ?: false

    fun close(session: GeckoSession) {
        sessions[session]?.let { post(it, "close") }
    }
}
