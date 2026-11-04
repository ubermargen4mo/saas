package com.hrips.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

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
            AppLog.w("SitePermissions", "не удалось прочитать сохранённые разрешения сайтов", e)
        }
    }

    fun get(origin: String, kind: String): Boolean? {
        val key = normalizeOrigin(origin)
        saved.firstOrNull { it.origin == key && it.kind == kind }?.let { return it.allowed }

        val host = Uri.parse(key).host ?: return null
        return saved.firstOrNull { it.origin == legacyKey(host) && it.kind == kind }?.allowed
    }

    fun set(origin: String, kind: String, allowed: Boolean) {
        saved.add(Saved(removeSaved(origin, kind), kind, allowed))
        save()
    }

    fun forget(origin: String, kind: String) {
        removeSaved(origin, kind)
        save()
    }

    private fun removeSaved(origin: String, kind: String): String {
        val key = normalizeOrigin(origin)
        val host = Uri.parse(key).host
        saved.removeAll {
            it.kind == kind && (it.origin == key || (host != null && it.origin == legacyKey(host)))
        }
        return key
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

        private fun normalizeOrigin(raw: String): String = Uri.parse(raw.trim()).webOrigin() ?: raw.trim().lowercase()
    }
}

internal fun Uri.webOrigin(): String? {
    val s = scheme?.lowercase().orEmpty()
    val h = host?.lowercase().orEmpty()
    if (s.isBlank() || h.isBlank()) return null
    val p = when {
        port == -1 -> null
        s == "http" && port == 80 -> null
        s == "https" && port == 443 -> null
        else -> port
    }?.let { ":$it" }.orEmpty()
    return "$s://$h$p"
}
