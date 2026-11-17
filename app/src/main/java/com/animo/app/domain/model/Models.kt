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
    /**
     * Provider page this row came from, for titles that are not in the original API (Hindi dub).
     * Blank for original-catalogue rows — everything they need is in the row itself.
     */
    val originUrl: String = "",
) {
    val hasSub: Boolean get() = subCount != null || episodeCount > 0
    val hasDub: Boolean get() = (dubCount ?: 0) > 0

    /**
     * Hindi-dub rows live in a **negative id space** so a single `anime` table (and therefore one
     * grid, one search, one set of filters) can hold both catalogues without a join or a flag that
     * could drift. The ranges are:
     *
     *   -1 … -999,999,999           toono.app
     *   -1,000,000,000 … -1,999,999,999  rareanimes.foo
     *
     * The original API's `s_id` tops out around 2.1e9, so the positive space can never collide.
     */
    val isHindi: Boolean get() = id < 0
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
    /** Multi-select genres. Empty = any. Matched case-insensitively against the CSV column. */
    val genres: Set<String> = emptySet(),
    /** Inclusive year window. Either bound may be null. */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** "TV" | "Movie" | "ONA" | "OVA" | "Special" | null = any. */
    val type: String? = null,
    /** Normalised status: "airing" | "finished" | "upcoming" | null = any. */
    val status: String? = null,
    /** A–Z browse index (first letter of the normalised title). */
    val letter: Char? = null,
    /** Only titles that actually have dub episodes. */
    val hasDub: Boolean = false,
    /** Only titles from a Hindi-dub provider (negative id space). */
    val hindiOnly: Boolean = false,
    val sort: SortOrder = SortOrder.RECENT,
)

enum class SortOrder { RECENT, TITLE_ASC, TITLE_DESC, YEAR_DESC, YEAR_ASC, SCORE_DESC }

/** Facet values for the filter sheet, computed from the local catalogue. */
data class FilterFacets(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val types: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val letters: List<Char> = emptyList(),
)

enum class SyncState { IDLE, RUNNING, THROTTLED, ERROR }

data class SyncProgress(val done: Int, val total: Int, val state: SyncState, val message: String = "")
