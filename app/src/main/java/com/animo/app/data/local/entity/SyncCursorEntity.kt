package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where a paged catalogue walk stopped, per provider.
 *
 * There is no `meta`/key-value table in this database and adding one for two integers would be a
 * second schema for the same purpose; a one-row-per-provider table is the smaller change and lets
 * each walker read and write its own row without touching anyone else's.
 *
 * [lastPage] is the **last page fully written**, not the next page to fetch: a run that is cancelled
 * between pages resumes at `lastPage + 1` and can never skip a page it did not commit.
 * [lastSyncEpoch] is epoch **seconds**, the unit the rest of the schema uses.
 */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val provider: String,
    val lastPage: Int,
    val lastSyncEpoch: Long,
)
