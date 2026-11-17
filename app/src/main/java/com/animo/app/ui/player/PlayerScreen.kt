package com.animo.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.R
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.player.PlayerController
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.LoadingState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.displayTitle
import com.animo.app.ui.common.formatClock
import com.animo.app.ui.common.labelRes
import com.animo.app.ui.theme.AnimoPalette
import kotlinx.coroutines.delay

private const val CONTROLS_AUTO_HIDE_MS = 3_000L

/** "End of episode" sleep mode pauses this close to the end instead of waiting for a state change. */
private const val END_OF_EPISODE_GRACE_MS = 700L
private const val SKIP_MS = 10_000L

/** Near-white rather than #FFFFFF: the player chrome never uses a pure colour. */
private val PlayerForeground = Color(0xFFF1F5F4)
private val PlayerControlScrim = Color(0x3DFFFFFF)

@Composable
fun PlayerScreen(
    animeId: Int,
    episodeId: Long,
    initialAudio: AudioTrack,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val factory = remember(container, animeId, episodeId, initialAudio) {
        playerViewModelFactory(
            animeId = animeId,
            episodeId = episodeId,
            audio = initialAudio,
            animeRepository = container.animeRepository,
            streamRepository = container.streamRepository,
        )
    }
    val viewModel: PlayerScreenViewModel = viewModel(
        key = "player-$animeId-$episodeId-${initialAudio.name}",
        factory = factory,
    )
    PlayerScreen(viewModel = viewModel, onBack = onBack, modifier = modifier)
}

@Composable
fun PlayerScreen(
    viewModel: PlayerScreenViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImmersiveVideoEffect(enabled = state is UiState.Content)

    Box(
        Modifier
            .then(modifier)
            .fillMaxSize()
            .background(AnimoPalette.videoBlack),
    ) {
        when (val current = state) {
            UiState.Loading -> LoadingState(label = stringResource(R.string.ui_loading))
            UiState.Empty -> LoadingState()
            is UiState.Error -> Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                ErrorState(
                    message = current.message,
                    secondaryLabel = stringResource(R.string.ui_action_back),
                    onSecondary = onBack,
                )
            }
            is UiState.Content -> PlayerStage(
                viewModel = viewModel,
                model = current.data,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun PlayerStage(
    viewModel: PlayerScreenViewModel,
    model: PlayerUiModel,
    onBack: () -> Unit,
) {
    // One surface state per playback instance: re-keying on episode/audio/reload gives the host a
    // clean slate instead of asking it to hot-swap sources.
    val surfaceState = remember(model.episode.id, model.audio, model.reloadToken) {
        PlayerSurfaceState()
    }
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var episodesVisible by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }

    // Sleep timer: null = off, 0 = "end of episode", else the minutes that were picked.
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepRemainingMs by remember { mutableLongStateOf(0L) }

    // One controller per route, not per source: PlayerHost attaches/detaches it on every rebuild,
    // which is what keeps the chosen quality and subtitle preference across an episode change.
    val controller = remember(viewModel) {
        PlayerController(
            scope = scope,
            switchAudio = null,
            onNext = viewModel::next,
            onPrev = viewModel::previous,
        )
    }
    val qualities by controller.qualities.collectAsState()
    val activeQualityId by controller.activeQualityId.collectAsState()
    val audios by controller.audios.collectAsState()
    val selectedAudioId by controller.selectedAudioTrackId.collectAsState()
    val texts by controller.texts.collectAsState()
    val selectedTextId by controller.selectedTextId.collectAsState()

    var speed by remember { mutableStateOf(surfaceState.speed) }
    LaunchedEffect(speed) { surfaceState.setSpeed(speed) }

    val onProgress = remember(viewModel) { { position: Long, duration: Long ->
        viewModel.onProgress(position, duration)
    } }
    val onError = remember(viewModel) { { error: Throwable ->
        viewModel.onPlaybackError(error)
    } }
    val masterUrlProvider: suspend () -> Result<PlayableStream> = remember(
        viewModel,
        model.episode.id,
        model.audio,
        model.reloadToken,
    ) {
        suspend { viewModel.resolveStream() }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.flushProgress() }
    }

    /**
     * One effect for both sleep modes, keyed only on the choice. Re-keying on `isPlaying` would reset
     * the countdown every time the user paused, which is exactly what a sleep timer must not do.
     */
    LaunchedEffect(sleepMinutes) {
        when (val minutes = sleepMinutes) {
            null -> Unit
            // "End of episode": poll cheaply; the position only moves while playing anyway.
            0 -> {
                while (true) {
                    delay(500L)
                    val duration = surfaceState.durationMs
                    if (duration > 0 &&
                        surfaceState.positionMs >= duration - END_OF_EPISODE_GRACE_MS
                    ) {
                        surfaceState.pause()
                        sleepMinutes = null
                        break
                    }
                }
            }
            else -> {
                sleepRemainingMs = minutes * 60_000L
                while (sleepRemainingMs > 0L) {
                    delay(1_000L)
                    if (surfaceState.isPlaying) sleepRemainingMs -= 1_000L
                }
                surfaceState.pause()
                sleepMinutes = null
            }
        }
    }

    LaunchedEffect(controlsVisible, surfaceState.isPlaying, settingsVisible, episodesVisible) {
        if (controlsVisible && surfaceState.isPlaying && !settingsVisible && !episodesVisible) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    BackHandler {
        when {
            settingsVisible -> settingsVisible = false
            episodesVisible -> episodesVisible = false
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize()) {
        key(model.episode.id, model.audio, model.reloadToken) {
            PlayerHostSlot(
                masterUrlProvider = masterUrlProvider,
                onProgress = onProgress,
                onError = onError,
                state = surfaceState,
                startPositionMs = model.startPositionMs,
                modifier = Modifier.fillMaxSize(),
                controller = controller,
            )
        }

        // Invisible tap target under the controls: single tap shows/hides the overlay, double tap
        // seeks ±10 s from the half you tapped. Double-tap is the gesture people already expect on a
        // video, and it costs one pointer handler on this box.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(surfaceState) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val forward = offset.x >= size.width / 2f
                            surfaceState.skipBy(if (forward) SKIP_MS else -SKIP_MS)
                            controlsVisible = true
                        },
                    )
                },
        )

        if (!controlsVisible && surfaceState.isBuffering && model.errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp),
                color = PlayerForeground,
                strokeWidth = 2.dp,
            )
        }

        val controlsAlpha by animateFloatAsState(
            targetValue = if (controlsVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "player-controls-alpha",
        )

        if (controlsVisible || controlsAlpha > 0.01f) {
            PlayerControlsOverlay(
                model = model,
                surfaceState = surfaceState,
                scrubPositionMs = scrubPositionMs,
                onScrub = { position -> scrubPositionMs = position },
                onSeek = { position ->
                    surfaceState.seekTo(position)
                    scrubPositionMs = null
                },
                onBack = onBack,
                onOpenSettings = {
                    settingsVisible = true
                    controlsVisible = true
                },
                onOpenEpisodes = {
                    episodesVisible = true
                    controlsVisible = true
                },
                sleepActive = sleepMinutes != null,
                onToggleAudio = viewModel::toggleAudio,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = controlsAlpha },
            )
        }

        model.errorMessage?.let { message ->
            PlayerErrorCard(
                message = message,
                onRetry = viewModel::retry,
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        EpisodeDrawer(
            visible = episodesVisible,
            episodes = model.episodes,
            currentEpisodeId = model.episode.id,
            progress = emptyMap(),
            onSelect = { episode ->
                episodesVisible = false
                viewModel.selectEpisode(episode)
            },
            onDismiss = { episodesVisible = false },
        )

        if (settingsVisible) {
            PlayerSettingsSheet(
                tuning = PlayerTuning(
                    qualities = qualities.map { QualityRow(it.id, it.label, it.height, it.bitrate) },
                    activeQualityId = activeQualityId,
                    audios = audios.map { TrackRow(it.id, it.label) },
                    selectedAudioId = selectedAudioId,
                    texts = texts.map { TrackRow(it.id, it.label) },
                    selectedTextId = selectedTextId,
                    speed = speed,
                    hasQualities = qualities.size > 1,
                    hasAudioTracks = audios.size > 1,
                ),
                sleepMinutesLeft = sleepMinutes,
                onDismiss = { settingsVisible = false },
                onSelectQuality = controller::selectQuality,
                onSelectAudio = controller::selectAudioTrack,
                onSelectText = controller::selectTextTrack,
                onSelectSpeed = { value ->
                    speed = value
                    surfaceState.setSpeed(value)
                },
                onSelectSleep = { minutes ->
                    sleepMinutes = minutes
                    sleepRemainingMs = (minutes ?: 0) * 60_000L
                },
            )
        }
    }
}

