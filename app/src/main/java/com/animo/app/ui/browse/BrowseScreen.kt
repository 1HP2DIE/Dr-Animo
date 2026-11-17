package com.animo.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SortOrder
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.AnimeCard
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.PosterGridSkeleton
import com.animo.app.ui.common.PreviewData
import com.animo.app.ui.common.SearchField
import com.animo.app.ui.common.rememberAnimeClick
import com.animo.app.ui.contentOrNull
import com.animo.app.ui.theme.AnimoTheme
import kotlinx.coroutines.launch

/** Trigger the next page when the last visible cell is this close to the end of the list. */
private const val LOAD_MORE_LOOKAHEAD = 12

/**
 * Browse: search, facets and an A–Z rail over the local catalog.
 *
 * The three optional arguments come from the `browse?genre=…&letter=…&hindi=true` deep link and are
 * applied on first composition. [onOpenAnime] is the navigation hook; it defaults to a no-op so a
 * caller that only wants the data surface can still render the screen.
 */
@Composable
fun BrowseScreen(
    initialGenre: String? = null,
    initialLetter: String? = null,
    initialHindi: Boolean = false,
    modifier: Modifier = Modifier,
    onOpenAnime: (Int) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val factory = remember(container) { browseViewModelFactory(container.animeRepository) }
    val viewModel: BrowseViewModel = viewModel(factory = factory)

    val query by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val facets by viewModel.facets.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val items = results.contentOrNull.orEmpty()
    val loadedCount by rememberUpdatedState(items.size)
    val canLoadMore by rememberUpdatedState(hasMore)

    LaunchedEffect(initialGenre, initialLetter, initialHindi) {
        viewModel.applyInitial(initialGenre, initialLetter, initialHindi)
    }

    // Re-arms on every distinct "last visible index". Appending a page does not change it, so this
    // never chains into an accidental full-catalog download.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (canLoadMore && lastVisible >= loadedCount - LOAD_MORE_LOOKAHEAD) {
                    viewModel.loadMore()
                }
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            SearchField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            BrowseFilterBar(
                activeCount = filters.activeFilterCount,
                sort = filters.sort,
                onOpenFilters = { sheetOpen = true },
                onSortSelect = viewModel::setSort,
                onClearAll = viewModel::resetFilters,
            )

            ActiveFilterRow(
                filters = filters,
                onChange = viewModel::applyFilters,
            )

            ResultsSummary(
                count = items.size,
                hasMore = hasMore,
                visible = results is UiState.Content,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    when (val state = results) {
                        UiState.Loading -> PosterGridSkeleton()
                        UiState.Empty -> if (filters.activeFilterCount == 0 && query.isBlank()) {
                            EmptyState(
                                title = stringResource(R.string.browse_empty_catalog_title),
                                body = stringResource(R.string.browse_empty_catalog_body),
                                icon = Icons.Rounded.CloudOff,
                            )
                        } else {
                            EmptyState(
                                title = stringResource(R.string.browse_empty_title),
                                body = stringResource(R.string.browse_empty_body),
                                icon = Icons.Rounded.SearchOff,
                                actionLabel = stringResource(R.string.browse_empty_reset),
                                onAction = viewModel::resetFilters,
                            )
                        }
                        is UiState.Error -> ErrorState(
                            message = state.message,
                            onRetry = viewModel::retry,
                        )
                        is UiState.Content -> ResultsGrid(
                            items = state.data,
                            hasMore = hasMore,
                            gridState = gridState,
                            onOpenAnime = onOpenAnime,
                        )
                    }
                }

                AlphabetIndex(
                    selected = filters.letter,
                    onSelect = { letter ->
                        viewModel.setLetter(letter)
                        scope.launch { gridState.scrollToItem(0) }
                    },
                    modifier = Modifier
                        .width(20.dp)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp),
                )
            }
        }
    }

    if (sheetOpen) {
        FilterSheet(
            filters = filters,
            facets = facets,
            resultCount = items.size,
            hasMore = hasMore,
            onFiltersChange = viewModel::applyFilters,
            onReset = viewModel::resetFilters,
            onDismiss = { sheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseFilterBar(
    activeCount: Int,
    sort: SortOrder,
    onOpenFilters: () -> Unit,
    onSortSelect: (SortOrder) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = activeCount > 0,
            onClick = onOpenFilters,
            shape = RoundedCornerShape(50),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = {
                Text(
                    text = if (activeCount > 0) {
                        stringResource(R.string.browse_filters_count, activeCount)
                    } else {
                        stringResource(R.string.browse_filters)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = colors.onSurface.copy(alpha = 0.04f),
                labelColor = colors.onSurfaceVariant,
                iconColor = colors.onSurfaceVariant,
                selectedContainerColor = colors.primary.copy(alpha = 0.16f),
                selectedLabelColor = colors.primary,
                selectedLeadingIconColor = colors.primary,
            ),
        )

        SortMenu(selected = sort, onSelect = onSortSelect)

        Spacer(Modifier.weight(1f))

        if (activeCount > 0) {
            TextButton(onClick = onClearAll) {
                Text(
                    text = stringResource(R.string.browse_clear_all),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** One removable chip per active filter part; the chip itself is the remove affordance. */
@Composable
private fun ActiveFilterRow(
    filters: Filters,
    onChange: (Filters) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filters.hasActiveFilters) {
        Spacer(Modifier.height(4.dp))
        return
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filters.genres.sorted().forEach { genre ->
            item(key = "genre:$genre", contentType = "chip") {
                RemovableFilterChip(
                    label = genre,
                    onRemove = { onChange(filters.copy(genres = filters.genres - genre)) },
                )
            }
        }

        val yearFrom = filters.yearFrom
        val yearTo = filters.yearTo
        if (yearFrom != null || yearTo != null) {
            item(key = "year", contentType = "chip") {
                RemovableFilterChip(
                    label = when {
                        yearFrom != null && yearTo != null ->
                            stringResource(R.string.browse_chip_year_range, yearFrom, yearTo)
                        yearFrom != null -> stringResource(R.string.browse_chip_year_from, yearFrom)
                        else -> stringResource(R.string.browse_chip_year_to, yearTo ?: 0)
                    },
                    onRemove = { onChange(filters.copy(yearFrom = null, yearTo = null)) },
                )
            }
        }

        filters.type?.let { type ->
            item(key = "type", contentType = "chip") {
                RemovableFilterChip(
                    label = type,
                    onRemove = { onChange(filters.copy(type = null)) },
                )
            }
        }

        filters.status?.let { status ->
            item(key = "status", contentType = "chip") {
                RemovableFilterChip(
                    label = statusLabel(status),
                    onRemove = { onChange(filters.copy(status = null)) },
                )
            }
        }

        filters.letter?.let { letter ->
            item(key = "letter", contentType = "chip") {
                RemovableFilterChip(
                    label = stringResource(R.string.browse_chip_letter, letter.toString()),
                    onRemove = { onChange(filters.copy(letter = null)) },
                )
            }
        }

        if (filters.hindiOnly) {
            item(key = "hindi", contentType = "chip") {
                RemovableFilterChip(
                    label = stringResource(R.string.browse_chip_hindi),
                    onRemove = { onChange(filters.copy(hindiOnly = false)) },
                )
            }
        }

        if (filters.hasDub) {
            item(key = "dub", contentType = "chip") {
                RemovableFilterChip(
                    label = stringResource(R.string.browse_chip_dub),
                    onRemove = { onChange(filters.copy(hasDub = false)) },
                )
            }
        }
    }
}

@Composable
private fun RemovableFilterChip(
    label: String,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    InputChip(
        selected = true,
        onClick = onRemove,
        shape = RoundedCornerShape(50),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.browse_chip_remove, label),
                modifier = Modifier.size(14.dp),
            )
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary.copy(alpha = 0.14f),
            selectedLabelColor = colors.primary,
            selectedTrailingIconColor = colors.primary,
        ),
    )
}

@Composable
private fun ResultsSummary(
    count: Int,
    hasMore: Boolean,
    visible: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                !visible -> ""
                hasMore -> stringResource(R.string.browse_results_more, count)
                else -> stringResource(R.string.browse_results, count)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ResultsGrid(
    items: List<Anime>,
    hasMore: Boolean,
    gridState: LazyGridState,
    onOpenAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            items = items,
            key = { anime -> anime.id },
            contentType = { "poster" },
        ) { anime ->
            AnimeCard(
                anime = anime,
                onClick = rememberAnimeClick(anime.id, onOpenAnime),
            )
        }

        if (hasMore) {
            item(
                key = "load-more",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "loading",
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
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

@Preview(name = "Browse grid", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 390, heightDp = 780)
@Composable
private fun BrowseGridPreview() {
    AnimoTheme {
        val rows = listOf(
            PreviewData.anime,
            PreviewData.anime.copy(id = 2, title = "One Piece", score = 8.72, poster = ""),
            PreviewData.anime.copy(id = 3, title = "Jujutsu Kaisen", score = 8.56, poster = ""),
            PreviewData.anime.copy(id = 4, title = "Bocchi the Rock!", score = 8.78, poster = ""),
            PreviewData.anime.copy(id = 5, title = "Vinland Saga", score = 8.61, poster = ""),
            PreviewData.anime.copy(id = 6, title = "Mushishi", score = 8.68, poster = ""),
        )
        ResultsGrid(
            items = rows,
            hasMore = true,
            gridState = rememberLazyGridState(),
            onOpenAnime = {},
        )
    }
}
