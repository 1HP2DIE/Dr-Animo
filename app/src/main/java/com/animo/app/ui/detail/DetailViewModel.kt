package com.animo.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Episode
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.ui.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

data class ResumePoint(
    val episode: Episode,
    val positionMs: Long,
)

data class DetailUiModel(
    val anime: Anime,
    val episodes: List<Episode>,
    val resume: ResumePoint?,
    val isRefreshingEpisodes: Boolean = false,
    val episodesError: String? = null,
) {
    val hasSub: Boolean get() = episodes.any { !it.subUrl.isNullOrBlank() }
    val hasDub: Boolean get() = episodes.any { it.dubUrl != null }
    val firstEpisode: Episode? get() = episodes.minByOrNull { it.number }
}

fun detailViewModelFactory(
    animeId: Int,
    repository: AnimeRepository,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { DetailViewModel(animeId, repository) }
}

class DetailViewModel(
    private val animeId: Int,
    private val repository: AnimeRepository,
) : ViewModel() {

    private data class EpisodeSyncFlags(
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val syncFlags = MutableStateFlow(EpisodeSyncFlags())
    private val resumePositions = MutableStateFlow<Map<Long, Long>>(emptyMap())

    private val stream: Flow<UiState<DetailUiModel>> = combine(
        repository.observeAnime(animeId),
        repository.observeEpisodes(animeId),
        syncFlags,
        resumePositions,
    ) { anime, episodes, flags, positions ->
        if (anime == null) {
            UiState.Error(NOT_IN_CATALOG)
        } else {
            UiState.Content(
                DetailUiModel(
                    anime = anime,
                    episodes = episodes,
                    resume = resolveResume(episodes, positions),
                    isRefreshingEpisodes = flags.refreshing,
                    episodesError = flags.error,
                ),
            )
        }
    }

    val state: StateFlow<UiState<DetailUiModel>> = stream.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState.Loading,
    )

    init {
        // Episode backfill on detail open — the architecture's budgeted trigger.
        refreshEpisodes()
        viewModelScope.launch {
            repository.observeEpisodes(animeId).collectLatest { episodes ->
                loadResumePositions(episodes)
            }
        }
    }

    fun refreshEpisodes() {
        if (syncFlags.value.refreshing) return
        viewModelScope.launch {
            syncFlags.update { it.copy(refreshing = true, error = null) }
            val result = repository.ensureEpisodes(animeId)
            val failure = result.exceptionOrNull()
            syncFlags.update {
                it.copy(
                    refreshing = false,
                    error = failure?.let { error -> error.message ?: error::class.simpleName ?: "unknown" },
                )
            }
        }
    }

    /**
     * `saveProgress` is per episode and there is no "last watched" query, so we inspect the newest
     * episodes (bounded scan) and take the highest-numbered one with a real position. 24 reads at
     * most, all against Room.
     */
    private suspend fun loadResumePositions(episodes: List<Episode>) {
        if (episodes.isEmpty()) {
            if (resumePositions.value.isNotEmpty()) resumePositions.value = emptyMap()
            return
        }
        val newest = episodes.sortedByDescending { it.number }.take(RESUME_SCAN)
        val loaded = buildMap {
            for (episode in newest) {
                val position = repository.progressFor(episode.id)
                if (position > MIN_RESUME_MS) put(episode.id, position)
            }
        }
        if (resumePositions.value != loaded) {
            resumePositions.value = loaded
        }
    }

    private fun resolveResume(
        episodes: List<Episode>,
        positions: Map<Long, Long>,
    ): ResumePoint? {
        if (positions.isEmpty()) return null
        val episode = episodes
            .filter { (positions[it.id] ?: 0L) > MIN_RESUME_MS }
            .maxByOrNull { it.number }
            ?: return null
        return ResumePoint(episode = episode, positionMs = positions[episode.id] ?: 0L)
    }

    private companion object {
        const val MIN_RESUME_MS = 5_000L
        const val RESUME_SCAN = 24
        const val NOT_IN_CATALOG = "series not in the local catalog (not synced yet)"
    }
}
