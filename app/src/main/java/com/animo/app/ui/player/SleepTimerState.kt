package com.animo.app.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * The player's sleep timer.
 *
 * Held as one saveable object instead of a few loose `remember`s because the countdown used to die
 * on every rotation: the video kept playing, the timer was gone, and the only way to notice was to
 * wake up to episode twelve at 3 a.m.
 *
 * [minutes] is the choice — `null` off, `0` stop when *this* episode ends, anything else a preset.
 * Time is consumed only while playback actually runs, so pausing to answer the door pauses the
 * clock as well; that is what the viewer asked for when they paused.
 */
@Stable
class SleepTimerState(
    initialMinutes: Int? = null,
    initialRemainingMs: Long = 0L,
    initialToken: Int = 0,
) {

    var minutes by mutableStateOf(initialMinutes)
        private set

    /**
     * Playing time left in milliseconds. Written about once a second while a preset runs, and read
     * only by the sheet pane that shows it — the player stage never touches it.
     */
    var remainingMs by mutableLongStateOf(initialRemainingMs)
        private set

    /**
     * Bumped on every selection.
     *
     * The countdown effect restarts on it, which also fixes the case that looked like a dead
     * button: tapping "15 minutes" while 15 minutes was already selected used to change nothing,
     * so the clock never restarted.
     */
    var token by mutableIntStateOf(initialToken)
        private set

    val isArmed: Boolean get() = minutes != null

    /** The end-of-episode mode: armed, but with no countdown to show. */
    val stopsAtEndOfEpisode: Boolean get() = minutes == 0

    /**
     * Whole minutes still to go, rounded up.
     *
     * Rounded up, not down: a timer with 40 seconds left reads "1 min left", never "0 min left"
     * while the screen is still playing.
     */
    val minutesLeft: Int
        get() = ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt()

    fun select(minutes: Int?) {
        this.minutes = minutes
        remainingMs = (minutes ?: 0).coerceAtLeast(0) * MINUTE_MS
        token++
    }

    /**
     * Consumes [elapsedMs] of playback time.
     *
     * Returns true on the tick that runs the preset out; the timer has disarmed itself by then, so
     * the caller only has to pause. [elapsedMs] is real elapsed time rather than an assumed second,
     * so a stalled main thread cannot stretch a 30-minute timer into 40.
     */
    fun tick(elapsedMs: Long, playing: Boolean): Boolean {
        val mode = minutes ?: return false
        if (mode == 0) return false
        if (playing && elapsedMs > 0L) remainingMs = (remainingMs - elapsedMs).coerceAtLeast(0L)
        if (remainingMs > 0L) return false
        disarm()
        return true
    }

    /** Turns the timer off without pausing anything — also used when the episode ends first. */
    fun disarm() {
        minutes = null
        remainingMs = 0L
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /** `-1` stands for "off": a bundle of primitives survives process death intact. */
        val Saver: Saver<SleepTimerState, Any> = listSaver(
            save = { listOf(it.minutes ?: -1, it.remainingMs, it.token) },
            restore = { saved ->
                SleepTimerState(
                    initialMinutes = (saved[0] as Int).takeIf { it >= 0 },
                    initialRemainingMs = saved[1] as Long,
                    initialToken = saved[2] as Int,
                )
            },
        )
    }
}
