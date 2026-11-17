package com.animo.app.ui.player

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
class PlayerSurfaceState {

    private val playing = mutableStateOf(false)
    private val buffering = mutableStateOf(true)
    private val position = mutableLongStateOf(0L)
    private val duration = mutableLongStateOf(0L)
    private val buffered = mutableLongStateOf(0L)
    private val speedState = mutableStateOf(1f)

    private var togglePlayPauseCommand: (() -> Unit)? = null
    private var seekToCommand: ((Long) -> Unit)? = null
    private var skipByCommand: ((Long) -> Unit)? = null
    private var pauseCommand: (() -> Unit)? = null
    private var speedCommand: ((Float) -> Unit)? = null

    val isPlaying: Boolean get() = playing.value
    val isBuffering: Boolean get() = buffering.value
    val positionMs: Long get() = position.value
    val durationMs: Long get() = duration.value
    val bufferedMs: Long get() = buffered.value

    /** Playback speed as last requested by the UI; survives a source swap because the slot re-applies it. */
    val speed: Float get() = speedState.value

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
    }

    fun bind(
        togglePlayPause: () -> Unit,
        seekTo: (Long) -> Unit,
        skipBy: (Long) -> Unit,
        pause: () -> Unit = {},
        setSpeed: (Float) -> Unit = {},
    ) {
        togglePlayPauseCommand = togglePlayPause
        seekToCommand = seekTo
        skipByCommand = skipBy
        pauseCommand = pause
        speedCommand = setSpeed
    }

    fun unbind() {
        togglePlayPauseCommand = null
        seekToCommand = null
        skipByCommand = null
        pauseCommand = null
        speedCommand = null
        buffering.value = false
    }

    fun togglePlayPause() {
        togglePlayPauseCommand?.invoke()
    }

    fun seekTo(positionMs: Long) {
        seekToCommand?.invoke(positionMs.coerceAtLeast(0L))
    }

    fun skipBy(deltaMs: Long) {
        skipByCommand?.invoke(deltaMs)
    }

    /** Used by the sleep timer: pause without toggling (a timer must never resume playback). */
    fun pause() {
        pauseCommand?.invoke()
    }

    fun setSpeed(value: Float) {
        speedState.value = value
        speedCommand?.invoke(value)
    }
}
