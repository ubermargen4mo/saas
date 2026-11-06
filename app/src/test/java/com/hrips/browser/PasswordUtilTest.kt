package com.hrips.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PasswordUtilTest {
    @Test fun normalizeOrigin() {
        assertEquals("https://example.com", normalizeOrigin("example.com"))
        assertEquals("https://example.com", normalizeOrigin("HTTPS://Example.com:443/login?x=1"))
        assertEquals("http://example.com:8080", normalizeOrigin("http://example.com:8080/a"))
        assertEquals("https://example.com", normalizeOrigin("https://user:pw@example.com/"))
        assertEquals("https://[::1]:8443", normalizeOrigin("https://[::1]:8443"))
        assertNull(normalizeOrigin("ftp://example.com"))
        assertNull(normalizeOrigin("javascript:alert(1)"))
        assertNull(normalizeOrigin("https://example.com:0"))
        assertNull(normalizeOrigin("https://exa mple.com"))
        assertNull(normalizeOrigin(""))
        assertNull(normalizeOrigin(null))
    }

    @Test fun domainMatching() {
        assertTrue(originMatchesDomain("https://login.example.com", "example.com"))
        assertTrue(originMatchesDomain("https://example.com", ".Example.com."))
        assertFalse(originMatchesDomain("https://badexample.com", "example.com"))
        assertEquals("example.com", displayHost("https://www.example.com:8443"))
    }

    @Test fun upsertUpdatesSameAccount() {
        val now = 1_000L
        val first = upsertLogin(emptyList(), IncomingLogin(null, "https://a.ru", null, null, "u", "p1"), now) { "id1" }
        assertTrue(first.isNew)
        val second = upsertLogin(first.list, IncomingLogin(null, "https://a.ru", "https://a.ru", null, "u", "p2"), now + 5) { "id2" }
        assertFalse(second.isNew)
        assertEquals(1, second.list.size)
        assertEquals("p2", second.saved.password)
        assertEquals(now + 5, second.saved.modified)
        assertEquals(now, second.saved.created)
        val same = upsertLogin(second.list, IncomingLogin("id1", "https://a.ru", null, null, "u", "p2"), now + 9) { "x" }
        assertEquals(now + 5, same.saved.modified)
    }

    @Test fun ruPlural() {
        assertEquals("1 файл", ruPlural(1, "файл", "файла", "файлов"))
        assertEquals("3 файла", ruPlural(3, "файл", "файла", "файлов"))
        assertEquals("11 файлов", ruPlural(11, "файл", "файла", "файлов"))
        assertEquals("21 файл", ruPlural(21, "файл", "файла", "файлов"))
        assertEquals("112 файлов", ruPlural(112, "файл", "файла", "файлов"))
    }

    @Test fun generatedPasswordHasAllClasses() {
        repeat(200) { seed ->
            val p = generatePassword(20, Random(seed.toLong()))
            assertEquals(20, p.length)
            assertTrue(p.any { it.isLowerCase() } && p.any { it.isUpperCase() } && p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() })
        }
        assertEquals(8, generatePassword(2).length)
        assertEquals(128, generatePassword(1000).length)
    }

    @Test fun csvRoundTrip() {
        val logins = listOf(
            SavedLogin("1", "https://a.ru", null, null, "ivan", "p,a\"ss\nword", 0, 0),
            SavedLogin("2", "http://b.com:8080", null, null, "", "x", 0, 0),
        )
        val parsed = PasswordCsv.parse(PasswordCsv.format(logins))
        assertNull(parsed.error)
        assertEquals(2, parsed.rows.size)
        assertEquals("p,a\"ss\nword", parsed.rows[0].password)
        assertEquals("ivan", parsed.rows[0].username)
        assertEquals("http://b.com:8080", parsed.rows[1].origin)
    }

    @Test fun csvFirefoxSemicolonAndBom() {
        val firefox = "\uFEFF\"url\",\"username\",\"password\",\"httpRealm\"\n\"https://x.org\",\"me\",\"secret\",\"\"\n"
        val r = PasswordCsv.parse(firefox)
        assertEquals(1, r.rows.size)
        assertEquals("https://x.org", r.rows[0].origin)
        val semi = "Website;Login;Password\r\nexample.com;bob;123\r\nbad url;bob;1\r\n"
        val s = PasswordCsv.parse(semi)
        assertEquals(1, s.rows.size)
        assertEquals(1, s.skipped)
        assertEquals("https://example.com", s.rows[0].origin)
        assertEquals("Файл пустой", PasswordCsv.parse("  ").error)
        assertTrue(PasswordCsv.parse("a,b\n1,2").error != null)
    }

    @Test fun mergeSkipsDuplicates() {
        val existing = listOf(SavedLogin("1", "https://a.ru", null, null, "u", "p", 0, 0))
        val rows = listOf(
            CsvLogin("https://a.ru", "u", "other"),
            CsvLogin("https://a.ru", "v", "p"),
            CsvLogin("https://a.ru", "v", "dup-in-file"),
            CsvLogin("https://b.ru", "u", "p"),
        )
        var n = 0
        val r = PasswordCsv.merge(existing, rows, 5) { "n${n++}" }
        assertEquals(2, r.added)
        assertEquals(2, r.duplicates)
        assertEquals(3, r.list.size)
        assertEquals("1", r.list.last().id)
    }

    @Test fun mergeIsFastOnLargeLists() {
        val existing = (0 until 5_000).map { SavedLogin("e$it", "https://s$it.ru", null, null, "u", "p", 0, 0) }
        val rows = (0 until 5_000).map { CsvLogin("https://s${it * 2}.ru", "u", "p") }
        val started = System.nanoTime()
        val r = PasswordCsv.merge(existing, rows, 0) { "x" }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(2_500, r.added)
        assertTrue("слияние заняло $ms мс", ms < 1_000)
    }
}
