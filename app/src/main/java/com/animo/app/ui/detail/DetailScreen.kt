package com.animo.app.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.animo.app.di.ServiceLocator
import com.animo.app.download.DownloadEntry
import com.animo.app.download.DownloadStatus
import com.animo.app.ui.settings.DownloadQuality
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.ui.player.PlayerScreen
import com.animo.app.ui.player.PlayerScreenViewModel
import com.animo.app.ui.player.playerViewModelFactory
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.repo.EpisodeProgress
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.BadgeTone
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.EpisodeRow
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.LoadingState
import com.animo.app.ui.common.PillBadge
import com.animo.app.ui.common.PreviewData
import com.animo.app.ui.common.SectionHeader
import com.animo.app.ui.common.displayTitle
import com.animo.app.ui.common.formatClock
import com.animo.app.ui.common.formatScore
import com.animo.app.ui.common.initials
import com.animo.app.ui.common.isPlayable
import com.animo.app.ui.common.pickAudio
import com.animo.app.ui.settings.SettingsPreferences
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.AnimoTheme
import com.animo.app.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val FullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

private val HeroShape = RoundedCornerShape(20.dp)

/**
 * One grid cell's worth of state, resolved before the grid is built.
 *
 * The map lookups happen once per (episode list, progress) pair instead of once per cell per
 * recomposition, so scrolling a 1,000-episode season stays a pure draw of already-computed values.
 */
private data class EpisodeCell(
    val episode: Episode,
    val progress: EpisodeProgress?,
    val isCurrent: Boolean,
)

