package com.hrips.browser

/**
 * Разбор того, что пользователь ввёл или вставил в адресную строку: адрес или поисковый запрос.
 * Чистая логика без Android API, поэтому покрыта локальными юнит-тестами (AddressInputTest).
 */
sealed interface AddressInput {
    data class Url(val url: String) : AddressInput
    data class Query(val text: String) : AddressInput
}

private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
private val PORT = Regex("^\\d{1,5}(?:[/?#].*)?$")
private val IPV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

/** Схемы, которые нельзя выполнять из ввода и вставки: `javascript:` из буфера обмена выполнился бы в открытой странице. */
private val UNSAFE_SCHEMES = setOf("javascript", "vbscript", "data")

/** Схемы без `//`, которые адресная строка открывает как есть. */
private val OPAQUE_SCHEMES = setOf("about", "view-source")

/** Схемы, разрешённые для адресов из других приложений (поделиться, веб-поиск): локальные файлы и служебные страницы туда не попадают. */
private val EXTERNAL_SCHEMES = setOf("http", "https")

fun parseAddressInput(raw: String, external: Boolean = false): AddressInput {
    val t = raw.trim()
    if (t.isEmpty()) return AddressInput.Query(t)
    val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
    if (scheme != null) {
        val rest = t.substring(scheme.length + 1)
        val hostPort = !rest.startsWith("//") && PORT.matches(rest)
        if (!hostPort) {
            if (scheme in UNSAFE_SCHEMES) return AddressInput.Query(t)
            val hierarchical = rest.startsWith("//") && rest.length > 2
            val open = if (external) scheme in EXTERNAL_SCHEMES && hierarchical
            else hierarchical || (scheme in OPAQUE_SCHEMES && rest.isNotEmpty())
            // «mailto:…», «tel:…», «ответ: …» и подобное без адреса сайта ищется, а не открывается как https://mailto:…
            return if (open) AddressInput.Url(t) else AddressInput.Query(t)
        }
    }
    if (t.any { it.isWhitespace() }) return AddressInput.Query(t)
    val authority = t.substringBefore('/').substringBefore('?').substringBefore('#')
    // «user@example.com» без пути почти всегда адрес почты, а не вход на сайт с логином в адресе
    if ('@' in authority) return AddressInput.Query(t)
    val host = hostOf(authority) ?: return AddressInput.Query(t)
    val kind = hostKind(host) ?: return AddressInput.Query(t)
    val prefix = if (kind == HostKind.LOCAL) "http://" else "https://"
    return AddressInput.Url(prefix + t)
}

private enum class HostKind { PUBLIC, LOCAL }

private fun hostOf(authority: String): String? {
    if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        if (end <= 1) return null
        val tail = authority.substring(end + 1)
        if (tail.isNotEmpty() && !PORT.matches(tail.removePrefix(":"))) return null
        return authority.substring(0, end + 1)
    }
    val colon = authority.lastIndexOf(':')
    if (colon >= 0) {
        val port = authority.substring(colon + 1)
        if (port.isEmpty() || port.length > 5 || !port.all(Char::isDigit) || port.toInt() !in 1..65535) return null
        return authority.substring(0, colon)
    }
    return authority
}

private fun hostKind(rawHost: String): HostKind? {
    val host = rawHost.lowercase().trimEnd('.')
    if (host.isEmpty()) return null
    if (host.startsWith("[")) return if (host == "[::1]" || host.startsWith("[fe80:") || host.startsWith("[fc") || host.startsWith("[fd")) HostKind.LOCAL else HostKind.PUBLIC
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan")) return HostKind.LOCAL
    if (IPV4.matches(host)) {
        val o = host.split('.').map { it.toInt() }
        if (o.any { it > 255 }) return null
        val local = o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) || (o[0] == 169 && o[1] == 254)
        return if (local) HostKind.LOCAL else HostKind.PUBLIC
    }
    val labels = host.split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 }) return null
    if (labels.any { l -> l.startsWith('-') || l.endsWith('-') || !l.all { it.isLetterOrDigit() || it == '-' } }) return null
    val tld = labels.last()
    // Домен верхнего уровня из букв (в том числе «рф») или punycode; «3.14» и «v1.2» остаются запросами
    val tldOk = tld.startsWith("xn--") || (tld.length >= 2 && tld.all { it.isLetter() })
    return if (tldOk) HostKind.PUBLIC else null
}
