package com.hrips.browser

object BookmarksHtml {
    private val LINK = Regex("""<a\s([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HREF = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val ADD_DATE = Regex("""\badd_date\s*=\s*"?(\d+)"?""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun unescape(s: String): String {
        val numeric = NUMERIC.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return numeric.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
    }

    fun export(entries: List<Entry>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<!-- This is an automatically generated file. It will be read and overwritten. DO NOT EDIT! -->\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        for (e in entries) {
            val title = e.title.ifBlank { e.url }
            append("    <DT><A HREF=\"").append(escape(e.url)).append("\" ADD_DATE=\"").append(e.time / 1000)
                .append("\">").append(escape(title)).append("</A>\n")
        }
        append("</DL><p>\n")
    }

    fun parse(text: String, now: Long = System.currentTimeMillis()): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for (m in LINK.findAll(text)) {
            val attrs = m.groupValues[1]
            val h = HREF.find(attrs) ?: continue
            val url = unescape((h.groups[1] ?: h.groups[2])?.value.orEmpty()).trim()
            val lower = url.lowercase()
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) continue
            if (!seen.add(url)) continue
            val title = unescape(TAGS.replace(m.groupValues[2], "")).trim().ifEmpty { url }
            val seconds = ADD_DATE.find(attrs)?.groupValues?.get(1)?.toLongOrNull()
            out.add(Entry(url, title, if (seconds != null && seconds > 0) seconds * 1000 else now))
        }
        return out
    }
}
