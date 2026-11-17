package com.animo.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catalog row — column names and nullability mirror the DDL in research/DATA_CONTRACT.md §5
 * byte-for-byte so the schema can be diffed against the contract.
 *
 * `isDub` / `isSub` are episode **counts**, absent on 73.7% / 5.7% of rows, hence nullable.
 */
@Entity(
    tableName = "anime",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["slug"], unique = true),
        Index(value = ["updatedAtEpoch"]),
        Index(value = ["year"]),
        Index(value = ["statusNorm"]),
        Index(value = ["type"]),
        Index(value = ["score"]),
    ],
)
data class AnimeEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val alternative: String,
    val titles: String,
    val native: String,
    val slug: String,
    val rating: String,
    val ratingNorm: String,
    val poster: String,
    val isDub: Int?,
    val isSub: Int?,
    val description: String,
    val aired: String,
    val season: String,
    val seasonNorm: String,
    val year: Int?,
    val duration: String,
    val status: String,
    val statusNorm: String,
    val score: Double?,
    val malId: String?,
    val episodesDeclared: Int?,
    val episodeCount: Int,
    val aniId: String?,
    val source: String?,
    val sId: Long,
    val backgroundImage: String,
    val genres: String,
    val studios: String,
    val producers: String,
    val type: String,
    val updatedAt: String,
    val updatedAtEpoch: Long,
    val nextAirAt: Long?,
    val nextAirEp: Int?,
    val detailFetchedAt: Long?,
)
