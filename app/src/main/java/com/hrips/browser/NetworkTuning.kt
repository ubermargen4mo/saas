package com.hrips.browser

import android.util.Log
import org.mozilla.geckoview.GeckoPreferenceController

/**
 * Настройки сетевого стека Gecko, которые ускоряют повторные соединения.
 * Берём только то, что не раскрывает лишнего: без предсказателя страниц и без предзагрузки DNS ссылок на странице.
 * Ставим в ветку умолчаний; если какого-то префа в этой версии Gecko нет, это просто попадёт в лог.
 */
object NetworkTuning {
    private const val TAG = "NetworkTuning"

    private val prefs: Map<String, Any> = mapOf(
        "network.http.http3.enable" to true,
        "network.http.rcwn.enabled" to true,
        "network.ssl_tokens_cache_enabled" to true,
        "network.early-hints.enabled" to true,
        "network.early-hints.preconnect.enabled" to true,
        "network.http.speculative-parallel-limit" to 6,
        "network.dnsCacheEntries" to 800,
        "network.dnsCacheExpiration" to 300,
        "network.dnsCacheExpirationGracePeriod" to 120,
    )

    fun apply() {
        val branch = GeckoPreferenceController.PREF_BRANCH_DEFAULT
        for ((pref, value) in prefs) {
            val result = when (value) {
                is Boolean -> GeckoPreferenceController.setGeckoPref(pref, value, branch)
                is Int -> GeckoPreferenceController.setGeckoPref(pref, value, branch)
                else -> continue
            }
            result.accept({ }, { e -> Log.w(TAG, "Не удалось выставить $pref", e) })
        }
    }
}
