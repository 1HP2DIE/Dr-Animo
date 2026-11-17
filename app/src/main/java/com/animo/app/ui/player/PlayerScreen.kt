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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.R
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.di.ServiceLocator
import com.animo.app.player.PlayerController
import com.animo.app.player.TrackOption
import com.animo.app.subtitle.SubtitleCue
import com.animo.app.subtitle.SubtitleFiles
import com.animo.app.subtitle.SubtitleParser
import com.animo.app.subtitle.SubtitlePreferences
import com.animo.app.subtitle.SubtitlePreset
import com.animo.app.subtitle.SubtitleStyles
import com.animo.app.subtitle.activeAt
import com.animo.app.subtitle.online.OnlineSubtitleRepository
import com.animo.app.subtitle.online.SubtitleQuery
import com.animo.app.subtitle.online.SubtitleResult
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.LoadingState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.displayTitle
import com.animo.app.ui.common.formatClock
import com.animo.app.ui.common.labelRes
import com.animo.app.ui.settings.SettingsPreferences
import com.animo.app.ui.theme.AnimoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var settingsTab by remember { mutableStateOf<PlayerMenuTab?>(null) }
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

    // ---- subtitles ---------------------------------------------------------
    // The look is persisted, so it is loaded once and then written back debounced: a slider drag
    // must not produce one DataStore write per pixel.
    val context = androidx.compose.ui.platform.LocalContext.current
    val subtitlePrefs = remember(context) { SubtitlePreferences(context) }
    var subtitleStyle by remember { mutableStateOf(SubtitleStyles.Default) }
    var subtitleStyleReady by remember { mutableStateOf(false) }
    var customPresets by remember { mutableStateOf(emptyList<SubtitlePreset>()) }
    LaunchedEffect(subtitlePrefs) {
        subtitleStyle = subtitlePrefs.style.first()
        subtitleStyleReady = true
        subtitlePrefs.presets.collect { customPresets = it }
    }
    LaunchedEffect(subtitleStyle, subtitleStyleReady) {
        if (!subtitleStyleReady) return@LaunchedEffect
        delay(350L)
        subtitlePrefs.setStyle(subtitleStyle)
    }

    // A side-loaded file is per playback, not per install: it belongs to this episode's release,
    // so it is deliberately not persisted.
    var fileCues by remember { mutableStateOf(emptyList<SubtitleCue>()) }
    var fileLabel by remember { mutableStateOf<String?>(null) }
    var fileActive by remember { mutableStateOf(false) }
    var subtitleBusy by remember { mutableStateOf<String?>(null) }
    var subtitleError by remember { mutableStateOf<String?>(null) }

    // Container cues are already "on screen now"; the player pushes them from Media3.
    var containerLines by remember { mutableStateOf(emptyList<String>()) }

    val subtitleFromFile = fileActive && fileCues.isNotEmpty()
    var subtitleClockMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(subtitleFromFile) {
        while (subtitleFromFile) {
            subtitleClockMs = surfaceState.clockPositionMs()
            delay(if (surfaceState.isPlaying) 90L else 300L)
        }
    }
    val subtitleLines = if (subtitleFromFile) {
        fileCues.activeAt(subtitleClockMs).flatMap { cue -> cue.text.split('\n') }
    } else {
        containerLines
    }

    val installSubtitle: (String, String) -> Unit = { name, content ->
        scope.launch {
            subtitleBusy = name
            subtitleError = null
            val parsed = withContext(Dispatchers.Default) {
                runCatching { SubtitleParser.parse(name, content) }.getOrDefault(emptyList())
            }
            subtitleBusy = null
            if (parsed.isEmpty()) {
                subtitleError = context.getString(R.string.ui_sub_error_empty)
            } else {
                fileCues = parsed
                fileLabel = name
                fileActive = true
                containerLines = emptyList()
                // One renderer at a time: the container track would otherwise draw a second copy.
                controller.selectTextTrack(TrackOption.OFF_ID)
            }
        }
    }

    /**
     * Selecting a side-loaded file is not a container track, so it turns the engine's text track
     * off; selecting the container's own track turns the file off. The picker shows one row per
     * source, so the two can never both be "selected".
     */
    val onSelectText: (String) -> Unit = { id ->
        when (id) {
            TrackRow.FILE_ID -> {
                fileActive = true
                // The engine must stop drawing its own track, and any cue it already pushed must go
                // too — otherwise the file and the container line overlap for a fraction of a second.
                containerLines = emptyList()
                controller.selectTextTrack(TrackOption.OFF_ID)
            }

            TrackOption.OFF_ID -> {
                fileActive = false
                containerLines = emptyList()
                controller.selectTextTrack(TrackOption.OFF_ID)
            }

            else -> {
                fileActive = false
                controller.selectTextTrack(id)
            }
        }
    }

    // ---- online search ------------------------------------------------------
    // Free sites are searched by title + episode. The picker stays open on the results, so the
    // viewer sees what the match actually is before anything replaces the screen text.
    val onlineRepository = remember { OnlineSubtitleRepository(ServiceLocator.httpClient) }
    var onlinePhase by remember { mutableStateOf<OnlineSubtitlePhase>(OnlineSubtitlePhase.Idle) }
    var onlineDownloadingId by remember { mutableStateOf<String?>(null) }
    var onlineNote by remember { mutableStateOf<String?>(null) }

    val subtitleQuery = remember(model.anime.id, model.episode.id) {
        SubtitleQuery(
            titles = listOf(model.anime.title, model.anime.alternative)
                .filter { it.isNotBlank() }
                .distinct(),
            episode = model.episode.number,
            year = model.anime.year,
            malId = model.anime.malId,
            aniListId = model.anime.aniId,
            isMovie = model.anime.type.equals("movie", ignoreCase = true),
        )
    }
    val onlineQueryLabel = "${model.anime.title} · E${model.episode.number}"

    // Everything below is per-episode: a file or a search result for the last episode must not
    // linger onto the next one.
    LaunchedEffect(model.episode.id) {
        onlinePhase = OnlineSubtitlePhase.Idle
        onlineDownloadingId = null
        onlineNote = null
        fileCues = emptyList()
        fileLabel = null
        fileActive = false
        containerLines = emptyList()
    }

    val startOnlineSearch: () -> Unit = {
        if (onlinePhase !is OnlineSubtitlePhase.Searching) {
            scope.launch {
                onlinePhase = OnlineSubtitlePhase.Searching
                onlineNote = null
                val report = runCatching { onlineRepository.search(subtitleQuery) }.getOrNull()
                onlinePhase = if (report == null) {
                    OnlineSubtitlePhase.Failed(context.getString(R.string.ui_sub_online_failed))
                } else {
                    OnlineSubtitlePhase.Ready(report.results, report.failedSources)
                }
            }
        }
    }

    val downloadOnline: (SubtitleResult) -> Unit = { result ->
        if (onlineDownloadingId == null) {
            scope.launch {
                onlineDownloadingId = result.id
                onlineNote = null
                val file = runCatching { onlineRepository.download(result) }.getOrNull()
                onlineDownloadingId = null
                if (file == null) {
                    onlineNote = context.getString(R.string.ui_sub_online_download_failed)
                } else {
                    installSubtitle(file.name, file.content)
                    onlineNote = context.getString(R.string.ui_sub_online_downloaded, file.name)
                }
            }
        }
    }

    val subtitleActions = SubtitlePanelActions(
        onSelectTrack = onSelectText,
        onStyleChange = { subtitleStyle = it },
        onSavePreset = { name ->
            val preset = SubtitlePreset(
                id = "custom-${System.currentTimeMillis()}",
                name = name,
                style = subtitleStyle,
            )
            scope.launch { subtitlePrefs.setPresets(listOf(preset) + customPresets) }
        },
        onDeletePreset = { id ->
            scope.launch { subtitlePrefs.setPresets(customPresets.filterNot { it.id == id }) }
        },
        onLoadFile = { uri ->
            scope.launch {
                subtitleBusy = context.getString(R.string.ui_sub_loading_generic)
                subtitleError = null
                val file = runCatching { SubtitleFiles.read(context, uri) }.getOrNull()
                subtitleBusy = null
                if (file == null) {
                    subtitleError = context.getString(R.string.ui_sub_error_load)
                } else {
                    installSubtitle(file.name, file.content)
                }
            }
        },
        onLoadUrl = { url ->
            scope.launch {
                subtitleBusy = context.getString(R.string.ui_sub_loading_link)
                subtitleError = null
                val file = runCatching {
                    SubtitleFiles.download(ServiceLocator.httpClient, url)
                }.getOrNull()
                subtitleBusy = null
                if (file == null) {
                    subtitleError = context.getString(R.string.ui_sub_error_load)
                } else {
                    installSubtitle(file.name, file.content)
                }
            }
        },
        onRemoveFile = {
            fileCues = emptyList()
            fileLabel = null
            fileActive = false
            controller.selectTextTrack(TrackOption.OFF_ID)
        },
        onSearchOnline = startOnlineSearch,
        onDownloadOnline = downloadOnline,
    )
    val inPip = rememberInPictureInPicture()
    val enterPip = rememberPipAction()

    val autoplayNext by remember(context) {
        SettingsPreferences(context).autoplayNext
    }.collectAsState(initial = true)

    // The same rows the detail grid reads, so the drawer never disagrees with the season list.
    val repository = LocalAppContainer.current.animeRepository
    val episodeProgress by remember(repository, model.anime.id) {
        repository.observeEpisodeProgress(model.anime.id)
    }.collectAsState(initial = emptyMap())

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

    LaunchedEffect(controlsVisible, surfaceState.isPlaying, settingsTab, episodesVisible) {
        if (controlsVisible && surfaceState.isPlaying && settingsTab == null && !episodesVisible) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    BackHandler {
        when {
            settingsTab != null -> settingsTab = null
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
                onEnded = {
                    if (autoplayNext) viewModel.next()
                },
                onCues = { lines -> containerLines = lines },
            )
        }

        // Gesture surface: tap = chrome, double tap = ±10 s, long press = accelerated seek,
        // vertical drag = volume (right half) or brightness (left half).
        val hudState = rememberPlayerHudState()
        Box(
            Modifier
                .matchParentSize()
                .playerGestures(
                    surfaceState = surfaceState,
                    hud = hudState,
                    onToggleControls = { controlsVisible = !controlsVisible },
                ),
        )

        PlayerGestureHud(
            hud = hudState.current,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 24.dp),
        )

        if (!inPip) {
            SubtitleOverlay(
                lines = subtitleLines,
                style = subtitleStyle,
                liftForControls = controlsVisible,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (!inPip && !controlsVisible && surfaceState.isBuffering && model.errorMessage == null) {
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

        if (!inPip && (controlsVisible || controlsAlpha > 0.01f)) {
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
                onOpenSettings = { tab ->
                    settingsTab = tab
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
                onEnterPip = enterPip,
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
            visible = episodesVisible && !inPip,
            episodes = model.episodes,
            currentEpisodeId = model.episode.id,
            progress = episodeProgress.mapNotNull { (id, entry) -> entry.fraction?.let { id to it } }.toMap(),
            onSelect = { episode ->
                episodesVisible = false
                viewModel.selectEpisode(episode)
            },
            onDismiss = { episodesVisible = false },
        )

        if (settingsTab != null && !inPip) {
            PlayerSettingsSheet(
                tuning = PlayerTuning(
                    qualities = qualities.map { QualityRow(it.id, it.label, it.height, it.bitrate) },
                    activeQualityId = activeQualityId,
                    audios = audios.map { TrackRow(it.id, it.label) },
                    selectedAudioId = selectedAudioId,
                    texts = texts.map { TrackRow(it.id, it.label) },
                    selectedTextId = if (fileActive) TrackRow.FILE_ID else selectedTextId,
                    speed = speed,
                    hasQualities = qualities.size > 1,
                    hasAudioTracks = audios.size > 1,
                ),
                sleepMinutesLeft = sleepMinutes,
                initialTab = settingsTab ?: PlayerMenuTab.QUALITY,
                subtitle = SubtitlePanelState(
                    style = subtitleStyle,
                    presets = SubtitleStyles.builtInPresets + customPresets,
                    fileLabel = fileLabel,
                    busyLabel = subtitleBusy,
                    error = subtitleError,
                    online = onlinePhase,
                    onlineQuery = onlineQueryLabel,
                    downloadingId = onlineDownloadingId,
                    onlineNote = onlineNote,
                ),
                subtitleActions = subtitleActions,
                onDismiss = { settingsTab = null },
                onSelectQuality = controller::selectQuality,
                onSelectAudio = controller::selectAudioTrack,
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
    onOpenSettings: (PlayerMenuTab) -> Unit,
    onOpenEpisodes: () -> Unit,
    sleepActive: Boolean,
    onToggleAudio: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = scrubPositionMs ?: surfaceState.positionMs
    val duration = surfaceState.durationMs

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(AnimoPalette.playerTopScrim),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(272.dp)
                .align(Alignment.BottomCenter)
                .background(AnimoPalette.playerBottomScrim),
        )

        // Top: identity only. Every control lives at the bottom, where a thumb actually reaches in
        // landscape — the old centred cluster covered the video and was unreachable one-handed.
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
            IconButton(onClick = onOpenEpisodes) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.List,
                    contentDescription = stringResource(R.string.ui_player_episodes),
                    tint = PlayerForeground,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Exactly centred: ±10 s either side of play/pause is the whole transport. Episode
            // navigation lives under the seek bar with the rest of the metadata, so the middle of
            // the screen stays symmetrical.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                PlayerGlyph(
                    icon = Icons.Rounded.Replay10,
                    description = null,
                    onClick = { surfaceState.skipBy(-SKIP_MS) },
                    size = 30.dp,
                )
                IconButton(
                    onClick = { surfaceState.togglePlayPause() },
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(PlayerControlScrim),
                ) {
                    if (surfaceState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PlayerForeground,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (surfaceState.isPlaying) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = stringResource(
                                if (surfaceState.isPlaying) R.string.ui_player_pause else R.string.ui_player_play,
                            ),
                            tint = PlayerForeground,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                PlayerGlyph(
                    icon = Icons.Rounded.Forward10,
                    description = null,
                    onClick = { surfaceState.skipBy(SKIP_MS) },
                    size = 30.dp,
                )
            }

            Spacer(Modifier.height(10.dp))

            PlayerSeekBar(
                positionMs = position,
                bufferedMs = surfaceState.bufferedMs,
                durationMs = duration,
                onScrub = onScrub,
                onSeek = onSeek,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (model.hasPrevious) {
                    PlayerGlyph(
                        icon = Icons.Rounded.SkipPrevious,
                        description = stringResource(R.string.ui_player_previous),
                        onClick = onPrevious,
                    )
                }
                if (model.hasNext) {
                    PlayerGlyph(
                        icon = Icons.Rounded.SkipNext,
                        description = stringResource(R.string.ui_player_next),
                        onClick = onNext,
                    )
                }
                Text(
                    text = "${formatClock(position)} / ${formatClock(duration)}",
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerForeground.copy(alpha = 0.78f),
                )
                Spacer(Modifier.weight(1f))
                if (model.canSwitchAudio) {
                    AudioSwitchChip(audio = model.audio, onClick = onToggleAudio)
                    Spacer(Modifier.width(6.dp))
                }
                // Subtitles open on the track pane; quality/speed/sleep open their own pane. One
                // sheet, four entry points — no separate dialogs to keep in sync.
                PlayerGlyph(
                    icon = Icons.Rounded.Subtitles,
                    description = stringResource(R.string.ui_player_cc),
                    onClick = { onOpenSettings(PlayerMenuTab.SUBTITLE) },
                )
                PlayerGlyph(
                    icon = Icons.Rounded.HighQuality,
                    description = stringResource(R.string.ui_player_quality),
                    onClick = { onOpenSettings(PlayerMenuTab.QUALITY) },
                )
                PlayerGlyph(
                    icon = Icons.Rounded.Speed,
                    description = stringResource(R.string.ui_player_speed),
                    onClick = { onOpenSettings(PlayerMenuTab.SPEED) },
                )
                PlayerGlyph(
                    icon = Icons.Rounded.Bedtime,
                    description = stringResource(R.string.ui_player_sleep),
                    onClick = { onOpenSettings(PlayerMenuTab.SLEEP) },
                    tint = if (sleepActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        PlayerForeground
                    },
                )
                PlayerGlyph(
                    icon = Icons.Rounded.PictureInPictureAlt,
                    description = stringResource(R.string.ui_player_pip),
                    onClick = onEnterPip,
                )
            }
        }
    }
}

/** Compact icon button used by the bottom bar; 44dp keeps a real touch target in landscape. */
@Composable
private fun PlayerGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String?,
    onClick: () -> Unit,
    size: Dp = 22.dp,
    tint: Color = PlayerForeground,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(size),
        )
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
