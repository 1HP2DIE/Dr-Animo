package com.animo.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
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
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ZoomOutMap
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.model.PlayableStream
import com.animo.app.di.ServiceLocator
import com.animo.app.player.PlayerController
import com.animo.app.player.TrackOption
import com.animo.app.player.StreamTracks
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
import com.animo.app.ui.common.findActivity
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.displayTitle
import com.animo.app.ui.common.formatClock
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

/** How often the sleep countdown is stepped. It only decides how fine-grained the stop is. */
private const val SLEEP_TICK_MS = 500L

/** Long enough for the "Sleep timer ended" line to be read, short enough not to hold the screen. */
private const val SLEEP_EXIT_DELAY_MS = 900L
private const val SKIP_MS = 10_000L

/** How long the up-next card counts down before it starts the next episode on its own. */
private const val UP_NEXT_COUNTDOWN_MS = 10_000L

/** Without an outro marker, the up-next card appears this close to the end. */
private const val UP_NEXT_FALLBACK_WINDOW_MS = 25_000L

/** A chapter button disappears this long before the chapter ends — it would only flash. */
private const val CHAPTER_TAIL_MS = 1_500L

private val PlayerForeground = PlayerChrome.foreground
private val PlayerControlScrim = PlayerChrome.controlFill

@Composable
fun PlayerScreen(
    animeId: Int,
    episodeId: Long,
    initialAudio: AudioTrack,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sleep timer ran out: everything is stopped and the player should leave the screen. */
    onSleepTimerEnded: () -> Unit = onBack,
    /**
     * The audio actually playing. A title page shows SUB/DUB for the whole list, so it has to know
     * when playback fell back (an episode without a dub) or the list would keep promising a track
     * that is not on screen.
     */
    onAudioChanged: (AudioTrack) -> Unit = {},
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
    PlayerScreen(
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
        onSleepTimerEnded = onSleepTimerEnded,
        onAudioChanged = onAudioChanged,
    )
}

@Composable
fun PlayerScreen(
    viewModel: PlayerScreenViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False = drawn inline at the top of the title page; true = immersive, landscape. */
    fullscreen: Boolean = true,
    /** Present when the player can switch between inline and full screen. */
    onToggleFullscreen: (() -> Unit)? = null,
    /** Called once the sleep timer has stopped playback and let go of the screen. */
    onSleepTimerEnded: () -> Unit = onBack,
    /** Playback audio, so a host list can follow a fallback instead of guessing. */
    onAudioChanged: (AudioTrack) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImmersiveVideoEffect(enabled = state is UiState.Content, immersive = fullscreen)

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
                inline = !fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onSleepTimerEnded = onSleepTimerEnded,
                onAudioChanged = onAudioChanged,
            )
        }
    }
}

