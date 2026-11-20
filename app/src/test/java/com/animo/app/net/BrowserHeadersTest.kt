package com.animo.app.net

import com.animo.app.data.remote.HttpClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fingerprint a provider fingerprints on: keys, values, and the two fetch-metadata headers. */
class BrowserHeadersTest {

    @Test
    fun `a top level navigation carries the full chrome set`() {
        val headers = BrowserHeaders.forUrl("https://page.invalid/watch/1")

        assertEquals(HttpClientFactory.UA_DESKTOP, headers["User-Agent"])
        assertEquals("en-US,en;q=0.9", headers["Accept-Language"])
        assertEquals("?0", headers["sec-ch-ua-mobile"])
        assertEquals("\"Windows\"", headers["sec-ch-ua-platform"])
        assertEquals("navigate", headers["sec-fetch-mode"])
        assertEquals("document", headers["sec-fetch-dest"])
        assertEquals("1", headers["upgrade-insecure-requests"])
        assertEquals("none", headers["sec-fetch-site"])
        assertTrue(headers.getValue("Accept").contains("text/html"))
        assertTrue(headers.getValue("sec-ch-ua").contains("Google Chrome"))
    }

    @Test
    fun `no referer means no referer or origin header`() {
        val headers = BrowserHeaders.forUrl("https://page.invalid/watch/1", referer = "  ")

        assertNull(headers["Referer"])
        assertNull(headers["Origin"])
        assertEquals("none", headers["sec-fetch-site"])
    }

    @Test
    fun `a same host referer is same-origin`() {
        val headers = BrowserHeaders.forUrl(
            url = "https://page.invalid/watch/1",
            referer = "https://page.invalid/browse",
        )

        assertEquals("https://page.invalid/browse", headers["Referer"])
        assertEquals("https://page.invalid", headers["Origin"])
        assertEquals("same-origin", headers["sec-fetch-site"])
    }

    @Test
    fun `a sibling subdomain is same-site`() {
        val headers = BrowserHeaders.forUrl(
            url = "https://cdn.page.invalid/stream.m3u8",
            referer = "https://page.invalid/watch/1",
        )

        assertEquals("same-site", headers["sec-fetch-site"])
    }

    @Test
    fun `a different registrable domain is cross-site`() {
        val headers = BrowserHeaders.forUrl(
            url = "https://embed.other.invalid/p/1",
            referer = "https://page.invalid/watch/1",
        )

        assertEquals("cross-site", headers["sec-fetch-site"])
    }

    @Test
    fun `a non default port is kept in the origin`() {
        val headers = BrowserHeaders.forUrl(
            url = "https://page.invalid/watch/1",
            referer = "https://page.invalid:8443/browse",
        )

        assertEquals("https://page.invalid:8443", headers["Origin"])
    }

    @Test
    fun `the header set names no host of its own`() {
        val headers = BrowserHeaders.forUrl("https://page.invalid/watch/1")

        // Every value is either the shared UA, a constant, or derived from the caller's url — a
        // baked-in provider host in this file would mean a host survived into the APK as a string.
        assertFalse(headers.containsKey("Host"))
        assertFalse(headers.any { (_, value) -> value.contains(".invalid") })
    }
}
