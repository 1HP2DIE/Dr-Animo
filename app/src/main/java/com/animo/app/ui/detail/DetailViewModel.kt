package com.animo.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.EpisodeProgress
import com.animo.app.ui.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResumePoint(
    val episode: Episode,
    val positionMs: Long,
)

data class DetailUiModel(
    val anime: Anime,
    val episodes: List<Episode>,
    /** Saved resume point per episode id; empty when nothing was ever played. */
    val progress: Map<Long, EpisodeProgress> = emptyMap(),
    /** `Settings > Playback > Default audio`, before it is checked against what this title offers. */
    val preferredAudio: AudioTrack = AudioTrack.SUB,
    val resume: ResumePoint? = null,
    val isRefreshingEpisodes: Boolean = false,
    val episodesError: String? = null,
) {
    val hasSub: Boolean get() = episodes.any { !it.subUrl.isNullOrBlank() }
    val hasDub: Boolean get() = episodes.any { it.dubUrl != null }
    val firstEpisode: Episode? get() = episodes.minByOrNull { it.number }

    /**
     * The preference, coerced to a track this title actually carries.
     *
     * A `DUB` default must not light up the DUB chip on a sub-only series (and the player would then
     * be asked for a stream that does not exist), so the fallback chain is exactly the one
     * `pickAudio` uses per episode, applied once for the whole grid.
     */
    val initialAudio: AudioTrack
        get() = when {
            preferredAudio == AudioTrack.DUB && hasDub -> AudioTrack.DUB
            preferredAudio == AudioTrack.SUB && hasSub -> AudioTrack.SUB
            hasSub -> AudioTrack.SUB
            hasDub -> AudioTrack.DUB
            else -> preferredAudio
        }
}

/**
 * @param defaultAudio the persisted playback default. Passed as a flow (rather than read from a
 *   `Context` here) so the ViewModel stays framework-free and testable, the same way
 *   [com.animo.app.ui.settings.SettingsViewModel] gets its preferences.
 */
fun detailViewModelFactory(
    animeId: Int,
    repository: AnimeRepository,
    defaultAudio: Flow<AudioTrack> = flowOf(AudioTrack.SUB),
): ViewModelProvider.Factory = viewModelFactory {
    initializer { DetailViewModel(animeId, repository, defaultAudio) }
}

class DetailViewModel(
    private val animeId: Int,
    private val repository: AnimeRepository,
    defaultAudio: Flow<AudioTrack> = flowOf(AudioTrack.SUB),
) : ViewModel() {

    private data class EpisodeSyncFlags(
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val syncFlags = MutableStateFlow(EpisodeSyncFlags())

    /**
     * Four Room flows plus the preference. Progress arrives as one map for the whole season, so the
     * model below is built once per emission instead of once per episode cell — the grid can hold a
     * thousand cells and none of them may look anything up.
     */
    private val stream: Flow<UiState<DetailUiModel>> = combine(
        repository.observeAnime(animeId),
        repository.observeEpisodes(animeId),
        repository.observeEpisodeProgress(animeId),
        syncFlags,
        defaultAudio,
    ) { anime, episodes, progress, flags, audio ->
        if (anime == null) {
            UiState.Error(NOT_IN_CATALOG)
        } else {
            UiState.Content(
                DetailUiModel(
                    anime = anime,
                    episodes = episodes,
                    progress = progress,
                    preferredAudio = audio,
                    resume = resolveResume(episodes, progress),
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
     * "Last watched" is the most recently *written* row, not the highest episode number: a viewer who
     * rewatches E3 after finishing E9 must be offered E3.
     *
     * [MIN_RESUME_MS] filters rows the player wrote before the media settled (opening an episode and
     * backing out immediately) — those would otherwise become a permanent "Resume E1" on a title the
     * user has not actually watched. A row that exists but cannot describe progress is still a
     * legitimate resume point: the episode was played, we just do not know how far.
     */
    private fun resolveResume(
        episodes: List<Episode>,
        progress: Map<Long, EpisodeProgress>,
    ): ResumePoint? {
        if (progress.isEmpty()) return null
        val byId = episodes.associateBy { it.id }
        return progress.entries
            .asSequence()
            .filter { it.value.positionMs > MIN_RESUME_MS }
            .mapNotNull { entry ->
                byId[entry.key]?.let { episode -> episode to entry.value }
            }
            .maxByOrNull { (_, saved) -> saved.updatedAtMs }
            ?.let { (episode, saved) -> ResumePoint(episode = episode, positionMs = saved.positionMs) }
    }

    private companion object {
        const val MIN_RESUME_MS = 5_000L
        const val NOT_IN_CATALOG = "series not in the local catalog (not synced yet)"
    }
}
