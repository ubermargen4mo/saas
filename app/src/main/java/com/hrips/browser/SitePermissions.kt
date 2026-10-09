package com.hrips.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Запомненные решения для сайтов по камере и микрофону.
 *
 * Новые записи ключуются по origin (scheme + host + port), а старые записи из версий,
 * где сохранялся только host, временно читаются как legacy fallback. При следующем изменении
 * записи legacy-вариант удаляется, чтобы постепенно перейти на безопасную origin-aware модель.
 */
class SitePermissions(context: Context) {
    class Saved(val origin: String, val kind: String, val allowed: Boolean) {
        val host: String
            get() = runCatching {
                val uri = Uri.parse(origin)
                val value = uri.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
                value + port
            }.getOrNull() ?: origin.removePrefix(LEGACY_PREFIX)
    }

    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)
    val saved = mutableStateListOf<Saved>()

    init {
        try {
            val arr = JSONArray(prefs.getString("sitePerms", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val raw = o.getString("h")
                saved.add(
                    Saved(
                        origin = if (raw.contains("://")) normalizeOrigin(raw) else legacyKey(raw),
                        kind = o.getString("k"),
                        allowed = o.getBoolean("a"),
                    )
                )
            }
        } catch (e: Exception) {
            // повреждённые данные: начинаем с чистого списка
        }
    }

    /** true = разрешено, false = запрещено, null = ещё не решали */
    fun get(origin: String, kind: String): Boolean? {
        val key = normalizeOrigin(origin)
        saved.firstOrNull { it.origin == key && it.kind == kind }?.let { return it.allowed }

        // Совместимость со старыми host-only записями. После изменения решения legacy запись удаляется.
        val host = Uri.parse(key).host ?: return null
        return saved.firstOrNull { it.origin == legacyKey(host) && it.kind == kind }?.allowed
    }

    fun set(origin: String, kind: String, allowed: Boolean) {
        val key = normalizeOrigin(origin)
        val host = Uri.parse(key).host
        saved.removeAll {
            it.kind == kind && (it.origin == key || (host != null && it.origin == legacyKey(host)))
        }
        saved.add(Saved(key, kind, allowed))
        save()
    }

    fun forget(origin: String, kind: String) {
        val key = normalizeOrigin(origin)
        val host = Uri.parse(key).host
        saved.removeAll {
            it.kind == kind && (it.origin == key || (host != null && it.origin == legacyKey(host)))
        }
        save()
    }

    fun clear() {
        saved.clear()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        saved.forEach { arr.put(JSONObject().put("h", it.origin).put("k", it.kind).put("a", it.allowed)) }
        prefs.edit().putString("sitePerms", arr.toString()).apply()
    }

    companion object {
        const val CAMERA = "camera"
        const val MIC = "mic"
        private const val LEGACY_PREFIX = "legacy://"

        fun label(kind: String) = when (kind) {
            CAMERA -> "Камера"
            MIC -> "Микрофон"
            else -> kind
        }

        private fun legacyKey(host: String) = "$LEGACY_PREFIX${host.trim().lowercase().removePrefix("www.")}"

        private fun normalizeOrigin(raw: String): String {
            val uri = Uri.parse(raw.trim())
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme.isBlank() || host.isBlank()) return raw.trim().lowercase()
            val normalizedPort = when {
                uri.port == -1 -> -1
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            val port = normalizedPort.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
            return "$scheme://$host$port"
        }
    }
}
