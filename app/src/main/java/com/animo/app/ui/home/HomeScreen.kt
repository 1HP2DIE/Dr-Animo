package com.animo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Menu
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.animo.app.ui.common.BrandHero
import com.animo.app.ui.common.ContinueWatchingCard
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.SectionHeader
import com.animo.app.ui.common.rememberAnimeClick
import com.animo.app.ui.shell.formatCount
import com.animo.app.ui.theme.AnimoPalette

private val GridColumns = GridCells.Adaptive(minSize = 112.dp)
private val GridGutter = 16.dp
private val HeroHeight = 168.dp

@Composable
fun HomeScreen(
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val factory = remember(container) { homeViewModelFactory(container.animeRepository) }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    HomeScreen(
        viewModel = viewModel,
        onOpenAnime = onOpenAnime,
        onOpenSearch = onOpenSearch,
        onOpenDrawer = onOpenDrawer,
        onOpenLibrary = onOpenLibrary,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val syncFailedTemplate = stringResource(R.string.ui_home_sync_failed)

    val repository = LocalAppContainer.current.animeRepository
    val syncState = (state as? UiState.Content)?.data?.sync?.state
    // The header count is catalogue-wide, not "loaded rows"; recount whenever a sync settles.
    // Plain state + LaunchedEffect rather than produceState: lint's ProduceStateDoesNotAssignValue
    // cannot see through the `by` delegate on a single-assignment producer and flags it as a bug.
    var catalogueSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, syncState) {
        catalogueSize = runCatching { repository.catalogSize() }.getOrDefault(0)
    }

    LaunchedEffect(viewModel, syncFailedTemplate) {
        viewModel.syncFailures.collect { cause ->
            snackbarHostState.showSnackbar(syncFailedTemplate.format(cause))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (val current = state) {
                UiState.Loading -> HomeSkeleton()
                UiState.Empty -> HomePlaceholder(
                    catalogueSize = catalogueSize,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = onOpenSearch,
                ) {
                    EmptyState(
                        title = stringResource(R.string.ui_home_empty_title),
                        body = stringResource(R.string.ui_home_empty_body),
                        icon = Icons.Rounded.CloudOff,
                        actionLabel = stringResource(R.string.ui_home_empty_action),
                        onAction = viewModel::refresh,
                    )
                }
                is UiState.Error -> HomePlaceholder(
                    catalogueSize = catalogueSize,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = onOpenSearch,
                ) {
                    ErrorState(
                        message = current.message,
                        onRetry = viewModel::refresh,
                    )
                }
                is UiState.Content -> HomeContent(
                    model = current.data,
                    catalogueSize = catalogueSize,
                    onRefresh = viewModel::refresh,
                    onOpenAnime = onOpenAnime,
                    onOpenSearch = onOpenSearch,
                    onOpenDrawer = onOpenDrawer,
                    onOpenLibrary = onOpenLibrary,
                )
            }
        }
    }
}

/** Hero stays visible while the body is empty or failed — it is the app's only drawer entry point. */
@Composable
private fun HomePlaceholder(
    catalogueSize: Int,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HomeHero(
            catalogueSize = catalogueSize,
            onOpenDrawer = onOpenDrawer,
            onOpenSearch = onOpenSearch,
            modifier = Modifier.padding(start = GridGutter, end = GridGutter, top = 8.dp),
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    model: HomeUiModel,
    catalogueSize: Int,
    onRefresh: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenLibrary: () -> Unit,
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
            contentPadding = PaddingValues(
                start = GridGutter,
                end = GridGutter,
                top = 8.dp,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(
                key = "hero",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "hero",
            ) {
                HomeHero(
                    catalogueSize = catalogueSize,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = onOpenSearch,
                )
            }

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
                    SectionHeader(
                        title = stringResource(R.string.ui_home_continue),
                        actionLabel = stringResource(R.string.ui_home_see_all),
                        onAction = onOpenLibrary,
                    )
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

/**
 * Compact hero: Dr. Animo banner, a charcoal scrim for legibility, the drawer and search entries,
 * and the catalogue count as the subtitle. Copy stays light in both themes because the artwork
 * underneath is always dark.
 */
@Composable
private fun HomeHero(
    catalogueSize: Int,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape),
    ) {
        BrandHero(
            modifier = Modifier.fillMaxWidth(),
            height = HeroHeight,
            cornerRadius = 0.dp,
        )
        Box(Modifier.matchParentSize().background(AnimoPalette.heroScrim))
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = stringResource(R.string.ui_drawer_open),
                        tint = AnimoPalette.onHero,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.ui_home_search),
                        tint = AnimoPalette.onHero,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = AnimoPalette.onHero,
            )
            Text(
                text = if (catalogueSize > 0) {
                    stringResource(R.string.ui_home_hero_subtitle, formatCount(catalogueSize))
                } else {
                    stringResource(R.string.app_tagline)
                },
                style = MaterialTheme.typography.bodySmall,
                color = AnimoPalette.onHeroSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
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
                trackColor = colors.onSurface.copy(alpha = 0.10f),
            )
        }
    }
}
