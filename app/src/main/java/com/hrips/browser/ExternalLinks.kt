package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ExternalRequest(val uri: String, val intent: Intent, val fallback: String?)

/**
 * Ссылки не для браузера: tel:, mailto:, intent:, market: и схемы приложений.
 * Раньше такие переходы молча не работали. Теперь: tel/mailto/sms/geo открываются сразу,
 * остальное спрашивает подтверждение. Переходы без нажатия пользователя (редиректы страниц) игнорируются.
 */
class ExternalLinks(private val context: Context) {
    var pending by mutableStateOf<ExternalRequest?>(null)
        private set

    /** Возвращает true, если это внешняя ссылка и во вкладке её загружать не нужно. */
    fun handle(uri: String, hasUserGesture: Boolean): Boolean {
        val scheme = Uri.parse(uri).scheme?.lowercase() ?: return false
        if (scheme in WEB) return false
        if (!hasUserGesture) return true
        val intent = build(uri)
        if (intent == null) {
            Notices.show("Не удалось открыть ссылку")
            return true
        }
        if (scheme in DIRECT) {
            if (!start(intent)) Notices.show("Нет приложения для открытия ссылки")
        } else {
            pending = ExternalRequest(uri, intent, intent.getStringExtra("browser_fallback_url"))
        }
        return true
    }

    fun dismiss() {
        pending = null
    }

    /** onFail получает запасной адрес страницы (если он есть в intent:// ссылке). */
    fun open(req: ExternalRequest, onFail: (String) -> Unit) {
        pending = null
        if (start(req.intent)) return
        val fb = req.fallback?.takeIf(::isSafeFallback)
        if (fb != null) onFail(fb) else Notices.show("Приложение для этой ссылки не найдено")
    }

    /** Только запускает, без уведомлений: что сказать человеку, решает вызывающий (иначе при запасном адресе было бы два сообщения). */
    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось открыть внешнюю ссылку", e)
        false
    }

    private fun isSafeFallback(uri: String): Boolean = runCatching {
        val u = Uri.parse(uri.trim())
        (u.scheme.equals("https", true) || u.scheme.equals("http", true)) && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun build(uri: String): Intent? = try {
        val i = if (uri.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }
        // Как в Chrome и Firefox: страница не должна указывать конкретный компонент или селектор
        i.addCategory(Intent.CATEGORY_BROWSABLE)
        i.component = null
        i.selector = null
        // Флаги из intent:// ссылки не берём совсем. Иначе страница могла бы выдать чужому приложению доступ к нашим
        // файлам (FLAG_GRANT_*_URI_PERMISSION вместе с content:// адресом нашего FileProvider).
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // Ссылка вида intent://...;scheme=https без подходящего приложения должна уходить в запасной адрес,
        // а не снова в браузер (в том числе в нас) через системный выбор
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (i.scheme.equals("http", true) || i.scheme.equals("https", true))) {
            i.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        }
        i
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "ExternalLinks"

        /** Схемы, которые грузит сам движок. */
        val WEB = setOf(
            "http", "https", "about", "data", "blob", "file", "javascript",
            "resource", "moz-extension", "view-source", "ws", "wss", "moz-icon",
        )

        /** Схемы, которые безопасно открыть сразу: система покажет набор номера или черновик письма, ничего не отправляя. */
        val DIRECT = setOf("tel", "mailto", "sms", "smsto", "geo")
    }
}
