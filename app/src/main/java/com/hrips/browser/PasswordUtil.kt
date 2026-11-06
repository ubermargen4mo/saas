package com.hrips.browser

import java.security.SecureRandom
import java.util.Random

data class SavedLogin(
    val id: String,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
    val created: Long,
    val modified: Long,
    val lastUsed: Long = 0L,
    val timesUsed: Int = 0,
)

class IncomingLogin(
    val guid: String?,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
)

private fun isHttpScheme(s: String) = s == "http" || s == "https"

private fun authority(afterScheme: String): String =
    afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')

private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

fun normalizeOrigin(raw: String?): String? {
    var s = raw?.trim().orEmpty()
    if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() || it == '"' || it == '<' || it == '>' }) return null
    if (!SCHEME_PREFIX.containsMatchIn(s)) s = if (s.startsWith("//")) "https:$s" else "https://$s"
    val at = s.indexOf("://")
    val scheme = s.substring(0, at).lowercase()
    if (!isHttpScheme(scheme)) return null
    val rest = authority(s.substring(at + 3))
    val host: String
    var port: String? = null
    if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        if (end < 0) return null
        host = rest.substring(0, end + 1)
        val tail = rest.substring(end + 1)
        if (tail.isNotEmpty()) {
            if (!tail.startsWith(":")) return null
            port = tail.substring(1)
        }
    } else {
        host = rest.substringBefore(':')
        if (rest.contains(':')) port = rest.substringAfter(':')
    }
    if (host.isBlank() || host.startsWith(".")) return null
    var portPart = ""
    if (!port.isNullOrEmpty()) {
        val n = port.toIntOrNull() ?: return null
        if (n !in 1..65535) return null
        val isDefault = (scheme == "http" && n == 80) || (scheme == "https" && n == 443)
        if (!isDefault) portPart = ":$n"
    } else if (port != null) {
        return null
    }
    return "$scheme://${host.lowercase()}$portPart"
}

fun originHost(origin: String): String {
    val rest = authority(origin.substringAfter("://", origin))
    val host = if (rest.startsWith("[")) rest.substringBefore(']') + "]" else rest.substringBefore(':')
    return host.lowercase()
}

fun displayHost(origin: String): String = originHost(origin).removePrefix("www.").ifBlank { origin }

private fun siteKey(origin: String): String = authority(origin.substringAfter("://", origin)).lowercase()

fun originMatchesDomain(origin: String, domain: String): Boolean {
    val d = domain.trim().trim('.').lowercase()
    if (d.isEmpty()) return false
    val h = originHost(origin)
    return h == d || h.endsWith(".$d")
}

fun sameAccount(a: SavedLogin, origin: String, username: String, httpRealm: String?): Boolean =
    siteKey(a.origin) == siteKey(origin) && a.username == username && a.httpRealm.orEmpty() == httpRealm.orEmpty()

class UpsertResult(val list: List<SavedLogin>, val saved: SavedLogin, val isNew: Boolean)

fun upsertLogin(list: List<SavedLogin>, inc: IncomingLogin, now: Long, newId: () -> String): UpsertResult {
    val byGuid = inc.guid?.let { g -> list.indexOfFirst { it.id == g } } ?: -1
    val idx = if (byGuid >= 0) byGuid else list.indexOfFirst { sameAccount(it, inc.origin, inc.username, inc.httpRealm) }
    if (idx >= 0) {
        val old = list[idx]
        val changed = old.password != inc.password || old.username != inc.username
        val updated = old.copy(
            formActionOrigin = inc.formActionOrigin ?: old.formActionOrigin,
            httpRealm = inc.httpRealm ?: old.httpRealm,
            username = inc.username,
            password = inc.password,
            modified = if (changed) now else old.modified,
        )
        return UpsertResult(list.toMutableList().also { it[idx] = updated }, updated, false)
    }
    val created = SavedLogin(
        id = newId(),
        origin = inc.origin,
        formActionOrigin = inc.formActionOrigin,
        httpRealm = inc.httpRealm,
        username = inc.username,
        password = inc.password,
        created = now,
        modified = now,
    )
    return UpsertResult(listOf(created) + list, created, true)
}

fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    val word = when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

private const val GEN_LOWER = "abcdefghijkmnopqrstuvwxyz"
private const val GEN_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val GEN_DIGITS = "23456789"
private const val GEN_SYMBOLS = "!@#\$%&*-_?"

