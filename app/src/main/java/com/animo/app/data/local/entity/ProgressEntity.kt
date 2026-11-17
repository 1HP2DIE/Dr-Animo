package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Resume point per episode — powers "continue watching" without a separate history table.
 * `updatedAt` is wall-clock epoch millis so the UI can show "3 days ago".
 */
@Entity(
    tableName = "progress",
    indices = [
        Index(value = ["animeId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class ProgressEntity(
    @PrimaryKey val episodeId: Long,
    val animeId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)
