package com.hrips.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitClearAndPushTest {
    @Test
    fun exitClearMask() {
        assertEquals(15, ExitClear.ALL)
        assertFalse(ExitClear.has(ExitClear.DEFAULT, ExitClear.COOKIES))
        assertFalse(ExitClear.has(ExitClear.DEFAULT, ExitClear.TABS))
        assertEquals(0, ExitClear.DEFAULT)
        assertEquals(ExitClear.TABS, ExitClear.sanitize(ExitClear.TABS or 64 or 1024))
        assertEquals("ничего", ExitClear.describe(0))
        assertEquals("ничего", ExitClear.describe(ExitClear.DEFAULT))
        assertEquals("вкладки, история, кэш", ExitClear.describe(ExitClear.TABS or ExitClear.HISTORY or ExitClear.CACHE))
        assertEquals("вкладки, история, cookies и данные сайтов, кэш", ExitClear.describe(ExitClear.ALL))
    }

    @Test
    fun pushScopePrivacy() {
        assertTrue(WebPushScope.isPrivate("https://example.com/^privateBrowsingId=1"))
        assertTrue(WebPushScope.isPrivate("https://example.com/^geckoViewSessionContextId=abc&privateBrowsingId=1"))
        assertFalse(WebPushScope.isPrivate("https://example.com/^userContextId=1"))
        assertFalse(WebPushScope.isPrivate("https://example.com/"))
        assertFalse(WebPushScope.isPrivate("https://example.com/privateBrowsingId=1"))
    }

    @Test
    fun pushScopeHostAndOrigin() {
        assertEquals("example.com", WebPushScope.host("https://www.example.com/app/^userContextId=1"))
        assertEquals("сайт", WebPushScope.host("not a url"))
        assertTrue(WebPushScope.isWebScope("https://example.com/"))
        assertTrue(WebPushScope.isWebScope("http://localhost:8080/"))
        assertFalse(WebPushScope.isWebScope("http://example.com/"))
        assertFalse(WebPushScope.isWebScope("moz-extension://abc/"))
    }

    @Test
    fun pushInstanceIsStableAndSafe() {
        val a = WebPushScope.instanceOf("https://example.com/")
        assertEquals(a, WebPushScope.instanceOf("https://example.com/"))
        assertNotEquals(a, WebPushScope.instanceOf("https://example.com/^userContextId=1"))
        assertTrue(a.matches(Regex("wp-[0-9a-f]{32}")))
    }
}
