package com.hrips.browser

import android.os.SystemClock
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

class BrowserEngine(private val runtime: GeckoRuntime) {
    private companion object {
        const val TAG = "BrowserEngine"
        const val WARM_TTL_MS = 60_000L
        const val CONNECT_TTL_MS = 20_000L
        const val MAX_WARMED_HOSTS = 64
        val IP_LIKE = Regex("^[0-9.]+$|^\\[.*]$")
    }

    private val executor by lazy { GeckoWebExecutor(runtime) }
    private val dnsThrottle = Throttle(WARM_TTL_MS, MAX_WARMED_HOSTS)
    private val connectThrottle = Throttle(CONNECT_TTL_MS, MAX_WARMED_HOSTS)

    /** Заранее резолвит имя сайта: к открытию страницы ответ DNS уже в кэше. Вызывать с основного потока. */
    fun warm(host: String) = prewarm(host, dnsThrottle, "прогреть DNS для") { executor.resolve(it).accept({ }, { }) }

    /**
     * Заранее открывает соединение целиком: DNS, TCP и TLS (как в Fenix). [warm] делает только DNS, а рукопожатие
     * при открытии страницы стоит 1-3 круговые задержки. Неиспользованное соединение GeckoView закрывает сам.
     * Только основной поток и только там, где сайт скорее всего откроют (поисковик, набранный адрес).
     */
    fun connect(host: String) = prewarm(host, connectThrottle, "заранее подключиться к") { executor.speculativeConnect("https://$it") }

    private inline fun prewarm(host: String, throttle: Throttle, action: String, run: (String) -> Unit) {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty() || !h.contains('.') || IP_LIKE.matches(h)) return
        if (!throttle.allow(h, SystemClock.elapsedRealtime())) return
        try {
            run(h)
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось $action $h", e)
        }
    }

    fun fetch(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String? = null,
        ifRange: String? = null,
        onDone: (WebResponse?) -> Unit,
    ) {
        val result = try {
            val request = WebRequest.Builder(uri).apply {
                if (!referrer.isNullOrBlank()) referrer(referrer)
                if (!range.isNullOrBlank()) {
                    header("Range", range)
                    if (!ifRange.isNullOrBlank()) header("If-Range", ifRange)
                }
            }.build()
            executor.fetch(request, if (incognito) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE)
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось отправить запрос", e)
            null
        }
        if (result == null) onDone(null) else result.accept({ onDone(it) }, { onDone(null) })
    }

    fun clearData(cookies: Boolean, cache: Boolean, onDone: () -> Unit) {
        var flags = 0L
        if (cookies) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES
        if (cache) flags = flags or StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE
        if (flags == 0L) onDone() else runtime.storageController.clearData(flags).accept({ onDone() }, { onDone() })
    }

    fun setTabActive(session: GeckoSession, active: Boolean) {
        runtime.webExtensionController.setTabActive(session, active)
    }

    /** Не чаще одного раза за [ttlMs] на ключ; помнит не больше [max] ключей (вытесняет самые старые). */
    private class Throttle(private val ttlMs: Long, private val max: Int) {
        private val last = HashMap<String, Long>()

        fun allow(key: String, now: Long): Boolean {
            val prev = last[key]
            if (prev != null && now - prev < ttlMs) return false
            last[key] = now
            if (last.size > max) {
                last.entries.removeAll { now - it.value >= ttlMs }
                while (last.size > max) last.remove(last.minByOrNull { it.value }?.key ?: break)
            }
            return true
        }
    }
}
