package com.hrips.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureUtilsTest {
    @Test fun fileNamesCannotEscapeFolder() {
        assertEquals(".._.._etc_passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("download", sanitizeFileName(".."))
        assertEquals("download", sanitizeFileName("   "))
        assertEquals("a_b_c.txt", sanitizeFileName("a\u0000b\nc.txt"))
        assertEquals("report.pdf", sanitizeFileName("report", "pdf"))
        assertEquals("report.tar.gz", sanitizeFileName("report.tar.gz", "zip"))
        assertEquals(150, sanitizeFileName("x".repeat(400), "bin").length)
        assertTrue(sanitizeFileName("x".repeat(400), "bin").endsWith(".bin"))
    }

    @Test fun query() {
        assertTrue(matchesQuery("Новости Хабра", "https://habr.com", "хабр  новости"))
        assertTrue(matchesQuery("x", "https://github.com", "GIT"))
        assertFalse(matchesQuery("x", "https://github.com", "git lab"))
        assertTrue(matchesQuery("x", "y", "  "))
    }

    @Test fun iconLinks() {
        val html = """
            <link rel="icon" href="/fav.ico">
            <LINK REL='apple-touch-icon' sizes="180x180" href='/apple.png?a=1&amp;b=2'>
            <link rel="icon" sizes="32x32" href="/i32.png">
            <link rel="mask-icon" href="/m.svg"><link rel="icon" href="data:image/png;base64,AA">
            <link rel="icon" href="/v.svg">
        """.trimIndent()
        val links = parseIconLinks(html)
        assertEquals(listOf("/apple.png?a=1&b=2", "/i32.png", "/fav.ico"), links.map { it.href })
        val urls = resolveIconUrls("https://site.ru/page/", links + IconLink("http://insecure/x.png", false, 0))
        assertEquals("https://site.ru/apple.png?a=1&b=2", urls.first())
        assertFalse(urls.any { it.startsWith("http://") })
    }

    @Test fun misc() {
        assertEquals(2, sampleSizeFor(4000, 3000, 1000))
        assertEquals(4, sampleSizeFor(5000, 3000, 1000))
        assertEquals(1, sampleSizeFor(100, 100, 1000))
        assertEquals(213, buildFromTag("build-213"))
        assertNull(buildFromTag("v1.2"))
        assertEquals(313L, versionCodeOfBuild(213))
        assertEquals("0aff", byteArrayOf(10, -1).toHex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"))
    }
}
