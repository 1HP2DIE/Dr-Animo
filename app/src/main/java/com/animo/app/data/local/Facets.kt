package com.animo.app.data.local

import com.animo.app.data.remote.Normalize
import com.animo.app.domain.model.FilterFacets

/**
 * The five columns the filter sheet is built from — one row per catalog title.
 *
 * A single projection (rather than a `DISTINCT` query per facet) because every facet is a function
 * of the same table: one scan, one invalidation reaction, and the CSV split happens in Kotlin where
 * [Normalize.splitCsv] already lives.
 */
data class FacetRow(
    val genres: String,
    val year: Int?,
    val type: String,
    val statusNorm: String,
    val titleNorm: String,
)

/**
 * Folds catalog rows into the facet lists the filter sheet renders.
 *
 * Everything here is derived from what is actually in the catalogue, so a facet can never offer a
 * value that returns zero rows. Sorting is part of the contract:
 *  - genres alphabetically (case-insensitive, first-seen casing wins so `Sci-Fi` stays `Sci-Fi`)
 *  - years newest first
 *  - letters ascending, so an A–Z strip renders left to right
 *
 * Statuses are translated to the three values [com.animo.app.domain.model.Filters.status] accepts —
 * raw API spellings never reach the UI.
 */
fun filterFacetsOf(rows: List<FacetRow>): FilterFacets {
    if (rows.isEmpty()) return FilterFacets()

    val genres = LinkedHashMap<String, String>()
    val years = LinkedHashSet<Int>()
    val types = LinkedHashSet<String>()
    val statuses = LinkedHashSet<String>()
    val letters = LinkedHashSet<Char>()

    for (row in rows) {
        for (genre in Normalize.splitCsv(row.genres)) {
            // The catalogue literally contains a genre called "unknown" — never offer it as a chip.
            if (genre.lowercase() == "unknown") continue
            genres.putIfAbsent(genre.lowercase(), genre)
        }
        row.year?.let(years::add)
        Normalize.normalizeType(row.type)?.let(types::add)
        statusLabel(row.statusNorm)?.let(statuses::add)
        row.titleNorm.firstOrNull()?.uppercaseChar()?.let(letters::add)
    }

    return FilterFacets(
        genres = genres.values.sortedBy { it.lowercase() },
        years = years.sortedDescending(),
        types = types.sorted(),
        statuses = statuses.sorted(),
        letters = letters.sorted(),
    )
}

/** Stored `statusNorm` -> the domain value, which is also what `Filters.status` round-trips through. */
private fun statusLabel(statusNorm: String): String? = when (statusNorm) {
    "currently_airing" -> "airing"
    "finished_airing" -> "finished"
    "not_yet_aired" -> "upcoming"
    else -> null
}
