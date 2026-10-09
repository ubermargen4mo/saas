package com.hrips.browser

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

/**
 * Narrow gateway for Gecko runtime operations that are not tied to a particular tab.
 * Keeps Browser orchestration and Compose code from depending on low-level executor/storage details.
 */
class BrowserEngine(private val runtime: GeckoRuntime) {
    private val executor by lazy(LazyThreadSafetyMode.NONE) { GeckoWebExecutor(runtime) }

    fun fetch(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        onDone: (WebResponse?) -> Unit,
    ) {
        val request = WebRequest.Builder(uri).apply {
            if (!referrer.isNullOrBlank()) referrer(referrer)
        }.build()
        val flags = if (incognito) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val result = runCatching { executor.fetch(request, flags) }.getOrNull() ?: run {
            onDone(null)
            return
        }
        result.accept({ onDone(it) }, { onDone(null) })
    }

    /**
     * Запрос с заголовками для докачки: [range] ("bytes=1000-") и [ifRange] (ETag или дата изменения файла).
     * Идёт через движок, поэтому cookies и referer те же, что у страницы, с которой начали загрузку.
     */
    fun fetchRange(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String?,
        ifRange: String?,
        onDone: (WebResponse?) -> Unit,
    ) {
        val request = WebRequest.Builder(uri).apply {
            if (!referrer.isNullOrBlank()) referrer(referrer)
            if (!range.isNullOrBlank()) header("Range", range)
            if (!range.isNullOrBlank() && !ifRange.isNullOrBlank()) header("If-Range", ifRange)
        }.build()
        val flags = if (incognito) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val result = runCatching { executor.fetch(request, flags) }.getOrNull() ?: run {
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
