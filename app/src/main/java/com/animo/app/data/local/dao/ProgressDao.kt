package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * One "continue watching" card's worth of data: the series, plus the resume point of the episode
 * that was touched last.
 *
 * A single join projection rather than "series rows, then one progress read per card" because the
 * home rail draws ~20 cards on a cold start and a per-card lookup would turn the cheapest screen in
 * the app into an N+1.
 */
data class ContinueWatchingRow(
    @Embedded val anime: AnimeEntity,
    /** 0 when the episode list was never backfilled — the card then shows the percentage alone. */
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * One episode's resume point, without inflating the whole row.
 *
 * The detail screen draws a tick or a progress line on every episode cell, so it needs the whole
 * season at once. A per-cell `positionFor` call would be 1,000 round trips on a long-running
 * shounen — the exact N+1 the continue-watching query above was written to avoid.
 */
data class EpisodeProgressRow(
    val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long,
    /** Wall-clock of the last write — the only way to tell "last watched" from "highest number". */
    val updatedAtMs: Long,
)

@Dao
interface ProgressDao {

    @Upsert
    suspend fun upsert(entity: ProgressEntity)

    @Query("SELECT positionMs FROM progress WHERE episodeId = :episodeId")
    suspend fun positionFor(episodeId: Long): Long?

    @Query("SELECT * FROM progress WHERE episodeId = :episodeId")
    fun observe(episodeId: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE animeId = :animeId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForAnime(animeId: Int): ProgressEntity?

    /**
     * Every saved resume point of one series, for the detail grid's per-episode indicator.
     *
     * One statement for the whole season (indexed on `animeId`) and a Flow, so the ticks refresh
     * the moment the player writes a row instead of on the next screen open.
     */
    @Query(
        """
        SELECT episodeId, positionMs, durationMs, updatedAt AS updatedAtMs
        FROM progress
        WHERE animeId = :animeId
        """,
    )
    fun observeForAnime(animeId: Int): Flow<List<EpisodeProgressRow>>

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProgressEntity>>

    /**
     * The continue-watching rail in one statement.
     *
     * The inner `GROUP BY animeId` with a bare-column projection is SQLite's documented "row that
     * owns the MAX()" idiom: with exactly one aggregate in the select list, every other column is
     * taken from the row that produced it — so the episode, position and duration always come from
     * the same, latest progress row. Putting the aggregate in the select list (rather than only in
     * an `ORDER BY`) is what makes that guarantee apply; a window function
     * (`ROW_NUMBER() OVER (PARTITION BY animeId …)`) would read better but needs SQLite 3.25 and
     * minSdk here is 24, so it would crash on older devices.
     *
     * `episode` is a `LEFT JOIN`: a progress row can exist before the episode list was backfilled,
     * and such a series must still appear in the rail. `LIMIT` applies after grouping, so it caps
     * series, not progress rows.
     */
    @Query(
        """
        SELECT a.*,
               COALESCE(e.number, 0) AS episodeNumber,
               p.positionMs AS positionMs,
               p.durationMs AS durationMs
        FROM anime AS a
        INNER JOIN (
            SELECT animeId, episodeId, positionMs, durationMs, MAX(updatedAt) AS updatedAt
            FROM progress
            GROUP BY animeId
        ) AS p ON p.animeId = a.id
        LEFT JOIN episode AS e ON e.episodeId = p.episodeId
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(limit: Int): Flow<List<ContinueWatchingRow>>

    @Query("DELETE FROM progress WHERE episodeId = :episodeId")
    suspend fun clear(episodeId: Long)

    @Query("DELETE FROM progress WHERE animeId = :animeId")
    suspend fun clearForAnime(animeId: Int)
}