@Composable
private fun PlayerStage(
    viewModel: PlayerScreenViewModel,
    model: PlayerUiModel,
    onBack: () -> Unit,
    inline: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onSleepTimerEnded: () -> Unit = onBack,
    onAudioChanged: (AudioTrack) -> Unit = {},
) {
    // One surface state per playback instance: re-keying on episode/audio/reload gives the host a
    // clean slate instead of asking it to hot-swap sources.
    // Playback speed is a viewer preference for the session, so it is keyed outside the surface and
    // handed to every new one: a fresh state would otherwise start at 1.0x while the chip still
    // showed the old value.
    var speed by rememberSaveable { mutableStateOf(1f) }
    val surfaceState = remember(model.episode.id, model.audio, model.reloadToken) {
        PlayerSurfaceState(initialSpeed = speed)
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var settingsTab by remember { mutableStateOf<PlayerMenuTab?>(null) }
    var episodesVisible by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val hudState = rememberPlayerHudState()
    // Lives here, not in the controls overlay: that leaves composition every time the chrome hides,
    // and would download the preview index again on every tap.
    val seekPreview = rememberSeekPreview(surfaceState.stream)
    // Independent of the lock: a brightness override set while dragging must survive locking.
    ReleaseBrightnessOnExit()

    // Lock: swallows every gesture until unlocked; bumping the token re-reveals the unlock button.
    var locked by remember { mutableStateOf(false) }
    var lockRevealToken by remember { mutableIntStateOf(0) }

    // Fit and the clock mode are viewer preferences for the session, not per episode.
    var videoFit by rememberSaveable { mutableStateOf(VideoFit.FIT) }
    var showRemaining by rememberSaveable { mutableStateOf(false) }

    val flashMessage: (String, androidx.compose.ui.graphics.vector.ImageVector) -> Unit = { text, icon ->
        val message = PlayerHud.Message(text, icon)
        hudState.show(message)
        scope.launch {
            delay(1_100L)
            if (hudState.current == message) hudState.show(null)
        }
    }
    val applyFit: (VideoFit) -> Unit = { fit ->
        if (fit != videoFit) {
            videoFit = fit
            when (fit) {
                VideoFit.FIT -> flashMessage(context.getString(R.string.ui_player_ctl_fit_fit), Icons.Rounded.FitScreen)
                VideoFit.ZOOM -> flashMessage(context.getString(R.string.ui_player_ctl_fit_zoom), Icons.Rounded.ZoomOutMap)
                VideoFit.STRETCH -> flashMessage(context.getString(R.string.ui_player_ctl_fit_stretch), Icons.Rounded.AspectRatio)
            }
        }
    }

    // Sleep timer: saveable, so a rotation or a PiP round trip does not silently cancel it.
    val sleep = rememberSaveable(saver = SleepTimerState.Saver) { SleepTimerState() }
    // The page that opened this player keeps its own SUB/DUB choice; tell it what is really playing,
    // including a silent fallback to the other track.
    LaunchedEffect(model.audio) { onAudioChanged(model.audio) }

    // Bumped when the timer runs out. The work below hangs off this rather than off the choice,
    // because disarming the timer changes the choice and would cancel a "then leave the screen"
    // step that lived inside the same effect.
    var sleepFired by rememberSaveable { mutableIntStateOf(0) }

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

    // ---- stream side-car subtitles ------------------------------------------
    // The source publishes subtitle files next to the video (Server 1: up to ~16 languages). They
    // are downloaded on demand and drawn by the same overlay as a side-loaded file.
    var streamTrackId by remember { mutableStateOf<String?>(null) }
    var streamCues by remember { mutableStateOf(emptyList<SubtitleCue>()) }
    val streamCueCache = remember { mutableMapOf<String, List<SubtitleCue>>() }
    // What the viewer chose, carried to the next episode: a label to look for, or "off".
    var preferredStreamLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitlesOffByViewer by rememberSaveable { mutableStateOf(false) }
    val streamSubtitles = surfaceState.stream?.subtitles.orEmpty()

    val activeCues = when {
        streamTrackId != null -> streamCues
        fileActive -> fileCues
        else -> emptyList()
    }
    val subtitleFromFile = activeCues.isNotEmpty()
    var subtitleClockMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(subtitleFromFile) {
        while (subtitleFromFile) {
            subtitleClockMs = surfaceState.clockPositionMs()
            delay(if (surfaceState.isPlaying) 90L else 300L)
        }
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
                streamTrackId = null
                containerLines = emptyList()
                // One renderer at a time: the container track would otherwise draw a second copy.
                controller.selectTextTrack(TrackOption.OFF_ID)
            }
        }
    }

    val loadStreamTrack: (Int) -> Unit = { index ->
        val track = streamSubtitles.getOrNull(index)
        val stream = surfaceState.stream
        if (track != null && stream != null) {
            val id = TrackRow.STREAM_PREFIX + index
            scope.launch {
                subtitleError = null
                val cached = streamCueCache[track.url]
                val cues = cached ?: run {
                    subtitleBusy = track.label
                    val file = runCatching {
                        SubtitleFiles.download(
                            client = ServiceLocator.httpClient,
                            url = track.url,
                            headers = mapOf("Referer" to stream.referer, "User-Agent" to stream.userAgent),
                        )
                    }.onFailure { com.animo.app.AppLog.w("stream subtitle download failed: ${track.url}", it) }
                        .getOrNull()
                    val parsed = file?.let {
                        withContext(Dispatchers.Default) {
                            runCatching { SubtitleParser.parse(it.name, it.content) }
                                .onFailure { e -> com.animo.app.AppLog.w("stream subtitle parse failed: ${it.name}", e) }
                                .getOrDefault(emptyList())
                        }
                    }.orEmpty()
                    com.animo.app.AppLog.d("stream subtitle ${track.label}: file=${file?.name} len=${file?.content?.length} cues=${parsed.size}")
                    subtitleBusy = null
                    parsed
                }
                if (cues.isEmpty()) {
                    subtitleError = context.getString(R.string.ui_sub_error_load)
                } else {
                    streamCueCache[track.url] = cues
                    streamCues = cues
                    streamTrackId = id
                    fileActive = false
                    containerLines = emptyList()
                    controller.selectTextTrack(TrackOption.OFF_ID)
                }
            }
        }
    }

    // A new stream: put back what the viewer had (same label), or the source's sensible default.
    // The pick failed and another server answered: say so, or the switch looks like it was ignored.
    LaunchedEffect(surfaceState.stream) {
        val picked = model.server ?: return@LaunchedEffect
        val playing = surfaceState.stream?.server ?: return@LaunchedEffect
        if (picked != playing) {
            flashMessage(
                context.getString(R.string.ui_player_server_fell_back, picked.number, playing.number),
                Icons.Rounded.Info,
            )
        }
    }

    LaunchedEffect(surfaceState.stream) {
        val subs = surfaceState.stream?.subtitles.orEmpty()
        if (subs.isEmpty() || subtitlesOffByViewer || streamTrackId != null || fileActive) return@LaunchedEffect
        val pick = preferredStreamLabel?.let { label -> subs.firstOrNull { it.label == label } }
            ?: StreamTracks.autoPick(subs, dub = isDubbedAudio(model))
        pick?.let { loadStreamTrack(subs.indexOf(it)) }
    }

    /**
     * Selecting a side-loaded file is not a container track, so it turns the engine's text track
     * off; selecting the container's own track turns the file off. The picker shows one row per
     * source, so the two can never both be "selected".
     */
    val onSelectText: (String) -> Unit = { id ->
        subtitlesOffByViewer = id == TrackOption.OFF_ID
        if (id.startsWith(TrackRow.STREAM_PREFIX)) {
            val index = id.removePrefix(TrackRow.STREAM_PREFIX).toIntOrNull() ?: -1
            preferredStreamLabel = streamSubtitles.getOrNull(index)?.label
            loadStreamTrack(index)
        } else {
            streamTrackId = null
        }
        if (!id.startsWith(TrackRow.STREAM_PREFIX)) when (id) {
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
        streamTrackId = null
        streamCues = emptyList()
        streamCueCache.clear()
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
    // Kept as State, not unwrapped here: this flow re-emits every few seconds while playing
    // (progress is saved four times a minute), and reading it in the stage would recompose the
    // whole player for a drawer that is usually closed.
    val episodeProgress = remember(repository, model.anime.id) {
        repository.observeEpisodeProgress(model.anime.id)
    }.collectAsState(initial = emptyMap())

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
     * One effect for both sleep modes, keyed on the choice and a token rather than on `isPlaying`:
     * re-keying on playback state would restart the countdown every time the viewer paused, which
     * is exactly what a sleep timer must not do. The token exists so re-picking the preset that is
     * already selected restarts the clock instead of doing nothing.
     */
    LaunchedEffect(sleep.minutes, sleep.token) {
        when (sleep.minutes) {
            null -> Unit
            // "End of episode": poll cheaply; the position only moves while playing anyway.
            0 -> {
                while (true) {
                    delay(500L)
                    val duration = surfaceState.durationMs
                    if (duration > 0 &&
                        surfaceState.positionMs >= duration - END_OF_EPISODE_GRACE_MS
                    ) {
                        sleep.disarm()
                        sleepFired++
                        break
                    }
                }
            }
            else -> {
                var last = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    delay(SLEEP_TICK_MS)
                    val now = android.os.SystemClock.elapsedRealtime()
                    val elapsed = now - last
                    last = now
                    // Wall clock, playing or paused: "30 minutes" has to mean 30 minutes of the
                    // phone being on, not 30 minutes of footage.
                    if (sleep.tick(elapsed)) {
                        sleepFired++
                        break
                    }
                }
            }
        }
    }

    /**
     * The timer ran out: stop everything and let the phone sleep.
     *
     * An ordinary app cannot switch the display off — that needs a system permission — so this does
     * what is actually available: stops the decoder instead of pausing it, drops the flag that kept
     * the screen awake, and leaves the player, after which the display times out on the system's own
     * schedule with nothing of ours holding it.
     */
    LaunchedEffect(sleepFired) {
        if (sleepFired == 0) return@LaunchedEffect
        surfaceState.stop()
        // Nothing may keep decoding behind the timer, including the media session service should
        // background playback ever own the stream.
        context.stopService(android.content.Intent(context, com.animo.app.player.PlaybackService::class.java))
        context.findActivity()?.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        flashMessage(
            context.getString(R.string.ui_player_sleep_ended),
            Icons.Rounded.Bedtime,
        )
        delay(SLEEP_EXIT_DELAY_MS)
        onSleepTimerEnded()
    }

    // A finger on the seek bar holds the chrome: hiding it mid-drag would drop the scrub and its
    // preview. Letting go restarts the countdown.
    val scrubbing = scrubPositionMs != null
    LaunchedEffect(controlsVisible, surfaceState.isPlaying, settingsTab, episodesVisible, scrubbing) {
        if (controlsVisible && surfaceState.isPlaying && settingsTab == null && !episodesVisible && !scrubbing) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // ---- markers fallback -------------------------------------------------------
    // Sources without their own intro/outro (Server 2, Hindi) borrow community timestamps once the
    // duration is known — the lookup is matched against the episode length.
    val durationKnown = surfaceState.durationMs > 0L
    LaunchedEffect(surfaceState, durationKnown) {
        if (!durationKnown || surfaceState.stream == null) return@LaunchedEffect
        if (surfaceState.intro != null || surfaceState.outro != null) return@LaunchedEffect
        val malId = model.anime.malId?.toIntOrNull() ?: return@LaunchedEffect
        val markers = com.animo.app.player.SkipTimes.fetch(
            client = ServiceLocator.httpClient,
            malId = malId,
            episode = model.episode.number,
            episodeLengthMs = surfaceState.durationMs,
        ) ?: return@LaunchedEffect
        surfaceState.setMarkers(markers.intro, markers.outro)
    }

    // ---- chapters & up next ---------------------------------------------------
    // derivedStateOf: these flip a handful of times per episode, while the position ticks 4x a
    // second — reading the position directly here would recompose the whole stage on every tick.
    val inIntro by remember(surfaceState) {
        derivedStateOf {
            val intro = surfaceState.intro
            intro != null && surfaceState.positionMs in intro.first until (intro.last - CHAPTER_TAIL_MS)
        }
    }
    val inOutro by remember(surfaceState) {
        derivedStateOf {
            val outro = surfaceState.outro
            outro != null && surfaceState.positionMs in outro.first until (outro.last - CHAPTER_TAIL_MS)
        }
    }
    val nearEnd by remember(surfaceState) {
        derivedStateOf {
            val duration = surfaceState.durationMs
            val position = surfaceState.positionMs
            duration > 0L && position < duration && (
                surfaceState.outro?.let { position >= it.first }
                    ?: (duration - position <= UP_NEXT_FALLBACK_WINDOW_MS)
                )
        }
    }
    val introLabel = stringResource(R.string.ui_player_ctl_chapter_intro)
    val outroLabel = stringResource(R.string.ui_player_ctl_chapter_outro)
    val chapters = listOfNotNull(
        surfaceState.intro?.let { SeekChapter(it, introLabel) },
        surfaceState.outro?.let { SeekChapter(it, outroLabel) },
    )

    // Two roads lead to the next episode (the countdown and the player's own "ended"); whichever
    // arrives first wins, so a short outro can never advance twice and skip an episode.
    var advanced by remember(model.episode.id) { mutableStateOf(false) }
    val advanceOnce: () -> Unit = {
        if (!advanced) {
            advanced = true
            viewModel.next()
        }
    }

    var upNextDismissed by remember(model.episode.id) { mutableStateOf(false) }
    val overlaysAllowed = !inPip && !locked && settingsTab == null && !episodesVisible &&
        model.errorMessage == null
    val upNextVisible = overlaysAllowed && model.hasNext && nearEnd && !upNextDismissed
    // "End of episode" sleep means the viewer asked to stop here; the countdown must not override it.
    val countdownArmed = autoplayNext && !sleep.stopsAtEndOfEpisode
    var countdownLeftMs by remember(model.episode.id) { mutableLongStateOf(UP_NEXT_COUNTDOWN_MS) }
    LaunchedEffect(upNextVisible, countdownArmed) {
        countdownLeftMs = UP_NEXT_COUNTDOWN_MS
        if (!upNextVisible || !countdownArmed) return@LaunchedEffect
        while (countdownLeftMs > 0L) {
            delay(100L)
            // Paused means "not yet": the countdown waits with the video.
            if (surfaceState.isPlaying) countdownLeftMs -= 100L
        }
        advanceOnce()
    }

    // Inline, back belongs to the page around the player unless one of the player's own layers
    // is open.
    BackHandler(enabled = !inline || locked || settingsTab != null || episodesVisible) {
        when {
            locked -> lockRevealToken++
            settingsTab != null -> settingsTab = null
            episodesVisible -> episodesVisible = false
            // Chrome first: back with the controls up hides them, it does not throw the viewer out
            // of a full-screen episode.
            controlsVisible -> controlsVisible = false
            onToggleFullscreen != null -> onBack()
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
                    // The natural end satisfies "stop after this episode" on its own; leaving the
                    // timer armed would keep claiming it still has an episode to wait for.
                    if (sleep.stopsAtEndOfEpisode) sleep.disarm()
                    else if (autoplayNext) advanceOnce()
                },
                onCues = { lines -> containerLines = lines },
                videoFit = videoFit,
            )
        }

        // Gesture surface: tap = chrome, double tap = ±10 s (keep tapping to add more), long
        // press = accelerated seek, vertical drag = volume/brightness, pinch = zoom/fit.
        if (!locked) {
            Box(
                Modifier
                    .matchParentSize()
                    .playerGestures(
                        surfaceState = surfaceState,
                        hud = hudState,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onPinch = { zoomIn -> applyFit(if (zoomIn) VideoFit.ZOOM else VideoFit.FIT) },
                    ),
            )
        }

        if (!inPip) TapSeekIndicator(hud = hudState.current)

        PlayerGestureHud(
            hud = hudState.current,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 24.dp),
        )

        if (!inPip) {
            if (subtitleFromFile) {
                // The 90 ms clock is read inside this child, so a tick invalidates the cue lookup and
                // the overlay instead of the whole player stage.
                StreamSubtitleLayer(
                    cues = activeCues,
                    clockMs = { subtitleClockMs },
                    style = subtitleStyle,
                    liftForControls = controlsVisible,
                )
            } else {
                SubtitleOverlay(
                    lines = containerLines,
                    style = subtitleStyle,
                    liftForControls = controlsVisible,
                    modifier = Modifier.matchParentSize(),
                )
            }
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

        if (!inPip && !locked && (controlsVisible || controlsAlpha > 0.01f)) {
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
                chapters = chapters,
                showRemaining = showRemaining,
                onToggleRemaining = { showRemaining = !showRemaining },
                videoFit = videoFit,
                onCycleFit = { applyFit(videoFit.next()) },
                onLock = {
                    locked = true
                    // The gesture surface leaves composition while locked, so its own cleanup never
                    // runs: without this a seek/volume pill would sit on the screen forever.
                    hudState.show(null)
                    controlsVisible = false
                    lockRevealToken++
                    flashMessage(context.getString(R.string.ui_player_ctl_locked), Icons.Rounded.Lock)
                },
                speed = speed,
                preview = seekPreview,
                onOpenSettings = { tab ->
                    settingsTab = tab
                    controlsVisible = true
                },
                onOpenEpisodes = {
                    episodesVisible = true
                    controlsVisible = true
                },
                sleepActive = sleep.isArmed,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onEnterPip = enterPip,
                inline = inline,
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = controlsAlpha },
            )
        }

        // Floating actions: bottom-end, lifted above the bar while the chrome is up.
        val floatLift by animateDpAsState(
            targetValue = when {
                inline -> if (controlsVisible) 56.dp else 10.dp
                controlsVisible && !locked -> 132.dp
                else -> 28.dp
            },
            animationSpec = tween(durationMillis = 220),
            label = "player-float-lift",
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(if (inline) Modifier else Modifier.navigationBarsPadding())
                .padding(end = if (inline) 12.dp else 24.dp, bottom = floatLift),
            horizontalAlignment = Alignment.End,
        ) {
            SkipChapterButton(
                visible = overlaysAllowed && inIntro,
                label = stringResource(R.string.ui_player_ctl_skip_intro),
                onClick = { surfaceState.intro?.let { surfaceState.seekTo(it.last) } },
            )
            // With a next episode, the up-next card is the outro's action; without one, skip it.
            SkipChapterButton(
                visible = overlaysAllowed && inOutro && !model.hasNext,
                label = stringResource(R.string.ui_player_ctl_skip_outro),
                onClick = { surfaceState.outro?.let { surfaceState.seekTo(it.last) } },
            )
            UpNextCard(
                visible = upNextVisible,
                next = model.nextEpisode,
                secondsLeft = if (countdownArmed) ((countdownLeftMs + 999L) / 1_000L).toInt() else null,
                countdownFraction = if (countdownArmed) {
                    1f - countdownLeftMs.toFloat() / UP_NEXT_COUNTDOWN_MS
                } else {
                    null
                },
                onPlay = advanceOnce,
                onDismiss = { upNextDismissed = true },
            )
        }

        if (locked && !inPip) {
            LockedOverlay(
                onUnlock = {
                    locked = false
                    controlsVisible = true
                    flashMessage(context.getString(R.string.ui_player_ctl_unlocked), Icons.Rounded.LockOpen)
                },
                revealToken = lockRevealToken,
                modifier = Modifier.matchParentSize(),
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

        EpisodeDrawerSlot(
            progress = episodeProgress,
            visible = episodesVisible && !inPip,
            episodes = model.episodes,
            currentEpisodeId = model.episode.id,
            currentAudio = model.audio,
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
                    // Source side-cars first (that is where the languages are), then container tracks.
                    texts = streamSubtitles.mapIndexed { index, sub ->
                        TrackRow(TrackRow.STREAM_PREFIX + index, sub.label)
                    } + texts.map { TrackRow(it.id, it.label) },
                    selectedTextId = streamTrackId
                        ?: if (fileActive) TrackRow.FILE_ID else selectedTextId,
                    speed = speed,
                    hasQualities = qualities.size > 1,
                    autoIsHighest = surfaceState.stream?.container == StreamContainer.PROGRESSIVE,
                    hasAudioTracks = audios.size > 1,
                    servers = model.servers.map { option ->
                        ServerRow(
                            number = option.server.number,
                            label = option.server.label,
                            reachable = option.reachable,
                            languages = option.languages.map { LanguageRow(it.id, it.label) },
                        )
                    },
                    activeServer = surfaceState.stream?.server?.number ?: model.server?.number,
                    activeLanguageId = model.displayedLanguageId,
                ),
                sleepTimer = sleep,
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
                onSelectLanguage = { number, languageId ->
                    com.animo.app.domain.model.StreamServer.fromNumber(number)?.let { server ->
                        viewModel.selectLanguage(server, languageId)
                    }
                },
                onSelectSpeed = { value ->
                    speed = value
                    surfaceState.setSpeed(value)
                },
                onSelectSleep = sleep::select,
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
    chapters: List<SeekChapter>,
    showRemaining: Boolean,
    onToggleRemaining: () -> Unit,
    videoFit: VideoFit,
    onCycleFit: () -> Unit,
    onLock: () -> Unit,
    speed: Float,
    preview: SeekPreviewState,
    onOpenSettings: (PlayerMenuTab) -> Unit,
    onOpenEpisodes: () -> Unit,
    sleepActive: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val position = scrubPositionMs ?: surfaceState.positionMs
    val duration = surfaceState.durationMs

    if (inline) {
        InlineControlsOverlay(
            surfaceState = surfaceState,
            position = position,
            duration = duration,
            onScrub = onScrub,
            onSeek = onSeek,
            chapters = chapters,
            preview = preview,
            onToggleFullscreen = onToggleFullscreen,
            modifier = modifier,
        )
        return
    }

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

        // Top: identity on the left, frame-level tools on the right (fit, lock, episodes). Nothing
        // here changes playback, so a stray thumb on the top edge can never seek or pause.
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
                    color = PlayerChrome.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayerStatusReadout(Modifier.padding(horizontal = 12.dp))
            PlayerGlyph(
                icon = when (videoFit) {
                    VideoFit.FIT -> Icons.Rounded.FitScreen
                    VideoFit.ZOOM -> Icons.Rounded.ZoomOutMap
                    VideoFit.STRETCH -> Icons.Rounded.AspectRatio
                },
                description = stringResource(R.string.ui_player_ctl_fit),
                onClick = onCycleFit,
            )
            PlayerGlyph(
                icon = Icons.Rounded.Lock,
                description = stringResource(R.string.ui_player_ctl_lock),
                onClick = onLock,
            )
            PlayerGlyph(
                icon = Icons.AutoMirrored.Rounded.List,
                description = stringResource(R.string.ui_player_episodes),
                onClick = onOpenEpisodes,
                size = 24.dp,
            )
        }

        // Exactly centred: ±10 s either side of play/pause is the whole transport. Episode
        // navigation lives under the seek bar with the rest of the metadata. The row sits in the
        // true middle of the screen, where the thumb rests, not on top of the seek bar.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
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
                    .size(64.dp)
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
                        imageVector = if (surfaceState.isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (surfaceState.isPlaying) R.string.ui_player_pause else R.string.ui_player_play,
                        ),
                        tint = PlayerForeground,
                        modifier = Modifier.size(34.dp),
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerSeekBar(
                positionMs = position,
                bufferedMs = surfaceState.bufferedMs,
                durationMs = duration,
                onScrub = onScrub,
                onSeek = onSeek,
                chapters = chapters,
                preview = preview,
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
                // Tap the clock to flip between total and remaining — the answer to "can I finish
                // this before my stop?" is one tap away.
                val trailing = if (showRemaining && duration > 0L) {
                    "-" + formatClock((duration - position).coerceAtLeast(0L))
                } else {
                    formatClock(duration)
                }
                Text(
                    text = "${formatClock(position)} / $trailing",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            onClickLabel = stringResource(R.string.ui_player_ctl_time_remaining),
                            onClick = onToggleRemaining,
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = PlayerChrome.foregroundMuted,
                )
                Spacer(Modifier.weight(1f))
                // Only when it is not the default: a "1x" badge is noise, a "1.5x" one is a reminder.
                if (speed != 1f) {
                    PlayerChip(
                        label = formatSpeed(speed),
                        onClick = { onOpenSettings(PlayerMenuTab.SPEED) },
                        accent = true,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (model.servers.isNotEmpty()) {
                    PlayerChip(
                        label = serverChipLabel(model, surfaceState.stream?.server),
                        onClick = { onOpenSettings(PlayerMenuTab.AUDIO) },
                    )
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
                    tint = if (sleepActive) PlayerChrome.accent else PlayerForeground,
                )
                PlayerGlyph(
                    icon = Icons.Rounded.PictureInPictureAlt,
                    description = stringResource(R.string.ui_player_pip),
                    onClick = onEnterPip,
                )
                if (onToggleFullscreen != null) {
                    PlayerGlyph(
                        icon = Icons.Rounded.FullscreenExit,
                        description = stringResource(R.string.ui_player_exit_fullscreen),
                        onClick = onToggleFullscreen,
                    )
                }
            }
        }
    }
}

