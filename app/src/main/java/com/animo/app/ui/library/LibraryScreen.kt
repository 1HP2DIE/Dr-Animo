package com.animo.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.ContinueWatchingCard
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.PillBadge
import com.animo.app.ui.common.PosterGridSkeleton
import com.animo.app.ui.common.PreviewData
import com.animo.app.ui.common.SectionHeader
import com.animo.app.ui.common.initials
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.AnimoTheme

/**
 * Library: resume rail + full history, both derived from progress rows.
 *
 * Read-only by design — there is deliberately no "remove from history" affordance here.
 */
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onOpenAnime: (Int) -> Unit = {},
    onResume: (animeId: Int, episodeId: Long, audio: AudioTrack) -> Unit = { _, _, _ -> },
    onBrowse: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val factory = remember(container) { libraryViewModelFactory(container.animeRepository) }
    val viewModel: LibraryViewModel = viewModel(factory = factory)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()

    val currentOnOpenAnime by rememberUpdatedState(onOpenAnime)
    val currentOnResume by rememberUpdatedState(onResume)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Resume -> currentOnResume(event.animeId, event.episodeId, event.audio)
                is LibraryEvent.OpenDetail -> currentOnOpenAnime(event.animeId)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (val current = state) {
                UiState.Loading -> PosterGridSkeleton()
                UiState.Empty -> EmptyState(
                    title = stringResource(R.string.browse_library_empty_title),
                    body = stringResource(R.string.browse_library_empty_body),
                    icon = Icons.Rounded.VideoLibrary,
                    actionLabel = stringResource(R.string.browse_library_empty_action),
                    onAction = onBrowse,
                )
                is UiState.Error -> ErrorState(message = current.message)
                is UiState.Content -> LibraryContent(
                    model = current.data,
                    resolving = resolving,
                    onResume = { anime -> viewModel.resume(anime) },
                    onOpenAnime = onOpenAnime,
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(
    model: LibraryUiModel,
    resolving: Int?,
    onResume: (Anime) -> Unit,
    onOpenAnime: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (model.continueWatching.isNotEmpty()) {
            item(
                key = "continue-header",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) {
                SectionHeader(
                    title = stringResource(R.string.browse_library_continue),
                    subtitle = stringResource(R.string.browse_library_count, model.continueWatching.size),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(
                items = model.continueWatching,
                key = { anime -> "continue-${anime.id}" },
                contentType = { "continue" },
            ) { anime ->
                Box {
                    ContinueWatchingCard(
                        anime = anime,
                        onClick = { onResume(anime) },
                    )
                    if (resolving == anime.id) {
                        val label = stringResource(R.string.browse_library_resuming)
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }

        item(
            key = "all-header",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "header",
        ) {
            SectionHeader(
                title = stringResource(R.string.browse_library_all),
                subtitle = stringResource(R.string.browse_library_count, model.watched.size),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(
            items = model.watched,
            key = { anime -> "watched-${anime.id}" },
            span = { GridItemSpan(maxLineSpan) },
            contentType = { "watched" },
        ) { anime ->
            WatchedRow(
                anime = anime,
                onClick = { onOpenAnime(anime.id) },
            )
        }
    }
}

/** Poster thumb + title + meta, the "history" shape — dense enough to scan, no art-heavy card. */
@Composable
private fun WatchedRow(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.browse_library_open, anime.title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(AnimoPalette.posterPlaceholder),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = anime.initials(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            if (anime.poster.isNotBlank()) {
                AsyncImage(
                    model = anime.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = anime.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                anime.year?.let { year -> PillBadge(text = year.toString()) }
                anime.type.takeIf { it.isNotBlank() }?.let { type -> PillBadge(text = type) }
                if (anime.episodeCount > 0) {
                    PillBadge(
                        text = stringResource(R.string.label_episodes_count, anime.episodeCount),
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(2.dp))
    }
}

@Preview(name = "Library", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 390, heightDp = 800)
@Composable
private fun LibraryPreview() {
    AnimoTheme {
        LibraryContent(
            model = LibraryUiModel(
                continueWatching = listOf(
                    PreviewData.anime,
                    PreviewData.anime.copy(id = 2, title = "One Piece", poster = ""),
                ),
                watched = listOf(
                    PreviewData.anime,
                    PreviewData.anime.copy(id = 2, title = "One Piece", poster = "", year = 1999),
                    PreviewData.anime.copy(id = 3, title = "Mushishi", poster = "", year = 2005),
                ),
            ),
            resolving = null,
            onResume = {},
            onOpenAnime = {},
        )
    }
}
