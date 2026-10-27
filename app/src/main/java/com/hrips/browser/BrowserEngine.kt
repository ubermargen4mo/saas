package com.hrips.browser

import android.util.Log
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
    }

    private val executor by lazy { GeckoWebExecutor(runtime) }

    private val dnsThrottle = Throttle(WARM_TTL_MS, MAX_WARMED_HOSTS)
    private val connectThrottle = Throttle(CONNECT_TTL_MS, MAX_WARMED_HOSTS)
    private val ipLike = Regex("^[0-9.]+$|^\\[.*]$")

    private fun cleanHost(host: String): String? {
        val h = host.trim().lowercase().trimEnd('.')
        return h.takeIf { it.isNotEmpty() && it.contains('.') && !ipLike.matches(it) }
    }

    /** Заранее резолвит имя сайта: к моменту открытия страницы ответ DNS уже в кэше. Вызывать с основного потока. */
    fun warm(host: String) {
        val h = cleanHost(host) ?: return
        if (!dnsThrottle.allow(h, android.os.SystemClock.elapsedRealtime())) return
        try {
            executor.resolve(h).accept({ }, { })
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прогреть DNS для $h", e)
        }
    }

    /**
     * Заранее открывает соединение с сайтом целиком: DNS, TCP и TLS (`GeckoWebExecutor.speculativeConnect`, как в Fenix).
     * [warm] делает только DNS, и при открытии страницы рукопожатие всё равно проходило заново (1-3 круговые задержки).
     * Неиспользованное соединение GeckoView закрывает сам. Вызывать с основного потока и только там, где человек
     * с большой вероятностью откроет сайт (поисковик при открытии строки поиска, набранный адрес).
     */
    fun connect(host: String) {
        val h = cleanHost(host) ?: return
        if (!connectThrottle.allow(h, android.os.SystemClock.elapsedRealtime())) return
        try {
            executor.speculativeConnect("https://$h")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось заранее подключиться к $h", e)
        }
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
                while (last.size > max) {
                    val oldest = last.minByOrNull { it.value }?.key ?: break
                    last.remove(oldest)
                }
            }
            return true
        }
    }

    fun fetch(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        onDone: (WebResponse?) -> Unit,
    ) = request(uri, referrer, incognito, range = null, ifRange = null, onDone = onDone)

    fun fetchRange(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String?,
        ifRange: String?,
        onDone: (WebResponse?) -> Unit,
    ) = request(uri, referrer, incognito, range, ifRange, onDone)

    private fun request(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String?,
        ifRange: String?,
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
            val flags = if (incognito) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
            executor.fetch(request, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось отправить запрос", e)
            null
        }
        if (result == null) {
            onDone(null)
            return
        }
        result.accept({ onDone(it) }, { onDone(null) })
    }

    fun clearData(cookies: Boolean, cache: Boolean, onDone: () -> Unit) {
        var flags = 0L
        if (cookies) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES
        if (cache) flags = flags or StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE
        if (flags == 0L) {
            onDone()
            return
        }
        runtime.storageController.clearData(flags).accept({ onDone() }, { onDone() })
    }

    fun setTabActive(session: GeckoSession, active: Boolean) {
        runtime.webExtensionController.setTabActive(session, active)
    }
}