/**
 * Controls for the inline player at the top of the title page: the same centred transport and seek
 * bar, plus the full-screen switch. Everything else (servers, subtitles, quality, speed, lock,
 * episodes) lives in full screen, where there is room for it — a 16:9 strip on a phone is not.
 */
@Composable
private fun InlineControlsOverlay(
    surfaceState: PlayerSurfaceState,
    position: Long,
    duration: Long,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    chapters: List<SeekChapter>,
    preview: SeekPreviewState,
    onToggleFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(PlayerChrome.inlineScrim)) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PlayerGlyph(
                icon = Icons.Rounded.Replay10,
                description = null,
                onClick = { surfaceState.skipBy(-SKIP_MS) },
                size = 26.dp,
            )
            IconButton(
                onClick = { surfaceState.togglePlayPause() },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(PlayerControlScrim),
            ) {
                if (surfaceState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = PlayerForeground,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (surfaceState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (surfaceState.isPlaying) R.string.ui_player_pause else R.string.ui_player_play,
                        ),
                        tint = PlayerForeground,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            PlayerGlyph(
                icon = Icons.Rounded.Forward10,
                description = null,
                onClick = { surfaceState.skipBy(SKIP_MS) },
                size = 26.dp,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatClock(position)} / ${formatClock(duration)}",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = PlayerChrome.foregroundMuted,
                )
                Spacer(Modifier.weight(1f))
                if (onToggleFullscreen != null) {
                    PlayerGlyph(
                        icon = Icons.Rounded.Fullscreen,
                        description = stringResource(R.string.ui_player_fullscreen),
                        onClick = onToggleFullscreen,
                        size = 26.dp,
                    )
                }
            }
            PlayerSeekBar(
                positionMs = position,
                bufferedMs = surfaceState.bufferedMs,
                durationMs = duration,
                onScrub = onScrub,
                onSeek = onSeek,
                chapters = chapters,
                preview = preview,
            )
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

