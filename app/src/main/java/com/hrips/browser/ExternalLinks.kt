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

class ExternalLinks(private val context: Context) {
    var pending by mutableStateOf<ExternalRequest?>(null)
        private set

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

    fun open(req: ExternalRequest, onFail: (String) -> Unit) {
        pending = null
        if (start(req.intent)) return
        val fb = req.fallback?.takeIf(::isSafeFallback)
        if (fb != null) onFail(fb) else Notices.show("Приложение для этой ссылки не найдено")
    }

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
        i.addCategory(Intent.CATEGORY_BROWSABLE)
        i.component = null
        i.selector = null
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (i.scheme.equals("http", true) || i.scheme.equals("https", true))) {
            i.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        }
        i
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "ExternalLinks"

        val WEB = setOf(
            "http", "https", "about", "data", "blob", "file", "javascript",
            "resource", "moz-extension", "view-source", "ws", "wss", "moz-icon",
        )

        val DIRECT = setOf("tel", "mailto", "sms", "smsto", "geo")
    }
}
