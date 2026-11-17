package com.animo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.R
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.AnimeCard
import com.animo.app.ui.common.ContinueWatchingCard
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.PosterGridSkeleton
import com.animo.app.ui.common.SectionHeader
import com.animo.app.ui.common.rememberAnimeClick
import com.animo.app.ui.theme.AnimoPalette

private val GridColumns = GridCells.Adaptive(minSize = 112.dp)

@Composable
fun HomeScreen(
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val factory = remember(container) { homeViewModelFactory(container.animeRepository) }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    HomeScreen(
        viewModel = viewModel,
        onOpenAnime = onOpenAnime,
        onOpenSearch = onOpenSearch,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val syncFailedTemplate = stringResource(R.string.ui_home_sync_failed)

    LaunchedEffect(viewModel, syncFailedTemplate) {
        viewModel.syncFailures.collect { cause ->
            snackbarHostState.showSnackbar(syncFailedTemplate.format(cause))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.ui_home_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (val current = state) {
                UiState.Loading -> PosterGridSkeleton()
                UiState.Empty -> EmptyState(
                    title = stringResource(R.string.ui_home_empty_title),
                    body = stringResource(R.string.ui_home_empty_body),
                    icon = Icons.Rounded.CloudOff,
                    actionLabel = stringResource(R.string.ui_home_empty_action),
                    onAction = viewModel::refresh,
                )
                is UiState.Error -> ErrorState(
                    message = current.message,
                    onRetry = viewModel::refresh,
                )
                is UiState.Content -> HomeContent(
                    model = current.data,
                    onRefresh = viewModel::refresh,
                    onOpenAnime = onOpenAnime,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    model: HomeUiModel,
    onRefresh: () -> Unit,
    onOpenAnime: (Int) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = model.isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState(),
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridColumns,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (model.showSyncBanner) {
                item(
                    key = "sync-banner",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "banner",
                ) {
                    SyncBanner(progress = model.sync)
                }
            }

            if (model.continueWatching.isNotEmpty()) {
                item(
                    key = "continue-header",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "header",
                ) {
                    SectionHeader(title = stringResource(R.string.ui_home_continue))
                }
                item(
                    key = "continue-rail",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "rail",
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(
                            items = model.continueWatching,
                            key = { anime -> "continue-${anime.id}" },
                            contentType = { "continue" },
                        ) { anime ->
                            ContinueWatchingCard(
                                anime = anime,
                                onClick = rememberAnimeClick(anime.id, onOpenAnime),
                                modifier = Modifier.width(184.dp),
                            )
                        }
                    }
                }
            }

            item(
                key = "recent-header",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) {
                SectionHeader(
                    title = stringResource(R.string.ui_home_recent),
                    subtitle = if (model.recent.isEmpty()) {
                        null
                    } else {
                        stringResource(R.string.ui_home_recent_count, model.recent.size)
                    },
                )
            }

            items(
                items = model.recent,
                key = { anime -> anime.id },
                contentType = { "poster" },
            ) { anime ->
                AnimeCard(
                    anime = anime,
                    onClick = rememberAnimeClick(anime.id, onOpenAnime),
                )
            }

            if (model.recent.isEmpty()) {
                item(
                    key = "recent-empty",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "empty",
                ) {
                    EmptyState(
                        title = stringResource(R.string.ui_home_empty_title),
                        body = stringResource(R.string.ui_home_empty_body),
                        icon = Icons.Rounded.CloudOff,
                        actionLabel = stringResource(R.string.ui_home_empty_action),
                        onAction = onRefresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncBanner(progress: SyncProgress) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val fraction = if (progress.total > 0) {
        (progress.done.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val headline = when (progress.state) {
        SyncState.THROTTLED -> stringResource(
            R.string.ui_sync_throttled_progress,
            progress.done,
            progress.total,
        )
        else -> stringResource(R.string.ui_sync_progress, progress.done, progress.total)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.primary.copy(alpha = 0.08f))
            .border(1.dp, colors.primary.copy(alpha = 0.22f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = colors.primary,
            strokeWidth = 2.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                maxLines = 2,
            )
            if (progress.message.isNotBlank()) {
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                color = colors.primary,
                trackColor = AnimoPalette.surfaceHigh,
            )
        }
    }
}
