package com.hrips.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import javax.net.ssl.HttpsURLConnection
import java.net.URL

class DohProvider(val id: String, val name: String, val note: String, val uri: String)

object PrivacyEngine {
    const val CUSTOM = "custom"

    const val HTTPS_OFF = 0
    const val HTTPS_PRIVATE = 1
    const val HTTPS_ALL = 2

    const val DOH_OFF = 0
    const val DOH_AUTO = 1
    const val DOH_STRICT = 2

    val dohProviders = listOf(
        DohProvider("cloudflare", "Cloudflare", "Быстрый, тот же, что по умолчанию в Firefox", "https://mozilla.cloudflare-dns.com/dns-query"),
        DohProvider("quad9", "Quad9", "Блокирует известные вредоносные домены", "https://dns.quad9.net/dns-query"),
        DohProvider("adguard", "AdGuard DNS", "Блокирует рекламные и трекерные домены", "https://dns.adguard-dns.com/dns-query"),
        DohProvider("google", "Google Public DNS", "Быстрый, но запросы видит Google", "https://dns.google/dns-query"),
    )

    fun apply(runtime: GeckoRuntime, store: Store) {
        val settings = runtime.settings
        applyGpc(settings, store.gpc)
        applyHttpsOnly(settings, store.httpsMode)
        applyDoh(settings, store.dohMode, store.dohUri())
        applyQueryStripping(settings, store.stripTrackingParams)
    }

    private fun applyGpc(s: GeckoRuntimeSettings, on: Boolean) {
        s.setGlobalPrivacyControl(on)
    }

    private fun applyHttpsOnly(s: GeckoRuntimeSettings, mode: Int) {
        s.setAllowInsecureConnections(
            when (mode) {
                HTTPS_PRIVATE -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
                HTTPS_ALL -> GeckoRuntimeSettings.HTTPS_ONLY
                else -> GeckoRuntimeSettings.ALLOW_ALL
            },
        )
    }

    private fun applyDoh(s: GeckoRuntimeSettings, mode: Int, uri: String?) {
        if (mode == DOH_OFF || uri == null) {
            s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            return
        }
        s.setTrustedRecursiveResolverUri(uri)
        s.setTrustedRecursiveResolverMode(
            if (mode == DOH_STRICT) GeckoRuntimeSettings.TRR_MODE_ONLY else GeckoRuntimeSettings.TRR_MODE_FIRST,
        )
    }

    private fun applyQueryStripping(s: GeckoRuntimeSettings, on: Boolean) {
        val cb = s.contentBlocking
        cb.setQueryParameterStrippingEnabled(on)
        cb.setQueryParameterStrippingPrivateBrowsingEnabled(on)
    }

    fun isValidDoh(text: String): Boolean {
        val u = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return false
        return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
    }

    private const val SAMPLE_QUERY = "AAABAAABAAAAAAAAA3d3dwdleGFtcGxlA2NvbQAAAQAB"

    fun probe(uri: String, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (!AppExecutors.tryExecute {
            var ok = false
            try {
                val sep = if ('?' in uri) '&' else '?'
                val c = URL("$uri${sep}dns=$SAMPLE_QUERY").openConnection() as HttpsURLConnection
                try {
                    c.connectTimeout = 5000
                    c.readTimeout = 5000
                    c.instanceFollowRedirects = false
                    c.useCaches = false
                    c.requestMethod = "GET"
                    c.setRequestProperty("Accept", "application/dns-message")
                    ok = c.responseCode == HttpsURLConnection.HTTP_OK &&
                        c.contentLength <= 4096 &&
                        (c.contentType ?: "").substringBefore(';').trim().equals("application/dns-message", ignoreCase = true)
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                ok = false
            }
            main.post { done(ok) }
        }) {
            main.post { done(false) }
        }
    }
}
