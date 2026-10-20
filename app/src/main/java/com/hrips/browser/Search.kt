package com.hrips.browser

import android.content.Context
import android.net.Uri
import mozilla.components.support.ktx.kotlin.isUrl

/** Поисковые системы и превращение введённого текста в адрес. */
object SearchEngines {
    data class Entry(val id: String, val title: String, val template: String)

    val all = listOf(
        Entry("google", "Google", "https://www.google.com/search?q=%s"),
        Entry("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Entry("yandex", "Яндекс", "https://yandex.ru/search/?text=%s"),
        Entry("bing", "Bing", "https://www.bing.com/search?q=%s"),
    )

    private const val PREFS = "hrips_search"
    private const val KEY = "engine"

    fun current(context: Context): Entry {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun select(context: Context, entry: Entry) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, entry.id).apply()
    }

    fun searchUrl(context: Context, query: String): String =
        current(context).template.replace("%s", Uri.encode(query))

    /** Адрес, если введён адрес; иначе поисковый запрос. */
    fun resolve(context: Context, input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return Components.BLANK
        val lower = text.lowercase()
        return when {
            lower.startsWith("about:") || lower.startsWith("file:") || lower.startsWith("content:") -> text
            text.contains(' ').not() && text.contains("://") -> text
            text.contains(' ').not() && text.isUrl() -> "https://$text"
            else -> searchUrl(context, text)
        }
    }

    fun looksLikeUrl(input: String): Boolean {
        val text = input.trim()
        return text.isNotEmpty() && !text.contains(' ') && (text.contains("://") || text.isUrl())
    }
}
