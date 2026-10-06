package com.hrips.browser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Запомненные решения для сайтов по камере и микрофону. Движок такие запросы (getUserMedia)
 * сам не сохраняет, поэтому хранится здесь. Геолокацию и уведомления запоминает сам Gecko,
 * они читаются и сбрасываются через StorageController (см. SitePermissionsSheet).
 * В приватных вкладках ничего не запоминается.
 */
class SitePermissions(context: Context) {
    class Saved(val host: String, val kind: String, val allowed: Boolean)

    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)
    val saved = mutableStateListOf<Saved>()

    init {
        try {
            val arr = JSONArray(prefs.getString("sitePerms", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                saved.add(Saved(o.getString("h"), o.getString("k"), o.getBoolean("a")))
            }
        } catch (e: Exception) {
            // повреждённые данные: начинаем с чистого списка
        }
    }

    /** true = разрешено, false = запрещено, null = ещё не решали */
    fun get(host: String, kind: String): Boolean? =
        saved.firstOrNull { it.host == host && it.kind == kind }?.allowed

    fun set(host: String, kind: String, allowed: Boolean) {
        saved.removeAll { it.host == host && it.kind == kind }
        saved.add(Saved(host, kind, allowed))
        save()
    }

    fun forget(host: String, kind: String) {
        saved.removeAll { it.host == host && it.kind == kind }
        save()
    }

    fun clear() {
        saved.clear()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        saved.forEach { arr.put(JSONObject().put("h", it.host).put("k", it.kind).put("a", it.allowed)) }
        prefs.edit().putString("sitePerms", arr.toString()).apply()
    }

    companion object {
        const val CAMERA = "camera"
        const val MIC = "mic"

        fun label(kind: String) = when (kind) {
            CAMERA -> "Камера"
            MIC -> "Микрофон"
            else -> kind
        }
    }
}
