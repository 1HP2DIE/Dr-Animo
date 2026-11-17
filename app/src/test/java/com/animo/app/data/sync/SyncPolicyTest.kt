package com.animo.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a nightly run is allowed to touch. A regression here is not a crash —
 * it is silently either (a) 9,404 detail requests or (b) a show that never gets its new episodes.
 */
class SyncPolicyTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private fun due(
        status: String = SyncPolicy.STATUS_FINISHED,
        declared: Int = 12,
        stored: Int = 12,
        fetchedAt: Long? = now - day,
    ) = SyncPolicy.dueAt(status, declared, stored, fetchedAt)

    // ---------------------------------------------------------------- never again

    @Test
    fun `a finished title with a complete episode list is never due`() {
        assertNull(due(status = SyncPolicy.STATUS_FINISHED, declared = 24, stored = 24))
        assertFalse(SyncPolicy.isDue(SyncPolicy.STATUS_FINISHED, 24, 24, now - 365 * day, now))
    }

    @Test
    fun `a finished title with a known gap keeps retrying weekly`() {
        assertEquals(
            now - day + SyncPolicy.GAP_INTERVAL_MS,
            due(status = SyncPolicy.STATUS_FINISHED, declared = 24, stored = 5),
        )
        assertTrue(SyncPolicy.isDue(SyncPolicy.STATUS_FINISHED, 24, 5, now - 8 * day, now))
    }

    @Test
    fun `a finished title that was never detailed is never fetched in the background`() {
        // 8,967 rows are in this state; fetching them blind is 5.5 h of requests.
        assertNull(due(status = SyncPolicy.STATUS_FINISHED, declared = 12, stored = 0, fetchedAt = null))
        assertFalse(SyncPolicy.isDue(SyncPolicy.STATUS_FINISHED, 12, 0, null, now))
    }

    @Test
    fun `opening a never-detailed finished title does fetch it`() {
        assertTrue(SyncPolicy.shouldFetchOnOpen(SyncPolicy.STATUS_FINISHED, 12, 0, null, now))
    }

    @Test
    fun `opening a complete finished title never fetches it`() {
        assertFalse(SyncPolicy.shouldFetchOnOpen(SyncPolicy.STATUS_FINISHED, 24, 24, now - 365 * day, now))
    }

    @Test
    fun `a never-detailed airing title is due in the background`() {
        assertEquals(0L, due(status = SyncPolicy.STATUS_AIRING, declared = 12, stored = 0, fetchedAt = null))
    }

    @Test
    fun `declared zero is not treated as complete and waits the long window`() {
        // 2,005 TV rows declare 0 episodes; they must not be parked forever...
        assertEquals(
            now - day + SyncPolicy.UNKNOWN_RECHECK_MS,
            due(status = SyncPolicy.STATUS_FINISHED, declared = 0, stored = 0),
        )
        // ...but they are still only rechecked monthly, never weekly.
        assertFalse(SyncPolicy.isDue(SyncPolicy.STATUS_FINISHED, 0, 0, now - 20 * day, now))
    }

    // ---------------------------------------------------------------- weekly

    @Test
    fun `an airing title is due once a week`() {
        assertEquals(
            now - day + SyncPolicy.ONGOING_INTERVAL_MS,
            due(status = SyncPolicy.STATUS_AIRING, declared = 12, stored = 12),
        )
    }

    @Test
    fun `an airing title fetched yesterday is not due`() {
        assertFalse(SyncPolicy.isDue(SyncPolicy.STATUS_AIRING, 12, 12, now - day, now))
    }

    @Test
    fun `an airing title fetched eight days ago is due`() {
        assertTrue(SyncPolicy.isDue(SyncPolicy.STATUS_AIRING, 12, 12, now - 8 * day, now))
    }

    @Test
    fun `a complete airing title is still polled weekly`() {
        // Completeness only stops the finished branch; an airing show keeps growing.
        assertTrue(SyncPolicy.isDue(SyncPolicy.STATUS_AIRING, 24, 24, now - 8 * day, now))
    }

    @Test
    fun `an upcoming title uses the same weekly cadence`() {
        assertFalse(SyncPolicy.isDue(SyncPolicy.STATUS_UPCOMING, 0, 0, now - day, now))
        assertTrue(SyncPolicy.isDue(SyncPolicy.STATUS_UPCOMING, 0, 0, now - 8 * day, now))
    }

    // ---------------------------------------------------------------- blank status

    @Test
    fun `a blank status is retried monthly, not nightly`() {
        assertFalse(SyncPolicy.isDue("", 0, 0, now - 20 * day, now))
        assertTrue(SyncPolicy.isDue("", 0, 0, now - 40 * day, now))
    }

    // ---------------------------------------------------------------- priority

    @Test
    fun `a brand new row outranks a gap`() {
        assertEquals(
            0,
            SyncPolicy.priority(
                isNew = true,
                episodesDeclared = 12,
                episodeCount = 0,
                statusNorm = SyncPolicy.STATUS_FINISHED,
            ),
        )
        assertEquals(
            1,
            SyncPolicy.priority(
                isNew = false,
                episodesDeclared = 12,
                episodeCount = 0,
                statusNorm = SyncPolicy.STATUS_FINISHED,
            ),
        )
    }

    @Test
    fun `a gap outranks an airing title`() {
        assertEquals(1, SyncPolicy.priority(false, 12, 3, SyncPolicy.STATUS_AIRING))
        assertEquals(2, SyncPolicy.priority(false, 12, 12, SyncPolicy.STATUS_AIRING))
    }

    @Test
    fun `a complete finished row is last`() {
        assertEquals(3, SyncPolicy.priority(false, 12, 12, SyncPolicy.STATUS_FINISHED))
    }
}
