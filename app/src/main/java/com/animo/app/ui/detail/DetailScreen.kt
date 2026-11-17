package com.animo.app.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.BadgeTone
import com.animo.app.ui.common.EpisodeCard
import com.animo.app.ui.common.EpisodeRow
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.LoadingState
import com.animo.app.ui.common.PillBadge
import com.animo.app.ui.common.SectionHeader
import com.animo.app.ui.common.formatClock
import com.animo.app.ui.common.formatScore
import com.animo.app.ui.common.initials
import com.animo.app.ui.common.isPlayable
import com.animo.app.ui.common.pickAudio
import com.animo.app.ui.theme.AnimoPalette
import kotlinx.coroutines.launch

private val FullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

private val HeroShape = RoundedCornerShape(20.dp)

@Composable
fun DetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    onPlayEpisode: (Episode, AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val factory = remember(container, animeId) {
        detailViewModelFactory(animeId, container.animeRepository)
    }
    val viewModel: DetailViewModel = viewModel(factory = factory)
    DetailScreen(
        viewModel = viewModel,
        onBack = onBack,
        onPlayEpisode = onPlayEpisode,
        modifier = modifier,
    )
}

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (Episode, AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
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
) {
    val anime = model.anime
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noSourceMessage = stringResource(R.string.ui_detail_no_source)
    val gridState: LazyGridState = rememberLazyGridState()

    var selectedAudio by rememberSaveable(anime.id) { mutableStateOf(AudioTrack.SUB) }
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

    val sortedEpisodes = remember(model.episodes) { model.episodes.sortedBy { it.number } }

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
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "hero", span = FullSpan, contentType = "hero") {
                DetailHero(anime = anime)
            }

            item(key = "meta", span = FullSpan, contentType = "meta") {
                MetaSection(
                    model = model,
                    selectedAudio = selectedAudio,
                    onSelectAudio = { audio -> selectedAudio = audio },
                    onPlayEpisode = onEpisodeTap,
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
                            onClick = {
                                if (resume.episode.isPlayable()) {
                                    onEpisodeTap(resume.episode)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar(noSourceMessage) }
                                }
                            },
                            subtitle = stringResource(
                                R.string.ui_detail_resume_at,
                                formatClock(resume.positionMs),
                            ),
                        )
                    }
                }
            }

            item(key = "episodes-header", span = FullSpan, contentType = "header") {
                Column(Modifier.padding(top = 10.dp)) {
                    SectionHeader(
                        title = stringResource(R.string.ui_detail_episodes),
                        subtitle = if (sortedEpisodes.isEmpty()) {
                            null
                        } else {
                            stringResource(R.string.label_episodes_count, sortedEpisodes.size)
                        },
                        actionLabel = stringResource(R.string.ui_action_refresh),
                        onAction = onRefreshEpisodes,
                    )
                    model.episodesError?.let { error ->
                        Text(
                            text = stringResource(R.string.ui_detail_episodes_failed, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (sortedEpisodes.isEmpty()) {
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
                    items = sortedEpisodes,
                    key = { episode -> episode.id },
                    contentType = { "episode" },
                ) { episode ->
                    val onClick = remember(episode, onEpisodeTap) { { onEpisodeTap(episode) } }
                    EpisodeCard(
                        episode = episode,
                        onClick = onClick,
                        isCurrent = model.resume?.episode?.id == episode.id,
                    )
                }
            }
        }

        Row(
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
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
                    .background(AnimoPalette.posterPlaceholder)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (anime.native.isNotBlank()) {
                    Text(
                        text = anime.native,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (anime.alternative.isNotBlank()) {
                    Text(
                        text = anime.alternative,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                    PillBadge(text = genre, tone = BadgeTone.Outlined)
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
