package com.hrips.browser

import java.net.URL

private val UNSAFE_FILE_NAME = Regex("[\\\\/:*?\"<>|]")
private val CONTROL_CHARS = Regex("[\u0000-\u001F\u007F]")
private val QUERY_WHITESPACE = Regex("\\s+")
private val BUILD_TAG = Regex("^build-(\\d{1,9})$")
private val ICON_SIZE = Regex("(\\d+)x\\d+", RegexOption.IGNORE_CASE)

fun sanitizeFileName(
    raw: String,
    extension: String? = null,
    fallback: String = "download",
    maxLength: Int = 150,
): String {
    val limit = maxLength.coerceAtLeast(1)
    fun clean(value: String) = value
        .replace(UNSAFE_FILE_NAME, "_")
        .replace(CONTROL_CHARS, "_")
        .trim()
        .take(limit)

    var name = clean(raw)
    if (name.isBlank() || name == "." || name == "..") {
        name = clean(fallback).takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download".take(limit)
    }

    val ext = extension.orEmpty().trim().trimStart('.').filter { it.isLetterOrDigit() }.take(16)
    if (!name.contains('.') && ext.isNotEmpty() && ext.length + 1 < limit) {
        val room = limit - ext.length - 1
        name = "${name.take(room).trimEnd()}.$ext"
    }
    return name
}

fun matchesQuery(label: String, url: String, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return QUERY_WHITESPACE.split(trimmed).all { label.contains(it, ignoreCase = true) || url.contains(it, ignoreCase = true) }
}

class IconLink(val href: String, val appleTouch: Boolean, val size: Int)

private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

private fun decodeEntities(s: String) = s
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")

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
        val size = attrs["sizes"]?.let { ICON_SIZE.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        out.add(IconLink(href, rel.contains("apple-touch"), size))
    }
    return out.sortedWith(compareByDescending<IconLink> { it.appleTouch }.thenByDescending { it.size })
}

fun resolveIconUrls(base: String, links: List<IconLink>): List<String> =
    links.mapNotNull { l ->
        runCatching { URL(URL(base), l.href) }.getOrNull()?.takeIf { it.protocol.equals("https", true) }?.toString()
    }

fun buildFromTag(tag: String?): Int? =
    BUILD_TAG.find(tag.orEmpty().trim())?.groupValues?.get(1)?.toIntOrNull()

fun versionCodeOfBuild(build: Int): Long = build.toLong() + 100L
