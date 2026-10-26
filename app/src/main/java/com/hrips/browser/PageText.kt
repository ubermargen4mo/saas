package com.hrips.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Текст открытых страниц для поиска по вкладкам. Встроенное расширение (`assets/pagetext`) присылает видимый текст
 * страницы, мы держим его только в памяти (на диск не пишется, при закрытии приложения исчезает). Принимает и
 * приватные вкладки: они остаются в памяти так же, как и сами страницы.
 *
 * Всё, кроме [install], вызывается с главного потока.
 */
object PageText {
    private const val NATIVE_APP = "hripsPageText"
    private const val EXTENSION_ID = "pagetext@hrips.app"
    private const val SNIPPET_BEFORE = 36
    private const val SNIPPET_AFTER = 70

    private val entries = HashMap<String, String>()
    private val sessions = java.util.WeakHashMap<GeckoSession, String>()
    private var extension: WebExtension? = null

    /** Растёт при любом изменении индекса: по нему пересчитывается результат поиска. */
    var version by mutableIntStateOf(0)
        private set

    fun install(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/pagetext/", EXTENSION_ID).accept(
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
            { AppLog.i("PageText") { "расширение не загрузилось" } },
        )
    }

    private fun ready(ext: WebExtension) {
        extension = ext
        sessions.entries.toList().forEach { (s, id) -> attach(s, id, ext) }
    }

    /** Подписывает сессию вкладки на сообщения расширения. Если расширение ещё не готово, подпишется позже. */
    fun register(session: GeckoSession, tabId: String) {
        sessions[session] = tabId
        extension?.let { attach(session, tabId, it) }
    }

    private fun attach(session: GeckoSession, tabId: String, ext: WebExtension) {
        runCatching {
            session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val text = (message as? JSONObject)?.optString("text").orEmpty()
                        if (text.isNotEmpty()) {
                            entries[tabId] = text
                            version++
                        }
                        return null
                    }
                },
                NATIVE_APP,
            )
        }
    }

    /** Новая страница в этой вкладке: старый текст больше не её. */
    fun forget(tabId: String) {
        if (entries.remove(tabId) != null) version++
    }

    /** Оставляет текст только открытых вкладок. */
    fun retain(ids: Set<String>) {
        if (entries.keys.retainAll(ids)) version++
    }

    /** Есть ли уже текст этой вкладки (иначе искать по содержимому нечем). */
    fun has(tabId: String): Boolean {
        version
        return entries.containsKey(tabId)
    }

    private fun words(query: String) = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Все слова запроса встречаются в тексте страницы, без учёта регистра. */
    fun matches(tabId: String, query: String): Boolean {
        version
        val text = entries[tabId] ?: return false
        val ws = words(query)
        return ws.isNotEmpty() && ws.all { text.contains(it, ignoreCase = true) }
    }

    /** Фрагмент текста вокруг первого найденного слова запроса. */
    fun snippet(tabId: String, query: String): String? {
        version
        val text = entries[tabId] ?: return null
        val ws = words(query)
        val at = ws.firstNotNullOfOrNull { w -> text.indexOf(w, ignoreCase = true).takeIf { it >= 0 } } ?: return null
        val from = (at - SNIPPET_BEFORE).coerceAtLeast(0)
        val to = (at + SNIPPET_AFTER).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to).trim() + (if (to < text.length) "…" else "")
    }
}
