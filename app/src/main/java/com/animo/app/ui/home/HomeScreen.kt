package com.animo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.R
import com.animo.app.domain.model.CatalogueCounts
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
private val HeroHeight = 208.dp
private val PosterRailWidth = 124.dp
private val ContinueRailWidth = 196.dp

@Composable
fun HomeScreen(
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenBrowse: () -> Unit = {},
    onOpenHindi: () -> Unit = {},
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
        onOpenBrowse = onOpenBrowse,
        onOpenHindi = onOpenHindi,
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
    onOpenBrowse: () -> Unit = {},
    onOpenHindi: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val syncFailedTemplate = stringResource(R.string.ui_home_sync_failed)

    val repository = LocalAppContainer.current.animeRepository
    val syncState = (state as? UiState.Content)?.data?.sync?.state
    // Split counts, not a single total: the hero states how much of the catalogue is Hindi, and the
    // empty copy needs to know the original catalogue is still missing.
    // Plain state + LaunchedEffect rather than produceState (same reason as before: lint cannot see
    // through the `by` delegate on a single-assignment producer and flags it as a bug).
    var counts by remember { mutableStateOf(CatalogueCounts()) }
    LaunchedEffect(repository, syncState) {
        counts = runCatching { repository.counts() }.getOrDefault(counts)
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
                    counts = counts,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = onOpenSearch,
                    onOpenBrowse = onOpenBrowse,
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
                    counts = counts,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = onOpenSearch,
                    onOpenBrowse = onOpenBrowse,
                ) {
                    ErrorState(
                        message = current.message,
                        onRetry = viewModel::refresh,
                    )
                }
                is UiState.Content -> HomeContent(
                    model = current.data,
                    counts = counts,
                    onRefresh = viewModel::refresh,
                    onOpenAnime = onOpenAnime,
                    onOpenSearch = onOpenSearch,
                    onOpenDrawer = onOpenDrawer,
                    onOpenLibrary = onOpenLibrary,
                    onOpenBrowse = onOpenBrowse,
                    onOpenHindi = onOpenHindi,
                )
            }
        }
    }
}

