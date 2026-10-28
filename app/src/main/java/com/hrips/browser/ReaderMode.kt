package com.hrips.browser

import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Режим чтения. В GeckoView нет готового экрана чтения (он есть только в десктопном Firefox), поэтому, как и у
 * [PageText] и [ThemeColor], работает встроенное расширение (`assets/reader`): выделяет статью библиотекой Readability
 * от Mozilla и рисует её поверх страницы (закрытый shadow DOM). Адрес, история и сама страница не меняются:
 * «назад» закрывает читалку, перезагрузка и переход возвращают обычный вид.
 *
 * В отличие от остальных расширений здесь нужна связь в обе стороны, поэтому страница открывает порт
 * (`runtime.connectNative`), а мы держим его на сессию вкладки. От страницы приходит состояние («это статья»,
 * «читалка открыта») и сообщение об ошибке разбора, от нас команды `toggle` / `open` / `close` / `ping`.
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
        var port: WebExtension.Port? = null
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
                    override fun onConnect(port: WebExtension.Port) {
                        binding.port = port
                        port.setDelegate(
                            object : WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                    val json = message as? JSONObject ?: return
                                    when (json.optString("t")) {
                                        "state" -> {
                                            val readerable = json.optBoolean("readerable")
                                            val active = json.optBoolean("active")
                                            MainThread.handler.post { binding.onState(readerable, active) }
                                        }
                                        "fail" -> MainThread.handler.post { binding.onFail() }
                                    }
                                }

                                override fun onDisconnect(port: WebExtension.Port) {
                                    if (binding.port === port) binding.port = null
                                }
                            },
                        )
                        // Страница могла открыть порт раньше, чем мы подписались: просим повторить состояние.
                        post(binding, "ping")
                    }

                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? = null
                },
                NATIVE_APP,
            )
        }
    }

    private fun post(binding: Binding, cmd: String): Boolean {
        val port = binding.port ?: return false
        return runCatching { port.postMessage(JSONObject().put("cmd", cmd)) }.isSuccess
    }

    /** Открывает читалку, а если она открыта, закрывает. false, если страница не на связи (расширение не загрузилось, страница не http/https). */
    fun toggle(session: GeckoSession): Boolean = sessions[session]?.let { post(it, "toggle") } ?: false

    fun close(session: GeckoSession) {
        sessions[session]?.let { post(it, "close") }
    }
}
