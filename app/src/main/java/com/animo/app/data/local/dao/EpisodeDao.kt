package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.animo.app.data.local.entity.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episode WHERE animeId = :animeId ORDER BY number ASC, episodeId ASC")
    fun observeForAnime(animeId: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episode WHERE animeId = :animeId ORDER BY number ASC, episodeId ASC")
    suspend fun listForAnime(animeId: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episode WHERE episodeId = :episodeId")
    suspend fun findById(episodeId: Long): EpisodeEntity?

    @Query("SELECT COUNT(*) FROM episode WHERE animeId = :animeId")
    suspend fun countForAnime(animeId: Int): Int

    @Query("SELECT MAX(updatedAtEpoch) FROM episode WHERE animeId = :animeId")
    suspend fun maxUpdatedAtEpoch(animeId: Int): Long?

    @Upsert
    suspend fun upsertAll(items: List<EpisodeEntity>)

    @Upsert
    suspend fun upsert(item: EpisodeEntity)

    /**
     * Removes exactly the ids the diff produced. Deliberately not `NOT IN (:keep)`: a long-running
     * series has 1,220 episodes and SQLite's bound-variable ceiling is 999 on older platforms.
     * Only ever called with a non-empty list, and never on an empty server response (7/40 series
     * legitimately return `episodes: []`).
     */
    @Query("DELETE FROM episode WHERE episodeId IN (:episodeIds)")
    suspend fun deleteByIds(episodeIds: List<Long>)

    @Query("DELETE FROM episode WHERE animeId = :animeId")
    suspend fun deleteForAnime(animeId: Int)
}
