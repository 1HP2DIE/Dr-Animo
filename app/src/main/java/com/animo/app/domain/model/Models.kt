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
    /**
     * Which catalogue owns this row — one of [Providers]. A column since v6 (the provider page counts
     * rows per provider); readers fall back to [Providers.idFor] when it is blank, so a row written
     * before the column existed still reports its real owner instead of an empty string.
     */
    val providerId: String = "",
    /** What the row *is* — the one axis every provider can be mapped onto. See [ContentKind]. */
    val contentKind: ContentKind = ContentKind.ANIME,
    /**
     * ISO-639-1 tag of the catalogue this row came from, or `null` when the provider serves several
     * languages and does not say which one a title is. A language filter never matches `null`.
     */
    val language: String? = null,
) {
    val hasSub: Boolean get() = subCount != null || episodeCount > 0
    val hasDub: Boolean get() = (dubCount ?: 0) > 0

    /** Alias for [contentKind], so a filter chip can read `anime.kind` without a wrapper. */
    val kind: ContentKind get() = contentKind

    /**
     * Hindi-dub rows live in a **negative id space** so a single `anime` table (and therefore one
     * grid, one search, one set of filters) can hold every catalogue without a join or a flag that
     * could drift. The ranges — and the one below them, which is *not* Hindi — are documented and
     * classified by [CatalogIds]:
     *
     *   -1 … -999,999,999               toono.app
     *   -1,000,000,000 … -1,999,999,999  rareanimes.foo
     *   -2,000,000,001 … -2,100,000,000  MovieLink (see [isMovieLink])
     *
     * The original API's `s_id` tops out around 2.1e9, so the positive space can never collide.
     * A bare `id < 0` test would have been true for the MovieLink band as well; the classifier is
     * therefore an explicit range check, not a sign test.
     */
    val isHindi: Boolean get() = CatalogIds.isHindi(id)

    /** True for MovieLink rows — a separate catalogue that shares the `anime` table. */
    val isMovieLink: Boolean get() = CatalogIds.isMovieLink(id)
}

/** Split of the local catalogue, used by the home hero and the drawer stats. */
data class CatalogueCounts(
    val original: Int = 0,
    val hindi: Int = 0,
) {
    val total: Int get() = original + hindi
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
    /** Side-car subtitle files the source publishes next to the video (fetched with [referer]). */
    val subtitles: List<StreamSubtitle> = emptyList(),
    /** WebVTT index of seek-preview sprites (fetched with [referer]); `null` when there is none. */
    val previewVtt: String? = null,
    /** HLS playlist, or a single progressive file (MP4/MKV). */
    val container: StreamContainer = StreamContainer.HLS,
    /**
     * Other renditions of the same episode as separate URLs, for sources whose playlist has no
     * variants (each quality is its own file/playlist). Empty when the playlist itself adapts.
     */
    val alternates: List<StreamAlternate> = emptyList(),
    /** Audio language to select inside a multi-audio file (`jpn`, `eng`…); `null` = file default. */
    val preferredAudioLanguage: String? = null,
    /**
     * The server that actually produced this stream. It differs from the viewer's pick when that
     * server failed and the next one in order answered, and the menu must show the truth.
     */
    val server: StreamServer? = null,
)

enum class StreamContainer { HLS, PROGRESSIVE }

/** One rendition of a stream at its own URL. [url] uses the stream's referer and user agent. */
data class StreamAlternate(
    val label: String,
    val height: Int,
    val url: String,
)

/** One subtitle file a source offers for a stream. */
data class StreamSubtitle(
    val url: String,
    val label: String,
    /** ISO-639 code when the source names one (`eng`, `en`, `por`…), else `null`. */
    val language: String? = null,
    /** The source's own pick for the default track. */
    val isDefault: Boolean = false,
    /** Signs-and-songs only — what a dub wants on by default. */
    val forced: Boolean = false,
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
    /** Multi-select catalogue kind. Empty = any. */
    val kinds: Set<ContentKind> = emptySet(),
    /** Multi-select ISO-639-1 catalogue language. Empty = any. Rows with no language never match. */
    val languages: Set<String> = emptySet(),
    /** Multi-select provider ids. Empty = any. */
    val providers: Set<String> = emptySet(),
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
    /** Languages actually present in the catalogue, so a chip can never filter to nothing. */
    val languages: List<String> = emptyList(),
    /** Providers actually present, ordered as [Providers.ALL]. */
    val providers: List<String> = emptyList(),
)

enum class SyncState { IDLE, RUNNING, THROTTLED, ERROR }

data class SyncProgress(val done: Int, val total: Int, val state: SyncState, val message: String = "")
