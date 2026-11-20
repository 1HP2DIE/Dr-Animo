package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.animo.app.data.local.FacetRow
import com.animo.app.data.local.entity.AnimeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Projection used by the sync delta check — no full row inflation. `detailFetchedAt` and
 * `episodeCount` are carried because a catalog page upsert rewrites the whole row and must not
 * forget what the detail pass already learned.
 */
data class AnimeStamp(
    val id: Int,
    val updatedAtEpoch: Long,
    val detailFetchedAt: Long?,
    val episodeCount: Int,
    /** Last known air time (epoch seconds); kept when a later answer drops it. */
    val nextAirAt: Long? = null,
)

/** Projection for the sync policy — never inflates a whole row just to read four columns. */
data class DueRow(
    val statusNorm: String,
    val episodesDeclared: Int,
    val episodeCount: Int,
    val detailFetchedAt: Long?,
)

/**
 * Stored state of a paged-catalogue row, used by writers whose ids come from a hash.
 *
 * [slug] is here (and not in [AnimeStamp]) because it is the only column that can prove a hash
 * collision: two different slugs mapping onto one id are indistinguishable by id alone, and the
 * writer must not let the second one overwrite the first one's title.
 */
data class SlugStamp(
    val id: Int,
    val slug: String,
    val episodeCount: Int,
    val detailFetchedAt: Long?,
)

/** One row of the provider facet: how many catalogue rows a provider owns. See [AnimeDao.countsByProvider]. */
data class ProviderCount(
    val providerId: String,
    val count: Int,
)

@Dao
interface AnimeDao {

    // ---------------------------------------------------------------- reads

    @Query("SELECT * FROM anime ORDER BY updatedAtEpoch DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM anime WHERE id > 0 ORDER BY updatedAtEpoch DESC, id DESC LIMIT :limit")
    fun observeRecentOriginal(limit: Int): Flow<List<AnimeEntity>>

    /**
     * The Hindi rail: the two Hindi bands only. Not `id < 0` — MovieLink rows are negative as well
     * and belong to their own catalogue (`CatalogIds`).
     */
    @Query(
        "SELECT * FROM anime WHERE id BETWEEN -1999999999 AND -1 " +
            "ORDER BY updatedAtEpoch DESC, id DESC LIMIT :limit",
    )
    fun observeRecentHindi(limit: Int): Flow<List<AnimeEntity>>

    /** Highest rated originals; Hindi rows carry anitoko-incompatible scores, so they stay out. */
    @Query(
        "SELECT * FROM anime WHERE id > 0 AND score IS NOT NULL " +
            "ORDER BY score DESC, updatedAtEpoch DESC, id DESC LIMIT :limit",
    )
    fun observeTopRated(limit: Int): Flow<List<AnimeEntity>>

    @Query("SELECT COUNT(*) FROM anime WHERE id > 0")
    suspend fun countOriginal(): Int

    /** Hindi titles only: the two Hindi bands, so MovieLink rows are not counted as dub. */
    @Query("SELECT COUNT(*) FROM anime WHERE id BETWEEN :min AND :max")
    suspend fun countInBand(min: Int, max: Int): Int

    @Query("SELECT COUNT(*) FROM anime WHERE id BETWEEN -1999999999 AND -1")
    suspend fun countHindi(): Int

    @Query("SELECT * FROM anime WHERE id = :id")
    fun observeById(id: Int): Flow<AnimeEntity?>

    @Query("SELECT * FROM anime WHERE id = :id")
    suspend fun findById(id: Int): AnimeEntity?

    /**
     * The one browse/search entry point. The statement is built by
     * [com.animo.app.data.local.FilterSql] because a multi-genre filter needs a variable number of
     * `LIKE` predicates (AND semantics), which a `@Query` cannot express; every value is still a
     * bound argument, never interpolated. `observedEntities` keeps the returned flow live: any
     * write to `anime` re-runs the statement.
     */
    @RawQuery(observedEntities = [AnimeEntity::class])
    fun observeFiltered(query: SupportSQLiteQuery): Flow<List<AnimeEntity>>

