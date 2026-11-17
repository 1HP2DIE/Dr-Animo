package com.animo.app.domain.model

/** Catalog row. All fields mirror the measured API contract — see research/DATA_CONTRACT.md. */
data class Anime(
    val id: Int,
    val title: String,
    val alternative: String = "",
    val titles: String = "",
    val native: String = "",
    val slug: String = "",
    val rating: String = "",
    val poster: String = "",
    val background: String = "",
    val description: String = "",
    val score: Double? = null,
    val year: Int? = null,
    val season: String = "unknown",
    val status: String = "",
    val type: String = "",
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val episodesDeclared: Int? = null,
    val episodeCount: Int = 0,
    val subCount: Int? = null,
    val dubCount: Int? = null,
    val malId: String? = null,
    val aniId: String? = null,
    val updatedAtEpoch: Long = 0L,
    val nextAirAt: Long? = null,
    val nextAirEp: Int? = null,
) {
    val hasSub: Boolean get() = subCount != null || episodeCount > 0
    val hasDub: Boolean get() = (dubCount ?: 0) > 0
}

data class Episode(
    val id: Long,
    val animeId: Int,
    val number: Int,
    val title: String = "",
    val embedId: String = "",
    val subUrl: String? = null,
    val dubUrl: String? = null,
    val updatedAtEpoch: Long = 0L,
) {
    fun hasAudio(audio: AudioTrack): Boolean = when (audio) {
        AudioTrack.SUB -> !subUrl.isNullOrBlank()
        AudioTrack.DUB -> !dubUrl.isNullOrBlank()
    }
}

enum class AudioTrack { SUB, DUB }

/** Resolved, playable stream. [referer] must be applied to playlist AND every segment request. */
data class PlayableStream(
    val masterUrl: String,
    val referer: String,
    val userAgent: String,
    val audio: AudioTrack,
    val thumbnails: String? = null,
    val intro: LongRange? = null,
    val outro: LongRange? = null,
)

data class Filters(
    val genre: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val status: String? = null,
)

enum class SyncState { IDLE, RUNNING, THROTTLED, ERROR }

data class SyncProgress(val done: Int, val total: Int, val state: SyncState, val message: String = "")
