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
)

@Dao
interface AnimeDao {

    // ---------------------------------------------------------------- reads

    @Query("SELECT * FROM anime ORDER BY updatedAtEpoch DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM anime WHERE id > 0 ORDER BY updatedAtEpoch DESC, id DESC LIMIT :limit")
    fun observeRecentOriginal(limit: Int): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM anime WHERE id < 0 ORDER BY updatedAtEpoch DESC, id DESC LIMIT :limit")
    fun observeRecentHindi(limit: Int): Flow<List<AnimeEntity>>

    /** Highest rated originals; Hindi rows carry anitoko-incompatible scores, so they stay out. */
    @Query(
        "SELECT * FROM anime WHERE id > 0 AND score IS NOT NULL " +
            "ORDER BY score DESC, updatedAtEpoch DESC, id DESC LIMIT :limit",
    )
    fun observeTopRated(limit: Int): Flow<List<AnimeEntity>>

    @Query("SELECT COUNT(*) FROM anime WHERE id > 0")
    suspend fun countOriginal(): Int

    @Query("SELECT COUNT(*) FROM anime WHERE id < 0")
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
     */
    @Query("SELECT genres, year, type, statusNorm, titleNorm FROM anime")
    fun observeFacetRows(): Flow<List<FacetRow>>

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
    @Query("SELECT id, updatedAtEpoch, detailFetchedAt, episodeCount FROM anime WHERE id IN (:ids)")
    suspend fun stampsFor(ids: List<Int>): List<AnimeStamp>

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

    @Query("DELETE FROM anime")
    suspend fun deleteAll()
}
