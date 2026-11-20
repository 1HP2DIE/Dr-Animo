package com.animo.app.domain.model

/**
 * What a catalogue row *is*, as opposed to [Anime.type] which is the anime format (`TV`, `ONA`,
 * `OVA`, `Special`).
 *
 * The app now carries several catalogues at once and they do not agree on shape: an anime source
 * knows `TV`/`Movie`/`OVA`, a film source knows only "this is a film", a series source knows only
 * "this has episodes". `type` therefore cannot answer "show me only films", and this enum is the
 * one axis every provider can be mapped onto.
 *
 * Deliberately three values. Drama, cartoon, documentary and live channels all collapse into
 * [SERIES] because they behave identically in the UI (episodes, seasons, resume) and a viewer
 * filtering by "Series" wants all of them. Keeping a `DRAMA` beside a `SERIES` would have produced
 * two chips that differ only in who wrote the metadata.
 */
enum class ContentKind(val label: String) {

    ANIME("Anime"),
    MOVIE("Movie"),
    SERIES("Series"),

    ;

    companion object {

        /** Every value, in the order the filter chips render. */
        val ALL: List<ContentKind> = entries

        /**
         * Parse a stored value. Unknown or blank means [ANIME] — the oldest catalogue and the one
         * every row written before this column existed belongs to.
         */
        fun fromStored(raw: String?): ContentKind = when (raw?.trim()?.uppercase()) {
            "MOVIE" -> MOVIE
            "SERIES" -> SERIES
            else -> ANIME
        }

        /**
         * Best guess for a row whose provider does not state a kind.
         *
         *  - a film source with one episode is a film, with more than one it is a series;
         *  - an anime-format `Movie` is a film, everything else (`TV`, `ONA`, `OVA`, `Special`) is
         *    an anime;
         *  - anything else falls back to [ANIME].
         */
        fun infer(providerId: String?, type: String?, episodeCount: Int): ContentKind = when {
            providerId == Providers.MOVIE_LINK -> if (episodeCount > 1) SERIES else MOVIE
            type?.equals("Movie", ignoreCase = true) == true -> MOVIE
            type.isNullOrBlank() -> ANIME
            else -> ANIME
        }
    }
}