/** Hero stays visible while the body is empty or failed — it is the app's only drawer entry point. */
@Composable
private fun HomePlaceholder(
    counts: CatalogueCounts,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBrowse: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HomeHero(
            counts = counts,
            onOpenDrawer = onOpenDrawer,
            onOpenSearch = onOpenSearch,
            onOpenBrowse = onOpenBrowse,
            modifier = Modifier.padding(start = GridGutter, end = GridGutter),
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    model: HomeUiModel,
    counts: CatalogueCounts,
    onRefresh: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenHindi: () -> Unit,
) {
    val seeAll = stringResource(R.string.ui_home_see_all)
    val continueLabel = stringResource(R.string.ui_home_continue)
    val hindiLabel = stringResource(R.string.ui_home_hindi)
    val hindiSubtitle = stringResource(R.string.ui_home_hindi_subtitle)
    val recentLabel = stringResource(R.string.ui_home_recent)
    val recentCount = stringResource(R.string.ui_home_recent_count, model.recent.size)
    val topRatedLabel = stringResource(R.string.ui_home_top_rated)
    val topRatedSubtitle = stringResource(R.string.ui_home_top_rated_subtitle)
    val emptyTitle = stringResource(R.string.ui_home_empty_title)
    val emptyBody = stringResource(R.string.ui_home_empty_body)
    val emptyAction = stringResource(R.string.ui_home_empty_action)

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
                top = 0.dp,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
                HomeHero(
                    counts = counts,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = onOpenSearch,
                    onOpenBrowse = onOpenBrowse,
                )
            }

            if (model.showSyncBanner) {
                item(key = "sync-banner", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                    SyncBanner(progress = model.sync)
                }
            }

            if (model.continueWatching.isNotEmpty()) {
                shelfHeader(
                    key = "continue-header",
                    title = continueLabel,
                    actionLabel = seeAll,
                    onAction = onOpenLibrary,
                )
                shelfRail(
                    key = "continue-rail",
                    items = model.continueWatching,
                    contentType = "continue",
                    itemKey = { "continue-${it.anime.id}" },
                ) { item ->
                    ContinueWatchingCard(
                        anime = item.anime,
                        onClick = rememberAnimeClick(item.anime.id, onOpenAnime),
                        modifier = Modifier.width(ContinueRailWidth),
                        episodeNumber = item.episodeNumber,
                        progress = item.fraction,
                    )
                }
            }

            if (model.hindi.isNotEmpty()) {
                shelfHeader(
                    key = "hindi-header",
                    title = hindiLabel,
                    subtitle = hindiSubtitle,
                    actionLabel = seeAll,
                    onAction = onOpenHindi,
                )
                shelfRail(
                    key = "hindi-rail",
                    items = model.hindi,
                    contentType = "poster",
                    itemKey = { "hindi-${it.id}" },
                ) { anime ->
                    AnimeCard(
                        anime = anime,
                        onClick = rememberAnimeClick(anime.id, onOpenAnime),
                        modifier = Modifier.width(PosterRailWidth),
                        titleLines = 3,
                    )
                }
            }

            if (model.recent.isNotEmpty()) {
                shelfHeader(
                    key = "recent-header",
                    title = recentLabel,
                    subtitle = recentCount,
                    actionLabel = seeAll,
                    onAction = onOpenBrowse,
                )
                shelfRail(
                    key = "recent-rail",
                    items = model.recent,
                    contentType = "poster",
                    itemKey = { "recent-${it.id}" },
                ) { anime ->
                    AnimeCard(
                        anime = anime,
                        onClick = rememberAnimeClick(anime.id, onOpenAnime),
                        modifier = Modifier.width(PosterRailWidth),
                        titleLines = 3,
                    )
                }
            }

            if (model.topRated.isNotEmpty()) {
                shelfHeader(
                    key = "rating-header",
                    title = topRatedLabel,
                    subtitle = topRatedSubtitle,
                )
                shelfRail(
                    key = "rating-rail",
                    items = model.topRated,
                    contentType = "poster",
                    itemKey = { "rating-${it.id}" },
                ) { anime ->
                    AnimeCard(
                        anime = anime,
                        onClick = rememberAnimeClick(anime.id, onOpenAnime),
                        modifier = Modifier.width(PosterRailWidth),
                        titleLines = 3,
                    )
                }
            }

            item(key = "browse-all", span = { GridItemSpan(maxLineSpan) }, contentType = "cta") {
                BrowseAllCta(counts = counts, onOpenBrowse = onOpenBrowse)
            }

            if (model.isEmpty) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    EmptyState(
                        title = emptyTitle,
                        body = emptyBody,
                        icon = Icons.Rounded.CloudOff,
                        actionLabel = emptyAction,
                        onAction = onRefresh,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shelf helpers

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.shelfHeader(
    key: String,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        SectionHeader(
            title = title,
            subtitle = subtitle,
            actionLabel = actionLabel,
            onAction = onAction,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private fun <T> androidx.compose.foundation.lazy.grid.LazyGridScope.shelfRail(
    key: String,
    items: List<T>,
    contentType: String,
    itemKey: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "rail") {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(
                items = items,
                key = itemKey,
                contentType = { contentType },
            ) { item -> card(item) }
        }
    }
}

// ---------------------------------------------------------------- hero

/**
 * Top-anchored billboard: the card is flush with the status bar and carries the only two entry
 * points that are not in the bottom bar (drawer, search).
 *
 * The artwork is dark in every theme, so all copy here is [AnimoPalette.onHero] rather than a
 * colour-scheme role — that pair is what keeps light mode legible.
 */
@Composable
private fun HomeHero(
    counts: CatalogueCounts,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape),
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
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroIconButton(onClick = onOpenDrawer, icon = Icons.Rounded.Menu, label = stringResource(R.string.ui_drawer_open))
                Spacer(Modifier.weight(1f))
                HeroIconButton(onClick = onOpenSearch, icon = Icons.Rounded.Search, label = stringResource(R.string.ui_home_search))
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    text = stringResource(R.string.ui_home_hero_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = AnimoPalette.accent,
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = AnimoPalette.onHero,
                )
                Text(
                    text = if (counts.total > 0) {
                        stringResource(
                            R.string.ui_home_hero_counts,
                            formatCount(counts.original),
                            formatCount(counts.hindi),
                        )
                    } else {
                        stringResource(R.string.app_tagline)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AnimoPalette.onHeroSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                HeroBrowsePill(
                    label = stringResource(R.string.ui_home_browse_all),
                    onClick = onOpenBrowse,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .background(AnimoPalette.posterBadgeBg),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = AnimoPalette.onHero,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HeroBrowsePill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(AnimoPalette.accent.copy(alpha = 0.92f))
            .border(1.dp, AnimoPalette.onHero.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = AnimoPalette.onAccent,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = AnimoPalette.onAccent,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ---------------------------------------------------------------- footer

@Composable
private fun BrowseAllCta(counts: CatalogueCounts, onOpenBrowse: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        FilledTonalButton(
            onClick = onOpenBrowse,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (counts.total > 0) {
                    stringResource(R.string.ui_home_browse_all_count, formatCount(counts.total))
                } else {
                    stringResource(R.string.ui_home_browse_all)
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.padding(start = 6.dp).size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- sync banner

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
            .padding(top = 10.dp)
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