    /**
     * Facet source. Deliberately one projection for all five facets — the filter sheet needs
     * genres, years, types, statuses and letters, and they all live on the same row.
     *
     * The language and provider facets are **not** derivable here: each is one vocabulary over the
     * whole table, with a row count the UI also renders, so folding them in Kotlin would mean
     * unzipping every title's owner on the UI thread. They get their own DISTINCT / GROUP BY below,
     * on the same invalidation path.
     */
    @Query("SELECT genres, year, type, statusNorm, titleNorm FROM anime")
    fun observeFacetRows(): Flow<List<FacetRow>>

    /** Languages actually stored; `NULL`/blank is "not stated", not a language a chip can offer. */
    @Query(
        "SELECT DISTINCT language FROM anime " +
            "WHERE language IS NOT NULL AND language != '' ORDER BY language",
    )
    suspend fun languagesPresent(): List<String>

    /** Rows per owning catalogue, for the provider facet and the provider page. */
    @Query("SELECT providerId, COUNT(*) AS count FROM anime GROUP BY providerId")
    suspend fun countsByProvider(): List<ProviderCount>

    /** How many rows one catalogue owns — the provider page's headline number. */
    @Query("SELECT COUNT(*) FROM anime WHERE providerId = :providerId")
    suspend fun countForProvider(providerId: String): Int

