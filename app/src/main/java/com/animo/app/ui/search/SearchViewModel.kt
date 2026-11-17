package com.animo.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Filters
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.ui.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Facet values discovered in the local catalog — never hardcoded. */
data class FilterFacets(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val types: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && years.isEmpty() && types.isEmpty() && statuses.isEmpty()

    companion object {
        val None = FilterFacets()
    }
}

data class SearchUiModel(
    val results: List<Anime>,
    val query: String,
    /** True when the repository returned exactly the page limit — the UI says "first N". */
    val truncated: Boolean,
)

fun searchViewModelFactory(repository: AnimeRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { SearchViewModel(repository) }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: AnimeRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    /**
     * Facets come from the catalog itself. The empty-query search is the "browse everything" path;
     * if a repository implementation treats `""` as "no match" we fall back to the recent feed, so
     * the chip rows are populated either way.
     */
    private val facetSource: Flow<List<Anime>> = combine(
        repository.search(query = "", filters = Filters(), limit = FACET_LIMIT),
        repository.observeRecent(FACET_LIMIT),
    ) { fromSearch, fromRecent ->
        if (fromSearch.isNotEmpty()) fromSearch else fromRecent
    }

    val facets: StateFlow<FilterFacets> = facetSource
        .map { rows -> facetsFrom(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterFacets.None)

    /** Drives the difference between "no results" and "nothing synced yet". */
    val catalogIsEmpty: StateFlow<Boolean> = facets
        .map { it.isEmpty }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val debouncedQuery: Flow<String> = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()

    private val resultStream: Flow<UiState<SearchUiModel>> = combine(
        debouncedQuery,
        _filters,
    ) { query, filters -> query to filters }
        .flatMapLatest { (query, filters) ->
            repository.search(query = query, filters = filters, limit = RESULT_LIMIT)
                .map { rows ->
                    if (rows.isEmpty()) {
                        UiState.Empty
                    } else {
                        UiState.Content(
                            SearchUiModel(
                                results = rows,
                                query = query,
                                truncated = rows.size >= RESULT_LIMIT,
                            ),
                        )
                    }
                }
        }

    val state: StateFlow<UiState<SearchUiModel>> = resultStream.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState.Loading,
    )

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun selectGenre(genre: String?) = _filters.update { it.copy(genre = genre) }

    fun selectYear(year: String?) = _filters.update { it.copy(year = year?.toIntOrNull()) }

    fun selectType(type: String?) = _filters.update { it.copy(type = type) }

    fun selectStatus(status: String?) = _filters.update { it.copy(status = status) }

    fun resetFilters() {
        _filters.value = Filters()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val RESULT_LIMIT = 120
        const val FACET_LIMIT = 400
        const val MAX_GENRE_OPTIONS = 28
        const val MAX_YEAR_OPTIONS = 24
    }

    private fun facetsFrom(rows: List<Anime>): FilterFacets {
        if (rows.isEmpty()) return FilterFacets.None
        val genres = rows.flatMap { it.genres }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .take(MAX_GENRE_OPTIONS)
        val years = rows.mapNotNull { it.year }
            .distinct()
            .sortedDescending()
            .take(MAX_YEAR_OPTIONS)
        val types = rows.map { it.type.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val statuses = rows.map { it.status.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        return FilterFacets(genres = genres, years = years, types = types, statuses = statuses)
    }
}
