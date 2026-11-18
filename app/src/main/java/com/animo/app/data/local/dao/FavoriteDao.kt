package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Upsert
    suspend fun upsert(item: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE animeId = :animeId")
    suspend fun delete(animeId: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE animeId = :animeId)")
    fun observeIsFavorite(animeId: Int): Flow<Boolean>

    @Query("SELECT animeId FROM favorite")
    fun observeIds(): Flow<List<Int>>

    /** Newest heart first. */
    @Query(
        """
        SELECT anime.* FROM favorite
        JOIN anime ON anime.id = favorite.animeId
        ORDER BY favorite.addedAtMs DESC
        """,
    )
    fun observeFavorites(): Flow<List<AnimeEntity>>

    /**
     * Titles that may have a new episode out: still airing, and their recorded air time has passed
     * since the last episode fetch. Favourites come first. Times: `nextAirAt` is epoch **seconds**,
     * `detailFetchedAt` epoch **ms**.
     */
    @Query(
        """
        SELECT anime.id FROM anime
        LEFT JOIN favorite ON favorite.animeId = anime.id
        WHERE anime.id > 0
          AND anime.statusNorm IN ('currently_airing','not_yet_aired')
          AND anime.nextAirAt IS NOT NULL
          AND anime.nextAirAt * 1000 <= :nowMs
          AND (anime.detailFetchedAt IS NULL OR anime.detailFetchedAt < anime.nextAirAt * 1000)
        ORDER BY favorite.animeId IS NULL, anime.nextAirAt DESC
        LIMIT :limit
        """,
    )
    suspend fun idsAiredSinceFetch(nowMs: Long, limit: Int): List<Int>

    /** Hearted titles that are not finished and were not fetched in the last [cutoffMs]. */
    @Query(
        """
        SELECT anime.id FROM favorite
        JOIN anime ON anime.id = favorite.animeId
        WHERE anime.id > 0
          AND anime.statusNorm != 'finished_airing'
          AND (anime.detailFetchedAt IS NULL OR anime.detailFetchedAt <= :cutoffMs)
        LIMIT :limit
        """,
    )
    suspend fun favoriteIdsDue(cutoffMs: Long, limit: Int): List<Int>

    /** Every airing title with a known air time, for the weekly schedule. */
    @Query(
        """
        SELECT * FROM anime
        WHERE statusNorm IN ('currently_airing','not_yet_aired')
          AND nextAirAt IS NOT NULL
        ORDER BY nextAirAt
        """,
    )
    fun observeAiring(): Flow<List<AnimeEntity>>
}
