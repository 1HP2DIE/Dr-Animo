package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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

    @Query("SELECT * FROM anime WHERE id = :id")
    fun observeById(id: Int): Flow<AnimeEntity?>

    @Query("SELECT * FROM anime WHERE id = :id")
    suspend fun findById(id: Int): AnimeEntity?

    /**
     * FTS4 query. `anime_fts.rowid` is the content-table rowid, i.e. `anime.id`, because the index is
     * declared with `content='anime'` (see [com.animo.app.data.local.entity.AnimeFtsEntity]).
     * [match] is pre-sanitised by [com.animo.app.data.local.FtsQuery] — never interpolate user text.
     */
    @Query(
        """
        SELECT a.* FROM anime AS a
        INNER JOIN anime_fts ON anime_fts.rowid = a.id
        WHERE anime_fts MATCH :match
          AND (:genre IS NULL OR a.genres LIKE '%' || :genre || '%')
          AND (:year IS NULL OR a.year = :year)
          AND (:type IS NULL OR a.type = :type)
          AND (:status IS NULL OR a.statusNorm = :status)
        ORDER BY a.updatedAtEpoch DESC, a.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeSearch(
        match: String,
        genre: String?,
        year: Int?,
        type: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<AnimeEntity>>

    /** Filter-only browse (blank query) — same ordering as [observeSearch] so paging stays stable. */
    @Query(
        """
        SELECT * FROM anime
        WHERE (:genre IS NULL OR genres LIKE '%' || :genre || '%')
          AND (:year IS NULL OR year = :year)
          AND (:type IS NULL OR type = :type)
          AND (:status IS NULL OR statusNorm = :status)
        ORDER BY updatedAtEpoch DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeFiltered(
        genre: String?,
        year: Int?,
        type: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<AnimeEntity>>

    /**
     * Continue watching: one row per series, ordered by its most recently touched episode. Grouping
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
