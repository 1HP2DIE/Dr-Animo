package com.animo.app.ui.library

import android.text.format.DateUtils
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
import androidx.compose.material3.LinearProgressIndicator
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
import java.util.Locale
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.domain.repo.HistoryEntry
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
                    onResumeHistory = { entry -> viewModel.resumeHistory(entry) },
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
    onResumeHistory: (HistoryEntry) -> Unit,
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

        if (model.history.isNotEmpty()) {
            item(
                key = "history-header",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) {
                SectionHeader(
                    title = stringResource(R.string.browse_library_history),
                    subtitle = stringResource(R.string.browse_library_history_count, model.history.size),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(
                items = model.history,
                key = { entry -> "history-${entry.episodeId}" },
                span = { GridItemSpan(maxLineSpan) },
                contentType = { "history" },
            ) { entry ->
                HistoryRowItem(
                    entry = entry,
                    busy = resolving == entry.animeId,
                    onClick = { onResumeHistory(entry) },
                )
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
            onResumeHistory = {},
            onOpenAnime = {},
        )
    }
}

/**
 * One watched episode: which episode, how far, and when.
 *
 * The bar is the point of this row — the continue rail can only show one episode per series, so a
 * viewer who watched three of them had no way to see where each one stopped.
 */
@Composable
private fun HistoryRowItem(
    entry: HistoryEntry,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 54.dp, height = 74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.poster.isNotBlank()) {
                AsyncImage(
                    model = entry.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = entry.animeTitle.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.animeTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (entry.episodeNumber > 0) append("E${entry.episodeNumber}")
                    if (entry.episodeTitle.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(entry.episodeTitle)
                    }
                }.ifBlank { stringResource(R.string.browse_library_history_unknown_episode) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            entry.fraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = buildString {
                    if (entry.durationMs > 0L) {
                        append(formatClock(entry.positionMs))
                        append(" / ")
                        append(formatClock(entry.durationMs))
                        entry.fraction?.let { append(" · ") }
                    } else if (entry.positionMs > 0L) {
                        append(formatClock(entry.positionMs))
                        append(" · ")
                    }
                    append(relativeAge(entry.updatedAtMs))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `12:34`, or `1:02:03` past an hour. */
private fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** "3 days ago" — composed once per row; a stale hint is cheaper than a ticking clock. */
private fun relativeAge(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    if (System.currentTimeMillis() - epochMs < 60_000L) return "just now"
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
