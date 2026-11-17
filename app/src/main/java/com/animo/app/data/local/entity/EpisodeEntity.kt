package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Episode row. `episodeId` is the server id (globally unique in the 3,425-row sample).
 *
 * `(animeId, number)` is intentionally **not** unique: Soul Land returns two rows with number=263,
 * so the index is plain and ordering falls back to `episodeId` for a deterministic list.
 *
 * Never inserted with `OnConflictStrategy.REPLACE` on the parent: REPLACE is DELETE+INSERT and would
 * cascade-delete every episode of that anime.
 */
@Entity(
    tableName = "episode",
    foreignKeys = [
        ForeignKey(
            entity = AnimeEntity::class,
            parentColumns = ["id"],
            childColumns = ["animeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["animeId", "number"]),
        Index(value = ["animeId"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val episodeId: Long,
    val animeId: Int,
    val number: Int,
    val title: String,
    val jpTitle: String,
    val embedId: String,
    val subUrl: String?,
    val dubUrl: String?,
    val updatedAt: String,
    val updatedAtEpoch: Long,
)
