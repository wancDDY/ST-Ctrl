package com.tavern.app.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tavern window can load pages from anywhere, so what decides whether one of
 * them may reach the local tavern is the origin the browser attaches to the
 * request. These cases pin that decision down, including the ones that must not
 * disturb the tavern's own traffic.
 */
class TavernAccessPolicyTest {

    private fun verdict(
        url: String,
        referer: String? = null,
        origin: String? = null,
        method: String = "GET",
        granted: Set<String> = emptySet(),
    ) = TavernAccessPolicy.verdict(
        url,
        method,
        buildMap {
            referer?.let { put("Referer", it) }
            origin?.let { put("Origin", it) }
        },
    ) { it in granted }

    @Test
    fun `loopback hosts are the tavern`() {
        assertTrue(TavernAccessPolicy.isTavernHost("127.0.0.1"))
        assertTrue(TavernAccessPolicy.isTavernHost("LOCALHOST"))
        assertTrue(TavernAccessPolicy.isTavernHost("localhost."))
        assertTrue(TavernAccessPolicy.isTavernHost("::1"))
        assertFalse(TavernAccessPolicy.isTavernHost("not127.0.0.1"))
        assertFalse(TavernAccessPolicy.isTavernHost("example.com"))
        assertFalse(TavernAccessPolicy.isTavernHost(null))
    }

    @Test
    fun `requests that are not aimed at the tavern are untouched`() {
        assertEquals(
            TavernAccessPolicy.Verdict.Unrelated,
            verdict("https://example.com/api", referer = "https://other.com/"),
        )
    }

    @Test
    fun `the tavern page reaches itself`() {
        assertEquals(
            TavernAccessPolicy.Verdict.Allow,
            verdict("http://127.0.0.1:8000/api/ping", referer = "http://127.0.0.1:8000/"),
        )
    }

    @Test
    fun `an anonymous read is left alone but an anonymous write is not`() {
        // A response the caller cannot read needs no permission; the tavern's own
        // service worker traffic looks like this and must not be interrupted.
        assertEquals(
            TavernAccessPolicy.Verdict.Allow,
            verdict("http://localhost:8000/api/ping"),
        )
        assertEquals(
            TavernAccessPolicy.Verdict.Allow,
            verdict("http://localhost:8000/api/ping", origin = "null"),
        )
        // Nothing that could change state goes through without asking.
        assertEquals(
            TavernAccessPolicy.Verdict.Ask(""),
            verdict("http://localhost:8000/api/chats/save", method = "POST"),
        )
        assertEquals(
            TavernAccessPolicy.Verdict.Ask(""),
            verdict("http://localhost:8000/api/chats/delete", method = "DELETE"),
        )
    }

    @Test
    fun `another site is put to the user`() {
        assertEquals(
            TavernAccessPolicy.Verdict.Ask("workshop.example"),
            verdict("http://127.0.0.1:8000/api/ping", referer = "https://workshop.example/index.html"),
        )
        // Origin is used when the referrer was suppressed.
        assertEquals(
            TavernAccessPolicy.Verdict.Ask("other.example"),
            verdict("http://127.0.0.1:8000/api/ping", origin = "https://other.example"),
        )
    }

    @Test
    fun `an allowed site is not asked again`() {
        assertEquals(
            TavernAccessPolicy.Verdict.Allow,
            verdict(
                "http://127.0.0.1:8000/api/ping",
                referer = "https://workshop.example/index.html",
                granted = setOf("workshop.example"),
            ),
        )
    }

    @Test
    fun `hosts are read from full urls`() {
        assertEquals("example.com", TavernAccessPolicy.hostOf("https://Example.COM:8443/a?b=1"))
        assertNull(TavernAccessPolicy.hostOf("not a url"))
        assertNull(TavernAccessPolicy.hostOf(null))
    }

    @Test
    fun `the reported source is the complete address of the asking page`() {
        assertEquals(
            "https://workshop.example/index.html",
            TavernAccessPolicy.requesterUrl(mapOf("Referer" to "https://workshop.example/index.html")),
        )
        // The origin stands in when the referrer was suppressed.
        assertEquals(
            "https://other.example",
            TavernAccessPolicy.requesterUrl(mapOf("Origin" to "https://other.example")),
        )
        // Nothing to name the page with: both headers can be withheld, and "null"
        // names no site either.
        assertNull(TavernAccessPolicy.requesterUrl(emptyMap()))
        assertNull(TavernAccessPolicy.requesterUrl(mapOf("Origin" to "null")))
    }
}
