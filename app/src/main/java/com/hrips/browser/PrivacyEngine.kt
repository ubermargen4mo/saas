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

/**
 * Сетевая приватность движка: HTTPS-only, защищённый DNS (DoH), Global Privacy Control
 * и удаление известных tracking-параметров из URL.
 * Значения лежат в Store, сюда они попадают через [apply]. Вызывается при создании движка и при каждом
 * изменении настройки. Каждая настройка движка вынесена в свою функцию: если какой-то метод GeckoView
 * в вашей версии называется иначе, правится (или временно закомментируется) ровно одна строка.
 */
object PrivacyEngine {
    const val CUSTOM = "custom"

    // Режимы HTTPS-only (Store.httpsMode)
    const val HTTPS_OFF = 0
    const val HTTPS_PRIVATE = 1
    const val HTTPS_ALL = 2

    // Режимы DoH (Store.dohMode)
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

    /** Заголовок Sec-GPC: 1 и navigator.globalPrivacyControl. */
    private fun applyGpc(s: GeckoRuntimeSettings, on: Boolean) {
        s.setGlobalPrivacyControl(on)
    }

    /** Без HTTPS движок показывает страницу-предупреждение, открыть сайт можно только после подтверждения. */
    private fun applyHttpsOnly(s: GeckoRuntimeSettings, mode: Int) {
        s.setAllowInsecureConnections(
            when (mode) {
                HTTPS_PRIVATE -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
                HTTPS_ALL -> GeckoRuntimeSettings.HTTPS_ONLY
                else -> GeckoRuntimeSettings.ALLOW_ALL
            },
        )
    }

    /** TRR = Trusted Recursive Resolver, так в Gecko называется DoH. Первым ставим адрес, потом режим. */
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

    /** Удаляет известные tracking-параметры из URL в обычных и приватных вкладках. */
    private fun applyQueryStripping(s: GeckoRuntimeSettings, on: Boolean) {
        val cb = s.contentBlocking
        cb.setQueryParameterStrippingEnabled(on)
        cb.setQueryParameterStrippingPrivateBrowsingEnabled(on)
    }

    /** Подходит ли строка как адрес DoH-сервера: только https и непустой хост. */
    fun isValidDoh(text: String): Boolean {
        val u = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return false
        return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
    }

    /** Запрос A для www.example.com из RFC 8484 (base64url без «=»). */
    private const val SAMPLE_QUERY = "AAABAAABAAAAAAAAA3d3dwdleGFtcGxlA2NvbQAAAQAB"

    /**
     * Проверка своего сервера перед сохранением: GET с DNS-запросом,
     * ожидаем 200 и тип application/dns-message. Сеть в фоновом потоке, [done] вызывается в главном.
     * Адрес самого сервера разрешается обычным DNS системы, иначе не с чего начать.
     */
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
