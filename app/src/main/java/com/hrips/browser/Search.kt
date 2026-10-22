package com.hrips.browser

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SearchEngine(
    val name: String,
    val template: String,
    val suggest: String,
    val host: String,
    val color: Long,
)

object SearchEngines {
    val all = listOf(
        SearchEngine("Яндекс", "https://yandex.ru/search/?text=", "https://suggest.yandex.ru/suggest-ff.cgi?uil=ru&part=", "ya.ru", 0xFFFC3F1D),
        SearchEngine("Google", "https://www.google.com/search?q=", "https://suggestqueries.google.com/complete/search?client=firefox&q=", "www.google.com", 0xFF4285F4),
        SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q=", "https://duckduckgo.com/ac/?type=list&q=", "duckduckgo.com", 0xFFDE5833),
        SearchEngine("Bing", "https://www.bing.com/search?q=", "https://api.bing.com/osjson.aspx?query=", "www.bing.com", 0xFF008373),
        SearchEngine("Ecosia", "https://www.ecosia.org/search?q=", "https://ac.ecosia.org/autocomplete?type=list&q=", "www.ecosia.org", 0xFF3F8F3B),
        SearchEngine("Qwant", "https://www.qwant.com/?q=", "https://api.qwant.com/api/suggest/?client=opensearch&q=", "www.qwant.com", 0xFF2B5BE0),
        SearchEngine("Startpage", "https://www.startpage.com/do/search?q=", "https://www.startpage.com/osuggestions?q=", "www.startpage.com", 0xFF4A5CE8),
        SearchEngine("Ответы Mail.ru", "https://otvet.mail.ru/search/", "", "otvet.mail.ru", 0xFF005FF9),
        SearchEngine("Википедия", "https://ru.wikipedia.org/w/index.php?search=", "https://ru.wikipedia.org/w/api.php?action=opensearch&limit=8&search=", "ru.wikipedia.org", 0xFF5F6368),
    )
    var current by mutableStateOf(all[0])
}

fun parseSearch(url: String): Pair<SearchEngine, String>? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
    for (e in SearchEngines.all) {
        val base = Uri.parse(e.template).host.orEmpty().split('.').takeLast(2).joinToString(".")
        if (host != base && !host.endsWith(".$base")) continue
        if (e.host == "otvet.mail.ru") {
            val seg = uri.pathSegments
            if (host == "otvet.mail.ru" && seg.size >= 2 && seg[0] == "search" && seg[1].isNotBlank()) return e to seg[1]
            continue
        }
        val q = listOf("text", "q", "query", "search").firstNotNullOfOrNull { p ->
            uri.getQueryParameter(p)?.takeIf { it.isNotBlank() }
        } ?: continue
        return e to q
    }
    return null
}

fun isSearch(input: String): Boolean {
    val t = input.trim()
    val lower = t.lowercase()
    return !(lower.contains("://") || lower.startsWith("about:") || (!t.contains(' ') && t.contains('.')))
}

fun toUrl(input: String, engine: SearchEngine = SearchEngines.current): String {
    val t = input.trim()
    return when {
        t.contains("://") || t.startsWith("about:", ignoreCase = true) -> t
        !isSearch(t) -> "https://$t"
        else -> engine.template + Uri.encode(t)
    }
}

