package com.animo.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.ui.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiModel(
    val recent: List<Anime> = emptyList(),
    val continueWatching: List<Anime> = emptyList(),
    val sync: SyncProgress = SyncProgress(done = 0, total = 0, state = SyncState.IDLE),
    val isRefreshing: Boolean = false,
) {
    /** Banner shows for the two "work in flight" states; IDLE/ERROR are silent here. */
    val showSyncBanner: Boolean
        get() = sync.state == SyncState.RUNNING || sync.state == SyncState.THROTTLED

    val isEmpty: Boolean
        get() = recent.isEmpty() && continueWatching.isEmpty()
}

fun homeViewModelFactory(repository: AnimeRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(repository) }
}

class HomeViewModel(private val repository: AnimeRepository) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    /** Technical causes only — the screen owns the user-facing copy. */
    private val _syncFailures = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val syncFailures: SharedFlow<String> = _syncFailures.asSharedFlow()

    private val stream: Flow<UiState<HomeUiModel>> = combine(
        repository.observeRecent(),
        repository.observeContinueWatching(),
        repository.sync,
        refreshing,
    ) { recent, continueWatching, sync, isRefreshing ->
        val model = HomeUiModel(
            recent = recent,
            continueWatching = continueWatching,
            sync = sync,
            isRefreshing = isRefreshing,
        )
        // Keep Content while a sync is running: the banner is meaningful with an empty list.
        if (model.isEmpty && !model.showSyncBanner && !isRefreshing) {
            UiState.Empty
        } else {
            UiState.Content(model)
        }
    }

    val state: StateFlow<UiState<HomeUiModel>> = stream.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState.Loading,
    )

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val result = repository.refreshCatalog(force = true)
            refreshing.value = false
            result.exceptionOrNull()?.let { error ->
                _syncFailures.tryEmit(error.message ?: error::class.simpleName ?: "unknown")
            }
        }
    }
}
