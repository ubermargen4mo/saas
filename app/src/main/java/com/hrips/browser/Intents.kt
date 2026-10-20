package com.hrips.browser

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import mozilla.components.browser.state.state.ExternalPackage
import mozilla.components.browser.state.state.PackageCategory
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.EngineSession

/**
 * Приём ссылок и поисковых запросов из других приложений: ACTION_VIEW (в том числе из Custom Tabs),
 * «Поделиться», поиск и «Искать» из выделенного текста.
 */
object Intents {
    /** @return true, если интент был ссылкой/запросом и для него открыта вкладка. */
    fun handle(activity: Activity, c: Components, intent: Intent): Boolean {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val caller = callerOf(activity)

        val (url, source) = when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.dataString?.trim().orEmpty()
                if (data.isEmpty()) return false
                data to SessionState.Source.External.ActionView(caller)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) return false
                SearchEngines.resolve(activity, firstLine(text)) to SessionState.Source.External.ActionSend(caller)
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
                if (query.isEmpty()) return false
                SearchEngines.searchUrl(activity, query) to SessionState.Source.External.ActionSearch(caller)
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val query = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                if (query.isEmpty()) return false
                SearchEngines.searchUrl(activity, query) to SessionState.Source.External.ActionSearch(caller)
            }
            else -> return false
        }

        val id = c.tabsUseCases.addTab(
            url = url,
            selectTab = true,
            startLoading = true,
            flags = EngineSession.LoadUrlFlags.external(),
            source = source,
            private = false,
        )
        c.externalTabs.add(id)
        return true
    }

    private fun firstLine(text: String): String = text.lineSequence().first().trim()

    /** Приложение, из которого пришла ссылка (нужно, чтобы ссылка не «отскакивала» обратно в него). */
    private fun callerOf(activity: Activity): ExternalPackage? {
        val referrer = activity.referrer ?: return null
        if (referrer.scheme != "android-app") return null
        val pkg = referrer.host ?: return null
        if (pkg == activity.packageName) return null
        return ExternalPackage(pkg, PackageCategory.UNKNOWN)
    }
}
