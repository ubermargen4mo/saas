package com.hrips.browser

import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

data class ReaderPrefs(val font: String = "serif", val theme: String = "auto")

object ReaderMode {
    private const val NATIVE_APP = "hripsReader"
    const val EXTENSION_ID = "reader@hrips.app"

    private const val TAG = "Reader"

    private const val ANSWER_MS = 8_000L

    private class Binding(
        val onState: (active: Boolean) -> Unit,
        val onFail: () -> Unit,
        val onUnavailable: () -> Unit,
        val onPrefs: (ReaderPrefs) -> Unit,
    ) {
        var port: WebExtension.Port? = null

        var active = false

        var waiting: Runnable? = null

        var pageReady = false

        var postedAt = 0L

        var seq = 0

        fun answered() {
            waiting?.let { MainThread.handler.removeCallbacks(it) }
            waiting = null
        }
    }

    private var action: WebExtension.Action? = null

    fun onAction(a: WebExtension.Action) {
        action = a
        AppLog.i(TAG) { "действие расширения получено" }
    }

    private val sessions = java.util.WeakHashMap<GeckoSession, Binding>()
    private var extension: WebExtension? = null

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

    fun register(
        session: GeckoSession,
        onState: (active: Boolean) -> Unit,
        onFail: () -> Unit,
        onUnavailable: () -> Unit = {},
        onPrefs: (ReaderPrefs) -> Unit = {},
    ) {
        val binding = Binding(onState, onFail, onUnavailable, onPrefs)
        sessions[session] = binding
        val ext = extension
        AppLog.i(TAG) { "сессия зарегистрирована (расширение ${if (ext != null) "готово, подключаю" else "ещё не готово: $installState"})" }
        if (ext != null) attach(session, binding, ext)
    }

    fun forget(session: GeckoSession) {
        sessions.remove(session)?.let { b ->
            b.answered()
            b.port = null
        }
    }

    private fun attach(session: GeckoSession, binding: Binding, ext: WebExtension) {
        try {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        AppLog.i(TAG) { "канал страницы подключён" }
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {}
                            override fun onDisconnect(port: WebExtension.Port) {
                                MainThread.handler.post { if (binding.port === port) binding.port = null }
                                AppLog.i(TAG) { "канал страницы закрыт" }
                            }
                        })
                        MainThread.handler.post { binding.port = port }
                    }

                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val json = message as? JSONObject
                        if (json == null) {
                            AppLog.w(TAG, "сообщение не JSON: ${message.javaClass.simpleName} (от $nativeApp)")
                            return null
                        }
                        return when (val type = json.optString("t")) {
                            "hello" -> {
                                MainThread.handler.post { binding.pageReady = true }
                                AppLog.i(TAG) { "страница вышла на связь (hello)" }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "log" -> {
                                AppLog.i(TAG) { "страница: ${json.optString("m")}" }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "state" -> {
                                val active = json.optBoolean("active")
                                AppLog.i(TAG) { "страница сообщила: читалка ${if (active) "открыта" else "закрыта"}" }
                                MainThread.handler.post { binding.active = active; binding.answered(); binding.onState(active) }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "fail" -> {
                                AppLog.w(TAG, "страница сообщила: статью выделить не удалось (${json.optString("why")})")
                                MainThread.handler.post { binding.answered(); binding.onFail() }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "prefs" -> {
                                val prefs = ReaderPrefs(json.optString("font", "serif"), json.optString("theme", "auto"))
                                MainThread.handler.post { binding.onPrefs(prefs) }
                                GeckoResult.fromValue(JSONObject())
                            }
                            "poll" -> {
                                GeckoResult.fromValue(JSONObject())
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

    private fun post(binding: Binding, cmd: String): Boolean {
        val n = ++binding.seq
        binding.postedAt = android.os.SystemClock.elapsedRealtime()
        val a = action
        if (a == null) {
            AppLog.w(TAG, "команда #$n «$cmd»: у расширения нет действия (клик отправить некуда)")
            return false
        }
        AppLog.i(TAG) { "команда #$n «$cmd»: клик по действию (hello=${binding.pageReady})" }
        runCatching { a.click() }.onFailure {
            AppLog.w(TAG, "не удалось кликнуть по действию «$cmd»", it)
            return false
        }
        binding.answered()
        val timeout = Runnable {
            binding.waiting = null
            AppLog.w(TAG, "команда #$n «$cmd»: страница не ответила за ${ANSWER_MS / 1000} с (hello=${binding.pageReady})")
            binding.onUnavailable()
        }
        binding.waiting = timeout
        MainThread.handler.postDelayed(timeout, ANSWER_MS)
        return true
    }

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
        return post(b, "toggle")
    }

    fun unavailableReason(): String = when (installState) {
        Install.NOT_STARTED, Install.INSTALLING -> "Режим чтения ещё запускается, попробуйте через пару секунд"
        Install.FAILED -> "Режим чтения не установился: ${installError ?: "ошибка"}"
        Install.READY -> "Режим чтения недоступен на этой странице"
    }

    fun setLook(session: GeckoSession, font: String? = null, theme: String? = null): Boolean {
        val port = sessions[session]?.port ?: run {
            AppLog.w(TAG, "setLook: канал к странице не поднялся")
            return false
        }
        val msg = JSONObject().put("cmd", "set")
        if (font != null) msg.put("font", font)
        if (theme != null) msg.put("theme", theme)
        return runCatching { port.postMessage(msg) }.onFailure { AppLog.w(TAG, "setLook: не отправилось", it) }.isSuccess
    }

    fun close(session: GeckoSession) {
        sessions[session]?.let {
            if (it.active) {
                AppLog.i(TAG) { "close: запрос" }
                post(it, "toggle")
            }
        }
    }

    fun reset(session: GeckoSession) {
        sessions[session]?.let { b ->
            AppLog.i(TAG) { "новая загрузка: сбрасываю состояние (hello=${b.pageReady})" }
            b.answered()
            b.pageReady = false
            b.active = false
        }
    }
}