@Composable
private fun PlayerControlsOverlay(
    model: PlayerUiModel,
    surfaceState: PlayerSurfaceState,
    scrubPositionMs: Long?,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEpisodes: () -> Unit,
    sleepActive: Boolean,
    onToggleAudio: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = scrubPositionMs ?: surfaceState.positionMs
    val duration = surfaceState.durationMs

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .background(AnimoPalette.playerTopScrim),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .align(Alignment.BottomCenter)
                .background(AnimoPalette.playerBottomScrim),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.ui_action_back),
                    tint = PlayerForeground,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = model.anime.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = PlayerForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.ui_player_episode_label,
                        model.episode.number,
                        model.episode.displayTitle(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerForeground.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.canSwitchAudio) {
                AudioSwitchChip(audio = model.audio, onClick = onToggleAudio)
            }
            IconButton(onClick = onOpenEpisodes) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.List,
                    contentDescription = stringResource(R.string.ui_player_episodes),
                    tint = PlayerForeground,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.ui_player_settings),
                    tint = PlayerForeground,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            IconButton(
                onClick = { surfaceState.skipBy(-SKIP_MS) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Replay10,
                    contentDescription = null,
                    tint = PlayerForeground,
                    modifier = Modifier.size(28.dp),
                )
            }

            IconButton(
                onClick = { surfaceState.togglePlayPause() },
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(PlayerControlScrim),
            ) {
                if (surfaceState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = PlayerForeground,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (surfaceState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = PlayerForeground,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            IconButton(
                onClick = { surfaceState.skipBy(SKIP_MS) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forward10,
                    contentDescription = null,
                    tint = PlayerForeground,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            PlayerSeekBar(
                positionMs = position,
                bufferedMs = surfaceState.bufferedMs,
                durationMs = duration,
                onScrub = onScrub,
                onSeek = onSeek,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatClock(position)} / ${formatClock(duration)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerForeground.copy(alpha = 0.78f),
                )
                Spacer(Modifier.weight(1f))
                if (model.hasPrevious) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.ui_player_previous),
                            tint = PlayerForeground,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (model.hasNext) {
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.ui_player_next),
                            tint = PlayerForeground,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSwitchChip(
    audio: AudioTrack,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(audio.labelRes()),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PlayerControlScrim)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = PlayerForeground,
    )
}

@Composable
private fun PlayerErrorCard(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(R.string.ui_player_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.ui_player_error_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.ui_action_back))
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
