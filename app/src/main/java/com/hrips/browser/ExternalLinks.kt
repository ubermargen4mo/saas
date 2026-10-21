package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ExternalRequest(
    val uri: String,
    val intent: Intent,
    val fallback: String?,
    /** Название приложения, если это обычная веб-ссылка, которую умеет открывать установленное приложение. */
    val appLabel: String? = null,
    val web: Boolean = false,
)

class ExternalLinks(private val context: Context, private val openInAppsMode: () -> Int = { MODE_ASK }) {
    var pending by mutableStateOf<ExternalRequest?>(null)
        private set

    fun handle(uri: String, hasUserGesture: Boolean, fromUrl: String? = null): Boolean {
        val scheme = Uri.parse(uri).scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") return handleWeb(uri, hasUserGesture, fromUrl)
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

    /**
     * Обычная ссылка, которую по умолчанию (проверенная app link) открывает установленное приложение:
     * YouTube, Telegram и т.п. Только по нажатию, только при переходе на другой сайт; набранный адрес не трогаем.
     */
    private fun handleWeb(uri: String, hasUserGesture: Boolean, fromUrl: String?): Boolean {
        if (!hasUserGesture || fromUrl.isNullOrBlank()) return false
        val mode = openInAppsMode()
        if (mode == MODE_NEVER || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val target = Uri.parse(uri)
        val host = target.host?.lowercase() ?: return false
        val fromHost = Uri.parse(fromUrl).host?.lowercase()
        if (fromHost != null && sameSite(host, fromHost)) return false
        val pm = context.packageManager
        val (pkg, label) = try {
            val probe = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
            val browsers = query(pm, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE))
                .map { it.activityInfo.packageName }.toSet()
            val app = query(pm, probe).firstOrNull {
                val p = it.activityInfo.packageName
                p != context.packageName && p !in browsers
            } ?: return false
            app.activityInfo.packageName to app.loadLabel(pm).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось найти приложение для ссылки", e)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mode == MODE_ALWAYS) {
            return start(intent) // не вышло: страница откроется в браузере
        }
        pending = ExternalRequest(uri, intent, uri, appLabel = label, web = true)
        return true
    }

    @Suppress("DEPRECATION")
    private fun query(pm: android.content.pm.PackageManager, intent: Intent): List<android.content.pm.ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun sameSite(a: String, b: String): Boolean {
        if (a == b || a.endsWith(".$b") || b.endsWith(".$a")) return true
        fun root(h: String) = h.split('.').takeLast(2).joinToString(".")
        return root(a) == root(b)
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

    companion object {
        const val MODE_NEVER = 0
        const val MODE_ASK = 1
        const val MODE_ALWAYS = 2
        private const val TAG = "ExternalLinks"

        private val WEB = setOf(
            "http", "https", "about", "data", "blob", "file", "javascript",
            "resource", "moz-extension", "view-source", "ws", "wss", "moz-icon",
        )

        private val DIRECT = setOf("tel", "mailto", "sms", "smsto", "geo")
    }
}
