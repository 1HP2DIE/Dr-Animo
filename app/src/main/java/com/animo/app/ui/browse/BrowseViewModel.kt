package com.animo.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.FilterFacets
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SortOrder
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.ui.UiState
import com.animo.app.ui.contentOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Rows fetched per "page". [BrowseViewModel] asks for `pages * PAGE_SIZE + 1` and trims the probe. */
internal const val BROWSE_PAGE_SIZE = 60

private const val SEARCH_DEBOUNCE_MS = 250L
private const val MAX_PAGES = 40
private const val MIN_YEAR = 1900
private const val MAX_YEAR = 2100

/** One page window over the catalog. [hasMore] is true when the probe row came back. */
data class BrowsePage(
    val items: List<Anime>,
    val hasMore: Boolean,
)

/** Number of user-visible filter parts; drives the "Filters (N)" label and the empty state. */
val Filters.activeFilterCount: Int
    get() = genres.size +
        (if (yearFrom != null || yearTo != null) 1 else 0) +
        (if (type != null) 1 else 0) +
        (if (status != null) 1 else 0) +
        (if (letter != null) 1 else 0) +
        (if (hasDub) 1 else 0) +
        (if (hindiOnly) 1 else 0)

val Filters.hasActiveFilters: Boolean
    get() = activeFilterCount > 0

fun browseViewModelFactory(repository: AnimeRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { BrowseViewModel(repository) }
}

