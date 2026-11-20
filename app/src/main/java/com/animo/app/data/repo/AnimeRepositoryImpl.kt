package com.animo.app.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.FilterSql
import com.animo.app.data.local.FtsQuery
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.local.dao.ProgressDao
import com.animo.app.data.local.dao.ProviderCount
import com.animo.app.data.local.entity.EpisodeEntity
import com.animo.app.data.local.entity.ProgressEntity
import com.animo.app.data.local.filterFacetsOf
import com.animo.app.data.local.toDomain
import com.animo.app.data.sync.CatalogSyncer
import com.animo.app.data.sync.EpisodeBackfill
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.CatalogueCounts
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.FilterFacets
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.Providers
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.ContinueWatchingItem
import com.animo.app.domain.repo.EpisodeProgress
import com.animo.app.domain.repo.HindiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * The movielinkbd catalogue: its own id space below Hindi, and the only thing that knows how to
     * turn a `movie/<slug>` page into episode rows. Lazy for the same reason as [hindiCatalog] — the
     * provider is registered after the container is built, and this repository is a process-wide
     * singleton that must see that registration.
     */
    private val movieLinkStream: () -> MovieLinkStreamRepository? = { null },
    /**
     * Stamps "Last sync" after a catalogue walk that actually finished.
     *
     * Kept as a lambda instead of a settings dependency: the repository has no Context, and the UI
     * layer is the only place that knows where the timestamp lives. Calling it here (rather than
     * from one screen) is what makes the value true for the worker and the drawer's Sync now too,
     * which previously only recorded it if Settings happened to be open.
     */
    private val recordSynced: suspend (Long) -> Unit = {},
) : AnimeRepository {

    private val _sync = MutableStateFlow(SyncProgress(0, 0, SyncState.IDLE))

    override val sync: Flow<SyncProgress> = _sync.asStateFlow()

    // ---------------------------------------------------------------- reads

    override fun observeRecent(limit: Int): Flow<List<Anime>> =
        animeDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecentOriginal(limit: Int): Flow<List<Anime>> =
        animeDao.observeRecentOriginal(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecentHindi(limit: Int): Flow<List<Anime>> =
        animeDao.observeRecentHindi(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeTopRated(limit: Int): Flow<List<Anime>> =
        animeDao.observeTopRated(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun counts(): CatalogueCounts = withContext(Dispatchers.IO) {
        CatalogueCounts(
            original = animeDao.countOriginal(),
            hindi = animeDao.countHindi(),
        )
    }

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
     *
     * The per-row projection answers five facets; language and provider are whole-table
     * vocabularies (one DISTINCT, one GROUP BY), so they are read per emission instead of being
     * folded into the projection — the same invalidation triggers both, so the sheet still cannot
     * go stale against the grid.
     */
    override fun observeFacets(): Flow<FilterFacets> =
        animeDao.observeFacetRows()
            .map { rows ->
                filterFacetsOf(rows).copy(
                    languages = animeDao.languagesPresent(),
                    providers = orderedProviders(animeDao.countsByProvider()),
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * Provider ids in [Providers.ALL] order, so the chips and the provider page never reorder
     * themselves between emissions. A provider the build does not know about yet is kept (and sorted
     * last) instead of being hidden, and a blank bucket — only reachable if a writer forgot the
     * column — is dropped rather than shown as an unnamed chip.
     */
    private fun orderedProviders(counts: List<ProviderCount>): List<String> {
        val present = counts.filter { it.count > 0 }.map { it.providerId }.toSet()
        val known = Providers.ALL.map { it.id }.filter { it in present }
        val unknown = present
            .filter { id -> id.isNotBlank() && Providers.spec(id) == null }
            .sorted()
        return known + unknown
    }

    override fun observeAnime(id: Int): Flow<Anime?> =
        animeDao.observeById(id).map { row -> row?.toDomain() }

    override fun observeEpisodes(animeId: Int): Flow<List<Episode>> =
        episodeDao.observeForAnime(animeId).map { rows -> rows.map { it.toDomain() } }

    override fun observeContinueWatching(limit: Int): Flow<List<Anime>> =
        // Derived from the richer query so both shelves share one ordering rule (most recently
        // touched episode first) instead of two SQL statements that could drift apart.
        observeContinueWatchingItems(limit).map { items -> items.map { it.anime } }

    override fun observeContinueWatchingItems(limit: Int): Flow<List<ContinueWatchingItem>> =
        progressDao.observeContinueWatching(limit).map { rows ->
            rows.map { row ->
                ContinueWatchingItem(
                    anime = row.anime.toDomain(),
                    episodeNumber = row.episodeNumber,
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                )
            }
        }

    /**
     * One query for the season, not one per cell: `progress` is indexed on `animeId`, so this stays
     * a single index scan however many episodes a title has, and Room re-runs it on every write to
     * the table (the player's `saveProgress`) without the screen being re-opened.
     */
    override fun observeEpisodeProgress(animeId: Int): Flow<Map<Long, EpisodeProgress>> =
        progressDao.observeForAnime(animeId).map { rows ->
            rows.associate { row ->
                row.episodeId to EpisodeProgress(
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                    updatedAtMs = row.updatedAtMs,
                )
            }
        }

    override suspend fun catalogSize(): Int = animeDao.count()

    // ---------------------------------------------------------------- writes / network

    override suspend fun refreshCatalog(force: Boolean): Result<Unit> {
        val result = catalogSyncer.sync(force) { progress -> _sync.value = progress }
        if (result.isSuccess) runCatching { recordSynced(System.currentTimeMillis()) }
        return result
    }

    override suspend fun ensureEpisodes(animeId: Int): Result<Unit> = when {
        // movielink titles are below the Hindi range and read their list from the provider page too.
        MovieLinkIds.isMovieLink(animeId) -> ensureMovieLinkEpisodes(animeId)
        // Hindi rows resolve their episode list from the provider page, not from the API.
        animeId < 0 -> ensureHindiEpisodes(animeId)
        else -> episodeBackfill.ensure(animeId)
    }

    /**
     * The movielink index has no episode API either: the list is the `#mlbdEpisodeDownloads` grid of the
     * title page, and each cell's `/p/<token>` is the player itself. A re-open is free once rows exist,
     * for the same reason as the Hindi branch — the page fetch is the expensive part and must not be
     * repeated just to draw a list.
     *
     * The rows are written with ids derived from `(animeId, number)` ([MovieLinkIds.episodeId]), so a
     * second sync upserts the same keys instead of appending a duplicate list.
     */
    private suspend fun ensureMovieLinkEpisodes(animeId: Int): Result<Unit> {
        val repository = movieLinkStream()
            ?: return Result.failure(IllegalStateException("MovieLink catalogue is not available"))
        if (episodeDao.countForAnime(animeId) > 0) return Result.success(Unit)

        val row = animeDao.findById(animeId)
            ?: return Result.failure(IllegalStateException("no MovieLink title with id $animeId"))

        return repository.episodes(animeId, row.originUrl).map { episodes ->
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
