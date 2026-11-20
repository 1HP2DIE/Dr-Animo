package com.animo.app.data.remote.provider.movielink

/**
 * Route families the index uses. The first path segment of a detail page is the only thing that
 * distinguishes a film from an episodic title, so it is carried through [MovieLinkCard.seriesId]
 * instead of being re-guessed from the JSON-LD `@type` later.
 */
enum class MovieLinkKind(val path: String) {
    MOVIE("movie"),
    SERIES("series"),
    DRAMA("drama"),
    ANIME("anime");

    companion object {
        fun ofPath(raw: String): MovieLinkKind? =
            entries.firstOrNull { it.path == raw.trim().lowercase() }
    }
}

/**
 * One listing or search card.
 *
 * [seriesId] is the **host-relative** page path (`"movie/jawan-2023"`), never an absolute URL: the
 * index rotates mirrors, so a stored absolute link keeps pointing at a dead host after the next
 * rotation. The provider rebuilds the URL from its own `indexHost`, which means a catalogue synced
 * from `wwwc4j` stays usable when the mirror moves to `wwwc5j`.
 *
 * [year] is `null` when the card did not print one; a guessed year would poison the year filter.
 */
data class MovieLinkCard(
    val seriesId: String,
    val title: String,
    val year: Int? = null,
    val poster: String = "",
    val kind: MovieLinkKind = MovieLinkKind.MOVIE,
    /** The `[Action,Thriller]` block from the image alt, split. Empty when the card carried none. */
    val tags: List<String> = emptyList(),
)

/**
 * One row of the `#mlbdEpisodeDownloads` grid.
 *
 * [link] is host-relative (`"/p/<token>"`) for the same rotation reason as [MovieLinkCard.seriesId].
 * [number] is the number printed in the button label; for a multi-season title the caller renumbers
 * positionally (see `MovieLinkProvider.episodes`) because two seasons legitimately both have an
 * "Ep 1" and [ProviderEpisode.number] is the only ordering key the model has.
 */
data class MovieLinkEpisode(
    val number: Int,
    val season: Int? = null,
    val label: String = "",
    val quality: String = "",
    val token: String? = null,
    val link: String = "",
)

/**
 * The JSON-LD block. Kept as its own type because it is the one part of the page that is *data*
 * rather than presentation: when the visible markup drifts, this survives longer, so the detail
 * parser uses it as a fallback for every field it also scrapes.
 */
data class MovieLinkStructured(
    val type: String = "",
    val name: String = "",
    val description: String = "",
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val poster: String = "",
    val cast: List<String> = emptyList(),
    val episodeCount: Int? = null,
)

/**
 * One detail page, flattened.
 *
 * Every string is already entity-decoded and whitespace-collapsed; `""` means "the page did not
 * publish it", which is deliberately indistinguishable from "the parse missed it" — the caller must
 * treat both as absent rather than showing a fake value.
 *
 * [playerUrl] is the `#mlbdHdFrame` source, host-relative. It is the page's default stream: for a
 * film that is the film, for a series it is whatever episode the page opened with.
 */
data class MovieLinkDetail(
    val seriesId: String,
    val kind: MovieLinkKind,
    val title: String,
    val year: Int? = null,
    val rating: Double? = null,
    val poster: String = "",
    val screenshots: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val language: String = "",
    val quality: String = "",
    val resolution: String = "",
    val released: String = "",
    val cast: List<String> = emptyList(),
    val storyline: String = "",
    val playerUrl: String = "",
    val episodes: List<MovieLinkEpisode> = emptyList(),
    /** Declared quality labels in page order, e.g. `["1080p", "720p"]`. */
    val versions: List<String> = emptyList(),
    val structured: MovieLinkStructured? = null,
) {
    /** True when the page published at least one playable entry. */
    val isPlayable: Boolean get() = playerUrl.isNotBlank() || episodes.any { it.link.isNotBlank() }
}

/**
 * One catalogue page plus whether the pager offered a next one.
 *
 * [hasNext] is read from the pager's `<a href="…/page/N+1">`, not from the advertised page count:
 * the index currently ends at page 529, and a sync that trusted a hardcoded total would either stop
 * early or spend a request learning that page 530 is a 404.
 */
data class MovieLinkPage(
    val page: Int,
    val cards: List<MovieLinkCard>,
    val hasNext: Boolean = false,
) {
    val isEmpty: Boolean get() = cards.isEmpty()
}