/**
 * Browse = search + facets, both over Room, so every keystroke and chip tap re-subscribes a single
 * catalogue query instead of accumulating pages client-side.
 *
 * Paging note: the repository exposes `limit`/`offset`, but a *reactive* page stack has to merge
 * emissions that may arrive out of order. Growing the `limit` and always reading from offset 0 keeps
 * one authoritative emission: page N+1 is the same query with a wider window, so a catalogue update
 * can never leave a stale page stranded in the list.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class BrowseViewModel(private val repository: AnimeRepository) : ViewModel() {

    private data class BrowseRequest(
        val query: String,
        val filters: Filters,
        val pages: Int,
        val retry: Int,
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    private val pages = MutableStateFlow(1)
    private val retryToken = MutableStateFlow(0)

    private var initialApplied = false
    private var hindiApplied = false
    private var loadInFlight = false

    /** Catalog-derived facet values. An unimplemented/throwing source degrades to "no facets". */
    val facets: StateFlow<FilterFacets> = repository.observeFacets()
        .catch { emit(FilterFacets()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterFacets())

    /** Blank queries are not debounced: the first screen should paint on the first frame. */
    private val debouncedQuery: Flow<String> = _query
        .debounce { text -> if (text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .map { it.trim() }
        .distinctUntilChanged()

    private val requestStream: Flow<BrowseRequest> = combine(
        debouncedQuery,
        _filters,
        pages,
        retryToken,
    ) { query, filters, pageCount, retry ->
        BrowseRequest(query, filters, pageCount, retry)
    }.distinctUntilChanged()

    private val pageStream: Flow<UiState<BrowsePage>> = requestStream
        .flatMapLatest { request ->
            val visible = request.pages * BROWSE_PAGE_SIZE
            repository.search(
                query = request.query,
                filters = request.filters,
                limit = visible + 1,
                offset = 0,
            ).map { rows ->
                loadInFlight = false
                if (rows.isEmpty()) {
                    UiState.Empty
                } else {
                    UiState.Content(
                        BrowsePage(
                            items = rows.take(visible),
                            hasMore = rows.size > visible,
                        ),
                    )
                }
            }.catch { cause ->
                loadInFlight = false
                emit(
                    UiState.Error(
                        message = cause.message ?: cause::class.simpleName ?: "unknown",
                        cause = cause,
                    ),
                )
            }
        }

    private val pageState: StateFlow<UiState<BrowsePage>> = pageStream.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState.Loading,
    )

    /** What the grid renders. `Empty` here means "this query/filter combination matched nothing". */
    val results: StateFlow<UiState<List<Anime>>> = pageState
        .map { state -> state.toResults() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    val hasMore: StateFlow<Boolean> = pageState
        .map { state -> state.contentOrNull?.hasMore == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---------------------------------------------------------------- input

    fun onQueryChange(text: String) {
        if (text == _query.value) return
        resetPaging()
        _query.value = text
    }

    /**
     * Deep-link arguments (`browse?genre=Action&letter=A&hindi=true`) are applied on the first
     * composition only. Everything after that is user-driven.
     *
     * The Hindi flag is tracked separately from the genre/letter preset: a `browse?hindi=true` jump
     * from the drawer lands on the existing Browse destination, so the ViewModel is not necessarily
     * fresh even though the arguments are. Guarding it on its own keeps the source filter applied
     * exactly once per navigation without re-clobbering a genre the user has since changed.
     */
    fun applyInitial(genre: String?, letter: String?, hindi: Boolean = false) {
        if (hindi && !hindiApplied) {
            hindiApplied = true
            updateFilters { it.copy(hindiOnly = true) }
        }
        if (initialApplied) return
        initialApplied = true
        val initialGenre = genre?.trim()?.takeIf { it.isNotEmpty() }
        val initialLetter = normalizeLetter(letter)
        if (initialGenre == null && initialLetter == null) return
        updateFilters { current ->
            current.copy(
                genres = if (initialGenre != null) current.genres + initialGenre else current.genres,
                letter = initialLetter ?: current.letter,
            )
        }
    }

    fun toggleGenre(genre: String) = updateFilters { current ->
        if (genre in current.genres) {
            current.copy(genres = current.genres - genre)
        } else {
            current.copy(genres = current.genres + genre)
        }
    }

    fun setYearFrom(year: Int?) = updateFilters { it.copy(yearFrom = year) }

    fun setYearTo(year: Int?) = updateFilters { it.copy(yearTo = year) }

    fun setYearRange(from: Int?, to: Int?) = updateFilters { it.copy(yearFrom = from, yearTo = to) }

    fun setType(type: String?) = updateFilters { it.copy(type = type) }

    fun setStatus(status: String?) = updateFilters { it.copy(status = status) }

    /** Exact assignment — the rail and the sheet own the "tap again to clear" behaviour. */
    fun setLetter(letter: Char?) = updateFilters { it.copy(letter = letter) }

    fun setHasDub(enabled: Boolean) = updateFilters { it.copy(hasDub = enabled) }

    fun setHindiOnly(enabled: Boolean) = updateFilters { it.copy(hindiOnly = enabled) }

    fun setSort(sort: SortOrder) = updateFilters { it.copy(sort = sort) }

    fun applyFilters(next: Filters) = updateFilters { next }

    fun resetFilters() = updateFilters { Filters() }

    fun loadMore() {
        if (loadInFlight) return
        val page = pageState.value.contentOrNull ?: return
        if (!page.hasMore || pages.value >= MAX_PAGES) return
        loadInFlight = true
        pages.value += 1
    }

    fun retry() {
        resetPaging()
        retryToken.value += 1
    }

    // ---------------------------------------------------------------- internals

    private fun updateFilters(transform: (Filters) -> Filters) {
        val next = normalize(transform(_filters.value))
        if (next == _filters.value) return
        resetPaging()
        _filters.value = next
    }

    private fun resetPaging() {
        loadInFlight = false
        pages.value = 1
    }

    /** Keeps the year window coherent: bounds are clamped and swapped rather than rejected. */
    private fun normalize(filters: Filters): Filters {
        val from = filters.yearFrom?.coerceIn(MIN_YEAR, MAX_YEAR)
        val to = filters.yearTo?.coerceIn(MIN_YEAR, MAX_YEAR)
        return when {
            from != null && to != null && from > to -> filters.copy(yearFrom = to, yearTo = from)
            from != filters.yearFrom || to != filters.yearTo -> filters.copy(yearFrom = from, yearTo = to)
            else -> filters
        }
    }

    private fun normalizeLetter(raw: String?): Char? {
        val first = raw?.trim()?.firstOrNull() ?: return null
        return when {
            first.isLetter() -> first.uppercaseChar()
            first.isDigit() -> '#'
            first == '#' -> '#'
            else -> null
        }
    }

    private fun UiState<BrowsePage>.toResults(): UiState<List<Anime>> = when (this) {
        UiState.Loading -> UiState.Loading
        UiState.Empty -> UiState.Empty
        is UiState.Error -> this
        is UiState.Content -> UiState.Content(data.items)
    }
}
