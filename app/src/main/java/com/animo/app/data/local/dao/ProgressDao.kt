package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.animo.app.data.local.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE episodeId = :episodeId")
    suspend fun clear(episodeId: Long)

    @Query("DELETE FROM progress WHERE animeId = :animeId")
    suspend fun clearForAnime(animeId: Int)
}
