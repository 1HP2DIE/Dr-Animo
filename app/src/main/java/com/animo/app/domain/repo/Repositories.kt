package com.animo.app.domain.repo

import com.animo.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface AnimeRepository {
    fun observeRecent(limit: Int = 60): Flow<List<Anime>>

    /**
     * Home shelves. Split by id sign so one provider's sync clock cannot bury the other:
     * Hindi rows are stamped with the sync time, which is always newer than an original's
     * `updatedAt`, so a single "recent" stream returned Hindi-only content.
     */
    fun observeRecentOriginal(limit: Int = 24): Flow<List<Anime>>
    fun observeRecentHindi(limit: Int = 20): Flow<List<Anime>>
    fun observeTopRated(limit: Int = 20): Flow<List<Anime>>
    suspend fun counts(): CatalogueCounts

    fun search(query: String, filters: Filters = Filters(), limit: Int = 60, offset: Int = 0): Flow<List<Anime>>
    /** Facet values for the filter sheet — derived from the local catalogue, never the network. */
    fun observeFacets(): Flow<FilterFacets>
    fun observeAnime(id: Int): Flow<Anime?>
    fun observeEpisodes(animeId: Int): Flow<List<Episode>>
    fun observeContinueWatching(limit: Int = 20): Flow<List<Anime>>
    val sync: Flow<SyncProgress>

    suspend fun catalogSize(): Int
    suspend fun refreshCatalog(force: Boolean = false): Result<Unit>
    suspend fun ensureEpisodes(animeId: Int): Result<Unit>
    suspend fun saveProgress(animeId: Int, episodeId: Long, positionMs: Long, durationMs: Long)
    suspend fun progressFor(episodeId: Long): Long
}

/**
 * The Hindi-dub catalogue. Kept out of [AnimeRepository] on purpose: it has its own providers, its
 * own rate budget and its own failure modes, and the UI must still work when every Hindi provider is
 * down.
 *
 * Rows are written into the same `anime` table with a **negative id** (see `Anime.isHindi`), so
 * browse, search, facets, history and the player all treat them as ordinary titles.
 */
interface HindiCatalog {
    /** Pulls both providers into Room. Returns the number of titles written. */
    suspend fun sync(force: Boolean = false, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result<Int>

    /** Episodes for one Hindi title, read from the provider page (there is no episode API). */
    suspend fun episodes(animeId: Int, originUrl: String): Result<List<Episode>>

    suspend fun catalogCount(): Int
}

interface StreamRepository {
    suspend fun resolve(animeId: Int, episode: Episode, audio: AudioTrack): Result<PlayableStream>
    suspend fun invalidate(episodeId: Long)
}
