package com.hrips.browser

import android.util.Log
import org.mozilla.geckoview.GeckoPreferenceController

object NetworkTuning {
    private const val TAG = "NetworkTuning"

    private val prefs: Map<String, Any> = mapOf(
        "network.http.http3.enable" to true,
        "network.http.rcwn.enabled" to true,
        "network.ssl_tokens_cache_enabled" to true,
        "network.early-hints.enabled" to true,
        "network.early-hints.preconnect.enabled" to true,
        "network.http.speculative-parallel-limit" to 6,
        "network.http.pacing.requests.enabled" to false,
        "network.dnsCacheEntries" to 800,
        "network.dnsCacheExpiration" to 300,
        "network.dnsCacheExpirationGracePeriod" to 120,
    )

    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
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
