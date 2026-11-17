package com.animo.app.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.FilterSql
import com.animo.app.data.local.FtsQuery
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.local.dao.ProgressDao
import com.animo.app.data.local.entity.EpisodeEntity
import com.animo.app.data.local.entity.ProgressEntity
import com.animo.app.data.local.filterFacetsOf
import com.animo.app.data.local.toDomain
import com.animo.app.data.sync.CatalogSyncer
import com.animo.app.data.sync.EpisodeBackfill
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.FilterFacets
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.HindiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
    /**
     * Read lazily, not captured: the Hindi layer is registered after the container is built, and this
     * repository is a process-wide singleton that must see that registration.
     */
    private val hindiCatalog: () -> HindiCatalog? = { null },
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
        // `null` means "nothing searchable in the box" and the statement then has no FTS clause at
        // all — an empty search lists the whole (filtered) catalogue instead of matching nothing.
        val statement = FilterSql.build(
            match = FtsQuery.build(query),
            filters = filters,
            limit = limit,
            offset = offset,
        )
        return animeDao
            .observeFiltered(SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()))
            .map { rows -> rows.map { it.toDomain() } }
    }

    /**
     * Built from the catalogue, never the network, so the filter sheet is populated on a cold,
     * offline start and can never offer a value that returns an empty screen. Recomputed per
     * emission (no cached copy that could outlive a sync) off the main thread.
     */
    override fun observeFacets(): Flow<FilterFacets> =
        animeDao.observeFacetRows()
            .map(::filterFacetsOf)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

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

    override suspend fun ensureEpisodes(animeId: Int): Result<Unit> = when {
        // Hindi rows resolve their episode list from the provider page, not from the API.
        animeId < 0 -> ensureHindiEpisodes(animeId)
        else -> episodeBackfill.ensure(animeId)
    }

    /**
     * There is no episode endpoint for a Hindi provider, so the list is parsed from the title page and
     * cached the same way. A re-open is free once rows exist, which matters because the resolve chain
     * (page -> embed -> decrypt) is the expensive part and must not be repeated just to draw a list.
     */
    private suspend fun ensureHindiEpisodes(animeId: Int): Result<Unit> {
        val catalog = hindiCatalog()
            ?: return Result.failure(IllegalStateException("Hindi catalogue is not available"))
        if (episodeDao.countForAnime(animeId) > 0) return Result.success(Unit)

        val row = animeDao.findById(animeId)
            ?: return Result.failure(IllegalStateException("no Hindi title with id $animeId"))

        return catalog.episodes(animeId, row.originUrl).map { episodes ->
            episodeDao.upsertAll(
                episodes.map { episode ->
                    EpisodeEntity(
                        episodeId = episode.id,
                        animeId = animeId,
                        number = episode.number,
                        title = episode.title,
                        jpTitle = "",
                        embedId = episode.embedId,
                        subUrl = episode.subUrl,
                        dubUrl = episode.dubUrl,
                        updatedAt = "",
                        updatedAtEpoch = episode.updatedAtEpoch,
                    )
                },
            )
            val fetchedAt = System.currentTimeMillis()
            animeDao.updateDetailMeta(animeId, episodes.size, fetchedAt)
        }
    }

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
