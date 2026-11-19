package com.animo.app.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * The only thing the UI overlay and the Media3 host share.
 *
 * Flow direction:
 *  - host → UI: [update] pushes position/duration/buffered/playing/buffering.
 *  - UI → host: [bind] registers the three commands the overlay can issue.
 *
 * The class is snapshot-state backed, so the overlay recomposes on position ticks without any
 * polling. Everything is single-threaded on the main thread — the host must only call [update]
 * from the main dispatcher (ExoPlayer's listeners already do).
 *
 * Contract for `com.animo.app.player.PlayerHost`:
 * ```kotlin
 * DisposableEffect(player) {
 *     state.bind(
 *         togglePlayPause = { if (player.isPlaying) player.pause() else player.play() },
 *         seekTo = { ms -> player.seekTo(ms) },
 *         skipBy = { delta -> player.seekTo((player.currentPosition + delta).coerceAtLeast(0L)) },
 *     )
 *     onDispose { state.unbind() }
 * }
 * state.update(positionMs, durationMs, bufferedMs, player.isPlaying, playbackState == BUFFERING)
 * ```
 */
@Stable
class PlayerSurfaceState(initialSpeed: Float = 1f) {

    private val playing = mutableStateOf(false)
    private val buffering = mutableStateOf(true)
    private val position = mutableLongStateOf(0L)
    private val duration = mutableLongStateOf(0L)
    private val buffered = mutableLongStateOf(0L)
    private val speedState = mutableStateOf(initialSpeed)
    private val introState = mutableStateOf<LongRange?>(null)
    private val outroState = mutableStateOf<LongRange?>(null)
    private val streamState = mutableStateOf<com.animo.app.domain.model.PlayableStream?>(null)

    private var togglePlayPauseCommand: (() -> Unit)? = null
    private var seekToCommand: ((Long) -> Unit)? = null
    private var skipByCommand: ((Long) -> Unit)? = null
    private var pauseCommand: (() -> Unit)? = null
    private var stopCommand: (() -> Unit)? = null
    private var speedCommand: ((Float) -> Unit)? = null

    /** When [update] last ran — the base for [clockPositionMs]'s interpolation. */
    private var positionStampMs = 0L

    val isPlaying: Boolean get() = playing.value
    val isBuffering: Boolean get() = buffering.value
    val positionMs: Long get() = position.value
    val durationMs: Long get() = duration.value
    val bufferedMs: Long get() = buffered.value

    /** Playback speed as last requested by the UI; survives a source swap because the slot re-applies it. */
    val speed: Float get() = speedState.value

    /** Opening song, when the source marks one. Drives "Skip intro" and the seek-bar chapters. */
    val intro: LongRange? get() = introState.value

    /** Ending song, when the source marks one. Drives the "Up next" card. */
    val outro: LongRange? get() = outroState.value

    /**
     * The resolved stream, for what rides along with the video: side-car subtitles, the preview
     * sprite index, and the referer/user agent both of those hosts insist on.
     */
    val stream: com.animo.app.domain.model.PlayableStream? get() = streamState.value

    fun setStream(stream: com.animo.app.domain.model.PlayableStream) {
        streamState.value = stream
        setMarkers(stream.intro, stream.outro)
    }

    /**
     * Sources send `0..0` for "no marker" and the odd 1-second range; anything under five seconds
     * is not a song and must not grow a skip button.
     */
    fun setMarkers(intro: LongRange?, outro: LongRange?) {
        introState.value = intro?.takeIf { it.last - it.first >= MIN_MARKER_MS }
        outroState.value = outro?.takeIf { it.last - it.first >= MIN_MARKER_MS }
    }

    fun update(
        positionMs: Long,
        durationMs: Long,
        bufferedMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
    ) {
        position.value = positionMs.coerceAtLeast(0L)
        duration.value = durationMs.coerceAtLeast(0L)
        buffered.value = bufferedMs.coerceAtLeast(0L)
        playing.value = isPlaying
        buffering.value = isBuffering
        positionStampMs = SystemClock.elapsedRealtime()
    }

    /**
     * Position for things that must land inside a frame — subtitles today.
     *
     * The host pushes a position four times a second, which is plenty for a seek bar and visibly
     * late for a cue that starts on a cut. While playback is actually advancing, this extrapolates
     * from the last tick; paused and buffering fall back to the plain value so a paused frame never
     * drifts a cue onto the screen.
     */
    fun clockPositionMs(): Long {
        val base = position.value
        if (!playing.value || buffering.value) return base
        val elapsed = (SystemClock.elapsedRealtime() - positionStampMs).coerceAtLeast(0L)
        // At 2x the playhead advances two milliseconds per millisecond of clock.
        val predicted = base + (elapsed * speedState.value).toLong()
        val total = duration.value
        return if (total > 0L) predicted.coerceAtMost(total) else predicted
    }

    fun bind(
        togglePlayPause: () -> Unit,
        seekTo: (Long) -> Unit,
        skipBy: (Long) -> Unit,
        pause: () -> Unit = {},
        stop: () -> Unit = {},
        setSpeed: (Float) -> Unit = {},
    ) {
        togglePlayPauseCommand = togglePlayPause
        seekToCommand = seekTo
        skipByCommand = skipBy
        pauseCommand = pause
        stopCommand = stop
        speedCommand = setSpeed
    }

    fun unbind() {
        togglePlayPauseCommand = null
        seekToCommand = null
        skipByCommand = null
        pauseCommand = null
        stopCommand = null
        speedCommand = null
        buffering.value = false
    }

    fun togglePlayPause() {
        togglePlayPauseCommand?.invoke()
    }

    fun seekTo(positionMs: Long) {
        // Move the reported playhead now: the host's next tick is up to 250 ms away, and until then
        // the clock would snap back to where the seek started.
        val target = positionMs.coerceAtLeast(0L)
        position.value = target
        positionStampMs = SystemClock.elapsedRealtime()
        seekToCommand?.invoke(target)
    }

    fun skipBy(deltaMs: Long) {
        skipByCommand?.invoke(deltaMs)
    }

    /** Used by the sleep timer: pause without toggling (a timer must never resume playback). */
    fun pause() {
        pauseCommand?.invoke()
    }

    /**
     * Stop, not pause: the sleep timer is done with this episode and nothing may keep decoding (or
     * keep a surface alive) while the phone is supposed to go to sleep.
     */
    fun stop() {
        stopCommand?.invoke()
    }

    fun setSpeed(value: Float) {
        speedState.value = value
        speedCommand?.invoke(value)
    }

    private companion object {
        const val MIN_MARKER_MS = 5_000L
    }
}
