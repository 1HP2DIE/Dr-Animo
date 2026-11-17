package com.animo.app.domain.repo

import com.animo.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface AnimeRepository {
    fun observeRecent(limit: Int = 60): Flow<List<Anime>>
    fun search(query: String, filters: Filters = Filters(), limit: Int = 60, offset: Int = 0): Flow<List<Anime>>
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

interface StreamRepository {
    suspend fun resolve(animeId: Int, episode: Episode, audio: AudioTrack): Result<PlayableStream>
    suspend fun invalidate(episodeId: Long)
}
