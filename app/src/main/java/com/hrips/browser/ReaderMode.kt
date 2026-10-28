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

    private const val ANSWER_MS = 4_000L

    private class Binding(
        val onState: (active: Boolean) -> Unit,
        val onFail: () -> Unit,
        val onUnavailable: () -> Unit,
    ) {
        /** Висящий ответ на «poll» страницы: им отдаём команду. */
        var pending: GeckoResult<Any>? = null

        /** Команда, которую некому было отдать (страница ещё не вышла на связь): уйдёт с ближайшим «poll». */
        var queued: String? = null

        /** Ждём ответ страницы («state» или «fail») на последнюю команду; по таймеру сообщаем, что страница молчит. */
        var waiting: Runnable? = null

        fun answered() {
            waiting?.let { MainThread.handler.removeCallbacks(it) }
            waiting = null
        }
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
     * Подписывает сессию вкладки. [onState] получает, открыта ли читалка;
     * [onFail] вызывается, если по просьбе открыть читалку статью выделить не удалось, [onUnavailable] если
     * страница вообще не ответила (расширение не работает на ней или не успело подключиться).
     */
    fun register(
        session: GeckoSession,
        onState: (active: Boolean) -> Unit,
        onFail: () -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        val binding = Binding(onState, onFail, onUnavailable)
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
                                val active = json.optBoolean("active")
                                MainThread.handler.post { binding.answered(); binding.onState(active) }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "fail" -> {
                                MainThread.handler.post { binding.answered(); binding.onFail() }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "poll" -> {
                                // Новый запрос заменяет старый (страница перезагрузилась). Если команда ждала связи, отдаём её сразу.
                                val r = GeckoResult<Any>()
                                MainThread.handler.post {
                                    binding.pending?.complete(JSONObject())
                                    binding.pending = null
                                    val q = binding.queued
                                    if (q != null) {
                                        binding.queued = null
                                        r.complete(JSONObject().put("cmd", q))
                                    } else {
                                        binding.pending = r
                                    }
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

    private fun post(binding: Binding, cmd: String) {
        val pending = binding.pending
        if (pending != null) {
            binding.pending = null
            runCatching { pending.complete(JSONObject().put("cmd", cmd)) }
        } else {
            // Страница ещё не вышла на связь (только что загрузилась): команда уйдёт с её первым «poll».
            binding.queued = cmd
        }
        binding.answered()
        val timeout = Runnable {
            binding.waiting = null
            binding.queued = null
            binding.onUnavailable()
        }
        binding.waiting = timeout
        MainThread.handler.postDelayed(timeout, ANSWER_MS)
    }

    /** Открывает читалку, а если она открыта, закрывает. false, если расширение к вкладке не подключено. */
    fun toggle(session: GeckoSession): Boolean {
        val b = sessions[session] ?: return false
        if (extension == null) return false
        post(b, "toggle")
        return true
    }

    fun close(session: GeckoSession) {
        sessions[session]?.let { post(it, "close") }
    }

    /** Новая загрузка в сессии: ответ и команда прежней страницы больше ничего не значат. */
    fun reset(session: GeckoSession) {
        sessions[session]?.let { b ->
            b.answered()
            b.queued = null
            b.pending?.let { runCatching { it.complete(JSONObject()) } }
            b.pending = null
        }
    }
}
