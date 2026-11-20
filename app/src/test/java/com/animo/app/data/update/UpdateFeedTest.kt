package com.animo.app.data.update

import com.animo.app.ui.changelog.ChangeKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed is remote input from a static file: it must survive unknown fields, a missing `latest`
 * block and junk change kinds without throwing, and the version comparison must be numeric.
 */
class UpdateFeedTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val sample = """
        {
          "schema": 1,
          "generated": "2026-11-20T05:42:39Z",
          "repo": "1HP2DIE/Dr-Animo",
          "fieldFromTheFuture": {"nested": [1, 2, 3]},
          "latest": {
            "version": "1.10.0",
            "versionCode": 14,
            "date": "22 Nov 2026",
            "headline": "Faster starts.",
            "apkUrl": "https://example.invalid/Animo-1.10.0.apk",
            "sizeBytes": 1234567,
            "releaseUrl": "https://example.invalid/r"
          },
          "releases": [
            {
              "version": "1.10.0",
              "versionCode": 14,
              "tag": "v1.10.0",
              "date": "22 Nov 2026",
              "headline": "Faster starts.",
              "changes": [
                {"kind": "NEW", "text": "A new shelf"},
                {"kind": "fixed", "text": "A reopened bug"},
                {"kind": "REMOVED", "text": "kind the app does not know"},
                {"kind": "FIXED", "text": "   "}
              ]
            },
            {"version": "1.9.0", "versionCode": 13, "changes": []}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a real feed and ignores unknown fields`() {
        val feed = json.decodeFromString<UpdateFeed>(sample)

        assertEquals("1.10.0", feed.latest?.version)
        assertEquals(14, feed.latest?.versionCode)
        assertEquals(2, feed.releases.size)
        assertEquals("A new shelf", feed.releases.first().changes.first().text)
    }

    @Test
    fun `maps feed changes onto the changelog types and drops unusable rows`() {
        val release = json.decodeFromString<UpdateFeed>(sample).releases.first().toRelease()

        assertEquals("1.10.0", release.version)
        assertEquals("22 Nov 2026", release.date)
        // NEW + lowercase "fixed" survive; the unknown kind and the blank text do not.
        assertEquals(2, release.changes.size)
        assertEquals(ChangeKind.NEW, release.changes[0].kind)
        assertEquals(ChangeKind.FIXED, release.changes[1].kind)
    }

    @Test
    fun `an empty or malformed change row maps to null`() {
        assertNull(FeedChange("SOMETHING", "text").toChange())
        assertNull(FeedChange("NEW", "   ").toChange())
    }

    @Test
    fun `newer releases are filtered and ordered numerically not alphabetically`() {
        val feed = json.decodeFromString<UpdateFeed>(sample)
        // Pretend this build is 1.9.0 (code 13) — 1.10.0 must win even though "1.10.0" < "1.9.0" as text.
        val newer = feed.releases.filter { it.versionCode > 13 }.sortedByDescending { it.versionCode }
        assertEquals(1, newer.size)
        assertEquals("1.10.0", newer.first().version)
        assertTrue(newer.first().versionCode > 13)
        assertFalse(feed.releases[1].versionCode > 13)
    }

    @Test
    fun `a feed without a latest block is not an update`() {
        val feed = json.decodeFromString<UpdateFeed>("""{"releases": []}""")
        assertNull(feed.latest)
        assertTrue(feed.releases.isEmpty())
    }
}