@Composable
fun DetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDownloads: () -> Unit = {},
    /** A genre chip is a search: Browse opens with that genre already applied. */
    onOpenGenre: (String) -> Unit = {},
    /** Start this episode as soon as the page opens (resume from Home/Library). */
    autoplayEpisodeId: Long? = null,
    autoplayAudio: AudioTrack? = null,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current.applicationContext
    // The preference only seeds the audio chips; it is read here (not in the ViewModel) so the
    // ViewModel keeps its Context-free constructor.
    val preferences = remember(context) { SettingsPreferences(context) }
    val factory = remember(container, animeId, preferences) {
        detailViewModelFactory(
            animeId = animeId,
            repository = container.animeRepository,
            defaultAudio = preferences.defaultAudio,
        )
    }
    val viewModel: DetailViewModel = viewModel(factory = factory)
    val autoFullscreen by preferences.autoFullscreen.collectAsStateWithLifecycle(initialValue = false)

    // What the page is playing: (episode id, audio name). Saveable, so a rotation or process death
    // brings the video back instead of dropping the viewer onto the bare page.
    var playing by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var autoplayConsumed by rememberSaveable { mutableStateOf(false) }

    val play: (Episode, AudioTrack) -> Unit = { episode, audio ->
        playing = episode.id to audio.name
        fullscreen = autoFullscreen
    }
    LaunchedEffect(autoplayEpisodeId, autoFullscreen) {
        if (autoplayConsumed || autoplayEpisodeId == null) return@LaunchedEffect
        autoplayConsumed = true
        playing = autoplayEpisodeId to (autoplayAudio ?: AudioTrack.SUB).name
        fullscreen = autoFullscreen
    }
    val closePlayer: () -> Unit = {
        playing = null
        fullscreen = false
    }

    Column(
        Modifier
            .then(modifier)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // First child in both modes, so switching to full screen only changes its size: the same
        // ExoPlayer keeps playing instead of being rebuilt and re-buffered.
        playing?.let { (episodeId, audioName) ->
            val audio = AudioTrack.entries.firstOrNull { it.name == audioName } ?: AudioTrack.SUB
            val playerFactory = remember(container, animeId, episodeId, audio) {
                playerViewModelFactory(
                    animeId = animeId,
                    episodeId = episodeId,
                    audio = audio,
                    animeRepository = container.animeRepository,
                    streamRepository = container.streamRepository,
                )
            }
            val playerViewModel: PlayerScreenViewModel = viewModel(
                key = "player-$animeId-$episodeId-$audioName",
                factory = playerFactory,
            )
            PlayerScreen(
                viewModel = playerViewModel,
                // Full screen: back leaves full screen, or closes the player when the viewer chose
                // to always watch full screen. Inline: the close button.
                onBack = {
                    if (fullscreen && !autoFullscreen) fullscreen = false else closePlayer()
                },
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                modifier = if (fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .background(AnimoPalette.videoBlack)
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                },
            )
        }
        // Stays composed under a full-screen video (it just gets no height), so leaving full screen
        // lands on the same scroll position.
        DetailScreen(
            viewModel = viewModel,
            onBack = onBack,
            onPlayEpisode = play,
            playerActive = playing != null,
            playingEpisodeId = playing?.first,
            onOpenDownloads = onOpenDownloads,
            onOpenGenre = onOpenGenre,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (Episode, AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
    /** The video sits above the page: no hero, no status-bar inset, no floating back row. */
    playerActive: Boolean = false,
    playingEpisodeId: Long? = null,
    onOpenDownloads: () -> Unit = {},
    onOpenGenre: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        Modifier
            .then(modifier)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val current = state) {
            UiState.Loading -> LoadingState()
            UiState.Empty -> EmptyState(
                title = stringResource(R.string.ui_detail_no_episodes_title),
                body = stringResource(R.string.ui_detail_not_found),
                icon = Icons.Rounded.SearchOff,
            )
            is UiState.Error -> ErrorState(
                message = current.message,
                onRetry = viewModel::refreshEpisodes,
                secondaryLabel = stringResource(R.string.ui_action_back),
                onSecondary = onBack,
            )
            is UiState.Content -> DetailContent(
                model = current.data,
                onRefreshEpisodes = viewModel::refreshEpisodes,
                onBack = onBack,
                onPlayEpisode = onPlayEpisode,
                playerActive = playerActive,
                playingEpisodeId = playingEpisodeId,
                onOpenDownloads = onOpenDownloads,
                onOpenGenre = onOpenGenre,
            )
        }

        if (state !is UiState.Content) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.ui_action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    model: DetailUiModel,
    onRefreshEpisodes: () -> Unit,
    onBack: () -> Unit,
    onPlayEpisode: (Episode, AudioTrack) -> Unit,
    playerActive: Boolean = false,
    playingEpisodeId: Long? = null,
    onOpenDownloads: () -> Unit = {},
    onOpenGenre: (String) -> Unit = {},
) {
    val anime = model.anime
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noSourceMessage = stringResource(R.string.ui_detail_no_source)
    val gridState: LazyGridState = rememberLazyGridState()

    // Two remembered flags instead of one nullable value: `rememberSaveable` can only store bundle
    // types, and a null supplier would throw on the first configuration change.
    var pickedAudio by rememberSaveable(anime.id) { mutableStateOf(AudioTrack.SUB) }
    var audioWasPicked by rememberSaveable(anime.id) { mutableStateOf(false) }
    // The persisted default is only the *initial* choice: once the viewer taps a chip, their pick
    // wins and later preference emissions must not overwrite it.
    val selectedAudio = if (audioWasPicked) pickedAudio else model.initialAudio
    var synopsisExpanded by rememberSaveable(anime.id) { mutableStateOf(false) }

    val collapsed by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 1 || gridState.firstVisibleItemScrollOffset > 180
        }
    }
    val titleAlpha by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "detail-title-alpha",
    )

    // ---- downloads ----
    val context = LocalContext.current
    val downloadEntries by ServiceLocator.downloads.entries.collectAsStateWithLifecycle()
    val downloadsById = remember(downloadEntries) { downloadEntries.associateBy { it.id } }
    val settings = remember(context) { SettingsPreferences(context.applicationContext) }
    val defaultDownloadQuality by settings.downloadQuality.collectAsStateWithLifecycle(initialValue = DownloadQuality.HD)
    var downloadEpisode by remember { mutableStateOf<Episode?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the download runs either way; the permission only makes its progress visible */ }

    val sortedEpisodes = remember(model.episodes) { model.episodes.sortedBy { it.number } }
    val resumeEpisode = sortedEpisodes.firstOrNull { it.id == playingEpisodeId } ?: model.resume?.episode
    val headerTarget = resumeEpisode ?: sortedEpisodes.firstOrNull()

    // Derived once per emission, never per cell: the grid only reads these values.
    val episodeCells = remember(sortedEpisodes, model.progress, resumeEpisode) {
        sortedEpisodes.map { episode ->
            EpisodeCell(
                episode = episode,
                progress = model.progress[episode.id],
                isCurrent = episode.id == resumeEpisode?.id,
            )
        }
    }

    // Single stable handler for every episode cell — one allocation for the whole grid.
    val onEpisodeTap: (Episode) -> Unit = remember(
        selectedAudio,
        onPlayEpisode,
        scope,
        snackbarHostState,
        noSourceMessage,
    ) {
        { episode ->
            if (episode.isPlayable()) {
                onPlayEpisode(episode, episode.pickAudio(selectedAudio))
            } else {
                scope.launch { snackbarHostState.showSnackbar(noSourceMessage) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // 160dp, not 104dp: the tile now carries a big number, a real title and up to three
            // audio badges, and three of those on a 393dp phone was the cramped grid this replaced.
            // Two columns on a phone, more on a tablet.
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (playerActive) Modifier else Modifier.statusBarsPadding())
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!playerActive) {
                item(key = "hero", span = FullSpan, contentType = "hero") {
                    DetailHero(anime = anime)
                }
            } else {
                item(key = "now-playing-title", span = FullSpan, contentType = "title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = anime.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        com.animo.app.ui.common.FavoriteButton(animeId = anime.id)
                    }
                }
            }

            item(key = "meta", span = FullSpan, contentType = "meta") {
                MetaSection(
                    model = model,
                    selectedAudio = selectedAudio,
                    onSelectAudio = { audio ->
                        pickedAudio = audio
                        audioWasPicked = true
                    },
                    onPlayEpisode = onEpisodeTap,
                    onOpenGenre = onOpenGenre,
                )
            }

            if (anime.description.isNotBlank()) {
                item(key = "synopsis", span = FullSpan, contentType = "synopsis") {
                    SynopsisSection(
                        description = anime.description,
                        expanded = synopsisExpanded,
                        onToggle = { synopsisExpanded = !synopsisExpanded },
                    )
                }
            }

            model.resume?.let { resume ->
                item(key = "resume", span = FullSpan, contentType = "resume") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionHeader(title = stringResource(R.string.ui_home_continue))
                        EpisodeRow(
                            episode = resume.episode,
                            onClick = { onEpisodeTap(resume.episode) },
                            watchedFraction = model.progress[resume.episode.id]?.fraction ?: 0f,
                            subtitle = stringResource(
                                R.string.ui_detail_resume_at,
                                formatClock(resume.positionMs),
                            ),
                        )
                    }
                }
            }

            item(key = "episodes-header", span = FullSpan, contentType = "header") {
                EpisodesHeader(
                    episodeCount = sortedEpisodes.size,
                    target = headerTarget,
                    isResume = resumeEpisode != null,
                    error = model.episodesError,
                    onPlayTarget = onEpisodeTap,
                    onRefresh = onRefreshEpisodes,
                )
            }

            if (episodeCells.isEmpty()) {
                item(key = "episodes-empty", span = FullSpan, contentType = "empty") {
                    EmptyState(
                        title = stringResource(R.string.ui_detail_no_episodes_title),
                        body = stringResource(R.string.ui_detail_no_episodes_body),
                        icon = Icons.Rounded.SearchOff,
                        actionLabel = stringResource(R.string.ui_detail_no_episodes_action),
                        onAction = onRefreshEpisodes,
                    )
                }
            } else {
                items(
                    items = episodeCells,
                    key = { cell -> cell.episode.id },
                    contentType = { "episode" },
                ) { cell ->
                    val onClick = remember(cell.episode, onEpisodeTap) { { onEpisodeTap(cell.episode) } }
                    val audio = cell.episode.pickAudio(selectedAudio)
                    // The picked audio's download first; otherwise any language of this episode.
                    val entry = downloadsById[DownloadEntry.idOf(cell.episode.id, audio.name)]
                        ?: downloadEntries.firstOrNull { it.episodeId == cell.episode.id }
                    EpisodeTile(
                        episode = cell.episode,
                        progress = cell.progress,
                        isCurrent = cell.isCurrent,
                        showHindi = anime.isHindi,
                        onClick = onClick,
                        download = entry,
                        onDownload = {
                            // Always the sheet: a finished SUB still leaves DUB, Hindi… to download.
                            downloadEpisode = cell.episode
                        },
                    )
                }
            }
        }

        if (!playerActive) Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.ui_action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = anime.title,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = titleAlpha }
                    .padding(end = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            com.animo.app.ui.common.FavoriteButton(animeId = anime.id)
            IconButton(onClick = onOpenDownloads) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.ui_downloads_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (model.isRefreshingEpisodes) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(16.dp),
                )
            }
        }

        downloadEpisode?.let { episode ->
            val startedMessage = stringResource(R.string.ui_download_started, episode.number)
            DownloadOptionsSheet(
                animeId = anime.id,
                episode = episode,
                preferredAudio = episode.pickAudio(selectedAudio),
                defaultQuality = defaultDownloadQuality,
                downloadedKeys = downloadEntries
                    .filter { it.episodeId == episode.id && it.status != DownloadStatus.FAILED }
                    .map { it.id.substringAfter(':') }
                    .toSet(),
                onDownload = { requests ->
                    downloadEpisode = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    scope.launch {
                        requests.forEach { ServiceLocator.downloads.enqueue(anime, episode, it) }
                        snackbarHostState.showSnackbar(startedMessage)
                    }
                },
                onDismiss = { downloadEpisode = null },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

/**
 * "Episodes / N episodes" plus the two things the section can do: jump straight back into the series
 * and re-fetch the list.
 *
 * `SectionHeader` carries exactly one action, so the resume button sits beside it instead of inside
 * it — the header keeps its own tick/title hierarchy and the buttons stay a single tap target each.
 */
@Composable
private fun EpisodesHeader(
    episodeCount: Int,
    target: Episode?,
    isResume: Boolean,
    error: String?,
    onPlayTarget: (Episode) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                title = stringResource(R.string.ui_detail_episodes),
                subtitle = if (episodeCount == 0) {
                    null
                } else {
                    stringResource(R.string.label_episodes_count, episodeCount)
                },
                modifier = Modifier.weight(1f),
            )
            if (target != null) {
                TextButton(
                    onClick = { onPlayTarget(target) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (isResume) {
                            stringResource(R.string.ui_detail_resume, target.number)
                        } else {
                            stringResource(R.string.ui_detail_episodes_play, target.number)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.ui_action_refresh),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (error != null) {
            Text(
                text = stringResource(R.string.ui_detail_episodes_failed, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Compact episode cell: number, title (only when it says something the number does not), audio
 * badges, and how far the viewer got.
 *
 * Deliberately not a fixed-height tile: the old 96 dp cell reserved a weighted spacer between the
 * badges and the progress line, which read as a hole at the bottom of every episode. Here the
 * content defines the height and the progress line is the last 2 dp of the card.
 *
 * The DUB badge is rendered **only** when `episode.dubUrl != null` — `is_dub` on the parent anime is
 * an episode *count* and is absent on 73.7% of rows, so it can never be used to infer this. HINDI is
 * a title-level fact (the negative id space), which is why it is passed in rather than derived.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeTile(
    episode: Episode,
    progress: EpisodeProgress?,
    isCurrent: Boolean,
    showHindi: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    download: DownloadEntry? = null,
    onDownload: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val playable = episode.isPlayable()
    val shape = MaterialTheme.shapes.small
    val fraction = progress?.fraction
    val watched = progress?.isWatched == true
    val title = episode.displayTitle()
    val stateText = when {
        watched -> stringResource(R.string.ui_detail_episodes_state_watched, episode.number)
        fraction != null -> stringResource(
            R.string.ui_detail_episodes_state_progress,
            episode.number,
            (fraction * 100f).roundToInt(),
        )
        else -> stringResource(R.string.ui_detail_episodes_state_unplayed, episode.number)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isCurrent) colors.primary.copy(alpha = 0.10f) else colors.surfaceContainer)
            .border(
                width = 1.dp,
                color = if (isCurrent) colors.primary.copy(alpha = 0.45f) else colors.outline.copy(alpha = 0.55f),
                shape = shape,
            )
            .clickable(enabled = playable, onClick = onClick)
            .semantics { stateDescription = stateText }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = episode.number.toString(),
                // Tabular figures keep "9" and "11" the same width, so a column of numbers reads as a
                // column instead of a ragged edge.
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = if (playable) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.45f),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (watched) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (playable && onDownload != null) {
                EpisodeDownloadButton(
                    episodeNumber = episode.number,
                    entry = download,
                    onClick = onDownload,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        if (episode.title.isNotBlank()) {
            // `displayTitle()` fabricates "Episode N" for the 5+ blank/duplicate rows in the
            // catalogue; when that is all a row has, the big number above already says it.
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!episode.subUrl.isNullOrBlank()) {
                PillBadge(text = stringResource(R.string.label_sub), tone = BadgeTone.Accent)
            }
            if (episode.dubUrl != null) {
                PillBadge(text = stringResource(R.string.label_dub), tone = BadgeTone.Muted)
            }
            if (showHindi) {
                PillBadge(text = stringResource(R.string.ui_detail_episodes_hindi), tone = BadgeTone.Amber)
            }
        }

        // Always 2 dp tall so every cell in a row has the same height whether or not it was started.
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp),
        ) {
            if (!watched) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(colors.onSurface.copy(alpha = 0.10f)),
                ) {
                    val safe = (fraction ?: 0f).coerceIn(0f, 1f)
                    if (safe > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(safe)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHero(anime: Anime) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(252.dp)
            .clip(HeroShape)
            .background(AnimoPalette.posterPlaceholder),
    ) {
        val art = anime.background.ifBlank { anime.poster }
        if (art.isNotBlank()) {
            AsyncImage(
                model = art,
                contentDescription = stringResource(R.string.ui_content_desc_backdrop, anime.title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.matchParentSize().background(AnimoPalette.backdropScrim))

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .width(94.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (LocalIsDarkTheme.current) {
                            AnimoPalette.posterPlaceholder
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                            )
                        },
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                Text(
                    text = anime.initials(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (LocalIsDarkTheme.current) {
                        AnimoPalette.onPosterSecondary.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
                if (anime.poster.isNotBlank()) {
                    AsyncImage(
                        model = anime.poster,
                        contentDescription = stringResource(R.string.ui_content_desc_poster, anime.title),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AnimoPalette.onPoster,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (anime.native.isNotBlank()) {
                    Text(
                        text = anime.native,
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimoPalette.onPosterSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (anime.alternative.isNotBlank()) {
                    Text(
                        text = anime.alternative,
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimoPalette.onPosterSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaSection(
    model: DetailUiModel,
    selectedAudio: AudioTrack,
    onSelectAudio: (AudioTrack) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onOpenGenre: (String) -> Unit = {},
) {
    val anime = model.anime
    val columns = MaterialTheme.colorScheme
    val episodesLabel = stringResource(R.string.label_episodes_count, model.episodes.size)
    val metaChips = buildList {
        formatScore(anime.score)?.let { add(it to BadgeTone.Amber) }
        anime.year?.let { add(it.toString() to BadgeTone.Muted) }
        anime.type.takeIf { it.isNotBlank() }?.let { add(it to BadgeTone.Muted) }
        anime.status.takeIf { it.isNotBlank() }?.let { add(it to BadgeTone.Muted) }
        anime.rating
            .takeIf { it.isNotBlank() && !it.equals("None", true) && !it.equals("N/A", true) }
            ?.let { add(it to BadgeTone.Outlined) }
        if (model.episodes.isNotEmpty()) add(episodesLabel to BadgeTone.Accent)
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(count = metaChips.size, key = { index -> "meta-$index" }) { index ->
                val (label, tone) = metaChips[index]
                PillBadge(text = label, tone = tone)
            }
        }

        if (anime.genres.isNotEmpty()) {
            val genres = remember(anime.genres) { anime.genres.distinct() }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = genres, key = { it }) { genre ->
                    PillBadge(
                        text = genre,
                        tone = BadgeTone.Outlined,
                        onClick = { onOpenGenre(genre) },
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val resume = model.resume
            if (resume != null) {
                Button(onClick = { onPlayEpisode(resume.episode) }) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ui_detail_resume, resume.episode.number))
                }
            } else {
                val first = model.firstEpisode
                Button(
                    onClick = { first?.let(onPlayEpisode) },
                    enabled = first != null,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ui_detail_play))
                }
            }

            if (model.hasSub && model.hasDub) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioChip(
                        label = stringResource(R.string.label_sub),
                        selected = selectedAudio == AudioTrack.SUB,
                        onClick = { onSelectAudio(AudioTrack.SUB) },
                    )
                    AudioChip(
                        label = stringResource(R.string.label_dub),
                        selected = selectedAudio == AudioTrack.DUB,
                        onClick = { onSelectAudio(AudioTrack.DUB) },
                    )
                }
            }
        }

        if (model.hasDub && !model.hasSub) {
            Text(
                text = stringResource(R.string.ui_detail_audio) + ": " + stringResource(R.string.label_dub),
                style = MaterialTheme.typography.bodySmall,
                color = columns.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(50),
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary.copy(alpha = 0.16f),
            selectedLabelColor = colors.primary,
        ),
    )
}

@Composable
private fun SynopsisSection(
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val canExpand = description.length > 220
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.ui_detail_synopsis))
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (canExpand) {
            TextButton(
                onClick = onToggle,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.ui_detail_read_less else R.string.ui_detail_read_more,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Preview only — every value is a literal, so it composes without a container, a ViewModel or Room.
 */
@Preview(name = "EpisodeTile", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 240)
@Composable
private fun EpisodeTilePreview() {
    AnimoTheme {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EpisodeTile(
                episode = PreviewData.episode,
                progress = EpisodeProgress(positionMs = 320_000L, durationMs = 1_440_000L),
                isCurrent = true,
                showHindi = false,
                onClick = {},
            )
            EpisodeTile(
                episode = PreviewData.episode.copy(id = 7L, number = 13, title = ""),
                progress = EpisodeProgress(positionMs = 1_410_000L, durationMs = 1_440_000L),
                isCurrent = false,
                showHindi = false,
                onClick = {},
            )
            EpisodeTile(
                episode = PreviewData.episode.copy(id = 8L, number = 1042, subUrl = null, dubUrl = "dub"),
                progress = null,
                isCurrent = false,
                showHindi = true,
                onClick = {},
            )
        }
    }
}
