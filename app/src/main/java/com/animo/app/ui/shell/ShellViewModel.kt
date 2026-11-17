package com.animo.app.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.FilterFacets
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.domain.repo.AnimeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the drawer header and the sync shortcut need. Everything else comes from the local DB. */
data class ShellUiState(
    val catalogueSize: Int = 0,
    val genres: List<String> = emptyList(),
    val sync: SyncProgress = SyncProgress(done = 0, total = 0, state = SyncState.IDLE),
) {
    val isSyncing: Boolean
        get() = sync.state == SyncState.RUNNING || sync.state == SyncState.THROTTLED

    /** Drawer shows five shortcuts, never the whole facet list. */
    val topGenres: List<String>
        get() = genres.take(5)
}

fun shellViewModelFactory(repository: AnimeRepository): ViewModelProvider.Factory =
    viewModelFactory { initializer { ShellViewModel(repository) } }

/**
 * Owns the app-shell chrome: catalogue count for the drawer header, the genre shortcuts and the
 * manual sync trigger. It never filters or loads lists itself — Browse does that.
 */
class ShellViewModel(private val repository: AnimeRepository) : ViewModel() {

    private val catalogueSize = MutableStateFlow(0)
    private val isSyncing = MutableStateFlow(false)

    val state: StateFlow<ShellUiState> = combine(
        repository.observeFacets(),
        repository.sync,
        catalogueSize,
    ) { facets: FilterFacets, sync, size ->
        ShellUiState(catalogueSize = size, genres = facets.genres, sync = sync)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShellUiState(),
    )

    init {
        viewModelScope.launch {
            refreshCatalogueSize()
            var wasSyncing = false
            repository.sync.collect { progress ->
                val syncing = progress.state == SyncState.RUNNING || progress.state == SyncState.THROTTLED
                if (wasSyncing && !syncing) refreshCatalogueSize()
                wasSyncing = syncing
            }
        }
    }

    fun syncNow() {
        if (isSyncing.value) return
        viewModelScope.launch {
            isSyncing.value = true
            repository.refreshCatalog(force = true)
            isSyncing.value = false
            refreshCatalogueSize()
        }
    }

    private suspend fun refreshCatalogueSize() {
        catalogueSize.value = runCatching { repository.catalogSize() }
            .getOrDefault(catalogueSize.value)
    }
}
