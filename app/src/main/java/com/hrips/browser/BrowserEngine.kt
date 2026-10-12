package com.hrips.browser

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

/**
 * Узкий шлюз к операциям движка, не привязанным к вкладке (запросы с cookies, очистка данных, активность вкладки
 * для расширений). Browser и Compose-код не зависят от деталей executor и storage.
 */
class BrowserEngine(private val runtime: GeckoRuntime) {
    private companion object {
        const val TAG = "BrowserEngine"
    }

    // Запросы могут прийти из разных потоков (загрузки, меню), поэтому обычная потокобезопасная ленивость
    private val executor by lazy { GeckoWebExecutor(runtime) }

    /** Запрос через движок: cookies и referer те же, что у страницы. [onDone] получает null, если запрос не удался. */
    fun fetch(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        onDone: (WebResponse?) -> Unit,
    ) = request(uri, referrer, incognito, range = null, ifRange = null, onDone = onDone)

    /**
     * Запрос с заголовками для докачки: [range] ("bytes=1000-") и [ifRange] (ETag или дата изменения файла).
     * Идёт через движок, поэтому cookies и referer те же, что у страницы, с которой начали загрузку.
     * If-Range без Range не имеет смысла и не отправляется.
     */
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
            // Например, адрес, который WebRequest не принимает
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
