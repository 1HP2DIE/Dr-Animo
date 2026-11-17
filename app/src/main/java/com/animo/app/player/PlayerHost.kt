@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animo.app.player

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.animo.app.domain.model.PlayableStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The player surface. One [ExoPlayer] exists per active source and is released on dispose; Media3
 * has no Compose UI at 1.5.1, so this wraps `PlayerView` in an `AndroidView`.
 *
 * Every source is built by [Media3Factory], which creates a fresh `OkHttpDataSource.Factory` with
 * the Referer headers — required for playlist, variant and segment requests alike
 * (research/RESOLVER_SPEC.md §3).
 *
 * @param startPositionMs resume target, read once per [masterUrl]/[referer]/[userAgent] identity.
 * @param onError `null` clears a previous error (e.g. playback became READY).
 * @param reResolve optional one-shot re-resolve used on 403/410 before the error is surfaced. It
 *        should return the freshly resolved stream (the resolver rotates referers internally).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerHost(
    masterUrl: String,
    referer: String,
    userAgent: String,
    startPositionMs: Long,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onError: (ResolveException?) -> Unit,
    controller: PlayerController?,
    modifier: Modifier = Modifier,
    reResolve: (suspend () -> PlayableStream?)? = null,
    /** Integration hook for the UI overlay: receives the live player, and `null` on dispose. */
    onPlayerAvailable: ((Player?) -> Unit)? = null,
    /** Fired once when playback reaches the end, so the caller can honour "autoplay next". */
    onEnded: (() -> Unit)? = null,
    /**
     * The subtitle lines that are on screen right now, straight from the selected container track.
     * Empty when nothing is showing. The app draws these itself ([com.animo.app.ui.player.SubtitleOverlay])
     * so the viewer's colour/size/position settings apply; `PlayerView`'s own subtitle view is hidden.
     */
    onCues: ((List<String>) -> Unit)? = null,
    /** An `AspectRatioFrameLayout.RESIZE_MODE_*`; applied live, no rebuild. */
    resizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val client = Media3Factory.playbackClient

    var source by remember(masterUrl, referer, userAgent) {
        mutableStateOf(
            ActiveSource(
                masterUrl = masterUrl,
                referer = referer,
                userAgent = userAgent,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
            ),
        )
    }

    // Keyed by the caller's masterUrl, not by the active source: a re-resolved stream must not reset
    // the retry budget, otherwise a persistently blocked CDN could re-resolve forever.
    var reResolveAttempts by remember(masterUrl) { mutableStateOf(0) }
    val maxReResolveAttempts = if (reResolve == null) 0 else 1

    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnError by rememberUpdatedState(onError)
    val latestReResolve by rememberUpdatedState(reResolve)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnCues by rememberUpdatedState(onCues)

    val player = remember(source) {
        Media3Factory.buildPlayer(
            context = context.applicationContext,
            masterUrl = source.masterUrl,
            referer = source.referer,
            userAgent = source.userAgent,
            client = client,
            mediaId = source.masterUrl,
        )
    }

    var playing by remember(player) { mutableStateOf(false) }

    DisposableEffect(player, source) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) latestOnError(null)
                if (playbackState == Player.STATE_ENDED) latestOnEnded?.invoke()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                // Timing is the player's job; these are the lines that belong on screen right now.
                val lines = cueGroup.cues.mapNotNull { cue ->
                    cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }
                latestOnCues?.invoke(lines)
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = error.httpStatusCode()
                if (code == 403 || code == 410) {
                    // RESOLVER_SPEC §5: any 403/410 invalidates the cached manifest.
                    ResolveCache.shared.invalidateByMasterUrl(source.masterUrl)
                }
                val canRetry = (code == 403 || code == 410) && reResolveAttempts < maxReResolveAttempts
                if (!canRetry) {
                    latestOnError(error.toResolveException())
                    return
                }
                val reResolver = latestReResolve ?: return
                reResolveAttempts += 1
                val position = player.currentPosition.coerceAtLeast(0L)
                scope.launch {
                    val fresh = try {
                        reResolver()
                    } catch (e: Exception) {
                        null
                    }
                    if (fresh != null) {
                        // Swapping [source] rebuilds the player at the failure position; the old
                        // instance is released by this effect's onDispose.
                        source = ActiveSource(
                            masterUrl = fresh.masterUrl,
                            referer = fresh.referer,
                            userAgent = fresh.userAgent,
                            startPositionMs = position,
                        )
                    } else {
                        latestOnError(error.toResolveException())
                    }
                }
            }
        }

        player.addListener(listener)
        player.seekTo(source.startPositionMs)
        player.prepare()
        player.playWhenReady = true
        onPlayerAvailable?.invoke(player)

        onDispose {
            onPlayerAvailable?.invoke(null)
            player.removeListener(listener)
            player.release()
        }
    }

    // Resume/parent progress: one tick per second, independent of recomposition.
    LaunchedEffect(player, source) {
        while (true) {
            val rawDuration = player.duration
            val duration = if (rawDuration == C.TIME_UNSET) 0L else rawDuration.coerceAtLeast(0L)
            val position = player.currentPosition.coerceAtLeast(0L)
            latestOnProgress(position, duration)
            controller?.noteProgress(position, duration)
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(player, controller) {
        controller?.attach(player)
        onDispose { controller?.detach(player) }
    }

    val noSwap = remember { MutableStateFlow<StreamSwap?>(null) }
    val pendingSwap by (controller?.pendingSwap ?: noSwap).collectAsState()

    LaunchedEffect(pendingSwap?.id) {
        val swap = pendingSwap ?: return@LaunchedEffect
        controller?.consumeSwap(swap.id)
        source = ActiveSource(
            masterUrl = swap.stream.masterUrl,
            referer = swap.stream.referer,
            userAgent = swap.stream.userAgent,
            startPositionMs = swap.positionMs.coerceAtLeast(0L),
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // The Compose overlay is the only chrome. Media3's own controller draws a second set
                // of controls (gear, ±5 s, its own seek bar and clock) on every tap, and the two
                // never agree — measured on device.
                setUseController(false)
                // The Compose overlay draws subtitles with the viewer's own style; Media3's view
                // takes one fixed style and would paint a second copy underneath.
                subtitleView?.let { it.visibility = android.view.View.GONE }
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            view.player = player
            view.keepScreenOn = playing
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
        },
        onRelease = { view ->
            view.player = null
        },
    )
}

private const val PROGRESS_INTERVAL_MS = 1_000L

/** The currently loaded source; changing it builds a new player at [startPositionMs]. */
private data class ActiveSource(
    val masterUrl: String,
    val referer: String,
    val userAgent: String,
    val startPositionMs: Long,
)
