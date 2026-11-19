package com.animo.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerStateTest {

    private val minute = 60_000L

    @Test
    fun `selecting a preset arms it with the full countdown`() {
        val timer = SleepTimerState()
        timer.select(30)

        assertEquals(30, timer.minutes)
        assertEquals(30 * minute, timer.remainingMs)
        assertEquals(30, timer.minutesLeft)
        assertTrue(timer.isArmed)
        assertFalse(timer.stopsAtEndOfEpisode)
    }

    @Test
    fun `the countdown is wall clock, so it also runs while the video is paused`() {
        val timer = SleepTimerState()
        timer.select(15)

        // Nothing here says whether the video is playing: a sleep timer measures the phone being
        // on, not the footage being watched.
        timer.tick(elapsedMs = 5_000L)
        timer.tick(elapsedMs = 5_000L)
        assertEquals(15 * minute - 10_000L, timer.remainingMs)
    }

    @Test
    fun `the timer fires once and disarms itself`() {
        val timer = SleepTimerState()
        timer.select(15)
        timer.tick(elapsedMs = 14 * minute)
        // 59 seconds left is not the end.
        assertFalse(timer.tick(elapsedMs = 1_000L))

        val fired = timer.tick(elapsedMs = 59_000L)
        assertTrue(fired)
        assertNull(timer.minutes)
        assertFalse(timer.isArmed)
        // A spent timer must not keep firing on every later tick.
        assertFalse(timer.tick(elapsedMs = 1_000L))
    }

    @Test
    fun `a long gap still fires instead of overshooting`() {
        val timer = SleepTimerState()
        timer.select(15)
        // The process was frozen for an hour (doze, a stalled main thread): the timer is over.
        assertTrue(timer.tick(elapsedMs = 60 * minute))
        assertEquals(0L, timer.remainingMs)
    }

    @Test
    fun `the countdown rounds up so it never reads zero while running`() {
        val timer = SleepTimerState()
        timer.select(15)
        timer.tick(elapsedMs = 14 * minute + 20_000L)
        assertEquals(1, timer.minutesLeft)
    }

    @Test
    fun `re-picking the same preset restarts the clock`() {
        val timer = SleepTimerState()
        timer.select(15)
        timer.tick(elapsedMs = 10 * minute)
        val tokenAfterFirst = timer.token

        timer.select(15)
        assertEquals(15 * minute, timer.remainingMs)
        assertTrue("re-selecting must restart the countdown effect", timer.token > tokenAfterFirst)
    }

    @Test
    fun `end of episode mode does not run a countdown`() {
        val timer = SleepTimerState()
        timer.select(0)

        assertTrue(timer.stopsAtEndOfEpisode)
        assertTrue(timer.isArmed)
        assertFalse(timer.tick(elapsedMs = 10 * minute))
        assertEquals(0, timer.minutes)
    }

    @Test
    fun `off is off`() {
        val timer = SleepTimerState()
        timer.select(30)
        timer.select(null)

        assertNull(timer.minutes)
        assertFalse(timer.isArmed)
        assertFalse(timer.tick(elapsedMs = minute))
    }

    @Test
    fun `saved state restores the countdown`() {
        val timer = SleepTimerState()
        timer.select(45)
        timer.tick(elapsedMs = 20 * minute)

        val saved = with(SleepTimerState.Saver) { SaverScopeStub.save(timer) } ?: error("no saved state")
        val restored = SleepTimerState.Saver.restore(saved) ?: error("no restored timer")

        assertEquals(45, restored.minutes)
        assertEquals(25 * minute, restored.remainingMs)
        assertEquals(timer.token, restored.token)
    }

    @Test
    fun `off survives a save`() {
        val timer = SleepTimerState()
        val saved = with(SleepTimerState.Saver) { SaverScopeStub.save(timer) } ?: error("no saved state")
        assertNull(SleepTimerState.Saver.restore(saved)?.minutes)
    }
}

/** `listSaver` only needs a scope for `canBeSaved`; primitives always pass. */
private object SaverScopeStub : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}
