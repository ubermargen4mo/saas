package com.hrips.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PureUtilsTest {
    // ---- имена файлов
    @Test fun nameWithPathSeparatorsIsFlattened() {
        assertEquals("_.._etc_passwd", sanitizeFileName("/../etc/passwd"))
        assertFalse(sanitizeFileName("../../x.txt").contains('/'))
        assertFalse(sanitizeFileName("a\\b.txt").contains('\\'))
    }
    @Test fun controlCharsAreReplaced() = assertEquals("a_b.txt", sanitizeFileName("a\u0000b.txt"))
    @Test fun blankAndDotsBecomeDownload() {
        assertEquals("download", sanitizeFileName("  "))
        assertEquals("download", sanitizeFileName(".."))
        assertEquals("download", sanitizeFileName("."))
    }
    @Test fun longNameIsCut() = assertEquals(150, sanitizeFileName("a".repeat(400)).length)
    @Test fun extensionAddedOnlyWithoutDot() {
        assertEquals("file.pdf", sanitizeFileName("file", "pdf"))
        assertEquals("file.zip", sanitizeFileName("file.zip", "pdf"))
    }

    // ---- поиск по вкладкам
    @Test fun emptyQueryMatchesEverything() = assertTrue(matchesQuery("a", "b", "  "))
    @Test fun wordsMayBeSplitBetweenTitleAndUrl() = assertTrue(matchesQuery("Погода в Москве", "https://yandex.ru/pogoda", "москве yandex"))
    @Test fun allWordsRequired() = assertFalse(matchesQuery("Погода", "https://a.ru", "погода курс"))

    // ---- иконки сайта
    @Test fun parsesAnyAttributeOrderAndQuotes() {
        val html = """<link href='/a.png' rel='icon'><link rel=icon href=/b.png><link href="/c.png" sizes="180x180" rel="apple-touch-icon">"""
        val l = parseIconLinks(html)
        assertEquals(listOf("/c.png", "/a.png", "/b.png"), l.map { it.href })
    }
    @Test fun skipsSvgDataAndMask() {
        val html = """<link rel="icon" href="/x.svg"><link rel="icon" href="data:image/png;base64,AAA"><link rel="mask-icon" href="/m.png"><link rel="stylesheet" href="/s.css">"""
        assertTrue(parseIconLinks(html).isEmpty())
    }
    @Test fun decodesAmpersandInHref() = assertEquals("/i.png?a=1&b=2", parseIconLinks("""<link rel="icon" href="/i.png?a=1&amp;b=2">""")[0].href)
    @Test fun resolveKeepsOnlyHttps() {
        val links = listOf(IconLink("/a.png", false, 0), IconLink("http://evil/x.png", false, 0), IconLink("https://cdn.x/y.png", false, 0))
        assertEquals(listOf("https://site.ru/a.png", "https://cdn.x/y.png"), resolveIconUrls("https://site.ru/", links))
    }

    // ---- адресная строка (без Android-зависимостей)
    @Test fun isSearchDetection() {
        assertFalse(isSearch("example.com"))
        assertFalse(isSearch("https://x.ru/a b"))
        assertFalse(isSearch("about:blank"))
        assertTrue(isSearch("погода москва"))
        assertTrue(isSearch("котики"))
    }

    // ---- обновления
    @Test fun buildTagParsing() {
        assertEquals(42, buildFromTag("build-42"))
        assertEquals(null, buildFromTag("v1.0"))
        assertEquals(null, buildFromTag("build-"))
        assertEquals(null, buildFromTag(null))
        assertEquals(142L, versionCodeOfBuild(42))
    }
}
