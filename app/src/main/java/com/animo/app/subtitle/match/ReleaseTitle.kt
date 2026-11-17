package com.animo.app.subtitle.match

import java.util.Locale

/**
 * What a release filename actually says, beyond its words: which season, which episodes, which
 * year, and whether it is a batch.
 *
 * Every pattern here exists because a real file name used it. Order matters — `S01E05` is checked
 * before the loose `- 05` form, and 4-digit resolutions can never be read as episode numbers.
 */
data class ReleaseTitle(
    val raw: String,
    val normalized: NormalizedTitle,
    val season: Int?,
    /** Inclusive episode range; `null` when the name does not say (a movie, or a batch with no range). */
    val episodes: IntRange?,
    val year: Int?,
    val version: Int?,
    val isBatch: Boolean,
) {
    fun coversEpisode(episode: Int): Boolean =
        episodes?.contains(episode) ?: false

    companion object {

        private val SXXEYYE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,4})(?:\s*[-~]\s*E?(\d{1,4}))?\b""")
        private val NXNN = Regex("""(?i)\b(\d{1,2})x(\d{2,4})(?:\s*[-~]\s*(\d{2,4}))?\b""")
        private val SEASON_EPISODE = Regex(
            """(?i)\bseason\s*0*(\d{1,2})\s*(?:-|:|episode|ep)\s*0*(\d{1,4})\b""",
        )
        private val DASH_EPISODE = Regex("""(?:-|_|\s-\s)\s*0*(\d{1,3})(?!\d)""")
        private val EP_WORD = Regex("""(?i)\bep(?:isode)?\s*\.?\s*0*(\d{1,4})\b""")
        private val RANGE_TAIL = Regex("""(?i)(?:\s-\s|\[|\(|\bE)(\d{1,4})\s*[-~]\s*(?:E)?(\d{1,4})\b""")
        private val YEAR = Regex("""\b(19[5-9]\d|20[0-4]\d)\b""")
        private val VERSION = Regex("""(?i)\bv(\d{1,2})\b""")
        private val BATCH_WORD = Regex("""(?i)\b(batch|complete|全集|end)\b""")

        private val SEASON_IN_TITLE = Regex("""(?i)\bseason\s*0*(\d{1,2})\b""")
        private val ORDINAL_SEASON = Regex("""(?i)\b0*(\d{1,2})(?:st|nd|rd|th)\s+season\b""")

        fun parse(raw: String): ReleaseTitle = parse(raw, TitleNormalizer.normalize(raw))

        fun parse(raw: String, normalized: NormalizedTitle): ReleaseTitle {
            // Resolution/quality tokens never carry an episode number.
            val work = raw.replace(Regex("""(?i)\b(480|540|720|1080|1440|2160)[pi]\b"""), " ")

            var season: Int? = null
            var episodes: IntRange? = null

            SXXEYYE.find(work)?.let { match ->
                season = match.groupValues[1].toIntOrNull()
                val from = match.groupValues[2].toIntOrNull()
                val to = match.groupValues[3].toIntOrNull() ?: from
                if (from != null && to != null) episodes = from..to
            }

            if (episodes == null) {
                NXNN.find(work)?.let { match ->
                    season = match.groupValues[1].toIntOrNull()
                    val from = match.groupValues[2].toIntOrNull()
                    val to = match.groupValues[3].toIntOrNull() ?: from
                    if (from != null && to != null) episodes = from..to
                }
            }

            if (episodes == null) {
                SEASON_EPISODE.find(work)?.let { match ->
                    season = match.groupValues[1].toIntOrNull()
                    match.groupValues[2].toIntOrNull()?.let { episodes = it..it }
                }
            }

            if (episodes == null) {
                RANGE_TAIL.find(work)?.let { match ->
                    val from = match.groupValues[1].toIntOrNull()
                    val to = match.groupValues[2].toIntOrNull()
                    if (from != null && to != null && from <= to) episodes = from..to
                }
            }

            if (episodes == null) {
                val single = DASH_EPISODE.find(work)?.groupValues?.get(1)?.toIntOrNull()
                    ?: EP_WORD.find(work)?.groupValues?.get(1)?.toIntOrNull()
                if (single != null) episodes = single..single
            }

            val year = YEAR.findAll(work)
                .mapNotNull { it.value.toIntOrNull() }
                // A year-looking token that is actually an episode range is not a year.
                .firstOrNull { candidate -> episodes?.let { candidate !in it } ?: true }

            return ReleaseTitle(
                raw = raw,
                normalized = normalized,
                season = season,
                episodes = episodes,
                year = year,
                version = VERSION.find(raw)?.groupValues?.get(1)?.toIntOrNull(),
                isBatch = BATCH_WORD.containsMatchIn(raw) ||
                    (episodes != null && episodes.last - episodes.first >= 1),
            )
        }

        /** The season a *title* names, if it names one ("Season 9", "3rd Season"). */
        fun seasonInTitle(titles: List<String>): Int? = titles.firstNotNullOfOrNull { title ->
            SEASON_IN_TITLE.find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: ORDINAL_SEASON.find(title)?.groupValues?.get(1)?.toIntOrNull()
        }

        /** Lower-cased language slug, for sources that print full names. */
        fun languageToken(value: String): String = value.lowercase(Locale.ROOT).trim()
    }
}