    /** Continue watching: one row per series, ordered by its most recently touched episode. Grouping
     * on `a.id` collapses multiple in-progress episodes of the same series into a single card.
     */
    @Query(
        """
        SELECT a.* FROM anime AS a
        INNER JOIN progress AS p ON p.animeId = a.id
        GROUP BY a.id
        ORDER BY MAX(p.updatedAt) DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(limit: Int): Flow<List<AnimeEntity>>

    @Query("SELECT COUNT(*) FROM anime")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM anime")
    fun observeCount(): Flow<Int>

    // ---------------------------------------------------------------- writes

    @Upsert
    suspend fun upsertAll(items: List<AnimeEntity>)

    @Upsert
    suspend fun upsert(item: AnimeEntity)

    // ---------------------------------------------------------------- sync support

    /** Non-empty [ids] only — an empty `IN ()` is invalid SQL. */
    @Query("SELECT id, updatedAtEpoch, detailFetchedAt, episodeCount, nextAirAt FROM anime WHERE id IN (:ids)")
    suspend fun stampsFor(ids: List<Int>): List<AnimeStamp>

    /**
     * [SlugStamp]s for the ids a hash-derived writer is about to upsert. Empty [ids] → empty list,
     * same rule as [stampsFor].
     */
    @Query("SELECT id, slug, episodeCount, detailFetchedAt FROM anime WHERE id IN (:ids)")
    suspend fun slugStampsFor(ids: List<Int>): List<SlugStamp>

    @Query("SELECT updatedAtEpoch FROM anime WHERE id = :id")
    suspend fun updatedAtEpochOf(id: Int): Long?

    @Query("SELECT episodeCount FROM anime WHERE id = :id")
    suspend fun episodeCountOf(id: Int): Int?

    @Query("SELECT detailFetchedAt FROM anime WHERE id = :id")
    suspend fun detailFetchedAtOf(id: Int): Long?

    @Query("UPDATE anime SET episodeCount = :count, detailFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateDetailMeta(id: Int, count: Int, fetchedAt: Long)

    /** Rows the nightly worker may still spend requests on (episode list never fetched). */
    @Query(
        """
        SELECT id FROM anime
        WHERE detailFetchedAt IS NULL
        ORDER BY updatedAtEpoch DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun idsAwaitingDetail(limit: Int): List<Int>

    /**
     * The nightly episode queue, ordered by [SyncPolicy.priority].
     *
     * Deliberately one statement: the alternative (load 9,404 rows, filter in Kotlin) is exactly the
     * kind of work that made the old sync slow. The `NOT (...)` clause is the "never again" rule —
     * a finished title whose stored episode count reached what the catalog declared is excluded
     * before the LIMIT, so it can never crowd out a title that actually changed.
     *
     * A finished title that was never detailed is deliberately **absent** from the first branch:
     * 8,967 rows are in that state and fetching them blind is 5.5 h of requests for episode lists
     * nobody has opened. They are fetched on demand instead.
     *
     * @param ongoingCutoff `now - ONGOING_INTERVAL_MS`
     * @param gapCutoff `now - GAP_INTERVAL_MS`
     * @param unknownCutoff `now - UNKNOWN_RECHECK_MS`
     * @param newRowCutoff `now - NEW_ROW_WINDOW_MS` — rows newer than this rank first
     */
    @Query(
        """
        SELECT id FROM anime
        WHERE id > 0
          AND NOT (
                statusNorm = 'finished_airing'
            AND detailFetchedAt IS NOT NULL
            AND episodesDeclared > 0
            AND episodeCount >= episodesDeclared
          )
          AND (
                (statusNorm IN ('currently_airing','not_yet_aired')
                 AND (detailFetchedAt IS NULL OR detailFetchedAt <= :ongoingCutoff))
             OR (statusNorm NOT IN ('currently_airing','not_yet_aired')
                 AND detailFetchedAt IS NOT NULL
                 AND detailFetchedAt <= CASE
                        WHEN episodesDeclared > episodeCount THEN :gapCutoff
                        ELSE :unknownCutoff
                     END)
          )
        ORDER BY
          CASE
            WHEN detailFetchedAt IS NULL AND updatedAtEpoch >= :newRowCutoff THEN 0
            WHEN episodesDeclared > episodeCount THEN 1
            WHEN statusNorm IN ('currently_airing','not_yet_aired') THEN 2
            ELSE 3
          END,
          score DESC,
          updatedAtEpoch DESC,
          id ASC
        LIMIT :limit
        """,
    )
    suspend fun idsDueForEpisodeSync(
        ongoingCutoff: Long,
        gapCutoff: Long,
        unknownCutoff: Long,
        newRowCutoff: Long,
        limit: Int,
    ): List<Int>

    /** Titles whose declared episode count is higher than what is stored — the visible "missing". */
    @Query(
        "SELECT COUNT(*) FROM anime WHERE id > 0 AND episodesDeclared > 0 AND episodeCount < episodesDeclared",
    )
    suspend fun countEpisodeGaps(): Int

    /** How many titles the queue would consider right now (cheap, for settings/telemetry). */
    @Query(
        """
        SELECT COUNT(*) FROM anime
        WHERE id > 0
          AND NOT (
                statusNorm = 'finished_airing'
            AND detailFetchedAt IS NOT NULL
            AND episodesDeclared > 0
            AND episodeCount >= episodesDeclared
          )
          AND (
                (statusNorm IN ('currently_airing','not_yet_aired')
                 AND (detailFetchedAt IS NULL OR detailFetchedAt <= :ongoingCutoff))
             OR (statusNorm NOT IN ('currently_airing','not_yet_aired')
                 AND detailFetchedAt IS NOT NULL
                 AND detailFetchedAt <= CASE
                        WHEN episodesDeclared > episodeCount THEN :gapCutoff
                        ELSE :unknownCutoff
                     END)
          )
        """,
    )
    suspend fun countDueForEpisodeSync(ongoingCutoff: Long, gapCutoff: Long, unknownCutoff: Long): Int

    /** Detail age for one row, used by the on-demand path to honour the same policy. */
    @Query(
        "SELECT statusNorm, episodesDeclared, episodeCount, detailFetchedAt FROM anime WHERE id = :id",
    )
    suspend fun syncStateOf(id: Int): DueRow?

    @Query("DELETE FROM anime")
    suspend fun deleteAll()
}
