package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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

    private val web = setOf(
        "http", "https", "about", "data", "blob", "file", "javascript",
        "resource", "moz-extension", "view-source", "ws", "wss", "moz-icon",
    )
    private val direct = setOf("tel", "mailto", "sms", "smsto", "geo")

    /** Возвращает true, если это внешняя ссылка и во вкладке её загружать не нужно. */
    fun handle(uri: String, hasUserGesture: Boolean): Boolean {
        val scheme = Uri.parse(uri).scheme?.lowercase() ?: return false
        if (scheme in web) return false
        if (!hasUserGesture) return true
        val intent = build(uri) ?: return true
        if (scheme in direct) {
            start(intent)
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
        if (!start(req.intent)) {
            val fb = req.fallback?.takeIf(::isSafeFallback)
            if (fb != null) onFail(fb)
            else Toast.makeText(context, "Приложение для этой ссылки не найдено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Toast.makeText(context, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
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
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i
    } catch (e: Exception) {
        null
    }
}
