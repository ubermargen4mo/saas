package com.hrips.browser

import java.net.URL

/**
 * Чистые функции без зависимостей от Android: их проверяют обычные unit-тесты (app/src/test).
 */

/** Делает из имени файла безопасное: без разделителей путей, управляющих символов, до 150 знаков. [extension] добавляется, если в имени точки нет. */
fun sanitizeFileName(raw: String, extension: String? = null): String {
    var name = raw
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("[\u0000-\u001F\u007F]"), "_")
        .trim()
        .take(150)
    if (name.isBlank() || name == "." || name == "..") name = "download"
    if (!name.contains('.') && !extension.isNullOrBlank()) name = "$name.$extension"
    return name
}

/** Поиск по вкладкам: каждое слово запроса должно встретиться в заголовке или адресе, порядок не важен. */
fun matchesQuery(label: String, url: String, query: String): Boolean {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    return words.all { label.contains(it, ignoreCase = true) || url.contains(it, ignoreCase = true) }
}

class IconLink(val href: String, val appleTouch: Boolean, val size: Int)

private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
// name = "значение" | 'значение' | значение без кавычек
private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

private fun decodeEntities(s: String) = s
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")

/**
 * Достаёт из HTML ссылки на иконки сайта: rel="icon", "shortcut icon", "apple-touch-icon" и похожие.
 * Понимает любой порядок атрибутов, одинарные кавычки и значения без кавычек; svg и data: пропускает.
 * Сначала идут apple-touch (они обычно крупнее), потом по убыванию заявленного размера.
 */
fun parseIconLinks(html: String): List<IconLink> {
    val out = ArrayList<IconLink>()
    for (m in LINK_TAG.findAll(html)) {
        val attrs = HashMap<String, String>()
        for (a in ATTR.findAll(m.value)) {
            val v = a.groupValues[2].ifEmpty { a.groupValues[3] }.ifEmpty { a.groupValues[4] }
            attrs.putIfAbsent(a.groupValues[1].lowercase(), v)
        }
        val rel = attrs["rel"]?.lowercase() ?: continue
        if (!rel.contains("icon") || rel.contains("mask-icon")) continue
        val href = decodeEntities(attrs["href"]?.trim().orEmpty())
        if (href.isEmpty() || href.startsWith("data:", true) || href.substringBefore('?').endsWith(".svg", true)) continue
        val size = attrs["sizes"]?.let { Regex("(\\d+)x\\d+", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        out.add(IconLink(href, rel.contains("apple-touch"), size))
    }
    return out.sortedWith(compareByDescending<IconLink> { it.appleTouch }.thenByDescending { it.size })
}

/** Абсолютные https-адреса иконок относительно [base]; битые и небезопасные пропускаются. */
fun resolveIconUrls(base: String, links: List<IconLink>): List<String> =
    links.mapNotNull { l ->
        runCatching { URL(URL(base), l.href) }.getOrNull()?.takeIf { it.protocol.equals("https", true) }?.toString()
    }