/**
 * Small labelled value in the bottom bar ("S2 · Hindi", "1.5x"). Tapping opens the pane that
 * changes it. [accent] marks a value that is off its default, so it reads as a state, not a label.
 */
@Composable
private fun PlayerChip(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PlayerControlScrim)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (accent) PlayerChrome.accentSoft else PlayerForeground,
    )
}

/**
 * Whether the viewer is hearing a dub. The language id is the truth when one was picked (a server-2
 * "en" or "hi" is a dub even if the catalogue row has no dub link, which collapses [PlayerUiModel.audio]
 * to SUB); otherwise the audio track.
 */
private fun isDubbedAudio(model: PlayerUiModel): Boolean =
    model.languageId?.let { it != "sub" && it != "ja" } ?: (model.audio == AudioTrack.DUB)

/** `Server 2 · Hindi`, falling back to the audio label when the probe has not answered yet. */
private fun serverChipLabel(model: PlayerUiModel, playing: StreamServer?): String {
    val number = playing?.number ?: model.server?.number ?: 1
    val language = model.servers
        .firstOrNull { it.server.number == number }
        ?.languages
        ?.firstOrNull { it.id == model.displayedLanguageId }
        ?.label
        ?: if (model.audio == AudioTrack.DUB) "DUB" else "SUB"
    return "S$number · $language"
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

/**
 * Side-car subtitles, drawn from a clock the overlay owns.
 *
 * The player stage pushes position four times a second, which is visibly late for a cue that starts
 * on a cut, so file subtitles run on a 90 ms clock of their own. Reading that clock here — and
 * deriving the lines from it — keeps ~11 invalidations a second inside this child instead of
 * recomposing every branch of the player stage with it.
 */
@Composable
private fun StreamSubtitleLayer(
    cues: List<com.animo.app.subtitle.SubtitleCue>,
    clockMs: () -> Long,
    style: com.animo.app.subtitle.SubtitleStyle,
    liftForControls: Boolean,
) {
    val lines by remember(cues) {
        derivedStateOf { cues.activeAt(clockMs()).flatMap { cue -> cue.text.split('\n') } }
    }
    SubtitleOverlay(
        lines = lines,
        style = style,
        liftForControls = liftForControls,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The episode drawer, with its progress read inside this child.
 *
 * Progress is written to the database four times a minute during playback; unwrapping the flow in
 * the player stage re-ran every branch of it for a drawer that is closed most of the time.
 */
@Composable
private fun EpisodeDrawerSlot(
    progress: androidx.compose.runtime.State<Map<Long, com.animo.app.domain.repo.EpisodeProgress>>,
    visible: Boolean,
    episodes: List<com.animo.app.domain.model.Episode>,
    currentEpisodeId: Long,
    currentAudio: AudioTrack,
    onSelect: (com.animo.app.domain.model.Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    EpisodeDrawer(
        visible = visible,
        episodes = episodes,
        currentEpisodeId = currentEpisodeId,
        currentAudio = currentAudio,
        progress = progress.value.mapNotNull { (id, entry) -> entry.fraction?.let { id to it } }.toMap(),
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
