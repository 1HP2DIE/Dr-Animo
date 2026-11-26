package com.animo.app.net

import com.animo.app.data.remote.HttpClientFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The redirect upgrade, which is the difference between "the app cannot reach this catalogue at all"
 * and a working play.
 *
 * Measured on the second catalogue's mirror: an `https` page answers
 * `302 Location: http://<site>/…` and the very next hop is `301 https://…`. Cleartext is off
 * app-wide, so without the rewrite OkHttp kills the request before it reaches the TLS hop.
 */
class CleartextRedirectTest {

    @Test
    fun `an http redirect target is upgraded to https`() {
        assertEquals(
            "https://movielinkbd.tv/p/token123",
            HttpClientFactory.upgradeRedirect("http://movielinkbd.tv/p/token123"),
        )
    }

    @Test
    fun `the upgrade keeps the whole path and query`() {
        assertEquals(
            "https://site.example/series/x?ep=12&a=b",
            HttpClientFactory.upgradeRedirect("http://site.example/series/x?ep=12&a=b"),
        )
    }

    @Test
    fun `an https target is left exactly as it was`() {
        assertEquals(
            "https://site.example/a",
            HttpClientFactory.upgradeRedirect("https://site.example/a"),
        )
    }

    @Test
    fun `a relative target is not touched`() {
        // Location may be relative; there is nothing to upgrade and it must not be mangled.
        assertEquals("/p/token123", HttpClientFactory.upgradeRedirect("/p/token123"))
        assertEquals("", HttpClientFactory.upgradeRedirect(""))
    }

    @Test
    fun `only the scheme is matched, not a host that starts with http`() {
        assertEquals(
            "https://httpbin.org/get",
            HttpClientFactory.upgradeRedirect("http://httpbin.org/get"),
        )
    }
}
