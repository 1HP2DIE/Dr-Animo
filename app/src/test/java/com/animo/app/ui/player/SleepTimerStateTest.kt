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
    fun `time is consumed only while the video plays`() {
        val timer = SleepTimerState()
        timer.select(15)

        timer.tick(elapsedMs = 5_000L, playing = false)
        assertEquals(15 * minute, timer.remainingMs)

        timer.tick(elapsedMs = 5_000L, playing = true)
        assertEquals(15 * minute - 5_000L, timer.remainingMs)
    }

    @Test
    fun `the timer fires once and disarms itself`() {
        val timer = SleepTimerState()
        timer.select(15)
        timer.tick(elapsedMs = 14 * minute, playing = true)
        // 59 seconds left is not the end.
        assertFalse(timer.tick(elapsedMs = 1_000L, playing = true))

        val fired = timer.tick(elapsedMs = 59_000L, playing = true)
        assertTrue(fired)
        assertNull(timer.minutes)
        assertFalse(timer.isArmed)
        // A spent timer must not keep firing on every later tick.
        assertFalse(timer.tick(elapsedMs = 1_000L, playing = true))
    }

    @Test
    fun `the countdown rounds up so it never reads zero while running`() {
        val timer = SleepTimerState()
        timer.select(15)
        timer.tick(elapsedMs = 14 * minute + 20_000L, playing = true)
        assertEquals(1, timer.minutesLeft)
    }

    @Test
    fun `re-picking the same preset restarts the clock`() {
        val timer = SleepTimerState()
        timer.select(15)
        timer.tick(elapsedMs = 10 * minute, playing = true)
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
        assertFalse(timer.tick(elapsedMs = 10 * minute, playing = true))
        assertEquals(0, timer.minutes)
    }

    @Test
    fun `off is off`() {
        val timer = SleepTimerState()
        timer.select(30)
        timer.select(null)

        assertNull(timer.minutes)
        assertFalse(timer.isArmed)
        assertFalse(timer.tick(elapsedMs = minute, playing = true))
    }

    @Test
    fun `saved state restores the countdown`() {
        val timer = SleepTimerState()
        timer.select(45)
        timer.tick(elapsedMs = 20 * minute, playing = true)

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
