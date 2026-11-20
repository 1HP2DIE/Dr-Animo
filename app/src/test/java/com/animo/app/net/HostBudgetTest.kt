package com.animo.app.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pacing is the difference between "one extra request" and "the host blocks the app for an hour".
 * These run on a fake clock, so the suite never sleeps and never depends on wall time.
 */
class HostBudgetTest {

    private class FakeClock(var at: Long = 1_000L) {
        fun now(): Long = at
        suspend fun sleep(ms: Long) {
            at += ms
        }
    }

    private fun budget(clock: FakeClock, minIntervalMs: Long = 800L, maxBackoffMs: Long = 30_000L) =
        HostBudget(
            minIntervalMs = minIntervalMs,
            maxBackoffMs = maxBackoffMs,
            now = clock::now,
            sleeper = clock::sleep,
        )

    @Test
    fun `the first call to a host never waits`() = runBlocking {
        val clock = FakeClock()
        val host = budget(clock)

        assertEquals(0L, host.await("a.invalid"))
        assertEquals(1_000L, clock.at)
    }

    @Test
    fun `a second call inside the interval waits the remainder`() = runBlocking {
        val clock = FakeClock()
        val host = budget(clock)
        host.await("a.invalid")

        clock.at += 300L

        assertEquals(500L, host.await("a.invalid"))
        assertEquals(1_800L, clock.at)
    }

    @Test
    fun `a call after the interval has elapsed does not wait`() = runBlocking {
        val clock = FakeClock()
        val host = budget(clock)
        host.await("a.invalid")

        clock.at += 5_000L

        assertEquals(0L, host.await("a.invalid"))
    }

    @Test
    fun `a refusal arms a cooldown that the next call has to wait out`() = runBlocking {
        val clock = FakeClock()
        val host = budget(clock)

        val armed = host.onRefused("a.invalid")

        assertEquals(800L, armed)
        assertEquals(clock.now() + 800L, host.backoffUntilMs("a.invalid"))
        assertEquals(800L, host.waitForMs("a.invalid"))
        assertEquals(1, host.strikes("a.invalid"))
    }

    @Test
    fun `repeated refusals double the cooldown and stop at the cap`() {
        val clock = FakeClock()
        val host = budget(clock, minIntervalMs = 800L, maxBackoffMs = 5_000L)

        val waits = (1..5).map { host.onRefused("a.invalid") }

        assertEquals(listOf(800L, 1_600L, 3_200L, 5_000L, 5_000L), waits)
        assertEquals(5, host.strikes("a.invalid"))
    }

    @Test
    fun `a served host forgets its strikes and its cooldown`() {
        val clock = FakeClock()
        val host = budget(clock)
        host.onRefused("a.invalid")
        host.onRefused("a.invalid")

        host.onServed("a.invalid")

        assertEquals(0L, host.backoffUntilMs("a.invalid"))
        assertEquals(0, host.strikes("a.invalid"))
        assertEquals(0L, host.waitForMs("a.invalid", atMs = 0L))
    }

    @Test
    fun `hosts are paced and cooled down separately`() = runBlocking {
        val clock = FakeClock()
        val host = budget(clock)
        host.onRefused("a.invalid")

        assertEquals(800L, host.waitForMs("a.invalid"))
        assertEquals(0L, host.waitForMs("b.invalid"))
        assertEquals(0L, host.await("b.invalid"))
    }

    @Test
    fun `a rung after the first ignores the cooldown it just armed`() {
        val clock = FakeClock()
        val host = budget(clock)
        host.onRefused("a.invalid")

        // Same call, next rung: pacing still applies, the 800 ms cooldown does not.
        assertTrue(host.waitForMs("a.invalid", honorBackoff = true) > 0L)
        assertEquals(0L, host.waitForMs("a.invalid", honorBackoff = false))
    }
}
