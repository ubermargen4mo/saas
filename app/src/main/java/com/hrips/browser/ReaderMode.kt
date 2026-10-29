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

    private const val TAG = "Reader"

    // Страница может выйти на связь с паузой до 5 секунд (JS повторяет «poll» с растущей паузой), поэтому ждём дольше.
    private const val ANSWER_MS = 8_000L

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

        /** Скрипт страницы уже писал «hello» (значит, расширение внедрилось в эту страницу и связь с ней работает). */
        var pageReady = false

        /** Когда отправили последнюю команду (для журнала: за сколько страница ответила). */
        var postedAt = 0L

        var polls = 0
        var seq = 0

        fun answered() {
            waiting?.let { MainThread.handler.removeCallbacks(it) }
            waiting = null
        }
    }

    private val sessions = java.util.WeakHashMap<GeckoSession, Binding>()
    private var extension: WebExtension? = null

    /** Состояние установки расширения: для журнала и для понятного сообщения пользователю. */
    private enum class Install { NOT_STARTED, INSTALLING, READY, FAILED }

    private var installState = Install.NOT_STARTED
    private var installError: String? = null

    fun install(runtime: GeckoRuntime) {
        installState = Install.INSTALLING
        AppLog.i(TAG) { "установка расширения: $EXTENSION_ID" }
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/reader/", EXTENSION_ID).accept(
            { ext ->
                if (ext == null) {
                    AppLog.w(TAG, "ensureBuiltIn вернул пустой результат: расширение не найдено в assets/reader")
                    MainThread.handler.post { installState = Install.FAILED; installError = "расширение не найдено" }
                    return@accept
                }
                AppLog.i(TAG) {
                    "расширение загружено: версия=${ext.metaData.version}, включено=${ext.metaData.enabled}, " +
                        "в приватных=${ext.metaData.allowedInPrivateBrowsing}"
                }
                if (ext.metaData.allowedInPrivateBrowsing) {
                    MainThread.handler.post { ready(ext) }
                } else {
                    controller.setAllowedInPrivateBrowsing(ext, true).accept(
                        { updated -> MainThread.handler.post { ready(updated ?: ext) } },
                        { e ->
                            AppLog.w(TAG, "не удалось разрешить расширение в приватных вкладках", e)
                            MainThread.handler.post { ready(ext) }
                        },
                    )
                }
            },
            { e ->
                AppLog.w(TAG, "расширение не установилось", e)
                MainThread.handler.post { installState = Install.FAILED; installError = e?.message ?: "неизвестная ошибка" }
            },
        )
    }

    private fun ready(ext: WebExtension) {
        extension = ext
        installState = Install.READY
        val all = sessions.entries.toList()
        AppLog.i(TAG) { "расширение готово, подключаю к открытым сессиям: ${all.size}" }
        all.forEach { (s, b) -> attach(s, b, ext) }
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
        val ext = extension
        AppLog.i(TAG) { "сессия зарегистрирована (расширение ${if (ext != null) "готово, подключаю" else "ещё не готово: $installState"})" }
        if (ext != null) attach(session, binding, ext)
    }

    private fun attach(session: GeckoSession, binding: Binding, ext: WebExtension) {
        try {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val json = message as? JSONObject
                        if (json == null) {
                            AppLog.w(TAG, "сообщение не JSON: ${message.javaClass.simpleName} (от $nativeApp)")
                            return null
                        }
                        return when (val type = json.optString("t")) {
                            "hello" -> {
                                // Скрипт расширения запустился на странице: связь страницы с приложением работает.
                                MainThread.handler.post { binding.pageReady = true }
                                AppLog.i(TAG) { "страница вышла на связь (hello)" }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "log" -> {
                                // Строки журнала из самого скрипта страницы (адреса и текст статей не передаются).
                                AppLog.i(TAG) { "страница: ${json.optString("m")}" }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "state" -> {
                                val active = json.optBoolean("active")
                                AppLog.i(TAG) { "страница сообщила: читалка ${if (active) "открыта" else "закрыта"}" }
                                MainThread.handler.post { binding.answered(); binding.onState(active) }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "fail" -> {
                                AppLog.w(TAG, "страница сообщила: статью выделить не удалось (${json.optString("why")})")
                                MainThread.handler.post { binding.answered(); binding.onFail() }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "poll" -> {
                                // Новый запрос заменяет старый (страница перезагрузилась). Если команда ждала связи, отдаём её сразу.
                                val r = GeckoResult<Any>()
                                MainThread.handler.post {
                                    binding.polls++
                                    binding.pending?.complete(JSONObject())
                                    binding.pending = null
                                    val q = binding.queued
                                    if (q != null) {
                                        binding.queued = null
                                        AppLog.i(TAG) { "poll #${binding.polls}: отдаю отложенную команду «$q»" }
                                        r.complete(JSONObject().put("cmd", q))
                                    } else {
                                        binding.pending = r
                                        if (binding.polls <= 3) AppLog.i(TAG) { "poll #${binding.polls}: жду команду" }
                                    }
                                }
                                r
                            }
                            else -> {
                                AppLog.w(TAG, "неизвестный тип сообщения: «$type»")
                                null
                            }
                        }
                    }
                },
                NATIVE_APP,
            )
            AppLog.i(TAG) { "делегат сообщений подключён к сессии" }
        } catch (e: Throwable) {
            AppLog.w(TAG, "не удалось подключить делегат сообщений к сессии", e)
        }
    }

    private fun post(binding: Binding, cmd: String) {
        val n = ++binding.seq
        binding.postedAt = android.os.SystemClock.elapsedRealtime()
        val pending = binding.pending
        if (pending != null) {
            binding.pending = null
            AppLog.i(TAG) { "команда #$n «$cmd»: отдаю сразу, страница ждёт (hello=${binding.pageReady})" }
            runCatching { pending.complete(JSONObject().put("cmd", cmd)) }
                .onFailure { AppLog.w(TAG, "не удалось отдать команду «$cmd»", it) }
        } else {
            // Страница ещё не вышла на связь (только что загрузилась): команда уйдёт с её первым «poll».
            binding.queued = cmd
            AppLog.i(TAG) { "команда #$n «$cmd»: страницы на связи нет, откладываю (hello=${binding.pageReady}, poll-ов=${binding.polls})" }
        }
        binding.answered()
        val timeout = Runnable {
            binding.waiting = null
            val hadQueue = binding.queued != null
            binding.queued = null
            AppLog.w(
                TAG,
                "команда #$n «$cmd»: страница не ответила за ${ANSWER_MS / 1000} с " +
                    "(hello=${binding.pageReady}, poll-ов=${binding.polls}, команда так и не была забрана=$hadQueue)",
            )
            binding.onUnavailable()
        }
        binding.waiting = timeout
        MainThread.handler.postDelayed(timeout, ANSWER_MS)
    }

    /** Открывает читалку, а если она открыта, закрывает. false, если расширение к вкладке не подключено. */
    fun toggle(session: GeckoSession): Boolean {
        val b = sessions[session]
        if (b == null) {
            AppLog.w(TAG, "toggle: сессия не зарегистрирована")
            return false
        }
        if (extension == null) {
            AppLog.w(TAG, "toggle: расширение не готово (состояние $installState, ошибка: ${installError ?: "нет"})")
            return false
        }
        AppLog.i(TAG) { "toggle: запрос" }
        post(b, "toggle")
        return true
    }

    /** Почему [toggle] вернул false: для сообщения пользователю. */
    fun unavailableReason(): String = when (installState) {
        Install.NOT_STARTED, Install.INSTALLING -> "Режим чтения ещё запускается, попробуйте через пару секунд"
        Install.FAILED -> "Режим чтения не установился: ${installError ?: "ошибка"}"
        Install.READY -> "Режим чтения недоступен на этой странице"
    }

    fun close(session: GeckoSession) {
        sessions[session]?.let {
            AppLog.i(TAG) { "close: запрос" }
            post(it, "close")
        }
    }

    /** Новая загрузка в сессии: ответ и команда прежней страницы больше ничего не значат. */
    fun reset(session: GeckoSession) {
        sessions[session]?.let { b ->
            AppLog.i(TAG) { "новая загрузка: сбрасываю состояние (hello=${b.pageReady}, была команда=${b.queued != null})" }
            b.answered()
            b.queued = null
            b.pageReady = false
            b.pending?.let { runCatching { it.complete(JSONObject()) } }
            b.pending = null
        }
    }
}
