package com.animo.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.animo.app.data.local.entity.ProviderPrefEntity

/**
 * Per-provider sync preference. Read once by the sync worker (which provider may run) and once by
 * the provider page (what the toggle shows); written only when the viewer flips a toggle.
 */
@Dao
interface ProviderPrefDao {

    @Query("SELECT * FROM provider_pref")
    suspend fun all(): List<ProviderPrefEntity>

    @Query("SELECT * FROM provider_pref WHERE provider = :provider")
    suspend fun find(provider: String): ProviderPrefEntity?

    @Upsert
    suspend fun put(pref: ProviderPrefEntity)

    /** Reset one provider to its compiled-in default. */
    @Query("DELETE FROM provider_pref WHERE provider = :provider")
    suspend fun clear(provider: String)
}