fun generatePassword(length: Int = 20, rnd: Random = SecureRandom()): String {
    val n = length.coerceIn(8, 128)
    val all = GEN_LOWER + GEN_UPPER + GEN_DIGITS + GEN_SYMBOLS
    val chars = ArrayList<Char>(n)
    chars.add(GEN_LOWER[rnd.nextInt(GEN_LOWER.length)])
    chars.add(GEN_UPPER[rnd.nextInt(GEN_UPPER.length)])
    chars.add(GEN_DIGITS[rnd.nextInt(GEN_DIGITS.length)])
    chars.add(GEN_SYMBOLS[rnd.nextInt(GEN_SYMBOLS.length)])
    while (chars.size < n) chars.add(all[rnd.nextInt(all.length)])
    for (i in chars.indices.reversed()) {
        val j = rnd.nextInt(i + 1)
        val t = chars[i]; chars[i] = chars[j]; chars[j] = t
    }
    return String(chars.toCharArray())
}

class CsvLogin(val origin: String, val username: String, val password: String)

class CsvParse(val rows: List<CsvLogin>, val skipped: Int, val error: String?)

class CsvImport(val list: List<SavedLogin>, val added: Int, val duplicates: Int)

object PasswordCsv {
    const val HEADER = "name,url,username,password,note"

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun format(logins: List<SavedLogin>): String {
        val sb = StringBuilder(HEADER).append("\r\n")
        for (l in logins) {
            sb.append(escape(displayHost(l.origin))).append(',')
                .append(escape(l.origin)).append(',')
                .append(escape(l.username)).append(',')
                .append(escape(l.password)).append(',')
                .append("\r\n")
        }
        return sb.toString()
    }

    private val URL_COLUMNS = setOf("url", "website", "web site", "login_uri", "login uri", "origin", "hostname", "address", "site", "login url", "web address")
    private val USER_COLUMNS = setOf("username", "login_username", "user", "login", "email", "user name", "account", "login name")
    private val PASS_COLUMNS = setOf("password", "login_password", "pass", "passwort")

    internal fun records(text: String, delimiter: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var wasQuoted = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0); wasQuoted = false }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) out.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when {
                c == '"' && field.isEmpty() && !wasQuoted -> { inQuotes = true; wasQuoted = true }
                c == delimiter -> endField()
                c == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || wasQuoted) endRow()
        return out
    }

    private fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val counts = listOf(',', ';', '\t').associateWith { d -> first.count { it == d } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    fun parse(raw: String): CsvParse {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) return CsvParse(emptyList(), 0, "Файл пустой")
        val recs = records(text, detectDelimiter(text))
        if (recs.isEmpty()) return CsvParse(emptyList(), 0, "Файл пустой")
        val header = recs[0].map { it.trim().lowercase() }
        val urlCol = header.indexOfFirst { it in URL_COLUMNS }
        val passCol = header.indexOfFirst { it in PASS_COLUMNS }
        val userCol = header.indexOfFirst { it in USER_COLUMNS }
        if (urlCol < 0 || passCol < 0) {
            return CsvParse(emptyList(), 0, "Не нашёл столбцы «url» и «password» в первой строке")
        }
        val rows = ArrayList<CsvLogin>()
        var skipped = 0
        for (r in recs.drop(1)) {
            val origin = normalizeOrigin(r.getOrNull(urlCol))
            val password = r.getOrNull(passCol).orEmpty()
            if (origin == null || password.isEmpty()) { skipped++; continue }
            rows.add(CsvLogin(origin, if (userCol >= 0) r.getOrNull(userCol).orEmpty().trim() else "", password))
        }
        return CsvParse(rows, skipped, null)
    }

    /**
     * Добавляет строки CSV к сохранённым входам; уже имеющиеся аккаунты (сайт + логин) не трогает.
     * Ключи аккаунтов в хеш-наборе: импорт тысяч строк в большой список раньше был квадратичным и подвешивал экран.
     */
    fun merge(list: List<SavedLogin>, rows: List<CsvLogin>, now: Long, newId: () -> String): CsvImport {
        val known = HashSet<String>(list.size + rows.size)
        list.forEach { if (it.httpRealm.isNullOrEmpty()) known.add(accountKey(it.origin, it.username)) }
        var duplicates = 0
        val fresh = ArrayList<SavedLogin>()
        for (r in rows) {
            if (!known.add(accountKey(r.origin, r.username))) {
                duplicates++
                continue
            }
            fresh.add(SavedLogin(newId(), r.origin, null, null, r.username, r.password, now, now))
        }
        return CsvImport(fresh + list, fresh.size, duplicates)
    }

    private fun accountKey(origin: String, username: String) = siteKey(origin) + "\u0000" + username
}
