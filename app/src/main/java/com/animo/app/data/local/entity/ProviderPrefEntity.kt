package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The one thing about a provider that is not compiled in: whether it may sync itself.
 *
 * Provider names, languages, content kinds and server lists are static metadata in
 * [com.animo.app.domain.model.Providers], so they need no table. Only the viewer's choice needs to
 * survive a restart, and one row per provider is the smallest thing that does that.
 *
 * A missing row means "never chosen", and the caller falls back to
 * [com.animo.app.domain.model.ProviderSpec.defaultSyncMode] — which is why this table stays empty
 * on a fresh install and why deleting a row is a reset rather than a delete.
 */
@Entity(tableName = "provider_pref")
data class ProviderPrefEntity(
    @PrimaryKey val provider: String,
    /** `AUTO` | `MANUAL` | `OFF` — see [com.animo.app.domain.model.SyncMode]. */
    val syncMode: String,
)
