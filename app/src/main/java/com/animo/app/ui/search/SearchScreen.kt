package com.animo.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.R
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.UiState
import com.animo.app.ui.common.ActiveFilterSummary
import com.animo.app.ui.common.AnimeCard
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.ErrorState
import com.animo.app.ui.common.FilterChipRow
import com.animo.app.ui.common.FilterOption
import com.animo.app.ui.common.LoadingState
import com.animo.app.ui.common.SearchField
import com.animo.app.ui.common.rememberAnimeClick

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val factory = remember(container) { searchViewModelFactory(container.animeRepository) }
    val viewModel: SearchViewModel = viewModel(factory = factory)
    SearchScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenAnime = onOpenAnime,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val facets by viewModel.facets.collectAsStateWithLifecycle()
    val catalogIsEmpty by viewModel.catalogIsEmpty.collectAsStateWithLifecycle()

    var filtersExpanded by rememberSaveable { mutableStateOf(false) }

    val activeLabels = remember(filters) {
        listOfNotNull(
            filters.genre,
            filters.year?.toString(),
            filters.type,
            filters.status,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.ui_action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.ui_action_search),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            SearchField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                autoFocus = true,
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val colors = MaterialTheme.colorScheme
                FilterChip(
                    selected = filtersExpanded,
                    onClick = { filtersExpanded = !filtersExpanded },
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
                            text = stringResource(R.string.ui_filters),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = colors.onSurfaceVariant,
                        iconColor = colors.onSurfaceVariant,
                        selectedContainerColor = colors.primary.copy(alpha = 0.16f),
                        selectedLabelColor = colors.primary,
                        selectedLeadingIconColor = colors.primary,
                    ),
                )
                if (activeLabels.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_filters_active, activeLabels.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (activeLabels.isNotEmpty()) {
                    TextButton(onClick = viewModel::resetFilters) {
                        Text(
                            text = stringResource(R.string.ui_filter_reset),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (filtersExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 268.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterChipRow(
                        label = stringResource(R.string.ui_filter_genre),
                        options = facets.genres.map { FilterOption(key = it, label = it) },
                        selectedKey = filters.genre,
                        onSelect = viewModel::selectGenre,
                    )
                    FilterChipRow(
                        label = stringResource(R.string.ui_filter_year),
                        options = facets.years.map { FilterOption(key = it.toString(), label = it.toString()) },
                        selectedKey = filters.year?.toString(),
                        onSelect = viewModel::selectYear,
                    )
                    FilterChipRow(
                        label = stringResource(R.string.ui_filter_type),
                        options = facets.types.map { FilterOption(key = it, label = it) },
                        selectedKey = filters.type,
                        onSelect = viewModel::selectType,
                    )
                    FilterChipRow(
                        label = stringResource(R.string.ui_filter_status),
                        options = facets.statuses.map { FilterOption(key = it, label = it) },
                        selectedKey = filters.status,
                        onSelect = viewModel::selectStatus,
                    )
                }
            } else {
                ActiveFilterSummary(
                    labels = activeLabels,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (val current = state) {
                    UiState.Loading -> LoadingState()
                    UiState.Empty -> if (catalogIsEmpty) {
                        EmptyState(
                            title = stringResource(R.string.ui_search_no_catalog_title),
                            body = stringResource(R.string.ui_search_no_catalog_body),
                            icon = Icons.Rounded.CloudOff,
                        )
                    } else {
                        EmptyState(
                            title = stringResource(R.string.ui_search_empty_title),
                            body = stringResource(R.string.ui_search_empty_body),
                            icon = Icons.Rounded.SearchOff,
                        )
                    }
                    is UiState.Error -> ErrorState(message = current.message)
                    is UiState.Content -> SearchResults(
                        model = current.data,
                        onOpenAnime = onOpenAnime,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    model: SearchUiModel,
    onOpenAnime: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(
            key = "result-count",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "count",
        ) {
            Text(
                text = stringResource(
                    if (model.truncated) R.string.ui_search_count_capped else R.string.ui_search_count,
                    model.results.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(
            items = model.results,
            key = { anime -> anime.id },
            contentType = { "poster" },
        ) { anime ->
            AnimeCard(
                anime = anime,
                onClick = rememberAnimeClick(anime.id, onOpenAnime),
            )
        }
    }
}
