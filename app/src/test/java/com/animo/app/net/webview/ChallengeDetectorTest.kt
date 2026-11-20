package com.animo.app.net.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marker/cookie matching — the only part of the ladder that decides "browser needed". */
class ChallengeDetectorTest {

    @Test
    fun `cloudflare title is recognised`() {
        assertEquals("just a moment", ChallengeDetector.marker("Just a moment...", "<html></html>"))
    }

    @Test
    fun `interstitial body markers are recognised`() {
        assertEquals(
            "challenge-platform",
            ChallengeDetector.marker(null, "<script src=\"/cdn-cgi/challenge-platform/h/b\"></script>"),
        )
        assertEquals("ddos-guard", ChallengeDetector.marker("DDoS-Guard", null))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals("attention required", ChallengeDetector.marker("ATTENTION REQUIRED! | Cloudflare", null))
    }

    @Test
    fun `real content is not a challenge`() {
        assertNull(ChallengeDetector.marker("Episode 12 — Animo", "<html><body>stream url</body></html>"))
        assertNull(ChallengeDetector.marker(null, "<html><body><h1>Episode 12</h1></body></html>"))
    }

    @Test
    fun `json payloads are never scanned for markers`() {
        assertNull(ChallengeDetector.markerInDocument("{\"error\":\"just a moment\"}"))
        assertEquals("just a moment", ChallengeDetector.markerInDocument("<!doctype html><title>Just a moment</title>"))
    }

    @Test
    fun `clearance cookie is found among others`() {
        assertTrue(ChallengeDetector.hasClearance("__cf_bm=abc; cf_clearance=xyz; other=1"))
        assertFalse(ChallengeDetector.hasClearance("__cf_bm=abc"))
        assertFalse(ChallengeDetector.hasClearance(null))
        assertFalse(ChallengeDetector.hasClearance(""))
    }

    @Test
    fun `custom clearance names are honoured`() {
        assertTrue(ChallengeDetector.hasClearance("__ddg5_=1", setOf("__ddg5_")))
        assertFalse(ChallengeDetector.hasClearance("__ddg5_=1"))
    }

    @Test
    fun `cookie names survive spacing and flags`() {
        assertEquals(
            setOf("a", "b", "cf_clearance"),
            ChallengeDetector.cookieNames(" a=1 ; b=2; cf_clearance=x "),
        )
    }
}
