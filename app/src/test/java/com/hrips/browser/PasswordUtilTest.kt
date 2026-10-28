package com.hrips.browser

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordUtilTest {
    private fun login(id: String, origin: String, user: String, pass: String, realm: String? = null) =
        SavedLogin(id, origin, null, realm, user, pass, created = 1L, modified = 1L)

    private fun incoming(origin: String, user: String, pass: String, guid: String? = null) =
        IncomingLogin(guid, origin, origin, null, user, pass)

    // ---- Адреса -------------------------------------------------------------------------------------------

    @Test fun originIsReducedToSchemeHostPort() {
        assertEquals("https://example.com", normalizeOrigin("https://Example.com/login?x=1#top"))
        assertEquals("http://example.com:8080", normalizeOrigin("http://example.com:8080/a"))
        assertEquals("https://example.com", normalizeOrigin("https://example.com:443/"))
        assertEquals("http://example.com", normalizeOrigin("http://example.com:80"))
        assertEquals("https://example.com", normalizeOrigin("https://user:pw@example.com/path"))
    }

    @Test fun originWithoutSchemeIsHttps() {
        assertEquals("https://example.com", normalizeOrigin("example.com"))
        assertEquals("https://example.com", normalizeOrigin("  example.com/login  "))
        assertEquals("https://example.com:8080", normalizeOrigin("example.com:8080/x"))
        assertEquals("https://example.com", normalizeOrigin("//example.com/x"))
        assertEquals("https://example.com", normalizeOrigin("example.com/go?u=http://other.org"))
    }

    @Test fun originRejectsGarbage() {
        assertNull(normalizeOrigin(null))
        assertNull(normalizeOrigin(""))
        assertNull(normalizeOrigin("   "))
        assertNull(normalizeOrigin("android://hash@com.example.app/"))
        assertNull(normalizeOrigin("ftp://example.com"))
        assertNull(normalizeOrigin("https://"))
        assertNull(normalizeOrigin("https://example.com:99999"))
        assertNull(normalizeOrigin("https://example.com:abc"))
        assertNull(normalizeOrigin("not a url"))
        assertNull(normalizeOrigin("https://exa\"mple.com"))
    }

    @Test fun originKeepsIpv6() {
        assertEquals("http://[::1]:3000", normalizeOrigin("http://[::1]:3000/x"))
        assertEquals("[::1]", originHost("http://[::1]:3000"))
    }

    @Test fun hostHelpers() {
        assertEquals("example.com", originHost("https://Example.com:8443"))
        assertEquals("example.com", displayHost("https://www.example.com"))
        assertEquals("sub.example.com", displayHost("https://sub.example.com"))
    }

    // ---- Сопоставление с запросом Gecko -------------------------------------------------------------------

    @Test fun domainMatchesItselfAndSubdomains() {
        assertTrue(originMatchesDomain("https://example.com", "example.com"))
        assertTrue(originMatchesDomain("https://accounts.example.com", "example.com"))
        assertTrue(originMatchesDomain("https://www.example.com:8443", "example.com"))
        assertTrue(originMatchesDomain("https://example.com", "EXAMPLE.com."))
    }

    @Test fun domainDoesNotMatchLookalikes() {
        assertFalse(originMatchesDomain("https://notexample.com", "example.com"))
        assertFalse(originMatchesDomain("https://example.com.evil.org", "example.com"))
        assertFalse(originMatchesDomain("https://example.org", "example.com"))
        assertFalse(originMatchesDomain("https://example.com", ""))
    }

    // ---- Слияние -------------------------------------------------------------------------------------------

    @Test fun upsertAddsNewLoginFirst() {
        val old = listOf(login("a", "https://one.com", "u", "p"))
        val r = upsertLogin(old, incoming("https://two.com", "me", "pw"), now = 50L) { "new" }
        assertTrue(r.isNew)
        assertEquals(2, r.list.size)
        assertEquals("new", r.list[0].id)
        assertEquals(50L, r.saved.created)
        assertEquals("a", r.list[1].id)
    }

    @Test fun upsertUpdatesPasswordOfSameAccount() {
        val old = listOf(login("a", "https://one.com", "me", "old"))
        val r = upsertLogin(old, incoming("https://one.com", "me", "new"), now = 50L) { error("no new id expected") }
        assertFalse(r.isNew)
        assertEquals(1, r.list.size)
        assertEquals("a", r.saved.id)
        assertEquals("new", r.saved.password)
        assertEquals(50L, r.saved.modified)
        assertEquals(1L, r.saved.created)
    }

    @Test fun upsertTreatsHttpAndHttpsOfSameSiteAsOneAccount() {
        val old = listOf(login("a", "http://one.com", "me", "old"))
        val r = upsertLogin(old, incoming("https://one.com", "me", "new"), now = 5L) { "x" }
        assertFalse(r.isNew)
        assertEquals("new", r.list.single().password)
    }

    @Test fun upsertKeepsDifferentUsersOnTheSameSite() {
        val old = listOf(login("a", "https://one.com", "me", "p1"))
        val r = upsertLogin(old, incoming("https://one.com", "other", "p2"), now = 5L) { "b" }
        assertTrue(r.isNew)
        assertEquals(2, r.list.size)
    }

    @Test fun upsertByGuidMayChangeUsername() {
        val old = listOf(login("g1", "https://one.com", "me", "p"))
        val r = upsertLogin(old, incoming("https://one.com", "me2", "p", guid = "g1"), now = 9L) { "x" }
        assertFalse(r.isNew)
        assertEquals("me2", r.saved.username)
        assertEquals(9L, r.saved.modified)
    }

    @Test fun upsertWithoutChangesKeepsModifiedTime() {
        val old = listOf(login("a", "https://one.com", "me", "p"))
        val r = upsertLogin(old, incoming("https://one.com", "me", "p"), now = 99L) { "x" }
        assertEquals(1L, r.saved.modified)
    }

    @Test fun httpRealmSeparatesAccounts() {
        val old = listOf(login("a", "https://one.com", "me", "p", realm = "Area 51"))
        val r = upsertLogin(old, incoming("https://one.com", "me", "p2"), now = 5L) { "b" }
        assertTrue(r.isNew)
    }

    @Test fun russianPlurals() {
        fun p(n: Int) = ruPlural(n, "пароль", "пароля", "паролей")
        assertEquals("0 паролей", p(0))
        assertEquals("1 пароль", p(1))
        assertEquals("2 пароля", p(2))
        assertEquals("5 паролей", p(5))
        assertEquals("11 паролей", p(11))
        assertEquals("12 паролей", p(12))
        assertEquals("14 паролей", p(14))
        assertEquals("21 пароль", p(21))
        assertEquals("22 пароля", p(22))
        assertEquals("111 паролей", p(111))
        assertEquals("101 пароль", p(101))
    }

    // ---- Генератор -----------------------------------------------------------------------------------------

    @Test fun generatedPasswordHasRequestedLengthAndAllClasses() {
        val rnd = Random(42)
        repeat(200) {
            val p = generatePassword(16, rnd)
            assertEquals(16, p.length)
            assertTrue(p.any { it.isLowerCase() })
            assertTrue(p.any { it.isUpperCase() })
            assertTrue(p.any { it.isDigit() })
            assertTrue(p.any { !it.isLetterOrDigit() })
            assertFalse(p.any { it in "lIO01" })
        }
    }

    @Test fun generatedPasswordsDiffer() {
        assertTrue(generatePassword() != generatePassword())
        assertEquals(8, generatePassword(1).length)
        assertEquals(128, generatePassword(1000).length)
    }

    // ---- CSV -----------------------------------------------------------------------------------------------

    @Test fun csvRoundTripKeepsSpecialCharacters() {
        val src = listOf(
            login("1", "https://a.com", "me@a.com", "p,w\"d"),
            login("2", "https://b.com:8443", "имя", "line1\nline2"),
            login("3", "http://c.org", "", "простой"),
        )
        val back = PasswordCsv.parse(PasswordCsv.format(src))
        assertNull(back.error)
        assertEquals(0, back.skipped)
        assertEquals(3, back.rows.size)
        for (i in src.indices) {
            assertEquals(src[i].origin, back.rows[i].origin)
            assertEquals(src[i].username, back.rows[i].username)
            assertEquals(src[i].password, back.rows[i].password)
        }
    }

    @Test fun csvHeaderMatchesChromeFormat() {
        val text = PasswordCsv.format(listOf(login("1", "https://www.a.com", "u", "p")))
        assertEquals("name,url,username,password,note\r\na.com,https://www.a.com,u,p,\r\n", text)
    }

    @Test fun csvParsesFirefoxExport() {
        val text = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\"\n" +
            "\"https://example.com\",\"bob\",\"secret\",\"\",\"https://example.com\",\"{x}\"\n"
        val r = PasswordCsv.parse(text)
        assertNull(r.error)
        assertEquals(1, r.rows.size)
        assertEquals("bob", r.rows[0].username)
        assertEquals("secret", r.rows[0].password)
    }

    @Test fun csvParsesBitwardenExportAndSemicolons() {
        val bw = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
            ",,login,Site,,,0,https://example.com/login,bob,pw1,\n"
        val r = PasswordCsv.parse(bw)
        assertNull(r.error)
        assertEquals("https://example.com", r.rows.single().origin)
        assertEquals("pw1", r.rows.single().password)

        val semi = PasswordCsv.parse("url;username;password\nhttps://a.com;u;p\n")
        assertEquals("p", semi.rows.single().password)
    }

    @Test fun csvHandlesBomCrLfAndQuotedNewlines() {
        val text = "﻿name,url,username,password,note\r\nA,https://a.com,u,\"a\r\nb\",\r\n\r\nB,https://b.com,v,x,\r\n"
        val r = PasswordCsv.parse(text)
        assertNull(r.error)
        assertEquals(2, r.rows.size)
        assertEquals("a\r\nb", r.rows[0].password)
        assertEquals("x", r.rows[1].password)
    }

    @Test fun csvSkipsUnusableRows() {
        val text = "url,username,password\n" +
            "android://abc@com.app/,u,p\n" +
            "https://ok.com,u,\n" +
            "https://ok.com,u,pw\n"
        val r = PasswordCsv.parse(text)
        assertEquals(1, r.rows.size)
        assertEquals(2, r.skipped)
    }

    @Test fun csvWithoutKnownColumnsIsAnError() {
        assertNotNull(PasswordCsv.parse("a,b,c\n1,2,3\n").error)
        assertNotNull(PasswordCsv.parse("").error)
        assertNotNull(PasswordCsv.parse("   \n").error)
    }

    @Test fun csvMergeSkipsDuplicatesAndNeverOverwrites() {
        val existing = listOf(login("1", "https://a.com", "u", "mine"))
        val rows = listOf(
            CsvLogin("https://a.com", "u", "theirs"),
            CsvLogin("https://a.com", "u2", "p"),
            CsvLogin("https://a.com", "u2", "p"),
            CsvLogin("https://b.com", "", "p"),
        )
        var n = 0
        val r = PasswordCsv.merge(existing, rows, now = 7L) { "id${n++}" }
        assertEquals(2, r.added)
        assertEquals(2, r.duplicates)
        assertEquals(3, r.list.size)
        assertEquals("mine", r.list.first { it.id == "1" }.password)
    }
}
