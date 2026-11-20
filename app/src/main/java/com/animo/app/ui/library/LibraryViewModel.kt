package com.animo.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.HistoryEntry
import com.animo.app.ui.UiState
import com.animo.app.ui.common.isPlayable
import com.animo.app.ui.common.pickAudio
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val LIBRARY_LIMIT = 200
private const val CONTINUE_RAIL_LIMIT = 8

/** History is a scroll, not a summary; the cap only stops an unbounded query. */
private const val HISTORY_LIMIT = 500

/** Cap on the per-series episode probe used to guess the resume point. */
private const val PROGRESS_SCAN_LIMIT = 600

data class LibraryUiModel(
    val continueWatching: List<Anime> = emptyList(),
    /** Every watched episode, newest first: two episodes of one series are two rows. */
    val history: List<HistoryEntry> = emptyList(),
    val watched: List<Anime> = emptyList(),
) {
    val isEmpty: Boolean get() = watched.isEmpty() && history.isEmpty()
}

/** Navigation the screen has to perform; resolved here because it needs suspend repository reads. */
sealed interface LibraryEvent {
    data class Resume(val animeId: Int, val episodeId: Long, val audio: AudioTrack) : LibraryEvent
    data class OpenDetail(val animeId: Int) : LibraryEvent
}

fun libraryViewModelFactory(repository: AnimeRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { LibraryViewModel(repository) }
}

/**
 * Library is read-only: it only observes progress.
 *
 * `observeContinueWatching` returns series rows ordered by their most recently touched episode, so
 * the same list serves both sections — the first few as the resume rail, the whole list as history.
 */
class LibraryViewModel(private val repository: AnimeRepository) : ViewModel() {

    private val _resolving = MutableStateFlow<Int?>(null)
    val resolving: StateFlow<Int?> = _resolving.asStateFlow()

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    val state: StateFlow<UiState<LibraryUiModel>> =
        combine(
            repository.observeContinueWatching(LIBRARY_LIMIT),
            repository.observeHistory(HISTORY_LIMIT),
        ) { rows, history ->
            if (rows.isEmpty() && history.isEmpty()) {
                UiState.Empty
            } else {
                UiState.Content(
                    LibraryUiModel(
                        continueWatching = rows.take(CONTINUE_RAIL_LIMIT),
                        history = history,
                        watched = rows,
                    ),
                )
            }
        }
            .catch { cause ->
                emit(UiState.Error(cause.message ?: cause::class.simpleName ?: "unknown", cause))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /**
     * Resolves "resume" lazily, on tap: the domain layer exposes progress per episode but not the
     * last-watched episode of a series, so the latest playable episode with a position is the best
     * available answer. Anything unresolved (no episodes backfilled yet, progress rows gone) falls
     * back to the detail screen, which owns the episode picker.
     */
    fun resume(anime: Anime) {
        if (_resolving.value != null) return
        _resolving.value = anime.id
        viewModelScope.launch {
            val target = runCatching { latestWatchedEpisode(anime.id) }.getOrNull()
            _resolving.value = null
            if (target == null) {
                _events.emit(LibraryEvent.OpenDetail(anime.id))
            } else {
                val (episode, audio) = target
                _events.emit(LibraryEvent.Resume(anime.id, episode.id, audio))
            }
        }
    }

    /**
     * Resume exactly the episode the row is about.
     *
     * The series-level [resume] has to guess (it walks the episode list looking for any position),
     * which is wrong for a history row: the viewer tapped "Episode 12", not "wherever I left this
     * show". The audio is taken from that episode, and a missing episode row falls back to the
     * detail screen rather than opening the wrong one.
     */
    fun resumeHistory(entry: HistoryEntry) {
        if (_resolving.value != null) return
        _resolving.value = entry.animeId
        viewModelScope.launch {
            // The row already names the episode, so nothing here may turn into "open the series and
            // guess": a lookup that misses must still resume the tapped episode, not drop the viewer
            // on the detail screen. The audio is the only thing read from the episode row, and its
            // absence only costs the DUB preference.
            val audio = runCatching {
                repository.observeEpisodes(entry.animeId).first()
                    .firstOrNull { it.id == entry.episodeId }
                    ?.pickAudio(AudioTrack.SUB)
            }.getOrNull() ?: AudioTrack.SUB
            _resolving.value = null
            _events.emit(LibraryEvent.Resume(entry.animeId, entry.episodeId, audio))
        }
    }

    fun openDetail(anime: Anime) {
        _events.tryEmit(LibraryEvent.OpenDetail(anime.id))
    }

    private suspend fun latestWatchedEpisode(animeId: Int): Pair<Episode, AudioTrack>? {
        val episodes = repository.observeEpisodes(animeId)
            .first()
            .filter { it.isPlayable() }
            .sortedByDescending { it.number }
            .take(PROGRESS_SCAN_LIMIT)

        for (episode in episodes) {
            if (repository.progressFor(episode.id) > 0L) {
                return episode to episode.pickAudio(AudioTrack.SUB)
            }
        }
        return null
    }
}
