package com.animo.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.ui.UiState
import com.animo.app.ui.common.pickAudio
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PlayerUiModel(
    val anime: Anime,
    val episode: Episode,
    val episodes: List<Episode>,
    val index: Int,
    val audio: AudioTrack,
    val startPositionMs: Long,
    val reloadToken: Int,
    val errorMessage: String? = null,
) {
    val hasSub: Boolean get() = !episode.subUrl.isNullOrBlank()
    val hasDub: Boolean get() = !episode.dubUrl.isNullOrBlank()
    val canSwitchAudio: Boolean get() = hasSub && hasDub
    val hasNext: Boolean get() = index >= 0 && index < episodes.lastIndex
    val hasPrevious: Boolean get() = index > 0
    val nextEpisode: Episode? get() = episodes.getOrNull(index + 1)
    val previousEpisode: Episode? get() = episodes.getOrNull(index - 1)
}

fun playerViewModelFactory(
    animeId: Int,
    episodeId: Long,
    audio: AudioTrack,
    animeRepository: AnimeRepository,
    streamRepository: StreamRepository,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { PlayerScreenViewModel(animeId, episodeId, audio, animeRepository, streamRepository) }
}

class PlayerScreenViewModel(
    private val animeId: Int,
    private val requestedEpisodeId: Long,
    private val requestedAudio: AudioTrack,
    private val repository: AnimeRepository,
    private val streamRepository: StreamRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PlayerUiModel>>(UiState.Loading)
    val state: StateFlow<UiState<PlayerUiModel>> = _state.asStateFlow()

    private var anime: Anime? = null
    private var episodes: List<Episode> = emptyList()
    private var opening = false

    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastSavedAtMs = 0L

    init {
        viewModelScope.launch {
            combine(
                repository.observeAnime(animeId),
                repository.observeEpisodes(animeId),
            ) { anime, episodes -> anime to episodes }
                .collect { (loadedAnime, loadedEpisodes) ->
                    val sorted = loadedEpisodes.sortedBy { it.number }
                    episodes = sorted
                    if (loadedAnime == null) {
                        _state.value = UiState.Error(NOT_IN_CATALOG)
                        return@collect
                    }
                    anime = loadedAnime

                    val current = (_state.value as? UiState.Content)?.data
                    if (current == null) {
                        val target = sorted.firstOrNull { it.id == requestedEpisodeId } ?: sorted.firstOrNull()
                        if (target == null) {
                            _state.value = UiState.Error(NO_EPISODES)
                        } else {
                            open(target, requestedAudio, reloadToken = 0, startPositionMs = null)
                        }
                    } else {
                        // Room emitted a newer episode row (or the list grew): refresh in place
                        // without restarting playback.
                        val refreshed = sorted.firstOrNull { it.id == current.episode.id } ?: current.episode
                        val newIndex = sorted.indexOfFirst { it.id == refreshed.id }
                        if (refreshed != current.episode || newIndex != current.index || sorted != current.episodes) {
                            _state.value = UiState.Content(
                                current.copy(
                                    episode = refreshed,
                                    episodes = sorted,
                                    index = if (newIndex >= 0) newIndex else current.index,
                                ),
                            )
                        }
                    }
                }
        }
    }

    /** Resolves the stream for whatever is currently on screen. Called by the Media3 host. */
    suspend fun resolveStream(): Result<PlayableStream> {
        val current = (_state.value as? UiState.Content)?.data
            ?: return Result.failure(IllegalStateException("player is not ready"))
        return streamRepository.resolve(animeId, current.episode, current.audio)
    }

    fun next() {
        val current = current() ?: return
        current.nextEpisode?.let { open(it, current.audio, reloadToken = 0, startPositionMs = null) }
    }

    fun previous() {
        val current = current() ?: return
        current.previousEpisode?.let { open(it, current.audio, reloadToken = 0, startPositionMs = null) }
    }

    /**
     * Jump to another episode from the in-player drawer. Uses the resume position the repository
     * stored for that episode, and keeps the current audio preference.
     */
    fun selectEpisode(episode: Episode) {
        val current = current() ?: return
        if (episode.id == current.episode.id) return
        open(episode, current.audio, reloadToken = 0, startPositionMs = null)
    }

    fun selectAudio(audio: AudioTrack) {
        val current = current() ?: return
        if (audio == current.audio) return
        val available = when (audio) {
            AudioTrack.SUB -> current.hasSub
            AudioTrack.DUB -> current.hasDub
        }
        if (!available) return
        open(
            episode = current.episode,
            preferredAudio = audio,
            reloadToken = current.reloadToken + 1,
            startPositionMs = resumePosition(current.startPositionMs),
        )
    }

    fun toggleAudio() {
        val current = current() ?: return
        if (!current.canSwitchAudio) return
        selectAudio(if (current.audio == AudioTrack.SUB) AudioTrack.DUB else AudioTrack.SUB)
    }

    fun retry() {
        val current = current() ?: return
        viewModelScope.launch {
            streamRepository.invalidate(current.episode.id)
            _state.value = UiState.Content(
                current.copy(
                    errorMessage = null,
                    reloadToken = current.reloadToken + 1,
                    startPositionMs = resumePosition(current.startPositionMs),
                ),
            )
        }
    }

    fun onPlaybackError(error: Throwable) {
        val current = current() ?: return
        _state.value = UiState.Content(
            current.copy(errorMessage = error.message ?: error::class.simpleName ?: "playback error"),
        )
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
        lastPositionMs = positionMs
        lastDurationMs = durationMs
        if (durationMs <= 0L) return
        val current = current() ?: return
        val now = System.currentTimeMillis()
        if (now - lastSavedAtMs < SAVE_INTERVAL_MS) return
        lastSavedAtMs = now
        viewModelScope.launch {
            repository.saveProgress(animeId, current.episode.id, positionMs, durationMs)
        }
    }

    /** Called when the player leaves composition: persists the last known position even mid-tick. */
    fun flushProgress() {
        val current = current() ?: return
        val position = lastPositionMs
        val duration = lastDurationMs
        if (position <= 0L || duration <= 0L) return
        lastSavedAtMs = System.currentTimeMillis()
        viewModelScope.launch(NonCancellable) {
            repository.saveProgress(animeId, current.episode.id, position, duration)
        }
    }

    private fun current(): PlayerUiModel? = (_state.value as? UiState.Content)?.data

    private fun open(
        episode: Episode,
        preferredAudio: AudioTrack,
        reloadToken: Int,
        startPositionMs: Long?,
    ) {
        val loadedAnime = anime ?: return
        if (opening) return
        opening = true
        viewModelScope.launch {
            try {
                val position = startPositionMs ?: runCatching { repository.progressFor(episode.id) }
                    .getOrDefault(0L)
                val index = episodes.indexOfFirst { it.id == episode.id }
                _state.value = UiState.Content(
                    PlayerUiModel(
                        anime = loadedAnime,
                        episode = episode,
                        episodes = episodes,
                        index = if (index >= 0) index else 0,
                        audio = episode.pickAudio(preferredAudio),
                        startPositionMs = if (position > MIN_RESUME_MS) position else 0L,
                        reloadToken = reloadToken,
                    ),
                )
            } finally {
                opening = false
            }
        }
    }

    private fun resumePosition(fallback: Long): Long =
        if (lastPositionMs > MIN_RESUME_MS) lastPositionMs else fallback

    private companion object {
        const val MIN_RESUME_MS = 5_000L
        const val SAVE_INTERVAL_MS = 5_000L
        const val NOT_IN_CATALOG = "series not in the local catalog (not synced yet)"
        const val NO_EPISODES = "this series has no episodes yet"
    }
}
