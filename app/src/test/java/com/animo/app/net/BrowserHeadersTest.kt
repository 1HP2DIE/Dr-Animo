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
    fun `a top level navigation carries the full chrome set and no host of its own`() {
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
        assertNull(headers["Referer"])
        assertNull(headers["Origin"])

        // Every value is the shared UA, a constant, or derived from the caller's url. A hit here
        // would mean a real host survived into the APK as a string literal.
        assertFalse(headers.containsKey("Host"))
        assertFalse(headers.any { (_, value) -> value.contains(".invalid") })
    }

    @Test
    fun `a referer adds referer and origin and the right fetch site`() {
        val sameOrigin = BrowserHeaders.forUrl(
            url = "https://page.invalid/watch/1",
            referer = "https://page.invalid/browse",
        )
        assertEquals("https://page.invalid/browse", sameOrigin["Referer"])
        assertEquals("https://page.invalid", sameOrigin["Origin"])
        assertEquals("same-origin", sameOrigin["sec-fetch-site"])

        // Chrome's "site" is the last two labels, which is the whole public-suffix problem here.
        assertEquals(
            "same-site",
            BrowserHeaders.forUrl(
                url = "https://cdn.page.invalid/stream.m3u8",
                referer = "https://page.invalid/watch/1",
            )["sec-fetch-site"],
        )
        assertEquals(
            "cross-site",
            BrowserHeaders.forUrl(
                url = "https://embed.other.invalid/p/1",
                referer = "https://page.invalid/watch/1",
            )["sec-fetch-site"],
        )

        // A non-default port has to survive into the origin or the referer check fails.
        assertEquals(
            "https://page.invalid:8443",
            BrowserHeaders.forUrl(
                url = "https://page.invalid/watch/1",
                referer = "https://page.invalid:8443/browse",
            )["Origin"],
        )
    }
}
