package com.animo.app.data

import com.animo.app.data.local.FtsQuery
import com.animo.app.data.remote.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ingest-boundary regression tests. Every case here is a value that was actually observed on the
 * live API (research/DATA_CONTRACT.md §2, §3, §7) — if one of these breaks, a sync silently corrupts
 * the catalog.
 */
class NormalizeTest {

    // ---------------------------------------------------------------- HTML entities

    @Test
    fun `episode titles are html entity escaped`() {
        assertEquals("I'm Luffy!", Normalize.cleanText("I&#39;m Luffy!"))
        assertEquals("'quoted'", Normalize.cleanText("&apos;quoted&apos;"))
        assertEquals("R&D <3", Normalize.cleanText("R&amp;D &lt;3"))
    }

    @Test
    fun `nbsp is a real season value and decodes to a blank`() {
        assertEquals("", Normalize.cleanText("&nbsp"))
        assertEquals("", Normalize.cleanText("&nbsp;"))
        assertEquals("unknown", Normalize.normalizeSeason("&nbsp"))
    }

    @Test
    fun `unknown entities are preserved verbatim`() {
        assertEquals("&notarealentity;", Normalize.cleanText("&notarealentity;"))
    }

    // ---------------------------------------------------------------- season

    @Test
    fun `all eleven observed season spellings collapse to four values`() {
        val fall = listOf("fall", "Fall", "FALL", "autumn")
        val summer = listOf("summer", "Summer")
        val spring = listOf("spring", "Spring", "SPRING")
        val winter = listOf("winter", "Winter")
        val unknown = listOf("unknown", "", "&nbsp", "  ")

        fall.forEach { assertEquals("fall: $it", "fall", Normalize.normalizeSeason(it)) }
        summer.forEach { assertEquals("summer: $it", "summer", Normalize.normalizeSeason(it)) }
        spring.forEach { assertEquals("spring: $it", "spring", Normalize.normalizeSeason(it)) }
        winter.forEach { assertEquals("winter: $it", "winter", Normalize.normalizeSeason(it)) }
        unknown.forEach { assertEquals("unknown: $it", "unknown", Normalize.normalizeSeason(it)) }
    }

    // ---------------------------------------------------------------- status / rating

    @Test
    fun `status normalises the three observed values`() {
        assertEquals("finished_airing", Normalize.normalizeStatus("Finished Airing"))
        assertEquals("currently_airing", Normalize.normalizeStatus("Currently Airing"))
        assertEquals("not_yet_aired", Normalize.normalizeStatus("Not yet aired"))
        assertEquals("", Normalize.normalizeStatus(""))
    }

    @Test
    fun `rating collapses the none variants`() {
        assertEquals("pg", Normalize.normalizeRating("PG"))
        assertEquals("pg-13", Normalize.normalizeRating("PG-13"))
        assertEquals("r+", Normalize.normalizeRating("R+"))
        assertEquals("", Normalize.normalizeRating("None"))
        assertEquals("", Normalize.normalizeRating("N/A"))
        assertEquals("", Normalize.normalizeRating(""))
    }

    // ---------------------------------------------------------------- numbers and time

    @Test
    fun `episodes is a string and empty means unknown not zero`() {
        assertEquals(12, Normalize.parseIntOrNull("12"))
        assertNull(Normalize.parseIntOrNull(""))
        assertNull(Normalize.parseIntOrNull(null))
        assertNull(Normalize.parseIntOrNull("?"))
    }

    @Test
    fun `updated_at is parsed as utc`() {
        assertEquals(1_794_850_855L, Normalize.parseUtcSeconds("2026-11-16 17:40:55"))
        assertEquals(0L, Normalize.parseUtcSeconds(""))
        assertEquals(0L, Normalize.parseUtcSeconds("not a date"))
    }

    @Test
    fun `next_air_schedule_time in the past is dropped`() {
        val now = 1_800_000_000L
        assertNull(Normalize.sanitizeNextAirEpoch(1_743_287_400L, now))
        assertEquals(1_800_003_600L, Normalize.sanitizeNextAirEpoch(1_800_003_600L, now))
    }

    @Test
    fun `slug never blank because it is a unique index`() {
        assertEquals("anime-42", Normalize.slugOrFallback("  ", 42))
        assertEquals("one-piece", Normalize.slugOrFallback("one-piece", 42))
    }

    // ---------------------------------------------------------------- csv

    @Test
    fun `genres round trip through the csv column`() {
        val csv = Normalize.joinCsv(listOf("Action", " Adventure ", "", "Fantasy"))
        assertEquals("Action, Adventure, Fantasy", csv)
        assertEquals(listOf("Action", "Adventure", "Fantasy"), Normalize.splitCsv(csv))
        assertEquals(emptyList<String>(), Normalize.splitCsv(""))
    }

    // ---------------------------------------------------------------- FTS

    @Test
    fun `fts query makes prefix terms and strips syntax`() {
        assertEquals("one* piece*", FtsQuery.build("one piece"))
        assertEquals("Re* Zero*", FtsQuery.build("Re:Zero"))
        assertEquals("naru*", FtsQuery.build("  naru  "))
    }

    @Test
    fun `fts query never emits an empty match`() {
        assertNull(FtsQuery.build(""))
        assertNull(FtsQuery.build("   "))
        assertNull(FtsQuery.build("\"'*^()-"))
    }

    @Test
    fun `fts query caps token count`() {
        val long = (1..20).joinToString(" ") { "t$it" }
        val built = FtsQuery.build(long)
        assertEquals(8, built?.split(' ')?.size)
    }
}
