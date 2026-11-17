package com.animo.app.data.repo

import androidx.room.withTransaction
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.FtsQuery
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.local.dao.ProgressDao
import com.animo.app.data.local.entity.ProgressEntity
import com.animo.app.data.local.toDomain
import com.animo.app.data.remote.Normalize
import com.animo.app.data.sync.CatalogSyncer
import com.animo.app.data.sync.EpisodeBackfill
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.domain.repo.AnimeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Room is the source of truth; the UI never waits on the network. Every list here is a pure Room
 * Flow, so a screen renders instantly on a cold, offline start (ARCHITECTURE.md non-negotiable 5).
 *
 * The two network entry points ([refreshCatalog], [ensureEpisodes]) are the only suspension points
 * that can block, and both are triggered by explicit user or WorkManager intent.
 */
class AnimeRepositoryImpl(
    private val db: AnimoDatabase,
    private val animeDao: AnimeDao,
    private val episodeDao: EpisodeDao,
    private val progressDao: ProgressDao,
    private val catalogSyncer: CatalogSyncer,
    private val episodeBackfill: EpisodeBackfill,
) : AnimeRepository {

    private val _sync = MutableStateFlow(SyncProgress(0, 0, SyncState.IDLE))

    override val sync: Flow<SyncProgress> = _sync.asStateFlow()

    // ---------------------------------------------------------------- reads

    override fun observeRecent(limit: Int): Flow<List<Anime>> =
        animeDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun search(
        query: String,
        filters: Filters,
        limit: Int,
        offset: Int,
    ): Flow<List<Anime>> {
        val match = FtsQuery.build(query)
        val genre = filters.genre?.trim()?.takeIf { it.isNotEmpty() }
        val year = filters.year
        val type = filters.type?.trim()?.takeIf { it.isNotEmpty() }
        val status = filters.status?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(Normalize::normalizeStatus)
            ?.takeIf { it.isNotEmpty() }

        val rows = if (match != null) {
            animeDao.observeSearch(match, genre, year, type, status, limit, offset)
        } else {
            animeDao.observeFiltered(genre, year, type, status, limit, offset)
        }
        return rows.map { list -> list.map { it.toDomain() } }
    }

    override fun observeAnime(id: Int): Flow<Anime?> =
        animeDao.observeById(id).map { row -> row?.toDomain() }

    override fun observeEpisodes(animeId: Int): Flow<List<Episode>> =
        episodeDao.observeForAnime(animeId).map { rows -> rows.map { it.toDomain() } }

    override fun observeContinueWatching(limit: Int): Flow<List<Anime>> =
        animeDao.observeContinueWatching(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun catalogSize(): Int = animeDao.count()

    // ---------------------------------------------------------------- writes / network

    override suspend fun refreshCatalog(force: Boolean): Result<Unit> =
        catalogSyncer.sync(force) { progress -> _sync.value = progress }

    override suspend fun ensureEpisodes(animeId: Int): Result<Unit> =
        episodeBackfill.ensure(animeId)

    override suspend fun saveProgress(
        animeId: Int,
        episodeId: Long,
        positionMs: Long,
        durationMs: Long,
    ) {
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        progressDao.upsert(
            ProgressEntity(
                episodeId = episodeId,
                animeId = animeId,
                // Clamped so a bad player callback can never write a position past the end.
                positionMs = if (duration > 0L) position.coerceAtMost(duration) else position,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun progressFor(episodeId: Long): Long =
        progressDao.positionFor(episodeId) ?: 0L

    /** Exposed for the detail screen's resume row without leaking the DAO. */
    suspend fun latestProgressForAnime(animeId: Int): ProgressEntity? =
        progressDao.latestForAnime(animeId)

    /** Wipes the catalog (settings "clear cache"). Progress rows survive in their own table. */
    suspend fun clearCatalog() {
        db.withTransaction { animeDao.deleteAll() }
    }
}
