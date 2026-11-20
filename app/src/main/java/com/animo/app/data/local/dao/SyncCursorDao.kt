package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.animo.app.data.local.entity.SyncCursorEntity

/**
 * Paged-walk cursors. Deliberately tiny: one row per provider, no history, no timestamps beyond the
 * one the caller wants. A missing row is "never synced", which is `lastPage = 0` for every caller.
 */
@Dao
interface SyncCursorDao {

    @Query("SELECT * FROM sync_cursor WHERE provider = :provider")
    suspend fun find(provider: String): SyncCursorEntity?

    @Upsert
    suspend fun put(cursor: SyncCursorEntity)
}
