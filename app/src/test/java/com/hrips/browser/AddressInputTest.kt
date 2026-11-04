package com.hrips.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressInputTest {
    private fun url(input: String, external: Boolean = false) =
        (parseAddressInput(input, external) as? AddressInput.Url)?.url

    private fun isQuery(input: String, external: Boolean = false) =
        parseAddressInput(input, external) is AddressInput.Query

    @Test fun domainsWithoutScheme() {
        assertEquals("https://example.com", url("example.com"))
        assertEquals("https://example.com/a?b=1#c", url("  example.com/a?b=1#c "))
        assertEquals("https://sub.example.co.uk", url("sub.example.co.uk"))
        assertEquals("https://example.com:8443/x", url("example.com:8443/x"))
        assertEquals("https://example.com.", url("example.com."))
    }

    @Test fun internationalDomains() {
        assertEquals("https://пример.рф", url("пример.рф"))
        assertEquals("https://xn--e1afmkfd.xn--p1ai", url("xn--e1afmkfd.xn--p1ai"))
    }

    @Test fun localAddressesUseHttp() {
        assertEquals("http://localhost", url("localhost"))
        assertEquals("http://localhost:8080/app", url("localhost:8080/app"))
        assertEquals("http://192.168.1.1", url("192.168.1.1"))
        assertEquals("http://10.0.0.2:3000", url("10.0.0.2:3000"))
        assertEquals("http://[::1]:8080", url("[::1]:8080"))
        assertEquals("http://printer.local", url("printer.local"))
        assertEquals("https://8.8.8.8", url("8.8.8.8"))
    }

    @Test fun explicitSchemesPassThrough() {
        assertEquals("http://example.com", url("http://example.com"))
        assertEquals("HTTPS://Example.com", url("HTTPS://Example.com"))
        assertEquals("about:blank", url("about:blank"))
        assertEquals("file:///sdcard/a.html", url("file:///sdcard/a.html"))
        assertEquals("view-source:https://example.com", url("view-source:https://example.com"))
    }

    @Test fun queries() {
        listOf(
            "котики", "how to cook", "3.14", "v1.2", "c++", "what is example.com",
            "user@example.com", "mailto:a@b.com", "ответ: да", "example.com:99999", "", "   ", "a..b",
        ).forEach { assert(isQuery(it)) { "должно быть запросом: «$it»" } }
    }

    @Test fun scriptSchemesNeverExecute() {
        assert(isQuery("javascript:alert(1)"))
        assert(isQuery("JavaScript://%0aalert(document.cookie)"))
        assert(isQuery("data:text/html,<script>alert(1)</script>"))
        assert(isQuery("vbscript:msgbox"))
    }

    @Test fun externalTextOpensOnlyWeb() {
        assertEquals("https://example.com/x", url("https://example.com/x", external = true))
        assertEquals("https://example.com", url("example.com", external = true))
        assert(isQuery("file:///data/data/com.hrips.browser/files/logins.bin", external = true))
        assert(isQuery("about:config", external = true))
        assert(isQuery("moz-extension://abc/page.html", external = true))
    }
}
