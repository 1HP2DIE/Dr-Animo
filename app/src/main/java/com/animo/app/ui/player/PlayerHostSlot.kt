package com.animo.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.animo.app.R
import com.animo.app.domain.model.PlayableStream
import com.animo.app.player.PlayerHost
import kotlinx.coroutines.delay

/**
 * Integration seam between Agent B's overlay and Agent C's Media3 engine.
 *
 * Responsibilities split:
 *  - this slot resolves the [PlayableStream] (only when [masterUrlProvider] changes, because the
 *    screen re-keys the composable on episode/audio/reload) and handles resolver failures;
 *  - [PlayerHost] owns the ExoPlayer instance, the Referer-bearing data source and the 403/410
 *    re-resolve (see research/RESOLVER_SPEC.md);
 *  - [PlayerSurfaceState] carries commands one way and 4 Hz position ticks the other way.
 */
@Composable
internal fun PlayerHostSlot(
    masterUrlProvider: suspend () -> Result<PlayableStream>,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onError: (Throwable) -> Unit,
    state: PlayerSurfaceState,
    startPositionMs: Long,
    modifier: Modifier = Modifier,
    controller: com.animo.app.player.PlayerController? = null,
    onEnded: (() -> Unit)? = null,
    onCues: ((List<String>) -> Unit)? = null,
    videoFit: VideoFit = VideoFit.FIT,
) {
    var stream by remember { mutableStateOf<PlayableStream?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var player by remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(masterUrlProvider) {
        stream = null
        failure = null
        masterUrlProvider().fold(
            onSuccess = {
                stream = it
                state.setStream(it)
            },
            onFailure = { error ->
                failure = error
                onError(error)
            },
        )
    }

    // host -> overlay: position/duration/buffered at ~4 Hz.
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            val rawDuration = active.duration
            state.update(
                positionMs = active.currentPosition.coerceAtLeast(0L),
                durationMs = if (rawDuration == C.TIME_UNSET) 0L else rawDuration.coerceAtLeast(0L),
                bufferedMs = active.bufferedPosition.coerceAtLeast(0L),
                isPlaying = active.isPlaying,
                isBuffering = active.playbackState == Player.STATE_BUFFERING,
            )
            delay(250L)
        }
    }

    // overlay -> host: register the three commands the controls overlay can issue.
    DisposableEffect(player) {
        val active = player
        if (active != null) {
            // A new source builds a new ExoPlayer, so speed has to be re-applied rather than assumed.
            active.setPlaybackSpeed(state.speed)
            state.bind(
                togglePlayPause = { if (active.isPlaying) active.pause() else active.play() },
                seekTo = { positionMs -> active.seekTo(positionMs.coerceAtLeast(0L)) },
                skipBy = { deltaMs ->
                    active.seekTo((active.currentPosition + deltaMs).coerceAtLeast(0L))
                },
                pause = { active.pause() },
                setSpeed = { value -> active.setPlaybackSpeed(value) },
            )
        }
        onDispose { state.unbind() }
    }

    val activeStream = stream
    if (activeStream == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim),
            contentAlignment = Alignment.Center,
        ) {
            if (failure == null) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            } else {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_player_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.ui_player_error_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }

    PlayerHost(
        masterUrl = activeStream.masterUrl,
        referer = activeStream.referer,
        userAgent = activeStream.userAgent,
        startPositionMs = startPositionMs,
        onProgress = onProgress,
        onError = { error -> error?.let(onError) },
        controller = controller,
        modifier = modifier,
        reResolve = { masterUrlProvider().getOrNull() },
        onPlayerAvailable = { livePlayer -> player = livePlayer },
        onEnded = onEnded,
        onCues = onCues,
        resizeMode = videoFit.resizeMode,
    )
}
