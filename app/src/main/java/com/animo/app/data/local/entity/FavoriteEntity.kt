package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A title the viewer hearted. Kept apart from `anime` so a catalogue resync (which upserts whole
 * rows) can never clear it, and so a favourite survives even if its row is briefly missing.
 */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val animeId: Int,
    val addedAtMs: Long,
)
